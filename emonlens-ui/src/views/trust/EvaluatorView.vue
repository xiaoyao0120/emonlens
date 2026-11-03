<template>
  <div class="base-page">
    <div class="head-stack">
      <div class="page-toolbar">
        <div class="toolbar-actions">
          <n-button size="small" @click="openSuites">评估套件</n-button>
          <n-button type="primary" size="small" @click="openCreate">＋ 新建评估器</n-button>
        </div>
      </div>
    </div>

    <n-card size="small" class="table-card">
      <n-data-table class="main-table" flex-height style="height: 100%" :scroll-x="975"
        :columns="columns" :data="records" size="small" :bordered="false" remote
        :pagination="pagination" :loading="loading">
        <template #empty><EmptyState text="还没有评估器：点右上角「＋ 新建评估器」创建第一个。支持 EXPRESSION（含参考答案精确匹配预设）/ REGEX / LLM_JUDGE（需先在「系统设置」配置 LLM 端点）/ TRAJECTORY（工具轨迹确定性检查）/ SDK（自定义 Python scorer）" /></template>
      </n-data-table>
    </n-card>

    <!-- 新建/编辑评估器 -->
    <Modal v-model:show="showCreate" :width="560" :title="editing ? '编辑评估器：' + createForm.evaluatorKey : '新建评估器'">
      <n-form label-placement="top">
        <n-form-item label="评估器 Key（唯一）" required>
          <n-input v-model:value="createForm.evaluatorKey" placeholder="如 answer-quality" :disabled="editing" class="mono" />
        </n-form-item>
        <n-form-item label="类型" required>
          <n-select v-model:value="createForm.type" :options="typeOptions" :disabled="editing" />
        </n-form-item>
        <div class="page-sub" style="margin:-6px 0 10px">{{ typeHint }}</div>
        <n-form-item label="质量维度（可选，对应 Domain Pack qualityModel.dimensions[].key）">
          <n-input v-model:value="createForm.qualityDimension" placeholder="如 accuracy / safety" class="mono" />
        </n-form-item>
        <template v-if="createForm.type === 'EXPRESSION'">
          <n-form-item label="预设">
            <n-select v-model:value="createForm.preset" :options="expressionPresets" />
          </n-form-item>
          <n-form-item v-if="createForm.preset === 'reference-exact-match'" label="通过条件（预设）">
            <n-input :value="exactMatchExpression" readonly class="mono" />
          </n-form-item>
          <n-form-item v-else label="通过条件（SpEL，true = PASS）" required>
            <n-input v-model:value="createForm.passWhen" placeholder="如 error_count == 0" class="mono" />
          </n-form-item>
          <div v-if="createForm.preset === 'reference-exact-match'" class="page-sub" style="margin:-6px 0 10px">
            仅统一换行并去除首尾空白，保留大小写和内部内容；不适用于同义答案。
            缺少参考或候选输出时返回 UNKNOWN，不判 FAIL。参考答案只进入评估器，不会传入 REPLAY 的生成请求。
          </div>
          <div class="page-sub" style="margin:-6px 0 10px">
            可用变量（写错变量名会报错，不会静默判 FAIL）：
            <span class="mono">trace_id, agent_key, agent_version, span_count, error_count, duration_ms, tool_calls, llm_calls, input, candidate_output, expected_output, reference_match</span>
          </div>
        </template>
        <template v-else-if="createForm.type === 'REGEX'">
          <n-form-item label="作用内容" required>
            <n-select v-model:value="createForm.field" :options="[
              { label: 'completion（模型输出）', value: 'completion' },
              { label: 'prompt（模型输入）', value: 'prompt' },
              { label: 'tool_payload（工具结果）', value: 'tool_payload' }
            ]" />
          </n-form-item>
          <n-form-item label="正则表达式" required>
            <n-input v-model:value="createForm.pattern" placeholder="如 ^(?!.*身份证号).*$" class="mono" />
          </n-form-item>
          <n-form-item label="判定方式">
            <n-select v-model:value="createForm.requireMatch" :options="[
              { label: '命中正则 = PASS', value: 'true' },
              { label: '命中正则 = FAIL', value: 'false' }
            ]" />
          </n-form-item>
        </template>
        <template v-else-if="createForm.type === 'TRAJECTORY'">
          <n-form-item label="轨迹规则（JSON）" required>
            <n-input v-model:value="createForm.trajectorySpecText" type="textarea" :rows="10" class="mono"
              placeholder='{"trajectoryMode":"SUBSET","expectedTools":[{"name":"query_order","required":true}],"forbiddenTools":["delete_order"],"maxSteps":12,"allowCycles":false,"argumentRules":[{"tool":"query_order","path":"orderId","required":true,"regex":"^[0-9]+$"}]}' />
            <div class="page-sub">
              确定性检查，不调用 LLM。trajectoryMode: SUBSET（默认）/ ORDER / EXACT；
              没有工具轨迹时返回 UNKNOWN，不判 FAIL。仅 TRACE 模式有工具证据。
            </div>
          </n-form-item>
        </template>
        <template v-else-if="createForm.type === 'SDK'">
          <n-form-item label="输入契约（所需样本字段，逗号分隔）" required>
            <n-input v-model:value="createForm.requiredFieldsText" class="mono"
                     placeholder="input, candidate_output, expected_output" />
            <div class="page-sub">
              声明 scorer 需要哪些样本字段，运行时按契约下发载荷。可用：input / candidate_output /
              expected_output / retrieval_context / expected_tools / tools_called / turns / trace_digest。
            </div>
          </n-form-item>
          <n-form-item label="scorer 引用（可选）">
            <n-input v-model:value="createForm.sdkScorerRef" class="mono"
                     placeholder="如 scorers.py:amount_check，仅留痕便于追溯" />
            <div class="page-sub">
              服务端不执行任何用户代码。run 终态后由 SDK 拉取任务、本地执行 scorer 并回写：
              <span class="mono">emonlens eval run --sdk-runner ./scorers.py</span>。
              未回写的结果按 UNKNOWN 参与聚合，Gate 会因 SDK_EVALUATORS 检查项 BLOCKED。
            </div>
          </n-form-item>
        </template>
        <template v-else>
          <n-form-item label="指标预设（可选）">
            <n-select v-model:value="createForm.llmPreset" :options="llmPresetOptions"
                      clearable placeholder="选择后自动填充判分模式 / 模板 / 所需证据"
                      @update:value="applyLlmPreset" />
            <div class="page-sub">预设来自后端注册表；仍可手动调整下方字段。预设只影响新建/编辑时的 spec，运行时不会动态变化。</div>
          </n-form-item>
          <n-form-item label="评审 Rubric" required>
            <n-input v-model:value="createForm.rubric" type="textarea" :rows="3" placeholder="描述评审标准，如：回答必须礼貌且正确" />
          </n-form-item>
          <n-form-item v-if="createForm.judgeMode !== 'CLAIMS'" label="逐条评分维度（可选，JSON 数组）">
            <n-input v-model:value="createForm.criteriaText" type="textarea" :rows="3" class="mono"
              placeholder='[{"id":"accuracy","label":"准确","description":"事实正确","required":true}]' />
            <div class="page-sub">id 需以字母开头且唯一；required 默认 true，任一 required 维度未通过则整体 FAIL。模型未返回 criteria 时按 UNKNOWN 处理。</div>
          </n-form-item>
          <div class="cfg-two">
            <n-form-item label="判分模式">
              <n-select v-model:value="createForm.judgeMode" :options="[
                { label: 'HOLISTIC（整体 0-10）', value: 'HOLISTIC' },
                { label: 'CLAIMS（逐条 claim）', value: 'CLAIMS' }
              ]" />
            </n-form-item>
            <n-form-item label="Judge 模板 key（可选）">
              <n-input v-model:value="createForm.judgeTemplateKey"
                       @update:value="createForm.judgeTemplateVersion = null"
                       placeholder="platform/claim-v1" class="mono" />
            </n-form-item>
          </div>
          <n-form-item label="Judge 模板版本（可选）">
            <n-input-number v-model:value="createForm.judgeTemplateVersion" :min="1" clearable
                            placeholder="留空=保存时固定当前最新版本" style="width: 240px" />
            <div class="page-sub">
              创建/保存评估器时会把版本固定下来；之后发布新模板不会改变已有评估器和旧 run。
              要升级模板，显式填写新版本后保存（会生成新的评估器版本）。
            </div>
          </n-form-item>
          <n-form-item label="所需证据字段（逗号分隔，可选）">
            <n-input v-model:value="createForm.requiredFieldsText" class="mono"
                     placeholder="candidate_output, retrieval_context" />
            <div class="page-sub">缺字段时返回 UNKNOWN 并给出原因码，不判 FAIL。可用：input / candidate_output / expected_output / retrieval_context / expected_tools / tools_called / turns / trace_digest。</div>
          </n-form-item>
          <template v-if="createForm.judgeMode === 'CLAIMS'">
            <div class="cfg-two">
              <n-form-item label="最多 claim 数">
                <n-input-number v-model:value="createForm.maxClaims" :min="1" :max="20" style="width: 140px" />
              </n-form-item>
              <n-form-item label="通过最低比例（0-1）">
                <n-input-number v-model:value="createForm.minPassRatio" :min="0" :max="1" :step="0.05" style="width: 160px" />
              </n-form-item>
            </div>
            <n-form-item label="无法判定策略">
              <n-select v-model:value="createForm.claimsUnknownPolicy" :options="[
                { label: '阻断（存在无法判定时整体无法判定，Gate 阻断）', value: 'BLOCK' },
                { label: '按未通过处理', value: 'FAIL' },
                { label: '忽略（仅诊断；全部无法判定时仍无法判定）', value: 'PASS' }
              ]" />
            </n-form-item>
            <n-form-item label="证据要求">
              <n-checkbox v-model:checked="createForm.requireEvidence">
                通过的 claim 必须带可解析 evidenceRefs
              </n-checkbox>
            </n-form-item>
          </template>
          <div class="cfg-two">
            <n-form-item v-if="createForm.judgeMode !== 'CLAIMS'" label="PASS 最低分（0-10）">
              <n-input-number v-model:value="createForm.minScore" :min="0" :max="10" :step="0.5" style="width: 160px" />
            </n-form-item>
            <n-form-item label="温度">
              <n-input-number v-model:value="createForm.temperature" :min="0" :max="2" :step="0.1" style="width: 140px" />
            </n-form-item>
          </div>
          <n-form-item label="评审模型（留空用系统设置默认）">
            <n-input v-model:value="createForm.judgeModel" placeholder="gpt-4o-mini / deepseek-chat …" class="mono" />
          </n-form-item>
          <n-form-item label="参考答案">
            <n-checkbox v-model:checked="createForm.referenceRequired">
              必须提供参考答案（缺失时返回 UNKNOWN，不判 FAIL）
            </n-checkbox>
          </n-form-item>
          <div class="page-sub">
            评审输入 = Rubric + 候选输出 + 参考答案（如提供）；参考是评价依据，不要求逐字一致。
            Judge 结果标为“模型建议”，未经人工校准。
          </div>
        </template>
      </n-form>
      <template #footer>
        <n-button size="small" :disabled="creating" @click="showCreate = false">取消</n-button>
        <n-button type="primary" size="small" :loading="creating" @click="doSave">{{ editing ? '保存' : '创建' }}</n-button>
      </template>
    </Modal>

    <!-- 版本历史 -->
    <Modal v-model:show="showVersions" :width="620" :title="'版本历史：' + (versionTarget?.evaluatorKey || '')">
      <n-data-table :columns="versionColumns" :data="versions" size="small" :bordered="false" :max-height="420" />
      <template #footer>
        <n-button size="small" @click="showVersions = false">关闭</n-button>
      </template>
    </Modal>

    <!-- 评估套件 -->
    <Modal v-model:show="showSuites" :width="820" title="评估套件">
      <div class="page-sub" style="margin-bottom:10px">
        套件会把一组评估器冻结成不可变版本；运行实验时选择套件即可复现当时的评估标准。
      </div>
      <n-data-table :columns="suiteColumns" :data="suites" size="small" :bordered="false"
        :loading="suitesLoading" :row-key="(r) => r.id" :max-height="260" />
      <div v-if="!suitesLoading && !suites.length" class="page-sub" style="margin:8px 0">
        还没有套件。选择至少两个评估器创建第一个套件。
      </div>
      <n-divider />
      <n-form label-placement="top">
        <div class="suite-form-grid">
          <n-form-item label="套件 Key" required>
            <n-input v-model:value="suiteForm.suiteKey" placeholder="如 customer-support-regression" class="mono" />
          </n-form-item>
          <n-form-item label="名称" required>
            <n-input v-model:value="suiteForm.name" placeholder="如 客服回归三件套" />
          </n-form-item>
        </div>
        <n-form-item label="说明">
          <n-input v-model:value="suiteForm.description" type="textarea" :rows="2" placeholder="可选：说明这套评估器覆盖哪些质量维度" />
        </n-form-item>
        <n-form-item label="包含评估器" required>
          <n-select v-model:value="suiteForm.evaluatorIds" multiple filterable
            :options="suiteEvaluatorOptions" placeholder="选择 1–5 个评估器" />
          <div class="page-sub">套件创建后冻结当前评估器版本；后续修改评估器不会改变已有套件。</div>
        </n-form-item>
      </n-form>
      <template #footer>
        <n-button size="small" :disabled="suiteSaving" @click="showSuites = false">关闭</n-button>
        <n-button type="primary" size="small" :loading="suiteSaving" @click="createSuite">创建套件</n-button>
      </template>
    </Modal>

    <!-- 试跑评估器 -->
    <Modal v-model:show="showTest" :width="640" :title="'试跑：' + (testing?.evaluatorKey || '')">
      <n-form label-placement="top">
        <n-form-item label="选择 Trace（最近完成的 15 条，也可直接输入 ID）" required>
          <n-select v-model:value="testTraceId" filterable tag
            :options="recentTraces" placeholder="从最近完成的选择，或粘贴 traceId" class="mono" />
        </n-form-item>
      </n-form>
      <n-button size="small" type="primary" :loading="testingRun" :disabled="!testTraceId" @click="doTest" class="mb8">执行试跑</n-button>
      <div v-if="testResult" class="desc-list">
        <span class="k">分数</span><span>{{ testResult.score == null ? '—' : testResult.score }}</span>
        <span class="k">标签</span><span>{{ enumText('evalLabel', testResult.label) }}</span>
        <span class="k">原因</span><span>{{ testResult.reason || '—' }}</span>
      </div>
      <template #footer>
        <n-button size="small" @click="showTest = false">关闭</n-button>
      </template>
    </Modal>
  </div>
