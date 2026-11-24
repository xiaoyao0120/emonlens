<template>
  <div class="trace-panel" :class="{ 'page-mode': mode === 'page' }" :style="panelStyle">
    <!-- 身份行 + 关键指标行作为整体吸顶：下面 Observations/瀑布滚走。
         整页模式下滚动发生在外层 .content，且 .trace-panel 已 overflow:visible，
         所以这里的 sticky 有生效的包含块。 -->
    <div class="panel-head-stack">
    <div class="panel-head">
      <div class="head-left">
        <n-button v-if="mode === 'panel'" size="tiny" quaternary title="关闭" @click="$emit('close')">✕</n-button>
        <CopyableId :value="traceId" :length="20" class="head-id" />
        <n-tag v-if="detail?.summary?.status" size="small" :bordered="false"
          :type="detail.summary.errorCount > 0 ? 'warning' : detail.summary.status === 'COMPLETED' ? 'success' : 'info'">
          {{ enumText('runStatus', detail?.summary?.status) }}{{ detail?.summary?.errorCount > 0 ? ' ⚠' : '' }}
        </n-tag>
        <!-- 会话联动：同会话 trace 快速切换 + 会话时间线入口 -->
        <n-dropdown v-if="detail?.summary?.sessionId" trigger="click" :options="sessionOptions" @select="onSessionSelect">
          <n-tag size="small" :bordered="false" type="info" style="cursor:pointer"
            :title="'所属会话 ' + detail.summary.sessionId + '：点击切换同会话其他调用'">
            会话 {{ shortSessionId }} · {{ siblingTraces.length + 1 }} 条
          </n-tag>
        </n-dropdown>
        <n-tag v-for="t in tags" :key="t" size="small" :bordered="false" class="tag-chip" closable @close="removeTag(t)">{{ t }}</n-tag>
        <n-popover trigger="click" placement="bottom-start" :show-arrow="false">
          <template #trigger>
            <n-button size="tiny" quaternary>＋ 标签</n-button>
          </template>
          <div style="display:flex;gap:6px;width:240px">
            <n-input v-model:value="tagInput" size="small" placeholder="标签回车添加" @keyup.enter="addTag" />
            <n-button size="small" type="primary" @click="addTag">添加</n-button>
          </div>
        </n-popover>
      </div>
      <div class="head-actions">
        <n-button size="tiny" quaternary :disabled="!prevId" title="上一条" @click="goTrace(prevId)">‹</n-button>
        <n-button size="tiny" quaternary :disabled="!nextId" title="下一条" @click="goTrace(nextId)">›</n-button>
        <n-button size="tiny" :type="myThumb === 'up' ? 'primary' : 'default'" ghost title="好评"
          @click="quickScore(10)"><n-icon :component="ThumbsUpOutline" size="13" /></n-button>
        <n-button size="tiny" :type="myThumb === 'down' ? 'primary' : 'default'" ghost title="差评"
          @click="quickScore(0)"><n-icon :component="ThumbsDownOutline" size="13" /></n-button>
        <n-button size="tiny" @click="showScore = true">评分</n-button>
        <n-button size="tiny" title="收录为数据集样本，用于回归实验" @click="openAddToDataset">＋ 数据集</n-button>
      </div>
    </div>

    <!-- 加入数据集（Langfuse 式 trace → dataset 样本；无数据集时可直接新建） -->
    <Modal v-model:show="showAddToDataset" :width="480" title="收录为数据集样本">
      <n-form label-placement="top">
        <n-form-item label="目标数据集" required>
          <n-select v-model:value="datasetTarget" :options="datasetPickOptions" placeholder="选择数据集，或新建一个" />
        </n-form-item>
        <n-form-item v-if="datasetTarget === '__new__'" label="新数据集 Key（唯一）" required>
          <n-input v-model:value="newDatasetKey" placeholder="如 support-qa-regression" class="mono" />
        </n-form-item>
        <n-form-item label="输入（Input）">
          <n-input v-model:value="datasetForm.input" type="textarea" :rows="2" />
        </n-form-item>
        <n-form-item label="期望输出（Expected Output，可编辑）">
          <n-input v-model:value="datasetForm.expectedOutput" type="textarea" :rows="2" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-button size="small" :disabled="addingToDataset" @click="showAddToDataset = false">取消</n-button>
        <n-button type="primary" size="small" :loading="addingToDataset" @click="doAddToDataset">收录</n-button>
      </template>
    </Modal>

    <div v-if="detail?.summary" class="meta-strip">
      <div class="m-item"><span class="m-k">耗时</span><span class="num">{{ fmtDur(detail.summary.durationMs) }}</span></div>
      <div class="m-item"><span class="m-k">Tokens</span><span class="num">{{ totalIn }} → {{ totalOut }}<template v-if="totalCached">（缓存 {{ totalCached }}）</template></span></div>
      <div class="m-item"><span class="m-k">成本</span><span class="num">{{ fmtCost(detail.summary.totalCostUsd) }}</span></div>
      <div class="m-item"><span class="m-k">模型</span><span>{{ detail.summary.modelName || '—' }}</span></div>
      <div class="m-item" v-if="detail.summary.promptName"><span class="m-k">Prompt</span><span class="mono link" @click="router.push('/prompts')" :title="'版本 ' + (detail.summary.promptVersion || '-')">{{ detail.summary.promptName }}{{ detail.summary.promptVersion ? ' v' + detail.summary.promptVersion : '' }}</span></div>
    </div>
    </div>
    <n-spin v-if="loading" style="width: 100%; margin-top: 60px" />

    <EmptyState v-else-if="!detail?.summary && loadError" :text="loadError"
      action-text="返回 Trace 列表" @action="router.push('/traces')" />

    <div v-if="detail?.summary" class="panel-body">
      <!-- Span 树（时间条内嵌，支持折叠） -->
      <div class="sec-block">
        <div class="panel-title-row">
          <span class="panel-title">Observations（{{ visibleRows.length }} / {{ timelineRows.length }}）</span>
          <span class="type-legend">
            <span v-for="t in ['agent', 'llm', 'tool', 'retrieval']" :key="t" class="legend-item"
              :title="TYPE_TEXT[t]"><i class="dot" :style="{ background: typeColor(t) }"></i>{{ TYPE_TEXT[t] }}</span>
          </span>
          <span class="flex-1"></span>
          <n-button size="tiny" :type="errorOnly ? 'error' : 'default'" :ghost="errorOnly"
            @click="errorOnly = !errorOnly">只看错误</n-button>
          <n-button size="tiny" quaternary @click="expandAll">{{ collapsed ? '展开全部' : '收起为概览' }}</n-button>
          <n-input v-model:value="spanFilter" size="tiny" placeholder="过滤 span 名称…" clearable style="width: 180px" />
        </div>
        <!-- 时间轴标尺 -->
        <div class="tl-ruler">
          <span style="width: 26px"></span>
          <div class="tl-ruler-track">
            <span>0</span><span style="margin-left:auto">{{ axisMid }}</span><span style="margin-left:auto">{{ axisEnd }}</span>
          </div>
          <span style="width: 64px"></span>
        </div>
        <!-- 固定高度滚动区：标尺常驻，树内部上下滚动 -->
        <div class="tree-scroll">
          <div class="tree">
            <div v-for="node in visibleRows" :key="node.spanId" class="t-row"
              :class="{ selected: selected?.spanId === node.spanId }" @click="openSpan(node)">
              <div class="t-name" :style="{ paddingLeft: (6 + node.depth * 16) + 'px' }">
                <span v-if="node.childCount" class="tw-caret" :title="expanded.has(node.spanId) ? '收起' : '展开'"
                  @click.stop="toggleExpand(node.spanId)">{{ expanded.has(node.spanId) ? '▾' : '▸' }}</span>
                <span v-else class="tw-caret"></span>
                <span class="type-dot" :style="{ background: typeColor(node.spanType) }"></span>
                <span class="t-title" :title="node.spanName || node.toolName || node.spanType">{{ node.spanName || node.toolName || node.spanType }}</span>
                <n-tag v-if="node.status === 'ERROR'" size="tiny" type="error" :bordered="false">ERROR</n-tag>
              </div>
              <div class="t-waterfall">
                <div class="tl-bar" :style="{
                  marginLeft: node.offsetPct + '%',
                  width: Math.max(Math.min(node.widthPct, 100 - node.offsetPct), 0.5) + '%',
                  background: node.status === 'ERROR' ? 'var(--error)' : typeColor(node.spanType)
                }"></div>
              </div>
              <span class="t-dur num">{{ fmtDur(node.durationMs) }}<span v-if="node.widthPct >= 1 && node.widthPct <= 100"
                  style="color:var(--text-muted);font-size:10px;margin-left:3px">{{ Math.round(node.widthPct) }}%</span></span>
            </div>
            <div v-if="!visibleRows.length" class="t-empty">
              {{ errorOnly ? '该 Trace 没有 ERROR span' : (spanFilter.trim() ? '没有匹配的 span' : '暂无 span') }}
            </div>
          </div>
        </div>
      </div>

      <!--
        Span 详情抽屉：点 span 打开，替代原来常驻在底部的 Tabs 区。
        mask=false + block-scroll=false：抽屉不盖遮罩、不锁页面滚动，左侧瀑布图仍可继续点，
        方便一边看一个 span 一边对照别的 span。宽度上限 760px，窄屏按视口比例收。
      -->
      <n-drawer v-model:show="showSpanDetail" placement="right" :width="drawerWidth" :show-mask="false"
        :mask-closable="false" :block-scroll="false" :auto-focus="false" :trap-focus="false">
        <n-drawer-content closable :native-scrollbar="false" class="span-drawer">
          <template #header>
            <div class="drawer-head">
              <span v-if="selected" class="type-dot" :style="{ background: typeColor(selected.spanType) }"></span>
              <span class="d-name">{{ selected ? (selected.spanName || selected.toolName || selected.spanType) : 'Span 详情' }}</span>
              <n-tag v-if="selected" size="tiny" :bordered="false">{{ enumText('spanType', selected.spanType) }}</n-tag>
              <n-tag v-if="selected && selected.status === 'ERROR'" size="tiny" type="error" :bordered="false">ERROR</n-tag>
              <span v-if="selected" class="d-dur num">{{ fmtDur(selected.durationMs) }}</span>
            </div>
          </template>

          <n-tabs type="line" size="small" v-model:value="tab">
            <n-tab name="input">Input</n-tab>
            <n-tab name="output">Output</n-tab>
            <n-tab name="metadata">Metadata</n-tab>
            <n-tab :name="'evaluation'">Evaluation（{{ (detail.findings || []).length + (evalResults.length) }}）</n-tab>
            <n-tab :name="'comments'">Comments（{{ comments.length }}）</n-tab>
          </n-tabs>

        <div v-if="tab === 'input'" class="tab-body">
          <template v-if="selected">
            <div class="io-head">
              <span class="type-dot" :style="{ background: typeColor(selected.spanType) }"></span>
              <strong>{{ selected.spanName || selected.toolName || selected.spanType }}</strong>
              <n-tag size="tiny" :bordered="false" :title="selected.spanType">{{ enumText('spanType', selected.spanType) }}</n-tag>
            </div>
            <CodeBlock v-if="selected.inputText" :content="selected.inputText" label="Input" :start-expanded="true" />
            <EmptyState v-else :text="canViewContent ? '该 Span 无输入内容（受内容采集模式控制）' : '当前角色无内容查看权限（workspace:trust:trace:content）'" />
          </template>
        </div>

        <div v-else-if="tab === 'output'" class="tab-body">
          <template v-if="selected">
            <CodeBlock v-if="selected.outputText" :content="selected.outputText" label="Output" :start-expanded="true" />
            <EmptyState v-else :text="canViewContent ? '该 Span 无输出内容' : '当前角色无内容查看权限（workspace:trust:trace:content）'" />
          </template>
        </div>

        <div v-else-if="tab === 'metadata'" class="tab-body">
          <div v-if="selected" class="desc-list">
            <span class="k">Span ID</span><span class="mono">{{ selected.spanId }}</span>
            <span class="k">类型 / 名称</span><span>{{ enumText('spanType', selected.spanType) }} · {{ selected.spanName || selected.toolName || '—' }}</span>
            <span class="k">状态 / 耗时</span><span>{{ enumText('spanStatus', selected.status) }} · {{ fmtDur(selected.durationMs) }}</span>
            <span class="k">模型 / 工具</span><span>{{ selected.modelName || '—' }} / {{ selected.toolName || '—' }}</span>
            <span class="k">Tokens</span><span class="num">{{ selected.inputTokens ?? 0 }} in<template v-if="selected.cachedInputTokens">（缓存 {{ selected.cachedInputTokens }}）</template> / {{ selected.outputTokens ?? 0 }} out</span>
            <span class="k">Span 成本</span><span class="num">{{ fmtCost(selected.costUsd) }}</span>
            <span v-if="selected.errorType" class="k">错误</span><span v-if="selected.errorType" style="color: var(--error)">{{ selected.errorType }}</span>
            <template v-if="attrPairs.length">
              <div class="panel-title">Attributes（{{ attrPairs.length }}）</div>
              <div class="attr-grid">
                <template v-for="a in attrPairs" :key="a.key">
                  <span class="k mono" :title="a.key">{{ a.key }}</span>
                  <span class="attr-v" :title="a.value">{{ a.value }}</span>
                </template>
              </div>
            </template>
            <div class="panel-title">Raw</div>
            <CodeBlock :content="rawJson" label="raw" :max-height="180" />
            <n-button size="tiny" class="mt8" @click="copyCurl">复制上报 cURL</n-button>
          </div>
        </div>

        <div v-else-if="tab === 'evaluation'" class="tab-body">
          <div class="panel-title">评估结果（{{ evalResults.length }}）</div>
          <n-data-table v-if="evalResults.length" :columns="evalColumns" :data="evalResults" size="small" :bordered="false" />
          <div v-else class="page-sub">该 Trace 暂无评估结果</div>
          <div class="panel-title">Findings（{{ (detail.findings || []).length }}）</div>
          <n-data-table v-if="(detail.findings || []).length" :columns="findingColumns" :data="detail.findings" size="small" :bordered="false" />
          <div v-else class="page-sub">无异常发现</div>
        </div>

        <div v-else-if="tab === 'comments'" class="tab-body">
          <div class="comment-input">
            <n-input v-model:value="commentText" type="textarea" :rows="2" placeholder="写下评论（如：需要人工复核）" />
            <n-button type="primary" size="small" :disabled="!commentText.trim()" @click="addComment">发送</n-button>
          </div>
          <div v-for="c in comments" :key="c.id" class="comment-item">
            <div class="comment-head"><strong>{{ c.username }}</strong><span class="page-sub">{{ fmtTs(c.createdAt) }}</span></div>
            <div class="comment-text">{{ c.content }}</div>
          </div>
          <EmptyState v-if="!comments.length" text="暂无评论" />
        </div>
        </n-drawer-content>
      </n-drawer>
    </div>

    <!-- 人工评分 -->
    <Modal v-model:show="showScore" :width="440" title="对 Trace 评分">
      <n-form label-placement="top">
        <n-form-item label="分数（0-10）" required>
          <n-input-number v-model:value="scoreForm.score" :min="0" :max="10" :step="0.5" style="width: 200px" />
        </n-form-item>
        <n-form-item label="理由 / 备注">
          <n-input v-model:value="scoreForm.comment" type="textarea" :rows="2" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-button size="small" :disabled="scoring" @click="showScore = false">取消</n-button>
        <n-button type="primary" size="small" :loading="scoring" @click="doScore">提交</n-button>
      </template>
    </Modal>
  </div>
