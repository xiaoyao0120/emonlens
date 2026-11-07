-- R1-2 Playground 矩阵实验 + R1-3 prompt 版本绑定 / R1-4 CI 基线标签。
-- apply-once ledger: 2026-11-10-playground-matrix-prompt-binding
-- 幂等：表用 CREATE TABLE IF NOT EXISTS；列/索引按 information_schema 独立判断。
-- 执行：mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-11-10-playground-matrix-prompt-binding.sql

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

-- ---------- R1-3 / R1-4：run 绑定 prompt 版本与 CI 基线 ----------
CALL emonlens_add_column_if_missing('et_dataset_run', 'prompt_name', 'VARCHAR(128) NULL');
CALL emonlens_add_column_if_missing('et_dataset_run', 'prompt_version', 'INT NULL');
CALL emonlens_add_column_if_missing('et_dataset_run', 'prompt_label', 'VARCHAR(32) NULL');
CALL emonlens_add_column_if_missing('et_dataset_run', 'prompt_hash', 'CHAR(64) NULL');
CALL emonlens_add_column_if_missing('et_dataset_run', 'run_label', 'VARCHAR(64) NULL');
CALL emonlens_add_column_if_missing('et_dataset_run', 'commit_sha', 'VARCHAR(64) NULL');
CALL emonlens_add_index_if_missing('et_dataset_run', 'idx_dataset_run_label',
    'workspace_id, dataset_id, run_label');

-- ---------- R1-2：Playground 矩阵 ----------
CREATE TABLE IF NOT EXISTS et_playground_matrix (
    id                VARCHAR(36) PRIMARY KEY,
    workspace_id      VARCHAR(64) NOT NULL,
    request_key       VARCHAR(128) NOT NULL,
    request_hash      CHAR(64) NOT NULL,
    dataset_id        VARCHAR(36) NOT NULL,
    dataset_revision_id VARCHAR(36),
    name              VARCHAR(128),
    status            VARCHAR(16) NOT NULL,
    columns_json      MEDIUMTEXT NOT NULL,
    estimate_json     MEDIUMTEXT,
    evaluator_ids_json MEDIUMTEXT,
    item_count        INT NOT NULL DEFAULT 0,
    cell_count        INT NOT NULL DEFAULT 0,
    completed_cells   INT NOT NULL DEFAULT 0,
    failed_cells      INT NOT NULL DEFAULT 0,
    prompt_tokens     BIGINT NOT NULL DEFAULT 0,
    completion_tokens BIGINT NOT NULL DEFAULT 0,
    cost              DECIMAL(18,6),
    currency          VARCHAR(8),
    unpriced_models   VARCHAR(512),
    error             VARCHAR(1000),
    created_by        VARCHAR(64),
    created_at        DATETIME(3) NOT NULL,
    started_at        DATETIME(3),
    finished_at       DATETIME(3),
    UNIQUE KEY uk_playground_matrix_request (workspace_id, request_key),
    KEY idx_playground_matrix_created (workspace_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_playground_cell (
    id                VARCHAR(36) PRIMARY KEY,
    workspace_id      VARCHAR(64) NOT NULL,
    matrix_id         VARCHAR(36) NOT NULL,
    column_key        VARCHAR(64) NOT NULL,
    item_id           VARCHAR(36) NOT NULL,
    item_order        INT NOT NULL DEFAULT 0,
    status            VARCHAR(16) NOT NULL,
    output            MEDIUMTEXT,
    output_hash       CHAR(64),
    rendered_prompt   MEDIUMTEXT,
    input_text        MEDIUMTEXT,
    expected_text     MEDIUMTEXT,
    error             VARCHAR(1000),
    prompt_tokens     INT,
    completion_tokens INT,
    latency_ms        INT,
    cost              DECIMAL(18,6),
    scores_json       MEDIUMTEXT,
    aggregate_label   VARCHAR(16),
    score             DECIMAL(8,4),
    created_at        DATETIME(3) NOT NULL,
    finished_at       DATETIME(3),
    UNIQUE KEY uk_playground_cell (matrix_id, column_key, item_id),
    KEY idx_playground_cell_matrix (matrix_id, column_key, item_order)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_playground_diff (
    id                  VARCHAR(36) PRIMARY KEY,
    workspace_id        VARCHAR(64) NOT NULL,
    matrix_id           VARCHAR(36) NOT NULL,
    base_column         VARCHAR(64) NOT NULL,
    target_column       VARCHAR(64) NOT NULL,
    protocol_version    VARCHAR(32) NOT NULL,
    judge_models_json   TEXT,
    position_randomized TINYINT(1),
    position_seed       BIGINT,
    item_count          INT NOT NULL DEFAULT 0,
    same_count          INT NOT NULL DEFAULT 0,
    changed_count       INT NOT NULL DEFAULT 0,
    improved_count      INT NOT NULL DEFAULT 0,
    regressed_count     INT NOT NULL DEFAULT 0,
    tradeoff_count      INT NOT NULL DEFAULT 0,
    tie_count           INT NOT NULL DEFAULT 0,
    unknown_count       INT NOT NULL DEFAULT 0,
    status              VARCHAR(16) NOT NULL,
    created_by          VARCHAR(64),
    created_at          DATETIME(3) NOT NULL,
    KEY idx_playground_diff_matrix (matrix_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_playground_diff_item (
    id              VARCHAR(36) PRIMARY KEY,
    workspace_id    VARCHAR(64) NOT NULL,
    diff_id         VARCHAR(36) NOT NULL,
    item_id         VARCHAR(36) NOT NULL,
    determinism   VARCHAR(16) NOT NULL,
    preference      VARCHAR(16) NOT NULL,
    reason_code     VARCHAR(64),
    reason          VARCHAR(512),
    score_delta_json TEXT,
    calls_json      MEDIUMTEXT,
    UNIQUE KEY uk_playground_diff_item (diff_id, item_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------- R1-3：prompt 解析留痕 ----------
CREATE TABLE IF NOT EXISTS et_prompt_resolution (
    id            VARCHAR(36) PRIMARY KEY,
    workspace_id  VARCHAR(64) NOT NULL,
    name          VARCHAR(128) NOT NULL,
    label         VARCHAR(32) NOT NULL,
    version       INT NOT NULL,
    prompt_hash   CHAR(64),
    source        VARCHAR(32),
    requested_by  VARCHAR(64),
    created_at    DATETIME(3) NOT NULL,
    KEY idx_prompt_resolution_name (workspace_id, name, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

DROP PROCEDURE IF EXISTS emonlens_add_column_if_missing;
DROP PROCEDURE IF EXISTS emonlens_add_index_if_missing;
