-- Rollback of V1__player_store.sql (docs/DESIGN-PROD.md §25). Not run by Flyway: an operator runs
-- it by hand when the migration itself was wrong (RB-PROD-002), after V2 has been rolled back.

DROP TABLE transfer_nonce;
DROP TABLE character_section;
DROP TABLE character;
DROP TABLE shard_session;
DROP TABLE account;
DROP TABLE store_meta;
DELETE FROM flyway_schema_history WHERE version = '1';