</template>

<script setup>
import { computed, h, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NIcon, NTag } from 'naive-ui'
import { ThumbsUpOutline, ThumbsDownOutline } from '@vicons/ionicons5'
import api from '@/api'
import { enumText } from '@/common/i18n'
import { spanTypeColor as typeColor } from '@/common/colors'
import { copyToClipboard } from '@/common/clipboard'
import Modal from '@/components/Modal.vue'
import CopyableId from '@/components/CopyableId.vue'
import TimeText from '@/components/TimeText.vue'
import CodeBlock from '@/components/CodeBlock.vue'
import { useMessage } from 'naive-ui'
import EmptyState from '@/components/EmptyState.vue'
import SkeletonTable from '@/components/SkeletonTable.vue'
import { useAuthStore } from '@/store/auth'
import { fmtDur, fmtCostNullable as fmtCost } from '@/common/format'

const route = useRoute()
const router = useRouter()
const props = defineProps({ traceId: { type: String, default: '' }, mode: { type: String, default: 'panel' } })
const emit = defineEmits(['close'])
const message = useMessage()
const auth = useAuthStore()
const canViewContent = computed(() => auth.hasPermission('workspace:trust:trace:content'))
const traceId = computed(() => String(props.traceId || ''))
const detail = ref(null)
const loadError = ref('')
const selected = ref(null)
const tab = ref('input')
// Span 详情抽屉：点 span 打开；不锁滚动、无遮罩，左侧瀑布图仍可继续点
const showSpanDetail = ref(false)
const viewportWidth = ref(typeof window === 'undefined' ? 1440 : window.innerWidth)
const drawerWidth = computed(() => Math.min(760, Math.max(430, Math.round(viewportWidth.value * 0.46))))
function onViewportResize() { viewportWidth.value = window.innerWidth }
// 抽屉不遮挡内容：打开时给面板右侧留出与抽屉等宽的空间，瀑布图整体可见、可点
const panelStyle = computed(() => ({ paddingRight: showSpanDetail.value ? drawerWidth.value + 'px' : '0px' }))
function openSpan(node) {
  // 换到另一个 span 时回到 Input 页，避免停留在上一个 span 的 Metadata 上造成错觉
  if (!selected.value || selected.value.spanId !== node.spanId) tab.value = 'input'
  selected.value = node
  showSpanDetail.value = true
}
const loading = ref(true)
const evalResults = ref([])
const tags = ref([])
const tagInput = ref('')
const comments = ref([])
const commentText = ref('')
const showScore = ref(false), scoring = ref(false)
const scoreForm = reactive({ score: 8, comment: '' })
const myThumb = ref('')
const prevId = ref('')
const nextId = ref('')
const siblingTraces = ref([])
const shortSessionId = computed(() => String(detail.value?.summary?.sessionId || '').slice(0, 14))
const sessionOptions = computed(() => [
  ...siblingTraces.value.map((r) => ({
    label: r.traceId + (r.inputPreview ? ' · ' + String(r.inputPreview).slice(0, 18) : ''),
    key: r.traceId
  })),
  { label: '查看会话时间线 →', key: '__session__' }
])
function onSessionSelect(key) {
  const sid = detail.value?.summary?.sessionId
  if (key === '__session__' && sid) {
    router.push('/sessions/' + encodeURIComponent(sid))
  } else {
    goTrace(key)
  }
}

