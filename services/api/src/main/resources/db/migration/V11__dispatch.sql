-- A saved dispatch: one run for one center and one operating day, the route of each driver, and every order of
-- the run as a stop. Values are kept as they were computed, so what a driver received stays what the web shows.
-- Actual arrival, completion and proof of delivery go to a separate table with the driver app. Do not edit after merge.
CREATE TABLE dispatch_plan (
    plan_id      CHAR(36) PRIMARY KEY,
    center_code  TEXT NOT NULL REFERENCES center (center_code),
    service_day  DATE NOT NULL,
    method       TEXT NOT NULL,
    status       TEXT NOT NULL DEFAULT 'DRAFT',
    depart_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by   CHAR(36) NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT dispatch_plan_method CHECK (method IN ('ZONE')),
    -- A new run of the same center and day marks the earlier one REPLACED. Only one may be SENT (enforced on send).
    CONSTRAINT dispatch_plan_status CHECK (status IN ('DRAFT', 'SENT', 'CANCELLED', 'REPLACED'))
);

CREATE INDEX dispatch_plan_day ON dispatch_plan (center_code, service_day);

-- route_code is the representative zone code of the driver, reported to UPS as `loop`.
CREATE TABLE dispatch_route (
    route_id    CHAR(36) PRIMARY KEY,
    plan_id     CHAR(36) NOT NULL REFERENCES dispatch_plan (plan_id),
    driver_id   CHAR(36) NOT NULL REFERENCES driver (driver_id),
    route_code  TEXT,
    depart_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    start_lng   DOUBLE PRECISION,
    start_lat   DOUBLE PRECISION,
    distance_m  INTEGER NOT NULL,
    duration_s  INTEGER NOT NULL,
    geometry    TEXT,
    status      TEXT NOT NULL DEFAULT 'PLANNED',
    CONSTRAINT dispatch_route_driver UNIQUE (plan_id, driver_id),
    CONSTRAINT dispatch_route_status CHECK (status IN ('PLANNED', 'SENT', 'RUNNING', 'DONE'))
);

-- Every order of the run. An order no driver got has no route and no number.
CREATE TABLE dispatch_stop (
    plan_id          CHAR(36) NOT NULL REFERENCES dispatch_plan (plan_id),
    task_key         TEXT NOT NULL REFERENCES delivery_task (task_key),
    route_id         CHAR(36) REFERENCES dispatch_route (route_id),
    seq              INTEGER,
    planned_arrival  TIMESTAMP WITH TIME ZONE,
    distance_m       INTEGER,
    PRIMARY KEY (plan_id, task_key),
    CONSTRAINT dispatch_stop_placed CHECK ((route_id IS NULL) = (seq IS NULL))
);

CREATE INDEX dispatch_stop_route ON dispatch_stop (route_id, seq);
