# 玩法模式压测 harness 使用说明（2026-09-22）

`tools/loadtest/soak.py play` 给 `docs/DESIGN-M2.md` §15「50 玩家、单局、全服 1,000 单位」这一档的四条预算
（tick P99 < 50 ms；`S2C_UNIT_STATE` ≤ 256 字节、10 Hz、≤ 2.5 KB/s/玩家、50 人 ≤ 125 KB/s；进程堆 < 2 GB；
寻路 P99 < 2 ms）一个测量手段。它是 `docs/research/soak-2026-09-18/` 那套核心模式 harness 的玩法模式一半：
连接走真实的 `C2S_HELLO` 握手进 play，然后按 RTS 玩家的形态驱动切片，服务端侧的采样手段（JFR、`jcmd`
强制 GC、`ps`）完全相同。**本目录只登记 harness 与跑法；2026-09-22 只做了一次 60 s 冒烟证明它能跑，
机器不安静，数字不进文档。** 正式测量的验收用例是 `docs/TEST.md` `TC-GAME-068`。

## 前置

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"   # harness 要能调用 jcmd / jfr
export JAVA_TOOL_OPTIONS="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttp.nonProxyHosts=maven.aliyun.com|*.aliyun.com"
mkdir -p /tmp/synaxis-play
```

## 起玩法模式服务端（1,000 单位、端口 25566）

在 **worktree 根**执行，stdin 要保持打开，停机用 `stop`。`synaxis.app.units` 是对局播种的单位**总数**
（默认 67，即 `docs/TEST.md` §3 的验收阵容；前 `n − 3` 个是玩家队伍、绕出生点一圈圈铺，随后中立、
`team=2`、远哨各一个），非数字、≤ 3 或地图放不下时进程以 ERROR + 非零码拒启，端口不 bind。
stdout 一定要重定向到文件——harness 靠它数 WARN / ERROR 行。

```bash
mkfifo /tmp/synaxis-play/stdin.fifo
( sleep 86400 > /tmp/synaxis-play/stdin.fifo & )   # 占住 fifo 写端
./gradlew :server-app:run --no-daemon --console=plain \
  --init-script gradle/init/aliyun-mirror.init.gradle.kts \
  -Dsynaxis.app.port=25566 -Dsynaxis.app.units=1000 \
  < /tmp/synaxis-play/stdin.fifo > /tmp/synaxis-play/server.log 2>&1 &
# 就绪：server.log 出现 "synaxis server-app listening on 0.0.0.0:25566"，nc -z 127.0.0.1 25566 通
# 核对播种：python3 tools/protocol-probe/probe.py --port 25566 units --wait 6 --expect-min 999
#   出生点能看见 999 个（远哨在 +x 220 格外，超出 128 格的实体视距），commandable 998 个
# 停机：echo stop > /tmp/synaxis-play/stdin.fifo
```

## harness 做什么

每条连接：

| 动作 | 频率 | 细节 |
| --- | --- | --- |
| 登录 + `C2S_HELLO` 握手 | 一次 | 复用 `tools/protocol-probe/probe.py` 的 `Session.enter_play()`，用户名 `play0000`… |
| `C2S_CAMERA` | 5 Hz（`--camera-hz`） | 锚点沿利萨如曲线游走整图（`--map-size` 定范围、`--pan-speed` 定速度，默认 20 格/秒），`zoom` 固定 `--zoom` |
| 玩家实体位置包 | 与镜头同频 | 把玩家实体走到锚点（y = 40，地面）。`REQ-M2-018` 服务端侧「把玩家传送到锚点」尚未落地，不发这一包就没有区块流送、兴趣集也不会变 |
| `C2S_MOVE_ORDER` | 1/s（`--order-hz`） | 轮流对本连接可指挥的单位（`S2C_UNIT_SPAWN` 里 `flags & 1` 的）下令，目标是当时的锚点；`order_seq` = `order_group` 递增 |
| `C2S_SELECT` | 每 10 s（`--select-interval`） | 8 个可指挥单位一批，轮转 |
| KeepAlive / TeleportConfirm | 按需 | 与核心模式 harness 相同 |

入站每一帧只解到包 ID（压缩帧只解压前 8 字节），按包名记条数与线上字节；只有 `PluginMessagePacket`
与三种控制包整帧解开。`rts:game` 的负载按 `msg_id` 细分，`S2C_UNIT_STATE` 只读到 `count` 字段、不逐条
解条目，所以 50 连接 × 每秒上千帧不会让 harness 自己成为瓶颈（`samples.jsonl` 的 `harness_cpu_percent`
是判断依据）。

服务端侧：harness `pgrep` 找到进程后起 JFR（`synaxis.ServerTickTime`）、每 `--sample-interval` 秒采一次
`ps` / `jstat` / `lsof`、每 `--gc-probe-interval` 秒 `jcmd GC.run` 后读存活堆；结束后 dump JFR 算 tick
分布，读 `--server-log` 从开跑起新增的行数 WARN / ERROR / `Kicking connection_id=`，读 `--events-dir`
（默认 `logs`，相对服务端工作目录即 worktree 根）从开跑起新增的台账事件并取 `path_ms`。

## 门禁档：50 × 1,000 × 600 s

```bash
python3 tools/loadtest/soak.py play --port 25566 --connections 50 --duration 600 --units 1000 \
  --gc-probe-interval 60 --server-log /tmp/synaxis-play/server.log --out /tmp/synaxis-play/gate
