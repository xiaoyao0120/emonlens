<template>
  <div class="base-page trace-page">
    <div class="head-stack">
      <div class="page-head page-head--tabs">
        <div class="head-left">
          <n-tabs type="segment" size="small" style="width: 330px" :value="view" @update:value="onViewChange">
            <n-tab name="traces">调用链路</n-tab>
            <n-tab name="observations">观测明细</n-tab>
            <n-tab name="sessions">会话</n-tab>
          </n-tabs>
        </div>
      </div>

      <div class="page-toolbar">
        <div class="toolbar-filters">
          <template v-if="view === 'traces'">
            <n-select v-model:value="filters.timeRange" style="width: 130px" size="small" :options="[
              { label: '近 1 小时', value: '1h' }, { label: '近 24 小时', value: '24h' },
              { label: '近 7 天', value: '7d' }, { label: '近 30 天', value: '30d' }, { label: '全部', value: 'all' }]"
              @update:value="search" />
            <FilterBar :model-value="filters" :fields="filterFields" @update:model-value="onFiltersUpdate" @apply="search" />
          </template>
          <template v-else-if="view === 'observations'">
            <n-input v-model:value="obsFilters.query" placeholder="span / 工具名关键词" size="small" style="width: 165px"
              clearable @clear="load" @keyup.enter="load" />
            <n-select v-model:value="obsFilters.spanType" placeholder="全部类型" size="small" style="width: 120px" clearable
              :options="['agent', 'llm', 'tool', 'retrieval'].map((t) => ({ label: t, value: t }))" @update:value="load" />
            <n-input v-model:value="obsFilters.model" placeholder="按模型过滤" size="small" style="width: 130px" clearable @clear="load" />
            <n-input v-model:value="obsFilters.minDurationMs" placeholder="最小耗时 ms" size="small" style="width: 120px"
              clearable @clear="load" @keyup.enter="load" />
            <n-button size="small" :type="obsFilters.onlyError ? 'error' : 'default'" ghost
              @click="obsFilters.onlyError = !obsFilters.onlyError; load()">只看错误</n-button>
          </template>
          <template v-else>
            <n-input v-model:value="sessionQuery" placeholder="搜索会话名称 / id / 用户" size="small" style="width: 300px"
              clearable @clear="loadSessions" @keyup.enter="loadSessions" />
          </template>
        </div>
        <div class="toolbar-actions">
          <template v-if="view === 'traces'">
            <SavedViews storage-key="traces" :current="filters" @apply="applyView" />
            <ColumnsDropdown v-model="visibleKeys" :columns="allColumns" locked-key="inputPreview" />
            <n-button v-if="checkedRowKeys.length === 2" size="small" type="primary" ghost
              title="并排对比两条 Trace 的元信息、输入输出与 span 差异" @click="openCompare">对比（{{ checkedRowKeys.length }}）</n-button>
            <n-button size="small" :loading="exporting" @click="exportCsv">导出 CSV</n-button>
            <n-button size="small" @click="load">刷新</n-button>
          </template>
          <template v-else>
            <n-button size="small" :loading="loading" @click="refreshCurrentView">刷新</n-button>
          </template>
        </div>
      </div>
    </div>

    <n-card size="small" class="table-card">
      <SessionsView v-if="view === 'sessions'" ref="sessionsRef" :initial-query="sessionQuery" />
      <template v-else>
        <SkeletonTable v-if="loading && view === 'traces' && !records.length" :rows="8" />
        <EmptyState v-else-if="view === 'traces' && !loading && !records.length" text="当前筛选条件下没有调用记录"
          action-text="清除全部筛选" @action="resetFilters" />
        <n-data-table v-else-if="view === 'traces'" class="main-table" flex-height style="height: 100%" :scroll-x="1500"
          :columns="traceColumns" :data="records" size="small" :bordered="false" remote
          :row-key="(r) => r.traceId"
          :checked-row-keys="checkedRowKeys" @update:checked-row-keys="(k) => checkedRowKeys = k"
          :row-class-name="rowClassName"
          :pagination="pagination" :loading="loading" @update:sorter="onSorter" :sorter="sorterState"
          @update:page="onPageChange" @update:page-size="onPageSizeChange" />
        <n-data-table v-if="view === 'observations'" class="main-table" flex-height style="height: 100%" :scroll-x="1300"
          :columns="observationColumns" :data="observations" size="small" :bordered="false" remote
          :pagination="obsPagination" :loading="loading" @update:page="onObsPage" @update:page-size="onObsPageSize" />
      </template>
    </n-card>
  </div>
