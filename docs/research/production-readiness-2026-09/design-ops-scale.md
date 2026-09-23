# 运维与规模四件事（外加压测）技术设计草案（2026-09-23）

- 状态：草案，未评审。将来并入 `docs/DESIGN.md`（引擎侧：连接上限、tick 线程、压测判据）与 `docs/DESIGN-M2.md` 或新里程碑分册（宿主侧：台账、客户端 CI）；ID 暂不编号，文中用 ①～⑤ 与 R1～R8 指代。
- 基线：`main` `3085e0edab`。本文只读仓库，除本文件外未改任何文件。
- 边界：「世界存储与区块」（Anvil 读取器已删、只有 `NoopChunkLoaderImpl`、无自动卸载）由 `docs/DESIGN-M3.md` 负责，本文不设计，差距只标「归 DESIGN-M3」。加密与 Mojang 正版验证、PostgreSQL + Redis 持久化也不在本文范围；本文只写压测 harness 与连接闸门需要它们提供的接口。

## 0. 结论与推荐

五件事里有三件的现有实现在 200～500 人档必然不够，按以下顺序做。

1. **② 连接闸门先做**：它是其余几件的前提——`Server#start` 的 accept 循环没有任何上限（`network/socket/Server.java:82-117`），台账写盘量随连接数增长（③），压测机器人从同一 IP 进来也要靠它的白名单（⑤）。推荐四层：全局套接字上限（超限直接关 socket）、每 IP 并发上限与登录令牌桶（超限回 `LoginDisconnect`）、每 tick 入场上限（排队不踢，Paper 默认 5）、分片容量（`synaxis:server-full`）。新增三个引擎断线键 `synaxis:server-full`、`synaxis:login-throttled`、`synaxis:too-many-connections`。**这些检查必须排在 Mojang 会话验证之前**，否则每个被拒连接都要花一次 RSA 解密和一次 sessionserver 请求。
2. **③ 台账改成有界队列 + 单写线程**：现状每条事件 `Files.writeString(CREATE, APPEND)` 一次，实测 26 µs（`docs/DESIGN.md` §28 第一行）。按 M2 §15 的下令预算，50 人最坏 4,500 事件/秒，折合每 tick 5.85 ms 的 tick 线程时间；500 人是每秒 1.17 s，单线程做不完。改成 65,536 行的有界队列、1 s 或 64 KiB 刷一次，进程崩溃最多丢 1 s。**缓冲只省系统调用，不省字节**：`docs/DESIGN.md` §28 那条「改成带缓冲写入就能与连接 churn 脱钩」的说法不成立，字节与 churn 脱钩靠的是 ② 在 configuration 之前拒掉连接。
3. **⑤ 压测换成 Java 机器人**：`soak.py` 从不回 `ChunkBatchReceived`，所以每个连接只收到服务端第一批 9 个区块——gate-1000 那轮 600 s 里 50 条连接一共收到 450 个 `ChunkDataPacket`（= 50 × 9，50,800 字节），同期 `UnloadChunkPacket` 903,369 个。区块带宽从没量到过。Python 发包端单进程 2,880 包/秒、双进程 3,990 包/秒，而 Java 发包端 `PacketFlood` 在同一台机器上跑到过 40,015 包/秒 0 丢失（`docs/research/soak-2026-09-18/report.md` 第 580 行）；任务描述里「Java PacketFlood 5,015」是那一轮的**目标速率**，不是它的上限。
4. **① 真客户端 CI 常驻**：在现有 `client-gametest.yml` 上加 8 条回归断言 R1～R8，判定读 `report.txt` 与服务端台账，不读退出码。默认触发：PR（按路径过滤）+ push main + 每日一次；前 20 次连续全绿之前不设为必需检查。
5. **④ tick 多线程以 `docs/DESIGN-M3.md` 的 `API-M3-009` 为准**（空间分格 `ThreadProvider.grid`，默认仍 `counter` + 1 线程）。本文只补三件引擎侧的事：`ThreadDispatcherImpl.java:141` 的 `Math.abs(id) % n` 对 `Integer.MIN_VALUE` 会得到负下标，要改 `Math.floorMod`；全量测试从没在 `dispatcherThreads > 1` 下跑过；跨线程 `Acquirable` 全部经过一把全局锁（`AcquirableImpl.java:20`）。**RTS 单局不开多线程**：插件逻辑整体跑在调度线程上，开了也不并行，而 50 人 1,000 单位实测 tick P99 只有 8.617 ms。

需要用户拍板的点汇总在 §6，每条都给了默认值与切换条件。

## 1. 现状复核（任务描述里的已知现状逐条核对）

| 论断 | 结论 | 证据 |
| --- | --- | --- |
| Auth 只有 Offline / Velocity / Bungee | 属实 | `src/main/java/io/github/bearisbug/synaxis/Auth.java:19` `sealed interface Auth`，三个 record |
| server-app 用 `Auth.Offline` | 属实 | `server-app/src/main/java/io/github/bearisbug/synaxis/app/Main.java:96` `MinecraftServer.init(new Auth.Offline())` |
| encryption request 被删，留 `ReservedPacket` | 属实 | `network/packet/PacketVanilla.java:386` `entry(ReservedPacket.class, …), // minecraft:hello, offline mode never asks for encryption`；服务端方向的 `ClientEncryptionResponsePacket` 仍注册（`:286`），`NetworkBuffer#cipher`（`NetworkBufferImpl.java:287`）仍在 |
| Anvil 读取器已删、只有 Noop 加载器 | 属实，**归 DESIGN-M3** | `grep -rln 'implements ChunkLoader' src/main/java` 只输出 `instance/NoopChunkLoaderImpl.java`；`instance/` 下无 anvil 目录 |
| 没有区块自动卸载 | 属实，**归 DESIGN-M3** | `instance/InstanceManager.java:93` 注销整个实例时才 `unloadChunk`，别处无自动卸载路径 |
| `ServerProcessImpl` 写死 `ThreadProvider.counter()`、`dispatcherThreads` 默认 1 | 属实 | `ServerProcessImpl.java:104`；`ServerConfig.java:185` `private int dispatcherThreads = 1` |
| 连接数无上限 | 属实 | `network/socket/Server.java:82-117` accept 循环不计数；fd 耗尽时 `:85` 抛 `IOException`，`:89` 默认吞掉后 `continue` 空转 |
| 事件台账逐行同步写 | 属实 | `server-app/.../app/EventLedger.java:72-75` 每条事件 `Files.createDirectories` + `Files.writeString(…, CREATE, APPEND)` |
| Java PacketFlood 上限 5,015 包/秒 | **不准确** | 5,015 是 `TC-034` 那一档的目标速率；组 7 把速率抬到 40,015 包/秒仍 0 掉线（`docs/research/soak-2026-09-18/report.md:580`） |

## ① 真客户端端到端回归常驻 CI

