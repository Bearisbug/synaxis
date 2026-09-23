# 生产可用差距清单（对照 Paper，2026-09-23）

| 字段 | 值 |
| --- | --- |
| 基线 | `main` `3085e0edab` |
| 范围 | 身份与安全、持久化与运维、网络协议、性能与规模、面向客户端与插件的 API 五个维度，逐条对照 Paper 26.2 |
| 用户已拍板（2026-09-23） | 恢复加密与 Mojang 正版验证（每分片自己验证，不用 Velocity，跨大区 Transfer 后重新验证）；玩家数据用 PostgreSQL + Redis；真客户端端到端回归常驻 CI；连接数上限、事件台账缓冲写入、tick 多线程；200～500 人真实压测 |
| 不在本文范围 | 世界存储与区块，由 `docs/DESIGN-M3.md` 负责，本文只在 §4 标「归 DESIGN-M3」 |
| 同目录设计草案 | `design-auth.md`（加密与正版验证）、`design-persistence.md`（PostgreSQL + Redis）、`design-ops-scale.md`（CI、连接闸门、台账、tick 多线程、压测） |
| 证据路径约定 | 未写前缀的源码路径都在 `src/main/java/io/github/bearisbug/synaxis/` 下；`server-app/.../` 指 `server-app/src/main/java/io/github/bearisbug/synaxis/app/`；Paper 配置出处 `docs.papermc.io/paper/reference/` 下的 `server-properties`、`bukkit-configuration`、`spigot-configuration`、`global-configuration`、`world-configuration` 五页，2026-09-23 读取 |

## 1. 结论

离「内测可开服」还差 5 项开服阻断（G01～G05）和 22 项内测前必需（G06～G27），不含 DESIGN-M3 负责的世界存储与区块，合计 **88～117 人日**：开服阻断 22.5～29.5 人日，内测前必需 65.5～87.5 人日。正式运营前另有 11 项（G28～G38），20.5～25.5 人日。

开服阻断的 5 项是：正版验证与加密（已删，要重写）、连接闸门（accept 循环不计数）、玩家数据持久化（仓库里没有一行存储代码）、停机顺序（插件先于玩家断线被禁用，停机时在线玩家的数据会漏存）、聊天默认全进程广播且不限频（500 人分片上一条输入放大成 500 条输出）。关键路径是 G02 → G01 → G18 → G03：正版验证对公网开放前必须先有每 IP 登录令牌桶，持久化依赖异步调度器。

## 2. 总表

严重度四档：开服阻断 > 内测前必需 > 正式运营前 > 可选。「已拍板」指用户 2026-09-23 已定要做。编号只在本文内有效，勾选时引用编号即可。

### 2.1 开服阻断

| 编号 | 能力 | Paper | Synaxis 现状 | 证据 | 工作量（人日） | 建议 |
| --- | --- | --- | --- | --- | --- | --- |
| G01 | 正版验证（online mode）与 AES-CFB8 加密 | `online-mode` 默认 true；登录发加密请求 → 客户端回 EncryptionResponse → 服务端解 RSA 共享密钥开 AES-CFB8 → 调 sessionserver `hasJoined`（server-properties 页） | 已删 | `Auth.java:19-77` 只有 `Offline` / `Velocity` / `Bungee`；`server-app/.../Main.java:96` `new Auth.Offline()`；`network/packet/PacketVanilla.java:386` login `minecraft:hello` 是 `ReservedPacket`；`listener/manager/PacketListenerManager.java:129-131` 登录态没有加密响应监听器；`grep -rn "AES\|CFB8\|setEncryptionKey" src/main/java` 零命中；删除提交 `5c20d1724e`（16 文件，-559 行）与 `87901a7bfa`（`MojangUtils.authenticateSession`） | 6～8 | **做（已拍板）**。按 `design-auth.md` 重写，不整段回滚：上游有 5 处缺陷，其中密钥交换失败时断线包以明文发出，客户端读到乱码（`design-auth.md` §1.3）。新增 `Auth.Online(keyPair, sessionServer)`，sessionserver 地址可配，CI 与压测指向本地假 sessionserver。sessionserver 故障时拒绝登录并重试一次。与 G06、G07 同一个 PR |
| G02 | 连接闸门：全局连接上限、每 IP 并发与登录节流、每 tick 入场上限、分片人数上限 | `max-players` 默认 20；bukkit.yml `connection-throttle` 默认 4000 ms；paper-global `misc.max-joins-per-tick` 默认 5 | 缺失 | `network/socket/Server.java:82-117` accept 循环不计数，fd 用尽时 `:85` 抛 `IOException`，被 `suppressConnectionAcceptErrors=true`（`ServerConfig.java:196`）吞掉后空转；`ServerConfig` 无上限字段；`network/StatusResponse.java:26-28` max 写成 `online + 1`；`network/ConnectionManager.java:445,453` 两处 `drain` 无上限；50 条连接同秒进场时首个 tick 114.4 ms（`docs/research/soak-2026-09-18/report.md:231-232`） | 3～4 | **做（已拍板）**。按 `design-ops-scale.md` ② 四层：全局套接字上限 1,024，超限直接关 socket；每 IP 并发 16 + 登录令牌桶（容量 3、每 2 s 回填 1），超限回 `synaxis:login-throttled` / `synaxis:too-many-connections`；每 tick 入场 5 人，排队不踢；分片容量 500，超限回 `synaxis:server-full`，并写进 status 的 max。全部检查排在 Mojang 验证之前，被拒连接不花 RSA 解密和 sessionserver 请求。fd 耗尽时 accept 退避 100 ms |
| G03 | 玩家数据持久化（PostgreSQL + Redis）与插件存储 API | 每玩家 `world/playerdata/<uuid>.dat`；bukkit.yml `ticks-per.autosave` 6000 tick；paper-global `player-auto-save.max-per-tick` 按 tick 分摊 | 缺失 | `grep -rn -i "PlayerData\|autosave\|jdbc\|database\|postgres\|redis"` 主源码只命中 `Chunk.java` 一条无关注释；`gradle/libs.versions.toml` 无 JDBC / Redis 依赖；`plugin-api/.../PluginContext.java` 无存储接口；`docs/DESIGN-M2.md:1352,1381`「M2 不落盘任何持久数据」 | 8～12 | **做（已拍板）**。按 `design-persistence.md`：引擎不动，宿主 `server-app` 实现，`plugin-api` 只暴露按数据段读写的 `PlayerDataStore`。PostgreSQL 是唯一权威存储（写权租约、版本号、全部数据），Redis（默认 Valkey 8）只放在线位置、跨分片消息、登录限流这类可丢数据，Redis 整体宕机时照常登录。60 s 自动保存，交易等关键操作先落库后生效。与 `DESIGN-M3.md` `REQ-M3-018` 有三处分歧，见 §7 Q1、Q2 |
| G04 | 优雅停机：停机前保存玩家、排空 | `MinecraftServer#stopServer` 先 `playerList.saveAll()`，再踢人，再存世界（PaperMC 仓库 `paper-server/patches/sources/net/minecraft/server/MinecraftServer.java.patch`） | 部分 | 顺序是 `joinThread(tick)` → `scheduler.shutdown()`（插件 onDisable 在这里跑，`server-app/.../Main.java:141`）→ `connection.shutdown()` 踢人 → daemon 线程跑最后一个 tick 才派发 `PlayerDisconnectEvent`（`ServerProcessImpl.java:304-317`）；每线程 join 5 s（`ServerProcessImpl.java:46`） | 2.5 | **做**。顺序改为：关登录门 → 广播倒计时 → 批量保存全部在线玩家（总预算 30 s，超时的记 ERROR、靠租约过期兜底）→ 踢人 → 插件 onDisable → 停 tick。插件拿「停机前保存」钩子，不依赖断线事件。部署侧 systemd `TimeoutStopSec` 或 Kubernetes `terminationGracePeriodSeconds` ≥ 60 s |
| G05 | 聊天广播范围与限频 | `AsyncChatEvent` 的 viewers 可改；paper-global `spam-limiter` | 部分 | `listener/ChatMessageListener.java` 收件人是 `process().connection().getOnlinePlayers()`，整个进程，不分实例和频道；`grep -rniE 'chat.*(rate\|limit\|flood\|spam)' src/main/java` 零命中；server-app、game-rts 无 `PlayerChatEvent` 监听器；`docs/research/attack-surface-2026-09/README.md` 缺口 G5：50 人时出站上界 12.8 MB/s | 3 | **做**。引擎默认不广播，收件人由插件决定（频道、同实例、私聊）；每连接 1 条/秒令牌桶；`JoinGamePacket` 的 `enforcesSecureChat`（`entity/Player.java:355-357` 写死 true）改为 false，聊天一律以系统消息转发，用真客户端确认没有「聊天无法验证」提示。跨大区世界频道经 Redis pub/sub 属 MMO 社交系统，另计 5～8 人日 |

