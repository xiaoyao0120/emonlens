-- R2-2 端用户反馈采集：预签 token（只存 sha256）+ 类型化反馈（numeric/categorical/boolean/correction）。
-- apply-once ledger: 2026-11-12-end-user-feedback
-- 幂等：CREATE TABLE IF NOT EXISTS。
-- 执行：mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-11-12-end-user-feedback.sql
--
-- 安全口径：token 只授予「给指定 trace 写一条反馈」，读不到正文；平台只保存 token 的 sha256。
-- 反馈同时写一条 et_eval_result（evaluatorKey=end-user-feedback），既有监控与质量视图直接可用。

CREATE TABLE IF NOT EXISTS et_feedback_token (
    id            VARCHAR(36) PRIMARY KEY,
    workspace_id  VARCHAR(64) NOT NULL,
    trace_id      VARCHAR(64) NOT NULL,
    token_hash    CHAR(64) NOT NULL,
    max_uses      INT NOT NULL DEFAULT 5,
    used_count    INT NOT NULL DEFAULT 0,
    expires_at    DATETIME(3),
    revoked_at    DATETIME(3),
    last_used_at  DATETIME(3),
    created_by    VARCHAR(64),
    created_at    DATETIME(3) NOT NULL,
    UNIQUE KEY uk_feedback_token (token_hash),
    KEY idx_feedback_token_trace (workspace_id, trace_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_end_user_feedback (
    id            VARCHAR(36) PRIMARY KEY,
    workspace_id  VARCHAR(64) NOT NULL,
    trace_id      VARCHAR(64) NOT NULL,
    token_id      VARCHAR(36),
    kind          VARCHAR(16) NOT NULL,
    value_num     DECIMAL(8,4),
    value_text    VARCHAR(200),
    correction    TEXT,
    comment       VARCHAR(1000),
    user_ref      VARCHAR(128),
    source        VARCHAR(16) NOT NULL DEFAULT 'END_USER',
    label         VARCHAR(16),
    score         DECIMAL(5,2),
    created_at    DATETIME(3) NOT NULL,
    KEY idx_end_user_feedback_trace (workspace_id, trace_id, created_at),
    KEY idx_end_user_feedback_agent (workspace_id, created_at, kind)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;