-- A driver has one main zone (메인 권역) and may also serve other zones (driver_zone). One column keeps it to one.
-- Do not edit after merge.
ALTER TABLE driver ADD COLUMN main_zone_id CHAR(36) REFERENCES zone (zone_id);