</template>

<script setup>
import EmptyState from '@/components/EmptyState.vue'
import { computed, h, onMounted, reactive, ref, watch } from 'vue'
import { NButton, NPopconfirm, NSwitch, NTag, useMessage } from 'naive-ui'
import api from '@/api'
import { EVALUATOR_TYPE_COLORS, MUTED } from '@/common/colors'
import { enumText } from '@/common/i18n'

const message = useMessage()
const records = ref([]), loading = ref(false)
const showCreate = ref(false), creating = ref(false), editing = ref(null)
const showTest = ref(false), testing = ref(null), testingRun = ref(false)
const showVersions = ref(false), versionTarget = ref(null), versions = ref([])
const versionColumns = [
  { title: '版本', key: 'version', width: 60, render: (r) => h('span', { class: 'num' }, 'v' + r.version) },
  { title: '类型', key: 'type', width: 100 },
  { title: '指纹', key: 'fingerprint', minWidth: 150, render: (r) => h('span', { class: 'mono', style: 'font-size:11px' }, String(r.fingerprint || '').slice(0, 16) + '…') },
  { title: '时间', key: 'createdAt', width: 150, render: (r) => String(r.createdAt || '').replace('T', ' ').slice(0, 19) }
]
async function openVersions(row) {
  versionTarget.value = row
  versions.value = []
  showVersions.value = true
  try { versions.value = await api.evaluatorVersions(row.id) } catch (e) { message.error(e.message) }
}

