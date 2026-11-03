package io.emonlens.server.api;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.emonlens.common.ApiResult;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.ingest.IngestService;
import io.emonlens.server.ingest.TrustContext;
import io.emonlens.server.ingest.dto.IngestResponse;
import io.emonlens.server.ingest.dto.SpanEventDto;
import io.emonlens.server.storage.entity.PromptEntity;
import io.emonlens.server.storage.mapper.PromptMapper;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/ingest")
@RequiredArgsConstructor
public class IngestController {

    private final IngestService ingestService;
    private final PromptMapper promptMapper;
    private final io.emonlens.server.engine.prompt.PromptService promptService;

    /**
     * Prompt 运行时拉取（Langfuse public get_prompt 对标）：数据面用 X-Trust-Key 鉴权，
     * 供 SDK get_prompt 按 label 解析版本（默认 production）。与控制面 /prompts/{name}/resolve
     * 共用同一套解析口径，并留解析痕迹（source=SDK）。
     */
    @GetMapping("/prompts/{name}/resolve")
    public ApiResult<Map<String, Object>> resolvePrompt(
            @RequestAttribute(IngestKeyFilter.TRUST_CONTEXT_ATTRIBUTE) TrustContext context,
            @PathVariable String name,
            @RequestParam(defaultValue = "production") String label) {
        PromptEntity hit = promptService.resolve(context.getWorkspaceId(), name, label);
        promptService.recordResolution(hit, label,
                io.emonlens.server.engine.prompt.PromptService.SOURCE_SDK, null);
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", hit.getName());
        m.put("version", hit.getVersion());
        m.put("prompt", hit.getPrompt());
        m.put("promptHash", promptService.contentHash(hit));
        m.put("config", configOf(hit));
        m.put("labels", promptService.labelsOf(hit));
        return ApiResult.ok(m);
    }

    /** configJson → Map：SDK 侧 Prompt.config 是字典，这里与控制面 resolve 口径保持一致。 */
    private Map<String, Object> configOf(PromptEntity hit) {
        if (hit.getConfigJson() == null || hit.getConfigJson().isBlank()) {
            return Map.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(hit.getConfigJson(), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    @PostMapping("/events")
    public ApiResult<IngestResponse> ingest(
            @RequestAttribute(IngestKeyFilter.TRUST_CONTEXT_ATTRIBUTE) TrustContext context,
            @RequestBody List<SpanEventDto> spans) {
        if (spans == null || spans.isEmpty()) {
            return ApiResult.ok(new IngestResponse(0, Collections.emptyList(), 0, 0));
        }
        return ApiResult.ok(ingestService.accept(context, spans));
    }
}
