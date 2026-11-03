import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/store/auth'

const routes = [
  { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { public: true } },
  {
    path: '/',
    component: () => import('@/layout/MainLayout.vue'),
    children: [
      { path: '', redirect: '/dashboard' },
      { path: 'dashboard', name: 'dashboard', component: () => import('@/views/trust/DashboardView.vue'), meta: { title: '总览', perm: 'workspace:trust:overview:query' } },
      { path: 'traces', name: 'traces', component: () => import('@/views/trust/TraceListView.vue'), meta: { title: '调用链路', perm: 'workspace:trust:trace:query' } },
      { path: 'traces/compare', name: 'trace-compare', component: () => import('@/views/trust/TraceCompareView.vue'), meta: { title: 'Trace 对比', perm: 'workspace:trust:trace:query' } },
      { path: 'traces/:traceId', name: 'trace-detail', component: () => import('@/views/trust/TraceDetailView.vue'), meta: { title: 'Trace 详情', perm: 'workspace:trust:trace:query' } },
      { path: 'usage', name: 'usage', component: () => import('@/views/trust/UsageView.vue'), meta: { title: '用量成本', group: 'insights', perm: 'workspace:trust:overview:query' } },
      { path: 'alerts', name: 'alerts', component: () => import('@/views/trust/AlertsView.vue'), meta: { title: '事件告警', perm: 'workspace:trust:incident:query' } },
      { path: 'quality', name: 'quality', component: () => import('@/views/trust/QualityOverviewView.vue'), meta: { title: '质量总览', perm: 'workspace:trust:overview:query' } },
      { path: 'evaluators', name: 'evaluators', component: () => import('@/views/trust/EvaluatorView.vue'), meta: { title: '评估器', perm: 'workspace:trust:evaluator:query' } },
      { path: 'evaluations', name: 'evaluations', component: () => import('@/views/trust/EvaluationView.vue'), meta: { title: '评估结果', perm: 'workspace:trust:evaluation:query' } },
      { path: 'sessions', redirect: { path: '/traces', query: { tab: 'sessions' } } },
      { path: 'sessions/:sessionId', name: 'session-detail', component: () => import('@/views/trust/SessionDetailView.vue'), meta: { title: '会话详情', perm: 'workspace:trust:trace:query' } },
      { path: 'users', name: 'users', component: () => import('@/views/trust/UsersInsightView.vue'), meta: { title: '用户洞察', group: 'insights', perm: 'workspace:trust:overview:query' } },
      { path: 'agents', name: 'agents', component: () => import('@/views/trust/AgentView.vue'), meta: { title: '智能体接入', perm: 'workspace:trust:agent:query' } },
      { path: 'annotation-queues', name: 'annotation-queues', component: () => import('@/views/trust/AnnotationQueueView.vue'), meta: { title: '注解队列', perm: 'workspace:trust:trace:query' } },
      { path: 'annotation-queues/:id', name: 'annotation-queue-detail', component: () => import('@/views/trust/AnnotationQueueDetailView.vue'), meta: { title: '标注', perm: 'workspace:trust:trace:query' } },
      { path: 'playground', name: 'playground', component: () => import('@/views/trust/PlaygroundView.vue'), meta: { title: '试跑', group: 'workbench', perm: 'workspace:trust:prompt:query' } },
      { path: 'matrix', name: 'matrix', component: () => import('@/views/trust/MatrixView.vue'), meta: { title: '矩阵实验', group: 'workbench', perm: 'workspace:trust:prompt:query' } },
      { path: 'prompts', name: 'prompts', component: () => import('@/views/trust/PromptView.vue'), meta: { title: '提示词', group: 'workbench', perm: 'workspace:trust:prompt:query' } },
      { path: 'datasets', name: 'datasets', component: () => import('@/views/trust/DatasetView.vue'), meta: { title: '数据集', group: 'assets', perm: 'workspace:trust:dataset:query' } },
      { path: 'datasets/:id', name: 'dataset-detail', component: () => import('@/views/trust/DatasetDetailView.vue'), meta: { title: '数据集详情', perm: 'workspace:trust:dataset:query' } },
      { path: 'datasets/:id/runs/:runId', name: 'dataset-run-detail', component: () => import('@/views/trust/DatasetDetailView.vue'), meta: { title: '运行报告', perm: 'workspace:trust:dataset:query' } },
      { path: 'business-experiments', name: 'business-experiments', component: () => import('@/views/trust/BusinessExperimentView.vue'), meta: { title: '业务实验', perm: 'workspace:trust:evaluation:query' } },
      { path: 'domain-packs', name: 'domain-packs', component: () => import('@/views/trust/DomainPackView.vue'), meta: { title: '领域包', group: 'assets', perm: 'workspace:trust:dataset:query' } },
      { path: 'auto-rubrics', name: 'auto-rubrics', component: () => import('@/views/trust/AutoRubricView.vue'), meta: { title: '规则候选', group: 'assets', perm: 'workspace:trust:dataset:query' } },
      { path: 'quality-cases', name: 'quality-cases', component: () => import('@/views/trust/QualityCaseView.vue'), meta: { title: '质量案例', group: 'assets', perm: 'workspace:trust:evaluation:query' } },
      { path: 'models', name: 'models', component: () => import('@/views/trust/ModelPriceView.vue'), meta: { title: '模型价格', perm: 'workspace:trust:model:query' } },
      { path: 'sys/users', name: 'sys-users', component: () => import('@/views/sys/UsersRolesView.vue'), meta: { title: '用户与角色', permAny: ['workspace:sys:user:manage', 'workspace:sys:role:manage'] } },
      { path: 'sys/roles', redirect: { path: '/sys/users', query: { tab: 'roles' } } },
      { path: 'sys/audit', redirect: { path: '/sys/settings', query: { tab: 'audit' } } },
      { path: 'sys/notify', redirect: { path: '/sys/settings', query: { tab: 'notify' } } },
      { path: 'sys/settings', name: 'sys-settings', component: () => import('@/views/sys/SettingsView.vue'), meta: { title: '系统设置', permAny: ['workspace:sys:setting:manage', 'workspace:sys:notify:manage', 'workspace:sys:audit:query', 'workspace:sys:openapi:manage'] } },
      { path: ':pathMatch(.*)*', redirect: () => firstAllowedLocation(useAuthStore()) || '/login' },
    ]
  }
]

