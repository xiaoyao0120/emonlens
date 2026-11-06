<template>
  <div class="base-page eval-page">
    <div class="head-stack">
      <div class="page-toolbar">
        <div class="toolbar-filters">
          <n-select v-model:value="labelFilter" placeholder="全部标签" size="small" style="width: 150px" clearable
            :options="labelOptions" @update:value="search" />
          <n-select v-model:value="evaluatorFilter" placeholder="全部评估器" size="small" style="width: 200px" clearable
            :options="evaluatorOptions" @update:value="search" />
          <n-input v-model:value="traceFilter" placeholder="按 traceId 过滤" size="small" style="width: 220px" clearable @clear="search" />
        </div>
        <div class="toolbar-actions">
          <n-button size="small" @click="$router.push('/datasets')">去数据集看实验</n-button>
          <n-button size="small" :loading="exporting" @click="exportCsv">导出 CSV</n-button>
          <n-button size="small" @click="$router.push('/annotation-queues')">标注队列</n-button>
          <lan-button v-if="canRun" type="primary" @click="openRun">手动补评</lan-button>
        </div>
      </div>
    </div>

    <!-- 统计条 -->
    <div class="stat-strip">
      <div class="s-item">
        <span class="s-v">{{ stats.total }}</span><span class="s-k">总评估</span>
      </div>
      <div class="s-item">
        <span class="s-v" style="color:var(--success)">{{ stats.pass }}</span><span class="s-k">通过</span>
      </div>
      <div class="s-item">
        <span class="s-v" style="color:var(--error)">{{ stats.fail }}</span><span class="s-k">未通过</span>
      </div>
      <div class="s-item">
        <span class="s-v" style="color:var(--warn)">{{ stats.other }}</span><span class="s-k">不适用 / 不可用</span>
      </div>
      <div class="s-item pass-rate">
        <span class="s-v" :style="{ color: stats.rate >= 80 ? 'var(--success)' : stats.rate >= 50 ? 'var(--warn)' : 'var(--error)' }">{{ stats.rate }}%</span>
        <span class="s-k">通过率</span>
      </div>
    </div>

    <!-- 通过率趋势（近 14 天，对标 Langfuse score trend） -->
    <div v-if="trend.length" class="trend-strip">
      <div v-for="d in trend" :key="d.day" class="trend-col" :title="d.day + '：通过 ' + d.pass + ' / 未通过 ' + d.fail">
        <div class="trend-bars">
          <i class="tb-pass" :style="{ height: d.passH + '%' }"></i>
          <i class="tb-fail" :style="{ height: d.failH + '%' }"></i>
        </div>
        <span class="trend-day">{{ d.label }}</span>
      </div>
      <div class="trend-legend">
        <span><i class="dot" style="background:var(--success)"></i>通过</span>
        <span><i class="dot" style="background:var(--error)"></i>未通过</span>
        <span class="page-sub">近 14 天逐日</span>
      </div>
    </div>

    <n-card size="small" class="mt12 eval-table-card">
      <n-data-table class="main-table" flex-height style="height: 100%" :scroll-x="1480"
        :columns="columns" :data="records" size="small" :bordered="false" remote
        :pagination="pagination" :loading="loading"
        :row-props="(row) => ({ style: 'cursor:pointer', onClick: () => openResult(row) })" />
    </n-card>

    <!-- 评估器概览：每个评估器的通过率/样本/最近评估（横向条形） -->
    <n-card v-if="overview.length" size="small" class="mt12" title="评估器概览（全量统计）">
      <div class="ov-list">
        <div v-for="o in overview" :key="o.evaluatorKey" class="ov-row"
          :style="{ cursor: 'pointer' }" title="点击筛选该评估器" @click="evaluatorFilter = o.evaluatorKey; search()">
          <span class="ov-name mono">{{ o.evaluatorKey }}</span>
          <div class="ov-bar">
            <i class="ov-pass" :style="{ width: ovPassPct(o) + '%' }"></i>
            <i class="ov-fail" :style="{ width: ovFailPct(o) + '%' }"></i>
          </div>
          <span class="ov-rate num" :style="{ color: rateColor(o.passRate) }">{{ o.passRate == null ? '—' : o.passRate + '%' }}</span>
          <span class="ov-meta num">{{ o.pass }}/{{ o.fail }}/{{ o.total }}</span>
          <span class="ov-meta">{{ fmtTs(o.lastEvaluatedAt) }}</span>
        </div>
      </div>
    </n-card>

    <Modal v-model:show="showRun" :width="560" title="手动补评">
      <n-form label-placement="top">
        <n-form-item label="评估器" required>
          <n-select v-model:value="runForm.evaluatorId" placeholder="选择评估器" :options="runEvaluatorOptions" />
        </n-form-item>
        <n-form-item label="快捷填充（最近完成的 Trace，可多选）">
          <n-select v-model:value="quickPicks" multiple filterable clearable :options="recentTraces"
            placeholder="从最近完成的 Trace 勾选，自动并入下方列表" @update:value="syncQuick" />
        </n-form-item>
        <n-form-item label="Trace ID（每行一个）" required>
          <n-input v-model:value="runForm.traceIds" type="textarea" :rows="4" class="mono" placeholder="trace-8f3a2b&#10;trace-1c9d04" />
        </n-form-item>
      </n-form>
      <template #footer>
        <lan-button :disabled="running" @click="showRun = false">取消</lan-button>
        <lan-button type="primary" :loading="running" @click="doRun">执行</lan-button>
      </template>
    </Modal>
    <!-- 评估结果详情 -->
    <n-drawer v-model:show="showResult" :width="560">
      <n-drawer-content title="评估结果详情" closable>
        <template v-if="resultDetail">
          <div style="display:flex;align-items:center;gap:10px;margin-bottom:14px">
            <span class="num" :style="{ fontSize: '30px', fontWeight: 700, color: resultDetail.label === 'PASS' ? 'var(--success)' : resultDetail.label === 'FAIL' ? 'var(--error)' : 'var(--text)' }">{{ resultDetail.score == null ? '—' : resultDetail.score }}</span>
            <n-tag size="small" :bordered="false" :type="resultDetail.label === 'PASS' ? 'success' : resultDetail.label === 'FAIL' ? 'error' : 'warning'">{{ enumText('evalLabel', resultDetail.label) }}</n-tag>
            <span class="page-sub">尝试 #{{ resultDetail.attempt }}</span>
          </div>
          <div class="desc-list" style="margin-bottom:14px">
            <span class="k">评估器</span><span class="mono">{{ resultDetail.evaluatorKey }} v{{ resultDetail.evaluatorVersion }}</span>
            <span class="k">Agent</span><span class="mono">{{ resultDetail.agentKey || '—' }}</span>
            <span class="k">Trace</span>
            <span><a class="mono link" style="color:var(--primary);cursor:pointer"
              @click="router.push('/traces/' + encodeURIComponent(resultDetail.traceId))">{{ resultDetail.traceId }}</a></span>
            <span class="k">评审模型</span><span class="mono">{{ resultDetail.judgeModel || '—' }}</span>
            <span class="k">置信度</span><span class="num">{{ resultDetail.confidence == null ? '—' : Math.round(resultDetail.confidence * 100) + '%' }}</span>
            <span class="k">评审 Token</span><span class="num">{{ resultDetail.promptTokens == null ? '—' : (resultDetail.promptTokens + ' in / ' + resultDetail.completionTokens + ' out') }}</span>
            <span class="k">时间</span><span class="num">{{ resultDetail.createdAt ? String(resultDetail.createdAt).replace('T', ' ').slice(0, 19) : '—' }}</span>
          </div>
          <template v-if="criterionResults.length">
            <div class="panel-title">判定维度</div>
            <div style="display:flex;flex-direction:column;gap:6px;margin-bottom:14px">
              <div v-for="c in criterionResults" :key="c.id || c.label"
                style="display:flex;align-items:flex-start;gap:8px;font-size:12.5px">
                <n-tag size="tiny" :bordered="false" :type="c.passed ? 'success' : 'error'">
                  {{ c.passed ? '通过' : '未通过' }}
                </n-tag>
                <span style="font-weight:600">{{ c.label || c.id || '维度' }}</span>
                <span class="page-sub" style="word-break:break-word">{{ c.reason || '' }}</span>
              </div>
            </div>
          </template>
          <div class="panel-title">判定原因（全文）</div>
          <div style="font-size:13px;line-height:1.7;white-space:pre-wrap;word-break:break-word;background:var(--bg-subtle);border-radius:6px;padding:10px 12px">{{ resultDetail.reason || '—' }}</div>
        </template>
      </n-drawer-content>
    </n-drawer>
  </div>