### 1.1 现状

- 工作流 `.github/workflows/client-gametest.yml`：只在 push `client-gametest-ci` 分支或手动触发时跑（第 3～12 行）；`ubuntu-latest` + Temurin 25 + Xvfb + Mesa llvmpipe；同一 job 先起 server-app 玩法模式，再依次跑 `runClientGameTest`（dev）与 `runProductionClientGameTest`（Loom 生产启动器，装 `remapJar`）；最后 `cat` 两份 `report.txt` 与台账，并上传截图、报告、日志、台账，保留 14 天。
- 测试入口 `client-fabric/src/gametest/.../SpikeClientGameTest.java`：S0 列出含 `synaxis` 的模组（第 58～61 行），S1 连服并等待「进世界或 `DisconnectedScreen`」最多 1,200 tick（第 49、76～77 行），之后按 `SYNAXIS_SPIKES` 跑 S2～S5。报告在 `runTest` 末尾一次性写出（第 104、263～271 行）。
- 当前测试**不断言**：S1 断线时只往报告里写 `S1 disconnected: …`，测试照常结束。`docs/research/client-spikes-2026-09/README.md` 第 38～40 行写明「判定看 `report.txt` 与台账，不看退出码：S1 断线时 CI 也是绿的（run 35813814226）」。
- 退出码不可信的另一半：本地 aarch64 容器里 LWJGL 自带 `libopenal.so` 在退出析构段错误，Gradle 报 exit value 134（同文件第 61～63 行）；x86_64 上是否复现未测，CI run 35815252028 / 35815701720 是绿的。
- 耗时：CI 上 S0～S2、dev 与生产各启动一次，每次约 5 分 40 秒（同文件第 75～76 行）；S3～S5 合并后 CI 上还没跑过。
- 下令路径的可达性：客户端的选中与下令逻辑在 `RtsClient#selectAt` / `#orderAt`（`client-fabric/src/main/.../RtsClient.java:145-181`），但实例是 `SynaxisClientMod` 的 `private static final RTS`（`SynaxisClientMod.java:30`），鼠标输入到这两个方法的接线属阶段 6b，模组里目前只有 `RTS.receive` 与 `RTS.reset` 两处调用（`grep -n 'RTS\.' SynaxisClientMod.java`：第 44、78 行）。**所以 gametest 现在无法经由玩家输入下令，要直接调这两个方法。**
- 断线文案：模组自带 `assets/synaxis/lang/en_us.json` 与 `zh_cn.json`，覆盖 §14 与 M2 §14 的 10 个断线键，所以带模组的客户端能把 `synaxis:*` 键渲染成句子；原版客户端只看到键本身。

### 1.2 回归断言 R1～R8

新增一个 `RegressionClientGameTest`（与 spike 并列，spike 只在每日任务里跑），按下表顺序执行，R8 放最后因为它会断开连接。每条断言在报告里写一行 `R<n> PASS|FAIL <证据>`，**写报告放进 `try/finally`**：现在报告只在末尾写，中途一个 `waitFor` 超时抛异常就一行都不留，CI 看到的是「没有报告」而不是「哪条失败」。

| ID | 断言 | 客户端侧怎么量 | 服务端侧交叉验证（CI 步骤读台账与 `server.log`） | 超时 |
| --- | --- | --- | --- | --- |
| R1 | 模组与嵌套的 `:protocol` 已加载 | `FabricLoader#getAllMods` 同时含 `synaxis` 与 `io_github_bearisbug_synaxis_protocol` | — | 标题画面出现后立即判 |
| R2 | configuration 阶段握手通过 | `ClientHandshake` 进入 accepted 状态（客户端日志已有 `Handshake accepted: schema_version=…`） | 该连接依次有 `session_entered_configuration` 与 `client_verified`，没有 `client_rejected` | 随 R3 |
| R3 | 进世界 | `client.level != null && client.player != null` | 台账有 `player_entered_play` | 1,200 tick（沿用 `CONNECT_TIMEOUT_TICKS`） |
| R4 | 收到单位 | `RtsClient#bindings().all()` 非空，且至少一个 `Binding#commandable()` 为真；`unmatchedStateEntries()` 记入报告但不判 | — | 进世界后 200 tick |
| R5 | 选中 | 从相机位置向一个本方单位的实体中心构造射线调 `selectAt`，`selected()` 等于该 `unit_id` | 台账有 `unit_selected` 且 `count = 1` | 40 tick |
| R6 | 下令后单位移动 | 对该单位 8 格外的地面调 `orderAt`；`feedback().code()` 为 `OK`；该单位绑定的实体在 100 tick 内水平位移 ≥ 2.0 格 | 台账有 `unit_order_issued`（只在 `code=ok` 时写） | 结果码 40 tick，位移 100 tick |
| R7 | 光照非零 | 在出生点所在列，地表上一格的 `LightLayer.SKY` 读数为 15，地表下一格为 0（与 `docs/DESIGN.md` §28 光照 parity 技术债里现有的唯一一条断言同口径）；截图照拍但不参与判定 | — | 进世界后 100 tick |
| R8 | 断线原因可读 | 客户端发一条只有服务端才会发的游戏消息（`GamePayload` 包一条 S2C 消息），等到 `DisconnectedScreen`；其叙述文本包含 `en_us.json` 里 `synaxis:protocol-error` 的译文，且不含子串 `synaxis:` | `server.log` 有一行 `Kicking connection_id=… reason=synaxis:protocol-error` | 200 tick |

R8 用 protocol-error 而不是停服触发断线，是因为同一个服务端还要接第二次（生产 jar）启动；「服务端专用消息 → `synaxis:protocol-error`」这条路径已有集成测试 `GameChannelIntegrationTest#aMessageOnlyTheServerSendsIsAProtocolError`（`docs/research/attack-surface-2026-09/README.md` 第 33 行），CI 这里验的是客户端把键渲染成句子。

读 `RTS` 实例的方式：默认在 gametest 源码集里用反射读 `SynaxisClientMod.RTS`，模组 jar 零改动；字段改名时 R4 直接 FAIL，不会静默通过。切换条件：阶段 6b 把鼠标输入接上之后，R5 / R6 改走 `TestInput` 的真实点击，反射删掉。

### 1.3 判定规则

CI 在两次客户端启动之后加一个判定步骤，规则全部可机读：

1. 本次运行执行了几次客户端启动（PR 1 次，main 与每日 2 次，记为 N），就必须有几份 `report.txt`（`client-fabric/build/run/clientGameTest/synaxis-gametest/report.txt`、`…/productionClientGameTest/…/report.txt`）；缺一份即失败。
2. 每份报告里 `^R[1-8] PASS` 恰好 8 行、`^R[1-8] FAIL` 0 行。
3. 台账交叉验证：`client_verified` 行数 = N，`client_rejected` 行数 = 0，`unit_order_issued` 行数 ≥ N（这个事件只在 `code=ok` 时写，见 `docs/DESIGN-M2.md` §20）；`server.log` 中 `reason=synaxis:protocol-error` 的 kick 行 = N。
4. Gradle 客户端任务的退出码**只记录不判定**：两个客户端步骤加 `continue-on-error: true`，失败与否只由第 1～3 条决定。理由是退出码两个方向都错过——S1 断线时是 0，本地 aarch64 正常跑完是 134。