// ---------- 评估套件 ----------
const showSuites = ref(false), suites = ref([]), suitesLoading = ref(false), suiteSaving = ref(false)
const suiteEvaluatorOptions = ref([])
const suiteForm = reactive({ suiteKey: '', name: '', description: '', evaluatorIds: [] })
const suiteColumns = [
  { title: '套件', key: 'name', minWidth: 200, render: (r) => h('div', { style: 'display:flex;flex-direction:column;gap:2px' }, [
      h('span', { style: 'font-weight:600' }, r.name || r.suiteKey),
      h('span', { class: 'mono page-sub' }, `${r.suiteKey} · v${r.version}`)
    ]) },
  { title: '评估器', key: 'evaluators', minWidth: 260, ellipsis: { tooltip: true },
    render: (r) => (r.spec?.evaluators || []).map((e) => e.key).join(' + ') || '—' },
  { title: '指纹', key: 'fingerprint', width: 150, render: (r) => h('span', { class: 'mono', style: 'font-size:11px', title: r.fingerprint }, String(r.fingerprint || '').slice(0, 14) + '…') },
  { title: '创建时间', key: 'createdAt', width: 160, render: (r) => String(r.createdAt || '').replace('T', ' ').slice(0, 19) }
]
async function openSuites() {
  showSuites.value = true
  suitesLoading.value = true
  try {
    const [rows, page] = await Promise.all([
      api.evalSuites(),
      api.evaluators({ pageNumber: 1, pageSize: 200 })
    ])
    suites.value = rows || []
    suiteEvaluatorOptions.value = (page.records || []).map((e) => ({
      label: `${e.evaluatorKey}（${e.type}）`, value: e.id
    }))
  } catch (e) {
    message.error('加载套件失败：' + (e.message || e))
  } finally { suitesLoading.value = false }
}
async function createSuite() {
  if (!suiteForm.suiteKey.trim() || !suiteForm.name.trim()) {
    message.error('请填写套件 Key 和名称')
    return
  }
  if (!suiteForm.evaluatorIds.length) {
    message.error('请至少选择一个评估器')
    return
  }
  suiteSaving.value = true
  try {
    await api.createEvalSuite({
      suiteKey: suiteForm.suiteKey.trim(),
      name: suiteForm.name.trim(),
      description: suiteForm.description.trim() || undefined,
      evaluatorIds: suiteForm.evaluatorIds
    })
    message.success('评估套件已创建')
    suiteForm.suiteKey = ''
    suiteForm.name = ''
    suiteForm.description = ''
    suiteForm.evaluatorIds = []
    suites.value = await api.evalSuites() || []
  } catch (e) {
    message.error('创建套件失败：' + (e.message || e))
  } finally { suiteSaving.value = false }
}
const testTraceId = ref(''), testResult = ref(null)
const createForm = reactive({
  evaluatorKey: '', type: 'EXPRESSION',
  passWhen: 'error_count == 0', preset: '',
  field: 'completion', pattern: '', requireMatch: 'true',
  rubric: '', criteriaText: '', minScore: 6, temperature: 0, judgeModel: '',
  referenceRequired: false, qualityDimension: '',
  judgeMode: 'HOLISTIC', judgeTemplateKey: '', judgeTemplateVersion: null, requiredFieldsText: '',
  maxClaims: 20, minPassRatio: 0.8, claimsUnknownPolicy: 'BLOCK', requireEvidence: true,
  trajectorySpecText: '', llmPreset: ''
})
const presetOptions = ref([])
const llmPresetOptions = computed(() => [
  { label: '自定义（不套用预设）', value: '' },
  ...presetOptions.value.map((p) => ({ label: p.key, value: p.key }))
])
function applyLlmPreset(value) {
  const preset = presetOptions.value.find((p) => p.key === value)
  if (!preset) return
  createForm.judgeMode = preset.judgeMode || 'HOLISTIC'
  createForm.judgeTemplateKey = preset.templateKey || ''
  createForm.judgeTemplateVersion = null
  createForm.requiredFieldsText = (preset.requiredFields || []).join(', ')
  if (preset.judgeMode === 'CLAIMS') {
    createForm.maxClaims = 20
    createForm.minPassRatio = 0.8
    createForm.claimsUnknownPolicy = 'BLOCK'
    createForm.requireEvidence = true
  }
}
const pagination = reactive({
  page: 1, pageSize: 10, itemCount: 0, showSizePicker: true, pageSizes: [10, 20, 50, 100],
  prefix: ({ itemCount }) => '共 ' + itemCount + ' 条',
  onChange: (page) => { pagination.page = page; load() },
  onUpdatePageSize: (pageSize) => { pagination.pageSize = pageSize; pagination.page = 1; load() }
})

