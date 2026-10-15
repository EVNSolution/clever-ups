-- One code may carry several centers: a person who manages many centers types one code, not one per center.
-- The single center of V8 moves into this table. Do not edit after merge.
CREATE TABLE center_invite_center (
    code_hash   CHAR(64) NOT NULL REFERENCES center_invite (code_hash),
    center_code TEXT NOT NULL,
    PRIMARY KEY (code_hash, center_code)
);

INSERT INTO center_invite_center (code_hash, center_code)
SELECT code_hash, center_code FROM center_invite;

ALTER TABLE center_invite DROP COLUMN center_code;
