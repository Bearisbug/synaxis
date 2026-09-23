#!/bin/sh
# 每日基础备份：pg_basebackup 打成 tar.gz 放进 $BACKUP_ROOT/base/<UTC 时间戳>/，
# 只留最新 $BACKUP_KEEP 份，并删掉最旧一份之前的归档 WAL。以数据库服务器上的 postgres 用户执行。
set -eu

root=${BACKUP_ROOT:-/backup}
keep=${BACKUP_KEEP:-7}
checkpoint=${BACKUP_CHECKPOINT:-spread}

stamp=$(date -u +%Y%m%dT%H%M%SZ)
dest="$root/base/$stamp"
tmp="$root/base/.$stamp.partial"
mkdir -p "$root/base"
# 上次被 SIGKILL、OOM 或断电打断时 EXIT trap 没机会跑，留下的半份在这里删掉；前提是同一时刻只跑一个本脚本
rm -rf "$root/base"/.*.partial

trap 'rm -rf "$tmp"' EXIT
pg_basebackup -D "$tmp" -Ft -z -X stream -c "$checkpoint" -l "synaxis $stamp" -w
mv "$tmp" "$dest"
trap - EXIT
echo "base-backup: $dest"

# 目录名由本脚本按 UTC 时间戳生成，grep 只放行这一种形态
# shellcheck disable=SC2010
backups=$(ls -1 "$root/base" | grep -E '^[0-9]{8}T[0-9]{6}Z$' | sort)
count=$(echo "$backups" | wc -l)
if [ "$count" -gt "$keep" ]; then
    echo "$backups" | head -n $((count - keep)) | while read -r old; do
        rm -rf "${root:?}/base/$old"
        echo "base-backup: removed $root/base/$old"
    done
fi

# 最旧一份备份开始时的 WAL 段之前的归档都用不到了
# shellcheck disable=SC2010
oldest=$(ls -1 "$root/base" | grep -E '^[0-9]{8}T[0-9]{6}Z$' | sort | head -n 1)
segment=$(tar -xzOf "$root/base/$oldest/base.tar.gz" backup_label | sed -n 's/^START WAL LOCATION: .* (file \([0-9A-F]\{24\}\))$/\1/p')
[ -n "$segment" ] || { echo "base-backup: no START WAL LOCATION in $oldest/backup_label" >&2; exit 1; }
pg_archivecleanup -x .gz "$root/wal" "$segment"
echo "base-backup: kept WAL from $segment"