const typeOptions = [
  { label: 'EXPRESSION（表达式规则，零依赖）', value: 'EXPRESSION' },
  { label: 'REGEX（内容正则断言）', value: 'REGEX' },
  { label: 'LLM_JUDGE（模型评审）', value: 'LLM_JUDGE' },
  { label: 'TRAJECTORY（工具轨迹确定性检查）', value: 'TRAJECTORY' },
  { label: 'SDK（自定义 Python scorer，结果回写）', value: 'SDK' }
]
const EXACT_MATCH_PRESET = 'reference-exact-match'
const EXACT_MATCH_PASS_WHEN = 'reference_match.exact(candidate_output, expected_output)'
const exactMatchExpression = EXACT_MATCH_PASS_WHEN
const expressionPresets = [
  { label: '自定义表达式', value: '' },
  { label: '参考答案精确匹配（推荐）', value: EXACT_MATCH_PRESET }
]
watch(() => createForm.preset, (value, old) => {
  if (value === EXACT_MATCH_PRESET) {
    createForm.passWhen = EXACT_MATCH_PASS_WHEN
    createForm.referenceRequired = true
  } else if (old === EXACT_MATCH_PRESET && createForm.passWhen === EXACT_MATCH_PASS_WHEN) {
    // 只在用户从预设切回自定义且表达式仍是预设内容时清空；编辑已有自定义评估器时不覆盖其表达式
    createForm.passWhen = 'error_count == 0'
    createForm.referenceRequired = false
  }
})
// CLAIMS 需要 claim 模板；用户切到 CLAIMS 时补默认模板，避免用 holistic 模板解析 claims 直接判非法
watch(() => createForm.judgeMode, (mode) => {
  if (createForm.type !== 'LLM_JUDGE') return
  if (mode === 'CLAIMS' && !createForm.judgeTemplateKey.trim()) {
    createForm.judgeTemplateKey = 'platform/claim-v1'
    createForm.judgeTemplateVersion = null
  } else if (mode === 'HOLISTIC' && createForm.judgeTemplateKey.trim() === 'platform/claim-v1') {
    createForm.judgeTemplateKey = ''
    createForm.judgeTemplateVersion = null
  }
})
const typeHint = computed(() => ({
  EXPRESSION: '对 Trace 指标或数据集样本的候选/参考求值，零外部依赖；参考答案预设见下方选择',
  REGEX: '对采集的输入/输出/工具内容做正则断言，适合 PII 检查、关键词命中类规则',
  LLM_JUDGE: '把 Rubric、候选输出与参考答案交给大模型评审打分，适合开放性质量评估（需配置 LLM 端点）',
  TRAJECTORY: '对工具调用序列做确定性检查（顺序/子集/禁用工具/步数/循环/参数），不调用 LLM；仅 TRACE 模式有工具证据',
  SDK: '判定逻辑在用户环境执行（Python @emonlens.scorer），结果经开放 API 回写；服务端不执行任何用户代码'
}[createForm.type] || ''))

