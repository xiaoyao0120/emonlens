/**
 * 枚举/类型中文展示 + 为后续中英文切换预留的结构。
 *
 * 约定：
 * - 展示层不要直接渲染后端枚举原文，统一用 `enumText(group, value)`；
 * - 新增枚举时在 `DICTS.zh` / `DICTS.en` 同步补齐；
 * - `CODE: detail` 形态的值（如 `LOW_QUALITY: 分数不足`）会翻译前缀并保留明细；
 * - 未收录的值原样返回，保证新增后端枚举不会显示成空白。
 */

export const LOCALES = { ZH: 'zh', EN: 'en' }

// 当前语言。后续接入「系统设置 / 用户偏好」时，只需要改这里（或换成响应式 store）。
let currentLocale = LOCALES.ZH

export function getLocale() {
  return currentLocale
}

export function setLocale(locale) {
  if (Object.values(LOCALES).includes(locale)) currentLocale = locale
}

const DICTS = {
  zh: {
    severity: { CRITICAL: '严重', HIGH: '高', MEDIUM: '中', LOW: '低' },
    incidentStatus: { OPEN: '待处理', ACKNOWLEDGED: '已确认', RESOLVED: '已恢复' },
    monitorKind: { VALIDATION: '规则校验', METRIC: '指标阈值', EVALUATION: '评估达标' },
    findingDimension: {
      OUTPUT: '输出', BEHAVIOR: '行为', SECURITY: '安全', CONTEXT: '上下文', PERFORMANCE: '性能'
    },
    findingType: {
      MONITOR_VALIDATION: '规则校验命中',
      MONITOR_TRAJECTORY: '轨迹规则命中',
      MONITOR_METRIC: '指标阈值告警',
      MONITOR_EVALUATION: '评估达标告警',
      BEHAVIOR_LOOP: '行为循环'
    },
    evalLabel: {
      PASS: '通过', FAIL: '未通过', UNKNOWN: '无法判定', 'N/A': '不适用',
      EVAL_UNAVAILABLE: '评估不可用', ERROR: '执行错误'
    },
    attemptOutcome: {
      SUCCEEDED: '成功', UNKNOWN: '无法判定', FAILED: '失败'
    },
    evaluatorType: {
      EXPRESSION: '表达式规则', REGEX: '正则断言', LLM_JUDGE: '模型评审', TRAJECTORY: '工具轨迹', SDK: 'SDK 评测器'
    },
    runStatus: {
      COMPLETED: '执行完成', PARTIAL_FAILED: '部分失败', FAILED: '执行失败',
      INTERRUPTED: '已中断', CANCELLED: '已取消', PENDING: '排队中', RUNNING: '执行中',
      ERROR: '执行错误', OK: '正常'
    },
    itemStatus: { ACTIVE: '生效', QUARANTINED: '已失效' },
    riskLevel: { HIGH: '高', MEDIUM: '中', LOW: '低', UNASSIGNED: '未评估' },
    split: { TRAIN: '训练', VALIDATION: '验证', HOLDOUT: '留出', UNASSIGNED: '未分配' },
    failureMode: {
      LATENCY: '延迟', HALLUCINATION: '幻觉', REFERENCE_MISMATCH: '参考不一致',
      UNSAFE_TOOL: '不安全工具', FORMAT: '格式错误', OTHER: '其他'
    },
    sourceType: { MANUAL: '人工录入', IMPORT: '导入', SYNTHETIC: '合成', TRACE: 'Trace 收录' },
    candidateStatus: { DRAFT: '待审核', APPROVED: '已批准', REJECTED: '已拒绝' },
    filterReason: {
      DUPLICATE: '重复', DUPLICATE_NEAR: '近似重复', LOW_QUALITY: '低质量',
      GENERATION_FAILED: '生成失败', REJECTED_BY_REVIEWER: '人工拒绝'
    },
    caseStatus: {
      OPEN: '待处理', INVESTIGATING: '调查中', FIXED: '已修复',
      CLOSED: '已关闭', REJECTED: '已否决'
    },
    gateStatus: { PASS: '通过', FAIL: '未通过', BLOCKED: '阻断' },
    driftStatus: { IN_SYNC: '基线一致', DRIFTED: '与基线漂移' },
    packStatus: { PUBLISHED: '已发布', DEPRECATED: '已弃用', DRAFT: '草稿' },
    businessStatus: { ACTIVE: '进行中', COMPLETED: '已完成', CLOSED: '已结束', DRAFT: '草稿' },
    decision: { POSITIVE: '正向', NEGATIVE: '负向', INCONCLUSIVE: '不确定', BLOCKED: '阻断' },
    rubricRunStatus: { COMPLETED: '已完成', NO_AUDIT_SET: '未配置审计集', NO_FAILURES: '无失败样本', RUNNING: '执行中', FAILED: '执行失败' },
    rubricDecision: { PROPOSED: '已提议', APPROVED: '已采纳', REJECTED: '已否决' },
    trustStatus: { TRUSTED: '可信', WATCH: '观察', INSUFFICIENT_EVIDENCE: '证据不足', AT_RISK: '有风险', CRITICAL: '严重', DEGRADED: '退化', UNTRUSTED: '不可信' },
    spanStatus: { OK: '正常', ERROR: '异常' },
    agentStatus: { ACTIVE: '在线', INACTIVE: '离线' },
    synthesisStatus: { PENDING: '排队中', RUNNING: '生成中', COMPLETED: '已完成', PARTIAL_FAILED: '部分失败', FAILED: '失败', INTERRUPTED: '已中断' },
    spanType: {
      agent: 'Agent 根', workflow: '工作流', llm: '模型调用', tool: '工具调用',
      retrieval: '检索', guardrail: '护栏', handoff: '交接', memory: '记忆',
      approval: '审批', business_action: '业务动作'
    },
    channel: { EMAIL: '邮件', WEBHOOK: 'Webhook' },
    pairwisePreference: { A: '偏好 A', B: '偏好 B', TIE: '平局', UNKNOWN: '无法判定' },
    blueprintType: { RISK: '风险配比', SPLIT: '切分配比', PURPOSE: '用途配比' },
    toolSideEffect: { NONE: '无副作用', READ: '只读', WRITE: '写入', DESTRUCTIVE: '破坏性', EXTERNAL_COMMUNICATION: '外部通信', FINANCIAL: '资金' },
    credentialStatus: { ACTIVE: '启用', REVOKED: '已吊销' },
    noEvidence: { NO_EVIDENCE: '无证据' },
    rootCause: {
      UNKNOWN: '未知', PROMPT: '提示词', MODEL: '模型', TOOL: '工具', RETRIEVAL: '检索',
      DATA: '数据', POLICY: '策略', ENVIRONMENT: '环境'
    }
  },
  en: {
    severity: { CRITICAL: 'Critical', HIGH: 'High', MEDIUM: 'Medium', LOW: 'Low' },
    incidentStatus: { OPEN: 'Open', ACKNOWLEDGED: 'Acknowledged', RESOLVED: 'Resolved' },
    monitorKind: { VALIDATION: 'Validation', METRIC: 'Metric threshold', EVALUATION: 'Evaluation' },
    findingDimension: {
      OUTPUT: 'Output', BEHAVIOR: 'Behavior', SECURITY: 'Security', CONTEXT: 'Context', PERFORMANCE: 'Performance'
    },
    findingType: {
      MONITOR_VALIDATION: 'Validation hit',
      MONITOR_TRAJECTORY: 'Trajectory rule hit',
      MONITOR_METRIC: 'Metric threshold alert',
      MONITOR_EVALUATION: 'Evaluation alert',
      BEHAVIOR_LOOP: 'Behavior loop'
    },
    evalLabel: {
      PASS: 'Pass', FAIL: 'Fail', UNKNOWN: 'Unknown', 'N/A': 'N/A',
      EVAL_UNAVAILABLE: 'Eval unavailable', ERROR: 'Error'
    },
    attemptOutcome: {
      SUCCEEDED: 'Succeeded', UNKNOWN: 'Unknown', FAILED: 'Failed'
    },
    evaluatorType: {
      EXPRESSION: 'Expression', REGEX: 'Regex', LLM_JUDGE: 'LLM judge', TRAJECTORY: 'Trajectory', SDK: 'SDK evaluator'
    },
    runStatus: {
      COMPLETED: 'Completed', PARTIAL_FAILED: 'Partial failed', FAILED: 'Failed',
      INTERRUPTED: 'Interrupted', CANCELLED: 'Cancelled', PENDING: 'Pending', RUNNING: 'Running',
      ERROR: 'Error', OK: 'OK'
    },
    itemStatus: { ACTIVE: 'Active', QUARANTINED: 'Quarantined' },
    riskLevel: { HIGH: 'High', MEDIUM: 'Medium', LOW: 'Low', UNASSIGNED: 'Unassigned' },
    split: { TRAIN: 'Train', VALIDATION: 'Validation', HOLDOUT: 'Holdout', UNASSIGNED: 'Unassigned' },
    failureMode: {
      LATENCY: 'Latency', HALLUCINATION: 'Hallucination', REFERENCE_MISMATCH: 'Reference mismatch',
      UNSAFE_TOOL: 'Unsafe tool', FORMAT: 'Format', OTHER: 'Other'
    },
    sourceType: { MANUAL: 'Manual', IMPORT: 'Import', SYNTHETIC: 'Synthetic', TRACE: 'Trace' },
    candidateStatus: { DRAFT: 'Draft', APPROVED: 'Approved', REJECTED: 'Rejected' },
    filterReason: {
      DUPLICATE: 'Duplicate', DUPLICATE_NEAR: 'Near duplicate', LOW_QUALITY: 'Low quality',
      GENERATION_FAILED: 'Generation failed', REJECTED_BY_REVIEWER: 'Rejected by reviewer'
    },
    caseStatus: {
      OPEN: 'Open', INVESTIGATING: 'Investigating', FIXED: 'Fixed',
      CLOSED: 'Closed', REJECTED: 'Rejected'
    },
    gateStatus: { PASS: 'Pass', FAIL: 'Fail', BLOCKED: 'Blocked' },
    driftStatus: { IN_SYNC: 'In sync', DRIFTED: 'Drifted' },
    packStatus: { PUBLISHED: 'Published', DEPRECATED: 'Deprecated', DRAFT: 'Draft' },
    businessStatus: { ACTIVE: 'Active', COMPLETED: 'Completed', CLOSED: 'Closed', DRAFT: 'Draft' },
    decision: { POSITIVE: 'Positive', NEGATIVE: 'Negative', INCONCLUSIVE: 'Inconclusive', BLOCKED: 'Blocked' },
    rubricRunStatus: { COMPLETED: 'Completed', NO_AUDIT_SET: 'No audit split', NO_FAILURES: 'No failures', RUNNING: 'Running', FAILED: 'Failed' },
    rubricDecision: { PROPOSED: 'Proposed', APPROVED: 'Approved', REJECTED: 'Rejected' },
    trustStatus: { TRUSTED: 'Trusted', WATCH: 'Watch', INSUFFICIENT_EVIDENCE: 'Insufficient evidence', AT_RISK: 'At risk', CRITICAL: 'Critical', DEGRADED: 'Degraded', UNTRUSTED: 'Untrusted' },
    spanStatus: { OK: 'OK', ERROR: 'Error' },
    agentStatus: { ACTIVE: 'Active', INACTIVE: 'Inactive' },
    synthesisStatus: { PENDING: 'Pending', RUNNING: 'Running', COMPLETED: 'Completed', PARTIAL_FAILED: 'Partial failed', FAILED: 'Failed', INTERRUPTED: 'Interrupted' },
    spanType: {
      agent: 'Agent root', workflow: 'Workflow', llm: 'LLM call', tool: 'Tool call',
      retrieval: 'Retrieval', guardrail: 'Guardrail', handoff: 'Handoff', memory: 'Memory',
      approval: 'Approval', business_action: 'Business action'
    },
    channel: { EMAIL: 'Email', WEBHOOK: 'Webhook' },
    pairwisePreference: { A: 'Prefer A', B: 'Prefer B', TIE: 'Tie', UNKNOWN: 'Unknown' },
    blueprintType: { RISK: 'Risk mix', SPLIT: 'Split mix', PURPOSE: 'Purpose mix' },
    toolSideEffect: { NONE: 'None', READ: 'Read', WRITE: 'Write', DESTRUCTIVE: 'Destructive', EXTERNAL_COMMUNICATION: 'External', FINANCIAL: 'Financial' },
    credentialStatus: { ACTIVE: 'Active', REVOKED: 'Revoked' },
    noEvidence: { NO_EVIDENCE: 'No evidence' },
    rootCause: {
      UNKNOWN: 'Unknown', PROMPT: 'Prompt', MODEL: 'Model', TOOL: 'Tool', RETRIEVAL: 'Retrieval',
      DATA: 'Data', POLICY: 'Policy', ENVIRONMENT: 'Environment'
    }
  }
}

/**
 * 翻译枚举值。未收录时原样返回；`CODE: detail` 形态翻译前缀并保留 detail。
 */
export function enumText(group, value) {
  if (value == null || value === '') return value == null ? '' : value
  const raw = String(value)
  const table = (DICTS[currentLocale] || DICTS.zh)[group]
  if (!table) return raw
  if (table[raw]) return table[raw]
  const sep = raw.indexOf(':')
  if (sep > 0) {
    const head = table[raw.slice(0, sep).trim()]
    if (head) return `${head}：${raw.slice(sep + 1).trim()}`
  }
  return raw
}

/** 生成下拉选项：[{ label, value }]，label 走当前语言。 */
export function enumOptions(group, values) {
  return values.map((v) => ({ label: enumText(group, v), value: v }))
}