### 2.2 内测前必需

| 编号 | 能力 | Paper | Synaxis 现状 | 证据 | 工作量（人日） | 建议 |
| --- | --- | --- | --- | --- | --- | --- |
| G06 | 皮肤：`hasJoined` 返回的签名 textures 透传 | 正版模式下 `hasJoined` 带签名 `textures` property，原样进 PlayerInfo | 部分 | 消费端在：`entity/Player.java:377-386` 读 textures 并发 `PlayerSkinInitEvent`；来源缺：`listener/preplay/LoginListener.java:80` 离线档案不带 property，所有玩家显示默认皮肤；`entity/PlayerSkin.java:30,56` 经 `MojangUtils` 同步发 HTTP | 0.5 | **做**，随 G01。登录路径不要另调 `PlayerSkin.fromUsername`：多吃 Mojang 限流额度，且在 tick 线程上会阻塞 |
| G07 | 正版模式下同 UUID 重复登录踢旧会话 | 原版踢旧会话，翻译键 `multiplayer.disconnect.duplicate_login`（Paper 实现未核实） | 部分 | `listener/preplay/LoginListener.java:140-148` 拒后来者，注释说明理由是离线 UUID 由用户名派生，踢旧会让任何人能踢任何人 | 0.5 | **做**。按 Auth 模式分支：Online 踢旧，Offline 保持拒新；改 `DuplicateUuidLoginIntegrationTest` 三条。跨分片同账号互斥由 G03 的会话租约与 Redis 顶号通知处理 |
| G08 | 白名单与封禁（UUID / IP，带理由与到期） | `white-list`、`enforce-whitelist` 默认 false；`whitelist.json`、`banned-players.json`、`banned-ips.json` 本地文件，跨服共享靠插件 | 缺失 | `grep -rn -i "whitelist\|allowlist\|banned\|banlist"` 在 src/main、server-app、plugin-api、game-rts、example-plugin、client-fabric 零命中；`plugin-api/.../AdmissionRequest.java:13` 只带 `connectionId, username, modVersion, schemaVersion`，没有 IP | 3.5～5 | **做**。存 PostgreSQL 全大区共享，进程内 30 s 缓存，变更经 Redis pub/sub 让在线的被封玩家即时下线。判定排在 Mojang 验证之后、`AdmissionProvider` 之前（放 `AsyncPlayerPreLoginEvent`）。只封 UUID 和 IP，不按名字封：正版名字可改。依赖 G01、G03 |
| G09 | 权限与 GM 角色 | `op-permission-level` 默认 4；`ops.json`；权限节点靠 LuckPerms（`docs/research/01-bukkit-spigot-paper.md:452`），Vault 已停更（同文件 :421） | 部分 | 有：`entity/Player.java:263` `permissionLevel`、`:2137-2145` getter/setter（0～4）；没有：持久化（重登回 0）、权限节点、角色；`command/` 包只有 `Command.java:96`、`CommandCondition.java:13` 两处注释提到 permission；plugin-api 无权限接口 | 2.5～3 | **做最小自研版**。PostgreSQL 角色表（玩家 → 角色 → 权限串如 `gm.ban`），Redis 缓存；plugin-api 加 `has(player, node)`；`permissionLevel` 只控制客户端调试界面与补全显示，不作授权依据；GM 命令写审计行进事件台账。不引 LuckPerms（绑定 Bukkit/Velocity/Sponge 平台接口），不做 Vault 兼容 |
| G10 | 控制台命令与插件命令注册 | Brigadier；插件经 `LifecycleEvents.COMMANDS` 注册（`01-bukkit-spigot-paper.md:298`） | 部分 | `server-app/.../Main.java:227-237` `readConsole` 对非 `stop` 输入直接 `continue`；`plugin-api/.../PluginContext.java:26-77` 没有 `commands()`，插件只能绕到 `process().command()`，而 `docs/PLUGINS.md` §7 写明 `process()` 不作兼容承诺；引擎 `command/` 包 86 个文件，框架齐全；`docs/DESIGN.md:667` `CommandParserImpl` 每次异常写一行带栈 ERROR、未限流 | 4～5 | **做**。控制台输入交给 `CommandManager` + `ConsoleSender`；`PluginContext.commands()` 按 plugin id 命名空间、禁用时注销、异常走 `LogBudget`；内置 list、kick、broadcast、save、stop、tps/mspt、profile 共 7 条；命令条件接 G09。控制台补全与高亮（JLine）不做 |
| G11 | 入站包速率闸与补全限频 | paper-global `packet-limiter.all-packets` 500 包 / 7.0 s、KICK，可按包类型 overrides；`spam-limiter.tab-spam-limit` 500 | 部分 | 有：`entity/Player.java:294` 入站队列 1,024，满即 `synaxis:protocol-error`，每 tick 取 50 包；`docs/DESIGN.md:394` §15 写「每秒 > 500 包断线」，`:663` §28 登记为未实现（600 包/秒 × 60 s、800 包/秒 × 90 s 均不掉线）；`listener/TabCompleteListener.java` 只按 256 字符丢弃，补全在 tick 线程解析 | 3～4 | **做**。读线程入队处加滑动窗口计数，全部包 + 按包类型覆盖表，动作 DROP / KICK；解压字节/秒上限；补全每连接 20 次/秒。初值取 Paper 的 500 包 / 7 s，200 人压测后改为入站 P99 × 2。盘点里「soak 实测每玩家 1,025 包/秒，照抄 Paper 会误踢」一说不成立：那是出站引擎包（`soak-2026-09-22/report.md:51`），不是入站 |
| G12 | tick 卡死看门狗 | paper-global `watchdog.early-warning-delay` 10000 ms、`early-warning-every` 5000 ms；spigot.yml `settings.timeout-time` 60 s、`restart-on-crash` true | 缺失 | `grep -rn -i watchdog` 只命中登录超时（`network/socket/Server.java:103`、`network/player/PlayerSocketConnection.java:554-571`）；`TickSchedulerThread.java:34-51`（在包根，不在 `thread/`）只 catch 异常并在落后 `maxTickCatchUp` 个 tick 后重置基准 | 2 | **做**，阈值照 Paper：卡住 10 s 打印全部 TickThread 与调度线程的栈，之后每 5 s 一次；60 s 判死，先尝试落盘停机，15 s 未完成就 `Runtime.halt` 非零退出，由 systemd 或容器重启。不做 `restart-script` |
| G13 | 崩溃报告与未捕获异常 | 原版 CrashReport；Paper 给服务端线程设 UncaughtExceptionHandler（`MinecraftServer.java.patch`） | 部分 | `ExceptionManager.java:50` 默认 `Throwable::printStackTrace`，绕过 logback、行内无 tick 号与 connection_id；OOM 分支 `:26-30` 同样 printStackTrace 后走完整 stop；server-app 未调 `setExceptionHandler`，`grep -rn UncaughtExceptionHandler` 零命中 | 1.5 | **做**。走 SLF4J 并带上下文、保留 `LogBudget` 限流；致命路径写 `crash-reports/<时间戳>.txt`（线程转储、生效配置、插件列表、git head）；启动参数固定加 `-XX:+HeapDumpOnOutOfMemoryError -XX:+ExitOnOutOfMemoryError`，OOM 时不再走完整 stop |
| G14 | 日志文件与轮转 | log4j2：`logs/latest.log`，按天 + 启动滚动，gzip，文件与控制台都经 Async 追加器（PaperMC 仓库 `paper-server/src/main/resources/log4j2.xml`） | 部分 | `server-app/src/main/resources/logback.xml:3-10` 只有一个 STDOUT `ConsoleAppender` | 1 | **做**。`RollingFileAppender` + `SizeAndTimeBasedRollingPolicy`，每天或单文件 256 MB 滚动，gzip，保留 14 天，总量上限 10 GB；外包 `AsyncAppender`（`neverBlock=true`，队列 8,192）；容器部署保留 stdout，两者由启动参数二选一 |
| G15 | 事件台账缓冲写入与保留 | 无对应 | 部分 | `server-app/.../EventLedger.java:72-75` 每条事件一次 `Files.createDirectories` + `Files.writeString(CREATE, APPEND)`；`:21-31` 自注 100 次登录写 200 行、30,800 字节；`docs/DESIGN.md` §28 实测 26 µs/条；按天分文件，无压缩、无保留期、不分分片 | 2 | **做（已拍板）**。按 `design-ops-scale.md` ③：65,536 行有界队列 + 单写线程，1 s 或 64 KiB 刷一次，满即丢并计数；按天 gzip，保留 30 天，文件名带 shard id；每日上限 1 GiB。崩溃最多丢 1 s。`DESIGN.md` §28「缓冲写入即可与连接 churn 脱钩」要改写：缓冲只省系统调用，字节量靠 G02 在 configuration 之前拒掉连接 |
| G16 | 运行指标导出（TPS / MSPT / 在线 / 连接 / GC / 数据库延迟） | Paper 无内置 Prometheus 导出（社区插件方案未核实）；spark 提供游戏内视图 | 缺失 | `grep -rn -i "prometheus\|micrometer"` 零命中；`TickMonitor` 只在 `server-app/.../HandshakeGate.java:180` 用来计 tick 数；`docs/DESIGN.md:596-599` 定义了 3 个 SLI，注明 M1 不接外部监控；审计 `docs/research/minestom-audit/04-threading-events.md:819` P3-3 指出 `acquisitionTime` 语义有问题 | 3 | **做，赶在 G26 压测之前**。Micrometer + Prometheus registry（新依赖），JDK `HttpServer` 在内网端口暴露 `/metrics`；MSPT p50/p95/p99 按 TickThread 分别导出；在线人数、各阶段连接数、入站/出站包数与字节、实体与区块数、GC 与堆、数据库连接池与语句延迟。P3-3 修掉之前不导出 `acquisitionTime` |
| G17 | tick 多线程：引擎侧正确性与插件线程契约 | Paper 单主线程；Folia 按 region 并行，插件须声明 `folia-supported` 并改用区域调度器（`docs/research/02-performance-forks-folia.md:73-77`） | 部分 | `ServerProcessImpl.java:104` 写死 `ThreadProvider.counter()`；`ServerConfig.java:185` `dispatcherThreads = 1`，宿主从未设置；`thread/ThreadDispatcherImpl.java:141` `Math.abs(id) % n` 对 `Integer.MIN_VALUE` 得负下标；`thread/AcquirableImpl.java:20` 跨线程获取全走一把全局锁；`docs/DESIGN.md` §28 登记 `setInstance` / `teleport` 三处 Acquirable 未接线；全量测试从未在 `dispatcherThreads > 1` 下跑过 | 6～10 | **做（已拍板）**。空间分格 `ThreadProvider.grid`（16×16 区块一格）本身归 `DESIGN-M3.md` `API-M3-009`，见 §4。这里做：改 `Math.floorMod`；三处接线；全量测试加一轮 4 线程；`plugin-api` 与 `docs/PLUGINS.md` 写死「实体回调在实体所属 tick 线程、跨实体访问必须经 Acquirable」。RTS 单局不开多线程。线程数见 §7 Q6 |
| G18 | 插件调度器：异步执行与按实例/实体调度 | `BukkitScheduler` 同步/异步；Folia 的 Global/Region/Entity/Async 四种调度器（docs.papermc.io/paper/dev/scheduler） | 部分 | `plugin-api/.../PluginScheduler.java` 只有 `scheduleTask(task, delay, repeat)`；`server-app/.../PluginSchedulerImpl.java` 固定提交到全局 Scheduler；`timer/ExecutionType.java` 只有 `TICK_START`、`TICK_END` | 2.5 | **做**，G03 的前置。`runAsync`（虚拟线程，插件禁用时中断）+ `runOn(Schedulable)` 回到实例或实体所属线程。持久化的标准路径是「异步查库 → 结果回实体线程应用」 |
| G19 | 插件事件 API：优先级、忽略已取消、实例作用域 | 六档优先级 LOWEST～MONITOR；`@EventHandler(ignoreCancelled = true)`（docs.papermc.io/paper/dev/event-listeners） | 部分 | `plugin-api/.../PluginEvents.java` 只有 `addListener(Class, Consumer)`；`server-app/.../PluginEventsImpl.java` 全挂在同一个 `plugin/<id>` 子节点；引擎 `event/EventNode.java:227-230` 已有 `getPriority` / `setPriority`，宿主未暴露 | 1.5 | **做**。MMO 拆成任务、公会、聊天、经济多个插件后，同一事件多方处理必须能排顺序；按实例作用域的监听在多线程下才能在实例所属线程触发 |
| G20 | 按实体的追踪范围（含 Y 轴） | spigot.yml `entity-tracking-range` players 128、animals 96、monsters 96、misc 96、display 128、other 64；paper-world `tracking-range-y` | 部分 | `ServerConfig.java:183` `entityViewDistance = 5`（区块、方形），server-app 设 8；`instance/Instance.java:162` 构造时 `new EntityTrackerImpl(config.entityViewDistance())` 固化；全进程一个值，无 Y 轴 | 4～6 | **做**。实体类型默认值 + 每实体覆盖，类别由插件定（例：玩家 64 格、掉落物 24 格），不照搬 Spigot 的六类枚举 |
| G21 | 每玩家可见实体数上限 | Paper 无此配置项（三页配置文档未见对应键，源码未核实） | 缺失 | `entity/EntityView.java:270-292` 追踪范围内全部实体加入 viewer；`grep -rn "maxViewers\|viewerLimit"` 零命中；game-rts `Units.java:342` 只裁插件自己的 `S2C_UNIT_STATE`（512 条）；soak 4,000 单位档 `EntityPositionSyncPacket` 占引擎字节 50.7%（`soak-2026-09-22/report.md:132`） | 6～9 | **做**。默认每玩家 100 个非必显实体，超出按距离裁剪，插件可标必显（队友、目标、Boss）。按 soak 平均 31.7 B/包推算，200 人同格时每玩家约 126 KB/s、合计约 25 MB/s，这是推算不是实测；压测证明 200 人同格低于 100 Mbit/s 时再放宽 |
| G22 | 出站带宽：同步频率、每连接积压上限 | 原版按实体类型的 updateInterval（具体值未核实）；Paper 的 packet-limiter 只管入站 | 部分 | `network/player/PlayerSocketConnection.java:97` 出站队列 `mpscUnboundedArrayQueue`，无界，上界只靠 keep-alive 15 s；`ServerConfig.java:184` `entitySynchronizationTicks = 20`；没有每玩家出站字节预算 | 6～9 | **做**。每连接出站积压上限 4 MiB，超限先丢可合并的位置包，再断线；同步间隔与移动包频率可配，默认值改动前先用 soak 的 `EntityPositionSync` 字节占比验证收益 |
| G23 | 区块下发的服务端硬上限 | paper-global `chunk-loading-basic.player-max-chunk-send-rate` 75 区块/s | 部分 | `entity/Player.java:842-851` 只按客户端 `ChunkBatchReceived` 回报自适应；`ServerConfig.java:207-208` 上限 `maxChunksPerTick` 64（20 TPS 下 1,280 区块/s）；改过的客户端可把速率抬到上限 | 1 | **做**。每玩家硬上限默认 4 区块/tick（80/s，与 Paper 同量级），压测出站带宽后再调。区块内容、加载与生成速率归 DESIGN-M3 |
| G24 | Tab 玩家列表的 O(N²) 发包 | 原版 `PlayerList#tick` 每 600 tick 批量刷新延迟（未在本仓库核实） | 部分 | `entity/Player.java:388-394` 新人对每个在线玩家单独发一个 AddPlayer，500 人时收 499 个包；`:2372-2376` 每次 keep-alive 向全进程广播 `UPDATE_LATENCY`，`keepAliveDelay = 10,000 ms`，500 人时 500 × 500 ÷ 10 = 25,000 包/秒 | 2 | **做**。进服合并为一个多条目 `PlayerInfoUpdatePacket`；延迟每 30 s 批量一包；可见集合由插件设（同频道、好友）。改用模组社交面板时也保留 PlayerInfo（皮肤渲染依赖），只把 listed 设为 false |
| G25 | JVM 与 GC 默认参数 | Aikar flags：G1、`-Xms10G -Xmx10G`、`MaxGCPauseMillis=200`、`AlwaysPreTouch`（docs.papermc.io/paper/aikars-flags） | 缺失 | `grep "ZGC\|UseG1\|Xmx\|jvmArgs\|MaxGCPause\|AlwaysPreTouch"` 在构建文件、tools、`docs/DESIGN*.md`、CLAUDE.md 零命中；soak 三档强制 GC 后存活堆 43.0 / 51.1 / 64.1 MiB（`soak-2026-09-22/report.md:114-116`） | 2～3 | **做**。默认 G1、`-Xms4G -Xmx4G`、`-XX:+AlwaysPreTouch`、`-XX:MaxGCPauseMillis=50`（tick 预算 50 ms，Aikar 的 200 ms 偏大）。堆上限要超过 8 GiB，或压测中 GC 暂停 P99 > 10 ms 时改 JDK 25 分代 ZGC，代价是吞吐与 RSS（幅度未测） |
| G26 | 200～500 人压测手段 | 本仓库调研建议用 SoulFire 跑真实客户端、看 p95 MSPT（`02-performance-forks-folia.md:366`）；Folia 2023-06-18 单机 1,000 人（同文件 :101） | 部分 | `tools/loadtest/soak.py` 单 selector 循环（`soak-2026-09-18/report.md:21`），跑过的最大规模 64 连接；不回 `ChunkBatchReceived`，gate-1000 那轮 50 条连接 600 s 共收到 450 个 `ChunkDataPacket`（`docs/research/large-world-2026-09/README.md:53`）；50 连接三档 tick P99 8.617 / 9.698 / 11.096 ms（`soak-2026-09-22/report.md:35`），harness CPU 最大 27.0%（`:68`）；RTS 画像不含玩家两两可见 | 8～12 | **做（已拍板）**。按 `design-ops-scale.md` ⑤ 新建 Java 模块 `:loadtest`：回区块回执、可分机运行、可选加密（假 sessionserver `allow-all` 模式）；画像为主城 200 人同格、分散 500 人、传送涌入。门槛：两种画像 tick P99 < 50 ms，每玩家出站 P99 有数。G16、G17、G21 落地后各跑一次。50 人数据不能外推到 500 人：玩家两两追踪随人数平方增长 |
| G27 | 真客户端端到端回归常驻 CI | 无对应 | 部分 | `.github/workflows/client-gametest.yml` 只在 push `client-gametest-ci` 分支或手动触发时跑；`SpikeClientGameTest` 不断言，S1 断线时 CI 仍是绿的（run 35813814226，`docs/research/client-spikes-2026-09/README.md:38-40`） | 3～4（本文估，`design-ops-scale.md` 未给人日） | **做（已拍板）**。按 `design-ops-scale.md` ①：R1～R8 八条断言，判定读 `report.txt` 与服务端台账，退出码只记录不判定；PR（路径过滤）+ push main + 每日一次；连续 20 次全绿后设为必需检查。G01 落地后切到 `design-auth.md` §2.2 的 B 档（完整正版路径 + 假 sessionserver） |

