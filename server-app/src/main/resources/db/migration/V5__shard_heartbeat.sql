-- docs/DESIGN-M3.md API-M3-010 and §25, docs/DESIGN-PROD.md §25 V5: the heartbeat of the map shards
-- in the directory mode. A shard overwrites its own row every 5 s; a row counts as live while
-- updated_at > now() - interval '15 seconds', judged by the database clock. Rows are never deleted:
-- a stopped or crashed shard drops out of the choice of a channel by expiring.

CREATE TABLE shard_heartbeat (
    shard_id   text        PRIMARY KEY,
    map_id     text        NOT NULL,
    region_id  text        NOT NULL,
    channel    int         NOT NULL CHECK (channel BETWEEN 1 AND 999),
    host       text        NOT NULL CHECK (char_length(host) BETWEEN 1 AND 253),
    port       int         NOT NULL CHECK (port BETWEEN 1 AND 65535),
    build_id   text        NOT NULL,
    online     int         NOT NULL CHECK (online >= 0),
    capacity   int         NOT NULL CHECK (capacity BETWEEN 1 AND 500),
    accepting  boolean     NOT NULL,
    started_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
-- What a source shard reads to choose a channel of the target region
CREATE INDEX shard_heartbeat_region ON shard_heartbeat (map_id, region_id);
