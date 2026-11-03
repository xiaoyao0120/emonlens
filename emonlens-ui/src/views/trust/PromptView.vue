<template>
  <div class="base-page">
    <div class="head-stack">
      <div class="page-toolbar">
        <div class="toolbar-actions">
          <n-button type="primary" size="small" @click="openCreate">＋ 新建提示词</n-button>
        </div>
      </div>
    </div>

    <n-card size="small" class="table-card">
      <n-data-table class="main-table" flex-height style="height: 100%" :scroll-x="900"
        :columns="columns" :data="records" size="small" :bordered="false" remote
        :pagination="pagination" :loading="loading">
        <template #empty><EmptyState text="还没有提示词：点右上角「＋ 新建提示词」发布首个版本；Agent 运行时可用 emonlens.get_prompt(name) 按标签拉取，用量与通过率自动统计" /></template>
      </n-data-table>
    </n-card>

    <!-- 版本抽屉 -->
    <n-drawer v-model:show="showVersions" :width="680">
      <n-drawer-content :title="'提示词：' + (currentName || '')" closable>
        <div class="label-bar">
          <n-tag v-for="l in productionLabels" :key="l" size="small" type="success" :bordered="false">{{ l }} → v{{ labelVersion(l) }}</n-tag>
          <span class="flex-1"></span>
          <n-button size="tiny" @click="resolveCheck">校验解析</n-button>
        </div>
        <!-- 版本对比（LangSmith prompt diff） -->
        <div v-if="versions.length >= 2" class="diff-bar">
          <span class="page-sub">版本对比：</span>
          <n-select v-model:value="diffA" size="tiny" style="width: 100px" :options="versionPickOptions" />
          <span class="page-sub">→</span>
          <n-select v-model:value="diffB" size="tiny" style="width: 100px" :options="versionPickOptions" />
          <n-button size="tiny" @click="showDiff = !showDiff">{{ showDiff ? '隐藏差异' : '显示差异' }}</n-button>
        </div>
        <div v-if="showDiff && versions.length >= 2" class="diff-panel">
          <div v-for="(line, i) in diffLines" :key="i" class="diff-line mono" :class="line.type">
            <span class="diff-mark">{{ line.mark }}</span><span>{{ line.text || ' ' }}</span>
          </div>
          <div v-if="!diffLines.length" class="page-sub">
            {{ diffA == null || diffB == null ? '请选择要对比的两个版本'
              : diffA === diffB ? '相同版本，无差异' : '两个版本内容完全一致' }}
          </div>
        </div>
        <!-- 版本用量（Langfuse prompt usage） -->
        <div v-if="usage.length" class="usage-block">
          <div class="page-sub" style="margin: 4px 0 6px">版本用量（SDK 上报 attributes.prompt_name / prompt_version 建立关联）</div>
          <n-data-table size="small" :bordered="false" :columns="usageColumns" :data="usage" :row-key="(r) => r.promptVersion"
            :max-height="280"
            :row-props="(r) => ({ style: 'cursor:pointer', onClick: () => drillPrompt(r) })" />
        </div>
        <!-- 评估证据（R1-3）：这个 prompt 版本被哪些 run 评过、Gate 结论是什么 -->
        <div v-if="promptRuns.length" class="usage-block">
          <div class="page-sub" style="margin: 4px 0 6px">评估证据（run 绑定 prompt 版本后自动归集；Gate 结论随 production 指针移动失效）</div>
          <n-data-table size="small" :bordered="false" :columns="promptRunColumns" :data="promptRuns"
            :row-key="(r) => r.runId" :max-height="220" />
        </div>
        <div v-for="v in versions" :key="v.id" class="ver-card">
          <div class="ver-head">
            <strong class="mono">v{{ v.version }}</strong>
            <n-tag v-for="l in v.labels" :key="l" size="tiny" :bordered="false"
              :type="l === 'production' ? 'success' : l === 'latest' ? 'info' : 'default'">{{ l }}</n-tag>
            <span class="flex-1"></span>
            <span class="page-sub">{{ v.createdBy }} · {{ fmtTs(v.createdAt) }}</span>
          </div>
          <CodeBlock :content="v.prompt" :max-height="160" />
          <div v-if="extractVars(v.prompt).length" class="var-row">
            <span class="page-sub">变量：</span>
            <n-tag v-for="name in extractVars(v.prompt)" :key="name" size="tiny" :bordered="false" type="warning"
              class="mono">{{ varTag(name) }}</n-tag>
          </div>
          <div class="ver-ops">
            <n-button size="tiny" :disabled="v.labels.includes('production')" @click="doLabel('production', v.version)">
              设为 production
            </n-button>
            <n-button size="tiny" style="margin-left:6px" @click="openEdit(v)">复制为新版本</n-button>
            <n-button size="tiny" type="primary" ghost style="margin-left:6px"
              @click="router.push('/playground?prompt=' + encodeURIComponent(v.prompt) + '&name=' + encodeURIComponent(currentName) + '&version=' + v.version)">
              在 Playground 试跑 →
            </n-button>
          </div>
        </div>
      </n-drawer-content>
    </n-drawer>

    <!-- 新建/另存新版本 -->
    <Modal v-model:show="showCreate" :width="620" :title="editing ? '另存为新版本：' + createForm.name : '新建提示词'">
      <n-form label-placement="top">
        <n-form-item label="名称（唯一 Key）" required>
          <n-input v-model:value="createForm.name" :disabled="editing" placeholder="如 support-assistant" />
        </n-form-item>
            <n-form-item label="提示词内容（支持 &#123;&#123;变量&#125;&#125; 占位）" required>
          <n-input v-model:value="createForm.prompt" type="textarea" :rows="8" class="mono" />
        </n-form-item>
        <div class="cfg-row">
          <n-form-item label="模型">
            <n-input v-model:value="createForm.model" placeholder="gpt-4o-mini" />
          </n-form-item>
          <n-form-item label="温度">
            <n-input-number v-model:value="createForm.temperature" :min="0" :max="2" :step="0.1" />
          </n-form-item>
          <n-form-item label="设为 production">
            <n-switch v-model:value="createForm.setProduction" />
          </n-form-item>
        </div>
      </n-form>
      <template #footer>
        <n-button size="small" :disabled="creating" @click="showCreate = false">取消</n-button>
        <n-button type="primary" size="small" :loading="creating" @click="doCreate">保存</n-button>
      </template>
    </Modal>
  </div>
