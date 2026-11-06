-- 评估结果结论原因码落库：JUDGE_DISAGREEMENT / JUDGE_MEAN_BELOW_MIN_SCORE / INVALID_JUDGE_OUTPUT 等。
-- 之前该字段只在内存里传递，结果读回来时原因码丢失，界面只能看到一个没有解释的 UNKNOWN，
-- 事后也无法按"为什么判成这样"筛选与复盘。
-- apply-once ledger: 2026-11-06-eval-result-reason-code
-- 幂等：列按 information_schema 独立判断。
-- 执行：mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-11-06-eval-result-reason-code.sql

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

CALL emonlens_add_column_if_missing('et_eval_result', 'reason_code',
    'VARCHAR(64) NULL');

DROP PROCEDURE IF EXISTS emonlens_add_column_if_missing;
