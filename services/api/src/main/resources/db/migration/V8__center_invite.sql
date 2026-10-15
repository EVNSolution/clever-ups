-- A center entry code: made by the owner for one center and one role, used once, valid for a few days.
-- Only the hash of the code is kept; the code itself is shown once when it is made. Do not edit after merge.
CREATE TABLE center_invite (
    code_hash   CHAR(64) PRIMARY KEY,
    center_code TEXT NOT NULL,
    role        TEXT NOT NULL,
    created_by  CHAR(36) NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    used_by     CHAR(36),
    used_at     TIMESTAMP WITH TIME ZONE,
    CONSTRAINT center_invite_role CHECK (role IN ('member', 'viewer')),
    CONSTRAINT center_invite_used CHECK ((used_by IS NULL) = (used_at IS NULL))
);

CREATE INDEX center_invite_open ON center_invite (created_by, created_at);