</template>

<script setup>
import EmptyState from '@/components/EmptyState.vue'
import { computed, h, onMounted, reactive, ref, watch } from 'vue'
import { NButton, NPopconfirm, NTag } from 'naive-ui'
import { useMessage } from 'naive-ui'
import { useRouter } from 'vue-router'
import api from '@/api'
import CodeBlock from '@/components/CodeBlock.vue'

const message = useMessage()
const router = useRouter()
const records = ref([]), loading = ref(false)
const showVersions = ref(false), versions = ref([]), currentName = ref('')
const showCreate = ref(false), creating = ref(false), editing = ref(false)
const createForm = reactive({ name: '', prompt: '', model: 'gpt-4o-mini', temperature: 0.2, setProduction: false })
const pagination = reactive({ page: 1, pageSize: 10, itemCount: 0, showSizePicker: true, pageSizes: [10, 20, 50, 100],
  prefix: ({ itemCount }) => '共 ' + itemCount + ' 条',
  onChange: (p) => { pagination.page = p; load() },
  onUpdatePageSize: (s) => { pagination.pageSize = s; pagination.page = 1; load() } })

const columns = [
  { title: '名称', key: 'name', width: 170, render: (r) => h('span', { class: 'mono', style: 'cursor:pointer', onClick: () => openVersions(r.name) }, r.name) },
  { title: '版本', key: 'version', width: 110, render: (r) => h('span', {}, [
      h('span', { class: 'mono', style: 'font-weight:600' }, 'v' + r.version),
      h('span', { style: 'color:var(--text-muted);font-size:11px;margin-left:6px' }, `共 ${r.versionCount ?? 1} 版`)
    ]) },
  { title: 'production', key: 'productionVersion', width: 120, render: (r) => r.productionVersion != null
      ? h(NTag, { size: 'small', bordered: false, type: 'success' }, { default: () => '→ v' + r.productionVersion })
      : h('span', { style: 'color:var(--text-muted)' }, '—') },
  { title: '标签', key: 'labels', width: 150, render: (r) => (r.labels || []).map((l) =>
      h(NTag, { size: 'small', bordered: false, type: l === 'production' ? 'success' : l === 'latest' ? 'info' : 'default', style: 'margin-right:4px' }, { default: () => l })) },
  { title: '内容预览', key: 'prompt', minWidth: 240, ellipsis: { tooltip: true }, render: (r) => r.prompt },
  { title: '更新时间', key: 'createdAt', width: 150, render: (r) => h('span', { class: 'num', style: 'font-size:11.5px' }, fmtTs(r.createdAt)) },
  { title: '操作', key: 'ops', width: 180, render: (r) => h('span', {}, [
      h(NButton, { size: 'tiny', style: 'margin-right:6px', onClick: () => openVersions(r.name) }, { default: () => '版本' }),
      h(NButton, { size: 'tiny', style: 'margin-right:6px', onClick: () => quickPlay(r) }, { default: () => '试跑' }),
      h(NPopconfirm, { onPositiveClick: () => doDelete(r) }, {
        trigger: () => h(NButton, { size: 'tiny', type: 'error', ghost: true }, { default: () => '删除' }),
        default: () => `确认删除「${r.name}」的全部 ${r.versionCount ?? 1} 个版本？已上报的历史调用与用量统计不受影响。`
      })
    ]) }
]

