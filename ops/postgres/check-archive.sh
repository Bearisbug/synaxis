#!/bin/sh
# 归档与备份的告警探针：每分钟跑一次，退出码非 0 即告警（RB-PROD-008）。
#   ⓪ archive_mode 为 off：WAL 根本不归档，不会有待归档文件也不会有失败，①② 都看不出来
#   ① 最早一个待归档的 WAL 文件（pg_wal/archive_status/*.ready）等待超过 $ARCHIVE_MAX_LAG_S 秒：恢复点目标 ≤ 5 分钟守不住
#   ② 最近一次归档失败晚于最近一次归档成功：archive_command 正在失败
#   ③ 最新一份基础备份早于 $BASE_MAX_AGE_H 小时：每日备份没跑成
# 连本机超级用户或带 pg_monitor 角色的用户执行。
set -eu

root=${BACKUP_ROOT:-/backup}
max_lag=${ARCHIVE_MAX_LAG_S:-300}
max_age_h=${BASE_MAX_AGE_H:-26}
alert=0

row=$(psql -X -At -F ' ' -v ON_ERROR_STOP=1 -c "
SELECT count(r.name),
       coalesce(extract(epoch FROM now() - min(r.modification))::bigint, 0),
       coalesce(a.last_failed_time > a.last_archived_time OR (a.last_archived_time IS NULL AND a.failed_count > 0), false),
       a.archived_count, a.failed_count, coalesce(a.last_archived_wal, '-'),
       current_setting('archive_mode')
FROM pg_stat_archiver a
LEFT JOIN pg_ls_archive_statusdir() r ON r.name LIKE '%.ready'
GROUP BY a.archived_count, a.failed_count, a.last_archived_wal, a.last_failed_time, a.last_archived_time")
# shellcheck disable=SC2086 # 按空格拆成位置参数
set -- $row
ready=$1 lag=$2 failing=$3 mode=$7
echo "check-archive: ready=$ready oldest_ready_s=$lag failing=$failing archived=$4 failed=$5 last_archived=$6 archive_mode=$mode"
if [ "$mode" = off ]; then
    echo "check-archive: ALERT archive_mode is off" >&2
    alert=1
fi
if [ "$lag" -gt "$max_lag" ]; then
    echo "check-archive: ALERT oldest WAL waiting ${lag}s > ${max_lag}s" >&2
    alert=1
fi
if [ "$failing" = t ]; then
    echo "check-archive: ALERT archive_command is failing" >&2
    alert=1
fi

# shellcheck disable=SC2010
newest=$(ls -1 "$root/base" 2>/dev/null | grep -E '^[0-9]{8}T[0-9]{6}Z$' | sort | tail -n 1 || true)
if [ -z "$newest" ]; then
    echo "check-archive: ALERT no base backup in $root/base" >&2
    alert=1
else
    if [ -n "$(find "$root/base/$newest" -maxdepth 0 -mmin +$((max_age_h * 60)))" ]; then
        echo "check-archive: ALERT newest base backup $newest is older than ${max_age_h}h" >&2
        alert=1
    else
        echo "check-archive: newest base backup $newest"
    fi
fi
exit $alert