### 2.3 正式运营前

| 编号 | 能力 | Paper | Synaxis 现状 | 证据 | 工作量（人日） | 建议 |
| --- | --- | --- | --- | --- | --- | --- |
| G28 | 跨大区 Transfer 与签名 cookie | `accept-transfers` 默认 false；`Player#isTransferred()`、`Player#retrieveCookie` 存在（jd.papermc.io 1.21.11）；`storeCookie`、`transfer(host, port)` 未核实 | 部分 | `ServerConfig.java:190` `acceptTransfers = false`；`listener/preplay/HandshakeListener.java:53-59` 未开时用原版键 `multiplayer.disconnect.transfers_disabled` 踢；`network/player/PlayerConnection.java:324,328` `storeCookie` / `fetchCookie` 在；plugin-api `grep -i "transfer\|cookie"` 零命中；`grep -rn -i hmac src/main/java` 只命中 `Auth.java:25` | 3.5～5 | **做，排在 G01、G03 之后**。plugin-api 暴露 transfer、wasTransferred、store/fetch cookie；cookie 只放路由提示，HMAC-SHA256 + 过期时间 + 绑定 UUID + 一次性 nonce（Redis 记已用）；转入连接照常走 hello + `hasJoined`，不因 wasTransferred 跳过鉴权；每玩家转服冷却 5 s。内测开两个及以上大区时提前到内测前 |
| G29 | 管理接口（Server Management Protocol 兼容子集） | 原版 1.21.9 起 SMP：WebSocket 上的 JSON-RPC 2.0，`management-server-enabled` 默认 false，Bearer 鉴权（minecraft.wiki/w/Server_Management_Protocol；`docs/research/00-总报告.md:21`） | 缺失 | 整词 `grep -rn -i -w "rcon\|jsonrpc\|websocket"` 零命中；依赖里没有 HTTP / WebSocket 服务端 | 5 | **做**。SMP 子集（players 列表与踢人、server status/save/stop、bans、进出通知）+ `synaxis:` 扩展（drain、分片状态、广播、转服）；默认只绑内网；新依赖 Java-WebSocket 或 Jetty。RCON 不做 |
| G30 | 配置文件 | bukkit.yml、spigot.yml、paper-global.yml、paper-world-defaults.yml 分层 | 部分 | `ServerConfig.java:88-120` `fromSystemProperties()` 只读 `-Dsynaxis.*`；视距等值在进程创建时固化（`server-app/.../Main.java:53-57` 注释） | 1.5 | **做配置文件，不做热重载**。`synaxis.properties`，键名与 `synaxis.*` 一一对应，优先级 `-D` > 文件 > 默认，未知键拒绝启动。分片更新走滚动重启 + 转服 |
| G31 | 性能剖析命令（JFR） | 1.21 起内置 spark，Timings 1.21.3 起为空操作（`01-bukkit-spigot-paper.md:357-366`） | 部分 | `monitoring/EventsJFR.java` 有 10 处 `@Name`，含 `synaxis.ServerTickTime`；soak 用 jcmd 开 JFR（`tools/loadtest/soak.py:363-368`）；游戏内与控制台不能触发 | 1 | **做 JFR 命令**：`profile <秒数>`（默认 60 s）+ 对应 SMP 方法，产出 .jfr 路径，runbook 写 JMC / `jfr print` 用法。不接 spark：GPL-3.0，与 Apache-2.0 同包发行有许可证问题 |
| G32 | 数据库备份与恢复演练 | Paper 不自带备份 | 缺失 | `grep -rni backup` 只命中 `entity/Player.java:2224` 一条注释；`DESIGN-M3.md` 只写了迁移回退 SQL | 2 | **做，放运维层**。PostgreSQL WAL 归档 + 每日基础备份（pgBackRest 或 pg_basebackup），恢复点目标 ≤ 5 分钟；做一次恢复演练写成 runbook。WAL 归档在内测开放当天就开 |
| G33 | 代理转发与 PROXY protocol 启动自检 | paper-global `proxies.velocity.secret` 须与代理一致；`proxies.proxy-protocol` 默认 false | 部分 | `Auth.java:71-72` Bungee 的 tokens 为 null 时 `validToken` 恒真，握手地址可伪造 UUID / IP / properties；`network/player/PlayerSocketConnection.java:126-137` 对任何来源解析 PROXY 头部，没有可信来源名单 | 0.5～1 | **做启动自检**：生产只允许 `Auth.Online`；开 PROXY protocol 时必须配可信网段并开 `proxyProtocolRequired`，非名单来源带头部即断开 |
| G34 | 服务器列表 ping（MOTD、图标、真实人数上限） | `motd`、`enable-status`、`hide-online-players`；`PaperServerListPingEvent` 可改 MOTD、人数、图标、玩家样本 | 部分 | `network/StatusResponse.java:14` `DESCRIPTION` 写死 `"synaxis"`，`:21-28` 无 favicon、无 sample，max = online + 1 | 1.5 | **做**。MOTD、64×64 图标、max 读配置 + 可改事件。max 必须等于真实上限，否则负载均衡读 status 会误判。附带发现：`network/packet/client/status/LegacyServerListPingPacket.java` 未注册，是死代码，本表不处理 |
| G35 | 压缩阈值与级别可配 | `network-compression-threshold` 默认 256 | 部分 | `ServerProcessImpl.java:77` 阈值 256，只能在 start 前经 setter 改；`network/NetworkBufferImpl.java:301` `Deflater::new` 即默认级别；`ServerConfig` 无对应字段 | 0.5～1 | **做**。阈值与级别做成 `synaxis.*` 参数，默认 256 / 级别 6；soak 快照包压缩后 2,547 B/s，压缩前 4,420 B/s（`soak-2026-09-22/report.md:89`），省 42%，不要关 |
| G36 | 每玩家视距与 JoinGame 视距不一致 | `view-distance` 默认 10；`Player#setViewDistance` 存在 | 部分 | `entity/Player.java:2615-2617` 有效视距 = min(客户端, 实例) + 1，服务端不能按玩家下调；`:349` `JoinGamePacket` 发的是 `config().chunkViewDistance()` 而不是实例视距 | 1～1.5 | **做**。每玩家服务端视距上限，变更时发 UpdateViewDistance；JoinGame 改用实例视距；plugin-api 暴露。主城拥挤时按区域降视距 |
| G37 | 实体休眠机制（远处实体跳过 tick） | spigot.yml `entity-activation-range` animals 32、monsters 32、raiders 64、misc 16 等 | 缺失 | `grep -rni "activation\|skipTick\|shouldTick" src/main/java` 只命中无关注释与 `SetTickStatePacket`；已载入区块内每个实体每 tick 跑物理与位置同步 | 3～5 | **做引擎机制**：休眠开关 + 按最近玩家距离分档的钩子，AI 降频由插件做。优先级低于 G20、G21：soak 4,000 单位档 tick P99 11.096 ms |
| G38 | 多语言约定与翻译键 CI 检查 | Adventure `GlobalTranslator` 按客户端语言服务端渲染（docs.papermc.io/paper/dev/component-api/i18n） | 部分 | `adventure/EngineAdventure.java:37` `GlobalTranslator::render`；`JvmFlags.java:29` `synaxis.automatic-component-translation` 默认 false；模组带 `client-fabric/src/main/resources/assets/synaxis/lang/zh_cn.json`、`en_us.json` | 1 | **做约定 + 检查**：服务端只发翻译键与参数，文案进模组 lang 文件；CI 检查服务端引用的 `synaxis:*` 与插件键在 zh_cn、en_us 都存在。服务端翻译只留给来不及随模组发版的运营文本 |

