// 全站菜单唯一来源：MainLayout 侧栏与 CommandPalette ⌘K 共用
// IA 对标 Langfuse/Braintrust：MONITOR / EVALUATE / BUILD / MANAGE
import { h } from 'vue'
import { NIcon } from 'naive-ui'
import {
  AnalyticsOutline,
  ChatboxOutline,
  ClipboardOutline,
  CubeOutline,
  FlaskOutline,
  GitNetworkOutline,
  HardwareChipOutline,
  OptionsOutline,
  PeopleOutline,
  PricetagsOutline,
  ShieldCheckmarkOutline,
  SpeedometerOutline,
  TerminalOutline,
  WarningOutline,
} from '@vicons/ionicons5'

export const MENU_SECTION_LABELS = {
  MONITOR: '观测',
  EVALUATE: '评测',
  BUILD: '构建',
  MANAGE: '管理'
}

/** 同一菜单下用二级 Tab 展示的页面；菜单只保留一个入口。 */
export const TAB_GROUPS = {
  insights: [
    { label: '用户洞察', path: '/users', perm: 'workspace:trust:overview:query' },
    { label: '用量成本', path: '/usage', perm: 'workspace:trust:overview:query' }
  ],
  assets: [
    { label: '数据集', path: '/datasets', perm: 'workspace:trust:dataset:query' },
    { label: '领域包', path: '/domain-packs', perm: 'workspace:trust:dataset:query' },
    { label: '规则候选', path: '/auto-rubrics', perm: 'workspace:trust:dataset:query' },
    { label: '质量案例', path: '/quality-cases', perm: 'workspace:trust:evaluation:query' }
  ],
  workbench: [
    { label: '试跑', path: '/playground', perm: 'workspace:trust:prompt:query' },
    { label: '矩阵实验', path: '/matrix', perm: 'workspace:trust:prompt:query' },
    { label: '提示词', path: '/prompts', perm: 'workspace:trust:prompt:query' }
  ],
}

export const MENU = [
  // MONITOR
  { key: 'mon-overview', label: '总览', icon: SpeedometerOutline, path: '/dashboard', perm: 'workspace:trust:overview:query', section: 'MONITOR' },
  { key: 'mon-traces', label: '调用链路', icon: GitNetworkOutline, path: '/traces', perm: 'workspace:trust:trace:query', section: 'MONITOR' },
  { key: 'mon-alerts', label: '事件告警', icon: WarningOutline, path: '/alerts', perm: 'workspace:trust:incident:query', section: 'MONITOR' },
  { key: 'mon-insights', label: '洞察与成本', icon: PeopleOutline, path: '/users', group: 'insights', perm: 'workspace:trust:overview:query', section: 'MONITOR' },
  // EVALUATE
  { key: 'eval-quality', label: '质量总览', icon: ShieldCheckmarkOutline, path: '/quality', perm: 'workspace:trust:overview:query', section: 'EVALUATE' },
  { key: 'eval-configs', label: '评估器', icon: FlaskOutline, path: '/evaluators', perm: 'workspace:trust:evaluator:query', section: 'EVALUATE' },
  { key: 'eval-results', label: '评估结果', icon: AnalyticsOutline, path: '/evaluations', perm: 'workspace:trust:evaluation:query', section: 'EVALUATE' },
  { key: 'eval-quality-board', label: '评估器质量', icon: ShieldCheckmarkOutline, path: '/evaluator-quality', perm: 'workspace:trust:evaluation:query', section: 'EVALUATE' },
  { key: 'eval-assets', label: '数据集与资产', icon: CubeOutline, path: '/datasets', group: 'assets', perm: 'workspace:trust:dataset:query', section: 'EVALUATE' },
  { key: 'eval-business', label: '业务实验', icon: AnalyticsOutline, path: '/business-experiments', perm: 'workspace:trust:evaluation:query', section: 'EVALUATE' },
  { key: 'eval-annotations', label: '标注队列', icon: ClipboardOutline, path: '/annotation-queues', perm: 'workspace:trust:trace:query', section: 'EVALUATE' },
  // BUILD
  { key: 'build-workbench', label: '调试与提示词', icon: TerminalOutline, path: '/playground', group: 'workbench', perm: 'workspace:trust:prompt:query', section: 'BUILD' },
  { key: 'build-models', label: '模型价格', icon: PricetagsOutline, path: '/models', perm: 'workspace:trust:model:query', section: 'BUILD' },
  // MANAGE
  { key: 'mng-agents', label: '智能体接入', icon: HardwareChipOutline, path: '/agents', perm: 'workspace:trust:agent:query', section: 'MANAGE' },
  { key: 'mng-users', label: '用户与角色', icon: PeopleOutline, path: '/sys/users', perm: 'workspace:sys:user:manage', section: 'MANAGE' },
  { key: 'mng-settings', label: '系统设置', icon: OptionsOutline, path: '/sys/settings', perm: 'workspace:sys:setting:manage', section: 'MANAGE' }
]

export const MENU_SECTIONS = ['MONITOR', 'EVALUATE', 'BUILD', 'MANAGE']

export function renderMenuIcon(icon) {
  return () => h(NIcon, null, { default: () => h(icon) })
}
