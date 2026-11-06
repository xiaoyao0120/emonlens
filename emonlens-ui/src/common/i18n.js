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
    // 评估结果的 reasonCode：后端只保证机器可读，界面必须给出人能看懂的原因。
    // 新增 reasonCode 时在这里补一条；未收录的仍会原样显示，不会变成空白。
    evalReason: {
      JUDGE_PASS: '多数 judge 判定通过',
      JUDGE_FAIL: '多数 judge 判定不通过',
      JUDGE_DISAGREEMENT: 'judge 之间意见分歧，未达严格多数，不作硬判',
      JUDGE_MEAN_BELOW_MIN_SCORE: '多数 judge 判定通过，但平均分未达到阈值',
      INVALID_JUDGE_OUTPUT: 'judge 输出无法解析出分数',
      JUDGE_UNAVAILABLE: 'judge 调用不可用',
      EXPRESSION_ERROR: '表达式规则执行出错',
      REGEX_REJECTED: '正则断言未匹配',
      OUTPUT_MISSING: 'Trace 缺少输出，无法评估',
      INPUT_MISSING: '样本缺少输入',
      EXPECTED_TOOLS_MISSING: '未声明期望的工具调用',
      TOOLS_CALLED_MISSING: 'Trace 中没有工具调用记录',
      RETRIEVAL_CONTEXT_MISSING: 'Trace 中没有检索上下文',
      TURNS_MISSING: 'Trace 中没有可评估的轮次',
      CONTENT_NOT_CAPTURED: '正文未被采集',
      CONTENT_NOT_READABLE: '正文不可读（可能是编码或格式问题）',
      CONTENT_TRUNCATED: '正文被截断，结论按截断后的内容给出',
      TRACE_DIGEST_MISSING: '缺少 Trace 摘要',
      SNAPSHOT_UNAVAILABLE: '样本快照不可用',
      REFERENCE_MISSING: '缺少对照结果（A/B 或基线）',
      CANDIDATE_MISSING: '候选项缺失',
      CANDIDATE_INCOMPLETE: '候选项不完整',
      DETERMINISTIC_SAME: '两份输出完全一致，判定为平票',
      PREFERENCE_UP_SCORE_UP: '偏好更高的一方评分也更高，一致',
      PREFERENCE_DOWN_SCORE_DOWN: '偏好更低的一方评分也更低，一致',
      PREFERENCE_DOWN_SCORE_UP: '偏好更低的一方评分反而更高，不一致',
      PREFERENCE_UP_SCORE_DOWN: '偏好更高的一方评分反而更低，不一致',
      CLAIM_PASS: '声明校验通过',
      CLAIM_FAIL: '声明校验未通过',
      CLAIM_UNKNOWN: '声明无法验证',
      CLAIM_RATIO_BELOW_THRESHOLD: '通过比例低于阈值',
      ALL_CLAIMS_UNKNOWN: '所有声明都无法验证',
      UNKNOWN_EVALUATOR_TYPE: '未知的评估器类型',
      LEGACY_TASK_MISSING: '历史任务记录缺失',
      SOURCE_TASK_NOT_TERMINAL: '源任务尚未结束',
      SCORE_INVALID: '分数格式不合法'
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
    evalReason: {
      JUDGE_PASS: 'Majority of judges passed',
      JUDGE_FAIL: 'Majority of judges failed',
      JUDGE_DISAGREEMENT: 'Judges disagreed without a strict majority, so no verdict is forced',
      JUDGE_MEAN_BELOW_MIN_SCORE: 'Majority of judges passed, but the mean score is below the threshold',
      INVALID_JUDGE_OUTPUT: 'Judge output could not be parsed into a score',
      JUDGE_UNAVAILABLE: 'Judge call unavailable',
      EXPRESSION_ERROR: 'Expression rule failed to run',
      REGEX_REJECTED: 'Regex assertion did not match',
      OUTPUT_MISSING: 'Trace has no output to evaluate',
      INPUT_MISSING: 'Sample has no input',
      EXPECTED_TOOLS_MISSING: 'No expected tool calls declared',
      TOOLS_CALLED_MISSING: 'Trace contains no tool calls',
      RETRIEVAL_CONTEXT_MISSING: 'Trace contains no retrieval context',
      TURNS_MISSING: 'Trace has no evaluable turns',
      CONTENT_NOT_CAPTURED: 'Content was not captured',
      CONTENT_NOT_READABLE: 'Content is not readable (encoding or format)',
      CONTENT_TRUNCATED: 'Content was truncated; verdict is based on the truncated text',
      TRACE_DIGEST_MISSING: 'Trace digest missing',
      SNAPSHOT_UNAVAILABLE: 'Sample snapshot unavailable',
      REFERENCE_MISSING: 'Reference result missing',
      CANDIDATE_MISSING: 'Candidate missing',
      CANDIDATE_INCOMPLETE: 'Candidate incomplete',
      DETERMINISTIC_SAME: 'Both outputs are identical, scored as a tie',
      PREFERENCE_UP_SCORE_UP: 'Preferred side also scored higher — consistent',
      PREFERENCE_DOWN_SCORE_DOWN: 'Less preferred side also scored lower — consistent',
      PREFERENCE_DOWN_SCORE_UP: 'Less preferred side scored higher — inconsistent',
      PREFERENCE_UP_SCORE_DOWN: 'Preferred side scored lower — inconsistent',
      CLAIM_PASS: 'Claim check passed',
      CLAIM_FAIL: 'Claim check failed',
      CLAIM_UNKNOWN: 'Claim could not be verified',
      CLAIM_RATIO_BELOW_THRESHOLD: 'Verified ratio below threshold',
      ALL_CLAIMS_UNKNOWN: 'No claim could be verified',
      UNKNOWN_EVALUATOR_TYPE: 'Unknown evaluator type',
      LEGACY_TASK_MISSING: 'Legacy task record missing',
      SOURCE_TASK_NOT_TERMINAL: 'Source task has not finished',
      SCORE_INVALID: 'Score format invalid'
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
