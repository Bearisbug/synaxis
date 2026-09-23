# PostgreSQL 备份与时间点恢复

`docs/DESIGN-PROD.md` `REQ-PROD-035`（WAL 归档 + 每日基础备份，恢复点目标 ≤ 5 分钟）的部署侧实现，也是 `RB-PROD-008` 的操作步骤。工具只用 PostgreSQL 16 自带的 `pg_basebackup`、`archive_command` 与 `pg_archivecleanup`，不装 pgBackRest；换 pgBackRest 的条件见文末。用例是 `docs/TEST.md` 的 `TC-PROD-190`～`TC-PROD-193`。

| 文件 | 在哪跑 | 作用 |
| --- | --- | --- |
| `archive-wal.sh` | PostgreSQL 的 `archive_command` | 一个 WAL 文件 gzip 后写进 `$BACKUP_ROOT/wal/<名字>.gz`；先写 `.partial` 再改名；同名文件已在且内容相同时照成功处理，内容不同时退出码 1、绝不覆盖 |
| `enable-wal-archive.sh` | 数据库服务器，`postgres` 用户 | `ALTER SYSTEM` 写四个参数：`wal_level=replica`、`archive_mode=on`、`archive_command`（指向本目录的 `archive-wal.sh`）、`archive_timeout=60s`；之后要重启 |
| `base-backup.sh` | 同上，每日一次 | `pg_basebackup -Ft -z -X stream` 写进 `$BACKUP_ROOT/base/<UTC 时间戳>/`，只留最新 `$BACKUP_KEEP` 份（默认 7），再用 `pg_archivecleanup` 删掉最旧一份之前的归档 WAL |
| `check-archive.sh` | 同上，每分钟一次 | 退出码 1 即告警：最早一个待归档的 WAL 等了超过 `$ARCHIVE_MAX_LAG_S`（默认 300 s）、最近一次归档失败晚于最近一次成功、最新基础备份早于 `$BASE_MAX_AGE_H`（默认 26 h） |
| `restore-pitr.sh` | 恢复目标机，`postgres` 用户 | 把一份基础备份解到空的新数据目录，写 `restore_command`、`recovery_target_time`、`recovery_target_action=promote`、`archive_mode=off` 与 `recovery.signal` |

所有脚本是 POSIX `sh`（官方 `postgres:16-alpine` 镜像里没有 bash），共用环境变量 `BACKUP_ROOT`（默认 `/backup`），连库走 libpq 环境变量（`PGHOST`、`PGPORT`、`PGUSER`），默认连本机 socket 上的 `postgres` 超级用户。

## 目录布局

```
$BACKUP_ROOT/
  bin/     本目录的五个脚本（archive_command 写的是这里的绝对路径）
  wal/     归档的 WAL：<段名>.gz，另有 <段名>.<偏移>.backup.gz（每份基础备份一个标签文件）
  base/    <UTC 时间戳>/{base.tar.gz, pg_wal.tar.gz, backup_manifest}
```

`$BACKUP_ROOT` 不能和数据目录在同一块盘上。生产上挂单独的卷，再由主机侧每分钟把 `wal/` 与 `base/` 同步到另一台机器或机房对象存储：整台数据库机器丢失时，恢复点是「60 s 切段 + 至多 60 s 同步间隔」，仍在 5 分钟之内。远端是本地的镜像，本地的删除只来自 `base-backup.sh` 的保留规则。

## 1. 安装与开归档（内测开放当天做，`REQ-PROD-035`）

1. 以 `postgres` 用户把本目录的五个脚本拷到 `$BACKUP_ROOT/bin/` 并 `chmod +x`；`$BACKUP_ROOT` 属主是 `postgres`。
2. `BACKUP_ROOT=/backup /backup/bin/enable-wal-archive.sh`。输出四行 `pending_restart`，`archive_mode` 那行是 `true`。
3. 重启 PostgreSQL（systemd 是 `systemctl restart postgresql`，容器是 `docker restart <容器>`）。重启期间分片的会话续期会失败，按 `RB-PROD-002` 的冻结与恢复处理；所以重启放在全部分片 `drain` 之后，或放在开服之前。
4. 验证：`psql -Atc "SELECT pg_walfile_name(pg_switch_wal())"`，2 s 内 `$BACKUP_ROOT/wal/` 出现同名 `.gz`，`pg_stat_archiver.failed_count` 为 0。
5. 立即做第一份基础备份：`BACKUP_CHECKPOINT=fast /backup/bin/base-backup.sh`。没有基础备份时归档的 WAL 无从回放，`check-archive.sh` 也会一直告警。

