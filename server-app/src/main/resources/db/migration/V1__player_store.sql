-- docs/DESIGN-PROD.md §7, §9 and §25 V1: the player store of the host. PostgreSQL is its only
-- authority; the right to write a character is character.lease_session_id, checked together with
-- character.version in every write (API-PROD-004).

CREATE TABLE store_meta (
    key   text PRIMARY KEY,
    value text NOT NULL
);

CREATE TABLE account (
    account_uuid   uuid PRIMARY KEY,
    last_name      text        NOT NULL CHECK (char_length(last_name) BETWEEN 3 AND 16),
    first_login_at timestamptz NOT NULL DEFAULT now(),
    last_login_at  timestamptz NOT NULL DEFAULT now()
);

-- One row per run of a shard; session_id is the epoch every lease points at. Released sessions
-- are only made to expire, never deleted by the shard: character rows still point at them.
CREATE TABLE shard_session (
    session_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shard_id   text        NOT NULL,
    started_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL
);
CREATE INDEX shard_session_shard_id ON shard_session (shard_id);

CREATE TABLE character (
    character_id     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_uuid     uuid     NOT NULL REFERENCES account (account_uuid),
    slot             smallint NOT NULL CHECK (slot >= 0),
    version          bigint   NOT NULL DEFAULT 0 CHECK (version >= 0),
    lease_session_id bigint REFERENCES shard_session (session_id),
    last_saved_at    timestamptz,
    UNIQUE (account_uuid, slot)
);
CREATE INDEX character_lease_session_id ON character (lease_session_id) WHERE lease_session_id IS NOT NULL;

-- A plugin section; 65,536 bytes of JSON at most (§9)
CREATE TABLE character_section (
    character_id   bigint      NOT NULL REFERENCES character (character_id) ON DELETE CASCADE,
    section_id     text        NOT NULL,
    schema_version int         NOT NULL CHECK (schema_version >= 0),
    data           jsonb       NOT NULL CHECK (octet_length(data::text) <= 65536),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (character_id, section_id)
);

-- The single use nonces of transfer tickets (API-PROD-028); rows past expires_at are deleted by
-- transfer_nonce.gc (§17)
CREATE TABLE transfer_nonce (
    nonce      bytea PRIMARY KEY CHECK (octet_length(nonce) = 16),
    expires_at timestamptz NOT NULL
);
