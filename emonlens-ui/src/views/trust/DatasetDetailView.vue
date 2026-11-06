<template>
  <div class="base-page">
    <div class="page-head">
      <div class="head-left">
        <n-button size="small" quaternary @click="router.push('/datasets')">← 返回</n-button>
        <div class="page-title mono">{{ dataset?.datasetKey || '' }}</div>
      </div>
      <div class="head-right">
        <n-button size="small" :disabled="!!loadError || dataset?.contentRestricted"
                  :title="dataset?.contentRestricted ? '无内容查看权限，不能添加/编辑样本正文' : ''"
                  @click="openAddItem">＋ 添加样本</n-button>
        <n-button type="primary" size="small" :loading="creatingRun"
                  :disabled="!!loadError || dataset?.contentRestricted"
                  :title="dataset?.contentRestricted ? '无内容查看权限，不能运行实验' : ''"
                  @click="openRun">▶ 运行实验</n-button>
      </div>
    </div>

    <n-alert v-if="loadError" type="error" class="mt12" :bordered="false">
      数据集加载失败：{{ loadError }}
      <n-button text type="primary" size="small" @click="load">重试</n-button>
      <n-button text type="primary" size="small" @click="router.push('/datasets')">返回列表</n-button>
    </n-alert>

    <template v-if="!loadError">
    <n-alert v-if="activeRun" type="info" class="mt12" :bordered="false">
      运行中：{{ activeRun.evaluatorKey }} · 样本 {{ activeRun.itemCount }} · 已完成
      {{ doneCount(activeRun) }} 条。页面会自动刷新；刷新页面后可继续查看。
    </n-alert>
    <n-alert v-if="pollError" type="error" class="mt12" closable :bordered="false" @close="pollError = ''">
      {{ pollError }}
    </n-alert>
    <EvaluationFlow
      :item-count="(dataset?.items || []).length"
      :active-item-count="activeItemCount"
      :evaluator-count="evaluatorOptions.length"
      :latest-run="latestRun"
      :running-run="activeRun"
      :can-run="canOpenRun"
      :blocker="openRunBlocker"
      :creating="creatingRun"
      :calibration-status="latestCalibration?.status || ''"
      :calibration-detail="calibrationDetailText"
      :gate-decision="latestGate?.decision || ''"
      :gate-usable="latestGateUsable"
      :gate-detail="gateDetailText"
      :open-case-count="latestOpenCases"
      @manage-items="mainTab = 'data'"
      @manage-evaluators="router.push('/evaluators')"
      @run="openRun"
      @view-result="openLatestRun"
      @view-report="openLatestRun"
      @open-cases="router.push('/quality-cases')"
    />

    <div class="metric-hint mt12">
      <span>通过率 = PASS / (PASS + FAIL)</span>
      <span>覆盖率 = (PASS + FAIL) / 可用样本</span>
      <span>无法判定 / 执行错误单列</span>
      <span>Gate 只输出准入判断，不执行部署/回滚</span>
    </div>

    <n-tabs v-model:value="mainTab" type="segment" animated class="dataset-tabs tabs-segment-compact" style="--tabs-nav-width: 320px">
      <n-tab-pane name="runs"
        :tab="'实验与结果' + ((dataset?.runs || []).length ? ' (' + dataset.runs.length + ')' : '')">
      <!-- 实验历史 -->
      <n-card size="small" class="mt12" title="实验（Runs）">
        <n-data-table :columns="runColumns" :data="dataset?.runs || []" size="small" :bordered="false"
          :scroll-x="1420" :max-height="360">
          <template #empty>
            <EmptyState text="还没有实验：点右上角「运行实验」→ 选择一个或多个评估器，对样本逐条执行并保存结果。评估器需先在「评估器」页创建；运行会自动绑定数据集修订。样本来源：在「调用链路」的 Trace 详情页点「收录数据集」，把生产 trace 沉淀为回归样本。" />
          </template>
        </n-data-table>
      </n-card>

      <!-- 通过率趋势（回归是否恶化一目了然） -->
      <n-card v-if="trendBars.length" size="small" class="mt12" title="通过率与覆盖率趋势">
        <template #header-extra>
          <span class="page-sub">按时间正序 · 共 {{ trendBars.length }} 次实验 · 悬停查看详情</span>
        </template>
        <div class="trend-track">
          <div v-for="(b, i) in trendBars" :key="i" class="trend-col" :title="b.tip">
            <span class="trend-v num" :style="{ color: b.color }">{{ b.rateText }}</span>
            <div class="trend-bar-wrap">
              <div class="trend-bar" :style="{ height: Math.max(3, b.height) + '%', background: b.color }"></div>
            </div>
            <span class="trend-x num">#{{ b.idx }}</span>
          </div>
        </div>
      </n-card>

      <!-- 实验对比（按样本逐条 diff，包含未判定/缺失） -->
      <n-card v-if="compareColumns.length" size="small" class="mt12" title="实验对比（逐样本 diff）">
        <template #header-extra>
          <div class="cmp-picker">
            <n-select v-model:value="compareA" size="tiny" style="width: 260px" :options="runPickOptions" />
            <span class="page-sub">vs</span>
            <n-select v-model:value="compareB" size="tiny" style="width: 260px" :options="runPickOptions" />
            <n-button size="tiny" type="primary" :loading="pairwiseLoading"
              :disabled="comparability?.level === 'error'"
              :title="comparability?.level === 'error' ? comparability.message : '对两次 run 做双向成对偏好比较'"
              @click="openPairwiseOptions">
              成对偏好
            </n-button>
          </div>
        </template>
        <n-alert v-if="comparability" class="mb10" :type="comparability.level" :bordered="false">
          {{ comparability.message }}
        </n-alert>
        <div v-if="diffStats" class="diff-summary">
          <span>改善 <b class="num" style="color:var(--success)">{{ diffStats.improved }}</b></span>
          <span>退化 <b class="num" style="color:var(--error)">{{ diffStats.regressed }}</b></span>
          <span>持平 <b class="num">{{ diffStats.same }}</b></span>
          <span>未判定 <b class="num" style="color:var(--warn)">{{ diffStats.undetermined }}</b></span>
          <span>仅一侧/缺失 <b class="num">{{ diffStats.missing }}</b></span>
          <span class="page-sub">A=较早一次，B=较新一次；未判定与缺失样本保留显示，不隐藏</span>
        </div>
        <n-alert v-if="pairwise" type="default" :bordered="false" class="mb10">
          成对偏好（Pairwise）：A 胜 <b class="num">{{ pairwise.aWins }}</b> ·
          B 胜 <b class="num">{{ pairwise.bWins }}</b> ·
          TIE <b class="num">{{ pairwise.ties }}</b> ·
          无法判定 <b class="num">{{ pairwise.unknownCount }}</b> ·
          偏好率 <b>{{ pairwise.preferenceRate == null ? '—' : pairwise.preferenceRate + '%' }}</b> ·
          人工一致率 <b>{{ pairwise.calibration?.humanAgreementRate == null ? '—' : pairwise.calibration.humanAgreementRate + '%' }}</b>
          <n-button size="tiny" style="margin-left:8px" @click="openHumanReview">人工校准</n-button>
          <span class="page-sub">只证明相对优劣，不证明绝对合格；双向映射后冲突记 UNKNOWN。</span>
        </n-alert>
        <n-data-table :columns="compareColumns" :data="compareRows" size="small" :bordered="false"
          :max-height="360" :scroll-x="860" />
      </n-card>

      <!-- 成对比较历史：后端已持久化，这里补上回看入口 -->
      <n-card size="small" class="mt12" title="成对比较历史">
        <template #header-extra>
          <n-button size="tiny" :loading="pairwiseHistoryLoading" @click="loadPairwiseHistory">刷新</n-button>
        </template>
        <n-data-table :columns="pairwiseHistoryColumns" :data="pairwiseHistory" size="small"
          :bordered="false" :loading="pairwiseHistoryLoading" :row-key="(r) => r.id"
          :max-height="260" :scroll-x="900"
          :row-props="(r) => ({ style: 'cursor:pointer', onClick: () => openPairwiseDetail(r) })" />
        <div v-if="!pairwiseHistoryLoading && !pairwiseHistory.length" class="page-sub">
          还没有成对比较。选择两次可比较的 run，点上方「成对偏好」；结果会保存在这里。
        </div>
      </n-card>

      </n-tab-pane>
      <n-tab-pane name="data"
        :tab="'样本与设置' + ((dataset?.items || []).length ? ' (' + dataset.items.length + ')' : '')">
      <!-- 数据集蓝图 / 保鲜 / 漂移 -->
      <n-card size="small" class="mt12" title="数据集蓝图与保鲜">
        <template #header-extra>
          <n-space align="center" :size="8">
            <n-tag v-if="health" size="tiny" :bordered="false" :type="healthStatusType(health.status)">
              {{ healthStatusText(health.status) }}
            </n-tag>
            <n-button size="tiny" @click="openBindPack">绑定领域包</n-button>
            <n-popconfirm v-if="health?.staleItemCount > 0" @positive-click="quarantineStale">
              <template #trigger>
                <n-button size="tiny" type="warning" :loading="staleQuarantining">
                  软失效过期样本（{{ health.staleItemCount }}）
                </n-button>
              </template>
              按 maxItemAgeDays 把这些样本标为 QUARANTINED；不物理删除，可恢复。历史 run/revision 不受影响。
            </n-popconfirm>
            <n-popconfirm v-if="health?.domainPackId" @positive-click="upgradePack">
              <template #trigger>
                <n-button size="tiny" :loading="packUpgrading">升级领域包</n-button>
              </template>
              将升级到同一 packKey 的最新 PUBLISHED 版本；历史 run 仍保留旧版本。
            </n-popconfirm>
            <n-button size="tiny" @click="openBlueprint">配置蓝图</n-button>
          </n-space>
        </template>
        <div v-if="!health" class="page-sub">
          {{ healthError ? '健康数据加载失败：' + healthError : (healthLoading ? '加载中…' : '健康数据不可用') }}
          <n-button v-if="healthError" text type="primary" size="tiny" @click="loadHealth">重试</n-button>
        </div>
        <template v-else>
          <div class="run-summary sub">
            <span>样本 <b class="num">{{ health.itemCount }}</b></span>
            <span v-if="health.maxItemAgeDays">过期样本
              <b class="num" :style="{ color: health.staleItemCount ? 'var(--warn)' : 'var(--success)' }">
                {{ health.staleItemCount || 0 }}
              </b>
              <span class="page-sub">/ 上限 {{ health.maxItemAgeDays }} 天</span>
            </span>
            <span>蓝图覆盖 <b>{{ fmtPercent(health.requirementCoverage) }}</b></span>
            <span>蓝图来源 <b>{{ health.blueprintSource === 'DOMAIN_PACK' ? '领域包' : health.blueprintSource === 'DATASET' ? '数据集' : '未配置' }}</b></span>
            <span v-if="health.domainPackVersion">领域包
              <b>{{ health.domainPackVersion }}</b>
              <span v-if="health.domainPackStatus === 'DEPRECATED'" style="color:var(--warn)">（已弃用）</span>
            </span>
            <span>最近修订
              <b>{{ health.latestRevision ? 'v' + health.latestRevision.revision : '未冻结' }}</b>
            </span>
            <span>漂移 <b :style="{ color: health.drift.status === 'IN_SYNC' ? 'var(--success)' : 'var(--warn)' }">
              {{ enumText('driftStatus', health.drift.status) }}</b></span>
            <span>保鲜 <b :style="{ color: health.stale ? 'var(--error)' : 'var(--success)' }">
              {{ health.stale ? health.staleDays + ' 天' : '正常' }}</b></span>
          </div>
          <div v-if="(health.requirements || []).length" class="gate-checks" style="margin-top:8px">
            <span v-for="r in health.requirements" :key="r.label" class="gate-check"
              :class="r.met ? 'gate-pass' : 'gate-blocked'" :title="r.label">
              {{ enumText('blueprintType', r.type) }}:{{ r.key }} {{ r.actual }}/{{ r.required }}
            </span>
          </div>
          <n-alert v-if="(health.warnings || []).length" type="warning" :bordered="false"
            class="mt12" style="margin-bottom:0">
            {{ health.warnings.join('；') }}
          </n-alert>
          <div v-if="!health.blueprintConfigured" class="page-sub" style="margin-top:8px">
            蓝图未配置：可以声明最小样本量、风险/切分/用途最低配比和最大保鲜天数。
          </div>
          <div v-if="healthHistory.length" class="page-sub" style="margin-top:8px">
            最近保鲜快照：
            <span v-for="h in healthHistory" :key="h.id" style="margin-right:12px">
              {{ fmtTs(h.createdAt) }} · {{ healthStatusText(h.status) }} · 覆盖 {{ fmtPercent(h.requirementCoverage) }}
              · {{ h.stale ? h.staleDays + ' 天未刷新' : '未过期' }}
            </span>
          </div>
        </template>
      </n-card>

      <!-- 样本表 -->
      <n-card size="small" class="mt12" title="样本（Items）">
        <template #header-extra>
          <div style="display:flex;gap:6px;align-items:center">
            <n-button size="tiny" :disabled="dataset?.contentRestricted"
                      :title="dataset?.contentRestricted ? '无内容查看权限，不能导入正文' : ''"
                      @click="openImport">导入</n-button>
            <n-button size="tiny" :disabled="dataset?.contentRestricted"
                      :title="dataset?.contentRestricted ? '无内容查看权限，不能生成/批准候选' : ''"
                      @click="openSynthesis">合成</n-button>
            <n-button size="tiny" @click="exportItems('jsonl')">导出 JSONL</n-button>
            <n-button size="tiny" @click="exportItems('csv')">导出 CSV</n-button>
          </div>
        </template>
        <n-alert v-if="dataset?.contentRestricted" type="warning" :bordered="false" class="mb10">
          当前账号无内容查看权限：样本正文、编辑、导入、合成与候选批准不可用；导出只含标签、状态和 hash。
        </n-alert>
        <n-data-table :columns="itemColumns" :data="dataset?.items || []" size="small" :bordered="false"
          :loading="loading" :scroll-x="1120" :max-height="420" />
      </n-card>

      <!-- 数据集修订：run 绑定不可变 revision，样本后续编辑不影响旧报告 -->
      <n-card size="small" class="mt12" title="数据集修订（Revisions）">
        <template #header-extra>
          <n-button size="tiny" :loading="freezingRevision" @click="freezeRevision">冻结当前样本</n-button>
        </template>
        <div v-if="!(dataset?.revisions || []).length" class="page-sub">
          还没有修订。运行实验会自动冻结当前样本；也可以手动冻结，作为基线或候选对比的固定样本集。
        </div>
        <n-data-table v-else :columns="revisionColumns" :data="dataset?.revisions || []" size="small"
          :bordered="false" :max-height="220" />
      </n-card>

      </n-tab-pane>
    </n-tabs>

    <Modal v-model:show="showPairwiseOptions" :width="560" title="成对比较设置">
      <n-form label-placement="top">
        <n-form-item label="评审模型（1-3 个，逗号分隔；留空使用默认模型）">
          <n-input v-model:value="pairwiseForm.judgeModelsText"
            placeholder="例如：qwen-max,gpt-4o-mini" />
        </n-form-item>
        <div class="form-grid">
          <n-form-item label="每方向采样次数（1-3）">
            <n-input-number v-model:value="pairwiseForm.samplesPerDirection" :min="1" :max="3" style="width:100%" />
          </n-form-item>
          <n-form-item label="随机化 A/B 位置">
            <n-switch v-model:value="pairwiseForm.positionRandomized" />
          </n-form-item>
        </div>
        <n-form-item v-if="pairwiseForm.positionRandomized" label="位置随机种子（同 seed 可复现）">
          <n-input-number v-model:value="pairwiseForm.positionSeed" :min="1" style="width:100%"
            placeholder="留空则使用随机 seed" />
        </n-form-item>
      </n-form>
      <div class="page-sub">多模型逐模型双向判定后多数投票；模型分裂或方向冲突记 UNKNOWN，不把平票当偏好。</div>
      <template #footer>
        <n-button size="small" :disabled="pairwiseLoading" @click="showPairwiseOptions = false">取消</n-button>
        <n-button type="primary" size="small" :loading="pairwiseLoading" @click="createPairwise">开始比较</n-button>
      </template>
    </Modal>

    <Modal v-model:show="showHumanReview" :width="760" title="Pairwise 人工校准">
      <template v-if="pairwise">
        <div class="page-sub" style="margin-bottom:8px">
          逐样本记录人工偏好；同一评审对同一 item 覆盖更新。人工标签不改模型结果，只计算一致率。
        </div>
        <div v-for="item in pairwise.items || []" :key="item.itemId" class="human-row">
          <span class="mono">{{ item.itemId.slice(0, 10) }}…</span>
          <n-tag size="tiny" :bordered="false">{{ item.preference ? enumText('pairwisePreference', item.preference) : '—' }}</n-tag>
          <n-select v-model:value="humanDrafts[item.itemId]" size="tiny" clearable
            placeholder="人工偏好" :options="humanOptions" style="width: 130px" />
          <n-input v-model:value="humanNotes[item.itemId]" size="tiny"
            placeholder="备注（可选）" style="flex:1" />
        </div>
      </template>
      <template #footer>
        <n-button size="small" :disabled="humanSaving" @click="showHumanReview = false">取消</n-button>
        <n-button type="primary" size="small" :loading="humanSaving" @click="saveHumanReviews">保存</n-button>
      </template>
    </Modal>

    <Modal v-model:show="showPairwiseDetail" :width="820" title="成对比较详情">
      <template v-if="pairwiseDetail">
        <div class="run-summary sub" style="margin-bottom:10px">
          <span>协议 <b>{{ pairwiseDetail.protocolVersion || pairwiseDetail.protocol }}</b></span>
          <span>A 胜 <b class="num">{{ pairwiseDetail.aWins }}</b></span>
          <span>B 胜 <b class="num">{{ pairwiseDetail.bWins }}</b></span>
          <span>TIE <b class="num">{{ pairwiseDetail.ties }}</b></span>
          <span>无法判定 <b class="num">{{ pairwiseDetail.unknownCount }}</b></span>
          <span>偏好率 <b>{{ pairwiseDetail.preferenceRate == null ? '—' : pairwiseDetail.preferenceRate + '%' }}</b></span>
          <span>评审模型 <b>{{ (pairwiseDetail.judgeModels || [pairwiseDetail.judgeModel]).filter(Boolean).join(', ') || '默认' }}</b></span>
        </div>
        <n-data-table :columns="pairwiseItemColumns" :data="pairwiseDetail.items || []" size="small"
          :bordered="false" :max-height="420" :scroll-x="620" />
      </template>
    </Modal>

    <Modal v-model:show="showCredentials" :width="720" title="业务证据签名凭据">
      <div class="page-sub" style="margin-bottom:8px">
        RSA 公钥（Base64 X.509 DER，至少 2048 位）。实验开启 requireSignature 后，导入证据必须带 keyId/signature/signedAt/nonce。
      </div>
      <n-data-table :columns="credentialColumns" :data="credentials" size="small"
        :bordered="false" :row-key="(r) => r.id" :max-height="220" />
      <n-divider />
      <n-form label-placement="top">
        <div class="form-grid">
          <n-form-item label="Key ID"><n-input v-model:value="credentialForm.keyId" class="mono" /></n-form-item>
          <n-form-item label="Source"><n-input v-model:value="credentialForm.source" placeholder="如 growth-platform" /></n-form-item>
        </div>
        <n-form-item label="名称（可选）"><n-input v-model:value="credentialForm.name" /></n-form-item>
        <n-form-item label="RSA 公钥（Base64 DER）">
          <n-input v-model:value="credentialForm.publicKey" type="textarea" :rows="4" class="mono" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-button size="small" :disabled="savingCredential" @click="showCredentials = false">关闭</n-button>
        <n-button type="primary" size="small" :loading="savingCredential" @click="createCredential">登记凭据</n-button>
      </template>
    </Modal>

    <!-- 登记质量案例 -->
    <Modal v-model:show="showCaseCreate" :width="600" title="登记质量案例">
      <n-form label-placement="top">
        <n-form-item label="标题"><n-input v-model:value="caseForm.title" /></n-form-item>
        <n-form-item label="现象"><n-input v-model:value="caseForm.phenomenon" type="textarea" :rows="3" /></n-form-item>
        <div class="form-grid">
          <n-form-item label="根因分类">
            <n-select v-model:value="caseForm.rootCauseCategory" :options="rootCauseOptions" />
          </n-form-item>
          <n-form-item label="Owner"><n-input v-model:value="caseForm.owner" /></n-form-item>
        </div>
      </n-form>
      <template #footer>
        <n-button size="small" :disabled="caseSaving" @click="showCaseCreate = false">取消</n-button>
        <n-button type="primary" size="small" :loading="caseSaving" @click="createCase">登记</n-button>
      </template>
    </Modal>

    <!-- 标记修复 -->
    <Modal v-model:show="showCaseFix" :width="560" title="标记修复">
      <n-form label-placement="top">
        <n-form-item label="修复指纹 fixFingerprint">
          <n-input v-model:value="caseForm.fixFingerprint" placeholder="如 prompt-v2 / commit-sha" />
        </n-form-item>
        <n-form-item label="修复说明">
          <n-input v-model:value="caseForm.fixNote" type="textarea" :rows="3" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-button size="small" :disabled="caseSaving" @click="showCaseFix = false">取消</n-button>
        <n-button type="primary" size="small" :loading="caseSaving" @click="markCaseFixed">保存</n-button>
      </template>
    </Modal>

    <!-- 关联回归并关闭 -->
    <Modal v-model:show="showCaseClose" :width="600" title="关联回归 run 并关闭">
      <n-form label-placement="top">
        <n-form-item label="回归 run ID">
          <n-input v-model:value="caseForm.regressionRunId" placeholder="必须是已完成且有可用通过 Gate 的 run" class="mono" />
        </n-form-item>
      </n-form>
      <div class="page-sub">关闭会校验回归 run 的最新 Gate 为 PASS 且 verification.usable=true；不会自动部署或回滚。</div>
      <template #footer>
        <n-button size="small" :disabled="caseSaving" @click="showCaseClose = false">取消</n-button>
        <n-button type="primary" size="small" :loading="caseSaving" @click="closeCase">关联并关闭</n-button>
      </template>
    </Modal>

    <!-- 绑定业务实验 -->
    <Modal v-model:show="showBusinessCreate" :width="620" title="绑定外部业务实验">
      <n-form label-placement="top">
        <div class="form-grid">
          <n-form-item label="来源 source">
            <n-input v-model:value="businessForm.source" placeholder="如 clickhouse / growth-platform" />
          </n-form-item>
          <n-form-item label="外部实验 ID">
            <n-input v-model:value="businessForm.externalExperimentId" placeholder="exp-2026-10-001" />
          </n-form-item>
          <n-form-item label="设计类型">
            <n-select v-model:value="businessForm.designType" :options="[
              { label: 'RANDOMIZED（随机实验）', value: 'RANDOMIZED' },
              { label: 'OBSERVATIONAL（观测）', value: 'OBSERVATIONAL' }]" />
          </n-form-item>
          <n-form-item label="主指标">
            <n-input v-model:value="businessForm.primaryMetric" placeholder="如 conversion" />
          </n-form-item>
          <n-form-item label="最小样本量">
            <n-input v-model:value="businessForm.minSampleSize" placeholder="100" />
          </n-form-item>
          <n-form-item label="目标">
            <n-input v-model:value="businessForm.target" placeholder="如 提升下单转化" />
          </n-form-item>
        </div>
      </n-form>
      <template #footer>
        <n-button size="small" :disabled="savingBusiness" @click="showBusinessCreate = false">取消</n-button>
        <n-button type="primary" size="small" :loading="savingBusiness" @click="createBusiness">绑定</n-button>
      </template>
    </Modal>

    <!-- 导入业务证据 -->
    <Modal v-model:show="showBusinessEvidence" :width="680" title="导入业务实验报告版本">
      <n-form label-placement="top">
        <n-form-item label="报告版本 reportVersion">
          <n-input v-model:value="businessEvidenceForm.reportVersion" placeholder="v1 / 2026-10-20-final" />
        </n-form-item>
        <n-form-item label="metrics JSON">
          <n-input v-model:value="businessEvidenceForm.metrics" type="textarea" :rows="6" class="mono" />
          <div class="page-sub">可含 sampleSize、controlSize、treatmentSize、pValue、ciLower、ciUpper、relativeLift、srmPValue。</div>
        </n-form-item>
        <n-form-item label="qualityChecks JSON（可选）">
          <n-input v-model:value="businessEvidenceForm.qualityChecks" type="textarea" :rows="3" class="mono" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-button size="small" :disabled="savingBusiness" @click="showBusinessEvidence = false">取消</n-button>
        <n-button type="primary" size="small" :loading="savingBusiness" @click="importBusinessEvidence">导入</n-button>
      </template>
    </Modal>

    <!-- 绑定领域包 -->
    <Modal v-model:show="showBindPack" :width="520" title="绑定领域包">
      <n-form label-placement="top">
        <n-form-item label="领域包版本">
          <n-select v-model:value="bindPackId" :options="packOptions" clearable
            placeholder="留空表示解绑；数据集蓝图优先于 pack 蓝图" />
        </n-form-item>
      </n-form>
      <div class="page-sub">绑定不会覆盖已有数据集蓝图；数据集未配置蓝图时才使用 pack 的 datasetBlueprint。</div>
      <template #footer>
        <n-button size="small" :disabled="bindSaving" @click="showBindPack = false">取消</n-button>
        <n-button type="primary" size="small" :loading="bindSaving" @click="bindPack">保存</n-button>
      </template>
    </Modal>

    <!-- 配置蓝图 -->
    <Modal v-model:show="showBlueprint" :width="620" title="配置数据蓝图">
      <div class="page-sub" style="margin-bottom:8px">
        JSON 字段：minItems、riskMin、splitMin、purposeMin、maxStaleDays、maxItemAgeDays、autoQuarantineStale。保存空对象可清除。
        maxItemAgeDays 只做预检与显式软失效；autoQuarantineStale=true 时每日保鲜任务才会自动软失效，不物理删除样本。
      </div>
      <n-input v-model:value="blueprintText" type="textarea" :rows="10" class="mono" />
      <template #footer>
        <n-button size="small" :disabled="savingBlueprint" @click="showBlueprint = false">取消</n-button>
        <n-button type="primary" size="small" :loading="savingBlueprint" @click="saveBlueprint">保存</n-button>
      </template>
    </Modal>

    <!-- 添加/编辑样本 -->
    <Modal v-model:show="showAddItem" :width="620" :title="editingItemId ? '编辑样本' : '添加样本'">
      <n-form label-placement="top">
        <n-form-item label="关联 Trace ID（可选，TRACE 模式按此 trace 评估）">
          <n-input v-model:value="itemForm.traceId" placeholder="如 trace-8f3a2b" class="mono" />
        </n-form-item>
        <n-form-item label="输入（Input）">
          <n-input v-model:value="itemForm.input" type="textarea" :rows="3" />
        </n-form-item>
        <n-form-item label="期望输出（Expected Output）">
          <n-input v-model:value="itemForm.expectedOutput" type="textarea" :rows="3" />
          <div class="page-sub">参考答案只进入评估器，不会传给 REPLAY 的生成请求。精确匹配仅统一换行、去首尾空白，不适用于同义答案。</div>
        </n-form-item>
        <div class="form-grid">
          <n-form-item label="用途标签">
            <n-input v-model:value="itemForm.purposeTags" placeholder="happy,boundary,regression" />
          </n-form-item>
          <n-form-item label="风险级别">
            <n-select v-model:value="itemForm.riskLevel" :options="riskOptions" />
          </n-form-item>
          <n-form-item label="数据划分">
            <n-select v-model:value="itemForm.split" :options="splitOptions" />
          </n-form-item>
          <n-form-item label="分组键">
            <n-input v-model:value="itemForm.groupKey" placeholder="如 refund-order" />
          </n-form-item>
        </div>
        <n-form-item label="要防止的失败模式（可选）">
          <n-input v-model:value="itemForm.failureMode" placeholder="如 reference_mismatch / unsafe_tool" />
        </n-form-item>
        <n-collapse>
          <n-collapse-item title="高级：检索 / 工具 / 多轮 / 期望（v2）" name="case-v2">
            <div class="page-sub mb8">
              填写任意一项会自动使用 schemaVersion=2；留空保持旧样本行为。
              这些字段用于 RAG、Agent 轨迹和多轮评测；TRACE 模式会自动从 span 投影实际工具与检索证据。
            </div>
            <n-form-item label="检索上下文（JSON 数组）">
              <n-input v-model:value="itemForm.retrievalContextText" type="textarea" :rows="3"
                       class="mono" placeholder='[{"id":"doc-1","content":"...","score":0.9,"source":"kb/x.md"}]' />
            </n-form-item>
            <n-form-item label="期望工具（JSON 数组）">
              <n-input v-model:value="itemForm.expectedToolsText" type="textarea" :rows="3"
                       class="mono" placeholder='[{"name":"query_order","type":"FUNCTION","inputParameters":{"orderId":"123"},"required":true}]' />
            </n-form-item>
            <n-form-item label="多轮会话（JSON 数组）">
              <n-input v-model:value="itemForm.turnsText" type="textarea" :rows="3"
                       class="mono" placeholder='[{"role":"user","content":"..."},{"role":"assistant","content":"..."}]' />
            </n-form-item>
            <n-form-item label="样本级期望（JSON 对象）">
              <n-input v-model:value="itemForm.expectationsText" type="textarea" :rows="3"
                       class="mono" placeholder='{"must":["回答必须引用订单状态"],"mustNot":["不得承诺退款"],"unknownPolicy":"BLOCK"}' />
            </n-form-item>
          </n-collapse-item>
        </n-collapse>
      </n-form>
      <template #footer>
        <n-button size="small" :disabled="saving" @click="showAddItem = false">取消</n-button>
        <n-button type="primary" size="small" :loading="saving" @click="doAddItem">
          {{ editingItemId ? '保存' : '添加' }}
        </n-button>
      </template>
    </Modal>

    <!-- 批量导入 -->
    <Modal v-model:show="showImport" :width="700" title="批量导入样本（CSV / JSONL）">
      <n-form label-placement="top">
        <div class="cfg-two">
          <n-form-item label="格式">
            <n-select v-model:value="importForm.format" :options="[
              { label: 'JSONL（每行一个样本对象）', value: 'jsonl' },
              { label: 'CSV（首行表头）', value: 'csv' }
            ]" />
          </n-form-item>
          <n-form-item label="模式">
            <div style="display:flex;gap:14px;align-items:center">
              <n-checkbox v-model:checked="importForm.dryRun">仅校验（dry-run）</n-checkbox>
              <n-checkbox v-model:checked="importForm.atomic">atomic（全成或全败）</n-checkbox>
            </div>
          </n-form-item>
        </div>
        <n-form-item label="内容">
          <n-input v-model:value="importForm.content" type="textarea" :rows="12" class="mono"
                   :placeholder="importPlaceholder" />
        </n-form-item>
      </n-form>
      <n-alert v-if="importResult" :type="importResult.invalid ? 'warning' : 'success'"
               :bordered="false" class="mb10">
        共 {{ importResult.total }} 行：{{ importResult.dryRun ? '可新建' : '新建' }}
        {{ importResult.dryRun ? (importResult.wouldCreate ?? importResult.created) : importResult.created }}，
        重复 {{ importResult.duplicated }}，非法 {{ importResult.invalid }}<span v-if="importResult.dryRun">（dry-run，未写库）</span>
        <div v-for="err in (importResult.errors || []).slice(0, 20)" :key="err.row" class="mono"
             style="font-size:11.5px;margin-top:4px">
          第 {{ err.row }} 行：{{ err.message }}
        </div>
      </n-alert>
      <template #footer>
        <n-button size="small" :disabled="importLoading" @click="showImport = false">关闭</n-button>
        <n-button type="primary" size="small" :loading="importLoading" @click="doImport">开始导入</n-button>
      </template>
    </Modal>

    <!-- 合成样本 -->
    <Modal v-model:show="showSynthesis" :width="720" title="合成样本（LLM 生成 → 人工批准）"
           :on-after-leave="stopSynthesisPoll">
      <template v-if="!synthesisJob">
        <n-form label-placement="top">
          <div class="cfg-two">
            <n-form-item label="来源">
              <n-select v-model:value="synthesisForm.sourceType" :options="[
                { label: 'SCRATCH（无来源，纯生成）', value: 'SCRATCH' },
                { label: 'DATASET_ITEMS（从现有样本进化）', value: 'DATASET_ITEMS' },
                { label: 'DOCUMENTS（从上传文本生成）', value: 'DOCUMENTS' },
                { label: 'TRACE_CLUSTER（从 Trace 生成）', value: 'TRACE_CLUSTER' }
              ]" />
            </n-form-item>
            <n-form-item label="生成数量">
              <n-input-number v-model:value="synthesisForm.maxCandidates" :min="1" :max="50" style="width: 140px" />
            </n-form-item>
          </div>
          <div class="cfg-two">
            <n-form-item label="最低质量分（0-1）">
              <n-input-number v-model:value="synthesisForm.minQuality" :min="0" :max="1" :step="0.05" style="width: 160px" />
            </n-form-item>
            <n-form-item label="critic 重试次数">
              <n-input-number v-model:value="synthesisForm.maxRetries" :min="0" :max="3" style="width: 140px" />
            </n-form-item>
          </div>
          <n-form-item label="场景 / 任务描述（可选）">
            <n-input v-model:value="synthesisForm.scenario" type="textarea" :rows="2"
                     placeholder="如：售后客服场景，涉及退款、物流、换货" />
          </n-form-item>
          <n-form-item v-if="synthesisForm.sourceType === 'DOCUMENTS'"
                       label="文档（JSON 数组：name + content）">
            <n-input v-model:value="synthesisForm.documentsText" type="textarea" :rows="4" class="mono"
                     placeholder='[{"name":"refund.md","content":"退款政策……"}]' />
          </n-form-item>
          <n-form-item v-if="synthesisForm.sourceType === 'TRACE_CLUSTER'"
                       label="Trace ID（逗号或换行分隔）">
            <n-input v-model:value="synthesisForm.traceIdsText" type="textarea" :rows="3" class="mono"
                     placeholder="trace-abc123, trace-def456" />
          </n-form-item>
        </n-form>
        <div class="page-sub">
          生成结果先进入候选列表（DRAFT），需要人工逐条批准后才进入数据集；
          低质量候选自动标 REJECTED。需要系统设置 → LLM 端点已配置。
        </div>
      </template>
      <template v-else>
        <div class="mb10">
          <n-tag :type="synthesisStatusType" size="small" bordered="false">{{ enumText('synthesisStatus', synthesisJob.status) }}</n-tag>
          <span class="page-sub" style="margin-left:8px">
            候选 {{ synthesisJob.totalCandidates ?? 0 }} · 批准 {{ synthesisJob.acceptedCount ?? 0 }} · 拒绝 {{ synthesisJob.rejectedCount ?? 0 }}
          </span>
        </div>
        <n-alert v-if="synthesisJob.status === 'RUNNING' || synthesisJob.status === 'PENDING'"
                 type="info" :bordered="false" class="mb10">
          正在生成候选… 页面会自动刷新。
        </n-alert>
        <n-alert v-if="synthesisJob.error" type="error" :bordered="false" class="mb10">
          {{ synthesisJob.error }}
        </n-alert>
        <n-data-table :columns="synthesisCandidateColumns" :data="synthesisCandidates"
                      size="small" :bordered="false" :max-height="360" />
      </template>
      <template #footer>
        <n-button v-if="synthesisJob" size="small" :disabled="synthesisLoading"
                  @click="newSynthesis">新建任务</n-button>
        <n-button size="small" :disabled="synthesisLoading" @click="showSynthesis = false">关闭</n-button>
        <n-button v-if="!synthesisJob" type="primary" size="small" :loading="synthesisLoading"
                  @click="doCreateSynthesis">开始生成</n-button>
        <n-button v-else size="small" :loading="synthesisLoading" @click="refreshSynthesis">刷新</n-button>
      </template>
    </Modal>

    <!-- 运行实验 -->
    <Modal v-model:show="showRun" :width="600" title="运行实验">
      <div class="run-modal-head">
        <div class="run-modal-count">
          <b>{{ eligibleCount }}</b> 条可用样本 <span class="run-x">×</span>
          已选 <b>{{ selectedEvaluatorCount }}</b> 个评估器
        </div>
        <div class="page-sub">提交后后台执行；同一时间只允许一个运行。</div>
      </div>
      <n-form label-placement="top">
        <n-form-item label="评测方式" required>
          <n-radio-group v-model:value="runForm.mode">
            <n-radio value="TRACE">评估已有 Trace</n-radio>
            <n-radio value="REPLAY">重放（调用 LLM）</n-radio>
          </n-radio-group>
          <div class="mode-note">
            {{ runForm.mode === 'REPLAY'
              ? '对每条样本重新调用模型生成候选，再交给评估器判定；会产生外部调用费用。'
              : '读取已采集的 Trace 内容做判定，不调用模型；没有 Trace 的样本会被跳过。' }}
          </div>
        </n-form-item>

        <n-form-item v-if="suiteOptions.length" label="评估套件（可选）">
          <n-select v-model:value="runForm.suiteId" :options="suiteOptions" clearable
            placeholder="不使用套件，直接选择评估器" @update:value="onSuiteChange" />
          <div v-if="selectedSuite" class="page-sub">
            已冻结：{{ (selectedSuite.evaluatorKeys || []).join(' + ') }}；使用该版本，不读取评估器当前定义。
          </div>
        </n-form-item>

        <n-form-item label="评估器" :required="!runForm.suiteId">
          <n-select v-model:value="runForm.evaluatorIds" :options="evaluatorOptions" multiple
            :disabled="!!runForm.suiteId" placeholder="选择 1–5 个评估器" />
          <div class="page-sub">
            可多选，最多 5 个。还没有合适的评估器？
            <n-button text type="primary" size="tiny" @click="router.push('/evaluators')">去创建/管理</n-button>
          </div>
        </n-form-item>

        <n-form-item label="候选标识（可选）">
          <n-input v-model:value="runForm.candidateLabel" placeholder="如 prompt-v3 / gpt-4o-2026-08" />
          <div class="page-sub">仅用于结果与对比展示，不参与判定。</div>
        </n-form-item>

        <n-collapse class="run-advanced">
          <n-collapse-item title="预算、复核与样本修订（可选）" name="advanced">
            <n-form-item label="数据集修订">
              <n-select v-model:value="runForm.revisionId" :options="revisionOptions" />
              <div class="page-sub">留空自动冻结当前样本；选择已有修订可保证两次 run 使用同一批样本。</div>
            </n-form-item>
            <div class="form-grid">
              <n-form-item label="外部调用上限">
                <n-input-number v-model:value="runForm.maxExternalCalls" :min="1" :max="10000"
                  clearable placeholder="不填则不限制" style="width:100%" />
              </n-form-item>
              <n-form-item label="Token 上限">
                <n-input-number v-model:value="runForm.maxTokens" :min="1" :max="1000000000"
                  clearable placeholder="不填则不限制" style="width:100%" />
              </n-form-item>
            </div>
            <n-form-item label="成本上限（USD）">
              <n-input-number v-model:value="runForm.maxCost" :min="0" :step="0.000001"
                clearable placeholder="不填则不限制" style="width:200px" />
            </n-form-item>
            <n-form-item label="人工复核模式">
              <n-select v-model:value="runForm.reviewMode" style="width:240px" :options="reviewModeOptions" />
            </n-form-item>
            <div class="page-sub">预算用尽后未执行样本标为 BUDGET_EXCEEDED；复核模式影响校准流程，不影响判定口径。</div>
          </n-collapse-item>
          <n-collapse-item v-if="runForm.mode === 'REPLAY'" title="重放生成参数（可选）" name="replay">
            <n-form-item label="系统提示词">
              <n-input v-model:value="runForm.systemPrompt" type="textarea" :rows="2"
                placeholder="默认 You are a helpful assistant." />
            </n-form-item>
            <n-form-item label="模型">
              <n-input v-model:value="runForm.model" placeholder="留空则使用系统默认模型" />
            </n-form-item>
          </n-collapse-item>
        </n-collapse>

        <n-alert v-if="modalBlockReason" type="warning" :bordered="false" class="mt8">
          {{ modalBlockReason }}
        </n-alert>
        <div v-else class="page-sub">
          将执行 {{ eligibleCount }} 条样本 × {{ selectedEvaluatorCount }} 个评估器；
          提交后立即返回 runId，不等待模型。
        </div>
      </n-form>
      <template #footer>
        <n-button size="small" :disabled="creatingRun" @click="showRun = false">取消</n-button>
        <n-button type="primary" size="small" :disabled="!canSubmitRun" :loading="creatingRun" @click="doRun">
          开始运行
        </n-button>
      </template>
    </Modal>

    <!-- 运行报告抽屉：/datasets/:id/runs/:runId 可直接打开 -->
    <n-drawer :show="showRunDetail" width="88%" :transition="false" @update:show="onRunDrawerShow">
      <n-drawer-content closable>
        <template #header>
          <div class="report-head">
            <span>运行报告 {{ runDetail?.run ? '· ' + evaluatorSummary(runDetail.run) : '' }}</span>
            <n-button size="tiny" @click="copyRunLink">复制链接</n-button>
          </div>
        </template>
        <n-spin :show="runDetailLoading">
        <template v-if="runDetail?.run">
          <div v-if="!calibrationBlind" class="run-summary">
            <span>状态 <b>{{ statusText(runDetail.run.status) }}</b></span>
            <span>样本 <b class="num">{{ runDetail.run.itemCount }}</b></span>
            <span style="color:var(--success)">通过 <b class="num">{{ runDetail.run.passCount ?? 0 }}</b></span>
            <span style="color:var(--error)">未通过 <b class="num">{{ runDetail.run.failCount ?? 0 }}</b></span>
            <span style="color:var(--warn)">无法判定 <b class="num">{{ runDetail.run.unknownCount ?? 0 }}</b></span>
            <span style="color:var(--error)">ERROR <b class="num">{{ runDetail.run.errorCount ?? 0 }}</b></span>
            <span>未完成 <b class="num">{{ runDetail.run.incompleteCount ?? 0 }}</b></span>
            <span v-if="runDetail.run.evaluatorCount">评估器 <b class="num">{{ runDetail.run.evaluatorCount }}</b></span>
          </div>
          <div v-if="!calibrationBlind" class="run-summary sub">
            <span>通过率 <b>{{ fmtPercent(runDetail.run.passRate) }}</b></span>
            <span>判断覆盖率 <b>{{ fmtPercent(runDetail.run.coverage) }}</b></span>
            <span>调用 <b>{{ runUsageText(runDetail.run) }}</b></span>
            <span v-if="runDetail.run.configSnapshot?.suite">套件
              <b>{{ runDetail.run.configSnapshot.suite.name || runDetail.run.configSnapshot.suite.key }}
                · v{{ runDetail.run.configSnapshot.suite.version }}</b></span>
            <span v-if="runDetail.run.candidateLabel">候选 <b>{{ runDetail.run.candidateLabel }}</b></span>
            <span v-if="runDetail.run.attempt">尝试 <b class="num">#{{ runDetail.run.attempt }}</b></span>
            <span v-if="runDetail.run.resumedFromRunId" class="page-sub">
              续跑自 {{ runDetail.run.resumedFromRunId.slice(0, 8) }}
            </span>
            <span v-if="runDetail.run.configSnapshot?.budget?.maxTokens">
              Token 预算 <b class="num">{{ runDetail.run.configSnapshot.budget.maxTokens }}</b>
              <span class="page-sub">
                已用 {{ runDetail.run.tokensSpent || 0 }}，预留 {{ runDetail.run.tokensReserved || 0 }}
                <template v-if="runDetail.run.configSnapshot.budget.originalMaxTokens">
                  ；原始 {{ runDetail.run.configSnapshot.budget.originalMaxTokens }}，
                  累计已用 {{ (runDetail.run.configSnapshot.budget.resumeConsumedTokens || 0)
                    + (runDetail.run.tokensSpent || 0) + (runDetail.run.tokensReserved || 0) }}
                </template>
              </span>
            </span>
            <span v-if="runDetail.run.configSnapshot?.budget?.maxExternalCalls">
              预算 <b class="num">{{ runDetail.run.configSnapshot.budget.maxExternalCalls }}</b>
              <span class="page-sub">
                剩余 {{ budgetRemaining(runDetail.run) }}
                <template v-if="runDetail.run.configSnapshot.budget.originalMaxExternalCalls">
                  ；原始 {{ runDetail.run.configSnapshot.budget.originalMaxExternalCalls }}，
                  累计已用 {{ (runDetail.run.configSnapshot.budget.resumeConsumedCalls || 0)
                    + (runDetail.run.externalCallsReserved || 0) }}
                </template>
              </span>
            </span>
            <span v-if="runDetail.run.datasetRevisionId" class="page-sub">修订 {{ revisionLabel(runDetail.run.datasetRevisionId) }}</span>
            <span class="page-sub">{{ fmtTs(runDetail.run.startedAt) }}</span>
            <span class="flex-1"></span>
            <n-popconfirm v-if="isActive(runDetail.run)" @positive-click="cancelRun">
              <template #trigger>
                <n-button size="tiny" type="warning" :loading="cancelLoading">取消运行</n-button>
              </template>
              取消后未执行样本不会重跑，已产生结果保留。确定取消？
            </n-popconfirm>
            <span v-if="runDetail.run.status === 'INTERRUPTED' && !canResumeRun(runDetail.run)"
              class="page-sub">已有后续 attempt，请从尝试链末尾继续</span>
            <span v-if="canResumeRun(runDetail.run)
                && (runDetail.run.configSnapshot?.budget?.maxExternalCalls
                  || runDetail.run.configSnapshot?.budget?.maxTokens)" class="page-sub">
              续跑只使用已预留后的剩余预算
            </span>
            <n-popconfirm v-if="canResumeRun(runDetail.run)" @positive-click="resumeRun">
              <template #trigger>
                <n-button size="tiny" type="primary" :loading="resumeLoading">断点续跑</n-button>
              </template>
              将创建新的 attempt：已执行样本保留，只补未执行样本；原 run 不改写。确定续跑？
            </n-popconfirm>
            <n-popconfirm v-if="canRetryRun(runDetail.run)" @positive-click="retryRun">
              <template #trigger>
                <n-button size="tiny" type="primary" :loading="retryLoading">重试失败项</n-button>
              </template>
              将创建新的 attempt：只重跑 ERROR 样本，PASS/FAIL/UNKNOWN 结果保留；原 run 不改写。确定重试？
            </n-popconfirm>
            <n-button size="tiny" @click="exportRun">导出 CSV</n-button>
          </div>
          <n-alert v-if="runDetail.contentRestricted && !calibrationBlind" type="warning" :bordered="false" class="mb10">
            当前账号无内容查看权限：输入、参考、候选与判定理由已隐藏；导出也不会包含正文。
          </n-alert>
          <n-alert v-if="runDetail.run.legacyNoSnapshot && !calibrationBlind" type="warning" :bordered="false" class="mb10">
            历史记录，未保存快照：当时样本、评估器与生成配置不可复原，不能用于可比性结论。
          </n-alert>
          <n-card v-if="!calibrationBlind" size="small" class="mb10" title="逐样本结果">
            <template #header-extra>
              <span class="page-sub">先看每条样本的判定与原因；任务账本保留执行证据</span>
            </template>
            <n-data-table v-if="!calibrationBlind" :columns="runItemColumns" :data="runDetail.items || []" size="small" :bordered="false"
              :max-height="520" :scroll-x="1000" />
          </n-card>

          <n-card v-if="!calibrationBlind" size="small" class="mb10" title="任务账本（逐样本 × 评估器）">
            <template #header-extra>
              <span class="page-sub">样本 × 评估器逐条留痕，终态不被迟到结果覆盖</span>
            </template>
            <div v-if="runDetail.contentRestricted" class="page-sub" style="margin-bottom:6px">
              无内容查看权限：任务原因与 criterion 明细已隐藏。
            </div>
            <div v-if="runTasksLoading && !runTasks" class="page-sub">加载任务账本…</div>
            <n-alert v-else-if="runTasks?.legacy" type="default" :bordered="false">
              这是任务账本上线前的历史 run，没有逐任务证据；上方样本级结果仍然有效。新运行的实验会自动记录。
            </n-alert>
            <template v-else-if="runTasks">
              <div class="run-summary sub" style="margin-bottom:8px">
                <span>任务 <b class="num">{{ runTasks.summary?.total || 0 }}</b></span>
                <span style="color:var(--success)">成功 <b class="num">{{ runTasks.summary?.succeeded || 0 }}</b></span>
                <span style="color:var(--error)">失败 <b class="num">{{ runTasks.summary?.failed || 0 }}</b></span>
                <span style="color:var(--warn)">无法判定 <b class="num">{{ runTasks.summary?.unknown || 0 }}</b></span>
                <span>跳过 <b class="num">{{ runTasks.summary?.skipped || 0 }}</b></span>
                <span v-if="runTasks.summary?.cancelled">取消 <b class="num">{{ runTasks.summary.cancelled }}</b></span>
                <span v-if="runTasks.summary?.open">进行中 <b class="num">{{ runTasks.summary.open }}</b></span>
              </div>
              <n-data-table :columns="taskColumns" :data="runTasks.records || []" size="small"
                :bordered="false" :max-height="320" :scroll-x="980" />
            </template>
            <div v-else class="page-sub">任务账本暂不可用：{{ runTasksError || '未知错误' }}</div>
          </n-card>

          <n-card v-if="!calibrationBlind && attemptChain && attemptChain.attempts && attemptChain.attempts.length > 1"
            size="small" class="mb10" title="尝试链">
            <template #header-extra>
              <span class="page-sub">共 {{ attemptChain.attemptCount }} 次，原 run 不被覆盖</span>
            </template>
            <n-space>
              <n-tag v-for="a in attemptChain.attempts" :key="a.id" size="small"
                :type="attemptTagType(a.status)" :bordered="a.id !== runDetail.run.id"
                class="attempt-chip" @click="openRunDetail(a.id)">
                #{{ a.attempt }} {{ enumText('runStatus', a.status) }}
                <span class="page-sub">{{ a.passCount || 0 }}/{{ a.itemCount || 0 }}</span>
              </n-tag>
            </n-space>
          </n-card>
          <n-card v-if="calibration" size="small" class="mb10" title="Judge 校准（人工盲审）">
            <template #header-extra>
              <n-space align="center" :size="8">
                <n-select v-if="judgeEvaluatorKeys(runDetail).length > 1" size="tiny"
                  style="width:180px" :value="calibration.summary.evaluatorKey"
                  :options="judgeEvaluatorKeys(runDetail).map((k) => ({ label: k, value: k }))"
                  @update:value="selectCalibrationEvaluator" />
                <n-tag size="small" :bordered="false"
                  :type="calibrationStatusType(calibration.summary.status)">
                  {{ calibrationStatusText(calibration.summary.status) }}
                </n-tag>
                <n-button size="tiny" :disabled="calibrationBlind" @click="exportCalibration">
                  导出校准 CSV
                </n-button>
              </n-space>
            </template>
            <div class="page-sub" style="margin-bottom:8px">
              {{ calibration.summary.evaluatorKey }} ·
              {{ calibration.summary.reviewMode === 'DOUBLE_BLIND' ? '双人盲审' : '单人盲审' }} ·
              已完成 {{ calibration.summary.reviewed }}/{{ calibration.summary.reviewable }} ·
              <template v-if="calibration.summary.pendingAdjudication">
                待仲裁 {{ calibration.summary.pendingAdjudication }} ·
              </template>
              一致率 {{ calibration.summary.reviewed ? fmtPercent(calibration.summary.agreementRate) : '—' }} ·
              kappa {{ calibration.summary.kappa == null ? '—' : calibration.summary.kappa }} ·
              协议 {{ calibration.summary.protocolVersion || '混合/未知' }} ·
              至少 {{ calibration.summary.minimumReviewed }} 条且一致率 ≥ {{ calibration.summary.minimumAgreement }}% 才判 OK
            </div>
            <div v-if="calibration.summary.confusionMatrix" class="page-sub" style="margin-bottom:8px">
              混淆矩阵（PASS 为正类）：
              TP {{ calibration.summary.confusionMatrix.tp ?? '—' }} ·
              FP {{ calibration.summary.confusionMatrix.fp ?? '—' }} ·
              TN {{ calibration.summary.confusionMatrix.tn ?? '—' }} ·
              FN {{ calibration.summary.confusionMatrix.fn ?? '—' }} ·
              accuracy {{ calibration.summary.confusionMatrix.accuracy == null ? '—' : fmtPercent(calibration.summary.confusionMatrix.accuracy) }} ·
              precision {{ calibration.summary.confusionMatrix.precision == null ? '—' : fmtPercent(calibration.summary.confusionMatrix.precision) }} ·
              recall {{ calibration.summary.confusionMatrix.recall == null ? '—' : fmtPercent(calibration.summary.confusionMatrix.recall) }} ·
              F1 {{ calibration.summary.confusionMatrix.f1 == null ? '—' : fmtPercent(calibration.summary.confusionMatrix.f1) }}
            </div>
            <n-alert v-if="calibration.summary.protocolMixed" type="error" :bordered="false" style="margin-bottom:8px">
              该校准混用了多个协议版本（{{ (calibration.summary.protocols || []).join(' / ') }}），Gate 会阻断；请统一协议后重新校准。
            </n-alert>
            <n-alert v-if="calibration.summary.revealed"
                     type="success" :bordered="false" style="margin-bottom:8px">
              盲审已揭晓：{{ calibration.summary.reviewed }}/{{ calibration.summary.reviewable }} 条已审，
              一致率 {{ calibration.summary.agreementRate == null ? '—' : calibration.summary.agreementRate + '%' }}。
              人工标签只用于校准，不改写 run 判定。
            </n-alert>
            <n-alert v-else-if="Number(calibration.summary.reviewable || 0) > 0"
                     type="warning" :bordered="false" style="margin-bottom:8px">
              盲审进行中：全部样本完成前隐藏 run 统计、逐样本结果和 Gate；提交后不可改判。
              DOUBLE_BLIND 下第二名评审看不到第一名结论，分歧必须由第三方仲裁。Gate 会强制要求校准通过。
            </n-alert>
            <n-alert v-else type="default" :bordered="false" style="margin-bottom:8px">
              当前没有可人工校准的 Judge PASS/FAIL 样本（{{ calibrationStatusText(calibration.summary.status) }}）。
              含 LLM Judge 的 run 进入 Gate 前需要至少 10 条人工盲审且一致率 ≥ 80%。
            </n-alert>
            <n-data-table :columns="calibrationColumns"
              :data="(calibration.items || []).filter(i => i.reviewable)" size="small"
              :bordered="false" :max-height="300" :scroll-x="900" />
          </n-card>
          <n-card v-if="!calibrationBlind" size="small" class="mb10" title="准入结论（Gate）">
            <template #header-extra>
              <n-select v-if="gateTemplateOptions.length" v-model:value="gateTemplateKey"
                size="tiny" clearable placeholder="默认政策 90/0/0/24h"
                :options="gateTemplateOptions" style="width: 190px" />
              <n-button size="tiny" :loading="gateLoading" @click="createGate">生成准入结论</n-button>
            </template>
            <div v-if="gate" class="gate-box">
              <div class="gate-head">
                <n-tag size="small" :bordered="false" :type="gateType(gate.decision)">{{ enumText('gateStatus', gate.decision) }}</n-tag>
                <n-tag v-if="gateVerification" size="small" :bordered="false"
                  :type="gateVerification.usable ? 'success' : 'warning'">
                  {{ gateVerification.usable ? '当前可用' : '已不可用' }}
                </n-tag>
                <span class="page-sub">{{ gate.summary }}</span>
              </div>
              <div v-if="gateVerification && !gateVerification.usable" class="page-sub">
                失效原因：{{ (gateVerification.reasons || []).join(' / ') }}
              </div>
              <div class="gate-checks">
                <span v-for="c in gate.checks || []" :key="c.code" class="gate-check"
                  :class="'gate-' + String(c.status || '').toLowerCase()"
                  :title="c.code + ' · ' + (c.actual || '')">
                  {{ c.expected || c.code }} · {{ enumText('gateStatus', c.status) }}
                </span>
              </div>
              <div class="page-sub">策略 {{ gate.policyKey }} / {{ gate.policyVersion }} · 覆盖率 {{ fmtPercent(gate.coverage) }} ·
                候选 {{ gate.candidateLabel || '—' }} · 有效至 {{ fmtTs(gate.validUntil) }}</div>
            </div>
            <div v-else class="page-sub">
              尚未生成。Gate 只消费已结束且绑定 revision/version 的 run；缺证据返回 BLOCKED，不会默认放行。
            </div>
          </n-card>
          <n-card v-if="!calibrationBlind && costReport" size="small" class="mb10" title="成本估算">
            <div class="run-summary sub">
              <span>Prompt <b class="num">{{ costReport.promptTokens ?? '—' }}</b></span>
              <span>Completion <b class="num">{{ costReport.completionTokens ?? '—' }}</b></span>
              <span>Total <b class="num">{{ costReport.totalTokens ?? '—' }}</b></span>
              <span v-if="costReport.priced">成本
                <b class="num">{{ costReport.cost }} {{ costReport.currency }}</b>
              </span>
              <span v-else class="page-sub">未计价：{{ costReason(costReport.reason) }}</span>
            </div>
            <div class="page-sub">按已配置模型价格与 provider usage 估算；多模型或未配置价格时不伪造总额。</div>
          </n-card>

          <n-card v-if="!calibrationBlind && qualityModelReport?.bound"
            size="small" class="mb10" title="质量模型（Domain Pack）">
            <div class="run-summary sub">
              <span>{{ qualityModelReport.packKey }}@{{ qualityModelReport.packVersion }}</span>
              <span>维度覆盖 <b>{{ qualityModelReport.coverage == null ? '—' : qualityModelReport.coverage + '%' }}</b></span>
              <span>加权通过率 <b>{{ qualityModelReport.weightedScore == null ? '—' : qualityModelReport.weightedScore + '%' }}</b></span>
            </div>
            <div v-for="d in qualityModelReport.dimensions" :key="d.key" class="dimension-row">
              <span class="dimension-key">{{ d.label || d.key }}</span>
              <span class="page-sub">通过 {{ d.pass }} / 未通过 {{ d.fail }} / 无法判定 {{ d.unknown }} · 权重 {{ d.weight }}</span>
              <b class="num">{{ d.passRate == null ? '—' : d.passRate + '%' }}</b>
            </div>
            <div v-if="(qualityModelReport.unmappedEvaluatorKeys || []).length" class="page-sub">
              未映射评估器：{{ qualityModelReport.unmappedEvaluatorKeys.join(', ') }}
            </div>
          </n-card>

          <n-card v-if="!calibrationBlind && gateHistory.length > 1" size="small" class="mb10" title="历史 Gate">
            <n-space vertical size="small" class="gate-history-list">
              <div v-for="row in gateHistory" :key="row.gate.id" class="gate-history-row">
                <n-tag size="tiny" :bordered="false" :type="gateType(row.gate.decision)">
                  {{ enumText('gateStatus', row.gate.decision) }}
                </n-tag>
                <n-tag size="tiny" :bordered="false"
                  :type="row.verification?.usable ? 'success' : 'warning'">
                  {{ row.verification?.usable ? '当前可用' : '已不可用' }}
                </n-tag>
                <span class="page-sub">
                  {{ row.gate.policyKey }} / {{ row.gate.policyVersion }} ·
                  覆盖 {{ fmtPercent(row.gate.coverage) }} ·
                  {{ fmtTs(row.gate.createdAt) }}
                </span>
                <span v-if="!row.verification?.usable" class="page-sub">
                  原因：{{ (row.verification?.reasons || []).join(' / ') }}
                </span>
              </div>
            </n-space>
          </n-card>
          <n-alert v-if="!calibrationBlind" type="default" :bordered="false" class="mb10">
            Gate 只输出准入判断，不执行部署/回滚；LLM Judge 结果标为“模型建议”，未经人工校准。
          </n-alert>
          <n-card v-if="!calibrationBlind" size="small" class="mb10" title="业务实验证据">
            <template #header-extra>
              <n-space align="center" :size="8">
                <n-select v-if="businessExperiments.length > 1" size="tiny" style="width:220px"
                  :value="selectedBusinessId" :options="businessOptions"
                  @update:value="(v) => (selectedBusinessId = v)" />
                <n-button size="tiny" @click="openBusinessCreate">绑定实验</n-button>
                <n-button size="tiny" @click="openCredentials">签名凭据</n-button>
                <n-button v-if="selectedBusiness" size="tiny" type="primary"
                  @click="openBusinessEvidence">导入报告版本</n-button>
              </n-space>
            </template>
            <div v-if="!businessExperiments.length" class="page-sub">
              未绑定外部业务实验。离线 Gate 不会把 Judge 分数当作线上收益；业务证据需另附实验报告。
            </div>
            <template v-else-if="selectedBusiness">
              <div class="run-summary sub">
                <span>来源 <b>{{ selectedBusiness.source }}</b></span>
                <span>实验 <b class="mono">{{ selectedBusiness.externalExperimentId }}</b></span>
                <span>设计 <b>{{ selectedBusiness.designType }}</b></span>
                <span>主指标 <b>{{ selectedBusiness.primaryMetric }}</b></span>
                <span>最小样本 <b class="num">{{ selectedBusiness.minSampleSize || 0 }}</b></span>
                <n-tag size="tiny" :bordered="false" :type="businessDecisionType(selectedBusiness.latestEvidence?.decision)">
                  {{ selectedBusiness.latestEvidence?.decision ? enumText('decision', selectedBusiness.latestEvidence.decision) : '无证据' }}
                </n-tag>
              </div>
              <div v-if="selectedBusiness.latestEvidence" class="page-sub" style="margin-top:6px">
                版本 {{ selectedBusiness.latestEvidence.reportVersion }} ·
                样本 {{ selectedBusiness.latestEvidence.qualityChecks?.sampleSize ?? '—' }} ·
                lift {{ selectedBusiness.latestEvidence.metrics?.relativeLift ?? selectedBusiness.latestEvidence.metrics?.absoluteDiff ?? '—' }} ·
                SRM {{ selectedBusiness.latestEvidence.qualityChecks?.srmOk ? 'OK' : 'CHECK' }} ·
                护栏 {{ selectedBusiness.latestEvidence.qualityChecks?.guardrailsOk ? 'OK' : 'CHECK' }}
              </div>
              <n-data-table v-if="(selectedBusiness.evidence || []).length" size="small" :bordered="false"
                :max-height="180" :columns="businessEvidenceColumns" :data="selectedBusiness.evidence" />
            </template>
          </n-card>
          <n-card v-if="!calibrationBlind" size="small" class="mb10" title="质量案例（回归闭环）">
            <template #header-extra>
              <n-button size="tiny" @click="openCaseCreate">登记案例</n-button>
            </template>
            <div v-if="!qualityCases.length" class="page-sub">
              把未通过/无法判定或线上问题登记为案例；修复后必须用回归 run 的最新可用通过 Gate 才能关闭。
            </div>
            <div v-else class="case-list">
              <div v-for="c in qualityCases" :key="c.id" class="case-row">
                <div class="case-main">
                  <div>
                    <n-tag size="tiny" :bordered="false" :type="caseStatusType(c.status)">{{ enumText('caseStatus', c.status) }}</n-tag>
                    <b style="margin-left:6px">{{ c.title }}</b>
                    <span class="page-sub"> · {{ c.rootCauseCategory ? enumText('rootCause', c.rootCauseCategory) : '未知' }} · {{ c.owner || '未指派' }}</span>
                  </div>
                  <div class="page-sub">{{ c.phenomenon }}</div>
                </div>
                <n-space :size="6">
                  <n-button v-if="c.status === 'OPEN'" size="tiny" @click="advanceCase(c, 'INVESTIGATING')">开始调查</n-button>
                  <n-button v-if="c.status === 'INVESTIGATING'" size="tiny" @click="openCaseFix(c)">标记修复</n-button>
                  <n-button v-if="c.status === 'FIXED'" size="tiny" type="primary" @click="openCaseClose(c)">
                    关联回归并关闭
                  </n-button>
                  <n-tag v-if="c.status === 'CLOSED'" size="tiny" type="success" :bordered="false">已关闭</n-tag>
                </n-space>
              </div>
            </div>
          </n-card>

        </template>
        <div v-else class="page-sub">{{ runDetailLoading ? '加载中…' : '运行不存在或已被删除' }}</div>
        </n-spin>
      </n-drawer-content>
    </n-drawer>
    </template>
  </div>