### 1.4 触发方式

| 场景 | 触发 | 跑什么 | 预计耗时 |
| --- | --- | --- | --- |
| PR | `pull_request`，路径过滤：`client-fabric/**`、`protocol/**`、`server-app/**`、`game-rts/**`、`plugin-api/**`、`src/main/java/**/network/**`、`src/main/java/**/listener/**`、本工作流文件 | 只跑生产 jar 一次启动 + R1～R8 | 约 4 分钟（按「两次启动 5 分 40 秒」折算，未实测） |
| main | push `main` | dev + 生产两次启动 + R1～R8 | 约 6 分钟 |
| 每日 | `schedule` 每日一次（UTC 18:00，即北京时间 02:00） | 两次启动 + R1～R8 + S2～S5 spike（S3 带固定 `SYNAXIS_S3_DISTANCES`） | 未测；S0～S5 在本地容器约 3 分钟一轮 |

- 必需检查：前 20 次连续全绿之前不设为 branch protection 的必需检查，之后设为必需。
- 分钟数：仓库当前是私有（`docs/DESIGN.md` §28 Open Question）。GitHub 对私有仓库的免费额度与计费规则本轮未核实；按每天 10 次 PR 推送 × 4 分钟 + main 与每日各约 6 分钟估，每月约 1,600 分钟。

### 1.5 缺口

- CI 上无法测 FPS（llvmpipe 软件渲染），`docs/DESIGN-M2.md` §15「同屏 200 单位 ≥ 60 FPS」仍归验收人。
- R7 只证「服务端下发了非零光照数据」，证不了光照逐值正确；逐值对拍归 DESIGN-M3（`REQ-M3-006` 对拍夹具）。
- 加密与正版验证恢复后，CI 的 gametest 客户端没有 Mojang 账号，只能连离线模式的服务端。离线路径与正版路径的差异（加密、sessionserver）要靠 ⑤ 的机器人配合本地假 sessionserver 覆盖，或者在 CI 里起一个假 sessionserver，由鉴权设计决定。

## ② 连接数上限与登录节流

### 2.1 现状与威胁

- `Server#start`（`Server.java:74-117`）每接受一个 socket 就起读、写、登录看门狗三条虚拟线程并分配一个 `PlayerSocketConnection`，没有全局计数，也没有按 IP 计数。
- 每条连接各自有界：登录前 `loginTimeout` 30 s 关闭，登录后 keep-alive 15 s 踢出。但条数由对端决定：`docs/research/attack-surface-2026-09/README.md` G1 算过，一个对端以 100 连接/秒开合就能常驻 3,000 条。
- fd 耗尽后 `accept()` 抛异常，默认 `suppressConnectionAcceptErrors=true` 吞掉并立即重试，accept 线程吃满一个核，日志一行都不写（同文件 G1）。
- 入场不限速：`ConnectionManager#updateWaitingPlayers`（`network/ConnectionManager.java:444-459`）每 tick 把等待队列整个 drain 完。实测影响：4,000 单位档第 0 分钟的 tick P99 14.141 ms，高于其后五分钟的 10.583～11.536 ms，首秒出现 65.4 ms 的 tick，报告归因为「50 条连接同秒进 play 的进场代价」（`docs/research/soak-2026-09-22/report.md` 第 121～126 行）。500 人分片重启后全员同时重连，这个尖刺会按人数放大。
- PROXY protocol：开启时真实来源地址要读到第一个包才知道（`PlayerSocketConnection.java:126-131` 在 `read` 里改写 `remoteAddress`），所以按 IP 的检查不能放在 accept 那一刻。
- 已有的每连接包速率：§15 写「单连接每秒 > 500 包断线」，实现里只有 50 包/tick 的排空与 1,024 深的队列（`docs/DESIGN.md` §28 第二行技术债）。本文不改它，只记一笔：Paper 的 `packet-limiter.all-packets` 默认 7 s 窗口 500 包、动作 KICK（docs.papermc.io 全局配置页）。

### 2.2 Paper / Velocity 参照

| 项 | 默认值 | 出处 |
| --- | --- | --- |
| Bukkit `settings.connection-throttle` | 4,000 ms：同一 IP 两次连接之间至少隔 4 s | https://docs.papermc.io/paper/reference/bukkit-configuration/ |
| Paper `misc.max-joins-per-tick` | 5：单 tick 最多进服 5 人 | https://docs.papermc.io/paper/reference/global-configuration/ |
| Paper `packet-limiter.all-packets` | 7 s 窗口、500 包、KICK | 同上 |
| Velocity `login-ratelimit` | 同一 IP 两次连接的最小间隔（ms），0 关闭；默认值文档页未写，**未核实**（常见说法是 3,000） | https://docs.papermc.io/velocity/configuration/ |
| Gate | 按 IPv4 /24、IPv6 /64 做连接与登录配额 | `docs/research/05-proxy-network.md` 第 118 行 |

Bukkit 的固定 4 s 间隔对网吧、校园网这类多人共用出口 IP 的场景太严（同一 IP 第二个玩家要等 4 s），所以下面用令牌桶而不是固定间隔。

### 2.3 设计：四层闸门

所有检查都在**会话验证之前**：握手包决定 intent，`LoginStart` 之后才会发 encryption request；被拒的连接不产生 RSA 解密、不访问 sessionserver，也不进 configuration，所以不写台账（③ 的字节上界靠这一点）。

