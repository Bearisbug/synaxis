# Synaxis 服务端核心压测 / 长稳报告（2026-09-18）

- 对象：`server-app` 核心模式（`-Dsynaxis.app.core-mode=on`，宿主自建平坦世界 + `synaxis:main`
  echo 通道，任何用户名放行）。协议 776 / MC 26.2。
- 机器：Apple Silicon，12 核，24 GiB，JDK 25.0.4.1，端口 25565。
- harness：`tools/loadtest/soak.py`（本目录 `README.md` 有一条命令的跑法与采集手段表）。
- head：三组长跑在 `84b68cd75d`，SOAK-1 / SOAK-2 的证据表在 `30cec4073c` 上重测（两者之间只有 harness 与文档改动，引擎代码未变）；分支 `worktree-wf_3e0dff43-904-1`。
- 三组都用 `stop` 停机，Gradle `GRADLE_EXIT=0`，停机后 `pgrep synaxis.app.Main` 为空。
- **本轮只跑与记录，不修引擎缺陷。** 改动只在 harness、本报告与文档登记（`docs/DESIGN.md` §28 的
  两条技术债、`docs/TEST.md` §3 的工具登记），引擎代码一行未动。

## 压力是否到位

harness 用单 selector 循环驱动全部连接，只有登录握手在 8 个 worker 线程上跑。采样里同时记了服务端
与 harness 两侧的 CPU，用来判断压力落在哪一侧：

| 组 | 服务端峰值 CPU | harness 峰值 CPU | 峰值 load1 | 说明 |
| --- | --- | --- | --- | --- |
| 稳态 64 | 17.4% | 21.2% | 5.55 | 两侧同量级、harness 还更重，这一组不能用 CPU 判断瓶颈——见下方结论 |
| churn | 54.8% | 16.2% | 5.99 | 每秒 5 次全量登录（握手+登录+configuration+进世界）把服务端推到 55% |
| 恶意 | 72.6% | 20.4% | 5.99 | 攻击解码 + 64 连接负载叠加，服务端到 72% |

结论：稳态组服务端 17.4%、harness 21.2%，**两侧是同一量级、harness 反而更重**，所以这一组不能拿
CPU 说「服务端还有余量」，要看负载达成率：目标 64×10×1200 = 768,000 个移动包，实发 730,656，
达成 95%，缺口来自开跑时 8 个 worker 逐条登录的爬坡（登录完成前那几秒还没有移动包），不是 CPU
饿死。达成的 608.9 包/秒在 20 TPS 下折合每 tick 约 32 个移动包，tick 中位数 0.26 ms 离 50 ms 预算
差两个数量级——**核心在这个连接量级远未到瓶颈**，但这也意味着本轮没有把玩家侧负载推到拐点，稳态组
是长稳而不是过载。churn 与恶意两组服务端 CPU 到 55%–73%、明显高于同期 harness 的 16%–20%，压力确实
落在服务端一侧。真正的资源问题不是 CPU，而是下面 SOAK-1 的连接生命周期。

## 组 1：稳态（64 连接 / 20 分钟）

`--connections 64 --duration 1200 --move-hz 10 --echo-hz 0.5 --gc-probe-interval 300`

- 登录 64/64 成功，0 失败，0 断线；730,656 个移动包（608.9/s），7,648 次 KeepAlive 应答。
- tick（`synaxis.ServerTickTime`，24,412 个）：整段 p50 0.257 / p99 0.553 / max 25.97 ms，
  **超过 50 ms 的 0 个**（DESIGN.md §15 的 SLO 是单 tick P99 < 50 ms）。max 25.97 ms 落在第 0 分钟，
  是玩家进场时核心世界按需生成区块（`synaxis.ChunkGeneration` 在 t=0 集中出现）；第 1 分钟起每分钟
  p99 稳定在 0.47–0.62 ms、max 1.5–4.4 ms，无漂移。
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
- tick 12,609 个：整段 p50 0.31 / p99 0.87 / max 49.7 ms（max < 50 ms，故超 50 ms 的也是 0 个），
  第 1 分钟起每分钟 max ≤ 4 ms。max 49.7 ms 同样在第 0 分钟，与首批玩家进场的区块生成同时：
  `synaxis.ChunkGeneration` 在 t=0 的一秒内 529 次、单次最长 73 ms，但生成本身跑在虚拟线程上，
  不计入这一个 tick——tick 里看到的是它的下游代价（区块下发与实体可见性）。
- RSS 在 360–385 MiB 间波动，不随 churn 次数单调上行。

判定：**完成整个登录、进入 play 再挂断的连接会被及时回收**——线程 / FD / 堆均不随 churn 增长。

