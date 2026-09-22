# 玩法模式 §15 整表阈值测量报告（2026-09-22）

- 对象：`server-app` 玩法模式，`-Dsynaxis.app.units=1000 / 2000 / 4000`，地图预设 `rts-512`（1,024 个区块），
  端口 25566。协议 776 / MC 26.2。
- head：`9013cf76fd`（分支 `worktree-wf_a2e5dc25-b5d-2`），引擎、插件与 harness 都是这个提交，本轮一行代码未改。
- 机器：Apple M4 Pro，12 核，24 GiB，JDK 25.0.4.1，loopback。
- **机器不安静**：一个与本仓库无关的 Python 3.14 进程（pid 22343，已连续跑 5 天）全程占满一个核
  （`ps -o %cpu` 99%）。三档负载窗口内的 `load1`（`samples[].load1`，取 `clients > 0` 的行）分别是
  3.95～6.15、2.08～4.49、4.35～5.87。本报告的 tick 数字因此是「机器上另有一个满核进程」时的读数，
  比安静机器偏高。
- harness：`tools/loadtest/soak.py play`，参数照本目录 `README.md` 的门禁档与规模曲线；每档的 `profile`
  在 `summary.json` 里。三档的服务端都以 `stop` 停机，Gradle `BUILD SUCCESSFUL`，停机后
  `lsof -nP -iTCP:25566 -sTCP:LISTEN` 为空。
- 产物：`runs/gate-1000/summary.json`、`runs/scale-2000/summary.json`、`runs/scale-4000/summary.json`。
  JFR 文件已删。**本报告的每个数字都能从这三个文件复算**：直接读的写字段名，算出来的写公式。
- 验收用例：`docs/TEST.md` `TC-GAME-068`（第 2 步 = gate-1000，第 3 步 = scale-2000 / scale-4000）。

## 布局：三档量的都是最坏档

默认播种把全部单位绕出生点一圈圈铺开（1,000 个 ±17 格，4,000 个约 ±34 格），锚点离出生点 128 格内的
玩家兴趣集就是全部单位，`S2C_UNIT_STATE` 顶到 `count ≤ 512` 的上限（三档 `max_entries` 都是 512）。
所以「单位快照」那一行读到的是上限值，不是 §2「单玩家视野 50 单位」的画像值。服务端每 10 s 打一行
`An interest set is over the snapshot cap`（`RB-M2-003` 的信号，`LogBudget` 按 10 s 预算，行内带被抑制
的次数），三档的 `server_log.warn_lines` 全部是这一行：600 s 57 行、300 s 各 29 行。

## 三档数字表

`gate-1000` 是 50 连接 × 600 s，`scale-2000` / `scale-4000` 是 50 连接 × 300 s。B/s 一律是字节/秒。