</template>

<script setup>
import { computed, h, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NPopconfirm, NSelect, NTag, useMessage } from 'naive-ui'
import api from '@/api'
import EvaluationFlow from '@/components/EvaluationFlow.vue'
import { toCsv, downloadCsv } from '@/common/csv'
import { enumText, enumOptions } from '@/common/i18n'

const route = useRoute()
const router = useRouter()
const message = useMessage()
const datasetId = String(route.params.id || '')
const dataset = ref(null), loading = ref(true), loadError = ref('')
const selectedBusiness = computed(() => businessExperiments.value.find((e) => e.id === selectedBusinessId.value) || null)
const businessOptions = computed(() => businessExperiments.value.map((e) => ({
  label: `${e.source} / ${e.externalExperimentId}`, value: e.id
})))
const businessEvidenceColumns = [
  { title: '版本', key: 'reportVersion' },
  { title: '结论', key: 'decision', render: (row) => h(NTag, { size: 'tiny', bordered: false, type: businessDecisionType(row.decision) }, () => row.decision) },
  { title: '样本', render: (row) => row.qualityChecks?.sampleSize ?? '—' },
  { title: 'lift', render: (row) => row.metrics?.relativeLift ?? row.metrics?.absoluteDiff ?? '—' },
  { title: '接收时间', key: 'receivedAt', render: (row) => fmtTs(row.receivedAt) }
]
const health = ref(null), healthHistory = ref([]), healthLoading = ref(false), healthError = ref('')
const showBindPack = ref(false), bindSaving = ref(false), bindPackId = ref(null), packOptions = ref([])
const packUpgrading = ref(false)
const staleQuarantining = ref(false)
const businessExperiments = ref([]), selectedBusinessId = ref(''), savingBusiness = ref(false)
const showCredentials = ref(false), credentials = ref([]), savingCredential = ref(false)
const credentialForm = reactive({ keyId: '', source: '', name: '', publicKey: '' })
const credentialColumns = [
  { title: 'Key ID', key: 'keyId', render: (r) => h('span', { class: 'mono' }, r.keyId) },
  { title: 'Source', key: 'source', width: 130, render: (r) => r.source || '—' },
  { title: '状态', key: 'status', width: 100, render: (r) => h(NTag, {
      size: 'tiny', bordered: false, type: r.status === 'ACTIVE' ? 'success' : 'default'
    }, { default: () => enumText('credentialStatus', r.status) }) },
  { title: '操作', key: 'ops', width: 90, render: (r) => r.status === 'ACTIVE'
    ? h(NButton, { size: 'tiny', onClick: () => revokeCredential(r) }, { default: () => '吊销' })
    : '—' }
]
const showBusinessCreate = ref(false), showBusinessEvidence = ref(false)
const businessForm = reactive({ source: 'external', externalExperimentId: '', designType: 'RANDOMIZED', primaryMetric: '', minSampleSize: '100', target: '' })
const businessEvidenceForm = reactive({ reportVersion: '', metrics: '{\n  "sampleSize": 1000,\n  "pValue": 0.01,\n  "relativeLift": 0.05\n}', qualityChecks: '{}' })
const showBlueprint = ref(false), blueprintText = ref(''), savingBlueprint = ref(false)
const pairwise = ref(null), pairwiseLoading = ref(false)
const showPairwiseOptions = ref(false)
const pairwiseForm = reactive({
  judgeModelsText: '', samplesPerDirection: 1, positionRandomized: false, positionSeed: null
})
const pairwiseHistory = ref([]), pairwiseHistoryLoading = ref(false)
const showPairwiseDetail = ref(false), pairwiseDetail = ref(null)
const pairwiseHistoryColumns = [
  { title: '时间', key: 'createdAt', width: 150, render: (r) => fmtTs(r.createdAt) },
  { title: 'A run', key: 'runAId', width: 150, ellipsis: { tooltip: true }, render: (r) => h('span', { class: 'mono' }, shortRunId(r.runAId)) },
  { title: 'B run', key: 'runBId', width: 150, ellipsis: { tooltip: true }, render: (r) => h('span', { class: 'mono' }, shortRunId(r.runBId)) },
  { title: 'A 胜 / B 胜 / 平局 / 无法判定', key: 'wins', minWidth: 190, render: (r) =>
      `${r.aWins ?? 0} / ${r.bWins ?? 0} / ${r.ties ?? 0} / ${r.unknownCount ?? 0}` },
  { title: '偏好率', key: 'preferenceRate', width: 90, align: 'right', render: (r) => r.preferenceRate == null ? '—' : r.preferenceRate + '%' },
  { title: '评审模型', key: 'judgeModels', minWidth: 150, ellipsis: { tooltip: true },
    render: (r) => (r.judgeModels || [r.judgeModel]).filter(Boolean).join(', ') || '默认' },
  { title: '协议', key: 'protocolVersion', width: 110, render: (r) => r.protocolVersion || r.protocol || '—' }
]
const pairwiseItemColumns = [
  { title: '样本', key: 'itemId', width: 110, render: (r) => h('span', { class: 'mono' }, String(r.itemId || '').slice(0, 8)) },
  { title: '模型偏好', key: 'preference', width: 110, render: (r) => h(NTag, {
      size: 'tiny', bordered: false, type: r.preference === 'A' ? 'info' : r.preference === 'B' ? 'success'
        : r.preference === 'TIE' ? 'default' : 'warning'
    }, { default: () => (r.preference ? enumText('pairwisePreference', r.preference) : '—') }) },
  { title: '原因', key: 'reason', minWidth: 360, ellipsis: { tooltip: true }, render: (r) => reasonText(r.reason) }
]
function reasonText(reason) {
  return ({
    INPUT_OR_OUTPUT_MISSING: '输入或候选输出缺失',
    DIRECTION_CONFLICT: '双向判定冲突',
    JUDGE_ERROR: '评审调用失败',
    NO_MAJORITY: '多模型无多数',
    TIE: '平局'
  }[reason] || reason || '—')
}
function shortRunId(id) {
  if (!id) return '—'
  return String(id).length > 12 ? String(id).slice(0, 8) + '…' : String(id)
}
const showHumanReview = ref(false), humanSaving = ref(false)
const humanDrafts = reactive({}), humanNotes = reactive({})
const humanOptions = [
  { label: 'A 胜', value: 'A' }, { label: 'B 胜', value: 'B' },
  { label: '平局', value: 'TIE' }, { label: '无法判定', value: 'UNKNOWN' }
]
const qualityCases = ref([]), caseSaving = ref(false)
const showCaseCreate = ref(false), showCaseFix = ref(false), showCaseClose = ref(false)
const caseForm = reactive({ title: '', phenomenon: '', rootCauseCategory: 'UNKNOWN', owner: '',
  fixFingerprint: '', fixNote: '', regressionRunId: '' })
