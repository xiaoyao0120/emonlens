package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * Rewind 作业（R2-1）：按 filter 圈选历史 trace 批量补评。
 *
 * <p>作业本身是「可暂停的游标」：按 (start_time, trace_id) 递增推进，
 * 每轮受 rate_per_minute 限速，重复创建同 request_key 返回同一作业。</p>
 */
@Data
@TableName("et_eval_rewind_job")
public class EvalRewindJobEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_PAUSED = "PAUSED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;
    private String requestKey;
    private String ruleKey;
    private String filterJson;
    private String status;
    private Integer ratePerMinute;
    private Integer total;
    private Integer processed;
    private Integer evaluated;
    private Integer deduped;
    private Integer failed;
    private LocalDateTime cursorStartTime;
    private String cursorTraceId;
    private String error;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    @TableField(exist = false)
    private java.util.Map<String, Object> filter;
}
