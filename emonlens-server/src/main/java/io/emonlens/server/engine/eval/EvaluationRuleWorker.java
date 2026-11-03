package io.emonlens.server.engine.eval;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.emonlens.server.config.EmonLensProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * R2-1 调度：推进 Rewind 作业、补评空闲会话的 SESSION 规则。
 *
 * <p>两者都是「幂等 + 限速」的后台动作：Rewind 按作业游标推进且只改作业状态；
 * 会话补评只对超过空闲阈值、且尚未评估过的会话执行。api-only 实例
 * （emonlens.worker.enabled=false）不做这些动作。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvaluationRuleWorker {

    private final EvaluationRewindService rewindService;
    private final EvaluationRuleService ruleService;
    private final EmonLensProperties properties;

    @Scheduled(fixedDelayString = "${emonlens.eval.rewind-poll-ms:5000}")
    public void runRewind() {
        if (!properties.getWorker().isEnabled()) {
            return;
        }
        try {
            int processed = rewindService.runOnce();
            if (processed > 0) {
                log.info("rewind batch processed {} traces", processed);
            }
        } catch (Exception e) {
            log.error("rewind batch failed", e);
        }
    }

    @Scheduled(fixedDelayString = "${emonlens.eval.session-sweep-ms:60000}")
    public void sweepIdleSessions() {
        if (!properties.getWorker().isEnabled()) {
            return;
        }
        try {
            for (String workspaceId : ruleService.workspacesWithSessionRules()) {
                int produced = ruleService.sweepIdleSessions(workspaceId);
                if (produced > 0) {
                    log.info("session rules produced {} results, workspace={}", produced, workspaceId);
                }
            }
        } catch (Exception e) {
            log.error("session rule sweep failed", e);
        }
    }
}
