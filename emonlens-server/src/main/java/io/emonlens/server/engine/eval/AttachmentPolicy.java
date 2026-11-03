package io.emonlens.server.engine.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.common.Sha256Utils;

import lombok.RequiredArgsConstructor;

/**
 * 多模态附件（R3-4）：样本附带图片，判定时作为证据交给 vision 模型。
 *
 * <p>两种承载方式：</p>
 * <ul>
 *   <li><b>URL</b>：只校验 scheme（http/https）并原样传给模型端点——平台自己不抓取，
 *       因此不存在服务端请求伪造面；内网 URL 由模型侧拒绝或失败。</li>
 *   <li><b>base64</b>：随样本入库，单个上限 256KB、单样本最多 5 个；导出时默认脱敏成
 *       <code>sha256:…</code> 占位（与正文的 TRACE_CONTENT 口径一致）。</li>
 * </ul>
 *
 * <p>TRAJECTORY 评估器不消费附件：工具调用序列与图片无关。</p>
 */
@Component
@RequiredArgsConstructor
public class AttachmentPolicy {

    public static final int MAX_ATTACHMENTS = 5;
    public static final int MAX_BASE64_BYTES = 256 * 1024;
    public static final String KIND_IMAGE = "IMAGE";

    private static final List<String> ALLOWED_MIME = List.of(
            "image/png", "image/jpeg", "image/webp", "image/gif");

    private final ObjectMapper objectMapper;

    /** 校验并序列化；返回 null 表示没有附件。 */
    public String normalize(Object raw) {
        List<Map<String, Object>> attachments = parse(raw);
        if (attachments.isEmpty()) {
            return null;
        }
        if (attachments.size() > MAX_ATTACHMENTS) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "附件最多 " + MAX_ATTACHMENTS + " 个");
        }
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> attachment : attachments) {
            normalized.add(normalizeOne(attachment));
        }
        return toJson(normalized);
    }

    private Map<String, Object> normalizeOne(Map<String, Object> attachment) {
        String kind = attachment.get("kind") == null ? KIND_IMAGE
                : String.valueOf(attachment.get("kind")).toUpperCase();
        if (!KIND_IMAGE.equals(kind)) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "当前只支持 IMAGE 附件（TRAJECTORY 不涉及多模态）");
        }
        String mime = attachment.get("mime") == null ? null : String.valueOf(attachment.get("mime"));
        if (mime == null || !ALLOWED_MIME.contains(mime.toLowerCase())) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "图片 mime 只支持 " + String.join("/", ALLOWED_MIME));
        }
        String url = attachment.get("url") == null ? null : String.valueOf(attachment.get("url")).trim();
        String base64 = attachment.get("base64") == null
                ? null : String.valueOf(attachment.get("base64")).trim();
        if ((url == null || url.isEmpty()) && (base64 == null || base64.isEmpty())) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "附件需要 url 或 base64");
        }
        if (url != null && !url.isEmpty()) {
            String lower = url.toLowerCase();
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "附件 url 只支持 http/https（base64 请放在 base64 字段）");
            }
        }
        if (base64 != null && !base64.isEmpty() && base64.length() > MAX_BASE64_BYTES) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "附件 base64 超过 " + (MAX_BASE64_BYTES / 1024) + "KB，请改用 url 引用");
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("kind", KIND_IMAGE);
        normalized.put("mime", mime.toLowerCase());
        if (url != null && !url.isEmpty()) {
            normalized.put("url", url);
        }
        if (base64 != null && !base64.isEmpty()) {
            normalized.put("base64", base64);
        }
        if (attachment.get("alt") != null) {
            normalized.put("alt", String.valueOf(attachment.get("alt")));
        }
        return normalized;
    }

    /** judge prompt 里的附件说明：模型据此决定要不要看图，以及看哪张。 */
    public String promptBlock(String attachmentsJson) {
        return promptBlock(parse(attachmentsJson));
    }

    public String promptBlock(List<Map<String, Object>> attachments) {
        if (attachments.isEmpty()) {
            return "";
        }
        StringBuilder block = new StringBuilder("Attachments (images; consider them as evidence):");
        for (int i = 0; i < attachments.size(); i++) {
            Map<String, Object> attachment = attachments.get(i);
            block.append("\n- #").append(i + 1).append(' ').append(attachment.get("mime"));
            if (attachment.get("url") != null) {
                block.append(" url=").append(attachment.get("url"));
            } else {
                block.append(" inline=base64(").append(attachment.get("base64") == null
                        ? 0 : String.valueOf(attachment.get("base64")).length()).append(" chars)");
            }
            if (attachment.get("alt") != null) {
                block.append(" alt=\"").append(attachment.get("alt")).append('"');
            }
        }
        return block.toString();
    }

    /** 可交给 vision 模型的外链图片（base64 附件不在此列：LlmClient 只支持 URL 形态的图片）。 */
    public List<String> imageUrls(String attachmentsJson) {
        return imageUrls(parse(attachmentsJson));
    }

    public List<String> imageUrls(List<Map<String, Object>> attachments) {
        List<String> urls = new ArrayList<>();
        for (Map<String, Object> attachment : attachments) {
            Object url = attachment.get("url");
            if (url != null && !String.valueOf(url).isBlank()) {
                urls.add(String.valueOf(url));
            }
        }
        return urls;
    }

    /** 导出/无权限读取时的脱敏：base64 换成 sha256 占位，URL 保留（URL 本身不含内容）。 */
    public List<Map<String, Object>> redacted(String attachmentsJson) {
        return redacted(parse(attachmentsJson));
    }

    public List<Map<String, Object>> redacted(List<Map<String, Object>> attachments) {
        List<Map<String, Object>> redacted = new ArrayList<>();
        for (Map<String, Object> attachment : attachments) {
            Map<String, Object> row = new LinkedHashMap<>(attachment);
            Object base64 = row.remove("base64");
            if (base64 != null) {
                row.put("base64_sha256", Sha256Utils.hash(String.valueOf(base64)));
            }
            redacted.add(row);
        }
        return redacted;
    }

    public List<Map<String, Object>> parse(String attachmentsJson) {
        if (attachmentsJson == null || attachmentsJson.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(attachmentsJson,
                    new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private List<Map<String, Object>> parse(Object raw) {
        if (raw == null) {
            return new ArrayList<>();
        }
        if (raw instanceof List) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Object item : (List<?>) raw) {
                if (item instanceof Map) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) item).entrySet()) {
                        row.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                    rows.add(row);
                }
            }
            return rows;
        }
        return parse(String.valueOf(raw));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "附件序列化失败");
        }
    }
}
