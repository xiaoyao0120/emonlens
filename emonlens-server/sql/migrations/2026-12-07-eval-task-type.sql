-- SDK 评测器结果回写：任务行记录评估器类型，服务端执行阶段据此跳过 SDK 任务，
-- 终态收口与 Gate 检查按类型区分"未执行"与"等待外部结果"。apply-once ledger: 2026-12-07-eval-task-type
-- 幂等：列按 information_schema 独立判断。
-- 执行：mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-12-07-eval-task-type.sql

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

CALL emonlens_add_column_if_missing('et_eval_task', 'evaluator_type',
    'VARCHAR(32) NULL');

DROP PROCEDURE IF EXISTS emonlens_add_column_if_missing;
