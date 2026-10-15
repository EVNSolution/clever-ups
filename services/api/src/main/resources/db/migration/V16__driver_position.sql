-- The latest place a driver's phone reported, one row per driver. No history is kept: only "where is the driver
-- now" is needed for 운행 현황, and a trail of a person's movements is not. Do not edit after merge.
CREATE TABLE driver_position (
    driver_id   CHAR(36) PRIMARY KEY REFERENCES driver (driver_id),
    lat         DOUBLE PRECISION NOT NULL,
    lng         DOUBLE PRECISION NOT NULL,
    accuracy_m  DOUBLE PRECISION,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT driver_position_lat CHECK (lat >= -90 AND lat <= 90),
    CONSTRAINT driver_position_lng CHECK (lng >= -180 AND lng <= 180)
);