### 2.4 条件触发与可选

| 编号 | 能力 | Paper | Synaxis 现状 | 证据 | 工作量（人日） | 建议 |
| --- | --- | --- | --- | --- | --- | --- |
| G39 | 原生 AES-CFB8 / 压缩（velocity-native） | `paper-server/build.gradle.kts` 依赖 `com.velocitypowered:velocity-native:4.1.0`（PaperMC 仓库 main）；启用路径未核实 | 缺失 | `grep -rn "velocity-native\|libdeflate"` 在构建文件、`gradle/`、`src/`、`server-app/` 零命中；`design-auth.md` §5 本机实测加密 11.2～11.6 ms/MiB、解密 6.2～6.9 ms/MiB，按 soak 4,000 档下行折算 500 人约 0.21 核，不在 tick 线程上 | 2（条件触发） | **先不做**。生产机型上加密 + 压缩占用超过 1 个核的 10% 时再引入，代价是 JNI 与多平台二进制 |
| G40 | keep-alive 收尾 | 原版每 15 s 发、15 s 未应答断线（未核实） | 已有 | `ServerConfig.java:193-194` 10,000 ms / 15,000 ms；两阶段都有超时踢；缺陷：`ServerConfig.java:205` `socketTimeout` 对 `SocketChannel` 读无效（attack-surface 缺口 G2），超时原因是纯文本 `"Timeout"` 而非 §14 键（`docs/DESIGN.md:664`） | 0.5 | **只收尾两条技术债**。压测里跨大区公网玩家误踢数上升时，放宽到 15 s / 30 s |