</template>

<script setup>
import { computed, h, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NTag, useMessage } from 'naive-ui'
import api from '@/api'
import { enumText } from '@/common/i18n'
import { toCsv, downloadCsv } from '@/common/csv'
import { useAuthStore } from '@/store/auth'

const message = useMessage()
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const canRun = computed(() => auth.hasPermission('workspace:trust:evaluation:run'))
const records = ref([]), loading = ref(false), showRun = ref(false), labelFilter = ref(null)
const evaluatorFilter = ref(null), traceFilter = ref('')
const evaluatorOptions = ref([])
// 标签下拉必须走 enumText：之前直接渲染 PAS/FAIL 原始枚举，与表格里中文的「标签」列自相矛盾
// （页面 E2E 在筛选时抓到的）
const LABEL_VALUES = ['PASS', 'FAIL', 'UNKNOWN', 'EVAL_UNAVAILABLE', 'N/A']
const labelOptions = LABEL_VALUES.map((l) => ({ label: enumText('evalLabel', l), value: l }))
const running = ref(false)
const runForm = reactive({ evaluatorId: null, traceIds: '' })
const stats = reactive({ total: 0, pass: 0, fail: 0, other: 0, rate: 0 })
const pagination = reactive({
  page: 1, pageSize: 10, itemCount: 0, showSizePicker: true, pageSizes: [10, 20, 50, 100],
  prefix: ({ itemCount }) => '共 ' + itemCount + ' 条',
  onChange: (page) => { pagination.page = page; load() },
  onUpdatePageSize: (pageSize) => { pagination.pageSize = pageSize; pagination.page = 1; load() }
})

