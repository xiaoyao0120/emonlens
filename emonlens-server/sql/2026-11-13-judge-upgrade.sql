-- R3-3 Judge upgrade: keep the raw request/response of every judge model for audit.
ALTER TABLE et_eval_result ADD COLUMN judge_records_json TEXT COMMENT 'multi-judge raw records (JSON array)';
