<template>
  <div class="base-page">
    <div class="head-stack">
      <div class="page-toolbar">
        <div class="toolbar-filters">
          <n-select v-model:value="sourceName" placeholder="从提示词库填充" size="small" style="width: 200px" clearable
            :options="promptOptions" @update:value="onSourceName" />
          <n-select v-model:value="sourceVersion" placeholder="版本" size="small" style="width: 110px" clearable
            :options="versionOptions" :disabled="!sourceName" />
          <n-button size="small" :disabled="!sourceName" @click="fillFromPrompt">填充</n-button>
        </div>
        <div class="toolbar-actions">
          <n-button size="small" type="primary" ghost @click="router.push('/matrix')">矩阵实验 →</n-button>
          <n-button size="small" type="primary" ghost @click="openSave">保存为提示词</n-button>
        </div>
      </div>
    </div>

    <div class="pg-split">
      <!-- 左：输入 -->
      <div class="pg-left">
        <n-card size="small" title="System Prompt（可选）">
          <n-input v-model:value="form.systemPrompt" type="textarea" :rows="2" placeholder="设定角色，如：你是一名客服助手…" />
        </n-card>
        <n-card size="small" class="mt12" title="Prompt（支持 &#123;&#123;变量&#125;&#125; 占位）">
          <n-input v-model:value="form.prompt" type="textarea" :rows="9" class="mono" placeholder="如：客户资料：{{context}}&#10;问题：{{question}}" />
          <!-- 变量编辑 -->
          <div v-if="variables.length" class="var-block">
            <div class="var-title">变量（从模板自动识别）</div>
            <div v-for="v in variables" :key="v" class="var-row">
              <span class="mono var-key">&#123;&#123;{{ v }}&#125;&#125;</span>
              <n-input v-model:value="form.variables[v]" size="small" :placeholder="'填写 ' + v" />
            </div>
          </div>
          <div class="cfg-row">
            <n-form-item label="模型" label-placement="top">
              <n-input v-model:value="form.model" size="small" placeholder="gpt-4o-mini" />
            </n-form-item>
            <n-form-item label="温度" label-placement="top">
              <n-input-number v-model:value="form.temperature" size="small" :min="0" :max="2" :step="0.1" style="width: 110px" />
            </n-form-item>
            <n-form-item label="　" label-placement="top">
              <n-button type="primary" size="small" :loading="running" @click="run">▶ 运行</n-button>
            </n-form-item>
          </div>
        </n-card>
      </div>

      <!-- 右：输出 -->
      <div class="pg-right">
        <n-card size="small" title="输出">
          <template #header-extra>
            <span v-if="lastResult" class="page-sub">
              <template v-if="lastResult.model">{{ lastResult.model }}</template>
              <template v-if="lastResult.latencyMs != null"> · {{ lastResult.latencyMs }}ms</template>
            </span>
          </template>
          <template v-if="lastResult">
            <n-tag size="small" :bordered="false"
              :type="lastResult.status === 'OK' ? 'success' : lastResult.status === 'LLM_UNAVAILABLE' ? 'warning' : 'error'">
              {{ statusText(lastResult.status) }}
            </n-tag>
            <div v-if="lastResult.status === 'LLM_UNAVAILABLE'" class="llm-hint">
              LLM 端点未配置或不可用：在 <code>application.yml</code> 的
              <code>emonlens.eval.llm.base-url / api-key</code> 配置 OpenAI 兼容端点后重试。
              <div class="mono err-msg">{{ lastResult.message }}</div>
            </div>
            <div v-else-if="lastResult.status === 'ERROR'" class="llm-hint">
              调用失败：<span class="mono">{{ lastResult.message }}</span>
            </div>
            <template v-else>
              <CodeBlock class="mt8" :content="lastResult.output" label="completion" :start-expanded="true" />
              <div class="sec-hint">渲染后的 Prompt</div>
              <CodeBlock :content="lastResult.renderedPrompt" label="rendered prompt" :max-height="140" />
            </template>
          </template>
          <EmptyState v-else text="填写提示词后点击运行，输出将显示在这里" />
        </n-card>

        <!-- A/B 对照（同 Prompt 双配置并排对比） -->
        <n-card size="small" class="mt12" title="A/B 对照">
          <template #header-extra>
            <n-button size="tiny" @click="abEnabled = !abEnabled">{{ abEnabled ? '收起' : '展开' }}</n-button>
          </template>
          <template v-if="abEnabled">
            <div class="ab-cfg">
              <span class="page-sub">B 配置：</span>
              <n-input v-model:value="abForm.model" size="small" style="width: 150px" placeholder="对照模型" />
              <n-input-number v-model:value="abForm.temperature" size="small" :min="0" :max="2" :step="0.1" style="width: 100px" />
              <n-button type="primary" size="small" :loading="abRunning" :disabled="!form.prompt.trim()" @click="runCompare">▶ 运行对照</n-button>
              <span class="page-sub">A = 上方当前配置；两路并发调用同一渲染后 Prompt</span>
            </div>
            <template v-if="abResult">
              <div class="ab-grid">
                <div v-for="side in abSides" :key="side.key" class="ab-pane">
                  <div class="ab-head">
                    <n-tag size="tiny" :bordered="false" :type="side.r.status === 'OK' ? 'success' : 'warning'">{{ side.key }}</n-tag>
                    <span class="page-sub mono">{{ side.r.model || '—' }} · t={{ side.temp }}<template v-if="side.r.latencyMs != null"> · {{ side.r.latencyMs }}ms</template></span>
                  </div>
                  <CodeBlock v-if="side.r.output" :content="side.r.output" :label="'输出 ' + side.key" :max-height="200" />
                  <div v-else class="page-sub">{{ side.r.message || '无输出' }}</div>
                </div>
              </div>
              <div v-if="abNote" class="ab-note">{{ abNote }}</div>
            </template>
            <EmptyState v-else text="配置 B 后运行对照，两路输出将并排显示" />
          </template>
        </n-card>

        <!-- 本次会话的运行历史 -->
        <n-card v-if="history.length" size="small" class="mt12" title="本次运行记录（点击查看完整输出）">
          <div v-for="(h, i) in history" :key="i" class="hist-row" @click="lastResult = h">
            <span class="num hist-time">{{ h.at }}</span>
            <n-tag size="tiny" :bordered="false" :type="h.status === 'OK' ? 'success' : 'warning'">{{ statusText(h.status) }}</n-tag>
            <span class="page-sub mono">{{ h.model || '—' }}<template v-if="h.latencyMs != null"> · {{ h.latencyMs }}ms</template></span>
            <span class="hist-out" :title="h.renderedPrompt || ''">in: {{ (h.renderedPrompt || '').slice(0, 46) || '—' }} ｜ out: {{ (h.output || h.message || '').slice(0, 46) }}</span>
          </div>
        </n-card>
      </div>
    </div>

    <!-- 数据集批量试跑（LangSmith Playground × Dataset） -->
    <n-card size="small" class="mt12" title="数据集批量试跑">
      <template #header-extra>
        <span v-if="batchProgress" class="page-sub">{{ batchProgress }}</span>
      </template>
      <div class="batch-row">
        <n-button v-for="d in batchDatasetOptions" :key="d.value" size="small"
          :type="batchDatasetId === d.value ? 'primary' : 'default'" @click="onBatchDataset(d.value)">
          {{ d.label }}
        </n-button>
        <span class="page-sub" v-if="!batchDatasetOptions.length">暂无数据集（可在「数据集」页创建）</span>
        <span class="page-sub">{{ batchItems.length ? batchItems.length + ' 个样本' : '（选择数据集后，样本 input 按下方映射填入变量）' }}</span>
        <span class="flex-1"></span>
        <n-button type="primary" size="small" :disabled="!batchDatasetId || !batchItems.length || batchRunning"
          :loading="batchRunning" @click="runBatch">▶ 运行全部{{ batchItems.length ? '（' + Math.min(batchItems.length, 20) + '）' : '' }}</n-button>
      </div>
      <div v-if="variables.length && batchDatasetId" class="var-map-block">
        <div class="var-title">变量映射</div>
        <div v-for="v in variables" :key="v" class="var-row">
          <span class="mono var-key">&#123;&#123;{{ v }}&#125;&#125;</span>
          <n-select v-model:value="batchMapping[v]" size="small" style="width: 220px"
            :options="batchMapOptions" />
        </div>
      </div>
      <div v-if="batchResults.length" class="batch-summary">
        <span>成功 <b class="num" style="color:var(--success)">{{ batchSummary.ok }}</b></span>
        <span>失败 <b class="num" style="color:var(--error)">{{ batchSummary.fail }}</b></span>
        <span v-if="batchSummary.avgLatency != null">平均延迟 <b class="num">{{ batchSummary.avgLatency }} ms</b></span>
        <span v-if="batchSummary.judged">期望对照 <b class="num" :style="{ color: batchSummary.hitPct >= 80 ? 'var(--success)' : batchSummary.hitPct >= 50 ? 'var(--warn)' : 'var(--error)' }">{{ batchSummary.hit }}/{{ batchSummary.judged }}（{{ batchSummary.hitPct }}%）</b></span>
        <span class="flex-1"></span>
        <n-button size="tiny" @click="exportBatch">导出 CSV</n-button>
      </div>
      <n-data-table v-if="batchResults.length" class="mt12" size="small" :bordered="false" :max-height="360"
        :columns="batchColumns" :data="batchResults" :row-key="(r) => r.idx" />
    </n-card>

    <!-- 保存为提示词（Playground → Prompt Hub 闭环） -->
    <Modal v-model:show="showSave" :width="480" title="保存为提示词">
      <n-form label-placement="top">
        <n-form-item :label="'提示词名称（同名自动 +1 版本；已有：' + (saveNameOptions.map(o => o.value).join('、') || '无') + '）'" required>
          <n-input v-model:value="saveForm.name" placeholder="如 credit-assistant" />
        </n-form-item>
        <n-form-item label="设为 production 标签">
          <n-switch v-model:value="saveForm.setProduction" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-button size="small" :disabled="saving" @click="showSave = false">取消</n-button>
        <n-button type="primary" size="small" :loading="saving" @click="doSave">保存</n-button>
      </template>
    </Modal>
  </div>
