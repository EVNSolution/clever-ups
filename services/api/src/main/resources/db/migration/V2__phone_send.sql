-- Every phone-verification message the server decides to send is written here BEFORE it is sent.
-- One row per attempt; the row is the proof that a send was allowed and the count behind the daily cap.
-- Do not edit after merge; add V3__... instead.
CREATE TABLE phone_send_gate (
    id INTEGER PRIMARY KEY,
    CONSTRAINT phone_send_gate_single CHECK (id = 1)
);
-- The one row is locked (SELECT ... FOR UPDATE) to put every reservation in a line, on every slot of a deploy.
INSERT INTO phone_send_gate (id) VALUES (1);

CREATE TABLE phone_send (
    send_id      CHAR(36) PRIMARY KEY,
    request_key  TEXT NOT NULL,
    account_id   TEXT NOT NULL,
    phone        TEXT NOT NULL,
    status       TEXT NOT NULL,
    provider_ref TEXT,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    -- The same request can never create a second row, so it can never cause a second message.
    CONSTRAINT phone_send_request UNIQUE (request_key),
    CONSTRAINT phone_send_status CHECK (status IN ('RESERVED', 'SENT', 'FAILED', 'UNKNOWN'))
);

CREATE INDEX phone_send_recent ON phone_send (created_at);
CREATE INDEX phone_send_phone ON phone_send (phone, created_at);
