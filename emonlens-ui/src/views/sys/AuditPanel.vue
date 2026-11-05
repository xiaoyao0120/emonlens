<template>
  <div class="panel-root">
    <div class="page-toolbar">
      <div class="toolbar-filters">
        <n-input v-model:value="q.username" placeholder="用户名" size="small" style="width: 140px" clearable @clear="search" @keyup.enter="search" />
        <n-select v-model:value="q.action" placeholder="操作类型（可选可输）" size="small" style="width: 240px" clearable filterable tag
          :options="actionOptions" @update:value="search" />
        <n-select v-model:value="timeRange" size="small" style="width: 130px" :options="[
          { label: '近 1 小时', value: '1h' }, { label: '近 24 小时', value: '24h' },
          { label: '近 7 天', value: '7d' }, { label: '近 30 天', value: '30d' }, { label: '全部', value: 'all' }]"
          @update:value="search" />
        <lan-button type="primary" size="small" :icon="SearchOutline" @click="search">查询</lan-button>
      </div>
      <div class="toolbar-actions">
        <n-button size="small" :loading="exporting" @click="exportCsv">导出 CSV</n-button>
      </div>
    </div>
    <n-card size="small" class="table-card">
      <n-data-table class="main-table" flex-height style="height: 100%" :columns="columns" :data="records" size="small" :bordered="false" remote :scroll-x="730"
        :pagination="pagination" :loading="loading"
        :row-props="(row) => (row.detail ? { style: 'cursor:pointer', onClick: () => openDetail(row) } : {})" />
    </n-card>

    <!-- 参数快照详情 -->
    <Modal v-model:show="showDetail" :width="640" :title="'操作详情 · ' + (detailRow?.action || '')">
      <div class="desc-list" style="margin-bottom:12px">
        <span class="k">时间</span><span class="num">{{ fmtTs(detailRow?.createdAt) }}</span>
        <span class="k">用户</span><span class="mono">{{ detailRow?.username || '—' }}</span>
        <span class="k">资源</span><span class="mono">{{ detailRow?.resourceType || '—' }} / {{ detailRow?.resourceId || '—' }}</span>
        <span class="k">IP</span><span class="mono">{{ detailRow?.clientIp || '—' }}</span>
      </div>
      <CodeBlock v-if="detailPretty" :content="detailPretty" label="请求参数快照（敏感字段已掩码）" />
      <EmptyState v-else text="该操作无参数快照（历史记录或无请求体）" />
    </Modal>
  </div>
</template>

<script setup>
import { computed, h, onMounted, reactive, ref } from 'vue'
import { NTag, useMessage } from 'naive-ui'
import api from '@/api'
import { SearchOutline } from '@vicons/ionicons5'
import { toCsv, downloadCsv } from '@/common/csv'
import CodeBlock from '@/components/CodeBlock.vue'
import EmptyState from '@/components/EmptyState.vue'
import Modal from '@/components/Modal.vue'

const message = useMessage()
// action 初始必须是 null：tag 模式下空字符串会被当作"一个空标签"渲染成空白 chip，
// 占位文本「操作类型（可选可输）」因此不显示（页面 E2E 抓到的可用性问题）
const q = reactive({ username: '', action: null })
const timeRange = ref('7d')
const records = ref([]), loading = ref(false), exporting = ref(false)
const showDetail = ref(false), detailRow = ref(null)
const pagination = reactive({
  page: 1, pageSize: 10, itemCount: 0, showSizePicker: true, pageSizes: [10, 20, 50, 100],
  prefix: ({ itemCount }) => '共 ' + itemCount + ' 条',
  onChange: (page) => { pagination.page = page; load() },
  onUpdatePageSize: (pageSize) => { pagination.pageSize = pageSize; pagination.page = 1; load() }
})

