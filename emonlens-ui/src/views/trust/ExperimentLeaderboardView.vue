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
        散点可自选横/纵轴（成本、通过率、覆盖率、延迟、未判定占比），点数按样本数。
      </div>
    </div>

    <n-card size="small" :title="`${METRICS[axisY].label} × ${METRICS[axisX].label} 散点`">
      <template #header-extra>
        <div class="axis-pickers">
          <span class="page-sub">横轴</span>
          <n-select v-model:value="axisX" size="small" style="width: 128px" :options="axisOptions" />
          <span class="page-sub">纵轴</span>
          <n-select v-model:value="axisY" size="small" style="width: 128px" :options="axisOptions" />
          <span class="page-sub">共 {{ points.length }} 个 run（已过滤 {{ filteredOut }} 个）</span>
        </div>
      </template>
      <div v-if="points.length" class="chart-wrap">
        <svg :width="chartWidth" :height="chartHeight" class="chart">
          <line :x1="pad" :y1="chartHeight - pad" :x2="chartWidth - pad" :y2="chartHeight - pad" class="axis" />
          <line :x1="pad" :y1="pad" :x2="pad" :y2="chartHeight - pad" class="axis" />
          <!-- 纵轴网格与刻度：没有刻度时，一条跨越半幅的置信区间看着像渲染错误，
               而它可能只是 n=1 的真实不确定性（1/1 的 95% CI 就是 21%~100%）。 -->
          <g v-for="t in yTicks" :key="'yt-' + t.ratio">
            <line :x1="pad" :y1="t.y" :x2="chartWidth - pad" :y2="t.y" class="grid" />
            <text :x="pad - 6" :y="t.y + 3" class="tick" text-anchor="end">{{ t.label }}</text>
          </g>
          <text :x="chartWidth - pad" :y="chartHeight - pad + 16" class="tick" text-anchor="end">{{ METRICS[axisX].label }}</text>
          <text :x="pad - 6" :y="pad - 12" class="tick" text-anchor="start">{{ METRICS[axisY].label }}</text>
          <g v-if="showCi">
            <g v-for="(p, i) in points" :key="'ci-' + i">
              <line v-if="ciOf(p)" :x1="cx(p)" :y1="ciY(p, ciOf(p)[0])" :x2="cx(p)" :y2="ciY(p, ciOf(p)[1])"
                class="ci-whisker" />
              <line v-if="ciOf(p)" :x1="cx(p) - 3" :y1="ciY(p, ciOf(p)[0])" :x2="cx(p) + 3" :y2="ciY(p, ciOf(p)[0])"
                class="ci-cap" />
              <line v-if="ciOf(p)" :x1="cx(p) - 3" :y1="ciY(p, ciOf(p)[1])" :x2="cx(p) + 3" :y2="ciY(p, ciOf(p)[1])"
                class="ci-cap" />
            </g>
          </g>
          <circle v-for="(p, i) in points" :key="i" :cx="cx(p)" :cy="cy(p)" :r="radius(p)"
            :class="p.priced ? 'dot' : 'dot unpriced'">
            <title>{{ tip(p) }}</title>
          </circle>
        </svg>
        <div class="legend">
          <span><i class="dot-legend"></i> 已计价</span>
          <span><i class="dot-legend unpriced"></i> 缺价目</span>
          <span v-if="showCi">误差线 = 通过率 95% 置信区间（Wilson，n 为已判定条数）</span>
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
import { computed, h, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NTag, useMessage } from 'naive-ui'
import api from '@/api'
import EmptyState from '@/components/EmptyState.vue'

const message = useMessage()
const route = useRoute()
const router = useRouter()
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

// run 的计数列可能缺失（历史 run / 未 enrich），一律按 0 兜底——否则半径会算成 NaN，
// 圆点渲染不出来（这个 bug 就是页面 E2E 抓到的）
function itemCount(p) {
  return (Number(p.passCount) || 0) + (Number(p.failCount) || 0) + (Number(p.unknownCount) || 0)
}
const maxItems = computed(() => Math.max(1, ...points.value.map(itemCount)))

// 散点轴：后端 scatter 已经把这些指标都返回了，之前只是写死"成本 × 通过率"。
// 每个指标给出取值与展示格式；坏数据一律兜底成 0，避免坐标算成 NaN（NaN 会让圆点直接不渲染）。
const METRICS = {
  cost: { label: '成本 $', value: (p) => (p.cost == null ? 0 : Number(p.cost) || 0), fmt: (v) => v.toFixed(4) },
  passRate: { label: '通过率 %', value: (p) => (p.passRate == null ? 0 : Number(p.passRate) || 0), fmt: (v) => v.toFixed(0) + '%' },
  coverage: { label: '覆盖率 %', value: (p) => (p.coverage == null ? 0 : Number(p.coverage) || 0), fmt: (v) => v.toFixed(0) + '%' },
  latency: { label: '平均延迟 ms', value: (p) => (p.avgLatencyMs == null ? 0 : Number(p.avgLatencyMs) || 0), fmt: (v) => v.toFixed(0) + 'ms' },
  unknownRate: {
    label: '未判定占比 %',
    value: (p) => {
      const n = itemCount(p)
      return n ? ((Number(p.unknownCount) || 0) * 100) / n : 0
    },
    fmt: (v) => v.toFixed(1) + '%'
  }
}
const axisX = ref('cost')
const axisY = ref('passRate')
const axisOptions = Object.entries(METRICS).map(([value, m]) => ({ label: m.label, value }))
function axisMax(key, pointsList) {
  const max = Math.max(0, ...pointsList.map((p) => METRICS[key].value(p)))
  return max > 0 ? max : 1
}
const maxX = computed(() => axisMax(axisX.value, points.value))
const maxY = computed(() => axisMax(axisY.value, points.value))