function typeTag(t) {
  return h(NTag, { size: 'small', bordered: false, color: { color: (EVALUATOR_TYPE_COLORS[t] || MUTED) + '1a', textColor: EVALUATOR_TYPE_COLORS[t] || MUTED } }, { default: () => enumText('evaluatorType', t), title: t })
}
function specPreview(r) {
  const s = r.spec || {}
  if (r.type === 'EXPRESSION') return s.passWhen || ''
  if (r.type === 'REGEX') return `${s.field || 'completion'}: /${s.pattern || ''}/ ${s.requireMatch === false ? '(命中即 FAIL)' : ''}`
  if (r.type === 'LLM_JUDGE') return s.rubric || ''
  if (r.type === 'SDK') return '需要 ' + (s.requiredFields || []).join(', ') + (s.scorerRef ? ' · ' + s.scorerRef : '')
  return JSON.stringify(s)
}

const columns = [
  { title: '评估器 Key', key: 'evaluatorKey', width: 150, ellipsis: { tooltip: true }, render: (r) => h('span', { class: 'mono', style: 'font-size:12.5px' }, r.evaluatorKey) },
  { title: '类型', key: 'type', width: 105, render: (r) => typeTag(r.type) },
  { title: '定义', key: 'spec', minWidth: 170, ellipsis: { tooltip: true }, render: (r) => h('span', { class: 'mono', style: 'font-size:11.5px;color:var(--text-secondary)', title: specPreview(r) }, specPreview(r)) },
  { title: '近7天', key: 'recent', width: 80, align: 'right', render: (r) => {
      const s = overviewOf(r.evaluatorKey)
      return h('span', { class: 'num', title: '近 7 天评估 ' + s.recent7d + ' 次' }, s.recent7d ? String(s.recent7d) : '—')
    } },
  { title: '通过率', key: 'passRate', width: 85, render: (r) => {
      const s = overviewOf(r.evaluatorKey)
      if (s.passRate == null) return h('span', { style: 'color:var(--text-muted);font-size:12px', title: '暂无 PASS/FAIL 判定' }, '—')
      return h('span', { class: 'num', style: 'font-weight:600;color:' + (s.passRate >= 80 ? 'var(--success)' : s.passRate >= 50 ? 'var(--warn)' : 'var(--error)'), title: s.pass + ' PASS / ' + s.fail + ' FAIL' }, s.passRate + '%')
    } },
  { title: '最近评估', key: 'lastEval', width: 85, render: (r) => {
      const s = overviewOf(r.evaluatorKey)
      return h('span', { title: s.lastEvaluatedAt, style: 'font-size:11.5px;color:var(--text-muted)' }, s.lastEvaluatedAt ? fmtRel(s.lastEvaluatedAt) : '—')
    } },
  { title: '版本', key: 'version', width: 50 },
  { title: '状态', key: 'enabled', width: 60, render: (r) => h(NSwitch, { size: 'small', value: !!r.enabled, onUpdateValue: (v) => doToggle(r, v) }) },
  { title: '操作', key: 'ops', width: 190, render: (r) => [
      h(NButton, { size: 'tiny', style: 'margin-right:6px', onClick: () => openEdit(r) }, { default: () => '编辑' }),
      h(NButton, { size: 'tiny', style: 'margin-right:6px', disabled: r.type === 'SDK', title: r.type === 'SDK' ? 'SDK 评测器在用户环境执行，无服务端试跑' : undefined, onClick: () => openTest(r) }, { default: () => '试跑' }),
      h(NButton, { size: 'tiny', style: 'margin-right:6px', onClick: () => openVersions(r) }, { default: () => '版本' }),
      h(NPopconfirm, { onPositiveClick: () => doDelete(r) }, {
        trigger: () => h(NButton, { size: 'tiny', type: 'error', quaternary: true }, { default: () => '删除' }),
        default: () => `确认删除评估器 ${r.evaluatorKey}？其历史评估结果将一并删除，删除后不可恢复。`
      })
    ] }
]

