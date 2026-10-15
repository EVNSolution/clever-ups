-- Sign-up, access requests and who decided what. Do not edit after merge; add V5__... instead.
--
-- An access request holds what the 개발 관리자 decides on. The personal columns are nullable: they are
-- cleared when a request is no longer needed (see AccessRequestService.housekeeping).
CREATE TABLE access_request (
    request_id     CHAR(36) PRIMARY KEY,
    account_id     CHAR(36) NOT NULL REFERENCES account (account_id),
    full_name      TEXT,
    center_code    TEXT,
    driver_id      TEXT,
    phone          TEXT,
    -- True only after the phone proof (Firebase) has passed. Nothing sets it yet.
    phone_verified BOOLEAN NOT NULL DEFAULT FALSE,
    terms_version  TEXT NOT NULL,
    status         TEXT NOT NULL,
    decided_by     CHAR(36),
    decided_at     TIMESTAMP WITH TIME ZONE,
    decision_role  TEXT,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT access_request_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED'))
);

CREATE INDEX access_request_queue ON access_request (status, created_at);
CREATE INDEX access_request_account ON access_request (account_id);

-- The centers an account may use. The center list belongs to the account, not to the role.
CREATE TABLE account_center (
    account_id  CHAR(36) NOT NULL REFERENCES account (account_id),
    center_code TEXT NOT NULL,
    granted_by  CHAR(36),
    granted_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (account_id, center_code)
);

-- Append only: the code never updates or deletes a row here. `evidence` holds flags, not personal data.
CREATE TABLE access_decision (
    decision_id  CHAR(36) PRIMARY KEY,
    request_id   CHAR(36),
    account_id   CHAR(36) NOT NULL,
    actor_id     CHAR(36) NOT NULL,
    action       TEXT NOT NULL,
    role         TEXT,
    center_codes TEXT,
    evidence     TEXT,
    decided_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT access_decision_action CHECK (action IN ('APPROVE', 'REJECT', 'REVOKE'))
);

CREATE INDEX access_decision_account ON access_decision (account_id, decided_at);