watch(traceId, load)
onMounted(() => {
  load()
  window.addEventListener('resize', onViewportResize)
})
onUnmounted(() => window.removeEventListener('resize', onViewportResize))

function goTrace(id) {
  if (!id) return
  if (props.mode === 'page') {
    router.push('/traces/' + encodeURIComponent(id))
  } else {
    router.push({ path: '/traces', query: { ...route.query, traceId: id } })
  }
}
let recentIdsCache = { at: 0, ids: [] }
async function recentTraceIds() {
  if (Date.now() - recentIdsCache.at < 30000 && recentIdsCache.ids.length) {
    return recentIdsCache.ids
  }
  const page = await api.traceSearch({ pageNumber: 1, pageSize: 200 })
  recentIdsCache = { at: Date.now(), ids: (page.records || []).map((r) => r.traceId) }
  return recentIdsCache.ids
}

let loadSeq = 0
async function load() {
  if (!traceId.value) return
  const seq = ++loadSeq
  const tid = traceId.value
  loading.value = true
  loadError.value = ''
  detail.value = null
  try {
    const data = await api.traceDetail(tid)
    if (seq !== loadSeq) return
    detail.value = data
    // 页面模式支持 ?spanId= 深链定位（Observations 点击 span 跳转），否则默认选中首个 LLM span
    const wantSpanId = props.mode === 'page' ? String(route.query.spanId || '') : ''
    const spans = detail.value?.spans || []
    selected.value = (wantSpanId && spans.find((s) => s.spanId === wantSpanId))
      || spans.find((s) => s.spanType === 'llm') || spans[0] || null
    // 从 Observations 列表带 ?spanId= 点进来时直接打开抽屉：用户点的就是这个 span
    if (wantSpanId && spans.some((s) => s.spanId === wantSpanId)) {
      showSpanDetail.value = true
    }
    if (wantSpanId && !spans.find((s) => s.spanId === wantSpanId)) {
      collapsed.value = false   // 找不到时展开全部便于人工定位
    }
    resetCollapse()
    try {
      const page = await api.evalResults({ traceId: tid, pageNumber: 1, pageSize: 50 })
      if (seq === loadSeq) evalResults.value = page.records || []
    } catch (e) { if (seq === loadSeq) evalResults.value = [] }
    try {
      // 相邻导航：按时间倒序取最近 200 条定位当前（30s 缓存，避免每次打开都扫 200 行）
      const ids = await recentTraceIds()
      if (seq !== loadSeq) return
      const idx = ids.indexOf(tid)
      prevId.value = idx >= 0 && idx + 1 < ids.length ? ids[idx + 1] : ''
      nextId.value = idx > 0 ? ids[idx - 1] : ''
    } catch (e) { if (seq === loadSeq) { prevId.value = ''; nextId.value = '' } }
    // 会话联动：同 sessionId 的其他 trace，供头部快速切换
    try {
      const sid = detail.value?.summary?.sessionId
      if (sid) {
        const page = await api.traceSearch({ sessionId: sid, pageNumber: 1, pageSize: 50 })
        if (seq === loadSeq) siblingTraces.value = (page.records || []).filter((r) => r.traceId !== tid)
      } else {
        siblingTraces.value = []
      }
    } catch (e) { if (seq === loadSeq) siblingTraces.value = [] }
    try { const t = await api.traceTags(tid) || []; if (seq === loadSeq) tags.value = t } catch (e) { if (seq === loadSeq) tags.value = [] }
    try { const c = await api.traceComments(tid) || []; if (seq === loadSeq) comments.value = c } catch (e) { if (seq === loadSeq) comments.value = [] }
  } catch (e) {
    if (seq !== loadSeq) return
    loadError.value = String(e?.message || '').startsWith('trace not found')
      ? 'Trace 不存在或已被清除（数据可能因环境重置而丢失）'
      : (e?.message || '加载失败')
  } finally { if (seq === loadSeq) loading.value = false }
}

