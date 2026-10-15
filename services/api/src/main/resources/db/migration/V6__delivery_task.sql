-- One row of an Ours24 Job. The original address stays in address_location; this row only points to it.
-- No customer name and no phone number are kept here. The center comes from job_center. Do not edit after merge.
CREATE TABLE delivery_task (
    task_key        TEXT PRIMARY KEY,
    job_id          TEXT NOT NULL,
    row_no          INTEGER NOT NULL,
    tracking_number TEXT NOT NULL,
    status          TEXT,
    address_key     CHAR(64) NOT NULL REFERENCES address_location (address_key),
    job_created_at  TIMESTAMP WITH TIME ZONE,
    first_seen_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT delivery_task_row UNIQUE (job_id, tracking_number)
);

CREATE INDEX delivery_task_address ON delivery_task (address_key);
CREATE INDEX delivery_task_job ON delivery_task (job_id, row_no);