const runEvaluatorOptions = computed(() => evaluatorOptions.value.map((e) => ({ label: e.label, value: e.id })))

function labelTag(l) {
  const type = l === 'PASS' ? 'success' : l === 'FAIL' ? 'error' : l === 'EVAL_UNAVAILABLE' ? 'warning' : 'default'
  return h(NTag, { size: 'small', bordered: false, type, title: l }, { default: () => enumText('evalLabel', l) })
}
function scoreBar(r) {
  // N/A / 不可用：仅灰色 —，不渲染空量条（避免误导为 0 分）
  if (r.score == null) {
    return h('span', { style: 'display:inline-flex;justify-content:center;width:100%;color:var(--text-muted);font-size:12px' }, '—')
  }
  const labelColor = r.label === 'PASS' ? 'var(--success)' : r.label === 'FAIL' ? 'var(--error)' : 'var(--text-muted)'
  const pct = Math.max(4, Math.min(100, Math.round(Number(r.score) / 10 * 100)))
  return h('span', { style: 'display:inline-flex;align-items:center;gap:6px;width:100%' }, [
    h('span', { class: 'num', style: `flex:0 0 20px;text-align:right;font-weight:600;color:${labelColor}` }, String(r.score)),
    h('span', { style: 'flex:0 0 40px;height:5px;background:var(--bg-subtle);border-radius:3px;overflow:hidden;display:inline-block',
      title: `评分 ${r.score} / 10` }, [
      h('i', { style: `display:block;height:100%;width:${pct}%;background:${labelColor};border-radius:3px` })
    ])
  ])
}
function r_completion(r) { return r.completionTokens; }
const columns = [
  { title: '评估器', key: 'evaluatorKey', width: 170, ellipsis: { tooltip: true } },
  { title: 'Agent', key: 'agentKey', width: 150, ellipsis: { tooltip: true }, render: (r) => h('span', { class: 'mono', style: 'font-size:12px' }, r.agentKey) },
  { title: 'Trace', key: 'traceId', width: 170, ellipsis: { tooltip: true }, render: (r) => h('span', { class: 'mono link', style: 'color:var(--primary);cursor:pointer', title: '查看链路', onClick: () => router.push('/traces/' + encodeURIComponent(r.traceId)) }, r.traceId) },
  { title: '尝试', key: 'attempt', width: 60, align: 'right' },
  { title: '分数', key: 'score', width: 90, align: 'right', render: scoreBar },
  { title: '标签', key: 'label', width: 140, render: (r) => labelTag(r.label) },
  { title: '评审模型', key: 'judgeModel', width: 120, render: (r) => r.judgeModel
    ? h('span', { class: 'mono', style: 'font-size:11.5px' }, r.judgeModel)
    : h('span', { style: 'color:var(--text-muted);font-size:12px' }, '—') },
  { title: '置信度', key: 'confidence', width: 80, align: 'right', render: (r) => r.confidence == null
    ? h('span', { style: 'color:var(--text-muted);font-size:12px' }, '—')
    : h('span', { class: 'num' }, Math.round(Number(r.confidence) * 100) + '%') },
  { title: '评审Token', key: 'tokens', width: 100, align: 'right', render: (r) => (r.promptTokens == null && r.completionTokens == null)
    ? h('span', { style: 'color:var(--text-muted);font-size:12px' }, '—')
    : h('span', { class: 'num' }, (r.promptTokens ?? 0) + '+' + (r_completion(r) ?? 0)) },
  // 原因列：中文结论放最前（列宽有限会截断，解释放后面等于看不见），原始码留在 tooltip
  { title: '原因', key: 'reason', ellipsis: { tooltip: true }, render: (r) => h('span', {}, [
      r.reasonCode
        ? h('span', {
            class: 'mono',
            style: 'color:var(--text-muted);font-size:11px;margin-right:6px',
            title: r.reasonCode
          }, enumText('evalReason', r.reasonCode))
        : null,
      h('span', {}, r.reason || '—')
    ]) },
  { title: '时间', key: 'createdAt', width: 160, render: (r) => h('span', { class: 'mono', style: 'font-size:11.5px' }, fmtTs(r.createdAt)) }
]

