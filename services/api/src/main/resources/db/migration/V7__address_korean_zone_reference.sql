-- The Korean form of an address beyond the one line, its check against a reference, its zone, and the reference
-- set itself (pairs taken from the Fleetune upload file). Do not edit after merge.
ALTER TABLE address_location ADD COLUMN detail_ko TEXT;
ALTER TABLE address_location ADD COLUMN ko_source TEXT;
ALTER TABLE address_location ADD COLUMN verify_status TEXT NOT NULL DEFAULT 'UNVERIFIED';
ALTER TABLE address_location ADD COLUMN verified_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE address_location ADD CONSTRAINT address_location_ko_source
    CHECK (ko_source IS NULL OR ko_source IN ('TRANSLATOR', 'REFERENCE', 'MANUAL'));
ALTER TABLE address_location ADD CONSTRAINT address_location_verify
    CHECK (verify_status IN ('UNVERIFIED', 'MATCH', 'MISMATCH', 'NO_REFERENCE'));

-- The delivery marker: which zone an address falls in.
CREATE TABLE address_zone (
    address_key CHAR(64) PRIMARY KEY REFERENCES address_location (address_key),
    zone_id     CHAR(36) NOT NULL REFERENCES zone (zone_id),
    method      TEXT NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT address_zone_method CHECK (method IN ('DONG_MATCH', 'MANUAL'))
);

-- Ground truth for checking a translator: the Korean address a person or the old system wrote for an address.
-- tracking_hash is a SHA-256 of the tracking number; the number itself is not kept.
CREATE TABLE address_reference (
    address_key   CHAR(64) PRIMARY KEY REFERENCES address_location (address_key),
    address_ko    TEXT NOT NULL,
    detail_ko     TEXT,
    postal_code   TEXT,
    tracking_hash CHAR(64),
    source        TEXT NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