## 3. 已拍板的五项

**1. 加密与 Mojang 正版验证**（G01、G06、G07，另见 G28、G33、G39）→ `design-auth.md`。重写而非回滚；`Auth.Online(keyPair, sessionServer)`，`Offline` 加 `encrypt` 开关供测试与核心模式用。26.2 客户端的 authlib 能用三个系统属性改会话主机（`design-auth.md` §2.2 有字节码证据），真客户端 CI 不需要正版账号；authlib 是否接受 `http://` 未核实。Mojang 没有公开 `hasJoined` 限速，所以 G02 的每 IP 登录令牌桶是对公网开放在线模式的前置条件。该草案有 8 个待拍板点（①～⑧）。

**2. 玩家数据 PostgreSQL + Redis**（G03，另见 G04、G08、G09、G18、G28、G32）→ `design-persistence.md`。选型 pgjdbc 42.7.13 + HikariCP 7.1.0 + Flyway 13.7.0 + Jedis 8.0.1，运行时闭包 4,159,717 字节。写权租约放 PostgreSQL，不放 Redis：防双写的判据要和写入在同一事务里，Redis 主从切换会丢锁。该草案有 13 个待拍板点（D1～D13）、14 条测试计划（P1～P14），测试计划里的耗时目标都是按预算推的，没有实测。