</template>

<script setup>
import { computed, h, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { NCheckbox, NTag, useMessage } from 'naive-ui'
import { useRoute, useRouter } from 'vue-router'
import api from '@/api'
import { enumText } from '@/common/i18n'
import { fetchAgentOptions } from '@/common/agents'
import FilterBar from '@/components/FilterBar.vue'
import ColumnsDropdown from '@/components/ColumnsDropdown.vue'
import SavedViews from '@/components/SavedViews.vue'
import CopyableId from '@/components/CopyableId.vue'
import TimeText from '@/components/TimeText.vue'
import SkeletonTable from '@/components/SkeletonTable.vue'
import EmptyState from '@/components/EmptyState.vue'
import SessionsView from '@/views/trust/SessionsView.vue'
import { toCsv, downloadCsv } from '@/common/csv'
import { fmtDur, fmtCostNullable as fmtCost } from '@/common/format'

const message = useMessage()
const route = useRoute()
const router = useRouter()
const view = ref(['traces', 'observations', 'sessions'].includes(String(route.query.tab)) ? String(route.query.tab) : 'traces')
const sessionQuery = ref('')
const sessionsRef = ref(null)
let sessionSearchTimer = null
watch(sessionQuery, () => {
  clearTimeout(sessionSearchTimer)
  sessionSearchTimer = setTimeout(loadSessions, 300)
})
// 防抖窗口内离开页面时取消挂起的请求，避免对已卸载组件回调
onUnmounted(() => clearTimeout(sessionSearchTimer))
const filters = reactive({ agentKeys: [], statuses: [], environments: [], modelNames: [], promptName: '', promptVersion: '', userId: '', sessionId: '', tags: [], minDurationMs: '', maxDurationMs: '', minCostUsd: '', maxCostUsd: '', timeRange: '24h', query: '' })
// ---------- Trace 对比（LangSmith 式 span diff，勾选两条） ----------
const checkedRowKeys = ref([])
function openCompare() {
  if (checkedRowKeys.value.length !== 2) return
  const [a, b] = checkedRowKeys.value
  router.push({ path: '/traces/compare', query: { a, b } })
}
const sortState = reactive({ sortBy: null, sortOrder: null })
// 受控排序：清除筛选时同步清掉表头排序箭头
const sorterState = computed(() => sortState.sortBy
  ? { columnKey: sortState.sortBy, order: sortState.sortOrder === 'asc' ? 'ascend' : 'descend' }
  : null)
const records = ref([]), observations = ref([])
const total = ref(0), loading = ref(false)
const agentOptions = ref([])
const tagOptions = ref([])
const envOptions = ref([])
const modelOptions = ref([])
const promptOptions = ref([])
const pagination = reactive({ page: 1, pageSize: 10, itemCount: 0, showSizePicker: true, pageSizes: [10, 20, 50, 100],
  prefix: ({ itemCount }) => '共 ' + itemCount + ' 条' })
const obsPagination = reactive({ page: 1, pageSize: 10, itemCount: 0, showSizePicker: true, pageSizes: [10, 20, 50, 100] })
const obsFilters = reactive({ spanType: null, model: '', query: '', minDurationMs: '', onlyError: false })

function openTrace(id, spanId) {
  const query = spanId ? { spanId } : undefined
  router.push({ path: '/traces/' + encodeURIComponent(id), query })
}

const filterFields = [
  { key: 'agentKeys', label: 'Agent', type: 'select', options: agentOptions },
  { key: 'statuses', label: '状态', type: 'select', options: ['COMPLETED', 'RUNNING'].map((s) => ({ label: s, value: s })) },
  { key: 'userId', label: 'User', type: 'input', placeholder: 'user id' },
  { key: 'sessionId', label: '会话', type: 'input', placeholder: 'session id' },
  { key: 'tags', label: '标签', type: 'select', options: tagOptions },
  { key: 'environments', label: '环境', type: 'select', options: envOptions },
  { key: 'modelNames', label: '模型', type: 'select', options: modelOptions },
  { key: 'promptName', label: 'Prompt', type: 'select', options: promptOptions },
  { key: 'promptVersion', label: 'Prompt 版本', type: 'input', placeholder: '如 2' },
  { key: 'query', label: '搜索内容', type: 'input', placeholder: '输入/输出关键词' },
  { key: 'minDurationMs', label: '最小耗时 ms', type: 'input', placeholder: '如 1000' },
  { key: 'maxDurationMs', label: '最大耗时 ms', type: 'input', placeholder: '如 10000' },
  { key: 'minCostUsd', label: '最小成本 $', type: 'input', placeholder: '如 0.01' },
  { key: 'maxCostUsd', label: '最大成本 $', type: 'input', placeholder: '如 1' }
]

const allColumns = [
  { key: 'traceId', title: 'Trace ID' },
  { key: 'inputPreview', title: '输入摘要' },
  { key: 'outputPreview', title: '输出摘要' },
  { key: 'agentKey', title: 'Agent' },
  { key: 'modelName', title: '模型' },
  { key: 'status', title: '状态' },
  { key: 'spanCount', title: 'Spans/Err' },
  { key: 'durationMs', title: '耗时' },
  { key: 'totalCostUsd', title: '成本' },
  { key: 'startTime', title: '时间' }
]
const visibleKeys = ref(loadCols())
function loadCols() {
  try {
    const saved = JSON.parse(localStorage.getItem('ui.cols.traces.v2') || 'null')
    if (Array.isArray(saved) && saved.length) return saved
  } catch (e) { /* ignore */ }
  return allColumns.map((c) => c.key)
}
watch(visibleKeys, (v) => localStorage.setItem('ui.cols.traces.v2', JSON.stringify(v)))


const COL_DEFS = {
  inputPreview: { title: '输入摘要', key: 'inputPreview', minWidth: 220, ellipsis: { tooltip: true }, render: (r) => r.inputPreview || '—' },
  outputPreview: { title: '输出摘要', key: 'outputPreview', minWidth: 180, ellipsis: { tooltip: true }, render: (r) => r.outputPreview || '—' },
  traceId: { title: 'Trace ID', key: 'traceId', width: 150, render: (r) => h('span', {
    class: 'mono trace-link',
    title: '点击查看详情',
    onClick: () => openTrace(r.traceId)
  }, r.traceId.length > 16 ? r.traceId.slice(0, 16) + '…' : r.traceId) },
  agentKey: { title: 'Agent', key: 'agentKey', width: 150, ellipsis: { tooltip: true }, render: (r) => h('span', {}, [
      h('span', { class: 'mono', style: 'font-size:12px' }, r.agentKey),
      ...(r.agentVersion && r.agentVersion !== r.agentKey
        ? [h('span', { style: 'color:var(--text-muted);font-size:11px;margin-left:4px' }, r.agentVersion)]
        : [])
    ]) },
  modelName: { title: '模型', key: 'modelName', width: 110, ellipsis: { tooltip: true }, render: (r) => r.modelName
    ? h('span', { class: 'mono', style: 'font-size:12px' }, r.modelName)
    : h('span', { style: 'color:var(--text-muted);font-size:11.5px', title: '该 Trace 上报时未携带模型信息（旧版 SDK/CLI 桥接的历史数据；新会话完成后会自动带上）' }, '未采集') },
  status: { title: '状态', key: 'status', width: 110, render: (r) => h(NTag, {
    size: 'small', bordered: false,
    type: r.errorCount > 0 ? 'warning' : r.status === 'COMPLETED' ? 'success' : 'info'
  }, { default: () => (r.errorCount > 0 ? enumText('runStatus', r.status) + ' ⚠' : enumText('runStatus', r.status)) }) },
  spanCount: { title: 'Spans/Err', key: 'spanCount', width: 85, align: 'right', render: (r) => `${r.spanCount}/${r.errorCount}` },
  durationMs: { title: '耗时', key: 'durationMs', width: 85, align: 'right', sorter: true, render: (r) => h('span', { class: 'num' }, fmtDur(r.durationMs)) },
  totalCostUsd: { title: '成本', key: 'totalCostUsd', width: 80, align: 'right', sorter: true, render: (r) => h('span', { class: 'num' }, fmtCost(r.totalCostUsd)) },
  startTime: { title: '时间', key: 'startTime', width: 100, sorter: true, render: (r) => h(TimeText, { value: r.startTime }) }
}
const traceColumns = computed(() => [{ type: 'selection' }, ...visibleKeys.value.map((k) => COL_DEFS[k]).filter(Boolean)])

const observationColumns = [
  { title: 'Trace ID', key: 'traceId', width: 150, render: (r) => h('span', {
    class: 'mono trace-link',
    title: '点击查看详情并定位该 Span',
    onClick: () => openTrace(r.traceId, r.spanId)
  }, r.traceId.length > 16 ? r.traceId.slice(0, 16) + '…' : r.traceId) },
  { title: 'Name', key: 'spanName', minWidth: 170, ellipsis: { tooltip: true }, render: (r) => h('span', {
    class: 'mono trace-link',
    title: '点击查看该 Trace 详情并定位此 Span',
    onClick: () => openTrace(r.traceId, r.spanId)
  }, r.spanName || r.toolName || r.spanType) },
  { title: '类型', key: 'spanType', width: 95, render: (r) => h(NTag, { size: 'small', bordered: false, title: r.spanType }, { default: () => enumText('spanType', r.spanType) }) },
  { title: 'Agent', key: 'agentKey', width: 120 },
  { title: '模型 / 工具', key: 'modelName', width: 120, render: (r) => r.modelName || r.toolName || '—' },
  { title: '状态', key: 'status', width: 85, render: (r) => h(NTag, { size: 'small', bordered: false, type: r.status === 'ERROR' ? 'error' : r.status === 'OK' ? 'success' : 'info' }, { default: () => enumText('runStatus', r.status) }) },
  { title: 'Tokens', key: 'inputTokens', width: 90, align: 'right', render: (r) => h('span', {
    class: 'num', title: '输入 ' + (r.inputTokens || 0) + ' / 输出 ' + (r.outputTokens || 0)
      + (r.cachedInputTokens ? ' / 缓存命中 ' + r.cachedInputTokens : '')
  }, String((r.inputTokens || 0) + (r.outputTokens || 0))) },
  { title: '成本', key: 'costUsd', width: 75, align: 'right', render: (r) => h('span', { class: 'num' }, fmtCost(r.costUsd)) },
  { title: '耗时', key: 'durationMs', width: 85, align: 'right', render: (r) => h('span', { class: 'num' }, fmtDur(r.durationMs)) },
  { title: '时间', key: 'startTime', width: 100, render: (r) => h(TimeText, { value: r.startTime }) }
]

function toLocalIso(ms) {
  const d = new Date(ms)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}
function timeFromFor(range) {
  const now = Date.now()
  if (range === 'all') return undefined
  if (range === '1h') return toLocalIso(now - 3600000)
  if (range === '24h') return toLocalIso(now - 86400000)
  if (range === '7d') return toLocalIso(now - 7 * 86400000)
  return toLocalIso(now - 30 * 86400000)
}

function buildQuery(page, pageSize) {
  return {
    pageNumber: page, pageSize,
    agentKeys: filters.agentKeys?.length ? filters.agentKeys : undefined,
    statuses: filters.statuses?.length ? filters.statuses : undefined,
    userId: filters.userId || undefined,
    sessionId: filters.sessionId || undefined,
    minDurationMs: filters.minDurationMs ? Number(filters.minDurationMs) : undefined,
    maxDurationMs: filters.maxDurationMs ? Number(filters.maxDurationMs) : undefined,
    minCostUsd: filters.minCostUsd ? Number(filters.minCostUsd) : undefined,
    maxCostUsd: filters.maxCostUsd ? Number(filters.maxCostUsd) : undefined,
    tags: filters.tags?.length ? filters.tags : undefined,
    environments: filters.environments?.length ? filters.environments : undefined,
    modelNames: filters.modelNames?.length ? filters.modelNames : undefined,
    promptName: filters.promptName || undefined,
    promptVersion: filters.promptVersion || undefined,
    query: filters.query || undefined,
    sortBy: sortState.sortBy || undefined,
    sortOrder: sortState.sortOrder || undefined,
    timeFrom: timeFromFor(filters.timeRange)
  }
}
// 筛选状态同步到 URL（可分享/可刷新）
function syncFiltersToUrl() {
  const q = { ...route.query }
  const simple = { userId: filters.userId, sessionId: filters.sessionId, minDurationMs: filters.minDurationMs, maxDurationMs: filters.maxDurationMs, minCostUsd: filters.minCostUsd, maxCostUsd: filters.maxCostUsd, query: filters.query, timeRange: filters.timeRange, promptName: filters.promptName, promptVersion: filters.promptVersion }
  for (const [k, v] of Object.entries(simple)) {
    if (v) q[k] = String(v)
    else delete q[k]
  }
  for (const k of ['agentKeys', 'statuses', 'tags', 'environments', 'modelNames']) {
    if (filters[k]?.length) q[k] = filters[k].join(',')
    else delete q[k]
  }
  if (sortState.sortBy) { q.sortBy = sortState.sortBy; q.sortOrder = sortState.sortOrder }
  else { delete q.sortBy; delete q.sortOrder }
  router.replace({ query: q })
}
function loadFiltersFromUrl() {
  const q = route.query
  for (const k of ['userId', 'sessionId', 'minDurationMs', 'maxDurationMs', 'minCostUsd', 'maxCostUsd', 'query', 'timeRange', 'promptName', 'promptVersion']) {
    if (q[k]) filters[k] = String(q[k])
  }
  for (const k of ['agentKeys', 'statuses', 'tags', 'environments', 'modelNames']) {
    if (q[k]) filters[k] = String(q[k]).split(',').filter(Boolean)
  }
  if (q.sortBy) {
    sortState.sortBy = String(q.sortBy)
    sortState.sortOrder = String(q.sortOrder || 'desc')
  }
}

async function load() {
  syncFiltersToUrl()
  loading.value = true
  try {
    if (view.value === 'traces') {
      const body = await api.traceSearch(buildQuery(pagination.page, pagination.pageSize))
      records.value = body.records || []
      total.value = body.total || 0
      pagination.itemCount = total.value
    } else {
      const body = await api.observations({
        agentKey: filters.agentKeys?.length ? filters.agentKeys[0] : undefined,
        status: obsFilters.onlyError ? 'ERROR' : (filters.statuses?.length ? filters.statuses[0] : undefined),
        spanType: obsFilters.spanType || undefined,
        model: obsFilters.model.trim() || undefined,
        query: obsFilters.query.trim() || undefined,
        minDurationMs: obsFilters.minDurationMs ? Number(obsFilters.minDurationMs) : undefined,
        timeFrom: timeFromFor(filters.timeRange),
        pageNumber: obsPagination.page, pageSize: obsPagination.pageSize
      })
      observations.value = body.records || []
      obsPagination.itemCount = body.total || 0
    }
  } catch (e) {
    records.value = []; observations.value = []
    total.value = 0; pagination.itemCount = 0; obsPagination.itemCount = 0
  } finally { loading.value = false }
}
function onFiltersUpdate(next) {
  Object.assign(filters, next)   // 合并进现有 reactive 对象（整体替换 const 会静默失败）
}
function search() { pagination.page = 1; obsPagination.page = 1; load() }
function onViewChange(v) {
  view.value = v
  const query = { ...route.query }
  if (v === 'traces') delete query.tab
  else query.tab = v
  router.replace({ query })
  if (v !== 'sessions') load()
}
function loadSessions() {
  sessionsRef.value?.reload(sessionQuery.value)
}
function refreshCurrentView() {
  if (view.value === 'sessions') loadSessions()
  else load()
}
function onSorter(sorter) {
  if (sorter && sorter.order) {
    sortState.sortBy = sorter.columnKey
    sortState.sortOrder = sorter.order === 'ascend' ? 'asc' : 'desc'
  } else {
    sortState.sortBy = null
    sortState.sortOrder = null
  }
  search()
}
function onPageChange(page) { pagination.page = page; load() }
function onPageSizeChange(pageSize) { pagination.pageSize = pageSize; pagination.page = 1; load() }
function onObsPage(page) { obsPagination.page = page; load() }
function onObsPageSize(pageSize) { obsPagination.pageSize = pageSize; obsPagination.page = 1; load() }
function emptyFilters() {
  return { agentKeys: [], statuses: [], environments: [], modelNames: [], promptName: '', promptVersion: '',
    userId: '', sessionId: '', tags: [], minDurationMs: '', maxDurationMs: '', minCostUsd: '', maxCostUsd: '',
    timeRange: '24h', query: '' }
}
function applyView(saved) {
  Object.assign(filters, emptyFilters(), saved)
  search()
}
// 按当前筛选导出（最多 1000 条）
const exporting = ref(false)
async function exportCsv() {
  exporting.value = true
  try {
    const rows = []
    for (let page = 1; page <= 10; page++) {
      const body = await api.traceSearch(buildQuery(page, 100))
      rows.push(...(body.records || []))
      if (rows.length >= (body.total || 0)) break
    }
    if (!rows.length) { message.warning('当前筛选条件下没有可导出的数据'); return }
    const headers = ['Trace ID', '输入摘要', '输出摘要', 'Agent', '版本', 'User', '环境', '模型', '状态', 'Spans', 'Errors', '耗时ms', '成本USD', '开始时间']
    const csv = toCsv(headers, rows.map((r) => [
      r.traceId, r.inputPreview, r.outputPreview, r.agentKey, r.agentVersion, r.userId,
      r.environment, r.modelName, r.status, r.spanCount, r.errorCount, r.durationMs, r.totalCostUsd, r.startTime
    ]))
    downloadCsv('traces-' + new Date().toISOString().slice(0, 10) + '.csv', csv)
    message.success('已导出 ' + rows.length + ' 条')
  } catch (e) { message.error(e.message || '导出失败') }
  finally { exporting.value = false }
}
async function resetFilters() {
  Object.assign(filters, emptyFilters())
  sortState.sortBy = null
  sortState.sortOrder = null
  await search()
}

// 筛选下拉的候选项（Agent/标签/环境/Prompt/模型）只是给下拉用的，不该挡住列表首屏。
// 以前这里是 5 个请求串行 await，最后才 load()：其中用量接口要扫 7 天数据（修前 7.5 秒），
// 于是"打开调用链路"实际在等这些下拉项。现在先出列表，候选并行加载、失败了也不影响列表。
async function loadFilterOptions() {
  const [overview, tags, envs, prompts, usage] = await Promise.allSettled([
    api.scoreOverview(),
    api.distinctTags(),
    api.environments(),
    api.prompts({ pageNumber: 1, pageSize: 100 }),
    api.usage('7d'),
  ])
  if (overview.status === 'fulfilled') {
    agentOptions.value = Object.keys(overview.value || {}).map((k) => ({ label: k, value: k }))
  }
  if (tags.status === 'fulfilled') {
    tagOptions.value = (tags.value || []).map((t) => ({ label: t, value: t }))
  }
  if (envs.status === 'fulfilled') {
    envOptions.value = (envs.value || []).map((t) => ({ label: t, value: t }))
  }
  if (prompts.status === 'fulfilled') {
    const records = prompts.value?.records || []
    promptOptions.value = [...new Set(records.map((r) => r.name))].map((n) => ({ label: n, value: n }))
  }
  if (usage.status === 'fulfilled') {
    modelOptions.value = (usage.value?.models || [])
      .map((m) => ({ label: m.model || m.modelName, value: m.model || m.modelName }))
      .filter((o) => o.value)
  }
}

onMounted(async () => {
  if (route.query.traceId) {
    router.replace('/traces/' + encodeURIComponent(String(route.query.traceId)))
    return
  }
  loadFiltersFromUrl()
  await load()                 // 先把列表画出来
  void loadFilterOptions()     // 下拉候选随后补上，不阻塞首屏
})
</script>

<style scoped>
.link { cursor: pointer; color: var(--primary); }
.link:hover { text-decoration: underline; }
.mono { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; }
</style>