function fmtTs(s) {
  if (!s) return '—'
  return String(s).replace('T', ' ').slice(0, 19)
}

async function load() {
  loading.value = true
  try {
    const page = await api.evalResults({
      label: labelFilter.value || undefined,
      evaluatorKey: evaluatorFilter.value || undefined,
      traceId: traceFilter.value.trim() || undefined,
      pageNumber: pagination.page, pageSize: pagination.pageSize
    })
    records.value = page.records || []
    pagination.itemCount = page.total || 0
  } finally { loading.value = false }
}
async function loadStats() {
  try {
    const data = await api.evalStats({}) || {}
    stats.total = Number(data.total || 0)
    stats.pass = Number(data.pass || 0)
    stats.fail = Number(data.fail || 0)
    stats.other = Number(data.other || 0)
    const judged = stats.pass + stats.fail
    stats.rate = judged > 0 ? Math.round(stats.pass * 100 / judged) : 0
    buildTrend(data.daily || [])
  } catch (e) { /* 统计失败不打断页面 */ }
}

// ---------- 通过率趋势（近 14 天逐日 pass/fail） ----------
const trend = ref([])
// ---------- 评估器概览 ----------
const overview = ref([])
async function loadOverview() {
  try { overview.value = await api.evalOverview() || [] } catch (e) { overview.value = [] }
}
function ovTotal(o) { return Math.max(1, (o.pass || 0) + (o.fail || 0)) }
function ovPassPct(o) { return Math.round((o.pass || 0) / ovTotal(o) * 100) }
function ovFailPct(o) { return Math.round((o.fail || 0) / ovTotal(o) * 100) }
function rateColor(rate) {
  return rate == null ? 'var(--text-muted)' : rate >= 80 ? 'var(--success)' : rate >= 50 ? 'var(--warn)' : 'var(--error)'
}
function buildTrend(daily) {
  const byDay = new Map()
  for (const r of daily || []) {
    const day = String(r.day || '').slice(0, 10)
    if (!day) continue
    byDay.set(day, { pass: Number(r.pass || 0), fail: Number(r.fail || 0) })
  }
  if (!byDay.size) { trend.value = []; return }
  // 连续 14 天补零（无数据的天也占位），柱子按天均分，不再稀疏散布
  const p2 = (n) => String(n).padStart(2, '0')
  const days = []
  const today = new Date()
  for (let i = 13; i >= 0; i--) {
    const d = new Date(today.getTime() - i * 86400000)
    days.push(`${d.getFullYear()}-${p2(d.getMonth() + 1)}-${p2(d.getDate())}`)
  }
  const max = Math.max(1, ...days.map((d) => (byDay.get(d)?.pass || 0) + (byDay.get(d)?.fail || 0)))
  trend.value = days.map((day) => {
    const { pass = 0, fail = 0 } = byDay.get(day) || {}
    return {
      day,
      label: day.slice(5),
      pass, fail,
      passH: Math.round(pass / max * 100),
      failH: Math.round(fail / max * 100)
    }
  })
}
function search() { pagination.page = 1; load() }
// ---------- 评估结果详情抽屉 ----------
const showResult = ref(false), resultDetail = ref(null)
const criterionResults = computed(() => {
  const raw = resultDetail.value?.criterionResultsJson
  if (!raw) return []
  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed : []
  } catch (e) {
    return []
  }
})
function openResult(row) {
  resultDetail.value = row
  showResult.value = true
}

