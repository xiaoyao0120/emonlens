-- R3-4 multimodal: dataset samples carry image attachments (URL or base64) alongside text.
ALTER TABLE et_dataset_item ADD COLUMN attachments_json TEXT COMMENT 'multimodal attachments (JSON array)';
ALTER TABLE et_dataset_revision_item ADD COLUMN attachments_json TEXT COMMENT 'multimodal attachments (frozen with revision)';