const router = createRouter({ history: createWebHistory(), routes })

// 权限兜底：用户没有 /dashboard 权限时不能反复重定向到 /dashboard，
// 按菜单优先级找第一个可访问页面；一个都没有则回登录页（避免无限重定向）。
const FALLBACK_ROUTES = [
  [{ path: '/dashboard' }, 'workspace:trust:overview:query'],
  [{ path: '/traces' }, 'workspace:trust:trace:query'],
  [{ path: '/alerts' }, 'workspace:trust:incident:query'],
  [{ path: '/evaluations' }, 'workspace:trust:evaluation:query'],
  [{ path: '/evaluators' }, 'workspace:trust:evaluator:query'],
  [{ path: '/datasets' }, 'workspace:trust:dataset:query'],
  [{ path: '/agents' }, 'workspace:trust:agent:query'],
  [{ path: '/sys/users' }, 'workspace:sys:user:manage'],
  [{ path: '/sys/users', query: { tab: 'roles' } }, 'workspace:sys:role:manage'],
  [{ path: '/sys/settings' }, 'workspace:sys:setting:manage'],
  [{ path: '/sys/settings', query: { tab: 'notify' } }, 'workspace:sys:notify:manage'],
  [{ path: '/sys/settings', query: { tab: 'audit' } }, 'workspace:sys:audit:query'],
  [{ path: '/sys/settings', query: { tab: 'tokens' } }, 'workspace:sys:openapi:manage']
]
function firstAllowedLocation(auth) {
  const hit = FALLBACK_ROUTES.find(([, perm]) => auth.hasPermission(perm))
  return hit ? hit[0] : null
}

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (to.meta.public) return true
  if (!auth.isLoggedIn) {
    const restored = await auth.restore()
    if (!restored) return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.meta.perm && !auth.hasPermission(to.meta.perm)) {
    return firstAllowedLocation(auth) || { path: '/login' }
  }
  if (to.meta.permAny && !to.meta.permAny.some((p) => auth.hasPermission(p))) {
    return firstAllowedLocation(auth) || { path: '/login' }
  }
  return true
})

// 发版自愈：旧标签页懒加载已被新构建清空的 chunk 时（vite emptyOutDir），
// 动态 import 404 会导致路由静默中断、表现为"点击没反应"。
// 此处自动整页刷新拿到最新 index.html 与新 hash 资源；afterEach 成功导航后清除标记，chunk 真缺失时不死循环。
router.onError((error, to) => {
  const msg = String((error && error.message) || '')
  if (!/dynamically imported module|Importing a module script failed|Loading( CSS)? chunk/i.test(msg)) {
    return
  }
  const key = 'emonlens.chunk-reload'
  if (sessionStorage.getItem(key)) {
    sessionStorage.removeItem(key)
    return
  }
  sessionStorage.setItem(key, String(Date.now()))
  window.location.href = (to && to.fullPath) || '/'
})

// 路由成功解析（chunk 已可用）即清除自愈标记
router.afterEach(() => {
  try { sessionStorage.removeItem('emonlens.chunk-reload') } catch (e) { /* ignore */ }
})

export default router
