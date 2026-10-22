-- Rollback of V3__moderation.sql (docs/DESIGN-PROD.md §25). Not run by Flyway: an operator runs it
-- by hand when the migration itself was wrong (RB-PROD-002).

DROP TABLE whitelist;
DROP TABLE ban;
DELETE FROM flyway_schema_history WHERE version = '3';