// ---------- 标签 ----------
async function addTag() {
  const t = tagInput.value.trim()
  if (!t) return
  try {
    tags.value = await api.addTraceTags(traceId.value, [t])
    tagInput.value = ''
    message.success('标签已添加')
  } catch (e) { message.error(e.message) }
}
async function removeTag(t) {
  try { tags.value = await api.removeTraceTag(traceId.value, t) } catch (e) { message.error(e.message) }
}

// ---------- 评分 ----------
async function quickScore(score) {
  try {
    await api.humanScore(traceId.value, { score, comment: score >= 6 ? '👍 人工好评' : '👎 人工差评' })
    myThumb.value = score >= 6 ? 'up' : 'down'
    message.success('已评分 ' + score)
    const page = await api.evalResults({ traceId: traceId.value, pageNumber: 1, pageSize: 50 })
    evalResults.value = page.records || []
  } catch (e) { message.error(e.message) }
}
async function doScore() {
  scoring.value = true
  try {
    await api.humanScore(traceId.value, { score: scoreForm.score, comment: scoreForm.comment })
    myThumb.value = scoreForm.score >= 6 ? 'up' : 'down'
    message.success('评分已提交')
    showScore.value = false
    const page = await api.evalResults({ traceId: traceId.value, pageNumber: 1, pageSize: 50 })
    evalResults.value = page.records || []
  } catch (e) { message.error(e.message) }
  finally { scoring.value = false }
}

