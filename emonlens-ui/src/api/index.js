import client from './client'

const p = (page) => ({ pageNumber: page.pageNumber || 1, pageSize: page.pageSize || 20 })

export default {
  // 认证
  login: (username, password) => client.post('/auth/login', { username, password }),
  me: () => client.get('/auth/me'),
  myWorkspaces: () => client.get('/sys/workspaces'),

  // Dashboard
  dashboardSummary: (window, agentKey, environment) => client.get('/dashboard/summary', { window, agentKey, environment }),

  // Trust 总览
  scoreOverview: () => client.get('/score/overview'),
  agentScore: (agentId, window = '24h') => client.get(`/score/agents/${agentId}`, { window }),

  // Trace
  traceSearch: (query) => client.post('/traces/search', query),
  traceDetail: (traceId) => client.get(`/traces/${traceId}`),
  observations: (query) => client.get('/observations', query),

  // Finding / Incident
  findings: (query) => client.get('/findings', query),
  incidents: (query) => client.get('/incidents', query),
  incidentDetail: (id) => client.get(`/incidents/${id}`),
  incidentAck: (id) => client.post(`/incidents/${id}/ack`),
  incidentResolve: (id) => client.post(`/incidents/${id}/resolve`),
  eventDeadList: (query) => client.get('/event-dead', query),
  eventDeadRequeue: (id) => client.post(`/event-dead/${id}/requeue`),

  // Monitor
  monitors: (page) => client.get('/monitors', p(page)),
  createMonitor: (body) => client.post('/monitors', body),
  updateMonitor: (id, body) => client.put(`/monitors/${id}`, body),
  validateMonitor: (id) => client.post(`/monitors/${id}/validate`),
  enableMonitor: (id) => client.post(`/monitors/${id}/enable`),
  disableMonitor: (id) => client.post(`/monitors/${id}/disable`),
  dryRunMonitor: (id, limit) => client.post(`/monitors/${id}/dry-run`, { limit }),
  deleteMonitor: (id) => client.delete(`/monitors/${id}`),

  // Evaluator / Evaluation
  evaluators: (page) => client.get('/evaluators', p(page)),
  evalSuites: () => client.get('/eval-suites'),
  evaluatorPresets: () => client.get('/evaluators/presets'),
  createEvalSuite: (body) => client.post('/eval-suites', body),
  createEvaluator: (body) => client.post('/evaluators', body),
  updateEvaluator: (id, body) => client.put(`/evaluators/${id}`, body),
  testEvaluator: (id, traceId) => client.post(`/evaluators/${id}/test`, { traceId }),
  evaluatorVersions: (id) => client.get(`/evaluators/${id}/versions`),
  enableEvaluator: (id) => client.post(`/evaluators/${id}/enable`),
  disableEvaluator: (id) => client.post(`/evaluators/${id}/disable`),
  deleteEvaluator: (id) => client.delete(`/evaluators/${id}`),
  evalResults: (query) => client.get('/eval-results', query),
  // R2-3：评估器质量看板（一致率 / UNKNOWN 率 / 成本 / 数据集腐烂）
  evaluatorQuality: (params) => client.get('/evaluator-quality', params),
  scanEvaluatorQuality: () => client.post('/evaluator-quality/scan', {}),
  evalStats: (query) => client.get('/eval-results/stats', query),
  evalOverview: () => client.get('/eval-results/overview'),
  runEvaluation: (body) => client.post('/evaluations/run', body),

  // Sessions
  userInsights: (query) => client.get('/users', query),
  userInsightDetail: (userId) => client.get(`/users/${encodeURIComponent(userId)}/detail`),

  // Prompts（提示词管理）
  prompts: (page) => client.get('/prompts', p(page)),
  promptVersions: (name) => client.get(`/prompts/${name}/versions`),
  promptUsage: (name) => client.get(`/prompts/${name}/usage`),
  // R1-3：从 prompt 版本反查“被哪些 run 评过、Gate 结论是什么”
  promptRuns: (name) => client.get(`/prompts/${name}/runs`),
  resolvePrompt: (name, label) => client.get(`/prompts/${name}/resolve`, { label }),
  createPrompt: (body) => client.post('/prompts', body),
  setPromptLabel: (name, body) => client.post(`/prompts/${name}/label`, body),
  deletePrompt: (name) => client.delete(`/prompts/${name}`),

  // Environments（环境）
  environments: () => client.get('/environments'),

  // Annotation Queues（注解队列）
  annotationQueues: () => client.get('/annotation-queues'),
  createAnnotationQueue: (body) => client.post('/annotation-queues', body),
  deleteAnnotationQueue: (id) => client.delete(`/annotation-queues/${id}`),
  annotationQueueDetail: (id) => client.get(`/annotation-queues/${id}`),
  addAnnotationItems: (id, traceIds) => client.post(`/annotation-queues/${id}/items`, { traceIds }),
  nextAnnotationItem: (id) => client.get(`/annotation-queues/${id}/next`),
  completeAnnotationItem: (id, itemId, body) => client.post(`/annotation-queues/${id}/items/${itemId}/complete`, body),
  assignAnnotationItem: (id, itemId, reviewer) => client.put(`/annotation-queues/${id}/items/${itemId}/assign`, { reviewer }),

  // Usage & Cost（用量与成本）
  usage: (window, agentKey, dimension) => client.get('/dashboard/usage', { window, agentKey, dimension }),

  // Playground（提示词试跑）
  playgroundRun: (body) => client.post('/playground/run', body),

  // Playground 矩阵实验（R1-2）：试跑产物，不进 Gate
  estimatePlaygroundMatrix: (body) => client.post('/playground/matrices/estimate', body),
  createPlaygroundMatrix: (body, idempotencyKey) => client.post(
    '/playground/matrices', body, { headers: { 'Idempotency-Key': idempotencyKey } }),
  playgroundMatrices: (params) => client.get('/playground/matrices', params),
  playgroundMatrix: (id) => client.get(`/playground/matrices/${id}`),
  playgroundMatrixCsv: (id) => client.getBlob(`/playground/matrices/${id}/export.csv`, {}),
  diffPlaygroundMatrix: (id, body) => client.post(`/playground/matrices/${id}/diff`, body),
  playgroundMatrixDiff: (id, diffId) => client.get(`/playground/matrices/${id}/diffs/${diffId}`),
  promotePlaygroundColumn: (id, columnKey, body) => client.post(
    `/playground/matrices/${id}/columns/${columnKey}/promote`, body || {}),

  // Model Prices（模型价格）
  modelPrices: () => client.get('/model-prices'),
  modelPriceUsage: () => client.get('/model-prices/usage'),
  createModelPrice: (body) => client.post('/model-prices', body),
  updateModelPrice: (id, body) => client.put(`/model-prices/${id}`, body),
  deleteModelPrice: (id) => client.delete(`/model-prices/${id}`),

  // Trace Tags（标签）
  distinctTags: () => client.get('/tags'),
  traceTags: (traceId) => client.get(`/traces/${traceId}/tags`),
  addTraceTags: (traceId, tags) => client.post(`/traces/${traceId}/tags`, { tags }),
  removeTraceTag: (traceId, tag) => client.delete(`/traces/${traceId}/tags/${encodeURIComponent(tag)}`),

  // Human Score（人工评分）
  humanScore: (traceId, body) => client.post(`/traces/${traceId}/scores`, body),
  traceScores: (traceId) => client.get(`/traces/${traceId}/scores`),

  // Comments（评论）
  traceComments: (traceId) => client.get(`/traces/${traceId}/comments`),
  addTraceComment: (traceId, content) => client.post(`/traces/${traceId}/comments`, { content }),

  // Sessions（会话，Langfuse 一等导航对标）
  sessionsList: (query) => client.get('/sessions', query),
  sessionDetail: (sessionId) => client.get(`/sessions/${encodeURIComponent(sessionId)}`),

  // Datasets（数据集与实验）
  datasets: () => client.get('/datasets'),
  createDataset: (body) => client.post('/datasets', body),
  deleteDataset: (id) => client.delete(`/datasets/${id}`),
  datasetDetail: (id) => client.get(`/datasets/${id}`),
  datasetRuns: (id) => client.get(`/datasets/${id}/runs`),
  datasetRunsByLabel: (id, label) => client.get(`/datasets/${id}/runs`, { label }),
  // R1-4：CI 基线标签（同一数据集内同标签唯一）
  labelDatasetRun: (id, runId, runLabel) => client.post(
    `/datasets/${id}/runs/${runId}/label`, { runLabel }),
  addDatasetItem: (id, body) => client.post(`/datasets/${id}/items`, body),
  updateDatasetItem: (id, itemId, body) => client.put(`/datasets/${id}/items/${itemId}`, body),
  importDatasetItems: (id, body) => client.post(`/datasets/${id}/items/import`, body),
  exportDatasetItems: (id, format) => client.getBlob(`/datasets/${id}/items/export`, { format }),
  createSynthesisJob: (id, body, idempotencyKey) => client.post(
    `/datasets/${id}/synthesis-jobs`, body, { headers: { 'Idempotency-Key': idempotencyKey } }),
  synthesisJobs: (id) => client.get(`/datasets/${id}/synthesis-jobs`),
  synthesisJob: (id, jobId) => client.get(`/datasets/${id}/synthesis-jobs/${jobId}`),
  synthesisCandidates: (id, jobId, status) => client.get(
    `/datasets/${id}/synthesis-jobs/${jobId}/candidates`, { status }),
  approveSynthesisCandidate: (id, jobId, candidateId) => client.post(
    `/datasets/${id}/synthesis-jobs/${jobId}/candidates/${candidateId}/approve`, {}),
  rejectSynthesisCandidate: (id, jobId, candidateId, note) => client.post(
    `/datasets/${id}/synthesis-jobs/${jobId}/candidates/${candidateId}/reject`, { note }),
  deleteDatasetItem: (id, itemId) => client.delete(`/datasets/${id}/items/${itemId}`),
  invalidateDatasetItem: (id, itemId, body) => client.post(
    `/datasets/${id}/items/${itemId}/invalidate`, body),
  restoreDatasetItem: (id, itemId) => client.post(
    `/datasets/${id}/items/${itemId}/restore`, {}),
  quarantineStaleDatasetItems: (id, body) => client.post(
    `/datasets/${id}/items/quarantine-stale`, body),
  runDatasetExperiment: (id, body) => client.post(`/datasets/${id}/runs`, body),
  runDatasetExperimentAsync: (id, body, idempotencyKey) => client.post(
    `/datasets/${id}/runs/async`, body, { headers: { 'Idempotency-Key': idempotencyKey } }),
  datasetRunDetail: (id, runId) => client.get(`/datasets/${id}/runs/${runId}`),
  datasetRunCost: (id, runId) => client.get(`/datasets/${id}/runs/${runId}/cost`),
  datasetRunQualityModel: (id, runId) => client.get(`/datasets/${id}/runs/${runId}/quality-model`),
  datasetRunAttempts: (id, runId) => client.get(`/datasets/${id}/runs/${runId}/attempts`),
  datasetRunTasks: (id, runId, params) => client.get(`/datasets/${id}/runs/${runId}/tasks`, params),
  cancelDatasetRun: (id, runId) => client.post(`/datasets/${id}/runs/${runId}/cancel`),
  resumeDatasetRun: (id, runId, idempotencyKey) => client.post(
    `/datasets/${id}/runs/${runId}/resume`, null, { headers: { 'Idempotency-Key': idempotencyKey } }),
  retryDatasetRun: (id, runId, idempotencyKey) => client.post(
    `/datasets/${id}/runs/${runId}/retry`, null, { headers: { 'Idempotency-Key': idempotencyKey } }),
  judgeCalibration: (id, runId, evaluatorKey) => client.get(
    `/datasets/${id}/runs/${runId}/judge-calibration`, { evaluatorKey }),
  reviewJudgeCalibration: (id, runId, itemId, body) => client.put(
    `/datasets/${id}/runs/${runId}/judge-calibration/${itemId}`, body),
  adjudicateJudgeCalibration: (id, runId, itemId, body) => client.put(
    `/datasets/${id}/runs/${runId}/judge-calibration/${itemId}/adjudication`, body),
  datasetHealth: (id) => client.get(`/datasets/${id}/health`),
  datasetHealthHistory: (id, params) => client.get(`/datasets/${id}/health/history`, params),
  bindDatasetDomainPack: (id, body) => client.put(`/datasets/${id}/domain-pack`, body),
  upgradeDatasetDomainPack: (id, body) => client.post(`/datasets/${id}/domain-pack/upgrade`, body),
  domainPacks: (params) => client.get('/domain-packs', params),
  domainPack: (id) => client.get(`/domain-packs/${id}`),
  domainPackVersions: (packKey) => client.get(`/domain-packs/${packKey}/versions`),
  createDomainPack: (body) => client.post('/domain-packs', body),
  deprecateDomainPack: (id) => client.post(`/domain-packs/${id}/deprecate`, {}),
  autoRubricRuns: (datasetId) => client.get(`/datasets/${datasetId}/auto-rubric-runs`),
  createAutoRubricRun: (datasetId, body) => client.post(`/datasets/${datasetId}/auto-rubric-runs`, body),
  autoRubricRun: (id) => client.get(`/auto-rubric-runs/${id}`),
  decideAutoRubricCandidate: (runId, candidateId, body) => client.post(
    `/auto-rubric-runs/${runId}/candidates/${candidateId}/decision`, body),
  businessExperiments: (params) => client.get('/business-experiments', params),
  createPairwiseEvaluation: (datasetId, body) => client.post(`/datasets/${datasetId}/pairwise-evaluations`, body),
  pairwiseEvaluations: (datasetId, params) => client.get(`/datasets/${datasetId}/pairwise-evaluations`, params),
  pairwiseEvaluationDetail: (datasetId, evaluationId) => client.get(
    `/datasets/${datasetId}/pairwise-evaluations/${evaluationId}`),
  reviewPairwise: (datasetId, evaluationId, body) => client.post(
    `/datasets/${datasetId}/pairwise-evaluations/${evaluationId}/reviews`, body),
  pairwiseCalibration: (datasetId, evaluationId) => client.get(
    `/datasets/${datasetId}/pairwise-evaluations/${evaluationId}/calibration`),
  businessExperimentDetail: (id) => client.get(`/business-experiments/${id}`),
  createBusinessExperiment: (body) => client.post('/business-experiments', body),
  importBusinessEvidence: (id, body) => client.post(`/business-experiments/${id}/evidence`, body),
  businessCredentials: () => client.get('/business-experiments/credentials'),
  createBusinessCredential: (body) => client.post('/business-experiments/credentials', body),
  revokeBusinessCredential: (id) => client.post(
    `/business-experiments/credentials/${id}/revoke`, {}),
  qualityCases: (params) => client.get('/quality-cases', params),
  createQualityCase: (body) => client.post('/quality-cases', body),
  patchQualityCase: (id, body) => client.patch(`/quality-cases/${id}`, body),
  linkQualityCaseRegression: (id, body) => client.post(`/quality-cases/${id}/regression`, body),
  closeQualityCase: (id) => client.post(`/quality-cases/${id}/close`, {}),
  updateDatasetBlueprint: (id, body) => client.put(`/datasets/${id}/blueprint`, body),
  datasetRevisions: (id) => client.get(`/datasets/${id}/revisions`),
  freezeDatasetRevision: (id, body) => client.post(`/datasets/${id}/revisions`, body || {}),
  createQualityGate: (body) => client.post('/quality-gates', body),
  qualityGate: (id) => client.get(`/quality-gates/${id}`),
  latestQualityGate: (runId) => client.get('/quality-gates', { runId }),
  verifyQualityGate: (id) => client.get(`/quality-gates/${id}/verification`),
  qualityGateHistory: (runId) => client.get('/quality-gates/history', { runId }),

  // Agent 接入
  listAgents: () => client.get('/agents'),
  deleteAgent: (key) => client.delete(`/agents/${encodeURIComponent(key)}`),
  agentHealth: () => client.get('/agents/health'),
  syncAgent: (agentKey, body) => client.put(`/agents/${agentKey}`, body),
  agentDetail: (agentKey) => client.get(`/agents/${agentKey}`),
  syncAgentTool: (agentKey, toolName, sideEffect) => client.put(`/agents/${agentKey}/tools/${toolName}`, { sideEffect }),
  ingestKeys: () => client.get('/ingest-keys'),
  issueIngestKey: (body) => client.post('/ingest-keys', body || {}),
  revokeIngestKey: (id) => client.delete(`/ingest-keys/${id}`),

  // 开放 API Token
  openTokens: () => client.get('/open-tokens'),
  issueOpenToken: (body) => client.post('/open-tokens', body),
  revokeOpenToken: (id) => client.delete(`/open-tokens/${id}`),

  // 系统管理
  sysUsers: (page) => client.get('/sys/users', p(page)),
  createSysUser: (body) => client.post('/sys/users', body),
  changeUserStatus: (id, status) => client.put(`/sys/users/${id}/status`, { status }),
  resetUserPassword: (id, password) => client.put(`/sys/users/${id}/password`, { password }),
  changeUserRole: (id, roleId) => client.put(`/sys/users/${id}/role`, { roleId }),

  // 系统运行时设置（LLM 端点等）
  getEvalLlmSetting: () => client.get('/sys/settings/eval-llm'),
  saveEvalLlmSetting: (body) => client.put('/sys/settings/eval-llm', body),
  testEvalLlmSetting: (body) => client.post('/sys/settings/eval-llm/test', body || {}),
  getEvalSamplingSetting: () => client.get('/sys/settings/eval-sampling'),
  saveEvalSamplingSetting: (body) => client.put('/sys/settings/eval-sampling', body),
  getRetentionSetting: () => client.get('/sys/settings/retention'),
  saveRetentionSetting: (body) => client.put('/sys/settings/retention', body),
  sysRoles: () => client.get('/sys/roles'),
  createRole: (body) => client.post('/sys/roles', body),
  deleteRole: (id) => client.delete(`/sys/roles/${id}`),
  updateRolePermissions: (id, permissions) => client.put(`/sys/roles/${id}/permissions`, { permissions }),
  auditLogs: (query) => client.get('/audit-logs', query),
  menus: () => client.get('/sys/menus'),

  // 通知配置
  notifySenders: () => client.get('/notify/senders'),
  createNotifySender: (body) => client.post('/notify/senders', body),
  deleteNotifySender: (id) => client.delete(`/notify/senders/${id}`),
  notifyTestSender: (id) => client.post(`/notify/senders/${id}/test`),
  notifyTemplates: () => client.get('/notify/templates'),
  createNotifyTemplate: (body) => client.post('/notify/templates', body),
  updateNotifyTemplate: (id, body) => client.put(`/notify/templates/${id}`, body),
  notifyRecords: (query) => client.get('/notify/records', query)
}
