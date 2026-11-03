<template>
  <div class="base-page">
    <div class="head-stack">
      <div class="page-toolbar">
        <div class="toolbar-filters">
          <n-select v-model:value="windowHours" size="small" style="width: 140px" :options="windowOptions"
            @update:value="load" />
          <n-input-number v-model:value="staleDays" size="small" :min="1" :max="3650" style="width: 130px" />
          <n-button size="small" @click="load">刷新</n-button>
        </div>
        <div class="toolbar-actions">
          <n-button size="small" type="primary" ghost :loading="scanning" @click="scan">
            扫描并写入告警
          </n-button>
        </div>
      </div>
      <div class="page-hint">
        回答两个问题：哪个评估器最不可信（盲审一致率 / UNKNOWN 率 / 成本）、哪个数据集在腐烂（陈旧样本占比 + revision 年龄）。
        「扫描并写入告警」会把结论写成 Finding，交给既有的 Incident 与通知链路（24 小时内同一对象只报一次）。
      </div>
    </div>

    <n-card v-if="leastTrusted" size="small" title="最不可信的评估器">
      <div class="verdict-row">
        <n-tag size="small" :bordered="false" :type="verdictType(leastTrusted.verdict)">{{ leastTrusted.verdict }}</n-tag>
        <span class="mono strong">{{ leastTrusted.evaluatorKey }} v{{ leastTrusted.evaluatorVersion }}</span>
        <span class="page-sub">一致率 {{ fmtPct(leastTrusted.agreementRate) }} · UNKNOWN {{ fmtPct(leastTrusted.unknownRate) }}
          · 未执行 {{ fmtPct(leastTrusted.notExecutedRate) }} · 成本 {{ fmtCost(leastTrusted) }}</span>
      </div>
      <ul class="advice">
        <li v-for="(a, i) in leastTrusted.advice" :key="i">{{ a }}</li>
      </ul>
    </n-card>

    <n-card size="small" class="mt12" title="评估器质量（按不可信程度排序）">
      <n-data-table size="small" :bordered="false" :max-height="360" :columns="evaluatorColumns"
        :data="report?.evaluators || []" :row-key="(r) => r.evaluatorKey + '@' + r.evaluatorVersion" />
    </n-card>

    <n-card size="small" class="mt12" title="数据集健康（陈旧样本 / revision 年龄）">
      <n-data-table size="small" :bordered="false" :max-height="300" :columns="datasetColumns"
        :data="report?.datasets || []" :row-key="(r) => r.datasetId" />
    </n-card>

    <n-card size="small" class="mt12" title="缓存与告警">
      <div class="cache-line">
        <span>窗口内缓存条目 <b class="num">{{ report?.cache?.entries ?? 0 }}</b></span>
        <span>节省输入 token <b class="num">{{ report?.cache?.promptTokensSaved ?? 0 }}</b></span>
        <span>节省输出 token <b class="num">{{ report?.cache?.completionTokensSaved ?? 0 }}</b></span>
        <span class="page-sub">{{ report?.cache?.note }}</span>
      </div>
      <div v-if="(report?.alerts || []).length" class="alert-list">
        <div v-for="(a, i) in report.alerts" :key="i" class="alert-row">
          <n-tag size="tiny" :bordered="false" :type="a.kind === 'DATASET_ROT' ? 'warning' : 'error'">{{ a.kind }}</n-tag>
          <span class="mono">{{ a.target }}</span>
          <span class="page-sub">{{ a.detail }}</span>
        </div>
      </div>
      <EmptyState v-else text="当前窗口没有质量告警：所有评估器与数据集都还在可接受范围内" />
    </n-card>
  </div>
</template>

<script setup>
import { computed, h, onMounted, ref } from 'vue'
import { NTag, useMessage } from 'naive-ui'
import api from '@/api'
import EmptyState from '@/components/EmptyState.vue'

const message = useMessage()
const report = ref(null)
const windowHours = ref(168)
const staleDays = ref(90)
const scanning = ref(false)

const windowOptions = [
  { label: '最近 24 小时', value: 24 },
  { label: '最近 7 天', value: 168 },
  { label: '最近 30 天', value: 720 }
]
const leastTrusted = computed(() => report.value?.leastTrusted || null)

function verdictType(verdict) {
  if (verdict === 'OK' || verdict === 'HEALTHY') return 'success'
  if (verdict === 'LOW_TRUST' || verdict === 'ROTTING') return 'error'
  return 'warning'
}
function fmtPct(v) { return v == null ? '—' : v + '%' }
function fmtCost(row) {
  if (!row.priced) return '不可计价'
  return row.cost == null ? '—' : Number(row.cost).toFixed(4)
}

