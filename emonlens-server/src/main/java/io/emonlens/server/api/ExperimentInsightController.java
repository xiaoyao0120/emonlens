package io.emonlens.server.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.emonlens.common.ApiResult;
import io.emonlens.server.audit.Audit;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;
import io.emonlens.server.engine.eval.DatasetRunService;
import io.emonlens.server.engine.eval.ExperimentInsightService;

import lombok.RequiredArgsConstructor;

/**
 * 实验对比增强（R2-4）：分数 vs 成本/延迟散点、repetitions（同配置重跑）、数据集 leaderboard。
 * 只读取已冻结 run 的结论，不改写任何 run / Gate。
 */
@RestController
@RequestMapping("/api/v1/datasets/{datasetId}")
@RequiredArgsConstructor
public class ExperimentInsightController {

    private final ExperimentInsightService insightService;
    private final DatasetRunService datasetRunService;
    private final io.emonlens.server.engine.sim.SimulationReportService simulationReportService;

    /** R3-1：一次 REPLAY_SIM run 的会话摘要与三相成本（simulation / generation / judge）。 */
    @GetMapping("/runs/{runId}/simulation")
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<Map<String, Object>> simulation(@PathVariable String datasetId,
                                                     @PathVariable String runId) {
        var run = datasetRunService.requireRun(CurrentContext.require().getWorkspaceId(),
                datasetId, runId);
        return ApiResult.ok(simulationReportService.report(run));
    }

    /** 散点：一行一个 run；minCoverage 用于过滤"判得太少"的点。 */
    @GetMapping("/runs/scatter")
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<Map<String, Object>> scatter(
            @PathVariable String datasetId,
            @RequestParam(defaultValue = "30") int windowDays,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String candidateLabel,
            @RequestParam(required = false) Double minCoverage) {
        return ApiResult.ok(insightService.scatter(CurrentContext.require().getWorkspaceId(),
                datasetId, windowDays, model, candidateLabel, minCoverage));
    }

    /**
     * Leaderboard：按候选（同候选指纹）聚合重复次数、均值与方差、成本与延迟，
     * 覆盖率不足的候选按覆盖率线性降权并标 degraded。
     */
    @GetMapping("/leaderboard")
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<Map<String, Object>> leaderboard(
            @PathVariable String datasetId,
            @RequestParam(defaultValue = "30") int windowDays,
            @RequestParam(defaultValue = "90") double minCoverage) {
        return ApiResult.ok(insightService.leaderboard(CurrentContext.require().getWorkspaceId(),
                datasetId, windowDays, minCoverage));
    }

    /**
     * repetitions：按该 run 的冻结配置再跑一次（新 run 与原 run 候选指纹相同，自动归入同一组）。
     * 受活动 run 容量约束：已有活动 run 时返回 429，等它结束后再调用即可凑齐 N 次。
     */
    @PostMapping("/runs/{runId}/repetition")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "dataset.run.repetition", resourceType = "dataset")
    public ApiResult<Map<String, Object>> repeat(@PathVariable String datasetId,
                                                 @PathVariable String runId,
                                                 @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var run = datasetRunService.repeatFromRun(CurrentContext.require().getWorkspaceId(),
                datasetId, runId, idempotencyKey);
        return ApiResult.ok(Map.of(
                "runId", run.getId(),
                "sourceRunId", runId,
                "status", run.getStatus(),
                "candidateFingerprint", run.getCandidateFingerprint() == null ? "" : run.getCandidateFingerprint(),
                "repetitionCount", datasetRunService.repetitionCount(
                        CurrentContext.require().getWorkspaceId(), datasetId, run)));
    }
}
