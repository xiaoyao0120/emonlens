<template>
  <div class="base-page">
    <div class="head-stack">
      <div class="page-toolbar">
        <div class="toolbar-filters">
          <n-select v-model:value="form.datasetId" size="small" style="width: 200px" placeholder="选择数据集"
            :options="datasetOptions" @update:value="onDatasetChange" />
          <n-input-number v-model:value="form.sampleLimit" size="small" :min="1" :max="20" style="width: 120px" />
          <n-select v-model:value="form.evaluatorIds" size="small" multiple style="min-width: 220px"
            placeholder="可选：挂评估器给每格打分" :options="evaluatorOptions" :max-tag-count="2" />
          <n-button size="small" type="primary" ghost :loading="estimating" :disabled="!canRun" @click="runEstimate">
            预估成本
          </n-button>
          <n-button size="small" type="primary" :loading="running" :disabled="!canRun" @click="runMatrix">
            运行矩阵
          </n-button>
        </div>
        <div class="toolbar-actions">
          <n-input v-model:value="form.name" size="small" style="width: 190px" placeholder="矩阵名称（可选）" />
        </div>
      </div>
      <div class="page-hint">
        矩阵是试跑产物：不进 Gate、不进信任分。要让某次试跑成为准入证据，请用「升格为正式实验」把它变成 dataset run。
      </div>
    </div>

    <!-- 列定义 -->
    <n-card size="small" title="列（prompt 版本 × 模型 × 参数）">
      <template #header-extra>
        <n-button size="tiny" @click="addColumn">＋ 新增列</n-button>
      </template>
      <div class="col-grid">
        <div v-for="(col, i) in form.columns" :key="i" class="col-card">
          <div class="col-head">
            <n-input v-model:value="col.label" size="tiny" placeholder="列名（如 v3-gpt4o）" style="flex: 1" />
            <n-button size="tiny" text type="error" :disabled="form.columns.length <= 1" @click="removeColumn(i)">移除</n-button>
          </div>
          <div class="col-row">
            <n-select v-model:value="col.promptName" size="tiny" clearable placeholder="从提示词库取版本"
              :options="promptOptions" @update:value="(v) => onPromptName(col, v)" />
            <n-select v-model:value="col.promptVersion" size="tiny" clearable placeholder="版本"
              :options="versionOptionsOf(col.promptName)" :disabled="!col.promptName" />
          </div>
          <n-input v-model:value="col.prompt" type="textarea" :rows="3" size="small" class="mono mt6"
            placeholder="留空则用提示词库版本正文；支持双花括号变量占位" />
          <div class="col-row mt6">
            <n-input v-model:value="col.model" size="tiny" placeholder="模型" style="flex: 1" />
            <n-input-number v-model:value="col.temperature" size="tiny" :min="0" :max="2" :step="0.1"
              style="width: 92px" />
          </div>
          <div class="col-row mt6">
            <n-input v-model:value="col.systemPrompt" size="tiny" placeholder="System Prompt（可选）" />
          </div>
          <div class="col-meta">
            <n-tag v-if="col.promptName" size="tiny" :bordered="false" type="success">版本已冻结</n-tag>
            <span class="page-sub mono">{{ col.promptName ? col.promptName + (col.promptVersion ? ' v' + col.promptVersion : ' @production') : 'inline' }}</span>
          </div>
        </div>
      </div>
    </n-card>

    <!-- 成本预估 -->
    <n-card v-if="estimate" size="small" class="mt12" title="启动前成本预估（按 model price 估算）">
      <div class="est-summary">
        <span>{{ estimate.sampleCount }} 样本 × {{ estimate.columnCount }} 列 = <b class="num">{{ estimate.cellCount }}</b> 格</span>
        <span>预计外部调用 <b class="num">{{ estimate.estimatedCalls }}</b> 次</span>
        <span>预计 token：输入 <b class="num">{{ estimate.promptTokens }}</b> / 输出 <b class="num">{{ estimate.completionTokens }}</b></span>
        <span>
          预计成本
          <b class="num" :style="{ color: estimate.priced ? 'var(--text-primary)' : 'var(--warn)' }">
            {{ estimate.priced ? Number(estimate.total).toFixed(4) + ' ' + estimate.currency : '部分模型未配置价目' }}
          </b>
        </span>
      </div>
      <div v-if="estimate.unpricedModels && estimate.unpricedModels.length" class="page-sub mt6">
        未配置价目：{{ estimate.unpricedModels.join('、') }}（成本无法估算，可在「模型价格」页补录）
      </div>
      <n-data-table class="mt8" size="small" :bordered="false" :max-height="200" :columns="estimateColumns" :data="estimate.byColumn" />
    </n-card>

    <!-- 当前矩阵 -->
    <n-card v-if="matrix" size="small" class="mt12">
      <template #header>
        <div style="display:flex;align-items:center;gap:10px">
          <span>{{ matrix.name || '矩阵' }}</span>
          <n-tag size="tiny" :bordered="false" :type="statusType(matrix.status)">{{ matrix.status }}</n-tag>
          <span class="page-sub num">{{ matrix.completedCells }}/{{ matrix.cellCount }} 格 · 失败 {{ matrix.failedCells }}</span>
          <span v-if="matrix.cost != null" class="page-sub num">{{ Number(matrix.cost).toFixed(4) }} {{ matrix.currency }}</span>
        </div>
      </template>
      <template #header-extra>
        <n-button size="tiny" :disabled="!isTerminal" @click="exportCsv">导出 CSV</n-button>
      </template>
      <n-data-table size="small" :bordered="false" :max-height="420" :columns="gridColumns" :data="gridRows"
        :row-key="(r) => r.itemId" :scroll-x="1400" />

      <!-- Diff -->
      <div class="diff-bar">
        <span class="page-sub">Diff：</span>
        <n-select v-model:value="diffForm.baseColumn" size="tiny" style="width: 150px" :options="columnOptions" placeholder="base 列" />
        <span class="page-sub">→</span>
        <n-select v-model:value="diffForm.targetColumn" size="tiny" style="width: 150px" :options="columnOptions" placeholder="对比列" />
        <n-input v-model:value="diffForm.judgeModel" size="tiny" style="width: 150px" placeholder="judge 模型（可选）" />
        <n-button size="tiny" type="primary" ghost :loading="diffing"
          :disabled="!isTerminal || !diffForm.baseColumn || !diffForm.targetColumn" @click="runDiff">
          对比
        </n-button>
        <span class="flex-1"></span>
        <n-button size="tiny" :disabled="!isTerminal" @click="openPromote">升格为正式实验</n-button>
      </div>

      <div v-if="diff" class="diff-summary">
        <span>确定性变化 <b class="num">{{ diff.changedCount }}</b> / 一致 <b class="num">{{ diff.sameCount }}</b></span>
        <span style="color: var(--success)">改进 <b class="num">{{ diff.improvedCount }}</b></span>
        <span style="color: var(--error)">退步 <b class="num">{{ diff.regressedCount }}</b></span>
        <span style="color: var(--warn)">有得有失 <b class="num">{{ diff.tradeoffCount }}</b></span>
        <span>持平 <b class="num">{{ diff.tieCount }}</b> / 未知 <b class="num">{{ diff.unknownCount }}</b></span>
        <span class="page-sub mono">{{ diff.protocolVersion }}</span>
      </div>
      <n-data-table v-if="diff" class="mt8" size="small" :bordered="false" :max-height="260"
        :columns="diffColumns" :data="diff.items" />
    </n-card>

    <!-- 历史矩阵 -->
    <n-card size="small" class="mt12" title="矩阵记录">
      <n-data-table size="small" :bordered="false" :max-height="260" :columns="historyColumns" :data="history" />
    </n-card>

    <Modal v-model:show="showPromote" :width="460" title="升格为正式实验">
      <n-form label-placement="top">
        <n-form-item label="说明">
          <span class="page-sub">将按该列的 prompt 版本 / 模型 / 参数冻结一次 dataset run，进入 Gate 与候选指纹口径。</span>
        </n-form-item>
        <n-form-item label="列">
          <n-select v-model:value="promoteColumn" size="small" :options="columnOptions" />
        </n-form-item>
        <n-form-item label="运行标签（可选，如 baseline）">
          <n-input v-model:value="promoteForm.runLabel" size="small" placeholder="baseline" />
        </n-form-item>
        <n-form-item label="提交号（可选）">
          <n-input v-model:value="promoteForm.commitSha" size="small" placeholder="CI 注入 commit SHA" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-button size="small" :disabled="promoting" @click="showPromote = false">取消</n-button>
        <n-button type="primary" size="small" :loading="promoting" @click="doPromote">升格</n-button>
      </template>
    </Modal>
  </div>