// ---------- 版本用量 ----------
const usage = ref([])
const usageColumns = [
  { title: '版本', key: 'promptVersion', width: 70, render: (r) => h('span', { class: 'mono' }, r.promptVersion ? 'v' + r.promptVersion : '未标') },
  { title: '调用数', key: 'traceCount', width: 70, align: 'right', render: (r) => h('span', { class: 'mono' }, String(r.traceCount)) },
  { title: '错误调用', key: 'errorTraces', width: 80, align: 'right', render: (r) => r.errorTraces > 0
    ? h(NTag, { size: 'tiny', bordered: false, type: 'error' }, { default: () => String(r.errorTraces) })
    : h('span', { style: 'color:var(--text-muted)' }, '0') },
  { title: '通过率', key: 'passRate', width: 80, align: 'right', render: (r) => r.passRate == null
    ? h('span', { style: 'color:var(--text-muted)' }, '—')
    : h('span', { class: 'mono', style: 'color:' + (r.passRate >= 80 ? 'var(--success)' : r.passRate >= 50 ? 'var(--warn)' : 'var(--error)') }, r.passRate + '%') },
  { title: '最近使用', key: 'lastUsedAt', width: 150, render: (r) => h('span', { class: 'mono', style: 'font-size:11px' }, r.lastUsedAt ? String(r.lastUsedAt).replace('T', ' ').slice(0, 16) : '—') }
]
async function loadUsage() {
  usage.value = []
  if (!currentName.value) return
  try { usage.value = await api.promptUsage(currentName.value) || [] } catch (e) { /* ignore */ }
}