const actionOptions = computed(() => {
  const set = new Set(records.value.map((r) => r.action).filter(Boolean))
  return [...set].map((a) => ({ label: a, value: a }))
})
const detailPretty = computed(() => {
  const raw = detailRow.value?.detail
  if (!raw) return ''
  try { return JSON.stringify(JSON.parse(raw), null, 2) } catch (e) { return raw }
})

function openDetail(row) {
  if (!row.detail) return
  detailRow.value = row
  showDetail.value = true
}
function fmtTs(s) {
  return String(s || '').replace('T', ' ').slice(0, 19)
}
function timeFromFor(range) {
  const now = Date.now()
  const hours = { '1h': 1, '24h': 24, '7d': 24 * 7, '30d': 24 * 30 }[range]
  if (!hours) return undefined
  const d = new Date(now - hours * 3600 * 1000)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

const columns = [
  { title: '时间', key: 'createdAt', width: 145, render: (r) => h('span', { class: 'num', style: 'font-size:11.5px' }, fmtTs(r.createdAt)) },
  { title: '用户', key: 'username', width: 90, render: (r) => h('span', { class: 'mono' }, r.username) },
  { title: '操作', key: 'action', minWidth: 165, ellipsis: { tooltip: true }, render: (r) => h('span', { class: 'mono', style: 'font-size:12px' }, r.action) },
  { title: '资源', key: 'resourceType', minWidth: 150, ellipsis: { tooltip: true }, render: (r) => (r.resourceType || '—') + (r.resourceId ? ' / ' + r.resourceId : '') },
  { title: '参数', key: 'detail', width: 75, render: (r) => r.detail
      ? h(NTag, { size: 'tiny', bordered: false, type: 'info' }, { default: () => '有快照' })
      : h('span', { style: 'color:var(--text-muted);font-size:12px' }, '—') },
  { title: 'IP', key: 'clientIp', width: 110, ellipsis: { tooltip: true }, render: (r) => h('span', { class: 'mono', style: 'font-size:11.5px' }, r.clientIp || '—') }
]

function search() { pagination.page = 1; load() }
async function load() {
  loading.value = true
  try {
    const page = await api.auditLogs({
      username: q.username || undefined, action: q.action || undefined,
      timeFrom: timeFromFor(timeRange.value),
      pageNumber: pagination.page, pageSize: pagination.pageSize
    })
    records.value = page.records || []
    pagination.itemCount = page.total || 0
  } catch (e) { message.error(e.message || '审计日志加载失败') } finally { loading.value = false }
}
async function fetchAuditPage(page) {
  return api.auditLogs({
    username: q.username || undefined, action: q.action || undefined,
    timeFrom: timeFromFor(timeRange.value),
    pageNumber: page, pageSize: 500
  })
}

async function exportCsv() {
  exporting.value = true
  try {
    // 分页拉全量，避免超过单页 500 条时导出遗漏
    const first = await fetchAuditPage(1)
    const rows = [...(first.records || [])]
    const total = first.total || rows.length
    const pageCount = Math.ceil(total / 500)
    for (let page = 2; page <= pageCount; page += 1) {
      const next = await fetchAuditPage(page)
      rows.push(...(next.records || []))
    }
    const headers = ['时间', '用户', '操作', '资源类型', '资源ID', '参数快照', 'IP']
    const csv = toCsv(headers, rows.map((r) => [fmtTs(r.createdAt), r.username || '', r.action || '', r.resourceType || '', r.resourceId || '', r.detail || '', r.clientIp || '']))
    downloadCsv('emonlens-audit-logs.csv', csv)
  } catch (e) { message.error(e.message) } finally { exporting.value = false }
}
onMounted(load)
</script>

<style scoped>
.desc-list { display: grid; grid-template-columns: 80px 1fr; row-gap: 6px; font-size: 12.5px; }
.desc-list .k { color: var(--text-muted); }
.mono { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; }
.num { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; font-variant-numeric: tabular-nums; }
</style>