// ---------- 评估器统计（/eval-results/overview，与评估结果页同源） ----------
const overviewMap = ref({})
function overviewOf(key) {
  return overviewMap.value[key] || { recent7d: 0, passRate: null, pass: 0, fail: 0, lastEvaluatedAt: '' }
}
async function loadOverview() {
  try {
    const rows = await api.evalOverview() || []
    const m = {}
    for (const r of rows) {
      m[r.evaluatorKey] = {
        passRate: r.passRate, pass: r.pass, fail: r.fail, total: r.total,
        lastEvaluatedAt: r.lastEvaluatedAt || '',
        recent7d: r.recent7d == null ? 0 : r.recent7d
      }
    }
    overviewMap.value = m
  } catch (e) { overviewMap.value = {} }
}
function fmtRel(iso) {
  if (!iso) return '—'
  const t = new Date(String(iso).slice(0, 23).replace(' ', 'T')).getTime()
  if (Number.isNaN(t)) return '—'
  const diff = Date.now() - t
  if (diff < 60000) return '刚刚'
  if (diff < 3600000) return Math.floor(diff / 60000) + ' 分钟前'
  if (diff < 86400000) return Math.floor(diff / 3600000) + ' 小时前'
  return Math.floor(diff / 86400000) + ' 天前'
}