## 组 3：恶意 / 边界（六种攻击 + 64 连接负载 / 10 分钟）

`--connections 64 ... --attacks zero-frame,alloc-bomb,nbt-bomb,premature-finish,bad-version,deep-recursion --attack-interval 1.5`

- 攻击 185 次，全部探针退出码 0；64 条正常连接**零断线、零失败**，730k 量级移动包照常。
- tick 12,409 个：整段 p50 0.265 / p99 0.692 / max 67.8 ms（第 0 分钟区块生成），
  第 1 分钟起每分钟 max ≤ 2 ms —— 攻击没有在正常负载上制造尖刺。这一组的 max 超过 50 ms，
  但 SLO 口径是 P99 < 50 ms（DESIGN.md §15），三组 p99 均 ≤ 0.87 ms，不构成违约；本组超 50 ms 的
  tick 计数未记入本报告（harness 的 `over_50ms` 字段有，产物已按要求删除）。
- 服务端日志：每次解码类攻击一行 WARN，带 `connection_id` / `state` / `reason`，**无堆栈**
  （全日志 `at `/`Caused by` 行数为 0）。三类可观察 WARN：
  - zero-frame → `DataFormatException: Packet length must be positive: 0`
  - alloc-bomb → `IllegalArgumentException: Collection size (65,535) is out of the allowed range (0..100)`
  - nbt-bomb → `IllegalArgumentException: NBT is too large (max: 2097152 bytes)`
  bad-version 走 LoginDisconnect、deep-recursion 非创造玩家整包跳过，均不崩不挂。
- **「所有 WARN 都带 `connection_id`」只对上面三类解码断线成立**，另有两行 WARN 不带任何连接标识，
  docs/TEST.md §3 的日志关联口径在它们上面有真实反例：
  - `ConnectionManager.java:280` 的 `Player <username> failed to respond to known packs query`
    ——只有用户名。这是 SOAK-1 超时到点时打的那一行，每轮 premature-finish 洪泛停止约 5 分钟后
    成批出现（3,792 次攻击共 684 行，18%——其余连接在走到 known-packs 等待之前就结束了）。
  - `PacketReading.java:296` 的 `WARNING: Packet (...) 0x.. not fully read (NetworkBuffer{...})`
    （由默认开启的 `synaxis.warn-unread-bytes-packet` 控制）——打在包解码点，拿不到连接上下文。
    复核者在 premature-finish 洪泛里以约 1/540 的频率抓到过 `ClientChangeDifficultyPacket 0x4`
    这一形状；本轮 3,792 次同样的攻击重跑一次都没命中（`grep -c 'not fully read'` = 0），所以只能
    记下代码事实与他人观察，命中条件未定位。
- 堆存活集 33.1 → 32.4 → 29.1 MiB，不涨。

判定：**攻击不影响正常连接、服务端不崩不挂；解码断线的日志规范（一行 WARN + connection_id +
无堆栈），但上面两行 WARN 是这条规范的例外。**

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
- 证据（专门复现，`--connections 0 --duration 180 --attacks premature-finish --attack-interval 0.02`，
  实测攻击速率 9.05 次/秒 = 1,362 次 / 151 秒）：单条 premature-finish 客户端 0.09 秒就收到
  protocol-error 断线退出，服务端侧却按攻击次数堆积。下表每一行的四个数取自同一时刻（一次
  `Thread.dump_to_file` + 一次 `GC.class_histogram`，后者自带 Full GC）：

  | 攻击开始后 | 累计攻击 | 卡在 doConfiguration 的虚拟线程 | dump 里的全部线程 | 存活 Player |
  | --- | --- | --- | --- | --- |
  | 30 s | 271 | 145 | 172 | 154 |
  | 60 s | 544 | 165 | 193 | 181 |
  | 90 s | 816 | 244 | 272 | 260 |
  | 120 s | 1,089 | 315 | 340 | 335 |
  | 151 s | 1,362 | 356 | 381 | 377 |

  末行这一刻，滞留线程占 dump 里全部线程的 93%（356 / 381）。停止攻击后按同样方式测回收：

  | 停止攻击后 | 卡在 doConfiguration 的虚拟线程 | 存活 Player |
  | --- | --- | --- |
  | 0 s | 252 | 275 |
  | 60 s | 242 | 265 |
  | 121 s | 115 | 138 |
  | 181 s | 20 | 43 |
  | 241 s | **0** | 23 |
  | 301 s / 361 s | 0 / 0 | 23 / 23 |

  线程确实在 5 分钟的超时上界内全部回收，**滞留有上界、不是无界泄漏**；但 `Player` 不归零，
  见下面的 SOAK-2。
