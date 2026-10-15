-- Masters that the control web will manage: centers, their zones and drivers, and which center a Job belongs to.
-- Rows come from the control web or the master-data import tool, never from this file. Do not edit after merge.
CREATE TABLE center (
    center_code        TEXT PRIMARY KEY,
    name_ko            TEXT NOT NULL DEFAULT '',
    city_en            TEXT NOT NULL DEFAULT '',
    depot_name         TEXT NOT NULL DEFAULT '',
    depot_address_ko   TEXT NOT NULL DEFAULT '',
    default_quantity   INTEGER NOT NULL DEFAULT 1,
    default_task_min   INTEGER NOT NULL DEFAULT 5,
    notify_sender      TEXT NOT NULL DEFAULT 'UPS',
    notify_on_complete BOOLEAN NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE zone (
    zone_id     CHAR(36) PRIMARY KEY,
    center_code TEXT NOT NULL REFERENCES center (center_code),
    name        TEXT NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT zone_name UNIQUE (center_code, name)
);

-- driver_code is the DRIVER ID of the UPS master sheet. Names and phone numbers of drivers are not stored.
CREATE TABLE driver (
    driver_id   CHAR(36) PRIMARY KEY,
    center_code TEXT NOT NULL REFERENCES center (center_code),
    driver_code TEXT NOT NULL,
    vehicle_no  TEXT,
    max_load    INTEGER,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT driver_code UNIQUE (center_code, driver_code)
);

CREATE TABLE driver_zone (
    driver_id CHAR(36) NOT NULL REFERENCES driver (driver_id),
    zone_id   CHAR(36) NOT NULL REFERENCES zone (zone_id),
    PRIMARY KEY (driver_id, zone_id)
);

-- A Job is given to one center, once: the primary key makes a second assignment impossible.
-- Until the split of the shared Ours24 feed is decided, local runs use the default center (method DEFAULT).
CREATE TABLE job_center (
    job_id      TEXT PRIMARY KEY,
    center_code TEXT NOT NULL REFERENCES center (center_code),
    method      TEXT NOT NULL,
    assigned_by CHAR(36),
    assigned_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT job_center_method CHECK (method IN ('DEFAULT', 'MANUAL', 'RULE'))
);
