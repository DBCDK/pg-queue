

CREATE TABLE queue (
  consumer VARCHAR(32) NOT NULL,
  dequeueAfter TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
  tries INTEGER  NOT NULL DEFAULT 0,
--- The actual Job
  job TEXT NOT NULL
);


CREATE TABLE queue_error (
  consumer VARCHAR(32) NOT NULL,
  dequeueAfter TIMESTAMP WITH TIME ZONE NOT NULL,
  failedAt TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
  diag TEXT NOT NULL,
--- The actual Job
  job TEXT NOT NULL
);


CREATE INDEX queue_take ON queue (consumer, dequeueAfter);