</template>

<script setup>
import { computed, h, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NTag, useMessage } from 'naive-ui'
import api from '@/api'
import { toCsv, downloadCsv } from '@/common/csv'
import CodeBlock from '@/components/CodeBlock.vue'
import EmptyState from '@/components/EmptyState.vue'
import Modal from '@/components/Modal.vue'

const message = useMessage()
const route = useRoute()
const router = useRouter()
const running = ref(false)
const lastResult = ref(null)
const history = ref([])
const loadedFromPrompt = ref('')
const form = reactive({
  systemPrompt: '',
  prompt: '你是客服助手。请根据工单内容回答问题。\n工单内容：{{context}}\n问题：{{question}}',
  variables: {},
  model: 'gpt-4o-mini',
  temperature: 0.2
})

// 从模板提取变量
const variables = computed(() => {
  const out = new Set()
  for (const m of (String(form.prompt) + ' ' + String(form.systemPrompt)).matchAll(/\{\{\s*([a-zA-Z0-9_]+)\s*}}/g)) {
    out.add(m[1])
  }
  return [...out]
})

// ---------- A/B 对照（同 Prompt 双配置并发） ----------
const abEnabled = ref(false)
const abForm = reactive({ model: 'gpt-4o-mini', temperature: 0.7 })
const abRunning = ref(false)
const abResult = ref(null)
const abSides = computed(() => {
  if (!abResult.value) return []
  return [
    { key: 'A', r: abResult.value.a, temp: abResult.value.tempA },
    { key: 'B', r: abResult.value.b, temp: abResult.value.tempB }
  ]
})
const abNote = computed(() => {
  if (!abResult.value) return null
  const { a, b } = abResult.value
  if (a.status !== 'OK' || b.status !== 'OK') return '存在失败侧，无法对比（若 LLM 未配置请先在 application.yml 配置端点）'
  const dl = (b.latencyMs ?? 0) - (a.latencyMs ?? 0)
  const la = (a.output || '').length, lb = (b.output || '').length
  return `延迟：A ${a.latencyMs}ms · B ${b.latencyMs}ms（B ${dl >= 0 ? '慢' : '快'} ${Math.abs(dl)}ms）｜ 输出长度：A ${la} · B ${lb} 字符`
})
async function runCompare() {
  if (!form.prompt.trim()) { message.error('请填写提示词'); return }
  abRunning.value = true
  try {
    const vars = { ...form.variables }
    const [a, b] = await Promise.all([
      api.playgroundRun({ systemPrompt: form.systemPrompt, prompt: form.prompt, variables: vars, model: form.model, temperature: form.temperature }),
      api.playgroundRun({ systemPrompt: form.systemPrompt, prompt: form.prompt, variables: vars, model: abForm.model, temperature: abForm.temperature })
    ])
    abResult.value = { a, b, tempA: form.temperature, tempB: abForm.temperature }
  } catch (e) { message.error(e.message) }
  finally { abRunning.value = false }
}