| 项 | 字段或公式 | gate-1000 | scale-2000 | scale-4000 |
| --- | --- | --- | --- | --- |
| tick 个数 | `ticks.overall.count` | 12,311 | 6,312 | 6,310 |
| tick P50 ms | `ticks.overall.p50_ms` | 2.857 | 3.143 | 3.983 |
| tick P99 ms | `ticks.overall.p99_ms` | 8.617 | 9.698 | 11.096 |
| tick max ms | `ticks.overall.max_ms` | 32.178 | 43.065 | 79.431 |
| tick > 50 ms | `ticks.overall.over_50ms` | 0 | 0 | 2 |
| `S2C_UNIT_STATE` 单条最大字节 | `…S2C_UNIT_STATE.max_payload_bytes` | 2,023 | 2,024 | 2,054 |
| 单条最多条目 | `…S2C_UNIT_STATE.max_entries` | 512 | 512 | 512 |
| 每条目字节 | `max_payload_bytes ÷ max_entries` | 3.951 | 3.953 | 4.012 |
| 快照频率 Hz/连接 | `…S2C_UNIT_STATE.hz_per_connection` | 8.926 | 8.195 | 8.160 |
| 相邻快照间隔 P50 ms | `…S2C_UNIT_STATE.interval_ms.p50_ms` | 97.277 | 96.577 | 96.338 |
| 快照负载 B/s/玩家 | `…S2C_UNIT_STATE.bytes_per_s_per_connection` | 4,420.0 | 4,118.2 | 4,513.8 |
| 快照负载 B/s 50 人 | `…S2C_UNIT_STATE.bytes_per_s_total` | 220,866.3 | 205,607.1 | 225,310.2 |
| 快照线上 B/s/玩家（压缩后） | `…S2C_UNIT_STATE.wire_bytes ÷ inbound.connection_seconds` | 2,547.3 | 2,249.9 | 2,392.3 |
| 快照线上 B/s 50 人 | `…S2C_UNIT_STATE.wire_bytes ÷ elapsed_s` | 127,292.1 | 112,326.2 | 119,415.0 |
| `rts:game` 线上 B/s/玩家（四种消息合计） | `inbound.per_connection_bytes_per_s.rts_game` | 3,338.8 | 3,987.0 | 5,834.5 |
| `rts:game` 线上 B/s 50 人 | `Σ rts_game_by_msg.*.wire_bytes ÷ elapsed_s` | 166,843.9 | 199,056.8 | 291,234.0 |
| 引擎包线上 B/s/玩家 | `inbound.per_connection_bytes_per_s.engine` | 14,456.5 | 20,429.3 | 32,541.3 |
| 引擎包线上 B/s 50 人 | `Σ engine_by_packet.*.wire_bytes ÷ elapsed_s` | 722,400.5 | 1,019,953.9 | 1,624,329.4 |
| 引擎包 个/s/玩家 | `Σ engine_by_packet.*.count ÷ connection_seconds` | 581.6 | 722.7 | 1,025.4 |
| 入站合计 B/s/玩家 | `inbound.per_connection_bytes_per_s.total` | 17,795.3 | 24,416.3 | 38,375.8 |
| RSS 最大 MiB | `heap.rss_mib_max` | 230.1 | 362.6 | 335.0 |
| 强制 GC 后存活堆最大 MiB | `heap.live_mib_max` | 43.0 | 51.1 | 64.1 |
| 断开后存活堆 MiB | `heap.settle.live_mib_after_disconnect` | 39.2 | 44.7 | 57.6 |
| 服务端 WARN / ERROR 行 | `server_log.warn_lines` / `error_lines` | 57 / 0 | 29 / 0 | 29 / 0 |
| 被踢连接 | `server_log.kick_lines` | 0 | 0 | 0 |
| 掉线 / 登录失败 | `connections_lost` / `login_failures` | 0 / `{}` | 0 / `{}` | 0 / `{}` |
| 登录延迟 P99 ms | `login_latency_ms.p99_ms` | 124.684 | 164.569 | 188.376 |
| 下令 发 / 回 | `orders.sent` / `orders.answered` | 29,903 / 29,900 | 14,940 / 14,940 | 14,932 / 14,922 |
| 结果码 ok / out-of-bounds / no-path | `orders.codes` | 29,404 / 472 / 24 | 14,708 / 220 / 12 | 14,660 / 253 / 9 |
| 寻路搜索次数 | `pathfinding.searches` | 29,428 | 14,720 | 14,673 |
| 单次 `path_ms` P50 / P99 / max | `pathfinding.path_ms` | 0 / 0 / 13 | 0 / 0 / 5 | 0 / 0 / 3 |
| 50 ms 桶 `path_ms` P99 / max | `pathfinding.tick_bucket_ms` | 1.0 / 13.0 | 1.0 / 5.0 | 0.0 / 3.0 |
| 单次最大展开（issued / rejected） | `pathfinding.path_nodes_max_issued` / `_rejected` | 1,552 / 4,001 | 1,605 / 4,001 | 1,604 / 4,001 |
| 单位核对 | `units.check`（`seen_max` / `commandable_max`） | ok（1,000 / 998） | ok（2,000 / 1,998） | ok（4,000 / 3,998） |
| 服务端 CPU %（`ps`，单核 100%） | `samples[].cpu_percent`，`clients > 0` 的行 | 9.7～28.5 | 11.6～28.2 | 12.7～42.1 |
| harness CPU % 最大 | `samples[].harness_cpu_percent` | 17.7 | 25.0 | 27.0 |
| 线程 / FD | `samples[].threads` / `fds` | 66 / 111 | 66～67 / 111 | 66 / 111 |

两处读数要连着口径看：

- `hz_per_connection` 三档都低于 10（8.9 / 8.2 / 8.2），而相邻快照间隔的 P50 是 96～97 ms。服务端对兴趣集为空
  的玩家不发快照，镜头游过没有单位的地带就没有快照；间隔分布只统计 ≤ 1 s 的相邻两条，所以「发的时候是 10 Hz」
  由间隔证明，「平均每秒不到 10 条」是镜头路线的事。
