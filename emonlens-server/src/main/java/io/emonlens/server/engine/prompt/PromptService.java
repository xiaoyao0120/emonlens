package io.emonlens.server.engine.prompt;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.common.Sha256Utils;
import io.emonlens.server.storage.entity.PromptEntity;
import io.emonlens.server.storage.entity.PromptResolutionEntity;
import io.emonlens.server.storage.mapper.PromptMapper;
import io.emonlens.server.storage.mapper.PromptResolutionMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Prompt 版本解析的唯一实现（R1-3）：label / version → 不可变版本，并留解析痕迹。
 *
 * <p>版本内容不可变（同名 +1 版本），所以“prompt 改版”在数据上表现为 production
 * 指针移动。run 受理时冻结 (name, version, hash)，Gate 校验时再解析一次 production，
 * 指针一旦移动旧结论即视为过期（PROMPT_PRODUCTION_MOVED），不靠人工判断。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptService {

    public static final String LABEL_PRODUCTION = "production";
    public static final String LABEL_LATEST = "latest";

    public static final String SOURCE_UI = "UI";
    public static final String SOURCE_SDK = "SDK";
    public static final String SOURCE_API = "API";
    public static final String SOURCE_RUN = "RUN";
    public static final String SOURCE_MATRIX = "MATRIX";

    private final PromptMapper promptMapper;
    private final PromptResolutionMapper resolutionMapper;

    /** 受理 / 解析时冻结的 prompt 引用；version 与 hash 一起进候选指纹。 */
    public static class PromptRef {
        private String name;
        private Integer version;
        private String label;
        private String hash;

        public PromptRef() {
        }

        public PromptRef(String name, Integer version, String label, String hash) {
            this.name = name;
            this.version = version;
            this.label = label;
            this.hash = hash;
        }

        public String getName() {
            return name;
        }

        public Integer getVersion() {
            return version;
        }

        public String getLabel() {
            return label;
        }

        public String getHash() {
            return hash;
        }
    }

    public List<PromptEntity> versions(String workspaceId, String name) {
        return promptMapper.selectList(Wrappers.<PromptEntity>lambdaQuery()
                .eq(PromptEntity::getWorkspaceId, workspaceId)
                .eq(PromptEntity::getName, name)
                .orderByDesc(PromptEntity::getVersion));
    }

    public List<String> labelsOf(PromptEntity entity) {
        if (entity.getLabels() == null || entity.getLabels().isBlank()) {
            return List.of();
        }
        return Arrays.stream(entity.getLabels().split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
    }

    public PromptEntity latest(String workspaceId, String name) {
        return versions(workspaceId, name).stream().findFirst()
                .orElseThrow(() -> new EmonLensException(ErrorCode.NOT_FOUND,
                        "prompt not found: " + name));
    }

    public PromptEntity requireVersion(String workspaceId, String name, Integer version) {
        if (version == null) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "prompt version is required");
        }
        return versions(workspaceId, name).stream()
                .filter(v -> version.equals(v.getVersion()))
                .findFirst()
                .orElseThrow(() -> new EmonLensException(ErrorCode.NOT_FOUND,
                        "prompt version not found: " + name + " v" + version));
    }

    /** 按 label 解析：label 缺省 production；latest 缺省取最大版本。 */
    public PromptEntity resolve(String workspaceId, String name, String label) {
        String effective = label == null || label.isBlank() ? LABEL_PRODUCTION : label.trim();
        List<PromptEntity> all = versions(workspaceId, name);
        if (all.isEmpty()) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "prompt not found: " + name);
        }
        if (LABEL_LATEST.equals(effective)) {
            // latest 是“最大版本”的显式别名，不要求显式打标签
            return all.get(0);
        }
        return all.stream().filter(v -> hasLabel(v, effective)).findFirst()
                .orElseThrow(() -> new EmonLensException(ErrorCode.NOT_FOUND,
                        "prompt not found: " + name + " @" + effective));
    }

    /** version 优先于 label：显式版本必须存在，不能被 label 静默替换。 */
    public PromptEntity resolve(String workspaceId, String name, Integer version, String label) {
        if (version != null) {
            return requireVersion(workspaceId, name, version);
        }
        return resolve(workspaceId, name, label);
    }

    public PromptEntity production(String workspaceId, String name) {
        List<PromptEntity> all = versions(workspaceId, name);
        return all.stream().filter(v -> hasLabel(v, LABEL_PRODUCTION)).findFirst().orElse(null);
    }

    public boolean hasLabel(PromptEntity entity, String label) {
        return labelsOf(entity).contains(label);
    }

    /** Prompt 正文 fingerprint：同名不同版本内容不同，run 与 Gate 都按这个值比对。 */
    public String contentHash(PromptEntity entity) {
        if (entity == null) {
            return null;
        }
        return Sha256Utils.hash(entity.getName() + "\n" + entity.getVersion() + "\n"
                + (entity.getPrompt() == null ? "" : entity.getPrompt()));
    }

    public PromptRef refOf(PromptEntity entity, String label) {
        return new PromptRef(entity.getName(), entity.getVersion(), label, contentHash(entity));
    }

    public Map<String, Object> refMap(PromptEntity entity, String label) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", entity.getName());
        map.put("version", entity.getVersion());
        map.put("label", label);
        map.put("hash", contentHash(entity));
        return map;
    }

    /** 解析留痕：GET resolve 不进操作审计，这里单独记录谁按哪个 label 取到了哪个版本。 */
    public void recordResolution(PromptEntity entity, String label, String source, String requestedBy) {
        try {
            PromptResolutionEntity row = new PromptResolutionEntity();
            row.setWorkspaceId(entity.getWorkspaceId());
            row.setName(entity.getName());
            row.setLabel(label == null || label.isBlank() ? LABEL_LATEST : label);
            row.setVersion(entity.getVersion());
            row.setPromptHash(contentHash(entity));
            row.setSource(source);
            row.setRequestedBy(requestedBy);
            row.setCreatedAt(LocalDateTime.now());
            resolutionMapper.insert(row);
        } catch (Exception e) {
            // 留痕失败不能阻断 resolve：审计表坏了也不该让线上 Prompt 拉取挂掉
            log.warn("prompt resolution record failed, name={} version={}", entity.getName(),
                    entity.getVersion(), e);
        }
    }
}