// ---------- 补评快捷填充（最近完成 Trace 多选） ----------
const quickPicks = ref([])
const recentTraces = ref([])
function openRun() {
  // evaluatorFilter 存的是 evaluatorKey，run API 需要 evaluator id，不能直接复用
  if (!runForm.evaluatorId && evaluatorFilter.value) {
    const match = evaluatorOptions.value.find((e) => e.value === evaluatorFilter.value)
    runForm.evaluatorId = match?.id || null
  }
  loadRecentTraces()
  showRun.value = true
}
async function loadRecentTraces() {
  try {
    const page = await api.traceSearch({ status: 'COMPLETED', pageNumber: 1, pageSize: 15, sortBy: 'startTime', sortOrder: 'desc' })
    recentTraces.value = (page.records || []).map((r) => ({
      label: r.traceId + (r.inputPreview ? ' · ' + String(r.inputPreview).slice(0, 22) : ''),
      value: r.traceId
    }))
  } catch (e) { recentTraces.value = [] }
}
function syncQuick(vals) {
  const manual = runForm.traceIds.split('\n').map((s) => s.trim()).filter(Boolean)
  const manualOnly = manual.filter((id) => !recentTraces.value.some((o) => o.value === id))
  runForm.traceIds = [...new Set([...vals, ...manualOnly])].join('\n')
}
async function doRun() {
  const traceIds = runForm.traceIds.split('\n').map((s) => s.trim()).filter(Boolean)
  if (!runForm.evaluatorId) { message.error('请选择评估器'); return }
  if (!traceIds.length) { message.error('请填写至少一个 traceId'); return }
  running.value = true
  try {
    await api.runEvaluation({ evaluatorId: runForm.evaluatorId, traceIds })
    message.success('已执行，结果落库'); showRun.value = false; load(); loadStats()
  } catch (e) { message.error(e.message) }
  finally { running.value = false }
}
const exporting = ref(false)
async function exportCsv() {
  exporting.value = true
  try {
    const page = await api.evalResults({
      label: labelFilter.value || undefined,
      evaluatorKey: evaluatorFilter.value || undefined,
      traceId: traceFilter.value.trim() || undefined,
      pageNumber: 1, pageSize: 1000
    })
    const rows = page.records || []
    if (!rows.length) { message.warning('当前筛选条件下没有可导出的数据'); return }
    const csv = toCsv(
      ['评估器', 'Agent', 'Trace', '尝试', '分数', '标签', '置信度', '评审模型', 'Tokens(P+C)', '原因', '时间'],
      rows.map((r) => [r.evaluatorKey, r.agentKey, r.traceId, r.attempt, r.score, r.label, r.confidence == null ? '' : Math.round(Number(r.confidence) * 100) + '%', r.judgeModel, (r.promptTokens == null && r.completionTokens == null) ? '' : (r.promptTokens ?? 0) + '+' + (r.completionTokens ?? 0), r.reason, r.createdAt])
    )
    downloadCsv('evaluations-' + new Date().toISOString().slice(0, 10) + '.csv', csv)
    message.success('已导出 ' + rows.length + ' 条')
  } catch (e) { message.error(e.message || '导出失败') }
  finally { exporting.value = false }
}
onMounted(async () => {
  // 深链预填：/evaluations?traceId=..&evaluatorKey=..（会话时间线 chip 等入口跳转）
  if (route.query.traceId) traceFilter.value = String(route.query.traceId)
  if (route.query.evaluatorKey) evaluatorFilter.value = String(route.query.evaluatorKey)
  load()
  loadStats()
  loadOverview()
  try {
    const page = await api.evaluators({ pageNumber: 1, pageSize: 100 })
    evaluatorOptions.value = (page.records || []).map((r) => ({ label: r.evaluatorKey, value: r.evaluatorKey, id: r.id }))
  } catch (e) { /* ignore */ }
})
</script>

