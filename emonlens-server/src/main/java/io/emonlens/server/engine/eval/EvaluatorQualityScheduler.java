package io.emonlens.server.engine.eval;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.emonlens.server.config.EmonLensProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * R2-3 每日质量扫描：把低可信评估器与腐烂数据集写成 Finding，
 * 复用既有 Incident 归并与通知链路（Alerts 页因此能看到它们）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvaluatorQualityScheduler {

    private final EvaluatorQualityService qualityService;
    private final EmonLensProperties properties;

    @Scheduled(cron = "${emonlens.evaluator-quality.cron:0 10 3 * * *}")
    public void scan() {
        if (!properties.getWorker().isEnabled()) {
            return;
        }
        int created = 0;
        int workspaces = 0;
        for (String workspaceId : qualityService.workspaces()) {
            workspaces++;
            try {
                created += qualityService.scanAndRecord(workspaceId);
            } catch (Exception e) {
                log.error("evaluator quality scan failed, workspace={}", workspaceId, e);
            }
        }
        log.info("evaluator quality scan finished, workspaces={}, findings={}", workspaces, created);
    }
}
