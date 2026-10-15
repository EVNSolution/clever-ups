-- Public administrative boundaries, imported from a file (see infra/boundaries/README.md), not typed by people.
-- Levels: DONG is a 행정동 (code: 행안부 10 digits), SGG a 시·군·구 or 일반구 (5 digits), CITY a 시 that has 일반구
-- (the first 4 digits of its 구 and 0), made by joining them. A DONG's parent is its SGG; an 일반구's parent is its
-- CITY. `geometry` is a GeoJSON geometry in WGS84 longitude and latitude; the box columns let a reader pick the
-- shapes in view without a spatial extension. An import replaces every row. Do not edit after merge.
CREATE TABLE admin_boundary (
    code        TEXT PRIMARY KEY,
    level       TEXT NOT NULL,
    name        TEXT NOT NULL,
    parent_code TEXT REFERENCES admin_boundary (code),
    stat_code   TEXT,
    geometry    TEXT NOT NULL,
    min_lng     DOUBLE PRECISION NOT NULL,
    min_lat     DOUBLE PRECISION NOT NULL,
    max_lng     DOUBLE PRECISION NOT NULL,
    max_lat     DOUBLE PRECISION NOT NULL,
    source      TEXT NOT NULL,
    as_of       DATE NOT NULL,
    imported_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT admin_boundary_level CHECK (level IN ('CITY', 'SGG', 'DONG'))
);

CREATE INDEX admin_boundary_parent ON admin_boundary (parent_code);
CREATE INDEX admin_boundary_box ON admin_boundary (level, min_lng, max_lng, min_lat, max_lat);