// 提示词库填充
const promptOptions = ref([])
const sourceName = ref(null)
const sourceVersion = ref(null)
const versionOptions = ref([])
let versionsCache = []

function statusText(s) {
  return s === 'OK' ? '成功' : s === 'LLM_UNAVAILABLE' ? 'LLM 不可用' : '失败'
}
function onSourceName(name) {
  sourceVersion.value = null
  versionOptions.value = []
  versionsCache = []
  if (!name) return
  api.promptVersions(name).then((vs) => {
    versionsCache = vs
    versionOptions.value = vs.map((v) => ({ label: 'v' + v.version + (v.labels.length ? '（' + v.labels.join(',') + '）' : ''), value: v.version }))
  }).catch(() => {})
}
function fillFromPrompt() {
  const v = versionsCache.find((x) => x.version === sourceVersion.value) || versionsCache[0]
  if (!v) return
  form.prompt = v.prompt
  const cfg = v.config || {}
  if (cfg.model) form.model = cfg.model
  if (cfg.temperature != null) form.temperature = Number(cfg.temperature)
  message.success('已填充 ' + v.name + ' v' + v.version)
}

// ---------- 数据集批量试跑（LangSmith Playground × Dataset） ----------
const batchDatasetId = ref(null)
const batchDatasetOptions = ref([])
const batchItems = ref([])
const batchMapping = reactive({})
const batchRunning = ref(false)
const batchProgress = ref('')
const batchResults = ref([])
const batchMapOptions = [
  { label: '← 数据集输入（input）', value: 'input' },
  { label: '← 数据集期望输出', value: 'expected' },
  { label: '← 用上方手填变量值', value: 'fixed' }
]
async function loadBatchDatasets() {
  try {
    const list = await api.datasets()
    batchDatasetOptions.value = (list || []).map((d) => ({ label: d.datasetKey, value: d.id }))
  } catch (e) { batchDatasetOptions.value = [] }
}
async function onBatchDataset(id) {
  batchDatasetId.value = id
  batchItems.value = []
  batchResults.value = []
  batchProgress.value = ''
  if (!id) return
  try {
    const d = await api.datasetDetail(id)
    batchItems.value = (d.items || []).slice(0, 20)
    // 变量映射默认：第一个变量 ← input，其余 ← fixed
    variables.value.forEach((v, i) => { batchMapping[v] = i === 0 ? 'input' : 'fixed' })
  } catch (e) { message.error(e.message || '数据集加载失败') }
}
function mappedVars(item) {
  const out = {}
  for (const v of variables.value) {
    const mode = batchMapping[v] || 'fixed'
    if (mode === 'input') out[v] = item.input || ''
    else if (mode === 'expected') out[v] = item.expectedOutput || ''
    else out[v] = form.variables[v] || ''
  }
  return out
}
const batchColumns = [
  { title: '#', key: 'idx', width: 46, render: (r) => h('span', { class: 'mono', style: 'color:var(--text-muted)' }, String(r.idx + 1)) },
  { title: '样本输入', key: 'input', minWidth: 180, ellipsis: { tooltip: true }, render: (r) => r.item?.input || '—' },
  { title: '输出', key: 'output', minWidth: 240, ellipsis: { tooltip: true }, render: (r) => r.output || (r.message ? h('span', { style: 'color:var(--text-muted);font-size:12px' }, r.message) : '—') },
  { title: '状态', key: 'status', width: 110, render: (r) => statusTagB(r.status) },
  { title: '延迟', key: 'latencyMs', width: 90, align: 'right', render: (r) => r.latencyMs != null ? h('span', { class: 'mono' }, r.latencyMs + ' ms') : '—' },
  { title: '期望输出', key: 'expected', minWidth: 160, ellipsis: { tooltip: true }, render: (r) => r.item?.expectedOutput || '—' },
  { title: '对照', key: 'expectVerdict', width: 80, render: (r) => {
      if (r.expectVerdict == null) return h('span', { style: 'color:var(--text-muted);font-size:12px' }, '—')
      return h(NTag, { size: 'tiny', bordered: false, type: r.expectVerdict ? 'success' : 'error' },
        { default: () => r.expectVerdict ? '命中' : '未命中' })
    } }
]
async function runBatch() {
  if (!batchDatasetId.value || !batchItems.value.length) return
  batchRunning.value = true
  batchResults.value = []
  const items = batchItems.value
  let done = 0
  const results = new Array(items.length)
  // 并发 3 逐条调用既有单条接口
  const worker = async () => {
    while (true) {
      const i = nextIdx()
      if (i == null) return
      const item = items[i]
      try {
        const r = await api.playgroundRun({
          systemPrompt: form.systemPrompt,
          prompt: form.prompt,
          model: form.model,
          temperature: form.temperature,
          variables: mappedVars(item)
        })
        const rec = { idx: i, item, status: r.status, output: r.output, message: r.message, latencyMs: r.latencyMs }
        rec.expectVerdict = expectVerdictOf(rec)
        results[i] = rec
      } catch (e) {
        const rec = { idx: i, item, status: 'ERROR', message: e.message }
        rec.expectVerdict = expectVerdictOf(rec)
        results[i] = rec
      }
      done++
      batchProgress.value = '已完成 ' + done + ' / ' + items.length
      batchResults.value = results.filter(Boolean)
    }
  }
  let cursor = 0
  const nextIdx = () => (cursor < items.length ? cursor++ : null)
  await Promise.all([worker(), worker(), worker()])
  batchProgress.value = '完成：' + results.filter((r) => r && r.status === 'OK').length + ' 成功 / ' + items.length
  batchRunning.value = false
}

