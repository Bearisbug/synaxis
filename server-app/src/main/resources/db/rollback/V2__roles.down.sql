-- Rollback of V2__roles.sql (docs/DESIGN-PROD.md §25). Not run by Flyway: an operator runs it by
-- hand when the migration itself was wrong (RB-PROD-002).

DROP TABLE account_role;
DROP TABLE role_permission;
DROP TABLE role;
DELETE FROM flyway_schema_history WHERE version = '2';