| 层 | 检查点 | 默认值（引擎） | MMO 分片宿主设置 | 超限行为 | 断线键 |
| --- | --- | --- | --- | --- | --- |
| L0 全局套接字 | `Server#start` accept 之后、起线程之前 | `synaxis.max-connections = 1024` | 3 × 分片 `capacity`（500 人档 1,500） | 立即 `close()`，不发任何包（此时不知道对端是 status 还是 login，也不值得花一次写） | 无 |
| L0′ accept 失败退避 | 同上 `catch (IOException)` | 失败后 sleep 100 ms 再 accept | 同 | 不再空转；按 10 s 预算写一行带区间累计次数的 WARN（形状同 `PacketReading#reportUnreadBytes`），`suppressConnectionAcceptErrors` 只决定是否附栈 | 无 |
| L1 每 IP 并发 | 处理 `ClientHandshakePacket` 时（`listener/preplay/HandshakeListener.java:46`），PROXY 解码已完成 | `synaxis.max-connections-per-ip = 16`（IPv4 按 /32，IPv6 按 /64） | 同，可配白名单网段 | status intent：直接关；login / transfer intent：`LoginDisconnect` | `synaxis:too-many-connections` |
| L2 每 IP 登录令牌桶 | 同上，仅 login / transfer intent | 容量 3、每 2 s 回填 1 个（`synaxis.login-rate-per-ip = 0.5/s`，`burst = 3`）；status 另一只桶：容量 10、每秒回填 1 | 同 | `LoginDisconnect` | `synaxis:login-throttled` |
| L3 分片容量 | `LoginStart` 处理时，在发 encryption request 之前 | `synaxis.max-players = 0`（0 = 不限，M2 行为不变） | = 频道 `capacity`（`docs/DESIGN-M3.md` `API-M3-010`，200～500） | `LoginDisconnect` | `synaxis:server-full` |
| L4 每 tick 入场 | `updateWaitingPlayers` 的 `playWaitingPlayers.drain` | `synaxis.max-joins-per-tick = 0`（不限，M2 行为不变） | 5（Paper 默认值） | 超出的留在队列，下一 tick 继续；不踢 | 无 |

- 计数释放：L0 / L1 的计数在 `PlayerConnection#disconnect` 与 socket 关闭的同一路径上减（与 `ConnectionManager#removePlayer` 同锁，形状照抄 `trackPlayer`，避免 SOAK-2 那类「插入晚于回收」的孤儿）。
- 令牌桶表：按 IP 前缀为键，是对端可编的维度，必须有界。上限 65,536 个键，超过时按最久未用淘汰；每 60 s 清掉桶已满的键。
- L4 的节奏：5 人/tick = 100 人/秒，500 人全部重连约 5 s 进完，加上登录延迟（50 连接实测 P99 124.684 ms，`soak-2026-09-22/report.md` 第 59 行）。
- 转服：跨大区 Transfer 进来的玩家走 transfer intent，照样过 L1～L4。同一公会从同一出口 IP 集体转服会撞 L2 的容量 3，所以 L2 对 transfer intent 用单独一只桶：容量 10、每秒回填 2。这个数没有实测依据，是按「一次副本 10 人从同一网吧转服」估的，压测里单列一档验证（§⑤）。
- 日志：每个被拒连接不写 INFO 行（拒绝数由对端决定），只按 10 s 预算汇总一行 `Connection gate rejected N connections in the last 10 s: too_many=… throttled=… full=… closed_at_accept=…`。这条满足 `docs/DESIGN.md` §15「日志量」一行的上界要求，新类进 `ArchitectureTest` 豁免名单时写明上界。
- 指标：`TickMonitor` 之外加 JFR 事件 `synaxis.ConnectionGate`（每 10 s 一条，四个拒绝计数与当前连接数），soak `summary.json` 新增 `connection_gate` 字段。

### 2.4 断线键

三个新键是连接层概念，不含玩法，住在引擎的 `DisconnectReasons`（`src/main/java/.../DisconnectReasons.java`，现有 6 个键），与 M2 §14 那四个住在 `plugin-api` 的游戏侧键区分开。

| 键 | 协议动作 | 语义 | 客户端处理约定 |
| --- | --- | --- | --- |
| `synaxis:server-full` | LoginDisconnect | 分片在线人数已达 `max-players` | 提示「本频道已满」，模组可自动换频道 |
| `synaxis:login-throttled` | LoginDisconnect | 同一 IP 登录太频繁 | 提示「稍候几秒再试」 |
| `synaxis:too-many-connections` | LoginDisconnect | 同一 IP 的并发连接数达上限 | 提示「同一网络连接数过多」，附报障途径 |

模组的 `en_us.json` / `zh_cn.json` 各加三行。L0 与 L0′ 不发包，所以没有键；对端看到的是 TCP 连接被对方关闭。

### 2.5 测试要点

每层一条先红后绿的集成测试（真起 `Server`、真 socket）：第 1,025 条连接被关闭且前 1,024 条不受影响；同一 IP 第 17 条握手收到 `synaxis:too-many-connections`；同一 IP 4 次登录连发时第 4 次收到 `synaxis:login-throttled`、2 s 后恢复；`max-players=2` 时第 3 人收到 `synaxis:server-full` 且服务端没有发 encryption request；`max-joins-per-tick=5` 时 12 人同 tick 等待，分 3 个 tick 进完。fd 耗尽的退避用 `ulimit -n` 调小的子进程测：accept 线程 CPU 不再满核（判据：`ps` 采样 < 5%），日志 10 s 至多一行。

## ③ 事件台账带缓冲写入

### 3.1 现状

- 实现：`server-app/src/main/java/io/github/bearisbug/synaxis/app/EventLedger.java`。`record(event, attributes)` 拼一行 JSON（第 63～71 行），然后 `Files.createDirectories` + `Files.writeString(…events-YYYYMMDD.jsonl, CREATE, APPEND)`（第 72～75 行），每条事件一次 open / write / close；失败经 `LogBudget` 10 s 一行（第 51、77 行）。
- 调用线程不止一种：configuration 虚拟线程（`HandshakeGate.java:249` `session_entered_configuration`、`:356` `client_rejected`）、准入回调（`AdmissionGate.java:101`）、启动线程（`PluginHost.java:142` `plugin_enabled`）、tick 线程（插件经 `PluginContext#analytics()` 写的对局与指令事件，`PluginHost.java:129` 把 `ledger::record` 交给插件）。`record` 没有同步，多个线程同时 append 依赖单次 `write` 的原子性。
- 代价：单次 append 26 µs、每行约 158 字节、每连接 2～3 行约 315 字节（`docs/DESIGN.md` §28 第一行）。
- 放大到分片规模：M2 §15 的下令令牌桶把指令侧压在每连接 ≤ 90 事件/秒（同上）。50 人最坏 4,500 事件/秒 × 26 µs = 每秒 117 ms、每 tick 5.85 ms，占 50 ms 预算的 11.7%；500 人是每秒 1.17 s，单线程做不完（按 26 µs 线性折算，未实测）。

### 3.2 设计

- 生产者：`record` 在调用线程上拼好整行（`ts` 取调用时刻，与现在相同），`offer` 进一条有界 MPSC 队列，**永不阻塞**。容量 65,536 行（按每行 158 字节约 10 MiB）。
- 消费者：一条虚拟线程 `Syn-Ledger-Writer` 独占写文件：用 `FileChannel`（`CREATE, APPEND`）持有打开的文件，64 KiB 的写缓冲；触发写盘的条件是距上次写盘满 1,000 ms、缓冲满 64 KiB、或遇到跨天（按事件自己的 `ts` 决定进哪天的文件，跨天时关旧文件开新文件）。
- 刷盘：写盘即 `write` 进内核页缓存，不逐次 `fsync`；`close()` 时 `force(true)` 一次。
- 停机：`Main` 在 `host::disableAll` 这个 shutdown 任务之后再注册一个关闭台账的任务，给 2 s 预算排空队列并 `force`。插件在 `onDisable` 里写的最后几条事件（如 `match_ended`）因此能落盘。
- 队列满：丢**新来的**那条，计数加一；下一次写盘时若计数 > 0，先写一条 `{"event":"ledger_dropped","count":N,"since":…}` 再清零，同时经 `LogBudget` 写一行 WARN。
- 门禁：`EventLedger` 仍经 `LogBudget` 输出，不进 `ClientDrivenLogArchitectureTest` 豁免名单。