async function load() {
  loading.value = true
  try {
    const page = await api.evaluators({ pageNumber: pagination.page, pageSize: pagination.pageSize })
    records.value = page.records || []
    pagination.itemCount = page.total || 0
    loadOverview()
  } catch (e) { message.error(e.message || '评估器列表加载失败') }
  finally { loading.value = false }
}

function buildSpec() {
  if (createForm.type === 'EXPRESSION') {
    const spec = {
      type: 'EXPRESSION',
      passWhen: createForm.preset === EXACT_MATCH_PRESET ? EXACT_MATCH_PASS_WHEN : createForm.passWhen,
      referenceRequired: !!createForm.referenceRequired
    }
    if (createForm.preset) spec.preset = createForm.preset
    return spec
  }
  if (createForm.type === 'REGEX') {
    return { type: 'REGEX', field: createForm.field, pattern: createForm.pattern, requireMatch: createForm.requireMatch === 'true' }
  }
  if (createForm.type === 'TRAJECTORY') {
    let parsed
    try {
      parsed = createForm.trajectorySpecText.trim()
        ? JSON.parse(createForm.trajectorySpecText) : {}
    } catch (e) {
      throw new Error('轨迹规则不是合法 JSON：' + e.message)
    }
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      throw new Error('轨迹规则必须是 JSON 对象')
    }
    const { type: _ignored, ...rules } = parsed
    return { ...rules, type: 'TRAJECTORY' }
  }
  if (createForm.type === 'SDK') {
    const spec = { type: 'SDK', requiredFields: createForm.requiredFieldsText.split(',')
      .map((v) => v.trim()).filter(Boolean) }
    if (createForm.sdkScorerRef.trim()) spec.scorerRef = createForm.sdkScorerRef.trim()
    return spec
  }
  const spec = { type: 'LLM_JUDGE', rubric: createForm.rubric, minScore: createForm.minScore,
           temperature: createForm.temperature,
           judgeModel: createForm.judgeModel.trim() || undefined,
           referenceRequired: !!createForm.referenceRequired }
  if (createForm.llmPreset) {
    spec.preset = createForm.llmPreset
  }
  if (createForm.judgeMode && createForm.judgeMode !== 'HOLISTIC') {
    spec.judgeMode = createForm.judgeMode
  }
  if (createForm.judgeTemplateKey.trim()) {
    spec.judgeTemplateKey = createForm.judgeTemplateKey.trim()
  }
  if (createForm.judgeTemplateVersion != null) {
    spec.judgeTemplateVersion = createForm.judgeTemplateVersion
  }
  if (createForm.requiredFieldsText.trim()) {
    spec.requiredFields = createForm.requiredFieldsText.split(',')
      .map((v) => v.trim()).filter(Boolean)
  }
  if (createForm.judgeMode === 'CLAIMS') {
    spec.claimsPolicy = {
      maxClaims: createForm.maxClaims,
      requireEvidence: !!createForm.requireEvidence,
      unknownPolicy: createForm.claimsUnknownPolicy,
      minPassRatio: createForm.minPassRatio
    }
  }
  if (createForm.criteriaText.trim()) {
    let parsed
    try { parsed = JSON.parse(createForm.criteriaText) } catch (e) { throw new Error('criteria 不是合法 JSON') }
    if (!Array.isArray(parsed)) throw new Error('criteria 必须是 JSON 数组')
    spec.criteria = parsed
  }
  return spec
}

function openCreate() {
  editing.value = null
  Object.assign(createForm, { evaluatorKey: '', type: 'EXPRESSION', passWhen: 'error_count == 0', preset: '', field: 'completion', pattern: '', requireMatch: 'true', rubric: '', criteriaText: '', minScore: 6, temperature: 0, judgeModel: '', referenceRequired: false, qualityDimension: '', judgeMode: 'HOLISTIC', judgeTemplateKey: '', judgeTemplateVersion: null, requiredFieldsText: '', maxClaims: 20, minPassRatio: 0.8, claimsUnknownPolicy: 'BLOCK', requireEvidence: true, trajectorySpecText: '', llmPreset: '', sdkScorerRef: '' })
  showCreate.value = true
}