// ---------- 批量结果小结 / 期望对照 / 导出 ----------
const batchSummary = computed(() => {
  const rs = batchResults.value.filter(Boolean)
  const ok = rs.filter((r) => r.status === 'OK')
  const lat = ok.filter((r) => r.latencyMs != null)
  const judged = rs.filter((r) => r.expectVerdict != null)
  const hit = judged.filter((r) => r.expectVerdict === true).length
  return {
    ok: ok.length, fail: rs.length - ok.length,
    avgLatency: lat.length ? Math.round(lat.reduce((a, r) => a + r.latencyMs, 0) / lat.length) : null,
    judged: judged.length, hit, hitPct: judged.length ? Math.round(hit * 100 / judged.length) : null
  }
})
// 期望输出对照：期望文本按非中文字符切段，任一段出现在输出中即命中（轻量包含性判定）
function expectVerdictOf(r) {
  const expected = String(r.item?.expectedOutput || '').trim()
  if (!expected || r.status !== 'OK' || !r.output) return null
  const out = String(r.output)
  const keywords = expected.split(/[，,。；;\s]+/).map((s) => s.trim()).filter((s) => s.length >= 2)
  if (!keywords.length) return out.includes(expected) ? true : null
  return keywords.some((k) => out.includes(k))
}
async function exportBatch() {
  const rows = batchResults.value.filter(Boolean)
  const headers = ['#', '样本输入', '期望输出', '输出', '状态', '对照', '延迟ms']
  const csv = toCsv(headers, rows.map((r) => [
    r.idx + 1, r.item?.input || '', r.item?.expectedOutput || '',
    r.output || '', r.status || '', r.expectVerdict == null ? '' : (r.expectVerdict ? '命中' : '未命中'),
    r.latencyMs ?? ''
  ]))
  downloadCsv('emonlens-playground-batch.csv', csv)
}
function statusTagB(s) {
  if (s === 'OK') return h(NTag, { size: 'small', bordered: false, type: 'success' }, { default: () => '成功' })
  if (s === 'LLM_UNAVAILABLE') return h(NTag, { size: 'small', bordered: false, type: 'warning' }, { default: () => 'LLM 未配置' })
  return h(NTag, { size: 'small', bordered: false, type: 'error' }, { default: () => '错误' })
}

