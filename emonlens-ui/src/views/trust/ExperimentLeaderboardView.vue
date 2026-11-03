<template>
  <div class="base-page">
    <div class="head-stack">
      <div class="page-toolbar">
        <div class="toolbar-filters">
          <n-select v-model:value="datasetId" size="small" style="width: 200px" placeholder="选择数据集"
            :options="datasetOptions" @update:value="load" />
          <n-select v-model:value="windowDays" size="small" style="width: 130px" :options="windowOptions"
            @update:value="load" />
          <n-input-number v-model:value="minCoverage" size="small" :min="0" :max="100" style="width: 130px"
            @update:value="load" />
          <n-button size="small" @click="load">刷新</n-button>
        </div>
      </div>
      <div class="page-hint">
        排行口径：加权通过率 = 平均通过率 × 平均覆盖率；覆盖率低于阈值（默认 90%）的候选显式标「降权」并排在后面。
        散点横轴成本、纵轴通过率，点数按样本数。
      </div>
    </div>

    <n-card size="small" title="分数 × 成本散点">
      <template #header-extra>
        <span class="page-sub">共 {{ points.length }} 个 run（已过滤 {{ filteredOut }} 个）</span>
      </template>
      <div v-if="points.length" class="chart-wrap">
        <svg :width="chartWidth" :height="chartHeight" class="chart">
          <line :x1="pad" :y1="chartHeight - pad" :x2="chartWidth - pad" :y2="chartHeight - pad" class="axis" />
          <line :x1="pad" :y1="pad" :x2="pad" :y2="chartHeight - pad" class="axis" />
          <text :x="chartWidth - pad" :y="chartHeight - pad + 16" class="tick" text-anchor="end">成本</text>
          <text :x="pad - 6" :y="pad - 6" class="tick" text-anchor="start">通过率 %</text>
          <circle v-for="(p, i) in points" :key="i" :cx="cx(p)" :cy="cy(p)" :r="radius(p)"
            :class="p.priced ? 'dot' : 'dot unpriced'">
            <title>{{ tip(p) }}</title>
          </circle>
        </svg>
        <div class="legend">
          <span><i class="dot-legend"></i> 已计价</span>
          <span><i class="dot-legend unpriced"></i> 缺价目</span>
          <span>悬停查看 run 明细</span>
        </div>
      </div>
      <EmptyState v-else text="该数据集在窗口内没有完成的 run（或都被覆盖率过滤掉了）" />
    </n-card>

    <n-card size="small" class="mt12" title="Leaderboard（按加权通过率）">
      <n-data-table size="small" :bordered="false" :max-height="340" :columns="columns" :data="rows"
        :row-key="(r) => r.candidateFingerprint || r.candidate" />
    </n-card>
  </div>
</template>

<script setup>
import { computed, h, onMounted, ref } from 'vue'
import { NTag, useMessage } from 'naive-ui'
import api from '@/api'
import EmptyState from '@/components/EmptyState.vue'

const message = useMessage()
const datasets = ref([])
const datasetId = ref(null)
const windowDays = ref(30)
const minCoverage = ref(90)
const points = ref([])
const rows = ref([])
const filteredOut = ref(0)

const chartWidth = 640
const chartHeight = 260
const pad = 40

const windowOptions = [
  { label: '最近 7 天', value: 7 },
  { label: '最近 30 天', value: 30 },
  { label: '最近 90 天', value: 90 }
]
const datasetOptions = computed(() => datasets.value.map((d) => ({ label: d.datasetKey, value: d.id })))

const maxCost = computed(() => {
  const values = points.value.map((p) => (p.cost == null ? 0 : Number(p.cost)))
  const max = Math.max(0.0001, ...values)
  return max
})
const maxItems = computed(() => Math.max(1, ...points.value.map((p) => p.passCount + p.failCount + p.unknownCount)))

function cx(p) {
  const cost = p.cost == null ? 0 : Number(p.cost)
  return pad + (chartWidth - pad * 2) * (cost / maxCost.value)
}
function cy(p) {
  const rate = p.passRate == null ? 0 : Number(p.passRate)
  return chartHeight - pad - (chartHeight - pad * 2) * (rate / 100)
}
function radius(p) {
  const items = p.passCount + p.failCount + p.unknownCount
  return 4 + 8 * Math.sqrt(items / maxItems.value)
}
function tip(p) {
  const parts = [
    p.candidateLabel || p.runId,
    '通过率 ' + (p.passRate == null ? '—' : p.passRate + '%'),
    '覆盖 ' + (p.coverage == null ? '—' : p.coverage + '%'),
    '成本 ' + (p.priced ? Number(p.cost ?? 0).toFixed(4) : '不可计价'),
    '延迟 ' + (p.avgLatencyMs == null ? '—' : p.avgLatencyMs + 'ms')
  ]
  return parts.join(' · ')
}

