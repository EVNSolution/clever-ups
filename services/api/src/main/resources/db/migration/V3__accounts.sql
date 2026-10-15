-- Login, sessions and who may see data. One organization (UPS) for now, so a membership belongs to one account.
-- When a second organization exists, add organization_id with a new migration. Do not edit after merge.
CREATE TABLE account (
    account_id CHAR(36) PRIMARY KEY,
    login_id   TEXT NOT NULL,
    -- The login ID in lower case. Two IDs that differ only in case are the same ID.
    login_key  TEXT NOT NULL,
    status     TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT account_login_key UNIQUE (login_key),
    CONSTRAINT account_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

-- The password is kept apart from the account, so a sign-in method that has no password can be added later.
CREATE TABLE password_credential (
    account_id    CHAR(36) PRIMARY KEY REFERENCES account (account_id),
    password_hash TEXT NOT NULL,
    changed_at    TIMESTAMP WITH TIME ZONE NOT NULL
);

-- No row means "no membership": the account can sign in and can see no data.
CREATE TABLE membership (
    account_id CHAR(36) PRIMARY KEY REFERENCES account (account_id),
    role       TEXT,
    status     TEXT NOT NULL,
    decided_by CHAR(36),
    decided_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT membership_status CHECK (status IN ('PENDING', 'ACTIVE', 'REJECTED', 'REVOKED')),
    CONSTRAINT membership_role CHECK (role IS NULL OR role IN ('owner', 'admin', 'member', 'viewer')),
    -- An active membership always has a role.
    CONSTRAINT membership_active_role CHECK (status <> 'ACTIVE' OR role IS NOT NULL)
);

-- One row per sign-in. It holds two secrets, both stored only as SHA-256, so a copy of this table cannot be
-- used to sign in: the access token (short, sent with every request) and the refresh token (long, sent only
-- to /api/auth/refresh, and replaced at every use). The row is the "family": revoking it ends both.
CREATE TABLE session (
    session_id             CHAR(36) PRIMARY KEY,
    account_id             CHAR(36) NOT NULL REFERENCES account (account_id),
    csrf_token             TEXT NOT NULL,
    access_hash            CHAR(64) NOT NULL,
    access_expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    refresh_hash           CHAR(64) NOT NULL,
    refresh_expires_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    -- The refresh token that was replaced last. Seeing it again later means a copy was stolen.
    previous_refresh_hash  CHAR(64),
    previous_rotated_at    TIMESTAMP WITH TIME ZONE,
    absolute_expires_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at             TIMESTAMP WITH TIME ZONE,
    CONSTRAINT session_access UNIQUE (access_hash),
    CONSTRAINT session_refresh UNIQUE (refresh_hash)
);

CREATE INDEX session_account ON session (account_id);
CREATE INDEX session_previous_refresh ON session (previous_refresh_hash);

-- Failed and successful sign-in attempts, to lock repeated guessing. Counted by login key, so an unknown
-- ID is locked the same way as a known one and the lock does not show which IDs exist.
CREATE TABLE login_attempt (
    attempt_id   CHAR(36) PRIMARY KEY,
    login_key    TEXT NOT NULL,
    succeeded    BOOLEAN NOT NULL,
    attempted_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX login_attempt_recent ON login_attempt (login_key, attempted_at);