const evaluatorColumns = [
  { title: '评估器', key: 'evaluatorKey', minWidth: 160,
    render: (r) => h('span', { class: 'mono' }, r.evaluatorKey + ' v' + r.evaluatorVersion) },
  { title: '类型', key: 'type', width: 110 },
  { title: '判定', key: 'verdict', width: 110,
    render: (r) => h(NTag, { size: 'tiny', bordered: false, type: verdictType(r.verdict) },
      { default: () => r.verdict }) },
  { title: '评估数', key: 'evalCount', width: 80, align: 'right' },
  { title: '通过率', key: 'passRate', width: 80, align: 'right', render: (r) => fmtPct(r.passRate) },
  { title: 'UNKNOWN', key: 'unknownRate', width: 90, align: 'right', render: (r) => fmtPct(r.unknownRate) },
  { title: '未执行', key: 'notExecutedRate', width: 80, align: 'right', render: (r) => fmtPct(r.notExecutedRate) },
  { title: '盲审一致率', key: 'agreementRate', width: 100, align: 'right',
    render: (r) => r.reviewedCount
      ? h('span', { class: 'mono' }, fmtPct(r.agreementRate) + ` (${r.reviewedCount})`)
      : h('span', { style: 'color:var(--text-muted)' }, '未盲审') },
  { title: '成本', key: 'cost', width: 100, align: 'right', render: (r) => fmtCost(r) },
  { title: '建议', key: 'advice', minWidth: 260,
    render: (r) => h('span', { style: 'font-size:11.5px;color:var(--text-secondary)' },
      (r.advice || []).join('；') || '—') }
]

const datasetColumns = [
  { title: '数据集', key: 'datasetKey', minWidth: 160, render: (r) => h('span', { class: 'mono' }, r.datasetKey) },
  { title: '判定', key: 'verdict', width: 110,
    render: (r) => h(NTag, { size: 'tiny', bordered: false, type: verdictType(r.verdict) },
      { default: () => r.verdict }) },
  { title: '活跃样本', key: 'activeItems', width: 90, align: 'right' },
  { title: '陈旧样本', key: 'staleItems', width: 90, align: 'right' },
  { title: '陈旧占比', key: 'staleShare', width: 90, align: 'right', render: (r) => fmtPct(r.staleShare) },
  { title: '最新 revision', key: 'latestRevision', width: 110, align: 'right',
    render: (r) => r.latestRevision == null ? '未冻结' : 'r' + r.latestRevision },
  { title: 'revision 年龄', key: 'revisionAgeDays', width: 120, align: 'right',
    render: (r) => r.revisionAgeDays == null ? '—' : r.revisionAgeDays + ' 天' },
  { title: '蓝图', key: 'blueprintConfigured', width: 80,
    render: (r) => r.blueprintConfigured ? '已配置' : '未配置' },
  { title: '建议', key: 'advice', minWidth: 260,
    render: (r) => h('span', { style: 'font-size:11.5px;color:var(--text-secondary)' },
      (r.advice || []).join('；') || '—') }
]

async function load() {
  try {
    report.value = await api.evaluatorQuality({ windowHours: windowHours.value, staleDays: staleDays.value })
  } catch (e) { message.error(e.message) }
}
async function scan() {
  scanning.value = true
  try {
    const result = await api.scanEvaluatorQuality()
    message.success('已写入 ' + (result.findingsCreated ?? 0) + ' 条告警（24 小时内同一对象只报一次）')
    await load()
  } catch (e) { message.error(e.message) }
  finally { scanning.value = false }
}

onMounted(load)
</script>

<style scoped>
.mt12 { margin-top: 12px; }
.mono { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; font-size: 11.5px; }
.strong { font-weight: 600; font-size: 12.5px; }
.num { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; }
.page-hint { font-size: 11.5px; color: var(--text-muted); margin-top: 6px; line-height: 1.6; }
.verdict-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.advice { margin: 8px 0 0; padding-left: 18px; font-size: 12px; color: var(--text-secondary); line-height: 1.7; }
.cache-line { display: flex; gap: 20px; flex-wrap: wrap; font-size: 12.5px; color: var(--text-secondary); }
.alert-list { margin-top: 10px; display: flex; flex-direction: column; gap: 6px; }
.alert-row { display: flex; align-items: center; gap: 10px; font-size: 12px; }
</style>
