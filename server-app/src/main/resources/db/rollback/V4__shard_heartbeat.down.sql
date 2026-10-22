-- Rollback of V4__shard_heartbeat.sql (docs/DESIGN-PROD.md §25, docs/DESIGN-M3.md §25). Not run by
-- Flyway: an operator runs it by hand when the migration itself was wrong (RB-PROD-002), after V5
-- has been rolled back.

DROP TABLE shard_heartbeat;
DELETE FROM flyway_schema_history WHERE version = '4';