// ---------- 评论 ----------
async function addComment() {
  if (!commentText.value.trim()) return
  try {
    await api.addTraceComment(traceId.value, commentText.value.trim())
    commentText.value = ''
    comments.value = await api.traceComments(traceId.value)
  } catch (e) { message.error(e.message) }
}

// ---------- 加入数据集（Langfuse 式 trace → dataset 样本） ----------
const showAddToDataset = ref(false), addingToDataset = ref(false)
const datasetTarget = ref(null), datasetOptions = ref([])
const newDatasetKey = ref('')
const datasetForm = reactive({ input: '', expectedOutput: '' })
// 「＋ 新建数据集」常驻：没有数据集时也能在弹窗内一步建好并收录
const datasetPickOptions = computed(() => [
  { label: '＋ 新建数据集…', value: '__new__' },
  ...datasetOptions.value
])
async function openAddToDataset() {
  const sm = detail.value?.summary
  if (!sm) return
  datasetForm.input = sm.inputPreview || ''
  datasetForm.expectedOutput = sm.outputPreview || ''
  datasetTarget.value = null
  newDatasetKey.value = ''
  try {
    const list = await api.datasets()
    datasetOptions.value = (list || []).map((d) => ({ label: d.datasetKey + (d.itemCount ? `（${d.itemCount} 个样本）` : ''), value: d.id }))
  } catch (e) { datasetOptions.value = [] }
  showAddToDataset.value = true
}
async function doAddToDataset() {
  addingToDataset.value = true
  try {
    let datasetId = datasetTarget.value
    if (datasetId === '__new__') {
      const key = newDatasetKey.value.trim()
      if (!key) { message.error('请填写新数据集 Key'); return }
      const created = await api.createDataset({ datasetKey: key, description: '由 Trace ' + traceId.value + ' 收录创建' })
      datasetId = created?.id
      if (!datasetId) { message.error('数据集创建失败'); return }
    }
    if (!datasetId) { message.error('请选择数据集'); return }
    await api.addDatasetItem(datasetId, {
      traceId: traceId.value,
      input: datasetForm.input || null,
      expectedOutput: datasetForm.expectedOutput || null
    })
    message.success('已收录为数据集样本（可在数据集页运行回归实验）')
    showAddToDataset.value = false
  } catch (e) { message.error(e.message) }
  finally { addingToDataset.value = false }
}