**3. 真客户端端到端回归常驻 CI**（G27）→ `design-ops-scale.md` ①。R1～R8 断言，判定不看退出码：S1 断线时退出码是 0，本地 aarch64 正常跑完却是 134。

**4. 连接数上限、事件台账缓冲写入、tick 多线程**（G02、G15、G17）→ `design-ops-scale.md` ②③④。tick 多线程的空间分格以 `DESIGN-M3.md` `API-M3-009` 为准，草案只补引擎侧三件事（floorMod、多线程下全量测试、全局锁）。

**5. 200～500 人真实压测**（G26，前置 G16）→ `design-ops-scale.md` ⑤。500 人档需要服务端与机器人各一台 ≥ 8 核 / 16 GiB 的机器、千兆内网。该草案有 10 个待拍板点（§6 #1～#10）。

## 4. 归 DESIGN-M3 的条目

以下条目由 `docs/DESIGN-M3.md` 负责设计与估算，本文不估工作量。「世界存储与区块有没有更优解法」也由那个会话回答；同目录上一轮调研在 `docs/research/large-world-2026-09/`。

| 条目 | 现状证据 | 本文对 M3 的接口约束 |
| --- | --- | --- |
| 区块加载器与存储格式（Anvil 读取器已删） | `grep -rln 'implements ChunkLoader' src/main/java` 只输出 `instance/NoopChunkLoaderImpl.java`；`instance/` 下无 anvil 目录 | — |
| 世界 / 区块自动保存与停机时存盘 | `NoopChunkLoaderImpl.java:14` `saveChunk` 空实现；`Instance#saveInstance`、`saveChunksToStorage`（`instance/Instance.java:379-394`）除定义外无调用方 | 挂进 G04 的停机序列，顺序为保存玩家 → 保存 overlay → 停 tick；G12 判死路径决定是否强行刷盘 |
| 区块自动卸载与卸载延迟（Paper `delay-chunk-unloads-by` 10 s、`max-auto-save-chunks-per-tick` 24） | `instance/InstanceManager.java:93` 注销整个实例时才以方法引用 `instance::unloadChunk` 卸载，别处无调用 | 卸载在区块所属 tick 线程上或经 `Acquirable.sync` 执行，并发 `PartitionUnload`，否则空间分区会留下空分区 |
| 区块生成与光照的线程归属 | `instance/InstanceContainer.java:383` 注释 `TODO run in the instance thread?`，`:390,394` 用 `Thread::startVirtualThread`、无并发上限，与网络虚拟线程共用 carrier 池 | CPU 密集的生成与光照走有界平台线程池（例如 max(2, 核数/4)），不占虚拟线程 carrier；载入完成与卸载都经 dispatcher 的 `PartitionLoad` / `PartitionUnload` |
| 区块加载 / 生成速率（Paper `player-max-chunk-load-rate` 100、`player-max-chunk-generate-rate` -1） | 同上，只有 Noop 加载器 | 与 G23 的发送侧硬上限分开计 |
| 空间分格 `ThreadProvider.grid` | `REQ-M3-015`、`API-M3-009`；引擎默认仍 `counter` + 1 线程 | G17 的引擎侧正确性修复先于它落地 |
| 玩家状态存储 `REQ-M3-018` / `API-M3-012` | 与 G03 重叠 | 以 `design-persistence.md` 为本文口径，分歧见 §7 Q1、Q2 |

## 5. 明确不做的条目

### 5.1 因为不含原版玩法而不适用

