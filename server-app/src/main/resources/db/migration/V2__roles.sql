-- docs/DESIGN-PROD.md §7, §9 and §25 V2: roles and the permission nodes they grant (API-PROD-010).
-- A node is lower case and dotted; its last segment may be * (gm.* grants every node under gm.).

CREATE TABLE role (
    role_id text PRIMARY KEY
);

CREATE TABLE role_permission (
    role_id text NOT NULL REFERENCES role (role_id) ON DELETE CASCADE,
    node    text NOT NULL CHECK (node ~ '^([a-z0-9_-]+\.)*([a-z0-9_-]+|\*)$'),
    PRIMARY KEY (role_id, node)
);

-- No foreign key to account: a role can be granted to an account that has never logged in (§9).
-- A null expires_at never expires.
CREATE TABLE account_role (
    account_uuid uuid NOT NULL,
    role_id      text NOT NULL REFERENCES role (role_id) ON DELETE CASCADE,
    expires_at   timestamptz,
    PRIMARY KEY (account_uuid, role_id)
);