```

`--units` 只是把预期单位数记进 `summary.json` 与任一连接见过的最大兴趣集对照（`units.check`），
它不改服务端；服务端的数目只由 `-Dsynaxis.app.units` 定。找不到服务端进程或 `JFR.start` 失败时退出码 2。

## 规模曲线：50 × {1,000, 2,000, 4,000}

每档重启服务端（`stop`，换 `-Dsynaxis.app.units=<n>`），同一条 harness 命令换 `--units <n>` 与 `--out`：

```bash
for n in 1000 2000 4000; do
  # 重启服务端到 -Dsynaxis.app.units=$n 并等就绪，然后：
  python3 tools/loadtest/soak.py play --port 25566 --connections 50 --duration 300 --units $n \
    --gc-probe-interval 60 --server-log /tmp/synaxis-play/server.log --out /tmp/synaxis-play/scale-$n
done
```

读四个数看曲线：`ticks.overall.p99_ms`、`inbound.per_connection_bytes_per_s.engine`（实体位置包随
走动单位数涨）、`heap.live_mib_max`、`pathfinding.tick_bucket_ms.p99_ms`。

**布局会影响读数**：默认播种把全部单位铺在出生点周围 ±17 格（1,000 个）～ ±34 格（4,000 个），
锚点离出生点 128 格内的玩家兴趣集就是全部单位，`S2C_UNIT_STATE` 顶到 `count ≤ 512` 的上限
（1,000 个单位时单条 1,927 字节），`max_entries` 读到 512 就是这个意思。这是最坏档，不是 §2
「单玩家视野 ≤ 200」的画像值；要按画像测得改播种布局。

## `summary.json` 字段

| 字段 | 含义 | 对应 §15 |
| --- | --- | --- |
| `profile` | 本次参数（`connections` / `duration` / `units` / `map_size` / `camera_hz` / `order_hz` / `select_interval` / `pan_speed` / `zoom` / `gc_probe_interval`） | — |
| `pid`、`elapsed_s` | 服务端 pid；负载窗口时长（从第一次登录提交到截止，不含 settle） | — |
| `connected_peak`、`connected_end`、`login_attempts`、`login_failures`、`login_latency_ms` | 同核心模式 harness | — |
| `connections_lost`、`disconnect_reasons` | 跑完之前掉线的连接数；服务端 `Disconnect` 的原因键直方图（被踢的连接在这里，`server_log.kick_lines` 是服务端侧同一件事） | — |
| `keep_alives` | 应答的 KeepAlive 数 | — |
| `units.expected` / `seen_max` / `commandable_max` / `check` | `--units`；任一连接经 `S2C_UNIT_SPAWN` 见过的最大单位数；任一连接可指挥的最大单位数；`seen_max` 等于预期为 `ok`，恰好少 1 为 `ok-outpost-unseen`（远哨在出生点 220 格外、编号最末，锚点没游到它 128 格内就没人见过它，短跑常见），其余为 `mismatch`，未给 `--units` 为 `unset` | — |
| `uplink.camera_reports` / `position_packets` / `orders_sent` / `selects_sent` | 上行各类包的总数 | 限流行（应远低于 10/s/连接） |
| `orders.sent` / `answered` / `codes` | 下令数、收到的 `S2C_ORDER_RESULT` 数、结果码直方图（每条必回，`answered` 应等于 `sent`；`out-of-bounds` 是锚点落在柱子或口袋墙上，`busy` 是队列满） | 寻路行的队列深度 |
| `inbound.connection_seconds` | 全部连接在线秒数之和，下面「每连接」口径的分母 | — |
| `inbound.per_connection_bytes_per_s.total` / `engine` / `rts_game` | 每连接入站线上字节/秒，分引擎包与 `rts:game` 负载两栏（含帧长前缀） | 单位快照行的「单位位置计在引擎侧」 |
| `inbound.per_connection_distribution.engine` / `rts_game` | 逐连接自己的字节/秒的分布（`count` / `mean_ms` 等键名沿用 `distribution`，单位是 B/s） | 同上 |
| `inbound.engine_by_packet` | 引擎侧下行按包名的条数与线上字节（前 20 名，其余折进 `other`；实体位置包、区块包在这里） | 同上 |
| `inbound.rts_game_by_msg.<消息名>` | `count`、`payload_bytes`（`msg_id` 起的负载字节，不含通道名与帧头）、`wire_bytes`、`max_payload_bytes`、`max_entries`（`S2C_UNIT_STATE` 与 `S2C_UNIT_DESPAWN` 的 `count` 字段上限）、`hz_per_connection`、`bytes_per_s_per_connection`、`bytes_per_s_total` | 单位快照行：`S2C_UNIT_STATE.max_payload_bytes ≤ 256`、`bytes_per_s_per_connection ≤ 2,560`、`bytes_per_s_total ≤ 128,000` |
| `inbound.rts_game_by_msg.S2C_UNIT_STATE.interval_ms` | 同一连接相邻两条快照的间隔分布，只算 ≤ 1 s 的（兴趣集为空时服务端不发快照，那段空档是镜头的事）；`p50_ms` ≈ 100 即 10 Hz | 单位快照行的 10 Hz |
| `ticks.ticks` / `overall` / `per_minute` | JFR `synaxis.ServerTickTime` 的条数、整段分布（`p50_ms` / `p99_ms` / `max_ms` / `over_50ms`）、逐分钟分布 | tick 预算行：`overall.p99_ms < 50` |
| `heap.live_mib_max` / `rss_mib_max` / `gc_probes` / `settle` | 强制 GC 后存活堆的最大值；负载窗口内 RSS 最大值；逐次 GC 探针；断开 + settle 后的存活堆、线程数、FD 数 | 内存行：`live_mib_max < 2,048` |
| `pathfinding.events` | 运行期间台账新增的事件计数（`unit_order_rejected` 按 `code` 拆开） | — |
| `pathfinding.searches` / `path_ms` / `path_nodes_max` | 跑了搜索的事件数（`unit_order_issued` + `no-path` 档的 `unit_order_rejected`）、单次 `path_ms` 分布（台账分辨率 1 ms）、单次最大展开数 | 寻路行：`path_nodes_max ≤ 4,000` |
| `pathfinding.tick_bucket_ms` | `path_ms` 按台账 `ts` 的 50 ms 分桶求和后的分布——最接近「一个 tick 花在搜索上的毫秒」的读法 | 寻路行：`p99_ms < 2`（分辨率 1 ms，读到 ≤ 1 即达标） |
| `server_log.warn_lines` / `error_lines` / `kick_lines` / `samples` | 开跑起 `--server-log` 新增的 WARN、ERROR、`Kicking connection_id=` 行数；去掉时间戳、线程与 uuid 后的去重样例（≤ 10 条） | — |
| `samples` | 每 `--sample-interval` 秒一行：`clients`、`threads`、`fds`、`cpu_percent`、`rss_mib`、`load1`、`harness_cpu_percent`、`jstat` 的分代与 GC 计数、`moves`（玩家位置包累计） | — |

产物目录另有 `samples.jsonl`（每次采样一行）与 `soak.jfr`。从已有 JFR 单独算 tick 分布：
`python3 tools/loadtest/soak.py ticks --jfr <目录>/soak.jfr`。**跑完把 `soak.jfr` 删掉**，它一分钟就上 MB。

## 冒烟（2026-09-22，只证明能跑）

`--connections 50 --duration 60 --units 1000 --gc-probe-interval 20`，端口 25566，机器不安静，
结果只写进那一轮的交付说明、不进本目录。