const rootCauseOptions = enumOptions('rootCause', ['PROMPT', 'RETRIEVAL', 'TOOL', 'MODEL', 'DATA', 'TEST', 'ENVIRONMENT', 'UNKNOWN', 'OTHER'])
  .map((v) => ({ label: v, value: v }))
const showAddItem = ref(false), saving = ref(false), editingItemId = ref(null)
const itemForm = reactive({
  traceId: '', input: '', expectedOutput: '', purposeTags: '', riskLevel: 'UNASSIGNED',
  groupKey: '', split: 'UNASSIGNED', failureMode: '', schemaVersion: 1,
  retrievalContextText: '', expectedToolsText: '', turnsText: '', expectationsText: ''
})
const showRun = ref(false), creatingRun = ref(false), freezingRevision = ref(false)
const runForm = reactive({ mode: 'TRACE', suiteId: null, evaluatorIds: [], revisionId: '', candidateLabel: '', systemPrompt: '', model: '', maxExternalCalls: null, maxTokens: null, maxCost: null, reviewMode: 'SINGLE' })
const reviewModeOptions = [
  { label: 'SINGLE（单人盲审）', value: 'SINGLE' },
  { label: 'DOUBLE_BLIND（双人盲审 + 仲裁）', value: 'DOUBLE_BLIND' }
]
const evaluatorOptions = ref([])
const suiteOptions = ref([])
const selectedSuite = computed(() => suiteOptions.value.find((s) => s.value === runForm.suiteId)?.suite || null)
const riskOptions = computed(() => {
  const pack = health.value?.riskTaxonomy || []
  const options = pack.length
    ? pack.map((r) => ({ label: `${r.label || r.key}（${r.key}）`, value: r.key }))
    : enumOptions('riskLevel', ['UNASSIGNED', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'])
  if (!options.some((o) => String(o.value).toUpperCase() === 'UNASSIGNED')) {
    options.unshift({ label: enumText('riskLevel', 'UNASSIGNED'), value: 'UNASSIGNED' })
  }
  return options
})
const splitOptions = enumOptions('split', ['UNASSIGNED', 'DEV', 'VALIDATION', 'HOLDOUT'])
const mainTab = ref('runs')
const showRunDetail = ref(false), runDetail = ref(null), runDetailLoading = ref(false), attemptChain = ref(null), cancelLoading = ref(false), resumeLoading = ref(false), retryLoading = ref(false)
const runTasks = ref(null), runTasksLoading = ref(false), runTasksError = ref('')
let runTasksSeq = 0
const gate = ref(null), gateVerification = ref(null), gateHistory = ref([]), gateLoading = ref(false)
const qualityModelReport = ref(null)
const costReport = ref(null)
const gateTemplateKey = ref(null), gateTemplateOptions = ref([])
const calibrations = ref([]), calibrationEvaluatorKey = ref('')
const calibrationLoading = ref(false), calibrationSavingId = ref(null)
const calibration = computed(() => calibrations.value.find(
  (c) => c.summary?.evaluatorKey === calibrationEvaluatorKey.value) || calibrations.value[0] || null)
const pollError = ref('')
let pollTimer = null
let pollToken = 0
let detailRequestToken = 0

function fmtTs(s) {
  if (!s) return '—'
  return String(s).replace('T', ' ').slice(0, 19)
}
function fmtPercent(v) {
  if (v == null) return '—'
  return Number.isInteger(v) ? v + '%' : Number(v).toFixed(1) + '%'
}
function statusText(status) {
  return ({
    COMPLETED: '执行完成',
    PARTIAL_FAILED: '部分失败',
    FAILED: '执行失败',
    INTERRUPTED: '已中断',
    CANCELLED: '已取消',
    PENDING: '排队中',
    RUNNING: '执行中'
  })[status] || status || '—'
}
function statusType(status) {
  return ({
    COMPLETED: 'success',
    PARTIAL_FAILED: 'warning',
    FAILED: 'error',
    INTERRUPTED: 'warning',
    CANCELLED: 'default',
    PENDING: 'info',
    RUNNING: 'info'
  })[status] || 'default'
}
function statusTag(status, legacy) {
  const tags = [h(NTag, { size: 'small', bordered: false, type: statusType(status) },
    { default: () => statusText(status) })]
  if (legacy) {
    tags.push(h(NTag, { size: 'tiny', bordered: false, type: 'default', style: 'margin-left:6px' },
      { default: () => '历史记录' }))
  }
  return h('span', {}, tags)
}
function labelType(l) {
  if (l === 'PASS') return 'success'
  if (l === 'FAIL') return 'error'
  if (l === 'ERROR') return 'error'
  if (l === 'UNKNOWN') return 'warning'
  return 'default'
}
function labelTag(l) {
  return h(NTag, { size: 'small', bordered: false, type: labelType(l), title: l || undefined },
    { default: () => (l ? enumText('evalLabel', l) : '未完成') })
}
function isActive(run) {
  return !!run && (run.status === 'PENDING' || run.status === 'RUNNING')
}
const activeRun = computed(() => (dataset.value?.runs || []).find(isActive) || null)
const latestRun = computed(() => {
  const runs = [...(dataset.value?.runs || [])]
  return runs.sort((a, b) => String(b.startedAt || b.createdAt || '').localeCompare(String(a.startedAt || a.createdAt || '')))[0] || null
})
const latestGate = ref(null), latestGateUsable = ref(false)
const latestCalibration = ref(null), latestOpenCases = ref(0)
const evaluatorTypeByKey = ref({})
const calibrationDetailText = computed(() => latestCalibration.value
  ? `已审 ${latestCalibration.value.reviewed}/${latestCalibration.value.reviewable} · 一致率 ${fmtPercent(latestCalibration.value.agreementRate)}`
  : '')
const gateDetailText = computed(() => latestGate.value
  ? `${latestGate.value.policyKey || '默认政策'} · 覆盖 ${fmtPercent(latestGate.value.coverage)} · ${latestGateUsable.value ? '当前可用' : '已不可用'}`
  : '')
const activeItemCount = computed(() => (dataset.value?.items || []).filter((i) => i.status !== 'QUARANTINED').length)
function doneCount(run) {
  return (run?.passCount || 0) + (run?.failCount || 0) + (run?.unknownCount || 0) + (run?.errorCount || 0)
}
function runUsageText(run) {
  const u = run?.usageSummary
  if (!u || !u.externalCalls) return '不产生外部调用'
  if (!u.usageKnown) return `未知（${u.externalCalls} 次外部调用）`
  return `${u.totalTokens ?? (u.promptTokens || 0) + (u.completionTokens || 0)} tokens`
}
function evaluatorSummary(run) {
  const keys = run?.evaluatorKeys || []
  if (keys.length > 1) return keys.join(' + ')
  return run?.evaluatorKey || '—'
}
function evaluatorResultTags(row) {
  const results = row?.evaluatorResults || row?.resultDetails?.evaluators || []
  if (!results.length) return h('span', { style: 'color:var(--text-muted);font-size:12px' }, '—')
  const tags = []
  for (const e of results) {
    tags.push(h(NTag, { size: 'tiny', bordered: false, type: labelType(e.label), title: e.reason || '' },
      { default: () => `${e.evaluatorKey || e.evaluatorId}: ${e.label || '—'}` }))
    for (const c of e.criteria || []) {
      tags.push(h(NTag, {
        size: 'tiny', bordered: false, type: c.passed ? 'success' : 'error',
        title: c.reason || ''
      }, { default: () => `${c.label || c.id}: ${c.passed ? '✓' : '✗'}` }))
    }
    for (const claim of e.claims || []) {
      const type = claim.verdict === 'PASS' ? 'success'
        : claim.verdict === 'FAIL' ? 'error' : 'warning'
      tags.push(h(NTag, {
        size: 'tiny', bordered: false, type,
        title: [claim.reason, claim.evidenceRefs?.join(', ')].filter(Boolean).join(' · ')
      }, { default: () => `${claim.id || 'claim'}: ${claim.verdict ? enumText('evalLabel', claim.verdict) : '无法判定'}` }))
    }
  }
  return h('span', { style: 'display:flex;gap:4px;flex-wrap:wrap' }, tags)
}
function revisionLabel(id) {
  const r = (dataset.value?.revisions || []).find((x) => x.id === id)
  return r ? `v${r.revision}${r.label ? ' · ' + r.label : ''}` : String(id || '').slice(0, 8)
}
function itemUsageText(details) {
  const calls = details?.calls
  if (!calls) return '—'
  if (!calls.external) return '不产生外部调用'
  const usage = details.usage
  if (!usage || !usage.known) return `未知（${calls.external} 次调用）`
  return `${usage.totalTokens ?? ''} tokens（生成 ${calls.generation} / Judge ${calls.judge}）`
}

const itemColumns = [
  { title: 'Trace', key: 'traceId', width: 170, ellipsis: { tooltip: true },
    render: (r) => r.traceId
      ? h('span', { class: 'mono link', title: r.traceId, onClick: () => router.push('/traces/' + encodeURIComponent(r.traceId)) }, r.traceId) : '—' },
  { title: '输入', key: 'input', minWidth: 200, ellipsis: { tooltip: true }, render: (r) => r.input || '—' },
  { title: '期望输出', key: 'expectedOutput', minWidth: 200, ellipsis: { tooltip: true }, render: (r) => r.expectedOutput || '—' },
  { title: '契约', key: 'schemaVersion', width: 150, render: (r) => {
      if ((r.schemaVersion || 1) < 2) return h('span', { class: 'page-sub' }, 'v1')
      if (r.contentRestricted) return h('span', { class: 'page-sub' }, 'v2 · 内容受限')
      const parts = []
      if (r.retrievalContext?.length) parts.push('检索 ' + r.retrievalContext.length)
      if (r.expectedTools?.length) parts.push('工具 ' + r.expectedTools.length)
      if (r.turns?.length) parts.push('多轮 ' + r.turns.length)
      if (r.expectations) parts.push('期望')
      return h(NTag, { size: 'tiny', type: 'info', bordered: false },
        { default: () => 'v2 · ' + (parts.join(' / ') || '空') })
    } },
  { title: '用途', key: 'purposeTags', width: 130, ellipsis: { tooltip: true }, render: (r) => r.purposeTags || '—' },
  { title: '风险', key: 'riskLevel', width: 80, render: (r) => enumText('riskLevel', r.riskLevel) || '—' },
  { title: '划分', key: 'split', width: 95, render: (r) => enumText('split', r.split) || '—' },
  { title: '状态', key: 'status', width: 90, render: (r) => r.status === 'QUARANTINED'
    ? h(NTag, { size: 'tiny', type: 'warning', bordered: false }, { default: () => '已失效' })
    : h(NTag, { size: 'tiny', type: 'success', bordered: false }, { default: () => enumText('itemStatus', r.status) }) },
  { title: '失败模式', key: 'failureMode', width: 140, ellipsis: { tooltip: true }, render: (r) => (r.failureMode ? enumText('failureMode', r.failureMode) : '—') },
  { title: '操作', key: 'ops', width: 210, render: (r) => h('div', { style: 'display:flex;gap:6px' }, [
      h(NButton, { size: 'tiny', disabled: !!r.contentRestricted,
        title: r.contentRestricted ? '无内容查看权限，不能编辑样本正文' : undefined,
        onClick: () => openEditItem(r) }, { default: () => '编辑' }),
      r.status === 'QUARANTINED'
        ? h(NButton, { size: 'tiny', onClick: () => doRestoreItem(r) }, { default: () => '恢复' })
        : h(NPopconfirm, { onPositiveClick: () => doInvalidateItem(r) }, {
            trigger: () => h(NButton, { size: 'tiny' }, { default: () => '软失效' }),
            default: () => '软失效后新 run 不再纳入该样本，历史结果与 revision 不受影响，可恢复。'
          }),
      h(NPopconfirm, { onPositiveClick: () => doDeleteItem(r) }, {
        trigger: () => h(NButton, { size: 'tiny' }, { default: () => '删除' }),
        default: () => '确认删除该样本？'
      })
    ]) }
]
const revisionColumns = [
  { title: '版本', key: 'revision', width: 65, render: (r) => h('span', { class: 'num' }, 'v' + r.revision) },
  { title: '标签', key: 'label', width: 150, ellipsis: { tooltip: true } },
  { title: '样本', key: 'itemCount', width: 60, align: 'right' },
  { title: 'manifest', key: 'manifestHash', minWidth: 150, render: (r) => h('span', { class: 'mono', style: 'font-size:11px' }, String(r.manifestHash || '').slice(0, 16) + '…') },
  { title: '冻结时间', key: 'createdAt', width: 150, render: (r) => h('span', { class: 'num', style: 'font-size:11.5px' }, fmtTs(r.createdAt)) }
]
const revisionOptions = computed(() => [
  { label: '自动冻结当前样本', value: '' },
  ...(dataset.value?.revisions || []).map((r) => ({
    label: `v${r.revision} · ${r.label || ''} · ${r.itemCount} 条`, value: r.id
  }))
])
const runColumns = [
  { title: '评估器', key: 'evaluatorKey', width: 210, ellipsis: { tooltip: true },
    render: (r) => h('span', { class: 'mono', title: evaluatorSummary(r) }, evaluatorSummary(r)) },
  { title: '候选', key: 'candidateLabel', width: 110, ellipsis: { tooltip: true }, render: (r) => r.candidateLabel || '—' },
  { title: '模式', key: 'runMode', width: 75, render: (r) => h(NTag, {
      size: 'small', bordered: false, type: r.runMode === 'REPLAY' ? 'info' : 'default'
    }, { default: () => r.runMode === 'REPLAY' ? '重放' : '评估' }) },
  { title: '模型', key: 'model', width: 110, render: (r) => r.model
    ? h('span', { class: 'mono', style: 'font-size:11.5px' }, r.model) : '—' },
  { title: '状态', key: 'status', width: 130, render: (r) => statusTag(r.status, r.legacyNoSnapshot) },
  { title: '样本', key: 'itemCount', width: 60, align: 'right' },
  { title: 'PASS', key: 'passCount', width: 60, align: 'right', render: (r) => h('span', { class: 'num', style: 'color:var(--success)' }, String(r.passCount ?? 0)) },
  { title: 'FAIL', key: 'failCount', width: 60, align: 'right', render: (r) => h('span', { class: 'num', style: 'color:var(--error)' }, String(r.failCount ?? 0)) },
  { title: 'UNKNOWN', key: 'unknownCount', width: 78, align: 'right', render: (r) => h('span', { class: 'num', style: 'color:var(--warn)' }, String(r.unknownCount ?? 0)) },
  { title: 'ERROR', key: 'errorCount', width: 70, align: 'right', render: (r) => h('span', { class: 'num', style: 'color:var(--error)' }, String(r.errorCount ?? 0)) },
  { title: '未完成', key: 'incompleteCount', width: 68, align: 'right', render: (r) => h('span', { class: 'num' }, String(r.incompleteCount ?? 0)) },
  { title: '通过率', key: 'passRate', width: 78, align: 'right', render: (r) => h('span', { class: 'num', title: 'PASS/(PASS+FAIL)' }, fmtPercent(r.passRate)) },
  { title: '覆盖率', key: 'coverage', width: 78, align: 'right', render: (r) => h('span', { class: 'num', title: '(PASS+FAIL)/样本数' }, fmtPercent(r.coverage)) },
  { title: '时间', key: 'startedAt', width: 150, render: (r) => h('span', { class: 'num', style: 'font-size:11.5px' }, fmtTs(r.startedAt)) },
  { title: '操作', key: 'ops', width: 70, render: (r) =>
      h(NButton, { size: 'tiny', onClick: () => openRunDetail(r.id) }, { default: () => '结果' }) }
]
const runItemColumns = computed(() => {
  const replay = runDetail.value?.run?.runMode === 'REPLAY'
  const restricted = !!runDetail.value?.contentRestricted
  const cols = [
    { title: '样本', key: 'itemId', width: 110, render: (r) => h('span', { class: 'mono', style: 'font-size:11px' }, String(r.itemId || '').slice(0, 8)) }
  ]
  if (replay) {
    cols.push({ title: '原始 Trace', key: 'sourceTraceId', width: 145, ellipsis: { tooltip: true },
      render: (r) => r.sourceTraceId
        ? h('span', { class: 'mono link', title: r.sourceTraceId, onClick: () => router.push('/traces/' + encodeURIComponent(r.sourceTraceId)) }, r.sourceTraceId) : '—' })
  }
  cols.push({ title: replay ? '重放 Trace' : 'Trace', key: 'traceId', width: 180, ellipsis: { tooltip: true },
    render: (r) => r.traceId
      ? h('span', { class: 'mono link', title: r.traceId, onClick: () => router.push('/traces/' + encodeURIComponent(r.traceId)) }, r.traceId) : '—' })
  cols.push(
    { title: '判定', key: 'label', width: 95, render: (r) => h('span', {}, [
        labelTag(r.label || '未完成'),
        r.resultDetails?.judgeAdvice
          ? h(NTag, { size: 'tiny', bordered: false, type: 'info', style: 'margin-left:4px' }, { default: () => '模型建议' })
          : null
      ]) },
    { title: '逐评估器 / 维度', key: 'evaluatorResults', width: 240, render: (r) => evaluatorResultTags(r) },
    { title: '分数', key: 'score', width: 65, align: 'right', render: (r) => h('span', { class: 'num' }, r.score == null ? '—' : String(r.score)) },
    { title: '原因', key: 'reason', minWidth: 210, ellipsis: { tooltip: true }, render: (r) =>
        h('span', {}, [
          // 中文结论放最前：列宽有限会截断，解释放在长 reason 后面等于看不见
          r.resultDetails?.reasonCode
            ? h('span', {
                class: 'mono',
                style: 'color:var(--text-muted);font-size:11px;margin-right:6px',
                title: r.resultDetails.reasonCode
              }, enumText('evalReason', r.resultDetails.reasonCode))
            : null,
          h('span', {}, r.reason || '—')
        ]) },
    { title: '调用/用量', key: 'usage', width: 150, render: (r) => h('span', { style: 'font-size:11.5px;color:var(--text-secondary)' }, itemUsageText(r.resultDetails)) }
  )
  if (!restricted) {
    cols.push(
      { title: '输入', key: 'input', minWidth: 160, ellipsis: { tooltip: true }, render: (r) =>
          r.sampleSnapshot?.input || r.sampleSnapshot?.traceInput || '—' },
      { title: '候选输出', key: 'candidate', minWidth: 180, ellipsis: { tooltip: true }, render: (r) =>
          r.resultDetails?.candidateOutput ?? r.replayOutput ?? '—' },
      { title: '参考', key: 'expected', minWidth: 160, ellipsis: { tooltip: true }, render: (r) =>
          r.sampleSnapshot?.expectedOutput ?? '—' }
    )
  }
  return cols
})

const taskColumns = [
  { title: '样本', key: 'itemOrder', width: 60, render: (r) => h('span', { class: 'num' }, String((r.itemOrder ?? 0) + 1)) },
  { title: '评估器', key: 'evaluatorKey', width: 150, ellipsis: { tooltip: true }, render: (r) =>
      h('span', { class: 'mono', style: 'font-size:11px' }, r.evaluatorKey || r.evaluatorId || '—') },
  { title: '任务状态', key: 'status', width: 90, render: (r) => h(NTag, {
      size: 'small', bordered: false, type: taskStatusType(r.status)
    }, { default: () => taskStatusText(r.status) }) },
  { title: '判定', key: 'verdict', width: 90, render: (r) => r.verdict ? labelTag(r.verdict) : '—' },
  { title: '分数', key: 'score', width: 60, align: 'right', render: (r) => h('span', { class: 'num' }, r.score == null ? '—' : String(r.score)) },
  { title: '尝试', key: 'attemptCount', width: 55, align: 'right', render: (r) => {
      const attempts = r.attempts || []
      const tip = attempts.length
        ? attempts.map((a) => `#${a.callSeq} ${enumText('attemptOutcome', a.outcome)}${a.reasonCode ? '/' + a.reasonCode : ''} ${a.latencyMs ?? 0}ms`).join('\n')
        : '无 attempt 明细'
      return h('span', { class: 'num', title: tip }, String(r.attemptCount ?? 0))
    } },
  { title: 'Token', key: 'totalTokens', width: 70, align: 'right', render: (r) => r.totalTokens == null ? '—' : String(r.totalTokens) },
  { title: '耗时', key: 'latencyMs', width: 70, align: 'right', render: (r) => r.latencyMs == null ? '—' : `${r.latencyMs}ms` },
  { title: '原因', key: 'reason', minWidth: 220, ellipsis: { tooltip: true }, render: (r) =>
      h('span', {}, [
        // 中文结论放最前：列宽有限会截断，解释放在长 reason 后面等于看不见
        r.reasonCode
          ? h('span', {
              class: 'mono',
              style: 'color:var(--text-muted);font-size:11px;margin-right:6px',
              title: r.reasonCode
            }, enumText('evalReason', r.reasonCode))
          : null,
        h('span', {}, r.reason || '—')
      ]) },
  { title: '证据', key: 'evidenceHash', width: 90, render: (r) => r.evidenceHash
      ? h('span', { class: 'mono', style: 'font-size:10.5px', title: r.evidenceHash }, r.evidenceHash.slice(0, 8))
      : '—' }
]
function taskStatusType(status) {
  return ({ SUCCEEDED: 'success', FAILED: 'error', UNKNOWN: 'warning', RUNNING: 'info',
    PENDING: 'default', SKIPPED: 'default', CANCELLED: 'warning' })[status] || 'default'
}
function taskStatusText(status) {
  return ({ SUCCEEDED: '成功', FAILED: '失败', UNKNOWN: '未知', RUNNING: '执行中',
    PENDING: '待执行', SKIPPED: '跳过', CANCELLED: '已取消' })[status] || status || '—'
}
async function loadRunTasks(runId, token) {
  const seq = ++runTasksSeq
  runTasksLoading.value = true
  try {
    const data = await api.datasetRunTasks(datasetId, runId, { pageSize: 200 })
    if (seq !== runTasksSeq || token !== detailRequestToken
        || runDetail.value?.run?.id !== runId) return
    runTasks.value = data
    runTasksError.value = ''
  } catch (e) {
    if (seq === runTasksSeq && token === detailRequestToken) {
      runTasks.value = null
      runTasksError.value = e.message || String(e)
    }
  } finally {
    if (seq === runTasksSeq) runTasksLoading.value = false
  }
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    dataset.value = await api.datasetDetail(datasetId)
    if (!dataset.value) {
      loadError.value = '数据集不存在或已被删除'
      return
    }
    loadHealth()
    loadPairwiseHistory()
    loadQualitySummary()
    resumePolling()
  } catch (e) {
    dataset.value = null
    loadError.value = e?.message || '数据集不存在或已被删除'
  } finally { loading.value = false }
}
async function loadHealth() {
  healthLoading.value = true
  healthError.value = ''
  try {
    health.value = await api.datasetHealth(datasetId)
    healthHistory.value = await api.datasetHealthHistory(datasetId, { limit: 5 })
  } catch (e) {
    health.value = null
    healthHistory.value = []
    healthError.value = e?.message || '健康数据加载失败'
  } finally {
    healthLoading.value = false
  }
}
async function openBindPack() {
  bindPackId.value = health.value?.domainPackId || null
  showBindPack.value = true
  try {
    const rows = await api.domainPacks()
    packOptions.value = (rows || []).filter((p) => p.status === 'PUBLISHED')
      .map((p) => ({ label: `${p.packKey}@${p.version} · ${p.name}`, value: p.id }))
  } catch (e) {
    packOptions.value = []
    message.error('加载领域包失败：' + (e.message || e))
  }
}
async function bindPack() {
  bindSaving.value = true
  try {
    health.value = await api.bindDatasetDomainPack(datasetId, { domainPackId: bindPackId.value || null })
    showBindPack.value = false
    await loadHealth()
    message.success(bindPackId.value ? '已绑定领域包' : '已解绑领域包')
  } catch (e) {
    message.error('绑定失败：' + (e.message || e))
  } finally {
    bindSaving.value = false
  }
}
function healthStatusType(status) {
  return status === 'READY' ? 'success' : status === 'AT_RISK' ? 'warning' : 'default'
}
function healthStatusText(status) {
  return ({ READY: '蓝图就绪', AT_RISK: '需要关注', NO_BLUEPRINT: '未配置蓝图' })[status] || status
}
function openBlueprint() {
  blueprintText.value = JSON.stringify(health.value?.blueprint || {
    minItems: Math.max(10, health.value?.itemCount || 0),
    riskMin: { HIGH: 1 },
    splitMin: { HOLDOUT: 1 },
    purposeMin: { safety: 1 },
    maxStaleDays: 90
  }, null, 2)
  showBlueprint.value = true
}
async function quarantineStale() {
  staleQuarantining.value = true
  try {
    const result = await api.quarantineStaleDatasetItems(datasetId, { apply: true })
    message.success(`已软失效 ${result.appliedCount || 0} 条过期样本`)
    await Promise.all([load(), loadHealth()])
  } catch (e) {
    message.error('软失效失败：' + (e.message || e))
  } finally {
    staleQuarantining.value = false
  }
}

async function upgradePack() {
  packUpgrading.value = true
  try {
    const rows = await api.domainPacks()
    const currentKey = health.value?.domainPackKey
      || (rows.find((p) => p.id === health.value?.domainPackId) || {}).packKey
    const currentVersion = health.value?.domainPackVersion || '0.0.0'
    const candidates = (rows || []).filter((p) => p.packKey === currentKey
      && p.status === 'PUBLISHED' && compareVersion(p.version, currentVersion) > 0)
      .sort((a, b) => compareVersion(b.version, a.version))
    if (!candidates.length) {
      message.info('没有可升级的更高 PUBLISHED 版本')
      return
    }
    health.value = await api.upgradeDatasetDomainPack(datasetId, { domainPackId: candidates[0].id })
    message.success(`已升级到 ${currentKey}@${candidates[0].version}`)
    await loadHealth()
  } catch (e) {
    message.error('升级失败：' + (e.message || e))
  } finally {
    packUpgrading.value = false
  }
}
function compareVersion(a, b) {
  const x = String(a || '').split('.').map((n) => parseInt(n, 10) || 0)
  const y = String(b || '').split('.').map((n) => parseInt(n, 10) || 0)
  for (let i = 0; i < Math.max(x.length, y.length); i++) {
    if ((x[i] || 0) !== (y[i] || 0)) return (x[i] || 0) - (y[i] || 0)
  }
  return 0
}

async function saveBlueprint() {
  let body
  try {
    body = blueprintText.value.trim() ? JSON.parse(blueprintText.value) : {}
  } catch (e) {
    message.error('蓝图 JSON 无法解析')
    return
  }
  savingBlueprint.value = true
  try {
    health.value = await api.updateDatasetBlueprint(datasetId, body)
    showBlueprint.value = false
    message.success('蓝图已保存')
  } catch (e) {
    message.error('保存蓝图失败：' + (e.message || e))
  } finally {
    savingBlueprint.value = false
  }
}
function openAddItem() {
  editingItemId.value = null
  Object.assign(itemForm, {
    traceId: '', input: '', expectedOutput: '', purposeTags: '', riskLevel: 'UNASSIGNED',
    groupKey: '', split: 'UNASSIGNED', failureMode: '', schemaVersion: 1,
    retrievalContextText: '', expectedToolsText: '', turnsText: '', expectationsText: ''
  })
  showAddItem.value = true
}
function openEditItem(row) {
  editingItemId.value = row.id
  Object.assign(itemForm, {
    traceId: row.traceId || '', input: row.input || '', expectedOutput: row.expectedOutput || '',
    purposeTags: row.purposeTags || '', riskLevel: row.riskLevel || 'UNASSIGNED',
    groupKey: row.groupKey || '', split: row.split || 'UNASSIGNED', failureMode: row.failureMode || '',
    schemaVersion: row.schemaVersion || 1,
    retrievalContextText: row.retrievalContext ? JSON.stringify(row.retrievalContext, null, 2) : '',
    expectedToolsText: row.expectedTools ? JSON.stringify(row.expectedTools, null, 2) : '',
    turnsText: row.turns ? JSON.stringify(row.turns, null, 2) : '',
    expectationsText: row.expectations ? JSON.stringify(row.expectations, null, 2) : ''
  })
  showAddItem.value = true
}
function parseJsonField(text, label) {
  if (!text || !String(text).trim()) return undefined
  try {
    const value = JSON.parse(text)
    return value
  } catch (e) {
    throw new Error(`${label} 不是合法 JSON：${e.message}`)
  }
}
const showImport = ref(false), importLoading = ref(false), importResult = ref(null)
const importForm = reactive({ format: 'jsonl', dryRun: true, atomic: false, content: '' })
const importPlaceholder = computed(() => importForm.format === 'csv'
  ? 'input,expected_output,retrieval_context,purpose_tags,risk_level\n"q","a","[{""content"":""ctx""}]",regression,HIGH'
  : '{"input":"q","expected_output":"a"}\n{"input":"q2","schema_version":2,"retrieval_context":[{"content":"ctx"}]}')
function openImport() {
  importForm.content = ''
  importForm.dryRun = true
  importForm.atomic = false
  importResult.value = null
  showImport.value = true
}
async function doImport() {
  if (!importForm.content.trim()) { message.error('请粘贴要导入的内容'); return }
  importLoading.value = true
  try {
    const result = await api.importDatasetItems(datasetId, {
      format: importForm.format,
      dryRun: importForm.dryRun,
      atomic: importForm.atomic,
      content: importForm.content
    })
    importResult.value = result
    if (!importForm.dryRun && result.created > 0) {
      message.success('已导入 ' + result.created + ' 条')
      await load()
    }
  } catch (e) { message.error(e.message) }
  finally { importLoading.value = false }
}
async function exportItems(format) {
  try {
    const blob = await api.exportDatasetItems(datasetId, format)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `dataset-${datasetId}.${format}`
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
  } catch (e) { message.error('导出失败：' + e.message) }
}
const showSynthesis = ref(false), synthesisLoading = ref(false), synthesisJob = ref(null)
const synthesisCandidates = ref([])
let synthesisPollTimer = null
const synthesisForm = reactive({
  sourceType: 'SCRATCH', maxCandidates: 10, minQuality: 0.6, maxRetries: 2, scenario: '',
  documentsText: '', traceIdsText: ''
})
function resetSynthesisForm() {
  synthesisForm.sourceType = 'SCRATCH'
  synthesisForm.maxCandidates = 10
  synthesisForm.minQuality = 0.6
  synthesisForm.maxRetries = 2
  synthesisForm.scenario = ''
  synthesisForm.documentsText = ''
  synthesisForm.traceIdsText = ''
}
async function openSynthesis() {
  stopSynthesisPoll()
  synthesisJob.value = null
  synthesisCandidates.value = []
  resetSynthesisForm()
  showSynthesis.value = true
  // 恢复最近一次任务：关闭抽屉后仍能回来批准候选；没有任务时显示新建表单
  try {
    const jobs = await api.synthesisJobs(datasetId)
    if (!showSynthesis.value || synthesisJob.value) return
    if (jobs && jobs.length) {
      synthesisJob.value = jobs[0]
      startSynthesisPoll()
    }
  } catch (e) {
    if (showSynthesis.value) message.error('加载合成任务失败：' + e.message)
  }
}
function newSynthesis() {
  stopSynthesisPoll()
  synthesisJob.value = null
  synthesisCandidates.value = []
  resetSynthesisForm()
}
async function doCreateSynthesis() {
  synthesisLoading.value = true
  try {
    let source = {}
    if (synthesisForm.sourceType === 'SCRATCH') {
      source = { numGoldens: synthesisForm.maxCandidates }
    } else if (synthesisForm.sourceType === 'DOCUMENTS') {
      let documents
      try {
        documents = JSON.parse(synthesisForm.documentsText || '[]')
      } catch (e) {
        throw new Error('文档不是合法 JSON：' + e.message)
      }
      if (!Array.isArray(documents) || !documents.length) {
        throw new Error('DOCUMENTS 来源至少需要一条 {name, content}')
      }
      source = { documents }
    } else if (synthesisForm.sourceType === 'TRACE_CLUSTER') {
      const traceIds = synthesisForm.traceIdsText.split(/[,\n]/).map((v) => v.trim()).filter(Boolean)
      if (!traceIds.length) {
        throw new Error('TRACE_CLUSTER 来源至少需要一个 Trace ID')
      }
      source = { traceIds }
    } else {
      source = {}
    }
    const config = {
      maxCandidates: synthesisForm.maxCandidates,
      minQuality: synthesisForm.minQuality,
      maxRetries: synthesisForm.maxRetries
    }
    if (synthesisForm.scenario.trim()) {
      config.styling = { scenario: synthesisForm.scenario.trim() }
    }
    const result = await api.createSynthesisJob(datasetId, {
      sourceType: synthesisForm.sourceType, source, config
    }, 'syn-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8))
    synthesisJob.value = result.job
    message.success(result.created ? '合成任务已创建' : '复用已有合成任务')
    startSynthesisPoll()
  } catch (e) { message.error(e.message) }
  finally { synthesisLoading.value = false }
}
async function refreshSynthesis() {
  if (!synthesisJob.value) return
  try {
    const [candidates, job] = await Promise.all([
      api.synthesisCandidates(datasetId, synthesisJob.value.id),
      api.synthesisJob(datasetId, synthesisJob.value.id)
    ])
    synthesisJob.value = job
    synthesisCandidates.value = candidates || []
    const status = job.status
    if (status === 'COMPLETED' || status === 'PARTIAL_FAILED' || status === 'FAILED') {
      stopSynthesisPoll()
      if (status === 'COMPLETED') await load()
    }
  } catch (e) {
    // 后台轮询失败时停止定时器，避免每 2s 重复弹错；用户可手动「刷新」重试
    stopSynthesisPoll()
    message.error(e.message)
  }
}
function startSynthesisPoll() {
  stopSynthesisPoll()
  synthesisPollTimer = setInterval(refreshSynthesis, 2000)
  refreshSynthesis()
}
function stopSynthesisPoll() {
  if (synthesisPollTimer) { clearInterval(synthesisPollTimer); synthesisPollTimer = null }
}
// 抽屉关闭与路由离开都要停轮询：after-leave 在无动画/后台标签页下不保证触发
watch(showSynthesis, (open) => { if (!open) stopSynthesisPoll() })
onUnmounted(stopSynthesisPoll)
async function doApproveCandidate(row) {
  try {
    await api.approveSynthesisCandidate(datasetId, synthesisJob.value.id, row.id)
    message.success('已批准')
    await refreshSynthesis()
    // PARTIAL_FAILED / INTERRUPTED 下也可能有可批准候选；无论任务状态都刷新样本表
    await load()
  } catch (e) { message.error(e.message) }
}
async function doRejectCandidate(row) {
  try {
    await api.rejectSynthesisCandidate(datasetId, synthesisJob.value.id, row.id, 'REJECTED_BY_REVIEWER')
    message.success('已拒绝')
    await refreshSynthesis()
  } catch (e) { message.error(e.message) }
}
const synthesisStatusType = computed(() => {
  const s = synthesisJob.value?.status
  if (s === 'COMPLETED') return 'success'
  if (s === 'FAILED' || s === 'PARTIAL_FAILED') return 'error'
  if (s === 'RUNNING' || s === 'PENDING') return 'info'
  return 'default'
})
const synthesisCandidateColumns = [
  { title: '状态', key: 'status', width: 80, render: (r) => h(NTag, {
      size: 'tiny', bordered: false,
      type: r.status === 'DRAFT' ? 'info' : r.status === 'APPROVED' ? 'success' : 'warning'
    }, { default: () => enumText('candidateStatus', r.status) }) },
  { title: '输入', key: 'input', minWidth: 180, ellipsis: { tooltip: true },
    render: (r) => r.payload?.input || '—' },
  { title: '期望输出', key: 'expected', minWidth: 140, ellipsis: { tooltip: true },
    render: (r) => r.payload?.expected_output || '—' },
  { title: '质量', key: 'qualityScore', width: 65, align: 'right',
    render: (r) => h('span', { class: 'num' }, r.qualityScore == null ? '—' : String(r.qualityScore)) },
  { title: '过滤原因', key: 'filterReason', width: 130, ellipsis: { tooltip: true },
    render: (r) => (r.filterReason ? enumText('filterReason', r.filterReason) : '—') },
  { title: '操作', key: 'ops', width: 130, render: (r) => dataset.value?.contentRestricted
      ? h('span', { class: 'page-sub' }, '无内容权限')
      : r.status === 'DRAFT'
      ? h('div', { style: 'display:flex;gap:4px' }, [
          h(NButton, { size: 'tiny', type: 'primary', onClick: () => doApproveCandidate(r) },
            { default: () => '批准' }),
          h(NButton, { size: 'tiny', onClick: () => doRejectCandidate(r) },
            { default: () => '拒绝' })
        ])
      : h('span', { class: 'page-sub' }, r.createdItemId ? '已入数据集' : '—') }
]
async function doAddItem() {
  if (!itemForm.traceId.trim() && !itemForm.input.trim()) { message.error('traceId 与输入至少填一项'); return }
  saving.value = true
  try {
    const retrievalContext = parseJsonField(itemForm.retrievalContextText, '检索上下文')
    const expectedTools = parseJsonField(itemForm.expectedToolsText, '期望工具')
    const turns = parseJsonField(itemForm.turnsText, '多轮会话')
    const expectations = parseJsonField(itemForm.expectationsText, '样本级期望')
    const hasV2 = [retrievalContext, expectedTools, turns, expectations]
      .some((v) => v !== undefined && v !== null)
    const body = {
      traceId: itemForm.traceId.trim() || null,
      input: itemForm.input,
      expectedOutput: itemForm.expectedOutput,
      purposeTags: itemForm.purposeTags,
      riskLevel: itemForm.riskLevel,
      groupKey: itemForm.groupKey,
      split: itemForm.split,
      failureMode: itemForm.failureMode,
      schemaVersion: hasV2 ? 2 : 1,
      retrievalContext,
      expectedTools,
      turns,
      expectations
    }
    if (editingItemId.value) {
      await api.updateDatasetItem(datasetId, editingItemId.value, body)
      message.success('样本已保存')
    } else {
      await api.addDatasetItem(datasetId, body)
      message.success('样本已添加')
    }
    showAddItem.value = false
    editingItemId.value = null
    await load()
  } catch (e) { message.error(e.message) }
  finally { saving.value = false }
}
async function doInvalidateItem(row) {
  try {
    await api.invalidateDatasetItem(datasetId, row.id, { reason: '手动软失效' })
    message.success('样本已软失效')
    await load()
  } catch (e) {
    message.error('软失效失败：' + (e.message || e))
  }
}
async function doRestoreItem(row) {
  try {
    await api.restoreDatasetItem(datasetId, row.id)
    message.success('样本已恢复')
    await load()
  } catch (e) {
    message.error('恢复失败：' + (e.message || e))
  }
}

async function doDeleteItem(item) {
  try { await api.deleteDatasetItem(datasetId, item.id); await load() } catch (e) { message.error(e.message) }
}
async function freezeRevision() {
  freezingRevision.value = true
  try {
    const revision = await api.freezeDatasetRevision(datasetId, { label: 'manual' })
    message.success(`已冻结 v${revision.revision}（${revision.itemCount} 条）`)
    await load()
  } catch (e) {
    message.error('冻结修订失败：' + (e.message || e))
  } finally { freezingRevision.value = false }
}
async function loadEvaluators() {
  try {
    const page = await api.evaluators({ pageNumber: 1, pageSize: 100 })
    const rows = page.records || []
    evaluatorOptions.value = rows.map((e) => ({ label: `${e.evaluatorKey}（${e.type}）`, value: e.id }))
    evaluatorTypeByKey.value = Object.fromEntries(rows.map((e) => [e.evaluatorKey, e.type]))
    loadQualitySummary()
  } catch (e) { message.error('评估器加载失败：' + (e.message || e)) }
}
async function loadQualitySummary() {
  const run = latestRun.value
  latestGate.value = null
  latestGateUsable.value = false
  latestCalibration.value = null
  latestOpenCases.value = 0
  if (!run) return
  latestGate.value = await api.latestQualityGate(run.id).catch(() => null)
  if (latestGate.value?.id) {
    const verification = await api.verifyQualityGate(latestGate.value.id).catch(() => null)
    latestGateUsable.value = !!verification?.usable
  }
  const judgeKeys = (run.evaluatorKeys || []).filter((k) => evaluatorTypeByKey.value[k] === 'LLM_JUDGE')
  if (judgeKeys.length) {
    const cal = await api.judgeCalibration(datasetId, run.id, judgeKeys[0]).catch(() => null)
    latestCalibration.value = cal?.summary || null
  }
  const cases = await api.qualityCases({}).catch(() => [])
  latestOpenCases.value = (cases || []).filter((c) => c.datasetId === datasetId
    && c.status !== 'CLOSED' && c.status !== 'REJECTED').length
}
async function loadSuites() {
  try {
    const rows = await api.evalSuites()
    suiteOptions.value = (rows || []).map((s) => ({
      label: `${s.name || s.suiteKey} · v${s.version} · ${s.suiteKey}`,
      value: s.id,
      suite: s
    }))
  } catch (e) { message.error('评估套件加载失败：' + (e.message || e)) }
}
function onSuiteChange() {
  if (runForm.suiteId) runForm.evaluatorIds = []
}
const eligibleCount = computed(() => (dataset.value?.items || []).filter((i) =>
  i.status !== 'QUARANTINED' && (runForm.mode === 'REPLAY' ? i.input : i.traceId)).length)
const selectedEvaluatorCount = computed(() => runForm.suiteId
  ? (selectedSuite.value?.evaluatorKeys || []).length
  : runForm.evaluatorIds.length)
const canOpenRun = computed(() => !dataset.value?.contentRestricted
  && (dataset.value?.items || []).length > 0 && evaluatorOptions.value.length > 0)
const openRunBlocker = computed(() => {
  if (dataset.value?.contentRestricted) return '无内容查看权限，不能运行实验'
  if (!(dataset.value?.items || []).length) return '先添加样本'
  if (!evaluatorOptions.value.length) return '先创建评估器'
  return ''
})
const modalBlockReason = computed(() => {
  const items = dataset.value?.items || []
  if (!items.length) return '数据集中还没有样本：先添加样本，或从 Trace 收录。'
  if (items.length > 20) return '单批最多 20 条样本，请先拆分数据集。'
  if (!eligibleCount.value) {
    return runForm.mode === 'REPLAY'
      ? '当前没有带输入的可用样本；请补充样本或改用「评估已有 Trace」。'
      : '当前没有带 Trace 的可用样本；请从调用链路收录，或改用「重放」模式。'
  }
  if (!runForm.suiteId && !runForm.evaluatorIds.length) return '选择至少一个评估器（或一个评估套件）后即可运行。'
  return ''
})
const canSubmitRun = computed(() => !modalBlockReason.value)
async function openRun() {
  if (!evaluatorOptions.value.length) await loadEvaluators()
  if (!suiteOptions.value.length) await loadSuites()
  Object.assign(runForm, {
    mode: 'TRACE', suiteId: null, evaluatorIds: [], revisionId: '', candidateLabel: '',
    systemPrompt: '', model: '', maxExternalCalls: null, maxTokens: null, maxCost: null,
    reviewMode: 'SINGLE'
  })
  showRun.value = true
}
function openLatestRun() {
  if (latestRun.value) openRunDetail(latestRun.value.id)
}
function newIdempotencyKey() {
  if (globalThis.crypto && typeof globalThis.crypto.randomUUID === 'function') {
    return globalThis.crypto.randomUUID()
  }
  return 'run-' + Date.now() + '-' + Math.random().toString(36).slice(2, 12)
}
async function doRun() {
  if (!canSubmitRun.value) { message.error(modalBlockReason.value); return }
  creatingRun.value = true
  try {
    const payload = await api.runDatasetExperimentAsync(datasetId, {
      suiteId: runForm.suiteId || undefined,
      evaluatorIds: runForm.suiteId ? undefined : runForm.evaluatorIds,
      revisionId: runForm.revisionId || undefined,
      candidateLabel: runForm.candidateLabel || undefined,
      maxExternalCalls: runForm.maxExternalCalls ?? undefined,
      maxTokens: runForm.maxTokens ?? undefined,
      maxCost: runForm.maxCost ?? undefined,
      reviewMode: runForm.reviewMode || undefined,
      mode: runForm.mode,
      systemPrompt: runForm.systemPrompt || undefined,
      model: runForm.model || undefined
    }, newIdempotencyKey())
    const run = payload?.run || payload
    message.success(payload?.created === false
      ? '已存在相同请求的运行，继续查看原运行'
      : '运行已受理，正在后台执行')
    showRun.value = false
    upsertRun(run)
    startPolling(run.id)
  } catch (e) {
    message.error('创建运行失败：' + (e.message || e))
  } finally { creatingRun.value = false }
}
function upsertRun(run) {
  if (!dataset.value || !run) return
  const runs = dataset.value.runs || []
  const idx = runs.findIndex((r) => r.id === run.id)
  if (idx >= 0) runs.splice(idx, 1, run)
  else runs.unshift(run)
  dataset.value.runs = runs
}
async function openRunDetail(runId) {
  const token = ++detailRequestToken
  runDetail.value = null
  attemptChain.value = null
  runTasks.value = null
  runTasksLoading.value = false
  runTasksError.value = ''
  runTasksSeq++
  gate.value = null
  gateVerification.value = null
  gateHistory.value = []
  qualityModelReport.value = null
  costReport.value = null
  gateTemplateKey.value = null
  gateTemplateOptions.value = []
  calibrations.value = []
  calibrationEvaluatorKey.value = ''
  calibrationSavingId.value = null
  businessExperiments.value = []
  qualityCases.value = []
  selectedBusinessId.value = ''
  showBusinessCreate.value = false
  showBusinessEvidence.value = false
  // 立即打开抽屉给出反馈，主数据并行加载
  showRunDetail.value = true
  syncRunRoute(runId)
  runDetailLoading.value = true
  try {
    const [detail, chain, gateData, history] = await Promise.all([
      api.datasetRunDetail(datasetId, runId),
      api.datasetRunAttempts(datasetId, runId).catch((e) => {
        if (token === detailRequestToken) message.warning('尝试链加载失败：' + (e.message || e))
        return null
      }),
      api.latestQualityGate(runId).catch(() => null),
      api.qualityGateHistory(runId).catch(() => [])
    ])
    if (token !== detailRequestToken) return
    const historyVerification = Array.isArray(history)
      ? history.find((row) => row.gate?.id === gateData?.id)?.verification : null
    const verification = historyVerification
      || (gateData?.id ? await api.verifyQualityGate(gateData.id).catch(() => null) : null)
    if (token !== detailRequestToken) return
    runDetail.value = detail
    attemptChain.value = chain
    gate.value = gateData
    gateVerification.value = verification
    gateHistory.value = Array.isArray(history) ? history : []
    showRunDetail.value = true
    syncRunRoute(runId)
    loadRunTasks(runId, token)
    loadCalibration(detail, token)
    loadQualityModel(detail, runId, token)
    loadRunCost(runId, token)
    loadGateTemplates(detail, token)
    loadBusiness(runId, token)
    loadCases(runId, token)
    if (isActive(detail.run)) startPolling(runId)
  } catch (e) {
    if (token === detailRequestToken) {
      showRunDetail.value = false
      message.error('查询运行结果失败：' + (e.message || e))
    }
  } finally {
    if (token === detailRequestToken) runDetailLoading.value = false
  }
}
function judgeEvaluatorKeys(detail) {
  const keys = new Set()
  for (const item of detail?.items || []) {
    for (const r of item.evaluatorResults || []) {
      if (r.type === 'LLM_JUDGE' && r.evaluatorKey) keys.add(r.evaluatorKey)
    }
  }
  for (const key of detail?.run?.evaluatorKeys || []) {
    if (evaluatorTypeByKey.value[key] === 'LLM_JUDGE') keys.add(key)
  }
  if (!keys.size) {
    const single = judgeEvaluatorKey(detail)
    if (single) keys.add(single)
  }
  return [...keys]
}
function judgeEvaluatorKey(detail) {
  for (const item of detail?.items || []) {
    const found = (item.evaluatorResults || []).find((r) => r.type === 'LLM_JUDGE')
    if (found?.evaluatorKey) return found.evaluatorKey
  }
  return null
}
async function loadCalibration(detail, token) {
  const keys = judgeEvaluatorKeys(detail)
  if (!keys.length) {
    calibrations.value = []
    calibrationEvaluatorKey.value = ''
    return
  }
  calibrationLoading.value = true
  try {
    const results = await Promise.all(keys.map((key) =>
      api.judgeCalibration(datasetId, detail.run.id, key).catch(() => null)))
    if (token !== detailRequestToken) return
    calibrations.value = results.filter(Boolean)
    const wanted = keys.includes(calibrationEvaluatorKey.value)
      ? calibrationEvaluatorKey.value : keys[0]
    calibrationEvaluatorKey.value = calibrations.value.some(
      (c) => c.summary?.evaluatorKey === wanted)
      ? wanted : (calibrations.value[0]?.summary?.evaluatorKey || '')
  } finally {
    if (token === detailRequestToken) calibrationLoading.value = false
  }
}
async function saveCalibration(row, humanLabel) {
  if (!calibration.value?.summary?.evaluatorKey || !row?.itemId
      || row.reviewed || row.myReviewed || row.needsAdjudication) return
  calibrationSavingId.value = row.itemId
  const token = detailRequestToken
  try {
    await api.reviewJudgeCalibration(datasetId, runDetail.value.run.id, row.itemId, {
      evaluatorKey: calibration.value.summary.evaluatorKey, humanLabel
    })
    if (token !== detailRequestToken) return
    await loadCalibration(runDetail.value, token)
  } catch (e) {
    if (token === detailRequestToken) message.error('保存人工判定失败：' + (e.message || e))
  } finally {
    calibrationSavingId.value = null
  }
}
async function adjudicateCalibration(row, humanLabel) {
  if (!calibration.value?.summary?.evaluatorKey || !row?.itemId || !row.canAdjudicate) return
  calibrationSavingId.value = row.itemId
  const token = detailRequestToken
  try {
    await api.adjudicateJudgeCalibration(datasetId, runDetail.value.run.id, row.itemId, {
      evaluatorKey: calibration.value.summary.evaluatorKey, humanLabel
    })
    if (token !== detailRequestToken) return
    await loadCalibration(runDetail.value, token)
  } catch (e) {
    if (token === detailRequestToken) message.error('提交仲裁失败：' + (e.message || e))
  } finally {
    calibrationSavingId.value = null
  }
}
function exportCalibration() {
  if (calibrationBlind.value) return
  const rows = []
  for (const cal of calibrations.value) {
    for (const row of cal.items || []) {
      if (!row.reviewed) continue
      rows.push([
        cal.summary.evaluatorKey, row.itemId, row.judgeLabel || '', row.humanLabel || '',
        row.agreement == null ? '' : (row.agreement ? 'YES' : 'NO'),
        row.reviewedBy || '', row.reviewedAt || '', row.note || '',
        row.input || '', row.candidateOutput || '',
        row.primaryLabel || '', row.secondLabel || '', row.adjudicatedLabel || ''
      ])
    }
  }
  if (!rows.length) {
    message.warning('还没有已提交的人工判定')
    return
  }
  const csv = toCsv(['evaluatorKey', 'itemId', 'judgeLabel', 'humanLabel', 'agreement',
    'reviewedBy', 'reviewedAt', 'note', 'input', 'candidateOutput',
    'primaryLabel', 'secondLabel', 'adjudicatedLabel'], rows)
  downloadCsv(`emonlens-judge-calibration-${String(runDetail.value?.run?.id || '').slice(0, 8)}.csv`, csv)
  if (runDetail.value?.contentRestricted) {
    message.warning('当前账号无内容权限：导出不含样本正文')
  }
}
function calibrationStatusType(status) {
  return status === 'OK' ? 'success' : status === 'LOW_AGREEMENT' ? 'error'
    : status === 'INSUFFICIENT_SAMPLE' || status === 'IN_PROGRESS' ? 'warning' : 'default'
}
function calibrationStatusText(status) {
  return ({
    OK: '校准通过',
    LOW_AGREEMENT: '一致率过低',
    IN_PROGRESS: '盲审进行中',
    INSUFFICIENT_SAMPLE: '样本不足',
    NOT_REVIEWED: '未开始校准'
  })[status] || status
}
const calibrationBlind = computed(() => calibrations.value.some((c) =>
  Number(c.summary?.reviewable || 0) > 0 && !c.summary?.revealed))
const calibrationColumns = computed(() => [
  { title: '样本', key: 'input', minWidth: 210, ellipsis: { tooltip: true },
    render: (r) => h('span', { class: 'mono', style: 'font-size:11.5px' }, r.input || r.itemId) },
  { title: 'Judge（全部审完后）', key: 'judgeLabel', width: 130,
    render: (r) => r.reviewed
      ? h(NTag, { size: 'tiny', bordered: false,
          type: r.judgeLabel === 'PASS' ? 'success' : 'error' }, { default: () => enumText('evalLabel', r.judgeLabel) })
      : h('span', { class: 'page-sub' }, r.needsAdjudication ? '待仲裁' : '待提交') },
  { title: '评审进度', key: 'reviewCount', width: 86,
    render: (r) => h('span', { class: 'page-sub' },
      `${r.reviewCount || 0}/${r.requiredReviews || 1}`
      + (r.primaryLabel ? ` · ${r.primaryLabel}/${r.secondLabel || '—'}${r.adjudicatedLabel ? '/仲' + r.adjudicatedLabel : ''}` : '')) },
  { title: '人工判定', key: 'humanLabel', width: 180,
    render: (r) => {
      if (r.reviewed) {
        return h(NTag, { size: 'tiny', bordered: false,
          type: r.humanLabel === 'PASS' ? 'success' : 'error' }, { default: () => enumText('evalLabel', r.humanLabel) })
      }
      if (r.needsAdjudication) {
        if (!r.canAdjudicate) return h('span', { class: 'page-sub' }, '等待第三方仲裁')
        const loading = calibrationSavingId.value === r.itemId
        return h('div', { style: 'display:flex;gap:4px' }, [
          h(NButton, { size: 'tiny', type: 'success', loading,
            onClick: () => adjudicateCalibration(r, 'PASS') }, { default: () => '仲裁为通过' }),
          h(NButton, { size: 'tiny', type: 'error', loading,
            onClick: () => adjudicateCalibration(r, 'FAIL') }, { default: () => '仲裁为未通过' })
        ])
      }
      if (r.myReviewed) {
        return h(NTag, { size: 'tiny', bordered: false,
          type: r.myLabel === 'PASS' ? 'success' : 'error' },
          { default: () => `${enumText('evalLabel', r.myLabel)} · 已提交` })
      }
      return h(NSelect, {
        size: 'tiny', value: null, placeholder: '选择',
        loading: calibrationSavingId.value === r.itemId,
        options: [{ label: '通过', value: 'PASS' }, { label: '未通过', value: 'FAIL' }],
        onUpdateValue: (v) => saveCalibration(r, v)
      })
    } },
  { title: '一致', key: 'agreement', width: 65,
    render: (r) => r.agreement == null ? '—' : h(NTag, { size: 'tiny', bordered: false,
      type: r.agreement ? 'success' : 'error' }, { default: () => r.agreement ? '是' : '否' }) },
  { title: '理由', key: 'judgeReason', minWidth: 200, ellipsis: { tooltip: true },
    render: (r) => h('span', { style: 'font-size:11.5px;color:var(--text-secondary)' }, r.judgeReason || '—') }
])
function gateType(decision) {
  return decision === 'PASS' ? 'success' : decision === 'FAIL' ? 'error' : 'warning'
}
function budgetRemaining(run) {
  const b = run?.configSnapshot?.budget
  if (!b || b.maxExternalCalls == null) return 0
  return Math.max(0, Number(b.maxExternalCalls) - Number(run.externalCallsReserved || 0))
}
function canResumeRun(run) {
  if (!run || run.status !== 'INTERRUPTED') return false
  const list = attemptChain.value?.attempts
  if (!list || list.length === 0) return false
  return list[list.length - 1].id === run.id
}
function attemptTagType(status) {
  if (status === 'COMPLETED') return 'success'
  if (status === 'INTERRUPTED' || status === 'PENDING' || status === 'RUNNING') return 'warning'
  return 'error'
}
function canRetryRun(run) {
  if (!run || !['PARTIAL_FAILED', 'FAILED'].includes(run.status)
      || Number(run.errorCount || 0) <= 0) return false
  const list = attemptChain.value?.attempts
  if (!list || list.length === 0) return false
  return list[list.length - 1].id === run.id
}
async function retryRun() {
  const run = runDetail.value?.run
  if (!run?.id || !canRetryRun(run)) return
  retryLoading.value = true
  try {
    const next = await api.retryDatasetRun(datasetId, run.id, `retry-${run.id}-${run.attempt || 1}`)
    message.success(`已切换到第 ${next.attempt} 次尝试，正在重试 ERROR 样本`)
    await load()
    await openRunDetail(next.id)
  } catch (e) {
    message.error('重试失败项失败：' + (e.message || e))
  } finally {
    retryLoading.value = false
  }
}

async function resumeRun() {
  const run = runDetail.value?.run
  if (!run?.id || !canResumeRun(run)) return
  resumeLoading.value = true
  try {
    const next = await api.resumeDatasetRun(datasetId, run.id, `resume-${run.id}-${run.attempt || 1}`)
    message.success(`已切换到第 ${next.attempt} 次尝试，正在补齐未执行样本`)
    await load()
    await openRunDetail(next.id)
  } catch (e) {
    message.error('断点续跑失败：' + (e.message || e))
  } finally {
    resumeLoading.value = false
  }
}

async function cancelRun() {
  const run = runDetail.value?.run
  if (!run?.id || !isActive(run)) return
  cancelLoading.value = true
  try {
    const res = await api.cancelDatasetRun(datasetId, run.id)
    upsertRun(res.run)
    stopPolling()
    runDetail.value = await api.datasetRunDetail(datasetId, run.id)
    notifyTerminal(res.run)
    await loadCompare()
  } catch (e) {
    message.error('取消运行失败：' + (e.message || e))
  } finally {
    cancelLoading.value = false
  }
}

async function loadBusiness(runId, token) {
  try {
    const rows = await api.businessExperiments({ runId })
    if (token !== detailRequestToken) return
    businessExperiments.value = Array.isArray(rows) ? rows : []
    if (!selectedBusinessId.value && businessExperiments.value.length) {
      selectedBusinessId.value = businessExperiments.value[0].id
    }
  } catch (e) {
    if (token === detailRequestToken) businessExperiments.value = []
  }
}
function businessDecisionType(decision) {
  return decision === 'POSITIVE' ? 'success' : decision === 'NEGATIVE' ? 'error'
    : decision === 'BLOCKED' ? 'warning' : 'default'
}
async function openCredentials() {
  showCredentials.value = true
  try {
    credentials.value = await api.businessCredentials()
  } catch (e) {
    credentials.value = []
    message.error('加载签名凭据失败：' + (e.message || e))
  }
}
async function createCredential() {
  if (!credentialForm.keyId.trim() || !credentialForm.publicKey.trim()) {
    message.error('Key ID 与公钥必填')
    return
  }
  savingCredential.value = true
  try {
    await api.createBusinessCredential({
      keyId: credentialForm.keyId.trim(),
      source: credentialForm.source.trim() || null,
      name: credentialForm.name.trim() || null,
      publicKey: credentialForm.publicKey.trim()
    })
    credentialForm.keyId = ''
    credentialForm.source = ''
    credentialForm.name = ''
    credentialForm.publicKey = ''
    credentials.value = await api.businessCredentials()
    message.success('签名凭据已登记')
  } catch (e) {
    message.error('登记失败：' + (e.message || e))
  } finally {
    savingCredential.value = false
  }
}
async function revokeCredential(row) {
  try {
    await api.revokeBusinessCredential(row.id)
    credentials.value = await api.businessCredentials()
    message.success('凭据已吊销')
  } catch (e) {
    message.error('吊销失败：' + (e.message || e))
  }
}

function openBusinessCreate() {
  Object.assign(businessForm, { source: 'external', externalExperimentId: '', designType: 'RANDOMIZED',
    primaryMetric: '', minSampleSize: '100', target: '' })
  showBusinessCreate.value = true
}
async function createBusiness() {
  if (!businessForm.source.trim() || !businessForm.externalExperimentId.trim()
      || !businessForm.primaryMetric.trim()) {
    message.error('source、外部实验 ID、主指标必填')
    return
  }
  savingBusiness.value = true
  try {
    const created = await api.createBusinessExperiment({
      source: businessForm.source.trim(),
      externalExperimentId: businessForm.externalExperimentId.trim(),
      designType: businessForm.designType,
      primaryMetric: businessForm.primaryMetric.trim(),
      minSampleSize: Number(businessForm.minSampleSize || 0),
      target: businessForm.target.trim() || null,
      runId: runDetail.value?.run?.id || null,
      candidateFingerprint: runDetail.value?.run?.candidateFingerprint || null
    })
    businessExperiments.value = [created, ...businessExperiments.value.filter((e) => e.id !== created.id)]
    selectedBusinessId.value = created.id
    showBusinessCreate.value = false
    message.success('业务实验已绑定')
  } catch (e) {
    message.error('绑定失败：' + (e.message || e))
  } finally {
    savingBusiness.value = false
  }
}
function openBusinessEvidence() {
  businessEvidenceForm.reportVersion = ''
  showBusinessEvidence.value = true
}
async function importBusinessEvidence() {
  if (!businessEvidenceForm.reportVersion.trim()) {
    message.error('reportVersion 必填')
    return
  }
  let metrics, checks
  try {
    metrics = JSON.parse(businessEvidenceForm.metrics || '{}')
    checks = JSON.parse(businessEvidenceForm.qualityChecks || '{}')
  } catch (e) {
    message.error('metrics/qualityChecks JSON 无法解析')
    return
  }
  savingBusiness.value = true
  try {
    const detail = await api.importBusinessEvidence(selectedBusinessId.value, {
      reportVersion: businessEvidenceForm.reportVersion.trim(), metrics, qualityChecks: checks
    })
    const idx = businessExperiments.value.findIndex((e) => e.id === detail.id)
    if (idx >= 0) businessExperiments.value.splice(idx, 1, detail)
    showBusinessEvidence.value = false
    message.success('证据版本已导入')
  } catch (e) {
    message.error('导入失败：' + (e.message || e))
  } finally {
    savingBusiness.value = false
  }
}
async function loadRunCost(runId, token) {
  try {
    const report = await api.datasetRunCost(datasetId, runId)
    if (token !== detailRequestToken) return
    costReport.value = report
  } catch (e) {
    if (token === detailRequestToken) costReport.value = null
  }
}
function costReason(reason) {
  if (reason === 'MULTIPLE_MODELS') return '本次 run 使用多个模型，无法给出单一总价'
  if (reason === 'PRICE_MISSING') return '未配置该模型价格'
  if (reason === 'NO_MODEL') return '无法识别模型'
  return reason || '无可用用量'
}

async function loadQualityModel(detail, runId, token) {
  try {
    const report = await api.datasetRunQualityModel(datasetId, runId)
    if (token !== detailRequestToken) return
    qualityModelReport.value = report
  } catch (e) {
    if (token === detailRequestToken) qualityModelReport.value = null
  }
}

async function loadGateTemplates(detail, token) {
  const packId = detail?.run?.domainPackId || detail?.run?.configSnapshot?.domainPackId
  if (!packId) {
    gateTemplateOptions.value = []
    return
  }
  try {
    const pack = await api.domainPack(packId)
    if (token !== detailRequestToken) return
    const templates = pack?.manifest?.gateTemplates || []
    gateTemplateOptions.value = templates.map((t) => ({
      label: `${t.name || t.key}（${t.key}）`, value: t.key
    }))
  } catch (e) {
    gateTemplateOptions.value = []
  }
}

async function createGate() {
  const runId = runDetail.value?.run?.id
  if (!runId) return
  const token = detailRequestToken
  gateLoading.value = true
  try {
    const body = gateTemplateKey.value
      ? { runId, gateTemplateKey: gateTemplateKey.value }
      : { runId, minCoverage: 90, maxFail: 0, maxUnknown: 0, validHours: 24 }
    const created = await api.createQualityGate(body)
    if (token !== detailRequestToken || runDetail.value?.run?.id !== runId) return
    const history = await api.qualityGateHistory(runId).catch(() => [])
    if (token !== detailRequestToken || runDetail.value?.run?.id !== runId) return
    gate.value = created
    gateHistory.value = Array.isArray(history) ? history : []
    gateVerification.value = gateHistory.value
      .find((row) => row.gate?.id === created.id)?.verification
      || await api.verifyQualityGate(created.id).catch(() => null)
    message.success(`准入结论：${created.decision}`)
  } catch (e) {
    message.error('生成准入结论失败：' + (e.message || e))
  } finally { gateLoading.value = false }
}

// ---------- 后台运行轮询 ----------
function startPolling(runId) {
  stopPolling()
  const token = ++pollToken
  const tick = async () => {
    if (token !== pollToken) return
    try {
      const detail = await api.datasetRunDetail(datasetId, runId)
      // await 期间可能已切换 run/停止轮询：旧响应不得覆盖终态
      if (token !== pollToken) return
      pollError.value = ''
      upsertRun(detail.run)
      if (showRunDetail.value && runDetail.value?.run?.id === runId) {
        runDetail.value = detail
        loadRunTasks(runId, detailRequestToken)
        if (!isActive(detail.run)) loadCalibration(detail, detailRequestToken)
      }
      if (!isActive(detail.run)) {
        stopPolling()
        notifyTerminal(detail.run)
        await loadCompare()
        return
      }
    } catch (e) {
      if (token !== pollToken) return
      pollError.value = '运行已受理，但查询进度失败：' + (e.message || e)
    }
    if (token === pollToken) pollTimer = setTimeout(tick, 1500)
  }
  tick()
}
function stopPolling() {
  pollToken++
  if (pollTimer) clearTimeout(pollTimer)
  pollTimer = null
}
function resumePolling() {
  const active = (dataset.value?.runs || []).find(isActive)
  if (active) startPolling(active.id)
}
function notifyTerminal(run) {
  if (!run) return
  const counts = `PASS ${run.passCount ?? 0} / FAIL ${run.failCount ?? 0} / UNKNOWN ${run.unknownCount ?? 0} / ERROR ${run.errorCount ?? 0}`
  if (run.status === 'COMPLETED') {
    message.success(`执行完成：${counts}`)
  } else if (run.status === 'PARTIAL_FAILED') {
    message.warning(`部分样本执行失败：${counts}`)
  } else if (run.status === 'FAILED') {
    message.error(`评估执行失败：${counts}`)
  } else if (run.status === 'INTERRUPTED') {
    message.warning('运行已中断：可点「断点续跑」补齐未执行样本')
  } else if (run.status === 'CANCELLED') {
    message.info('运行已取消：已产生结果保留，未执行项不会继续运行')
  }
}
onUnmounted(stopPolling)

// ---------- 通过率与覆盖率趋势 ----------
const trendBars = computed(() => {
  const runs = (dataset.value?.runs || []).slice().reverse()
  return runs.map((r, i) => {
    const rate = r.passRate
    const text = rate == null ? '—' : fmtPercent(rate)
    return {
      idx: runs.length - i,
      rateText: text,
      height: rate == null ? 0 : rate,
      color: r.status === 'FAILED' || r.status === 'INTERRUPTED' ? 'var(--error)'
        : r.status === 'CANCELLED' ? 'var(--text-muted)'
        : rate == null ? 'var(--text-muted)'
        : rate >= 80 ? 'var(--success)' : rate >= 50 ? 'var(--warn)' : 'var(--error)',
      tip: `#${runs.length - i} ${evaluatorSummary(r)} · ${statusText(r.status)} · `
        + `PASS ${r.passCount ?? 0} / FAIL ${r.failCount ?? 0} / UNKNOWN ${r.unknownCount ?? 0} / ERROR ${r.errorCount ?? 0}`
        + ` · 通过率 ${text} · 覆盖率 ${fmtPercent(r.coverage)} · ${fmtTs(r.startedAt)}`
    }
  })
})

// ---------- 实验对比（按 itemId 对齐，包含未判定/缺失） ----------
const compareA = ref(null), compareB = ref(null)
const compareDetails = ref({ a: null, b: null })
const runPickOptions = computed(() => {
  const runs = dataset.value?.runs || []
  return runs.map((r, i) => ({
    label: `#${runs.length - i} ${evaluatorSummary(r)}${r.runMode === 'REPLAY' ? '·重放' : ''} ${statusText(r.status)} ${String(r.startedAt || '').slice(5, 16).replace('T', ' ')}`,
    value: r.id
  }))
})
function runNoOf(id) {
  const runs = dataset.value?.runs || []
  const i = runs.findIndex((r) => r.id === id)
  return i >= 0 ? '#' + (runs.length - i) : ''
}
async function loadCases(runId, token) {
  try {
    const rows = await api.qualityCases({ runId })
    if (token !== detailRequestToken) return
    qualityCases.value = Array.isArray(rows) ? rows : []
  } catch (e) {
    if (token === detailRequestToken) qualityCases.value = []
  }
}
function caseStatusType(status) {
  return status === 'CLOSED' ? 'success' : status === 'FIXED' ? 'info'
    : status === 'REJECTED' ? 'error' : status === 'OPEN' ? 'warning' : 'default'
}
function openCaseCreate() {
  Object.assign(caseForm, { title: '', phenomenon: '', rootCauseCategory: 'UNKNOWN', owner: '',
    fixFingerprint: '', fixNote: '', regressionRunId: '' })
  showCaseCreate.value = true
}
async function createCase() {
  if (!caseForm.title.trim() || !caseForm.phenomenon.trim()) {
    message.error('标题和现象必填')
    return
  }
  caseSaving.value = true
  try {
    const created = await api.createQualityCase({
      sourceRunId: runDetail.value?.run?.id,
      datasetId: datasetId,
      title: caseForm.title.trim(),
      phenomenon: caseForm.phenomenon.trim(),
      rootCauseCategory: caseForm.rootCauseCategory,
      owner: caseForm.owner.trim() || null
    })
    qualityCases.value = [created, ...qualityCases.value]
    showCaseCreate.value = false
    message.success('案例已登记')
  } catch (e) {
    message.error('登记失败：' + (e.message || e))
  } finally {
    caseSaving.value = false
  }
}
async function advanceCase(c, status) {
  try {
    const updated = await api.patchQualityCase(c.id, { status })
    replaceCase(updated)
  } catch (e) {
    message.error('更新案例失败：' + (e.message || e))
  }
}
function openCaseFix(c) {
  Object.assign(caseForm, { fixFingerprint: '', fixNote: '', regressionRunId: '' })
  caseForm.caseId = c.id
  showCaseFix.value = true
}
async function markCaseFixed() {
  if (!caseForm.fixFingerprint.trim()) { message.error('修复指纹必填'); return }
  caseSaving.value = true
  try {
    const updated = await api.patchQualityCase(caseForm.caseId, {
      status: 'FIXED', fixFingerprint: caseForm.fixFingerprint.trim(), fixNote: caseForm.fixNote.trim()
    })
    replaceCase(updated)
    showCaseFix.value = false
    message.success('已标记修复')
  } catch (e) {
    message.error('更新失败：' + (e.message || e))
  } finally {
    caseSaving.value = false
  }
}
function openCaseClose(c) {
  caseForm.caseId = c.id
  caseForm.regressionRunId = c.regressionRunId || ''
  showCaseClose.value = true
}
async function closeCase() {
  if (!caseForm.regressionRunId.trim()) { message.error('请填写回归 run ID'); return }
  caseSaving.value = true
  try {
    await api.linkQualityCaseRegression(caseForm.caseId, { runId: caseForm.regressionRunId.trim() })
    const closed = await api.closeQualityCase(caseForm.caseId)
    replaceCase(closed)
    showCaseClose.value = false
    message.success('案例已关闭')
  } catch (e) {
    message.error('关闭失败：' + (e.message || e))
  } finally {
    caseSaving.value = false
  }
}
function replaceCase(updated) {
  const idx = qualityCases.value.findIndex((x) => x.id === updated.id)
  if (idx >= 0) qualityCases.value.splice(idx, 1, updated)
}
function openPairwiseOptions() {
  if (!compareA.value || !compareB.value) return
  if (compareA.value === compareB.value) {
    message.warning('A/B 选择了同一次运行，请选择两次不同的运行。')
    return
  }
  if (comparability.value?.level === 'error') {
    message.warning(comparability.value.message)
    return
  }
  pairwiseForm.judgeModelsText = ''
  pairwiseForm.samplesPerDirection = 1
  pairwiseForm.positionRandomized = false
  pairwiseForm.positionSeed = null
  showPairwiseOptions.value = true
}
async function createPairwise() {
  if (!compareA.value || !compareB.value) return
  pairwiseLoading.value = true
  try {
    const body = {
      runAId: compareA.value,
      runBId: compareB.value,
      samplesPerDirection: pairwiseForm.samplesPerDirection || 1
    }
    const models = pairwiseForm.judgeModelsText.split(',').map((v) => v.trim()).filter(Boolean)
    if (models.length) body.judgeModels = models
    if (pairwiseForm.positionRandomized) {
      body.positionRandomized = true
      if (pairwiseForm.positionSeed) body.positionSeed = pairwiseForm.positionSeed
    }
    const detail = await api.createPairwiseEvaluation(datasetId, body)
    pairwise.value = detail
    showPairwiseOptions.value = false
    loadPairwiseHistory()
    if (detail.unknownCount) {
      message.warning(`成对比较完成：${detail.unknownCount} 条无法判定`)
    } else {
      message.success('成对比较完成')
    }
  } catch (e) {
    message.error('成对比较失败：' + (e.message || e))
  } finally {
    pairwiseLoading.value = false
  }
}
async function loadPairwiseHistory() {
  pairwiseHistoryLoading.value = true
  try {
    pairwiseHistory.value = await api.pairwiseEvaluations(datasetId, {}) || []
  } catch (e) {
    pairwiseHistory.value = []
  } finally { pairwiseHistoryLoading.value = false }
}
async function openPairwiseDetail(row) {
  try {
    pairwiseDetail.value = await api.pairwiseEvaluationDetail(datasetId, row.id)
    showPairwiseDetail.value = true
  } catch (e) {
    message.error('加载成对比较详情失败：' + (e.message || e))
  }
}
async function openHumanReview() {
  if (!pairwise.value?.items) return
  try {
    pairwise.value.calibration = await api.pairwiseCalibration(datasetId, pairwise.value.id)
  } catch (e) {
    message.error('加载人工校准失败：' + (e.message || e))
    return
  }
  const existing = pairwise.value.calibration?.reviews || []
  const byItem = Object.fromEntries(existing.map((r) => [r.itemId, r]))
  for (const item of pairwise.value.items) {
    humanDrafts[item.itemId] = byItem[item.itemId]?.preference || null
    humanNotes[item.itemId] = byItem[item.itemId]?.note || ''
  }
  showHumanReview.value = true
}
async function saveHumanReviews() {
  if (!pairwise.value) return
  humanSaving.value = true
  try {
    let saved = 0
    for (const item of pairwise.value.items || []) {
      const preference = humanDrafts[item.itemId]
      if (!preference) continue
      await api.reviewPairwise(datasetId, pairwise.value.id, {
        itemId: item.itemId, preference, note: humanNotes[item.itemId] || null
      })
      saved++
    }
    pairwise.value.calibration = await api.pairwiseCalibration(datasetId, pairwise.value.id)
    showHumanReview.value = false
    message.success(`已保存 ${saved} 条人工偏好`)
  } catch (e) {
    message.error('保存人工校准失败：' + (e.message || e))
  } finally {
    humanSaving.value = false
  }
}

async function loadCompare() {
  if (!compareA.value || !compareB.value) { compareDetails.value = { a: null, b: null }; return }
  const [a, b] = await Promise.all([
    api.datasetRunDetail(datasetId, compareA.value).catch(() => null),
    api.datasetRunDetail(datasetId, compareB.value).catch(() => null)
  ])
  compareDetails.value = { a, b }
}
watch([compareA, compareB], ([a, b]) => {
  pairwise.value = null
  // runs 最新在前；A 固定为较早一次、B 为较新一次，选反时自动交换，避免“改善/退化”被倒置
  if (a && b) {
    const runs = dataset.value?.runs || []
    const ia = runs.findIndex((r) => r.id === a)
    const ib = runs.findIndex((r) => r.id === b)
    if (ia >= 0 && ib >= 0 && ia < ib) {
      compareA.value = b
      compareB.value = a
      return
    }
  }
  loadCompare()
})
watch(dataset, (d) => {
  const runs = d?.runs || []
  if (runs.length >= 2 && !compareA.value && !compareB.value) {
    compareA.value = runs[1].id
    compareB.value = runs[0].id
    loadCompare()
  }
}, { deep: false })

const comparability = computed(() => {
  const { a, b } = compareDetails.value
  if (!a?.run || !b?.run) return null
  if (a.run.id === b.run.id) {
    return { level: 'error', message: 'A/B 选择了同一次运行，无法比较。' }
  }
  const ca = a.run.configSnapshot, cb = b.run.configSnapshot
  if (!ca || !cb) {
    return { level: 'warning', message: '历史记录未保存快照，无法确认两次运行的样本、评估标准与内容处理规则是否一致；不能据此宣称质量改善。' }
  }
  if (ca.sampleSetHash !== cb.sampleSetHash) {
    return { level: 'error', message: '样本输入/参考内容已变化（sampleSetHash 不同），不可直接比较；请用同一批样本复跑。' }
  }
  const fingerprints = (cfg) => (cfg?.evaluators || (cfg?.evaluator ? [cfg.evaluator] : []))
    .map((e) => e.fingerprint || '').join(',')
  if (fingerprints(ca) !== fingerprints(cb)) {
    return { level: 'error', message: '评估标准已变化（评估器配置/版本指纹不同），不可直接比较；不能把差异归因于 Prompt/模型改动。' }
  }
  if (ca.contentProcessing?.version !== cb.contentProcessing?.version
      || ca.contentProcessing?.exactMatchNormalization !== cb.contentProcessing?.exactMatchNormalization) {
    return { level: 'error', message: '内容处理规则不同，不可直接比较。' }
  }
  const generationChanged = JSON.stringify(ca.generation || {}) !== JSON.stringify(cb.generation || {})
  if (generationChanged) {
    return { level: 'info', message: '样本、评估标准与内容处理规则一致；生成 Prompt/模型不同，作为本次被测变量。' }
  }
  return { level: 'success', message: '样本输入/参考、评估器配置与内容处理规则一致，可比较。' }
})
function changeOf(la, lb) {
  if (!la || !lb) return 'missing'
  const judged = (l) => l === 'PASS' || l === 'FAIL'
  if (!judged(la) || !judged(lb)) return 'undetermined'
  if (la === lb) return 'same'
  return la === 'PASS' ? 'regressed' : 'improved'
}
const compareColumns = computed(() => {
  const { a, b } = compareDetails.value
  if (!a?.run || !b?.run) return []
  return [
    { title: '样本', key: 'itemId', width: 110, render: (r) => h('span', { class: 'mono', style: 'font-size:11px' }, String(r.itemId || '').slice(0, 8)) },
    { title: `A · ${runNoOf(compareA.value)} ${a.run.evaluatorKey}`, key: 'a', minWidth: 190, render: (r) => r.a ? cellOf(r.a) : missingCell() },
    { title: `B · ${runNoOf(compareB.value)} ${b.run.evaluatorKey}`, key: 'b', minWidth: 190, render: (r) => r.b ? cellOf(r.b) : missingCell() },
    { title: '变化', key: 'change', width: 105, render: (r) => {
        if (r.change === 'regressed') return h(NTag, { size: 'tiny', bordered: false, type: 'error' }, { default: () => '↓ 退化' })
        if (r.change === 'improved') return h(NTag, { size: 'tiny', bordered: false, type: 'success' }, { default: () => '↑ 改善' })
        if (r.change === 'same') return h('span', { style: 'color:var(--text-muted);font-size:12px' }, '持平')
        if (r.change === 'undetermined') return h(NTag, { size: 'tiny', bordered: false, type: 'warning' }, { default: () => '未判定' })
        return h('span', { style: 'color:var(--text-muted);font-size:12px' }, '仅一侧')
      } }
  ]
})
function missingCell() {
  return h('span', { style: 'color:var(--text-muted);font-size:12px' }, '未运行/缺失')
}
function cellOf(it) {
  return h('span', {}, [
    labelTag(it.label || '未完成'),
    ' ',
    h('span', { class: 'num' }, it.score == null ? '' : String(it.score)),
    it.resultDetails?.reasonCode
      ? h('span', {
          class: 'mono',
          style: 'color:var(--text-muted);font-size:11px;margin-left:6px',
          title: it.resultDetails.reasonCode
        }, enumText('evalReason', it.resultDetails.reasonCode))
      : null
  ])
}
const compareRows = computed(() => {
  const { a, b } = compareDetails.value
  if (!a?.run || !b?.run) return []
  const byItem = new Map()
  for (const it of a.items || []) {
    byItem.set(it.itemId, { itemId: it.itemId, a: it, b: null, order: it.sampleSnapshot?.itemOrder ?? 9999 })
  }
  for (const it of b.items || []) {
    const e = byItem.get(it.itemId) || { itemId: it.itemId, a: null, b: null, order: it.sampleSnapshot?.itemOrder ?? 9999 }
    e.b = it
    byItem.set(it.itemId, e)
  }
  return [...byItem.values()]
    .sort((x, y) => (x.order - y.order) || String(x.itemId).localeCompare(String(y.itemId)))
    .map((e) => ({ ...e, change: changeOf(e.a?.label, e.b?.label) }))
})
const diffStats = computed(() => {
  const rows = compareRows.value
  if (!rows.length) return null
  return {
    improved: rows.filter((r) => r.change === 'improved').length,
    regressed: rows.filter((r) => r.change === 'regressed').length,
    same: rows.filter((r) => r.change === 'same').length,
    undetermined: rows.filter((r) => r.change === 'undetermined').length,
    missing: rows.filter((r) => r.change === 'missing').length
  }
})

// ---------- 导出 ----------
function exportRun() {
  const detail = runDetail.value
  if (!detail?.run) return
  const run = detail.run
  const items = detail.items || []
  const cfg = run.configSnapshot || {}
  const gen = cfg.generation || {}
  const evaluator = cfg.evaluator || {}
  const restricted = !!detail.contentRestricted
  const headers = [
    'runId', 'runStatus', 'datasetRevision', 'candidateLabel', 'evaluatorKeys', 'evaluatorFingerprints', 'runMode', 'model', 'systemPrompt',
    'itemId', 'traceId', 'sourceTraceId', 'input', 'expectedOutput', 'candidateOutput',
    'label', 'score', 'reason', 'reasonCode', 'evaluatorResults', 'usage', 'errorCode'
  ]
  const rows = items.map((it) => {
    const s = it.sampleSnapshot || {}
    const d = it.resultDetails || {}
    const usage = d.usage
      ? (d.usage.known ? `total=${d.usage.totalTokens};prompt=${d.usage.promptTokens};completion=${d.usage.completionTokens}`
        : 'unknown')
      : 'unknown'
    return [
      run.id, run.status, run.datasetRevisionId || '', run.candidateLabel || '',
      (run.evaluatorKeys || [run.evaluatorKey || '']).join('|'),
      (cfg.evaluators || [evaluator]).map((e) => e.fingerprint || '').join('|'), run.runMode || '',
      run.model || '', gen.systemPrompt || '',
      it.itemId || '', it.traceId || '', it.sourceTraceId || '',
      restricted ? '' : (s.input || s.traceInput || ''),
      restricted ? '' : (s.expectedOutput || ''),
      restricted ? '' : (d.candidateOutput ?? it.replayOutput ?? ''),
      it.label || '', it.score ?? '', it.reason || '', d.reasonCode || '',
      (it.evaluatorResults || d.evaluators || []).map((e) => `${e.evaluatorKey}=${e.label}`).join('|'),
      usage, d.errorCode || ''
    ]
  })
  const csv = toCsv(headers, rows)
  downloadCsv(`emonlens-dataset-${run.datasetId || datasetId}-run-${String(run.id || '').slice(0, 8)}.csv`, csv)
  if (restricted) {
    message.warning('当前账号无内容权限：已导出不含正文与判定理由的结果')
  } else if (run.legacyNoSnapshot) {
    message.warning('历史记录未保存快照：导出不包含当时的样本与配置')
  }
}
function syncRunRoute(runId) {
  if (!runId) return
  if (String(route.params.runId || '') === String(runId)) return
  router.replace({ name: 'dataset-run-detail', params: { id: datasetId, runId } })
}
function onRunDrawerShow(show) {
  showRunDetail.value = show
  if (!show && route.params.runId) {
    router.replace({ name: 'dataset-detail', params: { id: datasetId } })
  }
}
async function copyRunLink() {
  const runId = runDetail.value?.run?.id
  if (!runId) return
  const url = `${window.location.origin}/datasets/${encodeURIComponent(datasetId)}/runs/${encodeURIComponent(runId)}`
  try {
    await navigator.clipboard.writeText(url)
    message.success('运行报告链接已复制')
  } catch (e) {
    message.info(url)
  }
}
watch(() => route.params.runId, (runId) => {
  if (!runId) return
  if (runDetail.value?.run?.id === String(runId) && showRunDetail.value) return
  openRunDetail(String(runId))
})
onMounted(async () => {
  await load()
  loadEvaluators()
  loadCompare()
  if (route.params.runId) openRunDetail(String(route.params.runId))
})
</script>

<style scoped>
.flex-1 { flex: 1; }
.report-head { display: flex; align-items: center; gap: 10px; }
.mb8 { margin-bottom: 8px; }
.mb10 { margin-bottom: 10px; }
.cmp-picker { display: flex; align-items: center; gap: 8px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 12px; }
.gate-box { display: flex; flex-direction: column; gap: 8px; }
.gate-head { display: flex; align-items: flex-start; gap: 10px; }
.gate-checks { display: flex; gap: 6px; flex-wrap: wrap; }
.gate-check { font-size: 11px; padding: 2px 6px; border-radius: 3px; background: var(--bg-subtle); white-space: nowrap; }
.gate-head .page-sub { line-height: 1.5; }
.attempt-chip { cursor: pointer; }
.gate-history-row { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.gate-history-list { max-height: 280px; overflow-y: auto; padding-right: 4px; }
.case-list { display: flex; flex-direction: column; gap: 10px; max-height: 420px; overflow-y: auto; padding-right: 4px; }
.gate-pass { color: var(--success); }
.gate-fail { color: var(--error); }
.gate-blocked { color: var(--warn); }
.dimension-row { display: flex; align-items: center; gap: 12px; padding: 5px 0; border-bottom: 1px solid var(--border-subtle); font-size: 12.5px; }
.dimension-key { min-width: 90px; font-weight: 600; }
.dimension-row .page-sub { flex: 1; }
.human-row { display: flex; align-items: center; gap: 10px; padding: 6px 0; border-bottom: 1px solid var(--border-subtle); font-size: 12.5px; }
.diff-summary { display: flex; align-items: center; gap: 18px; margin-bottom: 10px; font-size: 12.5px; color: var(--text-secondary); flex-wrap: wrap; }
.trend-track { display: flex; align-items: flex-end; gap: 8px; height: 130px; padding: 0 4px; }
.trend-col { flex: 1; min-width: 0; display: flex; flex-direction: column; align-items: center; gap: 4px; height: 100%; }
.trend-v { font-size: 11px; font-weight: 600; }
.trend-bar-wrap { flex: 1; width: 100%; max-width: 46px; display: flex; align-items: flex-end; background: var(--bg-subtle); border-radius: 4px 4px 0 0; overflow: hidden; }
.trend-bar { width: 100%; border-radius: 3px 3px 0 0; opacity: 0.85; }
.trend-bar:hover { opacity: 1; }
.trend-x { font-size: 10.5px; color: var(--text-muted); }
.mt12 { margin-top: 12px; }
.mono { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; }
.link { color: var(--primary); cursor: pointer; }
.link:hover { text-decoration: underline; }
.run-summary { display: flex; gap: 14px; font-size: 13px; margin-bottom: 8px; align-items: baseline; flex-wrap: wrap; }
.run-summary.sub { font-size: 12.5px; color: var(--text-secondary); margin-bottom: 12px; }
.metric-hint { display: flex; gap: 6px 16px; flex-wrap: wrap; padding: 8px 12px; color: var(--text-muted); font-size: 11.5px; background: var(--bg-subtle); border: 1px solid var(--border); border-radius: 6px; }
.metric-hint span:not(:last-child)::after { content: "·"; margin-left: 16px; color: var(--border-strong); }
.dataset-tabs { margin-top: 6px; }
.run-modal-head { margin-bottom: 14px; padding: 10px 12px; background: var(--bg-subtle); border: 1px solid var(--border); border-radius: 8px; }
.run-modal-count { font-size: 13px; color: var(--text-secondary); }
.run-modal-count b { font-size: 17px; color: var(--text-primary); }
.run-x { margin: 0 6px; color: var(--text-muted); }
.mode-note { margin-top: 6px; font-size: 12px; line-height: 18px; color: var(--text-muted); }
.run-advanced { margin-top: 2px; margin-bottom: 8px; }
.run-advanced :deep(.n-collapse-item__header) { font-size: 12.5px; }
.run-advanced :deep(.n-form-item:last-child) { margin-bottom: 0; }
</style>