- `orders.answered` 比 `orders.sent` 少 3 / 0 / 10 条：harness 到截止时刻立刻关连接，不等最后在飞的回包；
  最后一秒内 50 条连接各发 1 条，差值都在这 50 条以内。

## 与 §15 逐行对照（门禁档 gate-1000）

| §15 的行 | 目标 | 实测 | 结论 |
| --- | --- | --- | --- |
| tick 预算 | 单 tick P99 < 50 ms | P99 8.617 ms（P50 2.857、max 32.178，12,311 个 tick 里超 50 ms 的 0 个） | **达标**，P99 是预算的 17.2%（8.617 ÷ 50），余 41.383 ms |
| 寻路开销 | 单 tick 内 P99 < 2 ms | `tick_bucket_ms.p99_ms` = 1.0（台账分辨率 1 ms，≤ 1 即达标）；单次 `path_ms` P99 = 0、max = 13 ms（29,428 次里的一次） | **达标** |
| 寻路开销 | 单次展开 ≤ 4,000 | `path_nodes_max_issued` = 1,552；`path_nodes_max_rejected` = 4,001 是 24 次 `no-path` 吃满预算收手的按设计读数，不参与判 | **达标**，余 2,448 个节点 |
| 寻路开销 | 每 tick ≤ 4 次、队列 > 32 回 `busy` | `orders.codes` 里没有 `busy`；50 条/秒的下令速率下队列没满过 | **达标**（`busy` 0 次） |
| 单位快照 | 10 Hz | 相邻间隔 P50 97.277 ms | **达标** |
| 单位快照 | 每条 ≤ 256 字节 | `max_payload_bytes` = 2,023（512 条目 × 3.951 字节） | **未达标**，超 1,767 字节（7.9 倍） |
| 单位快照 | ≤ 2.5 KB/s/玩家（判据 `bytes_per_s_per_connection` ≤ 2,560） | 4,420.0 B/s（负载）；线上（压缩后）2,547.3 B/s | **未达标**，超 1,860 B/s（1.73 倍）；线上口径在线内 12.7 B/s |
| 单位快照 | 50 人 ≤ 125 KB/s（判据 `bytes_per_s_total` ≤ 128,000） | 220,866.3 B/s（负载）；线上 127,292.1 B/s | **未达标**，超 92,866 B/s（1.73 倍）；线上口径在线内 708 B/s |
| 单位快照 | 编码 CPU ≤ 0.2 ms/tick | 没有单独量，含在 tick 里 | 未测 |
| 内存 | 进程堆 < 2 GB | 强制 GC 后存活堆最大 43.0 MiB（10 次探针 38.5～43.0，见 `heap.gc_probes`），RSS 最大 230.1 MiB | **达标**，余 2,005 MiB |
| 内存 | 地图数据 < 50 MB | 不在本轮，由 `TC-GAME-029` 的 `heap_delta_kib` 判 | — |
| 限流 | 镜头 ≤ 10/s、选中 ≤ 10/s、下令 ≤ 10 操作/s | 上行实发：镜头 4.92/s（147,655 ÷ 29,982.4）、下令 0.997/s、选中 0.100/s | 在限内（harness 按画像发，不是压限流） |
| 端到端延迟 | 点地面到看见起步 P99 < 300 ms | harness 不量这一条 | 未测 |
| 客户端帧率 | 同屏 200 单位 ≥ 60 FPS | 需要屏幕 | 留给验收人 |

**单位快照三个「未达标」的性质**：差在布局，不在编码。每条目 3.951 字节与 §2 的估算（≈ 4 字节/条目）一致；
这一行的场景是「视野 50 单位」，而本轮每个玩家的兴趣集都顶到 512 条上限（`max_entries` = 512），单条字节与
每秒字节随之是 50 条目画像的 10 倍量级。按 3.951 字节/条目折算，50 条目约 198 字节加几字节的头，在 256 字节内
——这是折算，不是实测；要拿到画像值得把播种改成均匀铺开，见文末。线上口径（压缩后）三个数都恰在线内，
但 v0.39 定的判据字段是负载口径，本报告按负载口径记未达标。

## 规模曲线：50 连接 × {1,000, 2,000, 4,000}

