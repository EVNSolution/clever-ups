-- The shape of a zone (권역) and its place in two levels: a zone with a parent is a 세부권역 inside that zone. The
-- shape is a GeoJSON geometry in WGS84, made by the API from 행정동·시 boundaries or a drawn polygon; it never
-- overlaps another zone of the same center and level, and a 세부권역 stays inside its parent. Null until drawn.
-- The box columns let a reader find the zone of a point without a spatial extension. Do not edit after merge.
ALTER TABLE zone ADD COLUMN parent_zone_id CHAR(36) REFERENCES zone (zone_id);
ALTER TABLE zone ADD COLUMN shape TEXT;
ALTER TABLE zone ADD COLUMN min_lng DOUBLE PRECISION;
ALTER TABLE zone ADD COLUMN min_lat DOUBLE PRECISION;
ALTER TABLE zone ADD COLUMN max_lng DOUBLE PRECISION;
ALTER TABLE zone ADD COLUMN max_lat DOUBLE PRECISION;
ALTER TABLE zone ADD COLUMN shape_by CHAR(36);
ALTER TABLE zone ADD COLUMN shape_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE zone ADD CONSTRAINT zone_not_own_parent CHECK (parent_zone_id IS NULL OR parent_zone_id <> zone_id);

CREATE INDEX zone_parent ON zone (parent_zone_id);