| 条目 | 理由 |
| --- | --- |
| 统计（statistics）与进度（advancements） | 两个包已在 M1 波次 1 整包删除（`docs/research/minestom-audit/07-architecture-review.md` 8.2.2），`PacketVanilla.java:502,547` 是 `ReservedPacket`。MMO 的任务与成就由插件实现，存进 G03 的玩家数据；分析用计数走事件台账 |
| simulation distance | 它裁剪的是原版随机刻与实体 AI，`ADR-003`（`docs/DESIGN.md:451`）已删除这些子系统；`UpdateSimulationDistancePacket` 无调用方。JoinGame 里填视距值即可 |
| Paper 按生物类别的激活范围规则（villagers、raiders 等） | 原版生物 AI 不存在。引擎仍需要通用休眠机制，见 G37 |

### 5.2 其他理由不做

| 条目 | 理由 | 何时重评 |
| --- | --- | --- |
| 签名聊天与 Mojang 聊天举报；`enforce-secure-profile` | 跨分片、跨大区聊天要经服务端过滤、改写，签名消息改写即失效，跨进程转发还要维护 last-seen 链；`enforce-secure-profile` 会拒掉没有 Mojang 签名公钥的正版账号，而我们不用这把公钥。代价：玩家无法向 Mojang 举报聊天，举报走自家 GM 工单与事件台账 | 出现要求接入 Mojang 举报的合规要求 |
| `prevent-proxy-connections` | 国内玩家走加速器时出口 IP 与本地 IP 不一致，开了会误拒 | — |
| 资源包强制（`require-resource-pack`） | 资源打进模组 jar（`DESIGN-M2.md` §2、`REQ-M2-020`），模组握手（`server-app/.../HandshakeGate.java:87`，3 s 内必须收到 `C2S_HELLO` 且 schema 版本相等）已强制装对版本。引擎保留 `Player#sendResourcePacks`（`entity/Player.java:1498`） | 美术更新频率高于模组发版频率，或模组 jar 超过约 100 MB 时，约 2 人日启用热更新通道 |
| query 协议（GameSpy4 UDP） | 无鉴权 UDP，是反射放大面；分片人数与 tick 耗时走 G16 指标与 G29 管理接口 | — |
| RCON | 明文协议，功能是 SMP 的子集，由 G29 覆盖 | — |
| Velocity / BungeeCord 接入 | 用户已定每分片自己验证。现有代码暂留，去留见 §7 Q7 | 出现代理层计划 |
| Folia 式区域化（region 动态合并拆分、无主线程） | 已按大区分进程 + 频道，单进程 200～500 人用空间分格 + 每 tick 屏障即可；Folia 的正确性问题集中在跨 region 实体（#427、#504、#469，见 `02-performance-forks-folia.md:85-95`）。若做估 30 人日以上（未核实） | 单分片压测中单格 tick P99 > 35 ms 且无法再拆频道 |
| ViaVersion 类跨版本兼容 | 客户端整合包锁定 26.2 + 模组，模组协议还有自己的 `schema_version`，跨版本翻译会让两层版本错配（`HandshakeListener.java:61-65` 只认协议 776） | 升级 Minecraft 版本时统一升客户端整合包 |
| 计分板 Sidebar / Team 协议包 | 任务追踪、血条名牌、公会标签由模组 HUD 渲染，Sidebar 只有 15 行、名牌只能加前后缀。**模组需求要写进一条**：没有 Team 包时 `collisionRule` 按默认 always，模组必须自己关掉玩家互推，否则主城 200 人站桩会互相挤动 | 恢复五个包约 2 人日 |
| spark | GPL-3.0；硬 fork 改了包名，spark 的 Minestom 平台模块能否直接用未核实。由 G31 的 JFR 命令替代 | — |
| LuckPerms / Vault | 绑定 Bukkit/Velocity/Sponge 平台接口，由 G09 替代 | — |
| 独立反机器人模块 | G01（每个 bot 需要一个付费账号）+ G02 每 IP 节流 + 模组握手 3 s 预算已构成防线；网络层 DDoS 靠机房清洗 | 出现持有正版号的批量 bot 时，加全局登录速率熔断，约 1 人日 |
| 插件配置 API | 插件在 `dataDirectory()` 下自行读文件；分片级配置走 G30 | 出现第二个需要热更新配置的插件 |
| 引擎与插件热重载 | 多数引擎值在构造时固化，热改只会得到半生效状态；更新走滚动重启 + 转服 | — |
| 按名字封禁 | 正版名字可改，按 UUID 封覆盖此需求 | — |
| Paper `max-tick-time` 式截断 | 截断会让玩法状态半更新；超载降级（降视距、降快照频率）交给插件，引擎只给 G16 的指标 | — |

### 5.3 已有、无需改动

BossBar（`adventure/bossbar/BossBarManager.java`、`entity/Player.java:1143,1148`）；Title / Subtitle / ActionBar（`entity/Player.java:1123-1138`）；命令树下发（`entity/Player.java:397,610`）；资源包推送能力（见上）；keep-alive 机制（G40 只收尾技术债）。

## 6. 实施顺序

依赖关系决定顺序；同一阶段内的条目可以并行。

1. **无前置、可立即开工**：G15 台账、G14 日志、G13 崩溃报告、G12 看门狗、G27 CI 断言（先跑离线档）、G17 中的 floorMod 与多线程全量测试、G25 JVM 参数。
2. **G02 连接闸门**。它是 G01 对公网开放的前置（`hasJoined` 限速未公开），也是 G26 压测机器人同 IP 进场时的白名单入口。
3. **G01 + G06 + G07 同一个 PR**，随后 G27 切到假 sessionserver 的 B 档，G33 启动自检。
4. **G18 调度器 → G03 持久化 → G04 停机保存钩子**。G03 需要异步查库再回实体线程；G04 的「保存全部在线玩家」阶段调用 G03。
5. **G09 权限 → G08 白名单与封禁、G10 命令**。G08 按 UUID 判定依赖 G01，存储依赖 G03；G10 的命令条件依赖 G09。G19 事件优先级与 G05 聊天可在此阶段并行（聊天拆给插件后需要排顺序）。
6. **规模线**：G16 指标 → G26 harness → 200 人基线压测 → 按数据定 G11 阈值，做 G20、G21、G22、G23、G24 → DESIGN-M3 `API-M3-009` 空间分格 + G17 接线 → 500 人压测。
7. **正式运营前**：G28 Transfer 与 cookie（依赖 G01、G03），G29 管理接口（依赖 G10），G30～G38 按需穿插。

## 7. 需要用户拍板的问题