// ---------- 评估证据（R1-3） ----------
const promptRuns = ref([])
const promptRunColumns = [
  { title: 'run', key: 'runId', width: 140, ellipsis: { tooltip: true },
    render: (r) => h('span', { class: 'mono', style: 'cursor:pointer', onClick: () => openRun(r) },
      String(r.runId).slice(0, 10)) },
  { title: 'prompt 版本', key: 'promptVersion', width: 100,
    render: (r) => h('span', { class: 'mono' }, r.promptVersion ? 'v' + r.promptVersion : '—') },
  { title: '门禁', key: 'gateDecision', width: 100,
    render: (r) => r.gateDecision
      ? h(NTag, { size: 'tiny', bordered: false,
          type: r.gateDecision === 'PASS' ? 'success' : r.gateDecision === 'FAIL' ? 'error' : 'warning' },
        { default: () => r.gateDecision })
      : h('span', { style: 'color:var(--text-muted)' }, '—') },
  { title: '通过/失败', key: 'passCount', width: 100, align: 'right',
    render: (r) => h('span', { class: 'mono' }, `${r.passCount ?? 0}/${r.failCount ?? 0}`) },
  { title: '标签', key: 'runLabel', width: 90, render: (r) => r.runLabel || '—' },
  { title: '提交', key: 'commitSha', width: 100, render: (r) => r.commitSha ? String(r.commitSha).slice(0, 8) : '—' },
  { title: '时间', key: 'startedAt', width: 150,
    render: (r) => h('span', { class: 'num', style: 'font-size:11px' }, r.startedAt ? String(r.startedAt).replace('T', ' ').slice(0, 16) : '—') }
]
async function loadPromptRuns() {
  promptRuns.value = []
  if (!currentName.value) return
  try { promptRuns.value = await api.promptRuns(currentName.value) || [] } catch (e) { /* ignore */ }
}
function openRun(r) {
  showVersions.value = false
  router.push('/datasets/' + r.datasetId + '/runs/' + r.runId)
}
function drillPrompt(r) {
  showVersions.value = false
  router.push({ path: '/traces', query: { promptName: currentName.value, promptVersion: r.promptVersion, timeRange: 'all' } })
}

// ---------- 版本 diff（LCS 行级） ----------
const diffA = ref(null), diffB = ref(null), showDiff = ref(true)
const versionPickOptions = computed(() => versions.value.map((v) => ({ label: 'v' + v.version, value: v.version })))
function textOf(ver) {
  return (versions.value.find((v) => v.version === ver) || {}).prompt || ''
}
const diffLines = computed(() => {
  if (diffA.value == null || diffB.value == null || diffA.value === diffB.value) return []
  const a = textOf(diffA.value).split('\n')
  const b = textOf(diffB.value).split('\n')
  const n = a.length, m = b.length
  // LCS 表
  const dp = Array.from({ length: n + 1 }, () => new Array(m + 1).fill(0))
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1])
    }
  }
  const out = []
  let i = 0, j = 0
  while (i < n && j < m) {
    if (a[i] === b[j]) { out.push({ type: 'same', mark: ' ', text: a[i] }); i++; j++ }
    else if (dp[i + 1][j] >= dp[i][j + 1]) { out.push({ type: 'del', mark: '−', text: a[i] }); i++ }
    else { out.push({ type: 'add', mark: '+', text: b[j] }); j++ }
  }
  while (i < n) { out.push({ type: 'del', mark: '−', text: a[i] }); i++ }
  while (j < m) { out.push({ type: 'add', mark: '+', text: b[j] }); j++ }
  return out
})
// 打开抽屉时默认 A=最新上一版、B=最新；抽屉内新建版本后（versions 变化）也要补默认值
watch(versions, (vs) => {
  if (vs.length >= 2 && (diffA.value == null || diffB.value == null)) {
    diffA.value = vs[1]?.version ?? null
    diffB.value = vs[0]?.version ?? null
  }
}, { immediate: true })

function fmtTs(s) {
  if (!s) return '—'
  return String(s).replace('T', ' ').slice(0, 19)
}
/** 提取 {{变量}} 占位（与 Playground 同规则） */
function extractVars(prompt) {
  const names = []
  for (const m of String(prompt || '').matchAll(/\{\{\s*([a-zA-Z0-9_]+)\s*}}/g)) {
    if (!names.includes(m[1])) names.push(m[1])
  }
  return names
}
function varTag(name) {
  return '{{' + name + '}}'
}
const productionLabels = computed(() => ['production', 'latest'])
function labelVersion(label) {
  const hit = versions.value.find((v) => v.labels.includes(label))
  if (hit) return hit.version
  // latest 未显式打标签：解析接口回退到最大版本（/resolve 的兜底语义），展示与之一致
  if (label === 'latest' && versions.value.length) {
    return Math.max(...versions.value.map((v) => v.version))
  }
  return '—'
}

