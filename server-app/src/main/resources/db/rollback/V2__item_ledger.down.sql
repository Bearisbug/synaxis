-- Rollback of V2__item_ledger.sql (docs/DESIGN-PROD.md §25). Not run by Flyway: an operator runs
-- it by hand when the migration itself was wrong (RB-PROD-002).

DROP TABLE item_ledger;
DELETE FROM flyway_schema_history WHERE version = '2';