### 3.3 崩溃丢失窗口

| 故障 | 最多丢什么 | 依据 |
| --- | --- | --- |
| `stop` 正常停机 | 0 条 | 关闭任务排空并 `force` |
| JVM 崩溃、`kill -9`、OOM | 最近 1 s 的事件 + 队列里未写的行（队列在正常负载下接近空） | 写盘间隔 1,000 ms |
| 宿主机掉电或内核崩溃 | 上一条 + 内核尚未回写的页缓存 | Linux 页缓存回写周期由 `vm.dirty_expire_centisecs` 决定，默认值与部署机的实际设置**未核实**；要把这一档压到 1 s，需要每次写盘后 `force(false)`，代价未测 |

### 3.4 对 `docs/DESIGN-M2.md` §20 埋点口径的影响

- `ts` 仍在事件发生的线程上取，所以按 `ts` 算的时间与间隔不变。soak 的寻路口径（`tools/loadtest/soak.py` `LedgerWatch`，按 `ts` 分 50 ms 桶求 `path_ms`）不受影响。
- 行序：同一个线程写的事件顺序不变；不同线程之间，文件里的行序与 `ts` 顺序可能差几微秒。§20 的指标都是计数之比（握手成功率、指令被接受率、到达率、对局完成率），不看行序；离线聚合脚本凡是要配对的地方（如 `session_entered_configuration` → `client_verified`）按 `connection_id` 配对，不按相邻行。
- 丢弃：出现 `ledger_dropped` 的那一天，聚合脚本在红字汇总里标「口径受损」，比率照算但不参与 §23 放量环的进入门槛。
- 崩溃：最后 1 s 的事件缺失，最可能缺的是在飞的 `unit_order_issued` / `unit_order_completed`；到达率的分母里会多出几条「既没完成也没 superseded」的指令。崩溃本身会让对局以 `match_ended` 缺失的形式出现，对局完成率本来就把它算作未完成。
- soak 读台账：`LedgerWatch` 在 `--settle`（默认 15 s）之后才读，大于 1 s 写盘间隔，不用改。

### 3.5 字节量与连接 churn

缓冲把每条事件的代价从一次 open / write / close 降到一次内存拷贝（新代价未测），但**每连接 315 字节不变**。`docs/DESIGN.md` §28 那条技术债写的「要让写盘量与连接 churn 脱钩，必须改成带缓冲的写入」应改写为：写入耗时靠缓冲脱钩，写盘字节靠 ② 的 L1～L3 脱钩——被拒的连接不进 configuration，就不产生会话事件。以 L2 默认值（每 IP 每 2 s 一次登录）计，单个 IP 每天最多 43,200 次登录、约 13.6 MB 台账；多 IP 攻击仍能按 IP 数放大，所以另设一道每日文件上限 `synaxis.app.ledger-max-bytes-per-day`，默认 1 GiB：到达后只写 `ledger_dropped` 汇总行，直到跨天。

### 3.6 与 PostgreSQL + Redis 的关系

台账留在本地 JSONL，不写进 PostgreSQL：它是离线分析数据，不是玩家状态。多分片后按分片汇总文件，由采集方式（日志代理或定时拷贝）决定，不在本文范围。

## ④ tick 多线程

**以 `docs/DESIGN-M3.md` 的 `REQ-M3-015` / `API-M3-009` / `ADR-M3-006` 的定论为准。** M3 已定：`ThreadProvider.grid(exponent)`，16×16 区块一格，线程号 = 格坐标 hash 对线程数取模，`RefreshType.NEVER`；`synaxis.thread-provider=grid|counter` 默认 `counter`，`dispatcher-threads` 默认 1；只有 MMO 分片宿主开启，M2 `Main` 不设。本节只补引擎侧接口的细节与对单局 RTS 的影响。

### 4.1 现状

- `ServerProcessImpl.java:104` `ThreadDispatcher.dispatcher(ThreadProvider.counter(), config.dispatcherThreads(), …)`。`counter()` 每次 `findThread` 返回自增计数（`thread/ThreadProvider.java:10-19`），同一片区域的区块被轮流分到不同线程。
- 上游 Minestom 在起点 `16842f1954` 同样写死：`git show 16842f1954:src/main/java/net/minestom/server/ServerProcessImpl.java` 第 101 行 `ThreadDispatcher.dispatcher(ThreadProvider.counter(), ServerFlag.DISPATCHER_THREADS)`，`ThreadProvider` 只有 `counter()` 一个内置实现，`DISPATCHER_THREADS` 默认 1（`ServerFlag.java:20`）。上游文档写「Threads are assigned depending on the `ThreadProvider`, a single thread is used by default」（https://minestom.net/docs/thread-architecture/acquirable-api/inside-the-api）。上游没有空间分格的现成实现，M3 的 `grid` 是我们自己写。
- tick 的执行顺序（`ServerProcessImpl.java:358-413`）：调度器任务 → 连接 tick（入场、keep-alive）→ **每个 `Instance#tick` 串行** → `dispatcher().updateAndAwait`（多线程只发生在这里：区块与区块内实体、玩家的 `tick`，玩家收包在 `Player#update` 里的 `interpretPacketQueue`，`Player.java:435`）→ `processTickEnd` → `viewablePackets.flush()`。调度器、实例 tick、出站 flush 都在单个调度线程上。
- 测试覆盖：`dispatcherThreads > 1` 只在 `ServerProcessDispatcherTest`（两个进程各 1 / 2 线程）与 `PassengerRemovalOwnershipTest`（2 线程）出现；全量测试从未在多线程下跑过。攻击面盘点「没测到的」第 3 条同样写了「多 tick 线程下的崩溃隔离」未测。
- 所有权断言：`assertOwnership()` 已接到 `Entity#setVelocity`、`addPassenger`、`removePassenger`、`remove` 与 `LivingEntity` 一处（`grep -rn 'assertOwnership()' src/main/java`），`setInstance` / `teleport` 仍未接（`docs/DESIGN.md` §28 风险行）。只在开断言或 `-Dsynaxis.acquirable-strict=true` 时生效（`AcquirableImpl.java:121`）。

### 4.2 `Acquirable` 跨线程访问的代价