function cx(p) {
  return pad + (chartWidth - pad * 2) * (METRICS[axisX.value].value(p) / maxX.value)
}
function cy(p) {
  return chartHeight - pad - (chartHeight - pad * 2) * (METRICS[axisY.value].value(p) / maxY.value)
}
function radius(p) {
  return 4 + 8 * Math.sqrt(itemCount(p) / maxItems.value)
}
// 通过率的 95% 置信区间（Wilson，后端算）。散点只画点会让人把 n=1 的 100% 和 n=30 的 100% 当成一回事，
// 所以纵轴取通过率时补一条误差线，提示里也写明样本量。
function ciOf(p) {
  if (p.passRateLow == null || p.passRateHigh == null) return null
  return [Number(p.passRateLow), Number(p.passRateHigh)]
}
function ciY(p, value) {
  return chartHeight - pad - (chartHeight - pad * 2) * (value / maxY.value)
}
const showCi = computed(() => axisY.value === 'passRate')
// 纵轴刻度：5 等分，标签用当前指标自己的格式化函数（通过率带 %、成本带 4 位小数）
const yTicks = computed(() => {
  const metric = METRICS[axisY.value]
  return [0, 0.25, 0.5, 0.75, 1].map((ratio) => ({
    ratio,
    y: chartHeight - pad - (chartHeight - pad * 2) * ratio,
    label: metric.fmt(maxY.value * ratio)
  }))
})
function tip(p) {
  const ci = ciOf(p)
  const parts = [
    p.candidateLabel || p.runId,
    '通过率 ' + (p.passRate == null ? '—' : p.passRate + '%')
      + (ci ? `（95% CI ${ci[0]}–${ci[1]}%，n=${p.judgedCount ?? 0}）` : ''),
    '覆盖 ' + (p.coverage == null ? '—' : p.coverage + '%'),
    '成本 ' + (p.priced ? Number(p.cost ?? 0).toFixed(4) : '不可计价'),
    '延迟 ' + (p.avgLatencyMs == null ? '—' : p.avgLatencyMs + 'ms'),
    '未判定 ' + (itemCount(p) ? (((Number(p.unknownCount) || 0) * 100) / itemCount(p)).toFixed(1) + '%' : '—')
  ]
  return parts.join(' · ')
}

const columns = [
  { title: '#', key: 'rank', width: 46, render: (r, index) => h('span', { class: 'mono' }, String(index + 1)) },
  // 候选是长指纹：没有 ellipsis 会溢到右边的「重复」列上（截图里能直接看到压字）
  { title: '候选', key: 'candidate', minWidth: 160, ellipsis: { tooltip: true },
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

// 支持 ?datasetId= 深链：报表可以直接分享/收藏，E2E 也用它做确定性入口
const queryDatasetId = computed(() => (route.query.datasetId ? String(route.query.datasetId) : ''))

onMounted(async () => {
  try {
    datasets.value = (await api.datasets()) || []
    const wanted = queryDatasetId.value
    const hit = wanted ? datasets.value.find((d) => d.id === wanted) : null
    if (hit) {
      datasetId.value = hit.id
    } else if (datasets.value.length) {
      datasetId.value = datasets.value[0].id
    }
    if (datasetId.value) {
      await load()
    }
  } catch (e) { message.error(e.message) }
})

// 顶部切换数据集时同步到 URL，刷新后仍停在同一份报表
watch(datasetId, (value) => {
  if (!value || queryDatasetId.value === value) return
  router.replace({ query: { ...route.query, datasetId: value } })
})
</script>

<style scoped>
.mt12 { margin-top: 12px; }
.mono { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; font-size: 11.5px; }
.num { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; }
.page-sub { font-size: 11.5px; color: var(--text-muted); }
.page-hint { font-size: 11.5px; color: var(--text-muted); margin-top: 6px; line-height: 1.6; }
.axis-pickers { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.chart-wrap { display: flex; flex-direction: column; gap: 8px; }
.chart { background: var(--bg-subtle); border-radius: 6px; }
.axis { stroke: var(--border); stroke-width: 1; }
.grid { stroke: var(--border); stroke-width: 1; opacity: 0.35; stroke-dasharray: 3 3; }
.tick { fill: var(--text-muted); font-size: 11px; }
.dot { fill: var(--primary); opacity: 0.75; }
.dot.unpriced { fill: var(--warn); opacity: 0.6; }
.ci-whisker { stroke: var(--text-muted); stroke-width: 1; opacity: 0.55; }
.ci-cap { stroke: var(--text-muted); stroke-width: 1; opacity: 0.55; }
.legend { display: flex; gap: 16px; font-size: 11.5px; color: var(--text-muted); align-items: center; }
.dot-legend { display: inline-block; width: 9px; height: 9px; border-radius: 50%; background: var(--primary); margin-right: 4px; }
.dot-legend.unpriced { background: var(--warn); }
</style>
