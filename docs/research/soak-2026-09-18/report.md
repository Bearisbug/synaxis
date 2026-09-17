# Synaxis 服务端核心压测 / 长稳报告（2026-09-18）

- 对象：`server-app` 核心模式（`-Dsynaxis.app.core-mode=on`，宿主自建平坦世界 + `synaxis:main`
  echo 通道，任何用户名放行）。协议 776 / MC 26.2。
- 机器：Apple Silicon，12 核，24 GiB，JDK 25.0.4.1，端口 25565。
- harness：`tools/loadtest/soak.py`（本目录 `README.md` 有一条命令的跑法与采集手段表）。
- head：`84b68cd75d`，分支 `worktree-wf_3e0dff43-904-1`。
- 三组都用 `stop` 停机，Gradle `GRADLE_EXIT=0`，停机后 `pgrep synaxis.app.Main` 为空。
- **本轮只跑与记录，不修引擎缺陷。** 唯一改动是新增 harness + 本报告。

## 压力是否到位

harness 用单 selector 循环驱动全部连接，只有登录握手在 8 个 worker 线程上跑，因此 harness 自身
CPU 远低于服务端不会喧宾夺主。采样里同时记了服务端与 harness 两侧 CPU：

| 组 | 服务端峰值 CPU | harness 峰值 CPU | 峰值 load1 | 说明 |
| --- | --- | --- | --- | --- |
| 稳态 64 | 17.4% | 21.2% | 5.55 | 稳态 64 连接对 20 TPS 的核心世界是轻载，这是事实不是压力不足——见下方结论 |
| churn | 54.8% | 16.2% | 5.99 | 每秒 5 次全量登录（握手+登录+configuration+进世界）把服务端推到 55% |
| 恶意 | 72.6% | 20.4% | 5.99 | 攻击解码 + 64 连接负载叠加，服务端到 72% |

结论：稳态单看 CPU 是轻的（20 TPS、每 tick 只需处理 64×10=640 个移动包，tick 中位数 0.26 ms，
离 50 ms 预算差两个数量级），说明**核心在这个连接量级远未到瓶颈**；churn 与恶意两组把 CPU 压到
55%–73%，压力到位。真正的资源问题不是 CPU，而是下面 SOAK-1 的连接生命周期。

## 组 1：稳态（64 连接 / 20 分钟）

`--connections 64 --duration 1200 --move-hz 10 --echo-hz 0.5 --gc-probe-interval 300`

- 登录 64/64 成功，0 失败，0 断线；730,656 个移动包（608.9/s），7,648 次 KeepAlive 应答。
- tick（`synaxis.ServerTickTime`，24,412 个）：整段 p50 0.257 / p99 0.553 / max 25.97 ms，
  **超过 50 ms 的 0 个**。max 25.97 ms 落在第 0 分钟，是玩家进场时核心世界按需生成区块
  （`synaxis.ChunkGeneration` 在 t=0 集中出现）；第 1 分钟起每分钟 p99 稳定在 0.47–0.62 ms、
  max 1.5–4.4 ms，无漂移。
- 堆存活集（每 5 分钟一次 Full GC 后测）：32.2 → 31.9 → 31.8 → 28.6 MiB，20 分钟内不涨。
- 老年代 `OU` 在 6.8k→33k KiB 区间锯齿波动（正常 GC 行为，Full GC 后回落），非单调。
- 线程 66 恒定；FD 稳态 123，断开后回落 59；RSS 320–335 MiB。
- 客户端 RTT（echo 往返）p50 44.3 / p99 83.2 ms —— 20 TPS 下一次往返最多跨一个 50 ms tick，
  加上 harness 10 ms select 循环，44 ms 中位属预期，非发现。

判定：**稳态无单调增长、无 tick 尖刺（除进场一次性区块生成）、无异常日志。**

## 组 2：churn（16 常驻 + 5 登录/秒 / 10 分钟）

`--connections 16 --duration 600 --churn-hz 5 --churn-hold 8`

- 2,941 次登录，0 失败；churn 连接进世界后保持 8 秒再挂断。
- 线程 66 恒定（虚拟线程反复起落，JFR `jdk.VirtualThreadStart`=`VirtualThreadEnd`=15,715，成对）；
  FD 稳定 114（峰值），断开回落 59。
- 堆存活集：31.1 → 31.5 → 31.6 → 31.5 → 29.0 MiB，10 分钟无上涨；停机静置后 28.7 MiB。
- tick 12,609 个：整段 p50 0.31 / p99 0.87 / max 49.7 ms，第 1 分钟起每分钟 max ≤ 4 ms。
  max 49.7 ms 同样在第 0 分钟，对应首批玩家进场的区块生成（`synaxis.ChunkGeneration` 在 t=0
  的一秒内 529 次，单次生成 max 73 ms，落在 tick 里）。
- RSS 在 360–385 MiB 间波动，不随 churn 次数单调上行。

判定：**完成整个登录、进入 play 再挂断的连接会被及时回收**——线程 / FD / 堆均不随 churn 增长。

## 组 3：恶意 / 边界（六种攻击 + 64 连接负载 / 10 分钟）

`--connections 64 ... --attacks zero-frame,alloc-bomb,nbt-bomb,premature-finish,bad-version,deep-recursion --attack-interval 1.5`