// ---------- 保存为提示词 ----------
const showSave = ref(false), saving = ref(false)
const saveForm = reactive({ name: '', setProduction: false })
const saveNameOptions = ref([])
async function openSave() {
  if (!form.prompt.trim()) { message.error('请先填写要保存的提示词内容'); return }
  saveForm.name = sourceName.value || ''
  saveForm.setProduction = false
  try {
    const page = await api.prompts({ pageNumber: 1, pageSize: 100 })
    saveNameOptions.value = (page.records || []).map((r) => ({ label: r.name, value: r.name }))
  } catch (e) { saveNameOptions.value = [] }
  showSave.value = true
}
async function doSave() {
  const name = String(saveForm.name || '').trim()
  if (!name) { message.error('请填写提示词名称'); return }
  saving.value = true
  try {
    await api.createPrompt({ name, prompt: form.prompt, labels: [], setProduction: saveForm.setProduction })
    message.success('已保存为新版本：' + name)
    showSave.value = false
    sourceName.value = null
  } catch (e) { message.error(e.message) }
  finally { saving.value = false }
}

async function run() {
  if (!form.prompt.trim()) { message.error('请填写提示词'); return }
  running.value = true
  try {
    const result = await api.playgroundRun({
      systemPrompt: form.systemPrompt,
      prompt: form.prompt,
      variables: { ...form.variables },
      model: form.model,
      temperature: form.temperature
    })
    result.at = new Date().toTimeString().slice(0, 8)
    lastResult.value = result
    history.value = [result, ...history.value].slice(0, 8)
  } catch (e) { message.error(e.message) }
  finally { running.value = false }
}

