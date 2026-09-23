#!/bin/sh
# 打开 WAL 归档：以数据库服务器上的 postgres 用户执行，连本机超级用户（libpq 环境变量 PGHOST / PGPORT / PGUSER 照常生效）。
# 写的是 postgresql.auto.conf（ALTER SYSTEM），执行完必须重启 PostgreSQL 才生效。
set -eu

root=${BACKUP_ROOT:-/backup}
bin=$(cd "$(dirname "$0")" && pwd)

mkdir -p "$root/wal" "$root/base"
[ -w "$root/wal" ] || { echo "enable-wal-archive: $root/wal is not writable by $(id -un)" >&2; exit 1; }

psql -v ON_ERROR_STOP=1 -X -q <<SQL
ALTER SYSTEM SET wal_level = 'replica';
ALTER SYSTEM SET archive_mode = 'on';
ALTER SYSTEM SET archive_command = '$bin/archive-wal.sh %p %f $root/wal';
ALTER SYSTEM SET archive_timeout = '60s';
SELECT pg_reload_conf() \g /dev/null
SQL

psql -X -At -c "SELECT name || ' pending_restart=' || pending_restart FROM pg_settings WHERE name IN ('wal_level', 'archive_mode', 'archive_command', 'archive_timeout') ORDER BY name"
echo "enable-wal-archive: restart PostgreSQL to apply wal_level and archive_mode"
