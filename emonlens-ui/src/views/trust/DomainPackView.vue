<template>
  <div class="base-page">
    <div class="head-stack">
      <div class="page-toolbar">
        <div class="toolbar-actions">
          <n-button type="primary" size="small" @click="openCreate">＋ 发布领域包版本</n-button>
        </div>
      </div>
    </div>
    <n-card size="small" class="table-card">
      <n-data-table class="main-table" flex-height :columns="columns" :data="packs" size="small" :bordered="false"
        :loading="loading" :row-key="(r) => r.id" />
      <div v-if="!loading && !packs.length" class="page-sub mt8">还没有领域包。发布一个版本后，可在数据集「样本与设置」里绑定。</div>
    </n-card>

    <Modal v-model:show="showVersions" :width="760" :title="'版本历史：' + versionPackKey">
      <n-data-table :columns="versionColumns" :data="versionRows" size="small" :max-height="420"
        :bordered="false" :loading="versionsLoading" :row-key="(r) => r.id" />
      <div v-if="!versionsLoading && !versionRows.length" class="page-sub">暂无版本记录。</div>
    </Modal>

    <Modal v-model:show="showCreate" :width="720" title="发布领域包版本">
      <n-form label-placement="top">
        <div class="form-grid">
          <n-form-item label="packKey"><n-input v-model:value="form.packKey" placeholder="customer-support" /></n-form-item>
          <n-form-item label="version"><n-input v-model:value="form.version" placeholder="1.0.0" /></n-form-item>
        </div>
        <n-form-item label="名称" required><n-input v-model:value="form.name" placeholder="如 客服领域包" /></n-form-item>
        <n-form-item label="说明"><n-input v-model:value="form.description" type="textarea" :rows="2" /></n-form-item>
        <n-form-item label="manifest（domain-pack/v1）">
          <n-input v-model:value="form.manifestText" type="textarea" :rows="14" class="mono" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-button size="small" :disabled="saving" @click="showCreate = false">取消</n-button>
        <n-button type="primary" size="small" :loading="saving" @click="doCreate">发布</n-button>
      </template>
    </Modal>

    <Modal v-model:show="showDetail" :width="720" :title="detail ? detail.name : '领域包'">
      <template v-if="detail">
        <div class="page-sub" style="margin-bottom:8px">
          {{ detail.packKey }}@{{ detail.version }} · {{ enumText('packStatus', detail.status) }} ·
          digest <span class="mono">{{ detail.digest }}</span>
          <span v-if="detail.compatibility"> · {{ detail.compatibility }}</span>
        </div>
        <pre class="json-box">{{ JSON.stringify(detail.manifest, null, 2) }}</pre>
      </template>
    </Modal>
  </div>
</template>

<script setup>
import { h, onMounted, reactive, ref } from 'vue'
import { NButton, NPopconfirm, NTag, useMessage } from 'naive-ui'
import api from '@/api'
import { enumText } from '@/common/i18n'

const message = useMessage()
const packs = ref([]), loading = ref(false), saving = ref(false)
const showCreate = ref(false), showDetail = ref(false), detail = ref(null)
const showVersions = ref(false), versionsLoading = ref(false), versionRows = ref([]), versionPackKey = ref('')

const sampleManifest = {
  schemaVersion: 'domain-pack/v1',
  qualityModel: {
    dimensions: [
      { key: 'accuracy', label: '准确性', weight: 2 },
      { key: 'safety', label: '安全性', weight: 1, dependsOn: ['accuracy'] }
    ]
  },
  riskTaxonomy: [
    { key: 'high', label: '高风险' },
    { key: 'low', label: '低风险' }
  ],
  datasetBlueprint: { minItems: 20, riskMin: { high: 5 }, maxStaleDays: 30 },
  gateTemplates: [{ key: 'default', name: '默认准入' }]
}
const form = reactive({ packKey: '', version: '1.0.0', name: '', description: '',
  manifestText: JSON.stringify(sampleManifest, null, 2) })

