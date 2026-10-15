-- A driver signs in to the driver app with an ordinary account whose role is `driver`. The account is tied to one
-- driver of one center; a driver account reaches only its own routes, never the center's lists. Do not edit after merge.
ALTER TABLE membership DROP CONSTRAINT membership_role;
ALTER TABLE membership ADD CONSTRAINT membership_role CHECK (role IS NULL OR role IN ('owner', 'admin', 'member', 'viewer', 'driver'));

ALTER TABLE access_decision DROP CONSTRAINT access_decision_action;
ALTER TABLE access_decision ADD CONSTRAINT access_decision_action CHECK (action IN ('APPROVE', 'REJECT', 'REVOKE', 'LINK_DRIVER'));

CREATE TABLE driver_account (
    account_id CHAR(36) PRIMARY KEY REFERENCES account (account_id),
    driver_id  CHAR(36) NOT NULL REFERENCES driver (driver_id),
    linked_by  CHAR(36) NOT NULL,
    linked_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- One account per driver: two phones signed in as the same driver would split the route.
    CONSTRAINT driver_account_driver UNIQUE (driver_id)
);
