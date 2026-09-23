#!/bin/sh
# 时间点恢复的准备步骤：把一份基础备份解到一个空的新数据目录，写好恢复参数。
#   restore-pitr.sh <基础备份目录> <目标时间，带时区> <新数据目录>
# 之后用新数据目录起 PostgreSQL（同一大版本），它从 $BACKUP_ROOT/wal 回放到目标时间后自动 promote。
# 以 postgres 用户执行；恢复出来的实例关掉归档，不往原库的归档目录写。
set -eu

backup=$1
target=$2
data=$3
root=${BACKUP_ROOT:-/backup}

[ -f "$backup/base.tar.gz" ] || { echo "restore-pitr: $backup/base.tar.gz not found" >&2; exit 1; }
if [ -e "$data" ] && [ -n "$(ls -A "$data")" ]; then
    echo "restore-pitr: $data is not empty" >&2
    exit 1
fi

mkdir -p "$data"
chmod 700 "$data"
tar -xzf "$backup/base.tar.gz" -C "$data"
tar -xzf "$backup/pg_wal.tar.gz" -C "$data/pg_wal"

# postgresql.auto.conf 里后写的同名参数生效，覆盖备份里带过来的 archive_mode = on
cat >> "$data/postgresql.auto.conf" <<CONF
restore_command = 'test -f $root/wal/%f.gz && gzip -dc $root/wal/%f.gz > %p'
recovery_target_time = '$target'
recovery_target_action = 'promote'
archive_mode = 'off'
CONF
touch "$data/recovery.signal"
echo "restore-pitr: $data ready, recovery target $target"