</template>

<script setup>
import { computed, h, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { NTag, useMessage } from 'naive-ui'
import api from '@/api'
import Modal from '@/components/Modal.vue'

const message = useMessage()
const route = useRoute()

const datasets = ref([])
const promptNames = ref([])
const promptVersions = ref({})
const evaluators = ref([])
const history = ref([])
const matrix = ref(null)
const estimate = ref(null)
const diff = ref(null)
const estimating = ref(false)
const running = ref(false)
const diffing = ref(false)
const promoting = ref(false)
const showPromote = ref(false)
const promoteColumn = ref(null)
const promoteForm = reactive({ runLabel: '', commitSha: '' })
let pollTimer = null

const form = reactive({
  name: '',
  datasetId: null,
  sampleLimit: 10,
  evaluatorIds: [],
  columns: []
})
const diffForm = reactive({ baseColumn: null, targetColumn: null, judgeModel: '' })

const datasetOptions = computed(() => datasets.value.map((d) => ({ label: d.datasetKey, value: d.id })))
const promptOptions = computed(() => promptNames.value.map((n) => ({ label: n, value: n })))
const evaluatorOptions = computed(() => evaluators.value.map((e) => ({
  label: e.evaluatorKey + (e.version ? ' v' + e.version : ''), value: e.id
})))
const canRun = computed(() => !!form.datasetId && form.columns.length > 0)
const isTerminal = computed(() => {
  const s = matrix.value?.status
  return s === 'COMPLETED' || s === 'PARTIAL' || s === 'FAILED'
})
const columnOptions = computed(() => (matrix.value?.columns || [])
  .map((c) => ({ label: c.label || c.key, value: c.key })))

function statusType(status) {
  if (status === 'COMPLETED') return 'success'
  if (status === 'PARTIAL') return 'warning'
  if (status === 'FAILED') return 'error'
  return 'default'
}

function versionOptionsOf(name) {
  return (promptVersions.value[name] || []).map((v) => ({
    label: 'v' + v.version + (v.labels?.length ? '（' + v.labels.join(',') + '）' : ''), value: v.version
  }))
}

let columnSeq = 0
function newColumn(seed = {}) {
  columnSeq++
  return {
    key: 'c' + columnSeq,
    label: '列 ' + columnSeq,
    promptName: seed.promptName || null,
    promptVersion: seed.promptVersion || null,
    prompt: seed.prompt || '',
    systemPrompt: '',
    model: seed.model || 'gpt-4o-mini',
    temperature: 0.2
  }
}

function addColumn() { form.columns.push(newColumn()) }
function removeColumn(index) { form.columns.splice(index, 1) }

async function onPromptName(col, name) {
  col.promptName = name
  col.promptVersion = null
  if (!name) return
  try {
    const versions = await api.promptVersions(name)
    promptVersions.value[name] = versions || []
    const production = (versions || []).find((v) => (v.labels || []).includes('production'))
    col.promptVersion = production ? production.version : (versions?.[0]?.version ?? null)
    if (!col.prompt) col.prompt = production?.prompt || versions?.[0]?.prompt || ''
    if (production?.config?.model) col.model = production.config.model
  } catch (e) { message.error(e.message) }
}

function onDatasetChange() {
  matrix.value = null
  estimate.value = null
  diff.value = null
}

function payload() {
  return {
    name: form.name || null,
    datasetId: form.datasetId,
    sampleLimit: form.sampleLimit,
    evaluatorIds: form.evaluatorIds,
    columns: form.columns.map((c, i) => ({
      key: 'c' + (i + 1),
      label: c.label,
      promptName: c.promptName || null,
      promptVersion: c.promptName ? c.promptVersion : null,
      prompt: c.prompt || null,
      systemPrompt: c.systemPrompt || null,
      model: c.model || null,
      temperature: c.temperature
    }))
  }
}

async function runEstimate() {
  estimating.value = true
  try {
    estimate.value = await api.estimatePlaygroundMatrix(payload())
  } catch (e) { message.error(e.message) }
  finally { estimating.value = false }
}

async function runMatrix() {
  if (!estimate.value) {
    message.warning('先看一次预估再运行：矩阵会实际调用 LLM')
    return runEstimate()
  }
  running.value = true
  try {
    const body = { ...payload(), confirmEstimate: true }
    const created = await api.createPlaygroundMatrix(body, 'matrix-' + Date.now() + '-' + Math.random().toString(16).slice(2, 8))
    matrix.value = created
    diff.value = null
    startPolling(created.id)
    message.success('矩阵已受理，正在执行')
    loadHistory()
  } catch (e) { message.error(e.message) }
  finally { running.value = false }
}

function startPolling(id) {
  stopPolling()
  pollTimer = setInterval(async () => {
    try {
      matrix.value = await api.playgroundMatrix(id)
      if (isTerminal.value) {
        stopPolling()
        loadHistory()
      }
    } catch (e) {
      stopPolling()
    }
  }, 1500)
}
function stopPolling() {
  if (pollTimer) clearInterval(pollTimer)
  pollTimer = null
}

const gridRows = computed(() => {
  const cells = matrix.value?.cells || []
  const byItem = new Map()
  for (const cell of cells) {
    if (!byItem.has(cell.itemId)) {
      byItem.set(cell.itemId, {
        itemId: cell.itemId,
        itemOrder: cell.itemOrder,
        input: cell.input,
        expectedOutput: cell.expectedOutput
      })
    }
    byItem.get(cell.itemId)[cell.columnKey] = cell
  }
  return [...byItem.values()].sort((a, b) => (a.itemOrder ?? 0) - (b.itemOrder ?? 0))
})

const gridColumns = computed(() => {
  const base = [
    { title: '#', key: 'itemOrder', width: 46, render: (r) => h('span', { class: 'mono' }, String((r.itemOrder ?? 0) + 1)) },
    { title: '样本输入', key: 'input', width: 180, ellipsis: { tooltip: true }, render: (r) => r.input || '—' },
    { title: '期望输出', key: 'expectedOutput', width: 140, ellipsis: { tooltip: true }, render: (r) => r.expectedOutput || '—' }
  ]
  const cols = (matrix.value?.columns || []).map((column) => ({
    title: column.label || column.key,
    key: column.key,
    width: 260,
    render: (row) => {
      const cell = row[column.key]
      if (!cell) return '—'
      if (cell.status === 'ERROR') {
        return h('span', { style: 'color:var(--error);font-size:12px' }, cell.error || 'ERROR')
      }
      if (cell.status !== 'OK') {
        return h('span', { class: 'page-sub' }, cell.status)
      }
      return h('div', { class: 'cell' }, [
        h('div', { class: 'cell-out', title: cell.output || '' }, cell.output || ''),
        h('div', { class: 'cell-meta' }, [
          cell.aggregateLabel
            ? h(NTag, {
                size: 'tiny', bordered: false,
                type: cell.aggregateLabel === 'PASS' ? 'success' : cell.aggregateLabel === 'FAIL' ? 'error' : 'warning'
              }, { default: () => cell.aggregateLabel })
            : null,
          h('span', { class: 'mono' }, cell.latencyMs != null ? cell.latencyMs + 'ms' : ''),
          h('span', { class: 'mono' }, cell.cost != null ? Number(cell.cost).toFixed(5) : '')
        ])
      ])
    }
  }))
  return [...base, ...cols]
})

const estimateColumns = [
  { title: '列', key: 'label', minWidth: 120 },
  { title: '模型', key: 'model', width: 140, render: (r) => h('span', { class: 'mono' }, r.model) },
  { title: '格', key: 'cells', width: 60, align: 'right' },
  { title: '输入 token', key: 'promptTokens', width: 100, align: 'right', render: (r) => h('span', { class: 'num' }, String(r.promptTokens)) },
  { title: '输出 token', key: 'completionTokens', width: 100, align: 'right', render: (r) => h('span', { class: 'num' }, String(r.completionTokens)) },
  { title: '成本', key: 'cost', width: 110, align: 'right', render: (r) => (r.cost == null
    ? h('span', { style: 'color:var(--warn)' }, '未计价')
    : h('span', { class: 'num' }, Number(r.cost).toFixed(4))) }
]

const diffColumns = [
  { title: '样本', key: 'itemId', width: 150, ellipsis: { tooltip: true }, render: (r) => h('span', { class: 'mono' }, r.itemId) },
  { title: '确定性', key: 'deterministic', width: 100 },
  {
    title: '偏好', key: 'preference', width: 120,
    render: (r) => h(NTag, {
      size: 'tiny', bordered: false,
      type: r.preference === 'IMPROVEMENT' ? 'success'
        : r.preference === 'REGRESSION' ? 'error'
          : r.preference === 'TRADEOFF' ? 'warning' : 'default'
    }, { default: () => r.preference })
  },
  { title: '原因', key: 'reasonCode', width: 200, ellipsis: { tooltip: true }, render: (r) => h('span', { class: 'mono' }, r.reasonCode || '') },
  { title: '说明', key: 'reason', minWidth: 200, ellipsis: { tooltip: true } }
]

const historyColumns = [
  { title: '矩阵', key: 'name', minWidth: 160, render: (r) => r.name || r.id },
  { title: '状态', key: 'status', width: 100, render: (r) => h(NTag, { size: 'tiny', bordered: false, type: statusType(r.status) }, { default: () => r.status }) },
  { title: '格', key: 'cellCount', width: 70, align: 'right' },
  { title: '失败', key: 'failedCells', width: 70, align: 'right' },
  { title: '成本', key: 'cost', width: 100, align: 'right', render: (r) => (r.cost == null ? '—' : h('span', { class: 'num' }, Number(r.cost).toFixed(4))) },
  { title: '创建时间', key: 'createdAt', width: 160, render: (r) => h('span', { class: 'mono' }, String(r.createdAt || '').replace('T', ' ').slice(0, 19)) },
  {
    title: '', key: 'ops', width: 90,
    render: (r) => h('a', { class: 'link', onClick: () => openMatrix(r.id) }, '查看')
  }
]

async function openMatrix(id) {
  try {
    matrix.value = await api.playgroundMatrix(id)
    diff.value = null
    if (!isTerminal.value) startPolling(id)
  } catch (e) { message.error(e.message) }
}

async function runDiff() {
  if (!matrix.value) return
  diffing.value = true
  try {
    const body = {
      baseColumn: diffForm.baseColumn,
      targetColumn: diffForm.targetColumn,
      judgeModels: diffForm.judgeModel ? [diffForm.judgeModel] : null
    }
    diff.value = await api.diffPlaygroundMatrix(matrix.value.id, body)
    message.success('对比完成')
  } catch (e) { message.error(e.message) }
  finally { diffing.value = false }
}

async function exportCsv() {
  try {
    const blob = await api.playgroundMatrixCsv(matrix.value.id)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `emonlens-matrix-${matrix.value.id}.csv`
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
  } catch (e) { message.error('导出失败：' + e.message) }
}

function openPromote() {
  promoteColumn.value = diffForm.targetColumn || matrix.value?.columns?.[0]?.key || null
  promoteForm.runLabel = ''
  promoteForm.commitSha = ''
  showPromote.value = true
}

async function doPromote() {
  if (!promoteColumn.value) { message.error('请选择要升格的列'); return }
  promoting.value = true
  try {
    const result = await api.promotePlaygroundColumn(matrix.value.id, promoteColumn.value, {
      runLabel: promoteForm.runLabel || null,
      commitSha: promoteForm.commitSha || null
    })
    message.success('已升格为正式实验：' + result.runId + (result.promptVersion ? '（prompt v' + result.promptVersion + '）' : ''))
    showPromote.value = false
  } catch (e) { message.error(e.message) }
  finally { promoting.value = false }
}

async function loadHistory() {
  try {
    history.value = await api.playgroundMatrices({ limit: 20 })
  } catch (e) { history.value = [] }
}

onMounted(async () => {
  try {
    datasets.value = (await api.datasets()) || []
    // 支持 ?datasetId= 深链（与实验排行一致）：矩阵配置可直接分享/复现，E2E 也有确定性入口
    const wanted = route.query.datasetId ? String(route.query.datasetId) : ''
    if (wanted && datasets.value.some((d) => d.id === wanted)) {
      form.datasetId = wanted
    }
  } catch (e) { datasets.value = [] }
  try {
    const page = await api.prompts({ pageNumber: 1, pageSize: 100 })
    promptNames.value = [...new Set((page.records || []).map((r) => r.name))]
  } catch (e) { promptNames.value = [] }
  try {
    const page = await api.evaluators({ pageNumber: 1, pageSize: 200 })
    evaluators.value = page.records || []
  } catch (e) { evaluators.value = [] }
  form.columns = [newColumn({ model: 'gpt-4o-mini' }), newColumn({ model: 'gpt-4o' })]
  loadHistory()
})

onBeforeUnmount(stopPolling)
</script>

<style scoped>
.page-hint { font-size: 11.5px; color: var(--text-muted); margin-top: 6px; line-height: 1.6; }
.col-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 10px; }
.col-card { border: 1px solid var(--border); border-radius: 6px; padding: 8px 10px; min-width: 0; }
.col-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.col-row { display: flex; align-items: center; gap: 6px; }
.col-meta { display: flex; align-items: center; gap: 8px; margin-top: 6px; }
.mono { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; font-size: 11.5px; }
.mt6 { margin-top: 6px; }
.mt8 { margin-top: 8px; }
.mt12 { margin-top: 12px; }
.flex-1 { flex: 1; }
.page-sub { font-size: 11.5px; color: var(--text-muted); }
.est-summary { display: flex; flex-wrap: wrap; gap: 18px; font-size: 12.5px; color: var(--text-secondary); }
.diff-bar { display: flex; align-items: center; gap: 8px; margin-top: 12px; flex-wrap: wrap; }
.diff-summary { display: flex; flex-wrap: wrap; gap: 16px; margin-top: 10px; font-size: 12.5px; color: var(--text-secondary); }
.cell-out { font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.cell-meta { display: flex; align-items: center; gap: 6px; margin-top: 2px; color: var(--text-muted); font-size: 11px; }
.link { color: var(--primary); cursor: pointer; }
</style>