- 攻击 185 次，全部探针退出码 0；64 条正常连接**零断线、零失败**，730k 量级移动包照常。
- tick 12,409 个：整段 p50 0.265 / p99 0.692 / max 67.8 ms（第 0 分钟区块生成），
  第 1 分钟起每分钟 max ≤ 2 ms —— 攻击没有在正常负载上制造尖刺。
- 服务端日志：每次解码类攻击一行 WARN，带 `connection_id` / `state` / `reason`，**无堆栈**
  （全日志 `at `/`Caused by` 行数为 0）。三类可观察 WARN：
  - zero-frame → `DataFormatException: Packet length must be positive: 0`
  - alloc-bomb → `IllegalArgumentException: Collection size (65,535) is out of the allowed range (0..100)`
  - nbt-bomb → `IllegalArgumentException: NBT is too large (max: 2097152 bytes)`
  bad-version 走 LoginDisconnect、deep-recursion 非创造玩家整包跳过，均不崩不挂。
- 堆存活集 33.1 → 32.4 → 29.1 MiB，不涨。

判定：**攻击不影响正常连接、服务端不崩不挂、日志规范（一行 WARN + connection_id + 无堆栈）。**

## 发现

### SOAK-1（P1）：configuration 阶段中断的连接，会把虚拟线程 + 整个 Player 对象图滞留最长 5 分钟

- 位置：`src/main/java/io/github/bearisbug/synaxis/network/ConnectionManager.java:278`
  （`knownPacksFuture.get(knownPacksResponseTimeout, MILLISECONDS)`），默认值
  `src/main/java/io/github/bearisbug/synaxis/ServerConfig.java:189`
  （`knownPacksResponseTimeout = 5 * 60_000`）。
- 现象：`doConfiguration` 在一条**虚拟线程**里阻塞等待 known-packs 应答。客户端若在此之前就
  终止（premature-finish 触发协议错误、或登录后直接静默 / 断开），服务端向客户端发出
  `synaxis:protocol-error` 断线、TCP 也关了，**但这个虚拟线程不会被唤醒或取消**，它一直
  `park` 到 5 分钟超时才走 `catch (TimeoutException)` 打 `failed to respond to known packs query`
  并 `disconnect()`。在这 5 分钟里，与它绑定的 `Player`、`PlayerSocketConnection`、
  `ClientSettings`、`GameProfile`、`PlayerInputs` 全部存活、无法 GC。
- 证据（专门复现，`--connections 0 --attacks premature-finish --attack-interval 0.02`）：
  单条 premature-finish 客户端 0.09 秒就收到 protocol-error 断线退出，服务端侧却：

  | 时刻 | 卡在 doConfiguration 的线程 | 存活 Player | 累计攻击 |
  | --- | --- | --- | --- |
  | t≈90s | 253 | 266 | 1,572 |
  | t≈120s | 291 | 309 | 1,844 |
  | t≈150s | 317 | 337 | 2,116 |
  | 攻击停 + 静置 5 分钟 | **0** | 42（回落中） | — |

  线程 dump 里 189/190 条线程栈顶都是
  `io.github.bearisbug.synaxis.network.ConnectionManager.doConfiguration`；
  `GC.class_histogram` 同步显示约 197 个存活 `Player`。静置 5 分钟后线程归 0、Player 回落，
  证明是**超时上界内的滞留而非永久泄漏**。
- 影响：这是放大型资源占用——离线模式下任意用户名、一次 0.09 秒的握手+登录+早退，就换来服务端
  5 分钟的一条虚拟线程 + 一整个 Player 对象图。低速持续（实测 9 次/秒即累积数百上千条）即可把
  内存与线程数抬到攻击速率 ×300 秒的水平；正常玩家高延迟掉线也会踩到同一路径。稳态与 churn 两组
  没有触发它，正因为那两组的连接都**完整进了 play 之后**才断开，走的是另一条能即时回收的路径。
- 复现步骤：见本目录 `README.md` 末节。
- 归属：引擎缺陷，按任务边界本轮不修，留待下一轮。可能的方向（仅记录，未实施）：断开 / socket
  关闭时取消或完成 `knownPacksFuture`，或把默认 5 分钟超时压到秒级。

### 未发现的项（如实记录）

- 稳态 20 分钟、churn 10 分钟内，tick 时间、堆存活集、线程数、FD 均**无单调增长**；
  唯一的 tick 尖刺是玩家进场时的一次性区块生成（第 0 分钟，≤ 68 ms），此后每分钟 max ≤ 4 ms。
- 三组停机都干净：退出码 0，无残留 java 进程 / 线程。
- 六种攻击均未使正常连接掉线、未使服务端崩溃或挂起、日志无堆栈。

## 局限

- 单机 loopback，无真实网络抖动；RTT 含 harness 自身 10 ms select 循环。
- 核心模式是平坦世界、无实体 AI / 无玩法 tick 负载，测的是网络 + 连接生命周期 + 世界 tick 底座，
  不代表 game-rts 满载。稳态连接数（64）对核心是轻载，压力主要由 churn 与攻击两组提供。
- JFR 只开了 `synaxis.ServerTickTime` 与虚拟线程起止；未开分配采样（`jdk.ObjectAllocationSample`）。