// ---------- Raw / cURL ----------
const rawJson = computed(() => {
  if (!detail.value?.summary) return ''
  const sm = detail.value.summary
  return JSON.stringify({
    traceId: sm.traceId, sessionId: sm.sessionId, agentKey: sm.agentKey, agentVersion: sm.agentVersion,
    startTime: sm.startTime, endTime: sm.endTime, status: sm.status, modelName: sm.modelName,
    spans: (detail.value.spans || []).map((sp) => ({
      traceId: sp.traceId, spanId: sp.spanId, parentSpanId: sp.parentSpanId, agentKey: sm.agentKey,
      spanType: sp.spanType, spanName: sp.spanName, toolName: sp.toolName, modelName: sp.modelName,
      startTime: sp.startTime, endTime: sp.endTime, status: sp.status,
      inputTokens: sp.inputTokens, outputTokens: sp.outputTokens, input: sp.inputText, output: sp.outputText
    }))
  }, null, 2)
})
async function copyCurl() {
  const payload = rawJson.value
  const curl = "curl -X POST " + location.origin + "/api/v1/ingest/events \\\n  -H 'X-Trust-Key: YOUR_INGEST_KEY' \\\n  -H 'Content-Type: application/json' \\\n  -d '" + payload.replace(/'/g, "'\\''") + "'"
  const ok = await copyToClipboard(curl)
  if (ok) message.success('cURL 已复制（替换 YOUR_INGEST_KEY 后可回放）')
  else message.warning('复制失败，请手动选择文本')
}

// span attributes → 键值对（SDK 透传元数据，如 prompt_name/prompt_version）
const attrPairs = computed(() => {
  const raw = selected.value?.attributes
  if (!raw) return []
  try {
    const obj = JSON.parse(raw)
    return Object.entries(obj).map(([key, v]) => ({ key, value: typeof v === 'object' ? JSON.stringify(v) : String(v) }))
  } catch (e) { return [] }
})

// ---------- 格式化 ----------
function parseTs(s) {
  if (!s) return null
  return new Date(String(s).length >= 23 ? String(s).slice(0, 23) : String(s)).getTime()
}
function fmtTs(s) {
  if (!s) return '—'
  return String(s).replace('T', ' ').slice(0, 19)
}
const TYPE_TEXT = { agent: 'Agent', llm: 'LLM', tool: 'Tool', retrieval: 'Retrieval' }

// span 搜索过滤（按名称/类型/模型，保留父链；过滤时自动展开）
const spanFilter = ref('')
const errorOnly = ref(false)
const filteredRows = computed(() => {
  const kw = spanFilter.value.trim().toLowerCase()
  const rows = timelineRows.value
  const keep = new Set()
  for (const r of rows) {
    const kwHit = !kw || [r.spanName, r.toolName, r.spanType, r.modelName].some((v) => String(v || '').toLowerCase().includes(kw))
    const errHit = !errorOnly.value || r.status === 'ERROR'
    if (kwHit && errHit) {
      keep.add(r.spanId)
      let parent = r.parentSpanId
      while (parent && !keep.has(parent)) {
        keep.add(parent)
        parent = rows.find((x) => x.spanId === parent)?.parentSpanId
      }
    }
  }
  return rows.filter((r) => keep.has(r.spanId))
})

// ---------- 折叠树（长 trace 默认概览，按需展开） ----------
const collapsed = ref(true)
const expanded = ref(new Set())
// 概览态 = 根 span + 直接子级；按真实根 span 初始化（span id 为 UUID，无固定哨兵）
function resetCollapse() {
  collapsed.value = true
  const rows = timelineRows.value
  expanded.value = new Set(rows.filter((r) => !r.parentSpanId || !rows.some((x) => x.spanId === r.parentSpanId)).map((r) => r.spanId))
}
const childrenMap = computed(() => {
  const m = new Map()
  for (const r of timelineRows.value) {
    if (!m.has(r.parentSpanId)) m.set(r.parentSpanId, [])
    m.get(r.parentSpanId).push(r)
  }
  return m
})
function nodeChildren(spanId) {
  return childrenMap.value.get(spanId) || []
}
function toggleExpand(spanId) {
  const next = new Set(expanded.value)
  next.has(spanId) ? next.delete(spanId) : next.add(spanId)
  expanded.value = next
}
function expandAll() {
  collapsed.value = !collapsed.value
  if (!collapsed.value) {
    expanded.value = new Set(timelineRows.value.map((r) => r.spanId))
  } else {
    resetCollapse()
  }
}
// 可见行：从根出发，只走已展开节点（过滤时全部可见）
const visibleRows = computed(() => {
  const rows = filteredRows.value
  if (!collapsed.value || spanFilter.value.trim()) return rows
  // 折叠视图：根 span（无父）始终可见，其子级按 expanded 集合展开
  const roots = rows.filter((r) => !r.parentSpanId || !rows.some((x) => x.spanId === r.parentSpanId))
  const out = []
  const walk = (spanId) => {
    for (const child of nodeChildren(spanId)) {
      out.push(child)
      if (expanded.value.has(child.spanId)) walk(child.spanId)
    }
  }
  for (const r of roots) {
    out.push(r)
    if (expanded.value.has(r.spanId)) walk(r.spanId)
  }
  return out
})
// 轴标签
const axisEnd = computed(() => fmtDur(detail.value?.summary?.durationMs))
const axisMid = computed(() => fmtDur((detail.value?.summary?.durationMs || 0) / 2))

const totalIn = computed(() => (detail.value?.spans || []).reduce((a, s) => a + (s.inputTokens || 0), 0))
const totalOut = computed(() => (detail.value?.spans || []).reduce((a, s) => a + (s.outputTokens || 0), 0))
const totalCached = computed(() => (detail.value?.spans || []).reduce((a, s) => a + (s.cachedInputTokens || 0), 0))

// ---------- 树 + Gantt ----------
const timelineRows = computed(() => {
  const spans = detail.value?.spans || []
  if (!spans.length) return []
  const t0 = parseTs(detail.value.summary.startTime)
  const total = detail.value.summary.durationMs || Math.max(...spans.map((s) => (parseTs(s.startTime) - t0) + (s.durationMs || 0)), 1)
  const byId = new Map(spans.map((s) => [s.spanId, { ...s, children: [] }]))
  const roots = []
  for (const node of byId.values()) {
    const parent = node.parentSpanId ? byId.get(node.parentSpanId) : null
    if (parent) parent.children.push(node)
    else roots.push(node)
  }
  const rows = []
  const walk = (nodes, depth) => {
    nodes.sort((a, b) => (parseTs(a.startTime) || 0) - (parseTs(b.startTime) || 0))
    for (const n of nodes) {
      const offsetMs = Math.max((parseTs(n.startTime) || t0) - t0, 0)
      rows.push({ ...n, depth, childCount: n.children.length, offsetPct: Math.min(offsetMs / total * 100, 99.5), widthPct: Math.max((n.durationMs || 0) / total * 100, 0.8) })
      if (n.children.length) walk(n.children, depth + 1)
    }
  }
  walk(roots, 0)
  return rows
})

// ---------- 表格 ----------
const evalColumns = [
  { title: '评估器', key: 'evaluatorKey', width: 160 },
  { title: '分数', key: 'score', width: 70, align: 'right', render: (r) => h('span', { class: 'num', style: `color:${r.label === 'PASS' ? 'var(--success)' : r.label === 'FAIL' ? 'var(--error)' : 'inherit'}` }, r.score == null ? '—' : String(r.score)) },
  { title: '标签', key: 'label', width: 100, render: (r) => h(NTag, { size: 'small', bordered: false, type: r.label === 'PASS' ? 'success' : r.label === 'FAIL' ? 'error' : 'warning' }, { default: () => enumText('evalLabel', r.label) }) },
  { title: '原因', key: 'reason', ellipsis: { tooltip: true } }
]
const findingColumns = [
  { title: '严重级', key: 'severity', width: 90, render: (r) => h(NTag, { size: 'small', bordered: false, type: r.severity === 'CRITICAL' ? 'error' : r.severity === 'HIGH' ? 'warning' : 'default' }, { default: () => enumText('severity', r.severity) }) },
  { title: '类型', key: 'type', width: 160 },
  { title: '消息', key: 'message', ellipsis: { tooltip: true } }
]

</script>

<style scoped>
.trace-panel { height: 100%; min-height: 0; display: flex; flex-direction: column; overflow: auto; transition: padding-right 0.18s ease; }
/* 整页模式：卡片占满视口剩余高度；span 树与 Tab 标签固定，仅 Tab 内容区滚动 */
.trace-panel.page-mode { overflow: hidden; height: 100%; min-height: 0; }
.trace-panel.page-mode .panel-body { flex: 1; min-height: 0; overflow: hidden; }
/* 详情改抽屉后，整页模式下把原本留给底部 Tabs 的高度让给 span 树 */
.trace-panel.page-mode .sec-block { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.trace-panel.page-mode .tree-scroll { flex: 1; height: auto; min-height: 200px; }
/* Span 详情抽屉：标题行排版 */
.drawer-head { display: flex; align-items: center; gap: 8px; min-width: 0; }
.drawer-head .d-name { font-size: 14px; font-weight: 600; color: var(--text-primary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.drawer-head .d-dur { color: var(--text-muted); font-size: 12px; margin-left: auto; }
.panel-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-wrap: wrap; }
.head-id { font-size: 13px; font-weight: 600; color: var(--text-primary) !important; }
/* 图标按钮行比页头工具更密（仅图标），gap 收敛到 4 */
.head-actions { display: flex; align-items: center; gap: 4px; }

.tag-chip { margin-left: 2px; }
/* 身份行 + 指标行整体吸顶。整页模式才需要（侧滑面板里父级自己会滚，sticky 无意义且
   会和面板滚动打架）；背景用卡片底色，避免滚动的 span 树从下面透上来。 */
.panel-head-stack { display: block; }
.trace-panel.page-mode .panel-head-stack {
  position: static;
  z-index: auto;
  background: transparent;
  padding-bottom: 4px;
}
.meta-strip { display: flex; flex-wrap: wrap; gap: 14px; margin-top: 10px; padding: 8px 12px; background: var(--bg-subtle); border-radius: 8px; }
.m-item { display: flex; align-items: center; gap: 5px; font-size: 12px; }
.m-k { color: var(--text-muted); }
.link { color: var(--primary); cursor: pointer; }
.link:hover { text-decoration: underline; }
.mono { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; }
.panel-body { display: flex; flex-direction: column; min-height: 0; }
.sec-block { margin-top: 12px; }
.panel-title-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; row-gap: 6px; }
.panel-title { font-size: 11.5px; font-weight: 600; color: var(--text-muted); margin: 12px 0 6px; text-transform: uppercase; letter-spacing: 0.04em; white-space: nowrap; }
.tl-ruler { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.tl-ruler-track { flex: 1; display: flex; justify-content: space-between; font-size: 10px; color: var(--text-muted); border-bottom: 1px dashed var(--border); padding-bottom: 2px; }
.t-empty { padding: 18px 8px; text-align: center; font-size: 12px; color: var(--text-muted); }
.t-waterfall { flex: 0 0 30%; height: 8px; background: var(--bg-subtle); border-radius: 4px; position: relative; overflow: hidden; }
.tl-bar { height: 100%; border-radius: 4px; opacity: 0.85; min-width: 2px; }
.tree-scroll { height: 320px; overflow-y: auto; overscroll-behavior: contain; border: 1px solid var(--border); border-radius: 8px; background: var(--bg-surface); padding: 4px; }
.tree { display: flex; flex-direction: column; }
.t-row { display: flex; align-items: center; gap: 8px; height: 28px; padding: 0 6px; border-radius: 6px; cursor: pointer; }
.t-row:hover { background: var(--bg-subtle); }
.t-row.selected { background: var(--primary-bg); }
.t-name { flex: 1; min-width: 0; display: flex; align-items: center; gap: 6px; overflow: hidden; white-space: nowrap; }
.tw-caret { flex: 0 0 12px; width: 12px; text-align: center; color: var(--text-muted); cursor: pointer; font-size: 10px; user-select: none; }
.tw-caret:hover { color: var(--text-primary); }
.t-waterfall { flex: 0 0 30%; height: 8px; background: var(--bg-subtle); border-radius: 4px; position: relative; overflow: hidden; }
.tl-bar { height: 100%; border-radius: 4px; opacity: 0.85; min-width: 2px; }
.type-dot { flex: 0 0 8px; width: 8px; height: 8px; border-radius: 2px; }
.t-title { font-size: 12.5px; font-weight: 500; overflow: hidden; text-overflow: ellipsis; }
.t-meta { flex: 0 1 auto; font-size: 11px; color: var(--text-muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.t-dur { flex: 0 0 84px; text-align: right; font-size: 11.5px; color: var(--text-secondary); white-space: nowrap; }
.type-legend { display: inline-flex; gap: 10px; margin-left: 12px; flex-wrap: wrap; }
.legend-item { display: inline-flex; align-items: center; gap: 3px; font-size: 10.5px; color: var(--text-muted); white-space: nowrap; }
.legend-item .dot { width: 8px; height: 8px; border-radius: 2px; display: inline-block; }
.tab-body { padding-top: 12px; }
.io-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; font-size: 13px; }
.desc-list { display: grid; grid-template-columns: 110px 1fr; row-gap: 8px; font-size: 12.5px; }
.desc-list .k { color: var(--text-muted); }
.comment-input { display: flex; gap: 8px; align-items: flex-start; margin-bottom: 12px; }
.comment-item { border: 1px solid var(--border); border-radius: 8px; padding: 8px 12px; margin-bottom: 8px; }
.comment-head { display: flex; align-items: baseline; gap: 8px; margin-bottom: 2px; font-size: 12.5px; }
.comment-text { font-size: 13px; white-space: pre-wrap; word-break: break-word; }
.attr-grid { display: grid; grid-template-columns: 180px 1fr; gap: 6px 12px; font-size: 12px; background: var(--bg-subtle); border-radius: 6px; padding: 8px 12px; margin-bottom: 8px; }
.attr-grid .k { color: var(--text-muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.attr-v { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; word-break: break-all; }
</style>