- 同线程：`isOwned` 一次 `Thread.currentThread()` 比较，没有锁。
- 跨线程：`AcquirableImpl#enter`（第 139～156 行）先拿**全进程唯一的** `GLOBAL_LOCK`（第 20 行），再拿目标 `TickThread` 的锁。目标线程整个 tick 期间持有自己的锁，只在两个元素之间、且 `lock.hasQueuedThreads()` 时让出一次（`TickThread.java:78-82`）。所以一次跨线程 `sync` 最多等目标线程 tick 完**一个**元素，但所有跨线程访问被那把全局锁串行化：跨格交互越多，越接近单线程。
- 调用方自己是 `TickThread` 时，等全局锁期间会反复放开自己的锁（第 143～148 行）让别人进来，这是防死锁的做法，代价是本线程的元素在它等锁时可能被别的线程改。
- 可观测：每 tick 的获取等待总时长已经算在 `TickMonitor.acquisitionTimeMs`（`ServerProcessImpl.java:387-389`），但 soak `summary.json` 没有这个字段。

### 4.3 引擎侧接口补充（M3 未写到的）

| 项 | 内容 | 理由 |
| --- | --- | --- |
| 下标取模 | `ThreadDispatcherImpl.java:141` 由 `Math.abs(threadId) % threads.size()` 改为 `Math.floorMod(threadId, threads.size())` | `Math.abs(Integer.MIN_VALUE)` 仍是负数，得到负下标；`grid` 用格坐标 hash 当线程号，hash 取到 `MIN_VALUE` 并非不可能 |
| 多线程测试矩阵 | CI 加一个 job：`-Dsynaxis.dispatcher-threads=4 -Dsynaxis.thread-provider=grid -Dsynaxis.acquirable-strict=true` 跑引擎与 server-app 全量测试 | 这正是 `minestom-audit/07-architecture-review.md` 第 957 行「`DISPATCHER_THREADS=2` 下全量测试绿」那条验收，至今没做 |
| 跨格交互的推荐写法 | 插件 API 文档写明：跨格影响（攻击、交易）优先投递到目标实体的调度器（`Entity` 实现 `Schedulable`，`entity/Entity.java:125`），由目标所在线程下一 tick 执行；`Acquirable.sync` 只用于必须当 tick 读到对方状态的场合 | 投递不经过 `GLOBAL_LOCK` |
| 指标 | soak `summary.json` 新增 `ticks.acquire_ms`（每 tick `acquisitionTimeMs` 的分布）与每条 dispatcher 线程的忙碌占比 | 否则「500 人挤在 1 个分格」那一档（M3 §15）只能看到 tick 变慢，看不出是单格过载还是锁争用 |
| 串行段的上界 | `Instance#tick` 与调度器任务都在调度线程上串行，多线程加不快它们 | 插件每 tick 的全局逻辑（对局、AI 调度）是阿姆达尔定律里的串行部分，MMO 插件要按格或按实体拆 |

### 4.4 对单局 RTS 的影响

- RTS 的玩法逻辑整体是一个调度器任务：`RtsPlugin.java:91` `context.scheduler().scheduleTask(deployed::tick, TaskSchedule.nextTick(), TaskSchedule.tick(1))`，跑在调度线程上。寻路（≤ 2 ms/tick）、快照编码（≤ 5 ms/批）都在这里，开多少 dispatcher 线程都不会并行。
- 能并行的只有玩家与单位实体的 tick 和收包。收包路径 `GameChannel#receive`（`game-rts/.../GameChannel.java:116`）在玩家所属 tick 线程上执行；多线程下不同玩家的 `receive` 会并发，它碰到的共享对象有：寻路队列（`LinkedBlockingQueue`，线程安全）、`Match` 的 `unitTeams`（`ConcurrentHashMap`）、台账（③ 改完后是 MPSC 队列）、以及各个 `LogBudget` 实例（线程安全性本轮未审）。`GameSession.java:22` 的 Javadoc 写「everything … is read and written on the tick thread」，单数的「the tick thread」在多线程下要改成「该玩家所属的 tick 线程」。
- `grid` 下 512×512 的对局图是 32×32 区块 = 2×2 = 4 个格，即使开 12 线程也最多 4 条在忙，hash 取模碰撞时更少；玩家的镜头锚点跨格时玩家实体换线程。
- 实测不需要：50 连接 × 1,000 单位 tick P99 8.617 ms，至 4,000 单位 11.096 ms（`docs/research/soak-2026-09-22/report.md` 第 35 行），离 50 ms 预算还有 38.9 ms。
- 推荐：RTS 进程保持 `counter` + 1 线程（与 M3 默认一致）。切换条件：单局人数超过 200，或 tick P99 连续 5 分钟 > 25 ms（预算一半）；届时先测 `grid` + 4 线程在该地图上的收益，再决定。

## ⑤ 200～500 人真实压测

### 5.1 现有 harness 的缺口

| 缺口 | 证据 | 后果 |
| --- | --- | --- |
| 不回 `ChunkBatchReceived` | `grep -rn -i 'ChunkBatch' tools/` 无输出；服务端第一批之后等回执：`Player.java:208` `maxChunkBatchLead = 1`，`:872` `chunkBatchLead >= maxChunkBatchLead` 即返回 | 每连接只收到首批 9 个区块（`Player.java:206` `targetChunksPerTick = 9f`）。gate-1000 的 `engine_by_packet.ChunkDataPacket` = 450 条 / 50,800 字节（= 50 × 9），同期 `UnloadChunkPacket` 903,369 条 / 13,550,535 字节。§15 与 M3 §2 的区块带宽都没量过 |
| 发包端上限 | `soak.py` 单进程 2,880 包/秒、双进程 3,990 包/秒（`docs/research/soak-2026-09-18/report.md:123`） | 500 人各发 20 Hz 位置包就是 10,000 包/秒，Python 需要 ≥ 4 个进程，且双进程只提升到 1.39 倍 |
| 收包端上限 | 所有连接由一个 selector 循环驱动（`soak.py` 第 18～20 行）；50 连接时 harness CPU 最大 17.7～27.0%，对应每连接 581.6～1,025.4 个引擎包/秒（`soak-2026-09-22/report.md` 第 51、68 行） | 按线性外推 500 连接要 177%～270% 个核，单进程 Python 受 GIL 限制用不满一个核（外推，未测） |
| 采样与服务端同机 | `Sampler` 用 `jcmd` / `jstat` / `lsof` / `ps` 对服务端 pid 采样（`soak.py` 第 253～365 行） | 机器人与服务端分机时采样失效；同机时两者抢 CPU（9-22 那轮本来就有一个无关满核进程干扰，干扰大小未测） |
| 没有加密与正版验证 | 探针只走离线登录（`tools/protocol-probe/probe.py`），`Auth` 里没有 Mojang 路径 | 恢复加密后，AES/CFB8 在收发两侧的 CPU 代价无人量过 |
| 移动画像只有 RTS 镜头 | `play` 子命令按镜头 5 Hz 游走 | M3 要的步行 4.317 格/s、坐骑 10 格/s 两档不存在（`REQ-M3-014`，归 DESIGN-M3） |
| 同一出口 IP | 机器人都从 harness 主机连出 | ② 上线后会被 L1 / L2 拦住，必须有白名单或专用配置 |