- 影响：这是放大型资源占用——离线模式下任意用户名、一次 0.09 秒的握手+登录+早退，就换来服务端
  5 分钟的一条虚拟线程 + 一整个 Player 对象图。低速持续（实测 9 次/秒即累积数百上千条）即可把
  内存与线程数抬到攻击速率 ×300 秒的水平；正常玩家高延迟掉线也会踩到同一路径。稳态与 churn 两组
  没有触发它，正因为那两组的连接都**完整进了 play 之后**才断开，走的是另一条能即时回收的路径。
- 复现步骤：见本目录 `README.md` 末节。
- 归属：引擎缺陷，按任务边界本轮不修，留待下一轮。可能的方向（仅记录，未实施）：断开 / socket
  关闭时取消或完成 `knownPacksFuture`，或把默认 5 分钟超时压到秒级。

### SOAK-2（P2）：约 1% 的早退连接留下永不回收的 `Player`

- 现象：上表里线程归 0 之后，`GC.class_histogram`（自带 Full GC）连续三次都还数到 23 个存活
  `Player`，对应此前累计 2,165 次 premature-finish。再打一轮 1,627 次同样的攻击、等到线程再次
  归 0，残留从 23 升到 40（+17，同样连过三次 Full GC 不降）——**残留随攻击次数累积，不会自愈**，
  比例约 1%。
- 对照：同一进程上做 5 次完整的 `probe.py login --hold 2` 再断开，残留计数 23 → 23 不变。所以
  留下残留的是 configuration 阶段早退这条路径，正常连接的回收是干净的（也与组 1 / 组 2 的堆存活集
  不涨一致：40 个 `Player` 的浅堆只有 18.5 KiB，连带保留的对象图更大但未测，20 分钟量级的长跑
  都看不出来）。
- 未定位：这 23 / 40 个对象被谁持有本轮没有查（需要堆 dump 做 GC 根路径分析），因此只记为观察，
  不给结论。它与 SOAK-1 大概率同源（同一条早退路径），但 SOAK-1 有 5 分钟上界、这条没有上界，
  所以分开记。
- 复现：SOAK-1 的复现命令跑两轮，每轮结束后静置到线程归 0，再
  `jcmd <pid> GC.class_histogram | grep 'synaxis.entity.Player$'` 对比两轮的残留数。

### 未发现的项（如实记录）

- 稳态 20 分钟、churn 10 分钟内，tick 时间、堆存活集、线程数、FD 均**无单调增长**（SOAK-2 的
  残留量级太小，这两组的堆存活集看不出来）；
  唯一的 tick 尖刺是玩家进场时的一次性区块生成（第 0 分钟，≤ 68 ms），此后每分钟 max ≤ 4 ms。
- 三组停机都干净：退出码 0，无残留 java 进程 / 线程。
- 六种攻击均未使正常连接掉线、未使服务端崩溃或挂起、日志无堆栈。

## 局限

- **只跑了一条 tick 线程。** 三组都是默认起服，`ServerConfig.java:185` 的 `dispatcherThreads`
  默认 1，于是审计里最大的一簇跨 TickThread 缺陷（F27 / F17 / F58 / F22 / F37 那类 ABBA 锁序与
  tick 线程间竞态）在这个配置下根本不可能显现，DESIGN.md:417 写的「各区块分区并行」也没有被验证。
  要覆盖它必须另起一组 `dispatcherThreads > 1` 的长跑。
- **客户端包面只有 4 种。** harness 发 `ClientKeepAlive` / `ClientTeleportConfirm` /
  `ClientPlayerPositionAndRotation` / `ClientPluginMessage`，复用的 `probe.py` 在 configuration
  阶段也从不发 `ClientSettings`。因此视距变更（F11 在 2026-09-16 刚改成按 tick 节流的 `chunkQueue`
  路径，恰是只有长跑才显形的那类）、方块放置 / 破坏与光照、容器点击、聊天与命令补全全程零覆盖。
  另外每条连接只在半径 16–72 格的圆上走，64 条连接始终挤在 spawn 周围约 145 格的圆盘里，而
  DESIGN.md:375 的 tick 预算场景要求「各自站在不同区块」——区块与分区铺开的那一面没测到。
- 单机 loopback，无真实网络抖动；RTT 含 harness 自身 10 ms select 循环。
- 核心模式是平坦世界、无实体 AI / 无玩法 tick 负载，测的是网络 + 连接生命周期 + 世界 tick 底座，
  不代表 game-rts 满载。稳态连接数（64）对核心是轻载，压力主要由 churn 与攻击两组提供。
- JFR 只开了 `synaxis.ServerTickTime` 与虚拟线程起止；未开分配采样（`jdk.ObjectAllocationSample`）。
