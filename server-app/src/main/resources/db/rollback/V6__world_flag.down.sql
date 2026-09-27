-- Rollback of V6__world_flag.sql (docs/DESIGN-PROD.md §25, docs/DESIGN-M3.md §25). Not run by
-- Flyway: an operator runs it by hand when the migration itself was wrong (RB-PROD-002).

DROP TABLE world_flag;
DELETE FROM flyway_schema_history WHERE version = '6';