### 5.2 harness 改造

推荐新写 Java 机器人，放在独立 Gradle 模块 `:loadtest`（不进引擎、不进发布物）：

- 编解码：依赖引擎的包类与 `:protocol`，与 `PacketFlood.java` 一样从本仓库注册表拿包 ID。代价是机器人与服务端共用编解码，编解码错误在压测里看不出来（`docs/TEST.md` §2「包 ID 口径」已登记同一局限）；这一面由 ① 的真客户端 R1～R8 兜住。
- 线程：每个机器人一条读虚拟线程，发送由一个共享的定时调度推进。`PacketFlood` 用「每连接一条发送线程 + 一条读取线程」跑到过 40,015 包/秒。
- 区块回执：收到 `ChunkBatchFinished` 回 `ChunkBatchReceived`；回报的 `targetChunksPerTick` 默认 9.0（与服务端初值一致），可用 `--chunks-per-tick` 改。原版客户端 `ChunkBatchSizeCalculator` 的算法本轮未读，要照原版回报时再补。
- 移动：`--profile rts|walk|ride`，`walk` / `ride` 按 M3 的 4.317 / 10 格/s；位置包 20 Hz。
- 加密：`--encryption on` 时机器人做完整的 RSA 交换与 AES/CFB8。机器人没有 Mojang 账号，所以服务端在压测时要把 sessionserver 地址指向本地假服务（一个返回固定 profile 的 HTTP 桩）。这要求鉴权实现把 sessionserver URL 做成配置项；该配置项的设计归鉴权那份设计，本文只提需求。
- 分机：`--bot-offset / --bot-count` 切分机器人，多台机器各跑一份，各自产出 `bots-<n>.json`，由 `merge` 子命令合并。服务端侧采样拆成独立命令 `sample --pid`，在服务端主机上跑，产出 `server.json`（tick 分布、`acquire_ms`、堆、RSS、线程、fd、`connection_gate` 计数）。
- 产物字段：沿用 `soak-2026-09-22` 的 `summary.json` 字段口径，新增 `inbound.chunks`（M3 `REQ-M3-014` 已定名）、`login_latency_ms` 按加密开关分栏、`kicks_by_reason`、`chunk_batch_rtt_ms`。
- `soak.py` 保留给 50 人以下的冒烟与攻击探针（`--attacks`），不再扩。

### 5.3 机器需求

| 档 | 服务端 | 机器人 | 网络 | 依据 |
| --- | --- | --- | --- | --- |
| 50 人（现有） | 本机 M4 Pro 12 核 / 24 GiB | 同机 | loopback | 现有报告 |
| 200 人 | 专用机 ≥ 8 核 / 16 GiB，堆上限 3 GiB | 同机也可跑，但要在报告里记 `load1`；推荐另一台 ≥ 4 核 | 步行约 109 Mbit/s、骑乘约 183 Mbit/s，千兆够 | M3 §2「分片出站」、M3 §15 RSS < 4 GiB |
| 500 人 | 专用机 ≥ 8 核 / 16 GiB（M3 让分片宿主把 `dispatcher-threads` 设为核数） | 必须另一台，≥ 8 核 / 16 GiB | 步行约 272 Mbit/s、骑乘约 458 Mbit/s，千兆够；超过 800 Mbit/s 时要重估（M3 §2） | 同上 |

机器人侧的 CPU 需求本轮没有数据，第一轮 200 人跑完后按实测补这一列。

### 5.4 验收判据与预算对照

| 档 | 场景 | 判据 | 对照 |
| --- | --- | --- | --- |
| A 基线复测 | 50 机器人，RTS 画像，1,000 单位，600 s，Java harness | tick P99 与 gate-1000 的 8.617 ms 相差 ≤ 20%；harness 本身不是瓶颈（机器人 CPU < 50%） | `docs/DESIGN-M2.md` §15 tick 预算、`docs/DESIGN.md` §15 入站 5,000 包/秒 |
| B 区块带宽 | 200 机器人，walk 与 ride 各 600 s | 每人收到的区块数 > 9；区块下行 KB/s/人与模型（步行 35、坐骑 82）偏差 ≤ ±20% | M3 §15「带宽」行（归 DESIGN-M3 定阈值，本文只提供测量） |
| C 200 人稳态 | 200 机器人，walk，30 min | tick P99 < 50 ms、超 50 ms 的 tick 为 0、TPS 20、RSS < 4 GiB | M3 §15「tick 预算」行 |
| D 500 人 | 500 机器人，walk，600 s；另一档 500 人挤在 1 个分格 | 两档分别记 tick P99 与每人出口；不达标则频道 `capacity` 降到 200～300 | M3 §15「tick 预算（正式档）」行 |
| E 重连风暴 | 500 机器人同一秒断开再同时重连，`max-joins-per-tick=5` | 30 s 内 500 人全部进 play；0 次 `synaxis:server-full`；tick P99 在风暴那一分钟 < 50 ms | 本文 ② L4 |
| F 加密开销 | C 档加 `--encryption on` 复跑 | 记服务端 CPU 与 tick P99 相对 C 档的差值；差值判据等首轮数据出来再定 | 鉴权设计 |
| G 闸门 | 单个攻击 IP 以 100 连接/秒开合 60 s，同时 200 机器人正常在线（机器人网段加白名单） | 服务端连接数不超过 L0 上限；机器人 0 掉线；拒绝日志 10 s 至多 1 行；台账 0 条来自攻击 IP 的会话事件 | 本文 ②、③ |
| H 台账 | C 档期间 | `ledger_dropped` 0 条；台账体积记入 `summary.json` | `docs/DESIGN-M2.md` §15「数据增长」（该行至今「未测」） |

`docs/DESIGN.md` §15 的「50 玩家」一行与 `docs/DESIGN-M2.md` §15 的「50 玩家、单局」一行不因 200～500 人档而改；200～500 人档的数字写进 M3（或本文将来并入的分册）。

## 6. 待拍板