async function load() {
  loading.value = true
  try {
    const page = await api.prompts({ pageNumber: pagination.page, pageSize: pagination.pageSize })
    records.value = page.records || []
    pagination.itemCount = page.total || 0
  } finally { loading.value = false }
}
async function openVersions(name) {
  currentName.value = name
  await refreshVersions()
  loadUsage()
  loadPromptRuns()
  showVersions.value = true
}
async function refreshVersions() {
  versions.value = await api.promptVersions(currentName.value)
}
async function doLabel(label, version) {
  try {
    await api.setPromptLabel(currentName.value, { label, version })
    message.success(`已将 ${label} 指向 v${version}`)
    await refreshVersions()
    load()
    loadPromptRuns()
  } catch (e) { message.error(e.message) }
}
async function resolveCheck() {
  try {
    const hit = await api.resolvePrompt(currentName.value, 'production')
    message.success(`production → v${hit.version}`)
  } catch (e) { message.error(e.message) }
}
function quickPlay(r) {
  router.push('/playground?prompt=' + encodeURIComponent(r.prompt) + '&name=' + encodeURIComponent(r.name) + '&version=' + r.version)
}
async function doDelete(r) {
  try {
    const res = await api.deletePrompt(r.name)
    message.success(`已删除 ${r.name}（${res.deletedVersions} 个版本）`)
    load()
  } catch (e) { message.error(e.message) }
}
function openCreate() {
  editing.value = false
  Object.assign(createForm, { name: '', prompt: '', model: 'gpt-4o-mini', temperature: 0.2, setProduction: false })
  showCreate.value = true
}
function openEdit(v) {
  editing.value = true
  const cfg = v.config || {}
  Object.assign(createForm, {
    name: v.name,
    prompt: v.prompt,
    model: cfg.model || 'gpt-4o-mini',
    temperature: cfg.temperature ?? 0.2,
    setProduction: false
  })
  showCreate.value = true
}
async function doCreate() {
  if (!createForm.name.trim() || !createForm.prompt.trim()) { message.error('名称与内容必填'); return }
  creating.value = true
  try {
    await api.createPrompt({
      name: createForm.name.trim(),
      prompt: createForm.prompt,
      config: { model: createForm.model, temperature: createForm.temperature },
      setProduction: createForm.setProduction
    })
    message.success(editing.value ? '新版本已保存' : '提示词已创建')
    showCreate.value = false
    if (editing.value && showVersions.value) await refreshVersions()
    load()
  } catch (e) { message.error(e.message) }
  finally { creating.value = false }
}
onMounted(load)
</script>

<style scoped>
.label-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.flex-1 { flex: 1; }
.ver-card { border: 1px solid var(--border); border-radius: 8px; padding: 10px 12px; margin-bottom: 10px; }
.ver-head { display: flex; align-items: center; gap: 6px; margin-bottom: 6px; }
.ver-ops { margin-top: 8px; }
.cfg-row { display: flex; gap: 12px; }
.mono { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; }
.diff-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.diff-panel { background: var(--code-chrome); border: 1px solid var(--border); border-radius: 6px; padding: 8px 10px; margin-bottom: 12px; max-height: 320px; overflow: auto; }
.diff-line { display: flex; gap: 8px; font-size: 12px; line-height: 1.55; white-space: pre-wrap; word-break: break-word; }
.diff-mark { flex: 0 0 12px; user-select: none; }
.diff-line.add { background: var(--diff-new-bg); color: var(--diff-new-text); }
.diff-line.del { background: var(--diff-old-bg); color: var(--diff-old-text); }
.usage-block { margin-bottom: 10px; }
.var-row { display: flex; align-items: center; gap: 4px; margin-top: 6px; flex-wrap: wrap; }
</style>