onMounted(async () => {
  try {
    const page = await api.prompts({ pageNumber: 1, pageSize: 100 })
    promptOptions.value = [...new Set((page.records || []).map((r) => r.name))].map((n) => ({ label: n, value: n }))
  } catch (e) { /* ignore */ }
  // 从提示词页「在 Playground 试跑」跳转预载（/playground?prompt=..&name=..&version=..）
  const q = route.query
  if (q.prompt) {
    form.prompt = String(q.prompt)
    loadedFromPrompt.value = q.name
      ? `${q.name}${q.version ? ' v' + q.version : ''}`
      : ''
  }
  loadBatchDatasets()
})
</script>

<style scoped>
.pg-split { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 12px; flex: 1; align-items: start; }
@media (max-width: 1100px) { .pg-split { grid-template-columns: 1fr; } }
.mt12 { margin-top: 12px; }
.mt8 { margin-top: 8px; }
.mono { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; }
.page-sub { font-size: 11.5px; color: var(--text-muted); }
.var-block { margin-top: 10px; }
.var-title { font-size: 12px; font-weight: 600; color: var(--text-secondary); margin-bottom: 6px; }
.var-row { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.var-key { flex: 0 0 110px; font-size: 11.5px; color: var(--primary); background: var(--primary-bg); border-radius: 4px; padding: 3px 8px; }
.cfg-row { display: flex; align-items: flex-end; gap: 12px; margin-top: 10px; }
.llm-hint { margin-top: 10px; font-size: 12.5px; color: var(--text-secondary); line-height: 1.7; }
.llm-hint code { background: var(--bg-subtle); border-radius: 3px; padding: 1px 5px; font-size: 11.5px; }
.err-msg { margin-top: 4px; color: var(--warn); font-size: 11.5px; }
.sec-hint { font-size: 11.5px; color: var(--text-muted); margin: 10px 0 6px; }
.hist-row { display: flex; align-items: center; gap: 10px; padding: 6px 4px; border-bottom: 1px dashed var(--border); cursor: pointer; font-size: 12px; }
.hist-row:hover { background: var(--bg-subtle); }
.hist-out { flex: 1; color: var(--text-secondary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hist-time { flex: 0 0 60px; font-size: 11px; color: var(--text-muted); }
.batch-row { display: flex; align-items: center; gap: 10px; }
.var-map-block { margin-top: 10px; padding: 8px 10px; background: var(--bg-subtle); border-radius: 6px; }
.flex-1 { flex: 1; }
.batch-summary { display: flex; align-items: center; gap: 18px; margin-top: 10px; font-size: 12.5px; color: var(--text-secondary); }
.ab-cfg { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.ab-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 10px; margin-top: 10px; }
@media (max-width: 1100px) { .ab-grid { grid-template-columns: 1fr; } }
.ab-pane { border: 1px solid var(--border); border-radius: 6px; padding: 8px 10px; min-width: 0; }
.ab-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.ab-note { margin-top: 8px; font-size: 12px; color: var(--text-secondary); background: var(--bg-subtle); border-radius: 6px; padding: 6px 10px; }
</style>