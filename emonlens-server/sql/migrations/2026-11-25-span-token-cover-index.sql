-- 用量聚合的覆盖索引；apply-once ledger: 2026-11-25-span-token-cover-index
-- 执行：mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-11-25-span-token-cover-index.sql
--
-- 背景：「洞察与成本」按 trace 聚合 tokens（SUM(input/output/cached) GROUP BY trace_id），
-- 原先只有 uk_span(workspace_id, trace_id, span_id)，token 列不在索引里，每行都要回表。
-- 实测 7.3 万行 span 上是 2.2 秒；把 token 列并进索引变成覆盖扫描后降到 0.29 秒。

SET @has_idx := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'et_span_event'
      AND index_name = 'idx_span_token_cover');
SET @sql := CASE
    WHEN @has_idx = 0
        THEN 'ALTER TABLE et_span_event ADD KEY idx_span_token_cover (workspace_id, trace_id, input_tokens, output_tokens, cached_input_tokens)'
    ELSE 'SELECT 1' END;
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