| # | 问题 | 默认 | 切换条件与代价 |
| --- | --- | --- | --- |
| 1 | 真客户端 CI 的 PR 触发 | PR（路径过滤）+ main + 每日；前 20 次连续全绿后设为必需检查 | 私有仓库每月 Actions 分钟数超过预算，或 50 次运行里偶发失败 > 1 次时，PR 改为打标签 `needs-client-e2e` 才跑；代价是回归要到合并后才发现 |
| 2 | gametest 怎么拿到 `RtsClient` | 反射读 `SynaxisClientMod.RTS`，模组 jar 不改 | 阶段 6b 接上鼠标输入后改走 `TestInput` 真点击 |
| 3 | 每 IP 上限 | 并发 16、登录桶容量 3 / 每 2 s 回填 1、transfer 桶容量 10 / 每秒 2 | 首次公开内测里 `synaxis:login-throttled` 或 `too-many-connections` 被正常玩家触发（按 `connection_id` 查到对应账号在线）超过每天 5 次，把对应值翻倍；网吧集中的地区单独加白名单网段 |
| 4 | L0 超限直接关还是回 `Disconnect` | 直接关 | 如果运维需要从客户端侧区分「满了」与「网络断了」，改为回 status 响应里写满员；login 阶段的满员已由 L3 的 `synaxis:server-full` 覆盖 |
| 5 | 引擎默认值是否直接开闸门 | L0 1,024、L1 16、L2 开启；L3、L4 默认 0（不限），M2 进程行为除 L0～L2 外不变 | 如果要求 M2 进程完全不变，L0～L2 也默认关闭，只由分片宿主开启；代价是 M2 内测服对不受控来源仍无防护 |
| 6 | 台账写盘间隔与 fsync | 1,000 ms，不逐次 fsync | 台账被用来做计费或申诉证据时，改为每次写盘后 `force(false)`，先测代价 |
| 7 | 台账每日上限 | 1 GiB | 正常一天超过 100 MB 时，按实际量的 10 倍重设 |
| 8 | RTS 进程是否开多线程 | 不开（`counter` + 1） | 单局 > 200 人，或 tick P99 连续 5 分钟 > 25 ms |
| 9 | 压测 harness 语言 | Java 新模块 `:loadtest` | 如果不想新增模块，退路是 `soak.py` 多进程（≥ 4 进程）+ 回执，代价是加密那一档做不了（Python 标准库没有 AES） |
| 10 | 500 人档机器 | 服务端与机器人各一台 ≥ 8 核 / 16 GiB，千兆内网 | 只有本机时，500 人档只做 A～C，D～G 等第二台机器 |

## 7. 未核实事项与出处

未核实：

- GitHub Actions 私有仓库的免费分钟数与计费规则；§1.4 的月用量是按估计的推送次数算的。
- PR 只跑生产 jar 时约 4 分钟：按「两次启动 5 分 40 秒」折算，未实测；S2～S5 合并后在 CI 上的耗时未测。
- x86_64 上 libopenal 退出段错误是否复现（`client-spikes-2026-09/README.md` 第 63 行已写未测）。
- Velocity `login-ratelimit` 的默认值：文档页只写了语义，没写默认值。
- 缓冲后单次 `record` 的代价、500 人档 26 µs 线性外推的每秒 1.17 s、Python 收包端 177%～270% 核的外推，均未实测。
- Linux `vm.dirty_expire_centisecs` 在部署机上的取值。
- AES/CFB8 在 JDK 25 上的吞吐与对 tick 的影响，§5.4 F 档要量的就是它。
- 原版客户端 `ChunkBatchSizeCalculator` 的回报算法。
- ② 里 transfer 桶「容量 10 / 每秒 2」没有实测依据。
- `LogBudget` 与 `GameChannel` 在多 tick 线程下的线程安全性未审。

与其他文档的冲突（本文不改它们，只登记）：

- 用户 2026-09-23 拍板玩家数据用 PostgreSQL + Redis；`docs/DESIGN-M3.md` 的 `ADR-M3-009` 把 Redis 列为被否决的备选（「持久化默认不是同步落盘」），M3 §2 写「外部依赖只有 PostgreSQL」。需要 M3 那边对齐。
- `docs/DESIGN.md` §28 第一行技术债「改成带缓冲的写入就能与连接 churn 脱钩」与 §3.5 的分析不符，并入时改写。

出处：

- 本仓库源码（`main` `3085e0edab`）：`Auth.java:19`；`server-app/.../Main.java:96,112,141`；`network/packet/PacketVanilla.java:286,386`；`ServerProcessImpl.java:104,358-413,387-389`；`ServerConfig.java:185`；`network/socket/Server.java:74-117`；`listener/preplay/HandshakeListener.java:46-79`；`network/player/PlayerSocketConnection.java:126-131`；`network/ConnectionManager.java:444-459`；`entity/Player.java:206-208,435,842-872`；`entity/Entity.java:125`；`thread/ThreadProvider.java`；`thread/ThreadDispatcherImpl.java:140-141`；`thread/AcquirableImpl.java:20,121,139-163`；`thread/TickThread.java:52-59,78-82`；`server-app/.../EventLedger.java`；`HandshakeGate.java:249,356`；`AdmissionGate.java:101`；`PluginHost.java:129,142`；`game-rts/.../RtsPlugin.java:91`；`GameChannel.java:116`；`GameSession.java:22`；`client-fabric/src/gametest/.../SpikeClientGameTest.java`；`client-fabric/src/main/.../SynaxisClientMod.java:30,44,78`；`RtsClient.java:145-181`；`client-fabric/src/main/resources/assets/synaxis/lang/en_us.json`；`.github/workflows/client-gametest.yml`、`ci.yml`；`tools/loadtest/soak.py`、`PacketFlood.java`。
- 上游起点：`git show 16842f1954:src/main/java/net/minestom/server/ServerProcessImpl.java`（第 101 行）、`…/thread/ThreadProvider.java`、`…/ServerFlag.java`（第 20 行）。
- 本仓库调研与设计：`docs/DESIGN.md` §14、§15、§16、§21、§28；`docs/DESIGN-M2.md` §2、§14、§15、§17、§20、§21、§23、§28.2；`docs/DESIGN-M3.md` `REQ-M3-014`、`REQ-M3-015`、`API-M3-009`、`API-M3-010`、`ADR-M3-006`、`ADR-M3-009`、§2、§15（只读）；`docs/research/attack-surface-2026-09/README.md`；`docs/research/soak-2026-09-18/report.md`；`docs/research/soak-2026-09-22/report.md` 与 `runs/gate-1000/summary.json`；`docs/research/client-spikes-2026-09/README.md`；`docs/research/large-world-2026-09/README.md`；`docs/research/minestom-audit/07-architecture-review.md`；`docs/research/01-bukkit-spigot-paper.md`；`docs/research/05-proxy-network.md`；`docs/TEST.md`。
- 外部：https://docs.papermc.io/paper/reference/bukkit-configuration/（`connection-throttle` 4000）；https://docs.papermc.io/paper/reference/global-configuration/（`max-joins-per-tick` 5，`packet-limiter` 7 s / 500 / KICK）；https://docs.papermc.io/velocity/configuration/（`login-ratelimit` 语义）；https://minestom.net/docs/thread-architecture/acquirable-api/inside-the-api（默认单线程、跨线程获取会阻塞）。以上四页于 2026-09-23 读取。