/** 编辑：type/key 锁定，仅 spec 参与更新（后端 PUT /evaluators/{id} 语义） */
function openEdit(r) {
  editing.value = r
  const s = r.spec || {}
  Object.assign(createForm, {
    evaluatorKey: r.evaluatorKey, type: r.type,
    passWhen: s.passWhen || 'error_count == 0',
    preset: s.preset || '',
    field: s.field || 'completion', pattern: s.pattern || '',
    requireMatch: s.requireMatch === false ? 'false' : 'true',
    rubric: s.rubric || '', criteriaText: s.criteria ? JSON.stringify(s.criteria, null, 2) : '',
    minScore: s.minScore ?? 6,
    temperature: s.temperature ?? 0, judgeModel: s.judgeModel || '',
    referenceRequired: !!s.referenceRequired,
    qualityDimension: s.qualityDimension || '',
    judgeMode: s.judgeMode || 'HOLISTIC',
    judgeTemplateKey: s.judgeTemplateKey || '',
    judgeTemplateVersion: s.judgeTemplateVersion ?? null,
    requiredFieldsText: Array.isArray(s.requiredFields) ? s.requiredFields.join(', ') : '',
    maxClaims: s.claimsPolicy?.maxClaims ?? 20,
    minPassRatio: s.claimsPolicy?.minPassRatio ?? 0.8,
    claimsUnknownPolicy: s.claimsPolicy?.unknownPolicy || 'BLOCK',
    requireEvidence: s.claimsPolicy?.requireEvidence !== false,
    llmPreset: r.type === 'LLM_JUDGE' ? (s.preset || '') : '',
    trajectorySpecText: r.type === 'TRAJECTORY'
      ? JSON.stringify(Object.fromEntries(Object.entries(s).filter(([k]) => k !== 'type')), null, 2)
      : '',
    sdkScorerRef: s.scorerRef || ''
  })
  showCreate.value = true
}
function onTypeChange() { /* spec 字段随类型切换渲染 */ }

async function doSave() {
  if (!editing.value && !createForm.evaluatorKey.trim()) { message.error('请填写评估器 Key'); return }
  creating.value = true
  try {
    const spec = buildSpec()
    if (createForm.qualityDimension.trim()) {
      spec.qualityDimension = createForm.qualityDimension.trim()
    }
    if (editing.value) {
      await api.updateEvaluator(editing.value.id, { spec })
      message.success('评估器定义已更新')
    } else {
      await api.createEvaluator({ evaluatorKey: createForm.evaluatorKey.trim(), type: createForm.type, spec })
      message.success('评估器已创建并启用，将参与自动评估')
    }
    showCreate.value = false
    load()
  } catch (e) { message.error(e.message || '保存失败') }
  finally { creating.value = false }
}

async function doToggle(r) {
  try {
    if (r.enabled) await api.disableEvaluator(r.id); else await api.enableEvaluator(r.id)
    load()
  } catch (e) { message.error(e.message) }
}

async function doDelete(r) {
  try {
    await api.deleteEvaluator(r.id)
    message.success('已删除「' + r.evaluatorKey + '」')
    load()
  } catch (e) { message.error(e.message) }
}

function openTest(r) {
  testing.value = r
  testTraceId.value = ''
  testResult.value = null
  showTest.value = true
  loadRecentTraces()
}
// 最近完成的 Trace（试跑候选下拉，免手填）
const recentTraces = ref([])
async function loadRecentTraces() {
  try {
    const page = await api.traceSearch({ status: 'COMPLETED', pageNumber: 1, pageSize: 15, sortBy: 'startTime', sortOrder: 'desc' })
    recentTraces.value = (page.records || []).map((r) => ({
      label: r.traceId + (r.inputPreview ? ' · ' + String(r.inputPreview).slice(0, 24) : ''),
      value: r.traceId
    }))
  } catch (e) { recentTraces.value = [] }
}

async function doTest() {
  if (!testTraceId.value.trim()) { message.error('请输入 traceId'); return }
  testingRun.value = true
  try {
    testResult.value = await api.testEvaluator(testing.value.id, testTraceId.value.trim())
  } catch (e) { message.error(e.message || '试跑失败') }
  finally { testingRun.value = false }
}

onMounted(async () => {
  await load()
  try { presetOptions.value = await api.evaluatorPresets() || [] } catch (e) { /* 预设加载失败不影响手动配置 */ }
})
</script>

<style scoped>
.suite-form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 12px; }
.mb8 { margin-bottom: 8px; }
.desc-list { display: grid; grid-template-columns: 80px 1fr; row-gap: 8px; font-size: 13px; }
.desc-list .k { color: var(--text-secondary); }
.cfg-two { display: flex; gap: 16px; }
</style>