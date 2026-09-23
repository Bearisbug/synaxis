#!/bin/sh
# PostgreSQL archive_command：把一个 WAL 文件 gzip 后放进归档目录。
#   archive_command = '<本目录>/archive-wal.sh %p %f <归档目录>'
# 退出码 0 表示可以回收该段；非 0 时 PostgreSQL 记一行日志、累加 pg_stat_archiver.failed_count，稍后重试同一个文件。
set -eu

src=$1
name=$2
dir=$3
dest="$dir/$name.gz"

if [ -e "$dest" ]; then
    # 上次已经写成功、只是确认丢了：内容相同照成功处理。内容不同一律拒绝，不能覆盖已归档的 WAL
    if gzip -dc "$dest" | cmp -s - "$src"; then
        exit 0
    fi
    echo "archive-wal: $dest already exists with different content" >&2
    exit 1
fi

# 先写临时文件并落盘，再改名：中途崩溃不会在归档目录留下半个文件
tmp="$dir/.$name.gz.partial"
trap 'rm -f "$tmp"' EXIT
gzip -c "$src" > "$tmp"
sync "$tmp"
mv "$tmp" "$dest"
trap - EXIT
