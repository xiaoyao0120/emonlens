package io.emonlens.server.engine.eval;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.config.SysSettingService;
import io.emonlens.server.api.PairwiseEvaluationController.CreateRequest;
import io.emonlens.server.api.PairwiseEvaluationController.HumanReviewRequest;
import io.emonlens.server.storage.entity.DatasetEntity;
import io.emonlens.server.storage.entity.DatasetRunEntity;
import io.emonlens.server.storage.entity.DatasetRunItemEntity;
import io.emonlens.server.storage.entity.PairwiseEvaluationEntity;
import io.emonlens.server.storage.entity.PairwiseHumanReviewEntity;
import io.emonlens.server.storage.entity.PairwiseItemEntity;
import io.emonlens.server.storage.mapper.DatasetMapper;
import io.emonlens.server.storage.mapper.DatasetRunItemMapper;
import io.emonlens.server.storage.mapper.DatasetRunMapper;
import io.emonlens.server.storage.mapper.PairwiseEvaluationMapper;
import io.emonlens.server.storage.mapper.PairwiseHumanReviewMapper;
import io.emonlens.server.storage.mapper.PairwiseItemMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Pairwise（D3）：同一输入下两个已完成 run 的中性候选比较。
 * 每个方向独立调用；两方向映射回原始 A/B 后一致才算偏好，冲突/调用不全 -> UNKNOWN。
 * 只证明相对优劣，不证明绝对合格。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PairwiseEvaluationService {

    private static final String PROTOCOL = "BIDIRECTIONAL";
    private static final String PROTOCOL_VERSION = "pairwise/v2";
    private static final String PROTOCOL_VERSION_RANDOMIZED = "pairwise/v3";

    private final PairwiseJudge pairwiseJudge;
    private final PairwiseEvaluationMapper evaluationMapper;
    private final PairwiseItemMapper itemMapper;
    private final PairwiseHumanReviewMapper humanReviewMapper;
    private final DatasetMapper datasetMapper;
    private final DatasetRunMapper runMapper;
    private final DatasetRunItemMapper runItemMapper;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final DatasetRunService datasetRunService;

    public Map<String, Object> evaluate(String workspaceId, String datasetId, CreateRequest request) {
        DatasetEntity dataset = datasetMapper.selectById(datasetId);
        if (dataset == null || !Objects.equals(dataset.getWorkspaceId(), workspaceId)) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "dataset not found");
        }
        if (request == null || request.getRunAId() == null || request.getRunBId() == null) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "runAId/runBId is required");
        }
        if (Objects.equals(request.getRunAId(), request.getRunBId())) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "runAId/runBId 不能相同");
        }
        DatasetRunEntity runA = requireRun(workspaceId, datasetId, request.getRunAId());
        DatasetRunEntity runB = requireRun(workspaceId, datasetId, request.getRunBId());
        if (!DatasetRunEntity.STATUS_COMPLETED.equals(runA.getStatus())
                || !DatasetRunEntity.STATUS_COMPLETED.equals(runB.getStatus())) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "两个 run 都必须 COMPLETED");
        }
        if (Objects.equals(runA.getCandidateFingerprint(), runB.getCandidateFingerprint())) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "两个 run 的候选指纹相同，无法成对比较");
        }
        String sampleSetA = sampleSetHash(runA);
        String sampleSetB = sampleSetHash(runB);
        if (sampleSetA == null || !sampleSetA.equals(sampleSetB)) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "两个 run 的 sampleSetHash 不一致，不能直接成对比较");
        }
        // R2-4：repetitions 口径必须一致——一边跑了 3 次取均值、另一边只跑 1 次，比较结论不成立。
        int repeatsA = datasetRunService.repetitionCount(workspaceId, datasetId, runA);
        int repeatsB = datasetRunService.repetitionCount(workspaceId, datasetId, runB);
        if (repeatsA != repeatsB) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "两个 run 的 repetitions 次数不同（" + repeatsA + " vs " + repeatsB
                            + "）：成对比较要求同口径，请用相同重复次数重跑后再比较");
        }

        int samples = request.getSamplesPerDirection() == null ? 1 : request.getSamplesPerDirection();
        if (samples < 1 || samples > 3) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "samplesPerDirection 只允许 1..3");
        }
        List<String> models = normalizeModels(request);
        boolean randomize = Boolean.TRUE.equals(request.getPositionRandomized());
        long positionSeed = request.getPositionSeed() == null
                ? java.util.concurrent.ThreadLocalRandom.current().nextLong()
                : request.getPositionSeed();
        java.util.Random random = randomize ? new java.util.Random(positionSeed) : null;

        Map<String, DatasetRunItemEntity> aItems = byItem(runA.getId());
        Map<String, DatasetRunItemEntity> bItems = byItem(runB.getId());
        Set<String> common = new LinkedHashSet<>(aItems.keySet());
        common.retainAll(bItems.keySet());
        List<String> itemIds = new ArrayList<>(common);
        itemIds.sort(Comparator.naturalOrder());
        if (itemIds.isEmpty()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "两个 run 没有共同样本");
        }
        if ((long) itemIds.size() * samples * 2 * models.size() > 400) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "成对比较调用数超过 400；请缩小样本范围、samplesPerDirection 或评审模型数");
        }

        List<PairwiseItemEntity> rows = new ArrayList<>();
        int aWins = 0;
        int bWins = 0;
        int ties = 0;
        int unknown = 0;
        int[] positionCounts = new int[2];
        for (String itemId : itemIds) {
            DatasetRunItemEntity ia = aItems.get(itemId);
            DatasetRunItemEntity ib = bItems.get(itemId);
            PairwiseItemEntity row = new PairwiseItemEntity();
            row.setWorkspaceId(workspaceId);
            row.setItemId(itemId);
            String input = snapshot(ia).get("input") == null ? null : String.valueOf(snapshot(ia).get("input"));
            String reference = snapshot(ia).get("expectedOutput") == null ? null
                    : String.valueOf(snapshot(ia).get("expectedOutput"));
            String outputA = candidateOutput(ia);
            String outputB = candidateOutput(ib);
            if (input == null || outputA == null || outputB == null) {
                row.setPreference("UNKNOWN");
                row.setReason("INPUT_OR_OUTPUT_MISSING");
                row.setCallsJson("[]");
                unknown++;
            } else {
                PairwiseJudge.Verdict verdict = pairwiseJudge.judge(input, reference, outputA, outputB,
                        models, samples, randomize, random, positionCounts);
                row.setPreference(verdict.getPreference());
                row.setReason(verdict.getReasonCode());
                row.setCallsJson(toJson(verdict.getCalls()));
                if ("A".equals(verdict.getPreference())) {
                    aWins++;
                } else if ("B".equals(verdict.getPreference())) {
                    bWins++;
                } else if ("TIE".equals(verdict.getPreference())) {
                    ties++;
                } else {
                    unknown++;
                }
            }
            rows.add(row);
        }

        PairwiseEvaluationEntity evaluation = new PairwiseEvaluationEntity();
        evaluation.setWorkspaceId(workspaceId);
        evaluation.setDatasetId(datasetId);
        evaluation.setRunAId(runA.getId());
        evaluation.setRunBId(runB.getId());
        evaluation.setProtocol(PROTOCOL);
        evaluation.setProtocolVersion(randomize
                ? PROTOCOL_VERSION_RANDOMIZED : PROTOCOL_VERSION);
        evaluation.setSamplesPerDirection(samples);
        evaluation.setPositionRandomized(randomize);
        evaluation.setPositionSeed(randomize ? positionSeed : null);
        evaluation.setPositionAFirstCount(positionCounts[0]);
        evaluation.setPositionBFirstCount(positionCounts[1]);
        evaluation.setJudgeModel(models.size() == 1 ? models.get(0) : "MULTI");
        evaluation.setJudgeModelsJson(toJson(models));
        evaluation.setJudgeCount(models.size());
        evaluation.setItemCount(itemIds.size());
        evaluation.setJudgedCount(aWins + bWins + ties);
        evaluation.setAWins(aWins);
        evaluation.setBWins(bWins);
        evaluation.setTies(ties);
        evaluation.setUnknownCount(unknown);
        evaluation.setPreferenceRate(aWins + bWins == 0 ? null
                : Math.round(aWins * 1000.0 / (aWins + bWins)) / 10.0);
        evaluation.setStatus("COMPLETED");
        evaluation.setCreatedBy(request.getCreatedBy());
        evaluation.setCreatedAt(LocalDateTime.now());

        transactionTemplate.executeWithoutResult(status -> {
            evaluationMapper.insert(evaluation);
            for (PairwiseItemEntity row : rows) {
                row.setEvaluationId(evaluation.getId());
                itemMapper.insert(row);
            }
        });
        return detail(workspaceId, datasetId, evaluation.getId());
    }

    public List<Map<String, Object>> list(String workspaceId, String datasetId,
                                          String runAId, String runBId) {
        List<PairwiseEvaluationEntity> rows = evaluationMapper.selectList(
                Wrappers.<PairwiseEvaluationEntity>lambdaQuery()
                        .eq(PairwiseEvaluationEntity::getWorkspaceId, workspaceId)
                        .eq(PairwiseEvaluationEntity::getDatasetId, datasetId)
                        .eq(runAId != null && !runAId.isBlank(), PairwiseEvaluationEntity::getRunAId, runAId)
                        .eq(runBId != null && !runBId.isBlank(), PairwiseEvaluationEntity::getRunBId, runBId)
                        .orderByDesc(PairwiseEvaluationEntity::getCreatedAt));
        List<Map<String, Object>> result = new ArrayList<>();
        for (PairwiseEvaluationEntity row : rows) {
            result.add(evaluationMap(row));
        }
        return result;
    }

    public Map<String, Object> detail(String workspaceId, String datasetId, String evaluationId) {
        PairwiseEvaluationEntity evaluation = evaluationMapper.selectById(evaluationId);
        if (evaluation == null || !Objects.equals(evaluation.getWorkspaceId(), workspaceId)
                || !Objects.equals(evaluation.getDatasetId(), datasetId)) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "pairwise evaluation not found");
        }
        List<PairwiseItemEntity> rows = itemMapper.selectList(
                Wrappers.<PairwiseItemEntity>lambdaQuery()
                        .eq(PairwiseItemEntity::getEvaluationId, evaluationId)
                        .orderByAsc(PairwiseItemEntity::getItemId));
        Map<String, Object> map = evaluationMap(evaluation);
        map.put("items", rows.stream().map(this::itemMap).collect(java.util.stream.Collectors.toList()));
        map.put("calibration", calibrationSummary(evaluation, rows, reviews(evaluation.getId())));
        return map;
    }

    public Map<String, Object> review(String workspaceId, String datasetId, String evaluationId,
                                      HumanReviewRequest request, String reviewer) {
        PairwiseEvaluationEntity evaluation = requireEvaluation(workspaceId, datasetId, evaluationId);
        if (request == null || request.getItemId() == null || request.getItemId().isBlank()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "itemId is required");
        }
        PairwiseItemEntity item = itemMapper.selectOne(
                Wrappers.<PairwiseItemEntity>lambdaQuery()
                        .eq(PairwiseItemEntity::getEvaluationId, evaluation.getId())
                        .eq(PairwiseItemEntity::getItemId, request.getItemId()));
        if (item == null) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "pairwise item not found");
        }
        String preference = request.getPreference() == null
                ? "" : request.getPreference().trim().toUpperCase();
        if (!List.of("A", "B", "TIE", "UNKNOWN").contains(preference)) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "preference 只允许 A/B/TIE/UNKNOWN");
        }
        PairwiseHumanReviewEntity existing = humanReviewMapper.selectOne(
                Wrappers.<PairwiseHumanReviewEntity>lambdaQuery()
                        .eq(PairwiseHumanReviewEntity::getEvaluationId, evaluation.getId())
                        .eq(PairwiseHumanReviewEntity::getItemId, item.getItemId())
                        .eq(PairwiseHumanReviewEntity::getReviewer, reviewer));
        PairwiseHumanReviewEntity entity = existing == null
                ? new PairwiseHumanReviewEntity() : existing;
        entity.setWorkspaceId(workspaceId);
        entity.setEvaluationId(evaluation.getId());
        entity.setItemId(item.getItemId());
        entity.setReviewer(reviewer);
        entity.setPreference(preference);
        entity.setNote(request.getNote());
        if (existing == null) {
            entity.setCreatedAt(LocalDateTime.now());
            humanReviewMapper.insert(entity);
        } else {
            humanReviewMapper.updateById(entity);
        }
        return reviewMap(entity);
    }

    public Map<String, Object> calibration(String workspaceId, String datasetId,
                                           String evaluationId) {
        PairwiseEvaluationEntity evaluation = requireEvaluation(workspaceId, datasetId, evaluationId);
        List<PairwiseItemEntity> rows = itemMapper.selectList(
                Wrappers.<PairwiseItemEntity>lambdaQuery()
                        .eq(PairwiseItemEntity::getEvaluationId, evaluationId)
                        .orderByAsc(PairwiseItemEntity::getItemId));
        Map<String, Object> map = calibrationSummary(evaluation, rows, reviews(evaluationId));
        map.put("reviews", reviews(evaluationId).stream().map(this::reviewMap)
                .collect(java.util.stream.Collectors.toList()));
        return map;
    }

    private PairwiseEvaluationEntity requireEvaluation(String workspaceId, String datasetId,
                                                       String evaluationId) {
        PairwiseEvaluationEntity evaluation = evaluationMapper.selectById(evaluationId);
        if (evaluation == null || !Objects.equals(evaluation.getWorkspaceId(), workspaceId)
                || !Objects.equals(evaluation.getDatasetId(), datasetId)) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "pairwise evaluation not found");
        }
        return evaluation;
    }

    private List<PairwiseHumanReviewEntity> reviews(String evaluationId) {
        return humanReviewMapper.selectList(
                Wrappers.<PairwiseHumanReviewEntity>lambdaQuery()
                        .eq(PairwiseHumanReviewEntity::getEvaluationId, evaluationId)
                        .orderByAsc(PairwiseHumanReviewEntity::getCreatedAt));
    }

    private Map<String, Object> calibrationSummary(PairwiseEvaluationEntity evaluation,
                                                    List<PairwiseItemEntity> rows,
                                                    List<PairwiseHumanReviewEntity> reviews) {
        Map<String, List<PairwiseHumanReviewEntity>> byItem = new LinkedHashMap<>();
        for (PairwiseHumanReviewEntity review : reviews) {
            byItem.computeIfAbsent(review.getItemId(), key -> new ArrayList<>()).add(review);
        }
        int reviewed = 0;
        int consensusDisagreements = 0;
        int agreements = 0;
        int disagreements = 0;
        int modelUnknown = 0;
        for (PairwiseItemEntity row : rows) {
            List<PairwiseHumanReviewEntity> itemReviews = byItem.get(row.getItemId());
            if (itemReviews == null || itemReviews.isEmpty()) {
                continue;
            }
            reviewed++;
            Set<String> preferences = new LinkedHashSet<>();
            for (PairwiseHumanReviewEntity review : itemReviews) {
                preferences.add(review.getPreference());
            }
            if (preferences.size() > 1) {
                consensusDisagreements++;
                continue;
            }
            String human = preferences.iterator().next();
            if ("UNKNOWN".equals(human)) {
                continue;
            }
            if ("UNKNOWN".equals(row.getPreference()) || row.getPreference() == null) {
                modelUnknown++;
            } else if (human.equals(row.getPreference())) {
                agreements++;
            } else {
                disagreements++;
            }
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("humanReviewedItems", reviewed);
        map.put("agreementCount", agreements);
        map.put("disagreementCount", disagreements);
        map.put("modelUnknownCount", modelUnknown);
        map.put("consensusDisagreementCount", consensusDisagreements);
        map.put("humanAgreementRate", agreements + disagreements == 0 ? null
                : Math.round(agreements * 1000.0 / (agreements + disagreements)) / 10.0);
        return map;
    }

    private Map<String, Object> reviewMap(PairwiseHumanReviewEntity e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", e.getId());
        map.put("evaluationId", e.getEvaluationId());
        map.put("itemId", e.getItemId());
        map.put("reviewer", e.getReviewer());
        map.put("preference", e.getPreference());
        map.put("note", e.getNote());
        map.put("createdAt", e.getCreatedAt());
        return map;
    }

    private List<String> normalizeModels(CreateRequest request) {
        List<String> raw = request.getJudgeModels();
        List<String> models = new ArrayList<>();
        if (raw != null && !raw.isEmpty()) {
            for (String model : raw) {
                String trimmed = model == null ? null : model.trim();
                if (trimmed == null || trimmed.isEmpty()) {
                    throw new EmonLensException(ErrorCode.BAD_REQUEST, "judgeModels 不能包含空值");
                }
                if (!models.contains(trimmed)) {
                    models.add(trimmed);
                }
            }
        } else if (request.getJudgeModel() != null && !request.getJudgeModel().isBlank()) {
            models.add(request.getJudgeModel().trim());
        } else {
            SysSettingService.EvalLlm cfg = llmClient.effectiveConfig();
            models.add(cfg != null && cfg.getModel() != null && !cfg.getModel().isBlank()
                    ? cfg.getModel() : "gpt-4o-mini");
        }
        if (models.size() > 3) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "judgeModels 最多 3 个");
        }
        return models;
    }

    private DatasetRunEntity requireRun(String workspaceId, String datasetId, String runId) {
        DatasetRunEntity run = runMapper.selectById(runId);
        if (run == null || !Objects.equals(run.getWorkspaceId(), workspaceId)
                || !Objects.equals(run.getDatasetId(), datasetId)) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "run not found");
        }
        return run;
    }

    private Map<String, DatasetRunItemEntity> byItem(String runId) {
        Map<String, DatasetRunItemEntity> map = new LinkedHashMap<>();
        for (DatasetRunItemEntity item : runItemMapper.selectList(
                Wrappers.<DatasetRunItemEntity>lambdaQuery()
                        .eq(DatasetRunItemEntity::getRunId, runId))) {
            map.put(item.getItemId(), item);
        }
        return map;
    }

    private String sampleSetHash(DatasetRunEntity run) {
        Map<String, Object> config = parseMap(run.getConfigSnapshotJson());
        Object hash = config == null ? null : config.get("sampleSetHash");
        return hash == null ? null : String.valueOf(hash);
    }

    private String candidateOutput(DatasetRunItemEntity item) {
        if (item.getReplayOutput() != null && !item.getReplayOutput().isBlank()) {
            return item.getReplayOutput();
        }
        Map<String, Object> details = parseMap(item.getResultDetailsJson());
        Object output = details == null ? null : details.get("candidateOutput");
        return output == null || String.valueOf(output).isBlank() ? null : String.valueOf(output);
    }

    private Map<String, Object> snapshot(DatasetRunItemEntity item) {
        Map<String, Object> map = parseMap(item.getSampleSnapshotJson());
        return map == null ? Map.of() : map;
    }

    private Map<String, Object> evaluationMap(PairwiseEvaluationEntity e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", e.getId());
        map.put("datasetId", e.getDatasetId());
        map.put("runAId", e.getRunAId());
        map.put("runBId", e.getRunBId());
        map.put("protocol", e.getProtocol());
        map.put("protocolVersion", e.getProtocolVersion());
        map.put("samplesPerDirection", e.getSamplesPerDirection());
        map.put("positionRandomized", e.getPositionRandomized());
        map.put("positionSeed", e.getPositionSeed());
        map.put("positionAFirstCount", e.getPositionAFirstCount());
        map.put("positionBFirstCount", e.getPositionBFirstCount());
        map.put("judgeModel", e.getJudgeModel());
        map.put("judgeModels", parseList(e.getJudgeModelsJson()));
        map.put("judgeCount", e.getJudgeCount());
        map.put("itemCount", e.getItemCount());
        map.put("judgedCount", e.getJudgedCount());
        map.put("aWins", e.getAWins());
        map.put("bWins", e.getBWins());
        map.put("ties", e.getTies());
        map.put("unknownCount", e.getUnknownCount());
        map.put("preferenceRate", e.getPreferenceRate());
        map.put("status", e.getStatus());
        map.put("createdAt", e.getCreatedAt());
        return map;
    }

    private Map<String, Object> itemMap(PairwiseItemEntity row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("itemId", row.getItemId());
        map.put("preference", row.getPreference());
        // 原始 LLM reason 可能引用候选正文；只留在 DB 审计，不对普通查询下发。
        map.put("reason", row.getReason());
        return map;
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> parseList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                    new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "pairwise payload 无法序列化");
        }
    }
}