const columns = [
  { title: 'packKey', key: 'packKey', width: 150, render: (r) => h('span', { class: 'mono' }, r.packKey) },
  { title: 'version', key: 'version', width: 80, render: (r) => h('span', { class: 'mono' }, r.version) },
  { title: '名称', key: 'name', minWidth: 140 },
  { title: '状态', key: 'status', width: 95, render: (r) => h(NTag, {
      size: 'tiny', bordered: false, type: r.status === 'PUBLISHED' ? 'success' : 'default'
    }, { default: () => enumText('packStatus', r.status) }) },
  { title: 'digest', key: 'digest', width: 120, render: (r) => h('span', { class: 'mono' }, r.digest.slice(0, 12) + '…') },
  { title: '动作', key: 'actions', width: 170, render: (r) => h('div', { style: 'display:flex;gap:6px' }, [
      h(NButton, { size: 'tiny', onClick: () => openDetail(r) }, { default: () => '查看' }),
      h(NButton, { size: 'tiny', onClick: () => openVersions(r) }, { default: () => '版本' }),
      h(NPopconfirm, { onPositiveClick: () => deprecate(r) }, {
        trigger: () => h(NButton, { size: 'tiny', disabled: r.status === 'DEPRECATED' }, { default: () => '弃用' }),
        default: () => '弃用后不能恢复；已引用该版本的 run 不受影响。'
      })
    ]) }
]

async function load() {
  loading.value = true
  try { packs.value = await api.domainPacks() } catch (e) {
    message.error('加载领域包失败：' + (e.message || e))
  } finally { loading.value = false }
}
function openCreate() {
  Object.assign(form, { packKey: '', version: '1.0.0', name: '', description: '',
    manifestText: JSON.stringify(sampleManifest, null, 2) })
  showCreate.value = true
}
async function doCreate() {
  let manifest
  try { manifest = JSON.parse(form.manifestText) } catch (e) {
    message.error('manifest 不是合法 JSON')
    return
  }
  saving.value = true
  try {
    const created = await api.createDomainPack({
      packKey: form.packKey.trim(), version: form.version.trim(), name: form.name.trim(),
      description: form.description.trim() || null, manifest
    })
    showCreate.value = false
    message.success('已发布 ' + created.packKey + '@' + created.version)
    await load()
  } catch (e) {
    message.error('发布失败：' + (e.message || e))
  } finally { saving.value = false }
}
const versionColumns = [
  { title: 'version', key: 'version', width: 80, render: (r) => h('span', { class: 'mono' }, r.version) },
  { title: '状态', key: 'status', width: 95, render: (r) => h(NTag, {
      size: 'tiny', bordered: false, type: r.status === 'PUBLISHED' ? 'success' : 'default'
    }, { default: () => enumText('packStatus', r.status) }) },
  { title: 'digest', key: 'digest', minWidth: 180, ellipsis: { tooltip: true },
    render: (r) => h('span', { class: 'mono' }, r.digest) },
  { title: '兼容性', key: 'compatibility', width: 120, render: (r) => r.compatibility || '—' },
  { title: '创建时间', key: 'createdAt', width: 160, render: (r) => String(r.createdAt || '').replace('T', ' ').slice(0, 19) }
]
async function openDetail(row) {
  try {
    detail.value = await api.domainPack(row.id)
    showDetail.value = true
  } catch (e) { message.error('加载详情失败：' + (e.message || e)) }
}
async function openVersions(row) {
  versionPackKey.value = row.packKey
  versionRows.value = []
  showVersions.value = true
  versionsLoading.value = true
  try {
    versionRows.value = await api.domainPackVersions(row.packKey) || []
  } catch (e) {
    message.error('加载版本失败：' + (e.message || e))
  } finally { versionsLoading.value = false }
}
async function deprecate(row) {
  try {
    await api.deprecateDomainPack(row.id)
    message.success('已弃用')
    await load()
  } catch (e) { message.error('弃用失败：' + (e.message || e)) }
}
onMounted(load)
</script>

<style scoped>
.json-box { max-height: 480px; overflow: auto; padding: 12px; background: var(--bg-subtle); border: 1px solid var(--border); border-radius: 8px; font-size: 12px; }
</style>