<style scoped>
.mt12 { margin-top: 12px; }
.stat-strip { display: flex; gap: 12px; }
.s-item { flex: 1; background: var(--bg-surface); border: 1px solid var(--border); border-radius: var(--radius-card); padding: 12px 16px; display: flex; align-items: baseline; gap: 8px; }
.s-v { font-size: 21px; font-weight: 600; font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; }
.s-k { font-size: 12px; color: var(--text-muted); }
.trend-strip { display: flex; align-items: flex-end; gap: 6px; margin-top: 12px; background: var(--bg-surface); border: 1px solid var(--border); border-radius: var(--radius-card); padding: 12px 16px; }
.trend-col { display: flex; flex-direction: column; align-items: center; gap: 4px; flex: 1; }
.trend-bars { display: flex; flex-direction: column; justify-content: flex-end; align-items: center; height: 56px; width: 100%; }
.trend-bars i { display: block; width: 60%; max-width: 18px; border-radius: 2px 2px 0 0; }
.tb-pass { background: var(--success); opacity: 0.85; }
.tb-fail { background: var(--error); opacity: 0.85; }
.trend-day { font-size: 10px; color: var(--text-muted); font-family: Menlo, monospace; }
.trend-legend { display: flex; flex-direction: column; gap: 4px; font-size: 11.5px; color: var(--text-secondary); padding-left: 10px; }
.trend-legend .dot { display: inline-block; width: 8px; height: 8px; border-radius: 2px; margin-right: 4px; }
.ov-list { display: flex; flex-direction: column; gap: 8px; max-height: 420px; overflow-y: auto; padding-right: 4px; }
.ov-row { display: flex; align-items: center; gap: 10px; }
.ov-row:hover { background: var(--bg-subtle); border-radius: 4px; }
.ov-name { flex: 0 0 190px; font-size: 12.5px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ov-bar { flex: 1; height: 10px; display: flex; border-radius: 5px; overflow: hidden; background: var(--bg-subtle); }
.ov-pass { background: var(--success); opacity: 0.85; }
.ov-fail { background: var(--error); opacity: 0.85; }
.ov-rate { flex: 0 0 56px; text-align: right; font-size: 12.5px; font-weight: 600; }
.ov-meta { flex: 0 0 150px; font-size: 11px; color: var(--text-muted); font-family: Menlo, monospace; white-space: nowrap; }
.mono { font-family: Menlo, monospace; }
.link { text-decoration: none; }
.link:hover { text-decoration: underline; }
.desc-list { display: grid; grid-template-columns: 90px 1fr; row-gap: 7px; font-size: 12.5px; align-items: baseline; }
.desc-list .k { color: var(--text-muted); }
.panel-title { font-size: 11.5px; font-weight: 600; color: var(--text-muted); margin: 14px 0 8px; text-transform: uppercase; letter-spacing: 0.04em; }
.link:hover { text-decoration: underline; }
/* 内容高于一屏：页面自然滚动，表格给固定高度，分页不再溢出卡片。
   overflow 必须 visible：.base-page 默认的 overflow:auto 会把自己变成"永远不滚的
   滚动容器"，劫走 .page-head 的 sticky 包含块——页面明明在 .content 里滚，页头却
   完全不吸顶（实测滚到底 headTop 从 76 变 -50）。 */
.eval-page { height: auto; min-height: 100%; overflow: visible; }
.eval-table-card { flex: 1; min-height: 0; }
.page-sub { font-size: 11.5px; color: var(--text-muted); }
</style>
