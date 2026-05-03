-- Top-N slow query analytics. Requires shared_preload_libraries=pg_stat_statements
-- (set on the postgres container in docker-compose.yml). The extension itself
-- is a per-database object; create idempotently so dropping/recreating the DB
-- still works.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
