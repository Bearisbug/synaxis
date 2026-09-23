-- docs/DESIGN-PROD.md §7, §9 and §25 V4: bans and the whitelist (API-PROD-009).
-- Neither has a foreign key to account: an account can be banned or invited before it ever logged
-- in (§9). A ban is in force while revoked_at is null and expires_at is null or later than now()
-- (§11: derived, no state column). created_by and revoked_by are the operators, the nil UUID for
-- the console.

CREATE TABLE ban (
    ban_id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind         text        NOT NULL CHECK (kind IN ('uuid', 'network')),
    subject_uuid uuid,
    subject_ip   cidr,
    reason       text        NOT NULL CHECK (char_length(reason) BETWEEN 1 AND 256),
    created_at   timestamptz NOT NULL DEFAULT now(),
    created_by   uuid        NOT NULL,
    expires_at   timestamptz,
    revoked_at   timestamptz,
    revoked_by   uuid,
    CHECK ((kind = 'uuid' AND subject_uuid IS NOT NULL AND subject_ip IS NULL)
        OR (kind = 'network' AND subject_uuid IS NULL AND subject_ip IS NOT NULL
            AND ((family(subject_ip) = 4 AND masklen(subject_ip) BETWEEN 8 AND 32)
              OR (family(subject_ip) = 6 AND masklen(subject_ip) BETWEEN 32 AND 128))))
);
-- What every shard reads every 30 s: the bans not lifted
CREATE INDEX ban_not_revoked ON ban (ban_id) WHERE revoked_at IS NULL;

CREATE TABLE whitelist (
    account_uuid uuid PRIMARY KEY,
    added_at     timestamptz NOT NULL DEFAULT now()
);
