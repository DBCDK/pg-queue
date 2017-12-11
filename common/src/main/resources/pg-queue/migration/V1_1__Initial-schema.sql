
ALTER TABLE queue ADD COLUMN consumer VARCHAR(32) NOT NULL DEFAULT 'default';
ALTER TABLE queue ALTER COLUMN consumer DROP DEFAULT;
ALTER TABLE queue ADD COLUMN queued TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp();
ALTER TABLE queue ADD COLUMN dequeueAfter TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp();
ALTER TABLE queue ADD COLUMN tries INTEGER  NOT NULL DEFAULT 0;

ALTER TABLE queue_error ADD COLUMN consumer VARCHAR(32) NOT NULL;
ALTER TABLE queue_error ADD COLUMN queued TIMESTAMP WITH TIME ZONE NOT NULL;
ALTER TABLE queue_error ADD COLUMN failedAt TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp();
ALTER TABLE queue_error ADD COLUMN diag TEXT NOT NULL;

CREATE INDEX queue_take ON queue (consumer, dequeueAfter);
CREATE INDEX queue_error_time ON queue_error (consumer, queued);
CREATE INDEX queue_error_type ON queue_error (consumer, diag);

ALTER TABLE queue SET (autovacuum_vacuum_threshold = 25000);
ALTER TABLE queue SET (autovacuum_vacuum_scale_factor = 0.00025);
ALTER TABLE queue SET (autovacuum_vacuum_cost_limit = 10000);
ALTER TABLE queue SET (autovacuum_analyze_threshold = 500000);
ALTER TABLE queue SET (autovacuum_analyze_scale_factor = 0.005);
