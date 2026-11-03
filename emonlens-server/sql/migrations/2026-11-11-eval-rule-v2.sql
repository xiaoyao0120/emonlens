-- R2-1 评估规则 v2：规则对象（scope / filter / 采样与预算）、命中账本、Rewind 作业，
-- 以及评估结果的 scope / session_id / rule_key 三列（SESSION 汇总与「哪条规则产出结论」所需）。
-- apply-once ledger: 2026-11-11-eval-rule-v2
-- 幂等：表用 CREATE TABLE IF NOT EXISTS；列/索引按 information_schema 独立判断。
-- 执行：mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-11-11-eval-rule-v2.sql
--
-- 不兼容点（升级须知）：升级后「在工作空间里创建并启用任何规则」即切换到规则口径，
-- 全局采样率（emonlens.eval.default-rate / sys setting）对已配置规则的空间不再生效；
-- 未配置规则的空间行为完全不变。回退：删除/禁用全部规则即回到全局采样。

DROP PROCEDURE IF EXISTS emonlens_add_column_if_missing;
DELIMITER $$
CREATE PROCEDURE emonlens_add_column_if_missing(
    IN tbl VARCHAR(64),
    IN col VARCHAR(64),
    IN ddl VARCHAR(512)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = tbl AND column_name = col
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', ddl);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

DROP PROCEDURE IF EXISTS emonlens_add_index_if_missing;
DELIMITER $$
CREATE PROCEDURE emonlens_add_index_if_missing(
    IN tbl VARCHAR(64),
    IN idx VARCHAR(64),
    IN cols VARCHAR(512)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = tbl AND index_name = idx
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', tbl, '` ADD INDEX `', idx, '` (', cols, ')');
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

CALL emonlens_add_column_if_missing('et_eval_result', 'scope', 'VARCHAR(16) NULL');
CALL emonlens_add_column_if_missing('et_eval_result', 'session_id', 'VARCHAR(64) NULL');
CALL emonlens_add_column_if_missing('et_eval_result', 'rule_key', 'VARCHAR(100) NULL');
CALL emonlens_add_index_if_missing('et_eval_result', 'idx_eval_result_scope',
    'workspace_id, scope, evaluator_key, created_at');

CREATE TABLE IF NOT EXISTS et_eval_rule (
    id                VARCHAR(36) PRIMARY KEY,
    workspace_id      VARCHAR(64) NOT NULL,
    rule_key          VARCHAR(100) NOT NULL,
    name              VARCHAR(200),
    enabled           TINYINT(1) NOT NULL DEFAULT 1,
    scope             VARCHAR(16) NOT NULL DEFAULT 'TRACE',
    filters_json      TEXT,
    evaluator_ids_json TEXT NOT NULL,
    sample_rate       DOUBLE NOT NULL DEFAULT 1.0,
    error_forced      TINYINT(1) NOT NULL DEFAULT 1,
    max_per_hour      INT NOT NULL DEFAULT 0,
    session_idle_minutes INT NOT NULL DEFAULT 5,
    priority          INT NOT NULL DEFAULT 100,
    created_by        VARCHAR(64),
    created_at        DATETIME(3) NOT NULL,
    updated_at        DATETIME(3) NOT NULL,
    UNIQUE KEY uk_eval_rule (workspace_id, rule_key),
    KEY idx_eval_rule_scope (workspace_id, enabled, scope)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_eval_rule_hit (
    id            VARCHAR(36) PRIMARY KEY,
    workspace_id  VARCHAR(64) NOT NULL,
    rule_key      VARCHAR(100) NOT NULL,
    scope         VARCHAR(16) NOT NULL,
    subject_id    VARCHAR(64) NOT NULL,
    decision      VARCHAR(24) NOT NULL,
    eval_count    INT NOT NULL DEFAULT 0,
    detail        VARCHAR(500),
    created_at    DATETIME(3) NOT NULL,
    UNIQUE KEY uk_eval_rule_hit (rule_key, subject_id),
    KEY idx_eval_rule_hit_budget (rule_key, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_eval_rewind_job (
    id                VARCHAR(36) PRIMARY KEY,
    workspace_id      VARCHAR(64) NOT NULL,
    request_key       VARCHAR(128) NOT NULL,
    rule_key          VARCHAR(100) NOT NULL,
    filter_json       TEXT,
    status            VARCHAR(16) NOT NULL,
    rate_per_minute   INT NOT NULL DEFAULT 60,
    total             INT NOT NULL DEFAULT 0,
    processed         INT NOT NULL DEFAULT 0,
    evaluated         INT NOT NULL DEFAULT 0,
    deduped           INT NOT NULL DEFAULT 0,
    failed            INT NOT NULL DEFAULT 0,
    cursor_start_time DATETIME(3),
    cursor_trace_id   VARCHAR(64),
    error             VARCHAR(1000),
    created_by        VARCHAR(64),
    created_at        DATETIME(3) NOT NULL,
    started_at        DATETIME(3),
    finished_at       DATETIME(3),
    UNIQUE KEY uk_eval_rewind_request (workspace_id, request_key),
    KEY idx_eval_rewind_status (workspace_id, status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

DROP PROCEDURE IF EXISTS emonlens_add_column_if_missing;
DROP PROCEDURE IF EXISTS emonlens_add_index_if_missing;