const columns = [
  { title: '#', key: 'rank', width: 46, render: (r, index) => h('span', { class: 'mono' }, String(index + 1)) },
  { title: '候选', key: 'candidate', minWidth: 160,
    render: (r) => h('span', { class: 'mono' }, r.candidate || '—') },
  { title: '重复', key: 'runs', width: 70, align: 'right',
    render: (r) => h('span', { class: 'num' }, String(r.runs)) },
  { title: '加权通过率', key: 'adjustedPassRate', width: 120, align: 'right',
    render: (r) => h(NTag, { size: 'tiny', bordered: false, type: r.degraded ? 'warning' : 'success' },
      { default: () => (r.adjustedPassRate == null ? '—' : r.adjustedPassRate + '%') }) },
  { title: '通过率均值±σ', key: 'passRateMean', width: 140, align: 'right',
    render: (r) => h('span', { class: 'mono' },
      (r.passRateMean == null ? '—' : r.passRateMean + '%') + ' ± ' + (r.passRateStdDev ?? '—')) },
  { title: '覆盖率', key: 'coverageMean', width: 90, align: 'right',
    render: (r) => h('span', { class: 'mono' }, r.coverageMean == null ? '—' : r.coverageMean + '%') },
  { title: '成本', key: 'cost', width: 100, align: 'right',
    render: (r) => h('span', { class: 'mono' }, r.priced ? Number(r.cost ?? 0).toFixed(4) : '不可计价') },
  { title: '延迟', key: 'avgLatencyMs', width: 90, align: 'right',
    render: (r) => h('span', { class: 'mono' }, r.avgLatencyMs == null ? '—' : r.avgLatencyMs + 'ms') },
  { title: '降权', key: 'degraded', width: 90,
    render: (r) => (r.degraded
      ? h(NTag, { size: 'tiny', bordered: false, type: 'warning' }, { default: () => '覆盖率不足' })
      : h('span', { style: 'color:var(--text-muted)' }, '—')) },
  { title: 'prompt', key: 'promptVersion', width: 110,
    render: (r) => h('span', { class: 'mono' },
      r.promptName ? r.promptName + ' v' + (r.promptVersion ?? '?') : '—') }
]

async function load() {
  if (!datasetId.value) return
  try {
    const [scatter, board] = await Promise.all([
      api.datasetScatter(datasetId.value, { windowDays: windowDays.value, minCoverage: minCoverage.value }),
      api.datasetLeaderboard(datasetId.value, { windowDays: windowDays.value, minCoverage: minCoverage.value })
    ])
    points.value = scatter.points || []
    filteredOut.value = scatter.filteredOut || 0
    rows.value = board.rows || []
  } catch (e) { message.error(e.message) }
}

onMounted(async () => {
  try {
    datasets.value = (await api.datasets()) || []
    if (datasets.value.length) {
      datasetId.value = datasets.value[0].id
      await load()
    }
  } catch (e) { message.error(e.message) }
})
</script>

<style scoped>
.mt12 { margin-top: 12px; }
.mono { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; font-size: 11.5px; }
.num { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; }
.page-sub { font-size: 11.5px; color: var(--text-muted); }
.page-hint { font-size: 11.5px; color: var(--text-muted); margin-top: 6px; line-height: 1.6; }
.chart-wrap { display: flex; flex-direction: column; gap: 8px; }
.chart { background: var(--bg-subtle); border-radius: 6px; }
.axis { stroke: var(--border); stroke-width: 1; }
.tick { fill: var(--text-muted); font-size: 11px; }
.dot { fill: var(--primary); opacity: 0.75; }
.dot.unpriced { fill: var(--warn); opacity: 0.6; }
.legend { display: flex; gap: 16px; font-size: 11.5px; color: var(--text-muted); align-items: center; }
.dot-legend { display: inline-block; width: 9px; height: 9px; border-radius: 50%; background: var(--primary); margin-right: 4px; }
.dot-legend.unpriced { background: var(--warn); }
</style>