| 编号 | 问题 | 默认 | 切换条件 | 代价 |
| --- | --- | --- | --- | --- |
| Q1 | Redis 的定位。`DESIGN-M3.md` `ADR-M3-009` 把 Redis 列为被否方案（理由：默认非同步落盘），与 9-23「PostgreSQL + Redis」冲突 | PostgreSQL 唯一权威；Redis（Valkey 8）只放可丢数据，整体宕机时照常登录；M3 那边改 ADR 对齐 | Redis 最后只剩在线名单一个用途时不引入，改用 PostgreSQL `shard_heartbeat` 表 | 多一个依赖和一套故障模式 |
| Q2 | 写权租约粒度。`REQ-M3-018` 是每玩家租约 | 每分片一个会话租约：按 128 分片假设，正式档续租从 3,200 次/秒降到 25.6 次/秒，前者已超过 `ADR-M3-009` 自己写的 2,000 次/秒重估线 | 出现玩家粒度的写权转移需求 | 需要 M3 会话改 `REQ-M3-018` / `API-M3-012` |
| Q3 | `server-app` 默认模式 | 在线模式，离线须显式传参 | 本机开发频繁因忘传参而连不上 sessionserver | 改默认离线后，忘传参的生产部署会对外开成离线服 |
| Q4 | sessionserver 故障时 | 拒绝登录，重试一次 | 单次故障超过 30 分钟，且运营判定停服比冒名更贵 | 降级缓存引入同 IP 冒名风险与一套新组件 |
| Q5 | 每 IP 上限的数值。三份盘点分别给过 3、8、16 | 统一取 `design-ops-scale.md`：并发 16、登录桶容量 3 / 每 2 s 回填 1、transfer 桶容量 10 / 每秒 2 | 首次公开内测中正常玩家触发 `login-throttled` 或 `too-many-connections` 每天超过 5 次时翻倍；网吧集中地区加白名单网段 | 放宽后单 IP 能占的登录前资源随之翻倍 |
| Q6 | MMO 分片的 `dispatcherThreads`。`REQ-M3-015` 写「核数」，性能盘点建议「核数 − 2」 | 核数 − 2，给网络虚拟线程 carrier 与 GC 留 2 核 | 500 人压测中 carrier 利用率 < 50% 时改为核数 | 多 2 条 tick 线程换来的并行度在单格热点下用不上 |
| Q7 | `Auth.Velocity` / `Auth.Bungee` 去留 | G01 落地后单独一个提交删除 | 出现代理层计划 | 以后要用代理时从 git 历史复活；Bungee 无 guard 时谁都能冒名，留着就是一个开错参数的风险 |
| Q8 | RTS 进程是否开多线程 | 不开（`counter` + 1） | 单局 > 200 人，或 tick P99 连续 5 分钟 > 25 ms | 插件逻辑整体在调度线程上，开了也不并行 |
| Q9 | 数据库故障时在线玩家 | 10 s 冻结、30 s 踢出（`design-persistence.md` D12） | 运营宁可无上界丢进度也不踢人 | 踢人影响全分片在线 |
| Q10 | 新增依赖 | 接受 pgjdbc、HikariCP、Flyway、Jedis（G03）与 Micrometer + Prometheus（G16）；G29 的 WebSocket 库在做 G29 时再定 | 项目要求零新依赖时，G16 改为 JDK `HttpServer` 手写文本格式，约多 1 人日 | 依赖许可证为 BSD-2-Clause / Apache-2.0 / MIT，与 Apache-2.0 兼容 |
| Q11 | 压测机器 | 服务端与机器人各一台 ≥ 8 核 / 16 GiB，千兆内网 | 只有本机时 500 人档只跑部分画像，其余等第二台机器 | 本机另有满核进程时 tick 数字含干扰（`soak-2026-09-22/report.md:145`） |
| Q12 | G28 Transfer 排期 | 正式运营前 | 内测开两个及以上大区时提前到内测前 | 内测前工作量 +3.5～5 人日 |

三份草案里还有细项待拍板：`design-auth.md`「待拍板汇总」①～⑧、`design-persistence.md` D1～D13、`design-ops-scale.md` §6 #1～#10。上表 Q3、Q4、Q7 与 auth ①④⑧ 相同，Q5、Q8、Q11 与 ops #3、#8、#10 相同，Q9 即 persistence D12。

## 8. 未核实事项与本轮更正

### 8.1 未核实

1. Mojang 对 `hasJoined` 的限速；客户端「每账号 30 s 内最多 6 次 join」只来自 minecraft.wiki。
2. authlib 9.0.75 是否接受 `http://` 会话主机；客户端在假 services 主机上能否进服。不接受时假服务要自签证书，G27 B 档工作量要重估。
3. 在线模式恢复后 `enforcesSecureChat=false` + 系统消息转发时，真客户端是否出现聊天警告（G05）。
4. 目标机房（尤其中国大陆）到 `sessionserver.mojang.com` 的延迟与连通性。
5. x86 生产机型上 CFB8 与 RSA 的耗时；MMO 玩法下每玩家下行速率。
6. Paper 侧：`PlayerProfile` 类名、重复登录实现、白名单与封禁 API 类名、`storeCookie` 与 `transfer(host, port)`、velocity-native 的启用路径、原版实体 updateInterval 值、crash-reports 目录名、keep-alive 15 s / 15 s、「Paper 无可见实体上限」的源码核对。
7. spark 的 Minestom 平台模块在改包名后能否使用；JFR 开销约 1% 为 JDK 口径，未在本仓库实测。
8. G21 的 200 人同格 25 MB/s、G23 引用的 5.26 KB/区块，均为推算。
9. G25 中 G1 与分代 ZGC 的对比；Folia 式区域化的 30 人日估算。
10. SMP 在 26.2 对应的协议版本号（wiki 只写了 26.3 为 3.1.0）。
11. GitHub Actions 私有仓库的免费分钟数；G27 PR 档约 4 分钟为折算值。
12. `LogBudget` 与 `GameChannel` 在多 tick 线程下的线程安全性未审（G17 范围内要补）。

### 8.2 相对各维度盘点的更正

- G11：盘点以「soak 每玩家 1,025 包/秒」论证 Paper 默认阈值会误踢自家客户端。该数字是出站引擎包（`soak-2026-09-22/report.md:51`），不能用于入站阈值，已改为按压测入站分布定。
- G12：`TickSchedulerThread.java` 在包根，不在 `thread/` 下。
- G31：`EventsJFR.java` 有 10 处 `@Name`，盘点写 9 个事件。
- G02：bukkit.yml `connection-throttle` 默认 4000 ms 已由 bukkit-configuration 页核实，网络维度标的「未核实」撤销。
- G26：soak 数字已对照报告复核（`soak-2026-09-22/report.md:35,51,67,68,114-116`，`soak-2026-09-18/report.md:21`）；服务端 CPU 12.7～42.1% 只是 4,000 单位档，三档合计范围是 9.7～42.1%。
- 同一能力在多个维度重复出现的已合并：连接上限（三处）并入 G02；封禁、白名单、管理员名单持久化并入 G08；OP 与权限 API 并入 G09；控制台命令与插件命令框架并入 G10；包级限流与补全限频并入 G11；看门狗两处并入 G12；皮肤两处并入 G06；进场节流并入 G02；持久化插件 API 并入 G03。
- 严重度冲突的取舍：聊天（身份维度标「可选」，指签名；客户端维度标「开服阻断」，指广播放大）拆成 G05 与 §5.2 两条；Transfer 与 cookie（网络维度「内测前必需」，身份维度「正式运营前」）取正式运营前并在 Q12 给出提前条件。

下一步：用户按 §2 勾选并回答 §7 的 Q1～Q12；Q1、Q2 需要同时通知负责 `DESIGN-M3.md` 的会话，两边对齐后，G03 才能开工。