| 档 | tick P50 | tick P99 | tick max | > 50 ms | 引擎包 B/s/玩家 | 引擎包 B/s 50 人 | `rts:game` B/s/玩家 | 存活堆 MiB | 寻路桶 P99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1,000 | 2.857 | 8.617 | 32.178 | 0 | 14,456.5 | 722,400.5 | 3,338.8 | 43.0 | 1.0 |
| 2,000 | 3.143 | 9.698 | 43.065 | 0 | 20,429.3 | 1,019,953.9 | 3,987.0 | 51.1 | 1.0 |
| 4,000 | 3.983 | 11.096 | 79.431 | 2 | 32,541.3 | 1,624,329.4 | 5,834.5 | 64.1 | 0.0 |

拐点判断：

- **tick P99：至 4,000 未见拐点，上界待更大规模。** 8.617 → 9.698 → 11.096 ms，4,000 单位档仍是预算的 22.2%
  （11.096 ÷ 50）；逐分钟 P99（`ticks.per_minute`）在 4,000 档是 14.141 / 10.602 / 10.738 / 11.536 / 10.583 / 11.397，
  第 0 分钟最高是 50 条连接同秒进 play 的进场代价。
- **tick max 与超 50 ms 的两个 tick（4,000 档）**：6,310 个 tick 里 2 个。65.4 ms 在 20:10:54.351，即进场首秒
  （与 `soak-2026-09-18/report.md` 组 6 记的进场尖刺同性质）；79.4 ms 在 20:13:54.951（第 3 分钟），JFR 里它前后
  ±250 ms 没有任何 GC 暂停（最近的是 +258 ms 处 1.01 ms 的 G1New），整段最长的 GC 暂停是 harness 每 60 s 强制
  GC 的 G1Full 18.3 ms。这一个 tick 的归因未定；判据是 P99，它不构成越线。
- **带宽：设计里没有给引擎实体包画线，所以「带宽在哪一档越线」在 §15 上无线可越。** 快照那条线（负载口径
  ≤ 128,000 B/s）在三档都已越过（220,866 / 205,607 / 225,310），并且**与单位数无关**——512 条上限让快照体积在
  1,000 单位时就封顶。随单位数涨的是引擎实体包：14,457 → 20,429 → 32,541 B/s/玩家，2,000 → 4,000 涨 1.59 倍；
  按包名看，4,000 档 `EntityPositionSyncPacket` 占引擎字节的 50.7%（247,162,868 ÷ 487,298,826）、
  `EntityPositionAndRotationPacket` 17.1%、`SpawnEntityPacket` 12.3%。`rts:game` 一栏在 4,000 档涨到 5,834.5 是
  `S2C_UNIT_SPAWN` / `S2C_UNIT_DESPAWN` 的兴趣集换手（每连接 71.4 / 70.9 条/秒，1,000 档是 16.0 / 15.8）。
  三档出站合计（引擎 + `rts:game`，50 人）0.89 → 1.22 → 1.92 MB/s。
- **存活堆：43.0 → 51.1 → 64.1 MiB**，每加 1,000 单位约 7.0 MiB（(64.1 − 43.0) ÷ 3），离 2,048 MiB 的线还有
  1,984 MiB。
- **寻路不随单位数变**：下令速率是常数（50 条/秒），三档搜索次数 29,428 / 14,720 / 14,673 与时长成比例，
  桶 P99 1.0 / 1.0 / 0.0 ms。

## 未测与局限

- 客户端 ≥ 60 FPS 那一行需要屏幕，留给验收人。
- 端到端延迟行与快照编码 CPU 那一句没有单独测量手段，本轮未测。
- 机器不安静（见开头），tick 数字偏保守；三档的 `harness_cpu_percent` 最大 17.7 / 25.0 / 27.0，都低于同期服务端
  `cpu_percent` 的最大值，瓶颈不在 harness。
- 三档都是最坏布局，「视野 50 单位」画像未测。
- `unit_order_completed` 只有 524 / 221 / 229（issued 29,404 / 14,708 / 14,664）：harness 对本方 998～3,998 个单位
  轮流下令、目标是移动中的锚点，绝大多数指令在到达前被下一条替换（`unit_order_superseded` 28,880 / 14,487 /
  14,435），这是 harness 的驱动形态，不是缺陷。

## 下一步

1. 把播种改成按 §2 画像均匀铺开（视野典型 50、上限 200），重测单位快照行——现在那三个「未达标」是布局的
   结论，编码本身的每条目 3.951 字节与设计一致。
2. 引擎实体包要不要一条上界（4,000 档 50 人 1.62 MB/s，随单位数涨得最快），由设计定；定了再补进 §15。
3. 4,000 档那个 79.4 ms 的 tick 归因未定，下一轮带 `jdk.ExecutionSample` 抓一次。
