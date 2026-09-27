-- docs/DESIGN-M3.md API-M3-013 and §25, docs/DESIGN-PROD.md §25 V6: the world state flags, the
-- world state every channel of a region and every map release share. A write is conditional on
-- version (new key 0, written as version + 1); a shard only reads and writes the rows of its own
-- map_id and region_id.

CREATE TABLE world_flag (
    map_id     text        NOT NULL,
    region_id  text        NOT NULL,
    flag_key   text        NOT NULL CHECK (flag_key ~ '^[a-z0-9_.-]+:[a-z0-9_./-]+$' AND char_length(flag_key) <= 64),
    value      jsonb       NOT NULL CHECK (octet_length(value::text) <= 4096),
    version    bigint      NOT NULL CHECK (version >= 1),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (map_id, region_id, flag_key)
);
