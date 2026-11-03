// 共享色板：span 类型 / 监控类型 / 评估器类型 语义色（与 :root 令牌同一体系）
// 禁止在页面里另写 hex；新语义色先加到这里
export const SPAN_TYPE_COLORS = {
  agent: '#3B77F1',
  llm: '#7B6FD0',
  tool: '#1B8A4E',
  retrieval: '#D9A93B'
}

export const MONITOR_KIND_COLORS = {
  VALIDATION: '#3B77F1',
  TRAJECTORY: '#7B6FD0',
  EVALUATION: '#1B8A4E',
  METRIC: '#D9A93B'
}

export const EVALUATOR_TYPE_COLORS = {
  EXPRESSION: '#3B77F1',
  REGEX: '#1B8A4E',
  LLM_JUDGE: '#7B6FD0',
  TRAJECTORY: '#C07A2D',
  SDK: '#2E8B8B'
}

export const MUTED = '#8A9099'

export function spanTypeColor(t) {
  return SPAN_TYPE_COLORS[t] || MUTED
}