恢复点：提交在 `archive_timeout`（60 s）之内被切进一个已完成的段，归档本身在秒级完成，所以正常情况下提交后 ≤ 60 s 可恢复（`TC-PROD-191` 实测 27 s）；归档失败时由 `check-archive.sh` 在 WAL 等待满 300 s 时告警，两者合起来守住 5 分钟。

## 2. 定时任务

```cron
# postgres 用户的 crontab
15 4 * * *  BACKUP_ROOT=/backup /backup/bin/base-backup.sh >> /backup/base-backup.log 2>&1
* * * * *   BACKUP_ROOT=/backup /backup/bin/check-archive.sh > /dev/null 2>> /backup/check-archive.alert || <告警发送命令>
```

基础备份默认 `BACKUP_CHECKPOINT=spread`，按 `checkpoint_completion_target` 摊开检查点的写入，避免在白天高峰打出一阵 I/O；手工补备份时用 `fast`。`check-archive.sh` 的阈值对应 `docs/DESIGN-PROD.md` §21 的「WAL 归档延迟」一行（最新归档距今 ≤ 5 分钟，> 5 分钟告警）；它读的是 `pg_stat_archiver` 与 `pg_ls_archive_statusdir()`，用非超级用户跑时给该用户 `pg_monitor` 角色。

## 3. 时间点恢复（`RB-PROD-008` 处置）

1. 全部分片 `drain` 并停机，记下要回到的时间点 `T`（带时区，例如 `2026-09-23 16:11:20+00`）。
2. 选开始时间早于 `T` 的最新一份基础备份：`ls $BACKUP_ROOT/base`，目录名是 UTC 开始时间。
3. 在新实例上（同为 PostgreSQL 16，能读到 `$BACKUP_ROOT/wal/`）：`BACKUP_ROOT=/backup /backup/bin/restore-pitr.sh /backup/base/<时间戳> "<T>" <新数据目录>`，再用这个数据目录起 PostgreSQL（`pg_ctl -D <新数据目录> -l <日志> -w start`，或把 systemd 单元的数据目录指过去）。
4. 等 `SELECT pg_is_in_recovery()` 返回 `f`。日志里依次有 `starting point-in-time recovery to <T>`、若干 `restored log file`、`recovery stopping before commit of transaction … time …`（这个时间晚于 `T`）、`selected new timeline ID`、`archive recovery complete`。日志出现 `FATAL: recovery ended before configured recovery target was reached` 说明 `T` 之后的 WAL 没有归档到，换更早的 `T` 或先查归档缺口。
5. `server-app --migrate-only` 校验表结构版本（`docs/DESIGN-PROD.md` §25），切连接串，起服。旧租约随新 `shard_session` 全部过期。
6. 恢复出来的实例没有开归档（`restore-pitr.sh` 写了 `archive_mode=off`）：它是新时间线，不能往原库的 `wal/` 里写。确认要长期用它之后，对它重新执行第 1 节，归档目录换一个新的。
7. 原库保留只读 7 天，发现恢复点选错时切回。

## 4. 本机开发容器

本机开发与测试共用容器 `synaxis-pg`，各组各用自己的 database。归档与备份放在单独的卷 `synaxis-pg-backup`：

```bash
docker run -d --name synaxis-pg -e POSTGRES_PASSWORD=synaxis -p 127.0.0.1:5440:5432 \
  -v synaxis-pg-data:/var/lib/postgresql/data -v synaxis-pg-backup:/backup \
  --restart unless-stopped postgres:16-alpine
docker exec synaxis-pg mkdir -p /backup/bin
docker cp ops/postgres/. synaxis-pg:/backup/bin/
docker exec synaxis-pg chown -R postgres:postgres /backup
docker exec -u postgres synaxis-pg /backup/bin/enable-wal-archive.sh
docker restart synaxis-pg
docker exec -u postgres -e BACKUP_CHECKPOINT=fast synaxis-pg /backup/bin/base-backup.sh
```

本机没有定时任务，归档只增不减：`docker exec -u postgres synaxis-pg du -sh /backup/wal` 超过 1 GB 时再跑一次最后那条命令并加 `-e BACKUP_KEEP=1`，它会删掉新备份之前的全部 WAL。不再需要归档时 `ALTER SYSTEM SET archive_mode = off` 后重启，再删 `/backup/wal` 与 `/backup/base` 下的内容。

## 换 pgBackRest 的条件

满足任一条就换：库超过 50 GB（`pg_basebackup` 单线程全量，每日全量拖到 1 小时以上）；需要增量或差异备份；备份库要直接写对象存储。换的代价是多一个守护进程与一份配置，`check-archive.sh` 的两项归档检查改读 `pgbackrest info` 的输出。
