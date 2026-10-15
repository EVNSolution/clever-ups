-- What happened at each stop, reported by the driver app: arrival, then delivered (KB) or an exception code. A
-- delivery is face to face (the recipient's name typed) or at the door (a photo). Photos are files on the server;
-- this table keeps who took them and where they are. Kept apart from dispatch_stop, so a new run does not erase
-- what drivers did. Do not edit after merge.
CREATE TABLE delivery_result (
    plan_id        CHAR(36) NOT NULL,
    task_key       TEXT NOT NULL,
    driver_id      CHAR(36) NOT NULL REFERENCES driver (driver_id),
    arrived_at     TIMESTAMP WITH TIME ZONE,
    completed_at   TIMESTAMP WITH TIME ZONE,
    outcome        TEXT,
    code           TEXT,
    density        TEXT,
    pod_method     TEXT,
    recipient_name TEXT,
    lat            DOUBLE PRECISION,
    lng            DOUBLE PRECISION,
    PRIMARY KEY (plan_id, task_key),
    FOREIGN KEY (plan_id, task_key) REFERENCES dispatch_stop (plan_id, task_key),
    CONSTRAINT delivery_result_outcome CHECK (outcome IS NULL OR outcome IN ('DELIVERED', 'EXCEPTION')),
    CONSTRAINT delivery_result_done CHECK ((outcome IS NULL) = (completed_at IS NULL)),
    -- KB is the one delivered code; every other UPS code is an exception.
    CONSTRAINT delivery_result_code CHECK (outcome IS NULL OR (outcome = 'DELIVERED') = (code = 'KB')),
    CONSTRAINT delivery_result_density CHECK (density IS NULL OR density IN ('C', 'R')),
    CONSTRAINT delivery_result_pod CHECK (pod_method IS NULL OR pod_method IN ('FACE', 'DOOR'))
);

CREATE TABLE delivery_photo (
    photo_id     CHAR(36) PRIMARY KEY,
    plan_id      CHAR(36) NOT NULL,
    task_key     TEXT NOT NULL,
    content_type TEXT NOT NULL,
    size_bytes   INTEGER NOT NULL,
    taken_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (plan_id, task_key) REFERENCES delivery_result (plan_id, task_key)
);

CREATE INDEX delivery_photo_stop ON delivery_photo (plan_id, task_key);
