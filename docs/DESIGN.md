# Synaxis 设计文档 · 里程碑 M1「引擎独立化」

## 1. 元信息 / 变更记录

| 字段 | 值 |
| --- | --- |
| 状态 | 已批准并实施完毕（M1 与 M1.5 代码侧于 2026-09-09 完成，见 §26；阶段 3 剩两条人工用例：`REQ-011` 的核心模式下原版客户端冒烟 `TC-038`（取代废弃的 `TC-030`，用户 2026-10-06 选做法 A）与 `REQ-012` 的 `TC-016`） |
| 项目画像 | 内部系统（引擎只被本团队的游戏工程消费，不对公网；§3/§20 降为按需，§23 简化为「直接放量 + 回滚预案」。游戏本身对外发布时另立设计文档，画像为生产产品） |
| Owner | Bearisbug |
| 评审人 | Bearisbug |
| 最后更新 | 2026-10-19 |
| 关联 | 仓库 `https://github.com/Bearisbug/synaxis` · **里程碑分册 `docs/DESIGN-M2.md`（M2「游戏工程骨架与 RTS 垂直切片」，M2 范围内的需求 / 接口 / 状态机 / ADR 一律以该分册为准）** · **里程碑分册 `docs/DESIGN-M3.md`（M3「大世界地图管线与分片」，M3 范围内的需求 / 接口 / 状态机 / ADR 一律以该分册为准）** · **生产就绪分册 `docs/DESIGN-PROD.md`（生产就绪：加密与正版验证、连接闸门、玩家数据持久化（PostgreSQL + Redis）、运维与可观测性、tick 多线程（空间分格 ThreadProvider 的引擎侧）、面向客户端的基础能力与插件 API、真客户端回归与 200～500 人压测；范围内的需求 / 接口 / 状态机 / ADR 一律以该分册为准）** · **玩法架构分册 `docs/DESIGN-PLAY.md`（玩法架构：玩法插件怎么写、宿主托管的玩法实例与生命周期、四种形态（MMO 开放世界、RTS 对局、副本或小游戏、限时活动）的落点、客户端协作（按通道协商版本、服务端驱动的场景与 HUD）、样板玩法；范围内的需求 / 接口 / 状态机 / ADR 一律以该分册为准）** · 审计与架构评审 `docs/research/minestom-audit/`（00 总结、07 架构评审第八章） · 测试文档 `docs/TEST.md` |

变更记录：

| 日期 | 版本 | 改动 | 作者 |
| --- | --- | --- | --- |
| 2026-10-19 | v0.51 | 跟进 `docs/DESIGN-PROD.md` v0.9.107（RUN-082），需求不改。**§8** `API-003`：`startTicking()` 遇到已经由 `dispatcher().start()` 起了的 tick 线程时只起 tick 调度线程，不再第二次起 tick 线程（原先按 `ThreadDispatcher#start` 的契约抛 `IllegalThreadStateException`），测试可以先起 tick 线程、等分区的 tick 线程载入区块，再开始 tick；tick 线程起过又停了的照旧抛 | Bearisbug |
| 2026-10-19 | v0.50 | 按 RUN-081（`main` `9c4ad17a64`）移动后的代码同步一处行号，本版只改文档：§28 七处纯文本断线一行引的 keep-alive 超时踢人 `ConnectionManager.java:806` 改为 `:826` | Bearisbug |
| 2026-10-19 | v0.49 | 跟进 `docs/DESIGN-PROD.md` 的 RUN-081（集成分支 `prod/integrate-081`，基于 `main` `f6b40afd94`，该分册 v0.9.93～v0.9.106），各组只改了内容、没升本文档的版本，本版汇总，需求不改。**§5** `REQ-017`：原因字段至多 1,024 个字符，`Transcoder` 的默认数组读取失败只报出错的元素与下标（NET）。**§8** `API-002`：插件消息的通道名不是合法 identifier 时以 `synaxis:protocol-error` 断线（PLAYHOST）；`API-003`：`ServerProcess#start(address)` 只绑定端口，`startTicking()` 起 tick 线程，停机任务按注册顺序各执行一次、异常隔离，超长倒计时被拒（BOOT）；`API-004`：注册表冻结覆盖 tag 集合（CODEC）；新增 `API-005` 背包界面与点击：打开顺序、点击事务与事件推迟派发、`EntityEquipEvent` 的约束与锁（INV）。**§9** 实体元数据一条：`switchEntityType` 之后旧的 `EntityMeta` 拒绝写入（ENTITY）。**§11.1** 状态图与迁移表加 `login → disconnected`（LoginSuccess 之前的 `LoginAcknowledged`），`play → configuration` 一行写明确认之前排队的包怎么处理（NET）；「断线口径」段补 `ConnectionManager#dropPlayer` 只让摘到玩家的一方派发、configuration 态不发 play 包（PLAYER）。**§15** 输入校验表加「实体数值」一行：NaN 的属性取下限，伤害量与生命值收到 NaN 抛异常（PLAYER）。**§16** 竞态表：写线程出错时丢包并以 `synaxis:internal-error` 踢人（BOOT），事件分发按确切运行时类（EVENT），同一对实体与玩家的显示与隐藏交错（ENTITY），`setInstance` 的 `join`、换实例作废、读写线程同时断开、第二个 ping 四行（PLAYER）。**§28**：纯文本断线清点行补 `:217` 的更正，`SOAK-3` 清点行的握手、登录与配置期包数 11 → 12，`network` 包三处小缺陷一行由技术债改为已决（NET）；新增已决两行（WORLD：维度没有默认时钟时 `Instance#setTime` 不生效；STORE：宿主日志门禁的豁免名单去掉 `PluginSchedulerImpl`）；「读超时」一行行号跟进（BOOT、PLAYER）。两组改同一行的按词合并，§11.1「断线口径」段的行号取合并后的代码（见该分册 v0.9.106）。§1「关联」与 §26 不改。集成分支上补的跨组后续（该分册 v0.9.106）：`API-005` 点击事务一条补上丢弃类点击——`ItemDropEvent` 的监听器关界面时，取光标排到点击提交之后；§11.1 `play → configuration` 一行把「ack 之前排队的包」收窄为「ack 到达时还留在队列里的包」，更早一个 tick 末尾已取走的仍按 play 态处理 | Bearisbug |
| 2026-10-18 | v0.48 | 按 `docs/DESIGN-PROD.md` §28.3 交给光照会话的行同步 RUN-080（`main` `965c3bddae`），本版只改文档。**§11.1**「断线口径」段补首次出生在玩家进实例之前失败的派发：`ConnectionManager` 以 `synaxis:internal-error` 踢掉它、再交给一次 `leftBeforeSpawn`，`Player#dropPendingSpawn` 对它只返回一次真并广播 `PlayerInfoRemovePacket`（该分册 v0.9.87，`TC-PROD-793`、`TC-PROD-794`、`TC-PROD-795`）；该段引的 `ConnectionManager.java:606-612` 改为离开队列的排空 `:786-790`（原行号早已指到 `shutdown`），`PlayerConnection.java:273-276` 改为 `:275-278`（后者不在 §28.3 的清单里，逐个扫引用时发现）。**§28**：七处纯文本断线一行引的 `ConnectionManager.java:794` 改为 `:806`；审计 F49 两条已决行之后加一条已决行「物品与聊天组件写给 26.2 客户端的四处断线」（RUN-080 组 ITEMNET，`TC-PROD-810`、`TC-PROD-811`、`TC-PROD-812`、`TC-PROD-813`、`TC-PROD-814`） | Bearisbug |
| 2026-10-18 | v0.47 | 按 `docs/DESIGN-PROD.md` §28.3 交给本文档的四行改（RUN-080 组 NET、CODEC、CMD，该分册 v0.9.85、v0.9.88、v0.9.90；行号按集成分支 `prod/integrate-080` 合并后的代码），本版只改文档。**§11.1**：状态图与迁移表各加一条 `configuration → disconnected`（确认超时）：服务端已发 FinishConfiguration、`keepAliveKick` 内没收到确认，由 tick 以 `synaxis:keep-alive-timeout` 断开，这段时间不发 keep-alive。**§28**：`SOAK-3` 清点行 ② 档的前提补上批与批之间（断开之后读线程读空 socket、不再解码，迟到的踢人对已下线的连接什么都不做）；「读超时」技术债一行补 `FinishConfiguration` 发出到确认之间同样由 `keepAliveKick` 截止，`ConnectionManager.java:794` 改为 `:806`；「客户端 NBT 不进 tag 系统」已决行补编不出 NBT 的值移除它的键；`CommandParserImpl` 技术债一行补建命令包时 condition 抛异常按不可见处理与参数的错误回调何时调用，其后新增一行技术债：目标选择器只实现 6 个参数。§1「最后更新」改为 2026-10-18 | Bearisbug |
| 2026-10-12 | v0.46 | 登记玩法架构分册 `docs/DESIGN-PLAY.md`（v0.1，用户 2026-10-12 签字），照 `docs/DESIGN-PROD.md` §28.3 与该分册 §28.3 交给光照会话的行改，本版只改文档：§1「关联」行加分册指引；§26 里程碑表加「玩法架构」一行，表后一段写明它与生产就绪分册一样不对应单个里程碑、按能力划范围；§1「最后更新」改为 2026-10-12 | Bearisbug |
| 2026-10-07 | v0.45 | M3-S 集成分支 `m3s/integrate` 合入 `main` `8220ef9d0f`（RUN-072）之后，按 `docs/DESIGN-PROD.md` §28.3 Gradle 版本一行改，本版只改文档：该分册 v0.9.61 合入 Dependabot PR #10，根构建的 wrapper 由 9.7.1 升到 9.8.0（`gradle/wrapper/gradle-wrapper.properties`），§5 `REQ-001`、§6 架构图 `gradle` 节点、§17 依赖表三处写死的版本随之改为 9.8.0。`client-fabric` 的独立 wrapper 仍是 9.7.1，不在这三处 | Bearisbug |
| 2026-10-07 | v0.44 | M3-S 合并步骤（集成分支 `m3s/integrate`）按 `docs/DESIGN-PROD.md` §28.3 交给光照会话的行改，本版只改文档。**§15** 加一行「客户端位置与方块操作」：越界与单包 64 格的位置包拉回、挖掘与使用方块的距离判定（该分册 v0.9.47，`API-PROD-030`、`ADR-PROD-016`），载具移动包同判（v0.9.53）；核心模式平坦世界的边界不进这一行。**§5 `REQ-017`** 记上游移植第二批（该分册 v0.9.55：#3382、#3304、#3249、#3400 与 #3388、#3373、logback 1.6.5，用例 `TC-PROD-680`～`TC-PROD-687`）。**§16**「对端停止读取，服务端断开该连接」一行按该分册 v0.9.52 改：参与方加写线程自己，写线程退出前经 `flushRemaining` 写空出站队列并起同一条关闭线程。**§28**：Adventure 转换 SPI 一行记部分解决（悬停物品按缓冲区的注册表编码，`nbt(Registries)`），仍开着的是转换 SPI 本身与无参的 `nbt()`；三行转已决：引擎位置、挖掘与放置判定（RUN-070、RUN-071 已合入 `main`，余项在该分册 §28.2）、`TC-020` 的注册表样本（由 `docs/TEST.md` `TC-037` 取代，`probe.py compare-registry`）、`docs/ENGINE.md` §7 漏列的工作流（该分册 v0.9.50）。§1 状态行与 §26 阶段 3 行写上核心模式下原版冒烟的用例号 `TC-038` | Bearisbug |
| 2026-10-06 | v0.43 | 按 2026-10-06 的全项目盘点改过期标注，并登记用户同日对 `REQ-011` 验收方式的选择（做法 A，推荐项），本版只改文档。**`REQ-011`**：`TC-030`（玩法模式下的原版客户端登录）的前置在强制客户端模组握手（`REQ-M2-008`）之后不成立，废弃；核心模式（`-Dsynaxis.app.core-mode=on`）本来就放行原版客户端（`HandshakeGate` 只在玩法模式要求握手），新写一条核心模式下的原版客户端登录与断开用例，人工执行一次，不改代码；探针经 `synaxis:main` 的 echo 往返已有证据（RUN-004～015 中 9 轮的 `echo.txt`，最近 RUN-015）。§1 状态行、§5 `REQ-011` 一行（原版客户端在核心模式下进世界并断开，echo 往返交给探针）、§19 `REQ-011` 的原版客户端场景拆成两条、§26 阶段 3 行、§27 同步；新用例的 ID 由 `docs/TEST.md` 分配（`docs/DESIGN-M3.md` §26 M3-S 轨道 G 起草）。**§19**「删除波次 1 与 2 后协议不变」按两种进程分开写判据：不带宿主的引擎进程逐字节相同（`RegistryDataByteParityIntegrationTest`），`server-app` 除宿主加的 `synaxis:match` 外逐字节相同，加不带忽略项比的反证，取代 `TC-020` 的新用例追溯这一句；主源码文件数改为 §3 规模行的 ≤ 1,150。**§28**：已解决的五行转已决（ArchUnit 冻结存储剪枝、`minestom.` 前缀、`ObjectPoolTest` 的 NPE、连接数上限、jcstress 与 isolated-projects 的风险）；台账一行按现状改写处置；读超时一行写明一半到位；「监听器抛了异常」已决行的 ① 按 `docs/DESIGN-PROD.md` v0.9.39 改写（该分册 §28.3 交 M3 会话的一行）；公开或私有的时限与 `docs/DESIGN-M2.md` §28.2 对齐为「内测全量放量前」；纯文本断线、`network` 包三处、`CommandParserImpl` 的 ERROR、Adventure 两条技术债的过期时限改成可判定的触发条件并写明归属，Adventure `ClickCallback.Provider` 那条 Open Question 的过期时限同样改成触发条件并写明默认；新增五行：引擎位置、挖掘与放置判定的安全修复（`docs/DESIGN-M3.md` §28.2 交接行 M3 v0.38）、`TC-020` 的注册表样本、`docs/ENGINE.md` §7 漏列的工作流、Dependabot PR #6、`main` 的分支保护 | Bearisbug |
| 2026-09-30 | v0.42 | 按 `docs/DESIGN-PROD.md` v0.9.35 改限流阈值，本版只改文档。§15「限流」行与 §28 速率闸那条已决行的 7 s 窗口 582 包改为 750（用户 2026-09-30 拍板：`docs/TEST.md` RUN-057 六档压测机器人每人 7 s 上行包数 P99 最高 374 × 2，取整），已决行补上 582 → 750 的来源。§15「入站包」行的 50 玩家 5,000 包/秒是每连接 100 包/秒，7 s 700 个，在 750 之下 | Bearisbug |
| 2026-09-26 | v0.41（RUN-039 修复） | 按 `docs/DESIGN-PROD.md` v0.9.20（RUN-039 修复）改停机的信号路径，关闭该分册 §28.2「SIGTERM 在宿主发起的停机进行中到达」。原先关闭钩子的 `stop()` 得 false（停机已由控制台 `stop 30`、管理接口或看门狗判死占下）就立即返回，JVM 跑完关闭钩子即 halt，非 daemon 的 `Syn-Shutdown` 拦不住，倒计时之后的保存、踢人、插件禁用都不执行。**`API-003`** 约束：写明非 daemon 只在非信号的退出里拦得住 JVM；钩子（`ServerProcessImpl#stopOnSignal`）得 false 时结束剩余倒计时、等那次序列跑完才返回。**§11.2** 状态图与迁移表加 `stopping → stopping`（SIGTERM 关闭钩子）一行。**§16** 两线程同时发起停机一行：「停机按得 true 者的倒计时走」加上钩子后到这个例外（该行举的例子就是它）。`ServerProcess` 接口不变 | Bearisbug |
| 2026-09-26 | v0.40 | 按 `docs/DESIGN-PROD.md` §28.3 登记生产就绪第 5、6 波已落地的改动，本版只改文档，引擎代码未动。**限流**：§15「限流」行由「每秒 > 500 包」改为 7 s 窗口 > 582 包断线，另有解压后负载 > 4 MiB/s 断线、补全 > 20 次/秒丢弃（该分册 `API-PROD-012`）；§28 那条「没有按秒计数的速率闸」技术债转已决（第 5 波 5B，commit `d130557a5d`），初值由 500 改为 582。**服务器列表**：`REQ-005` 注明 `ping` 的 MOTD、图标、玩家样本与 `ServerListPingEvent` 已由第 6 波 6D 以新形态加回（`REQ-PROD-037` / `API-PROD-024`），`ping` 包本身、多版本响应格式与 legacy ping 不恢复。**配置**：`REQ-014` 补现状——`ServerConfig` 72 个进程级键，6C 加 `synaxis.compression-threshold`（进程级）与 `synaxis.compression-level`（`JvmFlags`，JVM 级），`server-app` 起服前读 `synaxis.properties`，该分册 v0.9.11 起 15 个 `ServerConfig` 布尔键只认 `true` / `false`；§28「开关结构上是 JVM 级」已决行补现状 16 个与读文件先于 `JvmFlags` 类加载。§1「最后更新」改为 2026-09-26 | Bearisbug |
| 2026-09-26 | v0.39 | 按 `docs/DESIGN-PROD.md` §28.3 改 `API-003` 的签名，关闭该分册 §28.2「`stop` 与 SIGTERM 竞态」。**`API-003`**：`stop()` 与 `beginShutdown(Duration)` 由 `void` 改为返回 `boolean`，即这次调用是否把 `stopping` 由 false 置为 true；已在停机时立即返回 false，不等那次停机、不改它的倒计时。`beginShutdown` 从任何线程调用都在新起的非 daemon 线程 `Syn-Shutdown` 上跑停机序列、立即返回（原先从非 tick 线程调用时要等进程停下），调用方因此能先拿到结果再回复；`stop()` 的线程规则不变，SIGTERM 的关闭钩子仍在自己线程上跑完序列。只丢弃返回值的调用照常编译，但二进制不兼容：方法描述符变了，按旧引擎编译、调这两个方法的类（包括经 `PluginContext#process()` 调用的插件）运行时抛 `NoSuchMethodError`，要按新引擎重编（`process()` 不作兼容承诺，`docs/PLUGINS.md` §7）。**§11.2**：`started → stopping` 按调用拆成三行（`stop` 非 tick 线程、`stop` tick 线程、`beginShutdown`），守卫加「`stopping` CAS 成功」，新增 `stopping → stopping`（再次调用，返回 false）；状态图同步。**§16** 加一行：两条线程同时发起停机 | Bearisbug |
| 2026-09-24 | v0.38 | 按 `docs/DESIGN-PROD.md` v0.7.1 §28.3 登记引擎侧已落地的改动，本版只改文档。**断线**：§11.1 `play → disconnected` 行与表后写明每个被创建的玩家离开时恰好派发一次 `PlayerDisconnectEvent`；configuration 结束到进世界之间断开的玩家，由 `PlayerConnection#disconnect` 同时交给 `ConnectionManager#leftBeforeSpawn`，下一 tick 在 tick 线程上补发，已开始进世界的由实体移除派发（commit `b2b7af2a3c`）。**停机**：`API-003` 登记 `beginShutdown(Duration)`、`setShutdownParticipant(ShutdownParticipant)` 与 `stop()` 的新语义；§11.2 `stopping → stopped` 的动作改为该分册 §11.4 的顺序；§17 `process.shutdown` 删去「第 3 波落地前仍是 10 s join」一句（第 3 波已落地）。**§28**：`Auth.Velocity` / `Auth.Bungee` 技术债整行删去（第 2 波 2B 已删两种模式，`MojangUtils.authenticateSession` 已随 REQ-014 删除）；`SOAK-3` 清点行 ② 档改为 4 处，`clientDrivenPathsDoNotWriteALinePerPacket` 豁免名单改为八个类（加 `ConnectionGate`、`SessionClient`，移出 `HandshakeListener`） | Bearisbug |
| 2026-09-23 | v0.37 | 按 `docs/DESIGN-PROD.md` v0.3 §28.3 登记生产就绪分册，引擎代码未动。**登记**：§1「关联」行加分册指引，§26 里程碑表加「生产就绪」一行并注明它按能力划范围。**引用**：§8 `API-001` 鉴权行改为在线 / 离线两种（`API-PROD-001`）；§11.1 注明 `login` 态的展开在该分册 §11.1；§11.2 注明 `stopping` 的展开与新关闭顺序在该分册 §11.4；§14 表尾注明新增键见该分册 §14。**停机预算**：§17 `process.shutdown` 的 10 s join 上限、§21「`stop()` < 10 s」SLO 与 `RB-002` 的恢复验证，改为倒计时之后保存 30 s（判死路径 15 s）+ 踢人 5 s + 台账 2 s，部署侧 `TimeoutStopSec` ≥ 60 s；第 3 波落地前实现仍是 10 s join。**§28**：台账逐条写、每秒 > 500 包断线、连接数无上限、`socketTimeout` 空操作、`Auth.Velocity` / `Bungee` 五条改为由 `REQ-PROD-017` / `REQ-PROD-013` / `REQ-PROD-001` / `REQ-PROD-043` / `REQ-PROD-036` 承接，台账那条按 `ADR-PROD-005` 改写（缓冲只省系统调用，字节上界靠闸门先于台账与每日上限）；`ExceptionManager` 每包上报与 `Acquirable` 未分配态两条转已决（第 0 波 `REQ-PROD-015`、`REQ-PROD-019` 前半已落地） | Bearisbug |
| 2026-09-23 | v0.36 | §28 光照 parity 测试那条技术债转已决：`LightParityIntegrationTest` 已随 `docs/DESIGN-M3.md` `REQ-M3-007` 补回，7 个场景与原版 26.2 逐值差异 0。设计正文其余章节与引擎代码未动 | Bearisbug |
| 2026-09-23 | v0.35 | 登记里程碑分册 `docs/DESIGN-M3.md`（M3「大世界地图管线与分片」，草稿）：§1「关联」行加分册指引，§26 里程碑表加 M3 一行。设计正文其余章节与引擎代码未动 | Bearisbug |
| 2026-09-23 | v0.34 | §28 光照 parity 测试那条技术债的「M2 决定光照去留时一并处理」落地：M2 决定保留光照（`docs/DESIGN-M2.md` v0.44），parity 测试仍未补，该行改写现有证据的范围，时限改为「地形出现平地与石柱以外的方块之前」。引擎代码未改 | Bearisbug |
| 2026-09-23 | v0.33 | 登记 REQ-005 删包留下的包 ID 缺陷与修复，设计正文动了 REQ-005 一行、§19 一条 Gherkin 与 §28 一行。**缺陷**：包 ID 就是包在注册表里的位置，REQ-005 删掉 clientbound 包之后排在后面的包整体前移——login 在 `minecraft:hello` 之后前移 1 位，play 有 18 个空位；服务端把 `login_compression` 发在原版 `login_finished` 的 ID 上，原版 26.2 客户端按 `login_finished` 解码失败、登录不进去。**修复**：commit `cb44131ef1` 用 `ReservedPacket` 占住每个空位（login 1 个、play 18 个），它只占位置，注册表不按类、不按 ID 解析它。**门禁**：新增 `PacketVanillaIdTest`，把 26.2 原版 login、configuration、play 三个状态两个方向共六张表逐个 ID ↔ 资源名钉死（期望值由 `javap` 读 26.2 客户端 jar 得到，写成测试里的常量，运行时不依赖 jar），对应 `docs/TEST.md` TC-036 | Bearisbug |
| 2026-09-22 | v0.32 | 攻击面盘点（`docs/research/attack-surface-2026-09/README.md`，`docs/TEST.md` §2 那行「安全渗透——无归属」自此有归属）在引擎侧修了两处、登记了两条，设计正文只动 §16。**修**：① 对端登录后停止读取，`PlayerConnection#disconnect` 之后 socket 永不关闭——断线包由写线程写、channel 也由它退出时关，而它正卡在 socket 写里等对端读；`PlayerSocketConnection` 现在在断开后给写线程 1 s 冲刷预算，超时由一条独立虚拟线程关闭 channel（§16 新增一行，§28 一条「已决」）。② 离线模式的 profile UUID 由服务端按用户名派生（`GameProfile#offlineUuid`），不再采信 `LoginStart` 里对端自选的 UUID——§10 与 §14 本来就写的是「按用户名派生」，是实现追齐；否则 `synaxis:duplicate-login` 这道闸按 UUID 判，对端换个 UUID 就能同名上线两次（§28 一条「已决」）。**登记**（§28 两条技术债）：连接数无上限且 fd 耗尽时 accept 循环空转；`socketTimeout` 对 `SocketChannel` 读无效，登录后唯一的空闲上界是 keep-alive。压缩帧的两道上限与 keep-alive 踢人各补一条证明既有控制的测试。游戏侧的四条登记见 `docs/DESIGN-M2.md` v0.40 | Bearisbug |
| 2026-09-19 | v0.31 | §8 `API-002`「自定义负载通道」的鉴权行更正：原文写「仅 `play` 状态的连接可用，其他状态收到即断线」，但 `ClientPluginMessagePacket` 同时实现 `ClientPacket.Configuration` 与 `ClientPacket.Play`、两个状态都注册了监听器，configuration 阶段同样派发 `PlayerPluginMessageEvent`。这是 `docs/DESIGN-M2.md` §28 登记的、要在 M2 阶段 4 随实现一起更正的技术债，本版随该阶段的客户端模组握手（`REQ-M2-008`）落地更正。引擎行为未变，改的是描述 | Bearisbug |
| 2026-09-18 | v0.30 | 客户端可驱动日志的清点范围由「引擎根模块」纠正为「全部模块的主源码」，口径改成一条能照抄复跑的命令；宿主 `:server-app` 上同形状的四处逐包日志（未注册频道的丢弃行、频道 handler 抛异常、插件事件监听器抛异常、台账写入失败）按引擎已有的形状限流，并由 `:server-app` 自己测试源集里的 `ClientDrivenLogArchitectureTest` 守同一条规则——引擎的 `ArchitectureTest` 拿不到别的模块的 classpath，一条规则守不了两个模块。§15 的「日志量」一行补上宿主那半边的门禁 | Bearisbug |
| 2026-09-18 | v0.29 | `ADR-007` 写死测试密度口径（含 `network` 排除 19 个 `package-info.java` 与 219 个纯 record 包定义后的逻辑文件分母），实测现状连同仍在 0.30 线下的 `command` / `utils` / `listener` / `adventure` / `world` 记进 §19：`event` 由 0.05 补到 0.32（新增 36 个测试文件），`network` 按逻辑文件为 1.09；拆分的前置条件按待拆类所在的 `entity`、`instance` 判，两者已过线。同批修两处事件树缺陷：`EventNode#setPriority` 在节点已挂载并派发过之后不失效父节点缓存，改后的优先级不生效；`addChild` 不拒绝把祖先（含节点自己）当孩子，直接把调用方打成 `StackOverflowError` 并在树上留下环。设计正文其余章节未动 | Bearisbug |
| 2026-09-18 | v0.28 | §15 的「入站包 50 玩家峰值 5,000 包/秒无丢包」由 2026-09-18 组 6 实测闭合（Java 发包端 `tools/loadtest/PacketFlood.java`，5,014.9 包/秒 / 6 分钟 / 0 掉线，发包窗口内 7,200 个 tick 的 p99 1.631 ms，超 50 ms 的只有 50 条连接同时进场的那个窗口首 tick；组 7 抬到 40,015 包/秒仍 0 掉线）。设计正文未动；§28 新增两条技术债：① §15 写的 500 包/秒速率闸没有实现，实际起作用的是 `playerPacketPerTick` 的排空能力 + 队列深度；② §14 的错误码信封之外，默认配置下客户端的包就能触发的纯文本断线还有七处（`SOAK-6` 按全部 30 个 `kick(...)` 调用点分档清点，本轮只追齐了填满包队列的那一条） | Bearisbug |
| 2026-09-18 | v0.27 | §15 新增非功能约束「日志量不由客户端决定」（客户端可驱动频率的 WARN/ERROR 必须有界），并由 `ArchitectureTest#clientDrivenPathsDoNotWriteALinePerPacket` 一条 ArchUnit 规则守住 `network..` 与 `listener..` 两个包树（`ExceptionManager#handleException` 一并算作写日志）；`PacketReading` 的「包未读完」按 10 秒限流并带区间累计次数；`PlayerSocketConnection` 派发一批包时遇到已断开的连接就 `break`，「紧跟断开」那一档才真的有界。§28 登记引擎根模块 27 处 WARN/ERROR/printStackTrace 的按频率归属扫描结论 | Bearisbug |
| 2026-09-18 | v0.26 | `SOAK-3` 修复（离线模式下同名玩家的重复 UUID）：§14 新增原因键 `synaxis:duplicate-login`，§11.1 的 login→configuration 守卫补上「profile UUID 未被在线连接占用」并新增 login→disconnected 一条转移；§28 登记一条「已决」 | Bearisbug |
| 2026-09-18 | v0.25 | `SOAK-1` / `SOAK-2` 修复：断线时释放 known-packs 等待，玩家进入生命周期集合改走与 `removePlayer` 同锁的 `trackPlayer`，§28 两条技术债转「已决」。§14 按实现更正——`connection_id` 只进服务端日志，不拼进断线文本（`docs/DESIGN-M2.md` §28.2 登记的那条文实不符随之关闭） | Bearisbug |
| 2026-09-18 | v0.24 | §28 新增两条技术债：2026-09-18 压测发现 `SOAK-1`——configuration 阶段被中断的连接，其虚拟线程与整个 `Player` 对象图滞留到 `knownPacksResponseTimeout`（默认 5 分钟，从最后一次连接起算）才回收，属 2026-09-15 审计 F02 未修完的那一半；`SOAK-2`——同一路径上约 1% 的连接留下永不回收的 `Player` | Bearisbug |
| 2026-09-09 | v0.1 | 初稿：里程碑 M1 范围、实体、协议契约、状态机、阶段计划 | Bearisbug |
| 2026-09-16 | v0.23 | §28 的四条审计残留全部转「已决」，由 `docs/DESIGN-M2.md` 的阶段 3 处置并在每条里注明去向：F29 残留（a）`SharedInstance` 整块删除（`REQ-M2-012`）、F29 残留（b）卸载区块时同步移除已下线玩家（`REQ-M2-013`）、F11 残留视距收缩的两半（`REQ-M2-014`）、F49 残留按「降级不修」处置（Javadoc + `:game-rts` 测试源集里的无条件 ArchUnit 规则，`REQ-M2-015`）。同批把 `InstanceContainer#copy()` 的内存警告写进该方法 Javadoc（登记在 M2 分册 §28.2，行为不变）。本文档其余章节未动——引擎侧契约没有变化 | Bearisbug |
| 2026-09-16 | v0.22 | §8 演进与弃用与 `ADR-007` 里的「唯一稳定面」由 `platform-api` 改称 `plugin-api`——M2 实际落地的稳定面模块是 `:plugin-api`（谁接 ABI 门谁就是稳定面），`platform-api` 这个模块名不会存在；这是 `docs/DESIGN-M2.md` `ADR-M2-005` 要求的显式决策改名 | Bearisbug |
| 2026-09-16 | v0.21 | 登记里程碑分册 `docs/DESIGN-M2.md`：§1「关联」行加分册指引，§26 里程碑表加 M2 一行并补「M2 起每个里程碑一份分册」的规则段 | Bearisbug |
| 2026-09-16 | v0.20 | 审计修复第二轮：F29、F49、F11 按用户拍板修复合入（§28 三条改为已决），并发表「非 tick 线程卸载区块」行随新契约更新；§28 新增四条修复员与审查员发现的同源残留 | Bearisbug |
| 2026-09-15 | v0.19 | 审计修复轮：2026-09-15 审计的 60 条发现修复 57 条并合入 main（台账 `docs/research/audit-2026-09-15/fixes.md`，RUN-014 全量通过）；§28 新增三条待拍板项（F29、F49、F11 剩余半边） | Bearisbug |
| 2026-09-15 | v0.18 | 项目定名 Synaxis：包名 `io.github.bearisbug.synaxis`、品牌 `synaxis`、系统属性键 `synaxis.*`、断线原因键与负载通道命名空间 `synaxis:`、仓库 `Bearisbug/synaxis`；§28 包名问题关闭 | Bearisbug |
| 2026-09-13 | v0.17 | REQ-017 完成；协议错误日志技术债关闭；`deep-recursion` 语义随创造包跳过而变 | Bearisbug |
| 2026-09-13 | v0.16 | §15 增加输入分配上限（移植上游 2026-09-12 的三个加固 PR）；REQ-017 | Bearisbug |
| 2026-09-09 | v0.15 | M1.5 全部完成（RUN-011）：命名残留清理，主源码 1,108 文件，2,576 测试；§1 状态更新 | Bearisbug |
| 2026-09-09 | v0.14 | REQ-015 合入；阶段 6 完成 | Bearisbug |
| 2026-09-09 | v0.13 | REQ-015 按实际交付改写（9 包底层集合、移出 6 包及理由、`JvmFlags` 豁免）；§24 规则描述同步 | Bearisbug |
| 2026-09-09 | v0.12 | REQ-014 完成；`PacketViewableUtils` 改为进程级（技术债关闭）；记录 JVM 级开关的归属决定 | Bearisbug |
| 2026-09-09 | v0.11 | REQ-013 完成；§28 记录门面改造的四处偏离；`CommandPacketFilteringTest` 技术债关闭 | Bearisbug |
| 2026-09-09 | v0.10 | §26 阶段 0～3 标记完成状态；M1 仅剩两条人工用例 | Bearisbug |
| 2026-09-09 | v0.9 | M1 gate（RUN-007）：代码侧全部通过；REQ-004 并发拦截场景措辞改为「回归单测或 jcstress」 | Bearisbug |
| 2026-09-09 | v0.8 | 阶段 3 完成：包名 `dev.gameengine`、品牌 `game-engine`、NOTICE / README / pom；文档路径同步 | Bearisbug |
| 2026-09-09 | v0.7 | 阶段 2 完成；主源码阈值改为 ≤ 1,150（REQ-008 把 49 个生成文件并入主源码后口径变化，等价手写 ≤ 1,100） | Bearisbug |
| 2026-09-09 | v0.6 | REQ-001 / REQ-007 按实测改写（黄金 NBT、精简形式基线、39 JSON + 23 NBT）；REQ-006 补 13 个协议枚举宿主类的处理 | Bearisbug |
| 2026-09-09 | v0.5 | 阶段 2 第一轮完成（REQ-005 波次 1 除注册表相关包、REQ-006、`demo` 删除）；补三条技术债 | Bearisbug |
| 2026-09-09 | v0.4 | REQ-009 补服务端品牌字符串；`net.minestom.data` 依赖已随 REQ-001 移除 | Bearisbug |
| 2026-09-09 | v0.3 | `monitoring` 改为保留；M1.5 按用户指示视为确认；vendoring 实测 74 个 JSON（由 freeze-data 同步） | Bearisbug |
| 2026-09-09 | v0.2 | 用户确认 M1；`snapshot` 纳入删除波次 1；`command` 保留至 M2 评估；包名改名后置为阶段 3 的一次性操作；新增 M1.5 草案 REQ-013～016 与 §26 阶段 4～6（待确认） | Bearisbug |
| 2026-09-09 | v0.2.1 | 更正注册表数据规模与落点：数据 jar 内 85 个条目里实际是 74 个 JSON，vendoring 路径确定为 `src/main/resources/data` | Bearisbug |

## 2. 背景 / 目标 / 非目标

- 背景：我们要做一款跑在 Minecraft 客户端上的自研游戏（小游戏 + MMORPG + 肉鸽），不要任何原版玩法，客户端强制安装我们的 Fabric 模组。服务端以 Minestom（Apache-2.0，commit `16842f1954`）为起点硬 fork，不再跟随上游。审计发现 15 条 P0 与 48 条 P1，已在分支 `fork/main` 修复并合入（133 个提交，187 文件）。当前代码仍带着 1,412 个文件的原版玩法逻辑、上游包名、上游数据生成链和一个 GitHub fork 关系，这些都不属于我们。
- 目标：在一个独立维护的仓库里得到一个**只含我们需要的部分、以我们的名字命名、构建与验证链完全自控**的引擎，并证明原版客户端仍能登录进世界、完成一次自定义协议往返。
- 非目标：
  - 不做任何游戏玩法、不做 Fabric 客户端模组、不做美术资源（属于下一里程碑 M2「RTS 垂直切片」）。
  - 不拆 `Entity` / `Player` / `Instance` / `InstanceContainer` 上帝类（测试密度不足 0.3 时拆分等于无安全网作业，见 `ADR-007`）。
  - 不改 `MinecraftServer` 静态门面、不改 `ServerFlag`（属于 M1 之后的阶段 4 与 5）。
  - 不做代理层、多机部署、经济、反作弊、基岩版。
  - 不升 Minecraft 版本。

规模画像：

| 维度 | 估算 | 依据 / 假设（未知则写假设 + 重估阈值） |
| --- | --- | --- |
| 用户量 | 引擎的直接用户是本团队 1 到 3 名开发者；引擎最终承载的游戏内测规模为同时在线 ≤ 50 人 | 用户于 2026-09-09 选定「内测规模，单机单区」；超过 50 同时在线需重估线程模型与是否上代理 |
| 请求量 | 每玩家每 tick 约 1 到 5 个入站包，50 人峰值约 5,000 包/秒；tick 频率 20 Hz | 原版协议经验值；超过 20,000 包/秒需重估网络线程数 |
| 数据量 | 注册表数据 74 个 JSON 解压 17.6 MB 随包 vendoring；世界数据由 M2 决定格式（预烘焙地图整块载入），M1 不落盘任何世界 | 数据量假设 ≤ 100 MB/张地图；超过需重估载入策略 |
| 分布与形态 | 单机单进程单区，无 CDN | 用户选定 |

## 3. 成功指标 / 验收阈值

N/A — 画像:内部系统，本里程碑无产品指标与埋点事件；工程验收阈值统一放在 §19 验收与 §26 里程碑退出条件，避免第二套口径。

## 4. 术语表 / 统一语言

| 术语 | 定义 | 代码/字段标识 | 对应实体 |
| --- | --- | --- | --- |
| 引擎 | 本仓库，由 Minestom 硬 fork 演化而来的服务端库，不含任何游戏规则 | `engine` | — |
| 服务进程 | 一个引擎实例：网络监听、tick 调度、注册表、事件树的持有者 | `ServerProcess` | `ENT-ServerProcess` |
| 连接 | 一个客户端 socket 及其协议状态 | `PlayerConnection` | `ENT-Connection` |
| 世界实例 | 一组区块与实体的容器，可有多个并存 | `Instance` | `ENT-Instance` |
| 区块 | 16×16 列的世界切片，实体的线程归属单位 | `Chunk` | `ENT-Chunk` |
| 实体 | 世界中有位置的对象，含玩家 | `Entity` | `ENT-Entity` |
| 玩家 | 绑定一个连接的实体 | `Player` | `ENT-Player` |
| 注册表 | 配置阶段全量下发给客户端的数据表，如维度类型、生物群系 | `DynamicRegistry` | `ENT-Registry` |
| 不透明注册表 | 服务端不解析语义、只按原样转发的注册表 | `OpaqueRegistry` | `ENT-Registry` |
| 自定义负载 | Minecraft 协议的 `CustomPayload` 包，我们的协议全部走它 | `PluginMessagePacket` / `ClientPluginMessagePacket` | — |
| 线程归属 | 实体或区块被分配到哪个 tick 线程；跨线程访问必须先获取所有权 | `Acquirable` | — |
| 删除波次 | 按依赖强度分批删除原版玩法子系统的批次 | — | — |

## 5. 功能清单与需求 ID

| ID | 功能 | 对应目标 | 优先级 |
| --- | --- | --- | --- |
| REQ-001 | 冻结：Minecraft 26.2（协议 776）、JDK 25、Gradle 9.8.0 钉死；注册表数据 74 个 JSON 从 `net.minestom:data:26.2-rv3` 解出 vendoring 进 `src/main/resources/data`，移除该外部依赖 | 构建链自控 | P0 |
| REQ-002 | 仓库独立：断开 GitHub fork 与 `upstream` 远程，推送到 `Bearisbug/synaxis`，保留完整 git 历史；删除旧 fork；本地目录改名 `synaxis` | 独立维护 | P0 |
| REQ-003 | 止血：审计 15 条 P0 与 48 条 P1 的修复合入主干，全量测试绿 | 可用基线 | P0（已完成） |
| REQ-004 | CI 门禁：`check`（Error Prone 96 项 `-Werror`、命名检查、禁止 import 检查）+ jcstress 并发场景 + ArchUnit 分层规则 + 新增行覆盖率门 | 防回退 | P0 |
| REQ-005 | 删除波次 1：21 个原版玩法子包共 157 文件约 12,660 行整包删除（寻路、AI、Anvil、batch、配方、进度、统计、对话、地图、ping、extras、计分板、调试、附魔、盔甲纹饰、乐器、书、弩、载具、`utils/nbt`、`snapshot`）；其中 `ping` 的服务器列表能力已由生产就绪第 6 波 6D 以新形态加回：MOTD、64×64 图标、玩家样本与可改的 `ServerListPingEvent`（`network/StatusResponse`、`network/Favicon`、`event/server/ServerListPingEvent`，见 `docs/DESIGN-PROD.md` `REQ-PROD-037` / `API-PROD-024`），`ping` 包本身、多版本响应格式与 legacy ping 不恢复；`monitoring` 保留，§21 的 tick 监控依赖它，替代品属 M2。删包不得改变其余包的 ID：包 ID 是包在注册表里的位置，删掉的 clientbound 包由 `ReservedPacket` 占住原位，六张协议表与 26.2 原版逐个一致（§28 的包 ID 那一行）| 只含需要的部分 | P0 |
| REQ-006 | 删除波次 2：`entity/metadata` 下 10 个原版生物子包共 181 文件 6,357 行删除，保留基类与 display / avatar / projectile / item 四组及 `ArmorStandMeta`、`InteractionMeta`；13 个仅作为物品组件 / 元数据槽位枚举宿主的 `*Meta` 类，把枚举抽到协议侧宿主后删壳（枚举同时定义 VarInt 序数与 NBT 名并参与 `ItemStack` 哈希，线格式不变） | 只含需要的部分 | P0 |
| REQ-007 | 不透明注册表：32 个注册表中仅 `dimensionType`、`biome`、`chatType`、`damageType` 保留服务端语义，其余 28 个降级为 `RegistryKey → BinaryTag` 原样转发（实现为「黄金 NBT」：删除类型前用原类型化 codec 编码一次固化为 `data/opaque/*.nbt`，JSON 直通因无法表达 NBT 数值类型被实测否决）；改造前后 `RegistryDataPacket` 字节序列逐字节相同（基线为客户端声明已有原版数据包时的精简形式，完整形式因 Adventure NBT compound 的 map 顺序按进程随机盐而天然不稳定，另以结构比对覆盖） | 协议行为不变 | P0 |
| REQ-008 | 生成链收编：`src/autogenerated` 26,126 行转为普通源码；删除 `code-generators` 与 `demo` 模块，同时新增最小启动器模块 `smoke`（一个 `main`：启动进程、一个平坦世界、注册 `synaxis:main` 的 echo 处理器）作为冒烟入口 | 构建链自控 | P1 |
| REQ-009 | 命名空间：`net.minestom.server` → 我们的包名（`io.github.bearisbug.synaxis`，见 §28），单独提交，`.git-blame-ignore-revs` 登记；GraalVM 可达性元数据单独处理；服务端品牌字符串（客户端 F3 显示）同步改为 `synaxis` | 以我们的名字命名 | P0 |
| REQ-010 | Apache-2.0 合规：`LICENSE` 原样保留；新建 `NOTICE` 声明派生自 Minestom 与起点 commit；README 顶部显著变更声明；pom 元数据换成我们的 | 合法分发 | P0 |
| REQ-011 | 冒烟：原版 26.2 客户端在核心模式（`-Dsynaxis.app.core-mode=on`）下走完 login → configuration → play 进入世界并断开；协议探针 `tools/protocol-probe/probe.py` 经自定义负载通道 `synaxis:main` 完成一次 echo 往返 | 证明仍可用 | P0 |
| REQ-012 | 运行手册：`docs/ENGINE.md` 写清 JDK、镜像脚本、分支策略、升版本 checklist；集成测试基类 teardown 清理 `$TMPDIR/minestom-test-world-*` | 冷启动可用 | P1 |
| REQ-013 | M1.5 · 静态门面实例化：`MinecraftServer` 静态访问（实测 142 调用点 / 56 文件）改为 `ServerProcess` 实例（`ServerProcess.of(auth)` 工厂，`Instance`/`Entity`/`PlayerConnection`/`CommandSender` 暴露 `process()`，`EventDispatcher` 删除）；`@EnvTest` 类级并行。已完成 | 可维护、可测 | P2 |
| REQ-014 | M1.5 · 配置对象：`ServerFlag`（实测 55 个开关）删除；37 个进程级开关进 `ServerConfig`（`process.config()`，系统属性键 `synaxis.*` 同默认值），10 个结构上 JVM 级的进 `JvmFlags` 并逐条注明原因，4 个归各自的包，4 个死开关删除；测试与生产同一份默认值（`ServerConfigTest` 断言 `defaults() == fromSystemProperties()`）。已完成。生产就绪之后的现状（全部键见 `docs/DESIGN-PROD.md` `API-PROD-023` 键表）：`ServerConfig` 有 72 个进程级键，其中第 6 波 6C 加的 `synaxis.compression-threshold`（默认 256，0 关闭压缩）原先是 `ServerProcessImpl` 里写死的初值；`JvmFlags` 有 12 项，6C 加的 `synaxis.compression-level`（默认 6，取 0～9）是 JVM 级，因为 `Deflater` 池是全 JVM 共用的静态池，另 4 项 JVM 级开关仍归各自的包。`server-app` 起服前读工作目录的 `synaxis.properties`（或 `-Dsynaxis.config-file` 指的文件），键与系统属性逐字相同、命令行优先，引擎的 `ServerProcess.of()` 与 `MinecraftServer.init()` 不读文件。自该分册 v0.9.11 起，`fromSystemProperties` 读的 15 个布尔键只认小写的 `true` / `false`，与值逐字比较，别的写法（带尾随空格的 `true `、`TRUE`、`yes`、空值）抛 `IllegalArgumentException`、不再当 false，嵌入方经 `ServerProcess.of()` 以 `-D` 传这些键时同样生效；`JvmFlags` 等 JVM 级布尔键仍按 `Boolean.parseBoolean` 或 `Boolean.getBoolean` 读（`true` 不分大小写，其余为 false） | 可维护 | P2 |
| REQ-015 | M1.5 · 分层：底层集合最终为 `codec、condition、coordinate、exception、gamedata、monitoring、thread、timer、utils` 9 个包，不得依赖集合之外的引擎包（实测起点 35 条边 260 条 import，终点 0）；`registry`、`color`、`component`、`crypto`、`sound`、`tag` 因各自声明 `NetworkBuffer.Type` 或直接产协议包而移出集合（理由在 `ArchitectureTest` javadoc）；`utils` 内 22 个错放类归位；ArchUnit 由「冻结」改为「禁止」，冻结存储删除；根包 `JvmFlags` 为唯一豁免；`tools/pkgdeps.py` 入库复核。已完成并合入 | 可读、可扩展 | P2 |
| REQ-017 | 上游加固移植：按可读字节限定集合预分配、不可信 NBT 解码限额与错误信息截断、非创造玩家跳过创造包解码、`DataComponentMap` 与注册表标签读取校验、`ArgumentNumber` 去 radix、依赖升级；协议错误改单行 WARN 日志，原因字段至多 1,024 个字符、超出部分只记总长，`Transcoder` 的默认数组读取失败时只报出错的元素与下标、不带整个值（`docs/DESIGN-PROD.md` v0.9.94）。已完成（2026-09-13，+26 测试）。第二批由生产就绪会话移植（`docs/DESIGN-PROD.md` v0.9.55，2026-10-06，上游 master 核对到 `fcd35c05d9`，用例 `docs/TEST.md` `TC-PROD-680`～`TC-PROD-687`）：#3382（`89399c65ce`）世界边界的过渡时间按 tick 发，五个世界边界包按 26.2 改字段；#3304 里 `FilteredText` 的四个编解码器与 `DataComponentPredicate.Damage.CODEC` 改为 final；#3249（`024de826c7`）装备槽全空时不发装备包；#3400（`268866b01b`）与 #3388 悬停物品组件按缓冲区带的注册表编码，`NbtComponentSerializer` 加 `nbt(Registries)`；#3373 导出 `item.predicate`；logback 升到 1.6.5 | 抗恶意客户端 | P1 |
| REQ-016 | M1.5 · 拆 `NetworkBufferTypeImpl`（拆前 1,297 行）为按类型族分文件的实现，零 API 影响。已完成：枢纽剩 15 行，10 个 package-private 持有类 | 可读 | P3 |

## 6. 总体架构

```mermaid
flowchart LR
  subgraph client[客户端]
    vanilla[原版 Minecraft 26.2]
    fabric[Fabric 模组 · M2 才有]
  end
  subgraph engine[Synaxis 服务进程]
    net[网络层<br/>socket · 帧解析 · 状态机]
    reg[注册表<br/>4 个语义注册表 + 28 个不透明注册表]
    world[世界层<br/>Instance · Chunk · Entity · Player]
    tick[线程模型<br/>ThreadDispatcher · Acquirable · Scheduler]
    ev[事件树 EventNode]
    payload[自定义负载通道]
  end
  subgraph build[构建与验证链]
    gradle[Gradle 9.8.0 · JDK 25<br/>阿里云镜像 init 脚本]
    ci[GitHub Actions<br/>check · jcstress · ArchUnit · 覆盖率]
    data[vendoring 的 74 个注册表 JSON]
  end
  vanilla -- "Minecraft 协议 776" --> net
  fabric -. "CustomPayload · M2" .-> payload
  net --> reg
  net --> world
  world --> tick
  world --> ev
  payload --> ev
  data --> reg
  gradle --> ci
  serverapp[server-app · M2 的游戏工程] -. "Java API" .-> engine
```

## 7. 数据模型 / 领域实体

```mermaid
erDiagram
  SERVER_PROCESS ||--o{ INSTANCE : owns
  SERVER_PROCESS ||--o{ CONNECTION : accepts
  SERVER_PROCESS ||--o{ REGISTRY : holds
  REGISTRY ||--o{ REGISTRY_ENTRY : contains
  INSTANCE ||--o{ CHUNK : loads
  INSTANCE ||--o{ ENTITY : tracks
  CHUNK ||--o{ ENTITY : partitions
  ENTITY ||--o| PLAYER : "is a"
  PLAYER ||--|| CONNECTION : bound_to
  SERVER_PROCESS {
    string lifecycle_state
    long tick_count
  }
  CONNECTION {
    uuid connection_id PK
    string protocol_state
    string remote_address
  }
  REGISTRY {
    string key PK
    bool opaque
  }
  REGISTRY_ENTRY {
    string key PK
    string registry_key FK
    bytes nbt_payload
  }
  INSTANCE {
    uuid instance_id PK
    string dimension_type FK
  }
  CHUNK {
    int chunk_x PK
    int chunk_z PK
    uuid instance_id FK
    string tick_thread
  }
  ENTITY {
    int entity_id PK
    uuid instance_id FK
    string entity_type
    string assigned_thread
  }
  PLAYER {
    int entity_id PK
    uuid connection_id FK
  }
```

实体 ID：`ENT-ServerProcess`、`ENT-Connection`、`ENT-Registry`（含 `REGISTRY_ENTRY`）、`ENT-Instance`、`ENT-Chunk`、`ENT-Entity`、`ENT-Player`。字段完整定义见 §9。

## 8. 接口契约 (API-first)

本引擎没有 HTTP 接口。对外契约有两类：面向客户端的 Minecraft 二进制协议，面向游戏工程的 Java API。契约文件：协议以 Minecraft 26.2 协议 776 的公开规范为准（`https://minecraft.wiki/w/Java_Edition_protocol`），Java API 以模块 `engine` 导出的包为准。

- **`API-001` 原版协议会话** — 实现 `REQ-011`、`REQ-007`
  - 方法/路径：TCP 25565，帧格式 `VarInt 长度 + 包 ID + 负载`，压缩阈值 256 字节，握手后按 §11 连接状态机推进
  - 鉴权/权限：`ROLE-Client`；在线 / 离线两种模式，见 `docs/DESIGN-PROD.md` `API-PROD-001`；离线模式下按用户名，见 §10
  - 请求：客户端按协议 776 发送 Handshake、LoginStart、KnownPacks、FinishConfiguration 等
  - 响应：LoginSuccess、`RegistryDataPacket`（内容来自 `ENT-Registry`，不透明注册表原样转发）、FinishConfiguration、JoinGame
  - 分页/幂等/版本：协议版本固定 776，不支持其他版本，握手声明其他版本必须断线并给出 `synaxis:unsupported-version`（见 §14）
  - 缓存与超时：`RegistryDataPacket` 在进程内缓存一份，注册表冻结后不再变化；登录阶段总超时 30 秒（§15）
- **`API-002` 自定义负载通道** — 实现 `REQ-011`
  - 方法/路径：`CustomPayload` 包，通道标识 `synaxis:main`，负载为我们的二进制消息；M1 只定义一个 `echo` 消息：客户端发任意 ≤ 1,024 字节，服务端原样回发
  - 鉴权/权限：`play` 与 `configuration` 两个状态都可用——`ClientPluginMessagePacket` 同时实现 `ClientPacket.Configuration` 与 `ClientPacket.Play`，两个状态都注册了监听器，两边都派发 `PlayerPluginMessageEvent`；`handshake` / `status` / `login` 状态没有这个包，收到即按帧解码失败断线 `synaxis:protocol-error`。包里的通道名只限长度（≤ 256 字符），不是合法 identifier（`Key.parseable` 判）时监听器不派发事件、以 `synaxis:protocol-error` 断线，与 cookie 应答的键同一判法；原版与 Fabric 客户端只发合法 identifier。（原文写作「仅 `play` 状态可用」，与实现不符；`docs/DESIGN-M2.md` 的 `ADR-M2-010` 正是建立在 configuration 阶段也派发这个事件上，该文档 §28 把这句登记为阶段 4 要更正的技术债。）
  - 请求/响应：见 §12 时序；消息编解码用引擎 `codec` 包，往返测试向量放 `src/test/resources/protocol/`
  - 幂等：`echo` 天然幂等；后续消息由 M2 的 `protocol` 模块定义
  - 缓存与超时：不缓存；单条负载超过 1,024 字节断线
- **`API-003` 服务进程生命周期 Java API** — 实现 `REQ-003`、`REQ-011`
  - 方法：`ServerProcess.start(address)`、`ServerProcess.startTicking()`、`boolean ServerProcess.stop()`、`boolean ServerProcess.beginShutdown(Duration countdown)`、`ServerProcess.setShutdownParticipant(ShutdownParticipant)`、`ServerProcess.isAlive()`
  - 约束：两者都按 `docs/DESIGN-PROD.md` §11.4 的顺序停机，都以 CAS 把 `stopping` 由 false 置为 true 来占下停机，返回值就是这次 CAS 的结果：整个进程只有一次调用得 true，之后的调用（看门狗判死路径已占下之后也一样）立即返回 false，不等那次停机、不改它的倒计时。`beginShutdown(countdown)` 占下之后在新起的非 daemon 平台线程 `Syn-Shutdown` 上跑序列，从任何线程调用都立即返回，所以调用方能先拿到结果再回复（宿主的控制台 `stop` 与管理接口 `minecraft:server/stop`，该分册 `API-PROD-011`、`API-PROD-022`）；`countdown` 为负或长于 `Long.MAX_VALUE` 纳秒（约 292 年）抛 `IllegalArgumentException`、不占停机。`stop()` 以 0 s 倒计时占下停机：从 tick 线程（`TickThread` 或 tick 调度线程）调用时同样另起 `Syn-Shutdown`、立即返回（对应审计 P0 停服死锁，已修）；从其他线程调用时在调用线程上跑完序列，等进程停下（监听关闭、tick 线程 join）再返回，SIGTERM 的关闭钩子走这一条。`Syn-Shutdown` 不是 daemon：序列 join 完 tick 线程之后，JVM 不会在它跑完之前因为只剩 daemon 线程而退出；但信号触发的退出不看非 daemon 线程，JVM 跑完关闭钩子就 halt。所以关闭钩子（`ServerProcessImpl#stopOnSignal`）的 `stop()` 得 false 时（停机已由 `beginShutdown` 或看门狗判死路径占下），钩子结束剩余倒计时（该分册 §11.4 `gate_closed → saving` 的 SIGTERM 一行），再等那次序列跑完才返回，保存、踢人与插件禁用都做完 JVM 才退出。`isAlive()` 所读的 `stopped` 推迟到该节最后一步才置位，保存、踢人、插件禁用期间 tick 照常运行。`setShutdownParticipant` 只能在 `start` 之前调用一次，重复或晚于 `start` 抛 `IllegalStateException`；不注册时保存阶段立即结束（`ServerProcess.java:205-258`）。停机任务（`SchedulerManager#buildShutdownTask`）在该分册 §11.4 的 `plugins_disabled` 阶段按注册顺序各执行一次，某个任务抛异常交给异常处理器，后面的任务照常执行，停机序列照常走到 `stopped`。`start(address)` 只绑定端口、开始接受连接，不 tick；`startTicking()` 在它之后调用一次，起 dispatcher 的 tick 线程与 tick 调度线程；tick 线程已经由 `dispatcher().start()` 起了就只起 tick 调度线程（测试可以先起 tick 线程、等区块载入完，再开始 tick），没 `start` 或重复调用抛 `IllegalStateException`，tick 线程起过又停了的由 `ThreadDispatcher#start` 抛 `IllegalThreadStateException`；`MinecraftServer#start` 两个都调，直接用 `ServerProcess.of()` 的嵌入方自己调，手动 tick 的测试（`Env`）两个都不调
  - 错误：绑定失败抛 `IOException`，进程进入 `stopped`（§11）
- **`API-004` 注册表访问 Java API** — 实现 `REQ-007`
  - 方法：`Registries.dimensionType()` 等 4 个语义注册表返回类型化对象；`Registries.opaque(key)` 返回 `RegistryKey → BinaryTag`
  - 约束：注册表在 `start()` 后冻结，写入抛 `IllegalStateException`，含 tag 集合（`getOrCreateTag` 新建、`removeTag`）；已有的 tag 照常查到
- **`API-005` 背包界面与点击 Java API** — 实现 `REQ-003`
  - 方法：`Player#openInventory(Inventory)` 打开界面，`Player#closeInventory()` 关闭；`Inventory#addViewer`、`Inventory#removeViewer` 是这两个方法调用的钩子，子类可以覆写来补发包（`VillagerInventory` 在 `addViewer` 里补发交易列表）。
  - 打开：`Player#openInventory` 先取出 `InventoryOpenEvent` 定下的界面，再摘掉旧界面的 viewer，把玩家打开的界面记成新界面，最后调新界面的 `addViewer`。不经 `openInventory` 直接调 `Inventory#addViewer`（玩家打开的界面不是它）抛 `IllegalStateException`，什么都不发；原先照发 `OpenWindowPacket`，客户端弹出一个服务端不认的窗口，窗口里的点击全被丢弃，断线时这名 viewer 也不移除。直接调 `removeViewer` 只摘 viewer、丢出光标，玩家打开的界面仍指向它、客户端窗口不关，要关界面用 `closeInventory`。`InventoryOpenEvent#setInventory` 只收 `Inventory`：传 null 抛 `NullPointerException`，传玩家背包抛 `IllegalArgumentException`，事件里的界面不变；不想打开就取消事件。
  - 点击事务：`AbstractInventory#handleClick` 先锁被点的界面、再锁点击者的背包，整笔点击在这两把监视器里完成，先写格子、后写光标。点击产生的 `InventoryClickEvent` 与 `InventoryItemChangeEvent` 推迟到整笔点击（光标在内）提交、两把监视器放开之后，在处理这次点击的线程上按产生的顺序派发，监听器在里面关或开界面时丢出的是提交后的光标，物品总量不变；原先写完一格就派发，监听器关界面把还没扣减的光标丢出（或在丢弃被取消时塞回背包），点击随后照原先算好的结果写光标，丢出或塞回的那份就多了出来。丢弃类点击（Q 键、窗口外丢光标）的 `ItemDropEvent` 决定这次点击怎么写，仍在点击里同步派发；监听器在里面关界面时，取光标的那一步（`Player#dropCursorItem`）排到点击提交之后，读到的同样是提交后的光标。不经点击、直接调 `setItemStack` 等方法写格子时，`InventoryItemChangeEvent` 照旧当场派发。`InventoryItemChangeEvent#getNewItem` 是格子里实际存下的物品。
  - 装备事件：玩家背包上写盔甲位、副手与选中的快捷栏格时派发的 `EntityEquipEvent` 在背包监视器里、点击提交之前同步派发，监听器不得开关界面；它在玩家背包上只接受材料与数量都不变的替换，可以改组件（`docs/DESIGN-PROD.md` `API-PROD-018`），别的生物身上照旧原样写入。
  - 锁：打开已绑定进程的界面不取它的监视器（只有第一次绑定时取）。`processItemStack`、`replaceItemStack`、`clear` 在界面的监视器里派发 `InventoryItemChangeEvent`，这些监听器与 `EntityEquipEvent` 的监听器不得同步写别的界面或别的玩家背包：两条 tick 线程交叉这样写会互等对方的监视器，要写就排到下一个 tick（`Entity#scheduleNextTick`）。

演进与弃用：

- 版本方案：日期版本 `YYYY.MM.DD-26.2`（沿用上游格式，MC 版本后缀固定） · 当前：M1 完成时打 `2026.xx.xx-26.2`
- 弃用期：Java API 无外部消费者，M1 内允许直接破坏；M2 起 `plugin-api` 成为唯一稳定面，引擎内部 API 不承诺兼容
- 兼容承诺对象：仅本仓库内的游戏工程
- 当前 deprecated 清单：无

## 9. 数据字典

| 字段 | API 名 | DB 名 | 类型 | 必填 | 枚举/约束 | 单位/精度 | PII |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 进程生命周期状态 | `lifecycle_state` | — | enum | 是 | created / started / stopping / stopped | — | 否 |
| tick 计数 | `tick_count` | — | long | 是 | ≥ 0，每 50 ms 加 1 | tick | 否 |
| 连接 ID | `connection_id` | — | uuid | 是 | 进程内唯一，用于日志关联（§14） | — | 否 |
| 协议状态 | `protocol_state` | — | enum | 是 | handshake / status / login / configuration / play / disconnected | — | 否 |
| 远端地址 | `remote_address` | — | string | 是 | IPv4/IPv6 加端口 | — | 是（日志脱敏到 /24） |
| 注册表键 | `key` | — | string | 是 | `minecraft:` 命名空间，26.2 共 32 个 | — | 否 |
| 是否不透明 | `opaque` | — | bool | 是 | 4 个语义注册表为 false，其余 28 个为 true | — | 否 |
| 注册表条目负载 | `nbt_payload` | — | bytes | 是 | NBT 二进制，原样来自 vendoring 的 JSON 转换 | — | 否 |
| 世界实例 ID | `instance_id` | — | uuid | 是 | 进程内唯一 | — | 否 |
| 维度类型 | `dimension_type` | — | string | 是 | 引用 `dimensionType` 注册表键 | — | 否 |
| 区块坐标 | `chunk_x` / `chunk_z` | — | int | 是 | 区块坐标，非方块坐标 | 区块 | 否 |
| tick 线程 | `tick_thread` / `assigned_thread` | — | string | 否 | `Ms-Tick-N`，未分配为 null | — | 否 |
| 实体 ID | `entity_id` | — | int | 是 | 进程内自增，协议要求 int | — | 否 |
| 实体类型 | `entity_type` | — | string | 是 | 引用 `entity_type` 注册表；M1 删除波次 2 后服务端只保留 display / avatar / projectile / item 四组元数据类型，其余类型仍可生成但无类型化元数据。运行中换类型（`Entity#switchEntityType`）只保留各类型共有的元数据，之前取到的 `EntityMeta` 随之失效，再写入抛 `IllegalStateException`（`docs/DESIGN-PROD.md` v0.9.96） | — | 否 |
| 断线原因码 | `disconnect_reason` | — | enum | 是 | 见 §14 错误码表 | — | 否 |

## 10. 权限与角色

| 角色 \ 操作 | `API-001` 协议会话 | `API-002` 自定义负载 | `API-003` 进程生命周期 | `API-004` 注册表访问 |
| --- | --- | --- | --- | --- |
| ROLE-Client（任意原版客户端） | ✓ | ✓（仅 play 状态） | ✗ | ✗ |
| ROLE-GameApp（我们的游戏工程，进程内 Java 调用） | ✗ | ✓（服务端侧发送） | ✓ | ✓ |
| ROLE-Operator（运维，通过 server-app 命令行） | ✗ | ✗ | ✓（stop） | ✗ |

行级可见性：不适用，M1 无按角色过滤的读接口。`API-001`/`API-002` 的「鉴权」是协议状态机守卫而非登录主体，已在矩阵中按状态标注。

## 11. 状态机

### 11.1 连接 `ENT-Connection`

```mermaid
stateDiagram-v2
  [*] --> handshake : TCP 接入(客户端)
  handshake --> status : Handshake next=1(客户端) [协议版本任意]
  handshake --> login : Handshake next=2(客户端) [协议版本=776]
  handshake --> disconnected : Handshake next=2(客户端) [协议版本≠776]
  status --> disconnected : StatusRequest 已应答(客户端) [已应答一次]
  login --> configuration : LoginAcknowledged(客户端) [LoginSuccess 已发 且 profile UUID 空闲]
  login --> disconnected : LoginAcknowledged(客户端) [profile UUID 已被在线连接占用]
  login --> disconnected : LoginAcknowledged(客户端) [LoginSuccess 未发]
  login --> disconnected : 登录超时(系统定时器) [now-连接建立>30s]
  configuration --> play : FinishConfiguration(客户端) [服务端已发 FinishConfiguration]
  configuration --> disconnected : FinishConfiguration(客户端) [服务端未发 FinishConfiguration]
  configuration --> disconnected : 确认超时(系统) [服务端已发 FinishConfiguration 且 keepAliveKick 内未确认]
  play --> configuration : ConfigurationAck(客户端) [服务端已请求切回]
  play --> disconnected : ConfigurationAck(客户端) [服务端未请求切回]
  play --> disconnected : 协议错误(系统) [帧长度=0 或 >上限 或 递归深度>64]
  play --> disconnected : 客户端断开(客户端)
  disconnected --> [*]
```

迁移表：

| 源 → 目标 | 事件 | 守卫（纯布尔） | 动作（副作用） | 谁触发 | 接口/事件 |
| --- | --- | --- | --- | --- | --- |
| [*] → handshake | TCP 接入 | — | 分配 `connection_id`，记日志 | 客户端 | `API-001` |
| handshake → status | Handshake next=1 | 协议版本任意 | — | 客户端 | `API-001` |
| handshake → login | Handshake next=2 | 协议版本 = 776 | — | 客户端 | `API-001` |
| handshake → disconnected | Handshake next=2 | 协议版本 ≠ 776 | 发 LoginDisconnect `synaxis:unsupported-version` | 客户端 | `API-001` |
| status → disconnected | StatusRequest 已应答 | 已应答一次 | 第二次 StatusRequest 直接关闭（审计网络层修复） | 客户端 | `API-001` |
| login → configuration | LoginAcknowledged | LoginSuccess 已发 且 profile UUID 未被在线连接占用 | 创建 `ENT-Player` 并占用该 UUID，发 KnownPacks、`RegistryDataPacket` | 客户端 | `API-001` |
| login → disconnected | LoginAcknowledged | profile UUID 已被在线连接占用 | 断线 `synaxis:duplicate-login`，不创建 `ENT-Player`；被拒的是**后来者**，在线会话不受影响（离线模式下 UUID 由用户名派生，踢老的等于给了任何人顶掉别人的手段）。旧连接尚未清理时的重连同样被拒，客户端重试即可——僵死会话由 keep-alive 在 `keepAliveKick` 内清掉，占用窗口有界 | 客户端 | `API-001` |
| login → disconnected | LoginAcknowledged | LoginSuccess 未发（还没有 profile，或服务端仍在 login 态） | 断线 `synaxis:protocol-error`，不创建 `ENT-Player`、不交异常处理器（`docs/DESIGN-PROD.md` v0.9.94） | 客户端 | `API-001` |
| login → disconnected | 登录超时 | now − 连接建立 > 30 s | 关闭 socket | 系统定时器 | 内部任务 `connection.login-timeout`（无对外 API） |
| configuration → play | FinishConfiguration | 服务端已发 FinishConfiguration | 创建 `ENT-Player`，发 JoinGame，进入世界 | 客户端 | `API-001` |
| configuration → disconnected | FinishConfiguration | 服务端未发 FinishConfiguration | 断线 `synaxis:protocol-error`（审计网络层 P0-1 修复：抢跑不再 NPE） | 客户端 | `API-001` |
| configuration → disconnected | 确认超时 | 服务端已发 FinishConfiguration 且 发出后 `keepAliveKick` 内没收到 FinishConfiguration | 断线 `synaxis:keep-alive-timeout`（这段时间不发 keep-alive） | 系统（tick） | `API-001` |
| play → configuration | ConfigurationAck | 服务端已请求切回 | 移除玩家实体，保留连接；ack 由读线程收到即处理（`PlayerSocketConnection.IMMEDIATE_PROCESS_PACKETS`），下一次读就按 configuration 解码；ack 到达时还留在玩家包队列里的包到时按 configuration 态处理（更早一个 tick 末尾已取走的仍按 play 态处理、派发 `PlayerPacketEvent`），两态共用的包（客户端设置、插件消息、资源包状态、自定义点击动作、pong）交 configuration 态的监听器、不派发 `PlayerPacketEvent`（pong 的监听器为空），play 态专有的包找不到监听器（每个包类警告一次）、丢弃（`docs/DESIGN-PROD.md` v0.9.94） | 客户端 | `API-001` |
| play → disconnected | ConfigurationAck | 服务端未请求切回 | 断线 `synaxis:protocol-error` | 客户端 | `API-001` |
| play → disconnected | 协议错误 | 帧长度 = 0 或 > 上限 或 递归深度 > 64 | 断线 `synaxis:protocol-error`，记 `connection_id` | 系统 | 内部任务 `connection.frame-guard`（无对外 API） |
| play → disconnected | 客户端断开 | — | 移除玩家实体，触发 `PlayerDisconnectEvent`（恰好一次，含尚未进世界的玩家，见表后） | 客户端 | `API-001` |

`login` 态内部的闸门、加密握手与会话验证展开在 `docs/DESIGN-PROD.md` §11.1（`ENT-LoginAttempt`）；本机只画 `login` 的进出边。

断线口径：每个被创建的 `ENT-Player` 离开时，引擎恰好派发一次 `PlayerDisconnectEvent`。服务端写出 `FinishConfigurationPacket` 时连接已是 PLAY 状态，玩家要到之后某个 tick 才由 `ConnectionManager#updateWaitingPlayers` 放进世界；这段时间里断开，`PlayerConnection#disconnect` 把实体移除排到下一 tick 的同时，把玩家交给 `ConnectionManager#leftBeforeSpawn`（`PlayerConnection.java:289-291`）。下一 tick 的 `updateWaitingPlayers` 先处理等待进世界的玩家，再在同一 tick 线程上逐个检查这批断开的玩家：还没开始进世界的（`Player#dropPendingSpawn` 为真，同时作废待进的世界），由这里派发事件；已开始进世界的跳过，由实体移除派发（`ConnectionManager.java:828-832`）。首次出生在玩家进实例之前失败的（出生流程同步抛出，`Player#UNSAFE_init` 把它转成异常完成的 future；或 future 异常完成时玩家还不在任何实例里，如出生区块载入失败）同样由这里派发：`ConnectionManager` 以 `synaxis:internal-error` 踢掉它，再把它交给一次 `leftBeforeSpawn`（客户端在失败之前就已断开的，早先那次检查因它正在出生而跳过，由这一次补上）；`Player#dropPendingSpawn` 对它只返回一次真，同时向在线玩家广播 `PlayerInfoRemovePacket`（`docs/DESIGN-PROD.md` v0.9.87，`docs/TEST.md` `TC-PROD-793`、`TC-PROD-794`、`TC-PROD-795`）。没有这一步时，不在任何实例里的实体不会 tick，事件永不派发，宿主存储的记录停在 `online`、租约不释放（`docs/DESIGN-PROD.md` §11.3 `online → saving`）。回归用例 `PlayerRemoveIntegrationTest`（commit `b2b7af2a3c`）。读线程、写线程与 tick 可能同时断开同一条连接，派发与否看的是谁把玩家从 `ConnectionManager` 摘掉：`ConnectionManager#dropPlayer` 在它的监视器里摘人并返回摘到的玩家，只有拿到玩家的那一次调用派发或排移除。

configuration 态的玩家收不到 play 包：从 `Player#UNSAFE_init` 把 JoinGame 排进发送队列起，到 `Player#remove` 为止（切回 configuration 时 `remove(false)` 先于 StartConfiguration 入队），`Player` 只发 play 包的 setter（生命值、饱食度、经验与等级、手持格、飞行等能力、调试信息、重生界面）才发包，此外只记值；进世界时 JoinGame 之后补发能力与经验，手持格随出生发，生命值与饱食度照旧回满；`Player#sendMessage` 在这段时间之外丢弃消息。原先这些 setter 无条件发包，play 包排到 configuration 态的连接上，写线程抛异常断开玩家。

### 11.2 服务进程 `ENT-ServerProcess`

```mermaid
stateDiagram-v2
  [*] --> created : 构造(ROLE-GameApp)
  created --> started : start(ROLE-GameApp) [端口绑定成功]
  created --> stopped : start(ROLE-GameApp) [端口绑定失败]
  started --> stopping : stop(ROLE-GameApp 或 ROLE-Operator) [stopping CAS 成功 ∧ 调用线程非 tick 线程]
  started --> stopping : stop(ROLE-GameApp 或 ROLE-Operator) [stopping CAS 成功 ∧ 调用线程是 tick 线程]
  started --> stopping : beginShutdown(ROLE-GameApp 或 ROLE-Operator) [stopping CAS 成功]
  stopping --> stopping : stop 或 beginShutdown(ROLE-GameApp 或 ROLE-Operator) [stopping 已为 true]
  stopping --> stopping : SIGTERM 关闭钩子(ROLE-Operator) [stopping 已为 true]
  stopping --> stopped : 关闭完成(系统) [监听已关且 tick 线程已 join]
  stopped --> [*]
```

迁移表：

| 源 → 目标 | 事件 | 守卫（纯布尔） | 动作（副作用） | 谁触发 | 接口/事件 |
| --- | --- | --- | --- | --- | --- |
| [*] → created | 构造 | — | 加载 vendoring 注册表，冻结 | ROLE-GameApp | `API-003` |
| created → started | start | 端口绑定成功 | 启动网络线程、tick 调度线程 | ROLE-GameApp | `API-003` |
| created → stopped | start | 端口绑定失败 | 抛 `IOException`，释放已建线程 | ROLE-GameApp | `API-003` |
| started → stopping | stop | `stopping` CAS 成功 ∧ 调用线程非 tick 线程 | 同步执行关闭序列，进程停下后返回 true | ROLE-GameApp / ROLE-Operator | `API-003` |
| started → stopping | stop | `stopping` CAS 成功 ∧ 调用线程是 tick 线程 | 把关闭序列投递到非 tick 线程 `Syn-Shutdown` 执行，本次调用立即返回 true（审计 P0-1 修复） | ROLE-GameApp / ROLE-Operator | `API-003` |
| started → stopping | beginShutdown | `stopping` CAS 成功 | 在新起的非 daemon 线程 `Syn-Shutdown` 上执行关闭序列（先关登录门，再按 `countdown` 倒计时），本次调用立即返回 true | ROLE-GameApp / ROLE-Operator | `API-003` |
| stopping → stopping | stop 或 beginShutdown | `stopping` 已为 true（CAS 失败） | 立即返回 false；不等正在跑的关闭序列，不改它的倒计时 | ROLE-GameApp / ROLE-Operator | `API-003` |
| stopping → stopping | SIGTERM（关闭钩子） | `stopping` 已为 true（钩子里的 `stop()` 得 false） | 结束剩余倒计时（该分册 §11.4 `gate_closed → saving` 的 SIGTERM 一行），等正在跑的关闭序列跑完才返回：JVM 跑完关闭钩子就 halt，不等 `Syn-Shutdown` | ROLE-Operator（发信号） | `API-003` |
| stopping → stopped | 关闭完成 | 监听已关且 tick 线程已 join | 依次关登录门、倒计时、保存、踢人、执行 scheduler shutdown 任务（含插件禁用），最后置 `stopped`、join tick 线程、关监听 | 系统 | 内部任务 `process.shutdown`（无对外 API） |

`stopping` 由 `docs/DESIGN-PROD.md` §11.4 展开为关登录门、倒计时、保存、踢人、插件禁用五个阶段，关闭顺序与各阶段预算以该节为准（实现 `ServerProcessImpl#shutdownProcess`）；上表三条 `started → stopping` 与两条 `stopping → stopping` 判的是同一个 `stopping` 标志，该分册 §11.4 的看门狗判死路径也以它占下停机。

其余实体：`ENT-Instance`、`ENT-Chunk`、`ENT-Entity`、`ENT-Player`、`ENT-Registry` 的状态由他机翻转或为极简态（加载/卸载、注册/移除、冻结），免建理由：`ENT-Chunk` 的 loaded/unloaded 由 `Instance.loadChunk/unloadChunk` 单向驱动且无多步流程；`ENT-Entity` 的 spawned/removed 同理；`ENT-Player` 随 `ENT-Connection` 的 configuration→play 与 play→disconnected 翻转；`ENT-Registry` 创建即冻结无第二态。每个目标态的产生转移已在 11.1 迁移表的动作列核到。

## 12. 关键流程时序

```mermaid
sequenceDiagram
  autonumber
  participant C as 原版客户端
  participant N as 引擎网络层
  participant R as 注册表
  participant W as 世界层
  C->>N: Handshake next=2, protocol=776 (API-001)
  C->>N: LoginStart (API-001)
  N-->>C: LoginSuccess (API-001)
  C->>N: LoginAcknowledged (API-001)
  N->>R: 取缓存的 RegistryDataPacket (API-004)
  R-->>N: 32 个注册表，28 个原样转发
  N-->>C: KnownPacks + RegistryData + FinishConfiguration (API-001)
  alt 客户端抢跑 FinishConfiguration
    C->>N: FinishConfiguration 早于服务端发送 (API-001)
    N-->>C: Disconnect synaxis:protocol-error (见 §14)
  end
  C->>N: FinishConfiguration (API-001)
  N->>W: 创建 Player，setInstance 加载出生区块
  alt 区块加载失败
    W-->>N: future 异常完成（审计 P0-5 修复：不再永久阻塞）
    N-->>C: Disconnect synaxis:internal-error (见 §14)
  end
  W-->>N: 加载完成
  N-->>C: JoinGame + 区块数据 (API-001)
  C->>N: CustomPayload synaxis:main echo(payload) (API-002)
  N-->>C: CustomPayload synaxis:main echo(payload) (API-002)
  Note over C,W: 驱动 §11.1 login→configuration→play；echo 往返即 REQ-011 验收点
```

## 13. 页面与跳转地图

N/A — 无前端。客户端 Fabric 模组与其界面属于 M2。

## 14. 错误处理与错误码

无 HTTP，不用 RFC 9457。统一错误信封是 Minecraft 协议的 `Disconnect` / `LoginDisconnect` 包，原因文本为 Adventure `Component`，其 `translate` 键即稳定错误码。排障关联 ID 就是 `connection_id`（§9），由网络层在 TCP 接入时生成，该连接的所有服务端日志行都带上它——**断线包本身不带**：`DisconnectReasons` 是纯 `TranslatableComponent`，`PlayerConnection#kick` 只把 `connection_id` 写进日志（`Kicking connection_id=… identifier=… reason=synaxis:…`）。用户报障时按时间与用户名在服务端日志里定位这串 id；要让玩家能直接读出它，需要 kick 时把它拼进 `Component`，那会改变每一次断线的可见文本，另行决定。

| 错误码（type） | 协议动作 | 语义 | 客户端处理约定 |
| --- | --- | --- | --- |
| `synaxis:unsupported-version` | LoginDisconnect | 协议版本 ≠ 776 | 提示安装指定版本 |
| `synaxis:protocol-error` | Disconnect | 帧格式非法、状态机非法推进、负载超限、递归超深 | 提示重连，重复出现提示报障（`connection_id` 在服务端日志里） |
| `synaxis:login-timeout` | 关闭 socket | 30 秒内未完成登录 | 提示重连 |
| `synaxis:duplicate-login` | Disconnect（configuration 阶段） | 该 profile UUID 已有在线连接（离线模式下 UUID 由用户名派生，同名即同 UUID） | 提示换个用户名；若是自己刚掉线后重连，稍候重试 |
| `synaxis:internal-error` | Disconnect | 服务端内部异常，如出生区块加载失败或实体注册被拒 | 提示重连并报障 |
| `synaxis:server-stopping` | Disconnect | 进程关闭 | 提示服务器已关闭 |

生产就绪新增的原因键（连接闸门、正版验证、顶号、出站积压、keep-alive 超时等）与本表既有键的新来源见 `docs/DESIGN-PROD.md` §14。

## 15. 非功能需求

| 维度 | 场景 | 目标 |
| --- | --- | --- |
| tick 预算 | 50 玩家同时在线，各自站在不同区块 | 单 tick 用时 P99 < 50 ms（追溯 §2 用户量 ≤ 50） |
| 登录延迟 | 单客户端从 TCP 接入到收到 JoinGame | < 2 s；登录总超时 30 s（§11.1） |
| 入站包 | 50 玩家峰值 | 5,000 包/秒无丢包（追溯 §2 请求量） |
| 内存 | 进程常驻，1 个世界实例 100 MB 地图 | 堆 < 2 GB（追溯 §2 数据量假设） |
| 限流 | 单连接 | 7 s 窗口 > 750 包断线 `synaxis:protocol-error`，另有解压后负载 > 4 MiB/s 断线、补全 > 20 次/秒丢弃，见 `docs/DESIGN-PROD.md` `API-PROD-012`；单包 > 2 MB 断线 `synaxis:protocol-error`；不做 429 类退避 |
| 输入分配上限 | 单个客户端包 | 客户端提供的 NBT 解码后估算堆占用 ≤ 2 MiB（`JvmFlags.NBT_MAX_BYTES`），集合与映射的预分配按可读字节数而非声明长度，注册表标签条目数 ≤ 注册表大小，创造模式背包包对非创造玩家不解码；超限断线 `synaxis:protocol-error`（学习上游 PR #3361/#3362/#3364） |
| 客户端位置与方块操作 | 单个客户端包 | 越过世界边界（由内向外，或在边界外离边界更远）、单包位移超过 64 格的位置包拉回原位、不载入区块；挖掘与使用方块离眼睛不小于方块交互距离 + 1 格的拒绝；载具移动包同样判单包 64 格与世界边界，拉回时给骑手发载具移动包（`docs/DESIGN-PROD.md` v0.9.53）；不做速度、碰撞与挖掘时长校验，见 `docs/DESIGN-PROD.md` `API-PROD-030`、`ADR-PROD-016`。核心模式平坦世界的边界不在这一行，见 `docs/DESIGN-M2.md` §28.2 G3 |
| 实体数值 | 插件与监听器给出的属性、伤害量、生命值 | 属性的计算结果为 NaN（基础值或修饰符为 NaN）时取该属性的下限，与原版一致；`Damage` 的构造与 `setAmount`、`LivingEntity#setHealth` 收到 NaN 抛 `IllegalArgumentException`；无穷大不拒，+∞ 伤害照常致死。否则一个 NaN 让上限为 NaN 的实体每次 `setHealth` 都抛异常，或让生命值成 NaN、实体再也死不了（`docs/DESIGN-PROD.md` §9 存档的生命值要求有限值） |
| 日志量 | 单个客户端持续发包 | 日志行数不由客户端决定：凡是频率由对端决定的 WARN / ERROR，必须按时间限流（行内带区间累计次数）、按类型去重，或紧跟一次**真的把这一批包停下来**的断开；网络栈 `network` 与客户端包监听器 `listener` 两个包树由 `ArchitectureTest#clientDrivenPathsDoNotWriteALinePerPacket` 守门（交给宿主的 `ExceptionManager#handleException` 同等对待——宿主决定落点，调用点决定频率），宿主的 `io.github.bearisbug.synaxis.app..` 由 `:server-app` 自己测试源集里的 `ClientDrivenLogArchitectureTest#hostPathsDoNotWriteALinePerPacket` 守同一条规则（一条规则守不了两个模块：ArchUnit 扫的是自己模块 classpath 上的类，引擎的测试源集里没有宿主的类），两边的豁免名单都逐条写明其上界。**这条约束的上界是「日志量正比于连接数，不正比于包数」**：每连接常数行是达标的，所以不断重连仍能把总量做上去（`PacketReading` 的限流计数器做成进程级而非每连接，就是因为每连接的额度照样随连接数放大），要挡的是连接数本身，不归这一行管 |
| 数据增长 | 12 个月 | M1 不落盘任何持久数据，无需清理；世界持久化由 M2 设计 |
| 构建 | 全量测试 | 本机 < 3 分钟，CI < 10 分钟 |
| 规模 | 主源码文件数 | 删除完成后 ≤ 1,150，其中含 REQ-008 并入的 49 个原生成文件，即手写源码 ≤ 1,100（起点 1,412 + 生成物 85 分置） |
| 兼容 | 客户端 | 仅原版 26.2；其他版本拒绝 |

## 16. 并发与一致性

| 竞态场景 | 参与方 | 期望结果 | 依据（约束/守卫） |
| --- | --- | --- | --- |
| tick 线程内调用 `stop()` | tick 线程 × `API-003` | 立即返回，关闭序列在另一线程完成，不死锁 | §11.2 守卫「调用线程是 tick 线程」；`ThreadDispatcher.shutdown` 不再持 monitor |
| 两条线程同时发起停机（如 SIGTERM 的关闭钩子与宿主控制台 `stop 5`） | 任意两条线程 × `API-003` | 恰一个调用得 true，另一个得 false、不改倒计时，宿主据此回「已在停机」。停机按得 true 那一方的倒计时走，只有一个例外：得 false 的是 SIGTERM 的关闭钩子时，它结束剩余倒计时、等序列跑完才返回，因为 JVM 跑完关闭钩子就 halt | `stopping` 的 CAS，返回值即 CAS 结果（§11.2 `started → stopping` 与两条 `stopping → stopping`）；钩子等序列最后一步完成的 future（`ServerProcessImpl#stopOnSignal`） |
| 非 tick 线程卸载区块，区块内有实体 | 调用线程 × 实体所属 tick 线程 | 区块内有在线玩家时整个卸载被拒绝（`IllegalStateException`，状态零变更）；否则在实例监视器内清缓存、监视器外逐个实体通过 `Acquirable.sync` 获取所有权后移除 | `InstanceContainer.unloadChunk`（审计 F27、F29）；`assertOwnership` 在测试 JVM 强制开启 |
| 两个线程同时首次触发同一事件类型的监听器重建 | tick 线程 × 虚拟线程 | 只重建一次，之后不丢事件 | `EventNodeImpl.Handle` 缓存合并为一个 volatile 不可变记录 |
| 区块加载 future 异常完成时玩家 `setInstance().join()` | 加载线程 × 调用线程 | `join` 在 5 秒内抛出，不永久阻塞 | `whenComplete` 两条路径都 countDown |
| 在 tick 线程上 `join` 玩家 `setInstance` 返回的 future | 玩家所属 tick 线程 × 载入线程 × tick 调度线程 | 只跑这次换实例自己的续延，续延只跑一次（`join` 与调度器谁先认领谁跑）；进程调度器的其余任务仍由 tick 调度线程在串行段处理。这条 tick 线程等到区块载完，监听器应挂续延而不是 `join` | `Player#setInstance` 的续延经 `AtomicBoolean` 认领；`Scheduler#process` 的 Javadoc 写明进程调度器只由 tick 调度线程处理 |
| 换实例等区块期间又调一次 `setInstance`，或玩家被移除 | 玩家所属线程 × 载入线程 × tick 调度线程 | 前一次的 future 以 `CancellationException` 失败，不加视距票、不发包、不派发 `PlayerSpawnEvent`；后一次照常。被这样作废的首次出生（如出生区块还没载完就切回 configuration）只记一行 debug，不踢人 | `Player#setInstance` 的换实例计数与 `isRemoved()`，续延在玩家的监视器里判（与 `Player#remove` 同一把） |
| 读线程与写线程同时断开同一条 configuration 期连接 | 读线程 × 写线程（或 tick） | `PlayerDisconnectEvent` 恰派发一次 | `ConnectionManager#dropPlayer` 在监视器里摘人并返回摘到的玩家，只有摘到的那一方派发 |
| 同一状态连接上的第二个 ping（`ClientPingServerEvent` 设了延迟，连接还开着） | 客户端 × 网络线程 | 第二个 ping 直接关闭连接，不派发事件、不排任务 | `PlayerConnection#acquireStatusPing`，与 StatusRequest 的一次性闸同形 |
| 同一客户端连续两次 StatusRequest | 客户端 × 网络线程 | 第二次直接关闭 | §11.1 守卫「已应答一次」 |
| 实体被移除的同时其 `setInstance` 续体到达 | tick 线程 × 加载线程 | 续体检查 `isRemoved()` 后放弃注册，无幽灵实体 | 审计实体层 P0-3 修复 |
| 同一对（实体，玩家）的显示与隐藏在两条 tick 线程上交错 | 显示方 × 隐藏方（追踪器回调、玩家每 5 tick 的重算、实体移除） | 客户端最后收到的生成或销毁与登记一致：登记已撤的不留幽灵，登记还在的不隐身 | 登记在两把视图锁里，包在放锁之后发；`EntityView` 的显示与隐藏发完包各自不持锁复核一次 `isViewer`，与登记不符就补发销毁或生成包，隐藏方补发生成时连同乘客包、乘客留在登记里，不加锁、不改锁序（`docs/DESIGN-PROD.md` v0.9.96） |
| 对端停止读取，服务端断开该连接 | 写线程（阻塞在 socket 写）× 断开方（tick / 读 / configuration 线程，或写线程自己：写循环捕到非 IO 异常时丢掉出错的包、以 `synaxis:internal-error` 踢掉连接，写线程上的 `PlayerPacketOutEvent` 监听器踢人） | 断开后 ≤ 1 s channel 关闭，读写两条线程退出，出站队列与读缓冲随之释放；对端读不读都一样 | `PlayerSocketConnection#closeOnceFlushed`：断开方起一条 `Syn-Socket-Closer` 虚拟线程，`join` 写线程 `CLOSE_AFTER_DISCONNECT_MILLIS`（1 s，与 `ConnectionManager#shutdown` 的冲刷预算相同）后 `channel.close()`——关闭是唯一能中止阻塞写的操作；写线程看到连接下线后经 `PlayerSocketConnection#flushRemaining` 写空出站队列（断开包与它之前排队的包）再关 channel，写之前起同一条线程（`startCloser`，一个连接只起一次），所以关闭线程有两个起点：断开方的 `closeOnceFlushed` 与写线程的 `flushRemaining`；写线程自己调 `disconnect` 时不在那里起这条线程，由它退出前的 `flushRemaining` 起，同样 1 s 后关（`docs/DESIGN-PROD.md` v0.9.52 §16「断开与出站队列」） |

一致性承诺：

- read-after-write：注册表在 `start()` 后冻结，任何线程读到同一份；实体状态只在其所属 tick 线程或持有 `Acquirable` 所有权时可写，其他线程读到的是上一个 tick 的快照。
- 异步投递：事件树同步分发；`AsyncEvent` 在虚拟线程上 at-most-once，监听器自行幂等。
- 事件分发：监听器按事件的确切运行时类查找，只有 `RecursiveEvent` 沿父类往上传（只走父类，不走接口）；按接口或不是 `RecursiveEvent` 的抽象类注册监听器，`EventNode#addListener` 与 `register` 抛 `IllegalArgumentException`，因为没有事件以它为运行时类派发。监听器抛的异常交给根节点所属进程的 `ExceptionManager`，同一次派发的其余监听器与子节点放弃；经 `map()` 挂上的映射节点各自接住自己子树里的异常，父节点后面的子节点照常执行（`docs/DESIGN-PROD.md` v0.9.98）。

## 17. 依赖与外部系统 / SLA

| 依赖 | 用途 | SLA | 超时/重试 | 失败影响 | 降级 |
| --- | --- | --- | --- | --- | --- |
| JDK 25（Homebrew `openjdk@25`） | 编译与运行 | 本机安装 | — | 无法构建 | 无；`docs/ENGINE.md` 写明安装 |
| Gradle 9.8.0 + 阿里云镜像 init 脚本 | 依赖解析 | 镜像可用性由阿里云保证 | Gradle 默认 3 次重试 | 无法构建 | 回退 Maven Central 直连（经本机代理，不稳） |
| Adventure 5.2、fastutil 8.5、JCTools 4.0 | 文本、集合、并发队列 | Maven Central 存量 | — | 无法构建 | 版本钉死在 `libs.versions.toml` |
| vendoring 的注册表数据（39 个 JSON + 23 个黄金 NBT） | 运行时注册表 | 随仓库 | — | 进程无法启动 | 无外部链，`REQ-001` 后不再依赖 `net.minestom:data` |
| GitHub `Bearisbug/synaxis` | 代码托管与 CI | GitHub SLA | — | CI 不跑 | 本地 `./gradlew check` 等价 |
| 原版 Minecraft 26.2 客户端 | 冒烟验收 | Mojang 分发 | — | 无法冒烟 | 钉死版本，启动器离线可用 |

内部后台任务：

| 任务 | 触发 | 批量/互斥 | 超时 | 失败策略 | 对应告警 |
| --- | --- | --- | --- | --- | --- |
| `tick`（`TickSchedulerThread`） | 每 50 ms | 单实例；各区块分区并行 | 单 tick > 50 ms 记入 `TickMonitor` | 跳过等待，下一 tick 补 | 见 `RB-001` |
| `connection.login-timeout` | 连接建立后 30 s 定时器 | 每连接一个 | — | 关闭 socket | 无 |
| `connection.frame-guard` | 每个入站帧 | 每连接串行 | — | 断线 `synaxis:protocol-error` | 高频出现见 `RB-003` |
| `process.shutdown` | `stop()` / `beginShutdown` / 看门狗判死 | 单实例，幂等（`stopping` CAS） | 倒计时之后：保存 30 s（判死路径 15 s）+ 踢人 5 s + 台账 2 s，阶段与计时见 `docs/DESIGN-PROD.md` §11.4；部署侧 `TimeoutStopSec` ≥ 60 s | 某阶段超出预算记 ERROR，进入下一阶段 | 见 `RB-002` |

## 18. ADR 决策记录

- **`ADR-001` 硬 fork Minestom，不再跟随上游** — Status: Accepted
  - Context：Minestom 是唯一生产可用的从零实现的 Java 版服务端库，但上游 68 个 open bug 中 88% 半年无人回应，包结构 37 个包成环，我们的游戏不要原版玩法。
  - Decision：以 commit `16842f1954` 为起点硬 fork，独立仓库、独立包名、自控构建链；Minecraft 版本钉死。
  - Alternatives：跟随上游 rebase（每季度 drop 都要 rebase，且不能重排结构）；Paper 系（要先做减法）；Rust 实现（无一生产可用）。
  - Consequences：正——可以删、可以重排、可以改门面；负——升 MC 版本变成有预算的主动项目（§28 与 `docs/ENGINE.md` checklist）。
- **`ADR-002` Minecraft 版本钉死 26.2，客户端强制 Fabric 模组** — Status: Accepted
  - Context：客户端本来就要装我们的模组，版本由整合包钉死。
  - Decision：协议 776 唯一支持版本，其他版本握手即拒。
  - Alternatives：ViaVersion 多版本兼容（引擎要维护多套协议，且与自定义负载冲突）。
  - Consequences：玩家必须用指定版本；升版本走 checklist。
- **`ADR-003` 删除原版玩法子系统，注册表降级为不透明转发** — Status: Accepted
  - Context：1,412 个文件中 394 个是我们永远不用的原版玩法逻辑；32 个注册表中 28 个服务端不解析。
  - Decision：三条判据（协议必需、原版玩法、数据基础设施）决定删/留/降级；`RegistryDataPacket` 字节一致是删除的硬验收。
  - Alternatives：全部保留（读不懂、测不动、上帝类不缩小）；连协议层一起裁（客户端会拒绝握手）。
  - Consequences：主源码减 23%；寻路、AI、背包点击语义、Anvil 需要自研替代，但都在 M2 本来的计划里。
- **`ADR-004` 改名在删除之后、门面改造之前** — Status: Accepted
  - Context：改名触及 1,889 文件 11,127 处。
  - Decision：删完再改少 23% 白工；单独 commit 并登记 `.git-blame-ignore-revs`；门面改造是语义改动，不与改名混在一段历史里。
  - Alternatives：先改名（多改 394 个要删的文件）；不改名（暗示这还是 Minestom）。
  - Consequences：阶段 0 到 3 必须连续完成，中途停下会留下「删一半、名一半」的状态。
- **`ADR-005` 注册表数据 vendoring，删除生成器** — Status: Accepted
  - Context：`net.minestom:data` 是我们不控制的外部数据链；`code-generators` 会在下次运行时覆盖手工裁剪。
  - Decision：74 个 JSON 进 `src/main/resources/data`；`src/autogenerated` 转普通源码；删除 `code-generators`，需要时从历史复活。
  - Alternatives：保留生成器并改造 11 个生成器只输出保留部分（额外 3 到 5 人日）。
  - Consequences：升版本时的常量表更新变成三方合并，见 `docs/ENGINE.md`。
- **`ADR-006` 保留完整 git 历史，不压缩** — Status: Accepted
  - Context：用户 2026-09-09 选定。
  - Decision：断开 fork 关系但保留 6,400 多个上游提交。
  - Alternatives：压成一个初始提交（失去 blame）。
  - Consequences：仓库 410 MB；`git blame` 可追溯每行来历，配合 `.git-blame-ignore-revs` 跳过改名提交。
- **`ADR-007` M1 不拆上帝类、不改静态门面** — Status: Accepted
  - Context：M1 起点时 `entity` 包测试密度 0.12，`event` 0.03；删除只让 `Entity` 少 1 条 import。
  - Decision：先把待拆类所在的包补测试到密度 ≥ 0.3（M1 后阶段 7），再按需求驱动拆分；门面与配置改造排在阶段 4 与 5。
  - Alternatives：M1 一起做（无安全网作业，回归面极大）。
  - Consequences：M2 游戏工程通过 `plugin-api` 六个接口隔离引擎，不直接依赖上帝类。
  - 密度口径：同名包树下的测试源文件数 ÷ 主源码文件数（两边都含子包）。`network` 包另按**逻辑文件**计分母——从 282 个主源码文件里排除 19 个 `package-info.java` 与 `packet/client`、`packet/server` 下 219 个纯 record 的包定义，余下 44 个解码 / 编码 / 状态机 / 缓冲区文件才是分母。那 219 个里 212 个是注册表条目，`PacketWriteReadRegistriesTest` 遍历五个状态的全部注册表条目、某个包类缺测试实例就直接失败，逐个做字节往返；余下 7 个不进注册表——`ChunkData` / `LightData` / `PlayerSpawnInfo` / `WorldPos` 随宿主包被间接覆盖，`FramedPacket` / `BufferedPacket` / `LegacyServerListPingPacket` 没有覆盖，都是一到三行的平凡 record，把这 7 个算回分母也只是 48/51 = 0.94，不改变结论。各包实测密度见 §19。

## 19. 验收 / 测试

测试策略：单元与集成测试用 JUnit 5 加仓库 `testing` 模块的 `@EnvTest`；并发用 jcstress；分层用 ArchUnit；协议用真 socket 冒烟。全量 `./gradlew check` 是每次提交的回归范围，冒烟在阶段 2 与 3 结束时必跑。可执行用例与轮次台账见 `docs/TEST.md`。

测试密度现状（2026-09-18 实测，口径见 `ADR-007`，只列主源码 ≥ 15 个文件的 13 个包）：过 0.30 的有 `tag` 1.25（20/16）、`network` 1.09（48/44 逻辑文件；按 282 个文件的粗口径为 0.17）、`inventory` 0.75（18/24）、`instance` 0.70（57/81）、`entity` 0.61（62/102）、`item` 0.50（32/64）、`registry` 0.41（9/22）、`event` 0.32（42/130）；仍在线下的有 `command` 0.27（23/86）、`utils` 0.27（9/33）、`listener` 0.23（10/43）、`adventure` 0.11（4/37）、`world` 0.07（2/29）。`ADR-007` 的拆分前置条件只看待拆的 `Entity` / `Player` / `Instance` / `InstanceContainer` 所在的 `entity` 与 `instance` 两个包，两者都已过线；线下那五个包不拦拆分，但密度门在全引擎范围尚未达成。

```gherkin
场景: 依赖冻结后离线构建 (REQ-001)
  Given 仓库不含对 net.minestom:data 的依赖，src/main/resources/data 含 39 个注册表 JSON 与 opaque/ 下 23 个黄金 NBT
  When 断开外网只保留阿里云镜像执行 ./gradlew check
  Then 构建成功，且 module-info 不再 requires net.minestom.data

场景: 仓库独立 (REQ-002)
  Given 本地仓库远程只有 origin 指向 Bearisbug/synaxis
  When 查询 GitHub 仓库元数据
  Then isFork 为 false，旧仓库 Bearisbug/Minestom 不存在，且 git log 保留起点 commit 16842f1954

场景: 止血基线 (REQ-003)
  Given 分支 main 含审计修复
  When 执行 ./gradlew :test --rerun-tasks
  Then 0 失败 0 错误，且从 tick 线程调用 stop() 在 5 秒内返回

场景: CI 门禁拦截并发回归 (REQ-004)
  Given 一个故意重新引入 ThreadDispatcher.shutdown synchronized 的提交
  When CI 运行 jcstress 场景
  Then 流水线红：并发回归单测失败（如 ThreadDispatcherTest）或 jcstress 出现 FORBIDDEN

场景: CI 门禁拦截分层违规 (REQ-004)
  Given 一个在 coordinate 包里 import entity 包的提交
  When CI 运行 ArchUnit 规则
  Then 规则失败，流水线红

场景: 删除波次 1 与 2 后协议不变 (REQ-005, REQ-006, REQ-007)
  Given 删除前录制的 RegistryDataPacket 字节序列（src/test/resources/protocol/registry-data-26.2.bin）
  When 删除完成后启动进程并录制同一包
  Then 不带宿主的引擎进程（RegistryDataByteParityIntegrationTest）两段字节逐字节相同；server-app 进程（宿主在 minecraft:dimension_type 里加了 synaxis:match）用 probe.py compare-registry 按注册表与条目拆开比，除 synaxis:match 外 26 个注册表与 4 个原版维度类型逐字节相同
  And 不带 --ignore minecraft:dimension_type/synaxis:match 比时（反证）只报 synaxis:match 一处不同
  And 主源码文件数 ≤ 1,150（含 REQ-008 并入的 49 个原生成文件，§3 规模行），./gradlew check 绿
  And login、configuration、play 三个状态两个方向的每个包 ID 都与 26.2 原版的同名包相同，原版有而引擎删掉的包留着空位

场景: 不透明注册表拒绝写入 (REQ-007)
  Given 进程已 start()
  When 调用 Registries.opaque("minecraft:enchantment") 并尝试写入
  Then 抛出 IllegalStateException

场景: 生成链收编 (REQ-008)
  Given 仓库不含 code-generators 与 demo 模块
  When 执行 ./gradlew check
  Then 构建成功，且 src/autogenerated 目录不存在、其内容位于 src/main/java

场景: 命名空间改名 (REQ-009)
  Given 改名提交已合入且登记在 .git-blame-ignore-revs
  When grep -r "net.minestom" 排除 resources 与 net.minestom.data 引用
  Then 零命中，且 git blame 任意源码行不指向改名提交

场景: Apache-2.0 合规 (REQ-010)
  Given 仓库根目录
  When 检查 LICENSE、NOTICE、README
  Then LICENSE 与上游逐字节相同，NOTICE 含派生声明与起点 commit，README 顶部含显著变更声明

场景: 核心模式下原版客户端登录进世界并断开 (REQ-011)
  Given server-app 以核心模式起服（-Dsynaxis.app.core-mode=on，监听 25565），不装任何模组的原版 26.2 客户端
  When 客户端多人游戏直连 127.0.0.1，进世界后停 10 秒再断开
  Then 5 秒内进入世界，没有 synaxis:client-mod-required，F3 显示服务端品牌 synaxis；服务端日志有该连接 connection_id 的登录与断开两行，进程继续运行

场景: 探针经 synaxis:main 完成 echo 往返 (REQ-011)
  Given 同一个核心模式进程
  When tools/protocol-probe/probe.py 登录进 play，在 synaxis:main 上发送 echo 负载 "ping"
  Then 1 秒内收到 echo "ping"，服务端日志含该连接的 connection_id

场景: 抢跑 FinishConfiguration 被拒绝 (REQ-011)
  Given 一个处于 login 状态的裸 socket 客户端
  When 在服务端发送 FinishConfiguration 之前先发 FinishConfiguration
  Then 收到 Disconnect synaxis:protocol-error，服务端本 tick 其他玩家正常更新

场景: 非法协议版本被拒绝 (REQ-011)
  Given 握手声明协议版本 775
  When next=2 进入登录
  Then 收到 LoginDisconnect synaxis:unsupported-version

场景: 分配攻击被拒绝 (REQ-017)
  Given 一个已登录的裸 socket 客户端
  When 发送声明 65,535 个元素但只含 10 字节负载的集合包，或自定义数据组件里估算超过 2 MiB 的 NBT
  Then 收到 Disconnect synaxis:protocol-error，服务端堆没有按声明长度分配，日志为单行 WARN 带 connection_id

场景: 非创造玩家的创造包不被解码 (REQ-017)
  Given 一个生存模式玩家
  When 发送 ClientCreativeInventoryActionPacket
  Then 包被整体跳过，连接保持，后续 KeepAlive 正常

场景: M1.5 · 静态门面实例化后测试可并行 (REQ-013)
  Given 引擎源码中不再有 MinecraftServer 静态访问器
  When 开启 JUnit 并行执行运行全量测试
  Then 0 失败，且总耗时低于串行基线

场景: M1.5 · 配置对象取代全局开关 (REQ-014)
  Given 两个 ServerProcess 实例分别配置 dispatcher 线程数 1 与 2
  When 同一 JVM 内同时启动
  Then 各自按自己的线程数分区 tick，互不影响

场景: M1.5 · 底层包不依赖上层 (REQ-015)
  Given ArchUnit 分层规则由冻结改为禁止
  When 运行 ./gradlew check
  Then 规则通过，依赖矩阵脚本显示底层集合出向上层的边为 0

场景: M1.5 · 拆分 NetworkBufferTypeImpl (REQ-016)
  Given 拆分完成
  When 运行全部 network 包往返测试
  Then 0 失败，且 NetworkBufferTypeImpl 单文件不超过 300 行

场景: 测试不留临时目录 (REQ-012)
  Given 全量测试运行完毕
  When 列出 $TMPDIR 下 minestom-test-world-* 目录
  Then 数量为 0
```

## 20. 埋点 / 分析事件

N/A — 画像:内部系统，本里程碑无产品埋点；运行指标见 §21。

## 21. 可观测性 (SLI/SLO/告警)

| SLI | SLO | 告警阈值 | 对应 Runbook |
| --- | --- | --- | --- |
| tick 用时 P99（`TickMonitor`） | < 50 ms | 连续 60 s P99 > 50 ms | 见 `RB-001` |
| `stop()` 到进程退出用时 | 倒计时之后每个阶段不超出 `docs/DESIGN-PROD.md` §11.4 的预算：保存 ≤ 30 s、踢人 ≤ 5 s、台账 ≤ 2 s | 任一阶段超出预算（`process.shutdown` 记 ERROR） | 见 `RB-002` |
| `synaxis:protocol-error` 断线率 | < 1% 连接 | 5 分钟内 > 5% | 见 `RB-003` |

日志：每行携带 `connection_id`（有连接上下文时）与 tick 计数；错误码与 §14 一致。M1 只写标准输出与文件日志，不接外部监控。

## 22. 运维 Runbook

- **`RB-001` tick 超时**：影响 全服卡顿 · owner Bearisbug · 诊断 用 spark 或 JFR 采样 tick 线程，看 `TickMonitor` 报告的最慢 instance · 处置 降低在线人数或减少实例数 · 回滚 回退到上一 tag · 升级判据 连续 5 分钟 SLO 不达标即停服排查 · 恢复验证 P99 回到 50 ms 以内持续 10 分钟
- **`RB-002` 停服卡死**：影响 进程无法退出，需 kill -9 · owner Bearisbug · 诊断 `jstack` 看 `process.shutdown` 卡在哪个 join · 处置 kill -9，收集线程转储 · 回滚 回退到上一 tag · 升级判据 出现一次即视为 P0 回归 · 恢复验证 倒计时之后保存 ≤ 30 s、踢人 ≤ 5 s、台账 ≤ 2 s 依次完成，进程退出（`docs/DESIGN-PROD.md` §11.4）
- **`RB-003` 协议错误断线激增**：影响 玩家被踢 · owner Bearisbug · 诊断 按 `connection_id` 聚合日志，区分单一恶意来源与全体客户端版本错配 · 处置 恶意来源加防火墙；版本错配核对整合包 · 回滚 N/A · 升级判据 5 分钟内 > 20% 连接 · 恢复验证 断线率回落到 1% 以下

## 23. 发布: Feature Flag / 灰度 / 回滚

内部系统，直接放量 + 回滚预案。无 feature flag：删除与改名是结构性变更，不能用开关控制，靠阶段 gate 与 tag 回滚。

| 环 | 放量 | 进入门槛 | 回滚动作 |
| --- | --- | --- | --- |
| 阶段 gate | 每阶段一个 tag | `./gradlew check` 绿 + 该阶段 ArchUnit 规则绿 + 冒烟（阶段 2、3） | `git checkout` 上一阶段 tag |

上线前检查单（M1 完成时填写）：

| 检查项 | 状态（过 / N-A + 证据） |
| --- | --- |
| 验收用例全量通过（见 §19 与 `docs/TEST.md` 轮次） | 待填 |
| 告警 ↔ Runbook 双向就绪 | 待填：M1 只有日志，`RB-*` 以人工检查代替告警 |
| 容量/负载按 §15 验证 | 待填：50 连接压测 |
| 回滚演练 | 待填：`git checkout` 上一 tag 后 `./gradlew check` 绿 |
| 用户文档/帮助中心已同步 | N-A：对玩家不可见；开发者文档 `docs/ENGINE.md` 为 REQ-012 验收项 |

## 24. 契约同步与防漂移机制

- 协议层：`network/packet` 每个包的 write→read 往返测试（上游已有 232 个）继续作为门禁；`RegistryDataPacket` 字节对比测试固化为集成测试，录制样本入库 `src/test/resources/protocol/registry-data-26.2.bin`。
- 分层：ArchUnit 规则入仓：底层 9 包不得依赖集合之外的引擎包（严格规则，无冻结存储）；`engine` 不得依赖 `game`（M2 生效）。`tools/pkgdeps.py` 输出依赖矩阵与「底层出向上层边数」，进 CI 前先在 gate 里人工跑。
- 自定义负载：M1 只有 `echo`，编解码测试向量入库；M2 的 `protocol` 模块同一份向量两端各跑一遍。
- 文档：本文档与 `docs/TEST.md` 按 `CLAUDE.md` 功能开发纪律随代码同步。

## 25. 迁移 / 数据回填

N/A — 无 schema 变更，M1 不落盘任何持久数据。

## 26. 里程碑

| 阶段 | 内容 | 退出条件 | 预估 |
| --- | --- | --- | --- |
| 0 冻结与独立 | REQ-001、REQ-002、REQ-004、REQ-012 | 独立仓库 CI 绿，基线测试数与耗时记录在 `docs/TEST.md` | 已完成（RUN-004） |
| 1 止血 | REQ-003 | 已完成：133 个提交合入，2,659 个测试 0 失败 | 已完成 |
| 2 删除 | REQ-005、REQ-006、REQ-007、REQ-008 | 字节对比通过，主源码 ≤ 1,150 文件（含 49 个并入的原生成文件），冒烟通过 | 已完成（RUN-006，主源码 1,116） |
| 3 改名与合规 | REQ-009、REQ-010、REQ-011 | grep 零命中，NOTICE 就位，原版客户端冒烟与 echo 往返通过 | 代码侧完成（RUN-007/008）；echo 往返有证据（RUN-004～015 中 9 轮的 `echo.txt`，最近 RUN-015）；待人工两条：核心模式下的原版客户端冒烟 `TC-038`（取代 `TC-030`：它的前置在强制客户端模组握手之后不成立、已废弃，用户 2026-10-06 选做法 A）与 `TC-016` |
| 4 门面（M1.5） | REQ-013 | 测试并行开启后全量绿且总耗时下降 | 已完成（串行 30.1 s → 并行 20 s） |
| 5 配置（M1.5） | REQ-014 | `DISPATCHER_THREADS=2` 下全量绿 | 已完成（2,576 测试，线程数 2 全绿两次后恢复默认 1） |
| 6 分层（M1.5） | REQ-015、REQ-016 | ArchUnit 禁止规则绿，依赖矩阵底层出向上层边为 0 | 已完成（2,576 测试；`pkgdeps.py` 底层出向上层边 0） |
| M2 游戏工程与 RTS 垂直切片 | 见 **`docs/DESIGN-M2.md`**（独立分册） | 见该分册 §26 | 见该分册 §26 |
| M3 大世界地图管线与分片 | 见 **`docs/DESIGN-M3.md`**（独立分册，草稿未评审） | 见该分册 §26 | 见该分册 §26 |
| 生产就绪 | 见 **`docs/DESIGN-PROD.md`**（独立分册，草稿；按依赖分第 0～6 波，与 M3 并行） | 见该分册 §26 | 见该分册 §26 |
| 玩法架构 | 见 **`docs/DESIGN-PLAY.md`**（独立分册，v0.1 用户 2026-10-12 签字；先做最小样板 S1，再按 W0～W7 分波） | 见该分册 §26 | 见该分册 §26 |

阶段 0 到 3 必须连续完成（`ADR-004`）。阶段 2 的删除必须在阶段 1 的实体层与 instance 层修复合入之后开始，否则复现测试会随被删代码丢失。

本文档只覆盖 M1 与 M1.5。**M2 起，每个里程碑一份分册**：M2 的需求、接口契约、状态机、ADR、里程碑与风险全在 `docs/DESIGN-M2.md`，本文档只在被它引用时作为引擎侧的背景事实源；改 M2 范围内的东西要改分册，不要改本文档。生产就绪分册 `docs/DESIGN-PROD.md` 与玩法架构分册 `docs/DESIGN-PLAY.md` 不对应单个里程碑，按能力划范围（见 §1「关联」），写法与分册规则相同。

## 27. 上线后度量与复盘

N/A — M1 与 M1.5 的代码侧已于 2026-09-09 完成，阶段 3 还剩两条人工用例（§26）；两条执行之后按 §26 退出条件逐项对照并记录实际人日与偏差。

## 28. 风险与开放问题

| 类型 | 描述 | 负责人 | 何时需拍板 |
| --- | --- | --- | --- |
| 风险 | `EventLedger` 每条事件开关一次 writer（`server-app`）。`docs/DESIGN-M2.md` §20 的逐指令事件与会话三条事件都已随阶段 4 落地，两类的上界不是同一条：**指令侧**由 §15 的令牌桶压在每连接 ≤ 90 事件/秒（该桶是按 `nanoTime` 的固定 1 秒窗口而非滑窗，跨窗口边界最坏是这个数的两倍；客户端能无限刷的 `stale` / `rate-limited` 两档按 §20 不写台账）；**会话侧**的 `session_entered_configuration` / `client_verified` / `client_rejected` 属 `docs/ENGINE.md` §8 的「由连接数决定」那一档，**而开多少条连接是对端说了算的**——离线模式下连一条不需要任何凭据——且这三条不满足那一档附带的「必须与一次断开在同一分支上」（前两条写在连接继续活着的分支上），所以每条连接 2～3 行是结构性的，不是一次断开的收尾。实测（玩法模式 `rts-128`）：单进程顺序走完 100 次完整登录耗时 8.59 s（11.6 连接/秒），台账多 200 行 / 30,800 字节；`PacketFlood` 的 50 条并发连接一条 `C2S_HELLO` 都没发、3.1 s 内全被 `synaxis:client-mod-required` 踢掉，同样写了 100 行 / 15,750 字节（每行约 158 字节）。按每连接 315 字节折算，`docs/DESIGN-M2.md` §15 的「内测 50 人每天 < 10 MB」只够 31,746 次连接，以实测速率约 46 分钟就写满：那条上界成立靠的是内测期间连接来源受控，不是台账自己有界。实测单次 append 26 µs。**由 `docs/DESIGN-PROD.md` `REQ-PROD-017` 承接**（生产就绪第 0 波已落地：65,536 行有界队列 + 单写线程，1 s 或 64 KiB 写一次，满即丢新并计数，每日上限 1 GiB）。按 `ADR-PROD-005`，缓冲只省系统调用、不省字节：写盘量与连接 churn 脱钩靠的是闸门先于台账（`ADR-PROD-002`，闸门拒绝与验证失败都不写台账）加每日上限。第 1 波落地之后（连接闸门 `ConnectionGate`，`13a416caa2`）的现状：写线程与有界队列已在，闸门先于台账也已成立，但离线模式下每条进入 configuration 的连接仍由 `HandshakeGate` 写一行 `session_entered_configuration`（`HandshakeGate.java:256`），行数仍由连接数决定，靠 L2 登录桶（每 IP 0.5 次/s）限速、每日 1 GiB 上限兜底；上限被玩家事件撑满之后审计事件也写不进，这一项在安全审查报告 `docs/research/security-review-2026-10/README.md` §5 第 5 行待排期 | 生产就绪会话 | 原定闸门落地时（第 1 波，已到）。改为：安全审查报告 §5 第 5 行排期时定要不要给审计事件单独留额度；最晚在任一分片出现 `reason=daily_cap` 的 `EVT:ledger_dropped` 时 |
| 已决 | §15 的限流那一行原写「单连接每秒 > 500 包断线」，**实现里没有任何按秒计数的速率闸**（2026-09-18 组 6 / 组 7 实测：一条连接 600 包/秒连发 60 秒、50 条连接各 800 包/秒连发 90 秒，一条都不掉）。真正起作用的是队列排空能力——`Player#interpretPacketQueue` 每 tick 至多取 `playerPacketPerTick`（默认 50，20 TPS 即 1,000 包/秒），超过它 `playerPacketQueueSize`（默认 1000，数组队列实际容量取到 1024）才会满、才以 `synaxis:protocol-error` 断开。文档写的是速率闸、实现是排空能力 + 队列深度：数不同（500 vs ~1,000），语义也不同（速率闸挡持续超速，队列只挡超过排空速度的那部分，999 包/秒可以长期跑满）。定 500 还是 1,000、要不要在热路径上每包加一次计数、超限踢还是丢，是三个设计决定。**由 `docs/DESIGN-PROD.md` `REQ-PROD-013` 承接，生产就绪第 5 波 5B 已落地**（commit `d130557a5d`）：读线程入队处滑动窗口计数，7 s 窗口 > 750 包断线 `synaxis:protocol-error`（默认动作 KICK，可配成 DROP 丢包；5B 落地时的 582 是 200 人 walk 基线每人 7 s 上行包数 P99 291 × 2，2026-09-30 按 RUN-057 六档压测机器人的 7 s P99 最高 374 × 2 取整提到 750），另有解压后负载 > 4 MiB/s 断线、补全 > 20 次/秒丢弃，见该分册 `API-PROD-012`；§15 限流行已按此改写 | Bearisbug | 已于 2026-09-24 由 `REQ-PROD-013` 处置 |
| 技术债 | §14 写明「原因文本为 Adventure `Component`，其 `translate` 键即稳定错误码」，但**默认配置下客户端的包就能触发的纯文本断线还有七处**（2026-09-18 `SOAK-6` 清点，口径是把 `grep -rn 'kick(' src/main/java` 的 30 个调用点逐个分档，不是抽样）：`KeepAliveListener:16`（`Bad Keep Alive packet`，一个 id 对不上的 KeepAlive 就够）、`PlayerPositionListener:61`（`You moved too far away!`，位置包带 NaN 或越界坐标）与共用同一常量的 `PlayerVehicleListener:22`、`LoginListener:48`（`Malformed username!`）、`LoginListener:124`（`Error during login!`，一个服务端从没请求过的登录插件应答就够，`PreAuthConnectionIntegrationTest#aKickStopsTheRestOfTheBatchFromBeingHandled` 每轮都在走这条路）、`ConnectionManager:484`（`Timeout`，停止应答 KeepAlive 超过 `keepAliveKick`，是真实环境里最常见的一种断线）、`Player:1566`（`Required resource pack was not loaded.`）。其余调用点的分档：入口是客户端包但要先有一次服务端内部失败的 2 处（`LoginListener:149 / 198`，同一个 `ERROR_DURING_LOGIN`）；只在代理转发配置或宿主自己发过登录插件请求时够得着的 5 处（`LoginListener:100 / 110 / 83`、`ConnectionManager:237`、`HandshakeListener:152`，其中 `LoginListener:83` 因 `ServerProcessImpl#auth` 是 `final` 实际是条不可达分支）；客户端够不着的 3 处（`Player:654`，以及 `kick(Component)` / `kick(String)` 两个公开 API 本身）；带原版 `translate` 键而非 §14 错误码的 2 处（`Player:2362` 的 `multiplayer.disconnect.invalid_packet`、`HandshakeListener:57` 的 `multiplayer.disconnect.transfers_disabled`）；已经用 `DisconnectReasons` 的 11 处。7 + 2 + 5 + 3 + 2 + 11 = 30。又因为 `PlayerConnection#kick` 只对 `TranslatableComponent` 写 `Kicking connection_id=…`，上面这些纯文本断线在服务端一行日志都不留，排障时连计数都拿不到。本轮只把压测直接打到的那一条（填满包队列）追齐成 `synaxis:protocol-error`；其余要么复用 `synaxis:protocol-error`、要么往 §14 的原因键表里加行（资源包、用户名格式、keep-alive 超时、代理握手各一个语义），是设计决定。2026-10-06 的核对：七处里 `ConnectionManager` 的 `Timeout` 已在生产就绪第 1 波改成 `synaxis:keep-alive-timeout`（`13a416caa2`，`ConnectionManager.java:848`），剩六处仍是纯文本，行号随代码变了：`KeepAliveListener:16`、`PlayerPositionListener:61`、`PlayerVehicleListener:22`、`LoginListener:48`、`LoginListener:182`（原记 `:124`）、`Player:1661`（原记 `:1566`）；`LoginListener:145`、`:217`、`:275` 的 `ERROR_DURING_LOGIN` 属「要先有一次服务端内部失败」那一档，不在六处之内。2026-10-18 更正（`docs/DESIGN-PROD.md` v0.9.94）：`:217` 是 `loginAckListener` 的 catch，对端在 login success 之前发 `LoginAcknowledged` 就能不经服务端失败走到它（profile 为 null，`Player` 构造器抛 `NullPointerException`）；现在这样的确认在 catch 之前以 `synaxis:protocol-error` 断开，那个 catch 移到 `:222`（`:275` 随之移到 `:280`），重新只有服务端内部失败才走得到；同一轮 `PlayerPositionListener:61` 移到 `:56`，`PlayerVehicleListener:22` 移到 `:19`（两者共用 `PlayerPositionListener#malformed`） | 生产就绪会话（`listener`、`network`） | 原定「扩 §14 原因键表时」，`keep-alive-timeout` 那次已经扩过。改为：下一次往原因键表加键（`docs/DESIGN-PROD.md` §14）时，或客户端模组要按 `translate` 键显示断线原因时（`docs/DESIGN-M3.md` §13 `PAGE-DISCONNECT`），六处一起定：复用 `synaxis:protocol-error` 还是各加一个键 |
| 已决 | `SOAK-3` 的同形状路径清点（2026-09-18，**只覆盖引擎根模块**，其余模块见下一条）：`SOAK-3` 证明的是「只要有一条每包一行的日志，客户端就能决定日志有多大」，所以按「这条日志的频率由谁决定」扫了引擎根模块 `src/main/java` 的 27 个报告点（口径：`grep -rn -e 'LOGGER\.warn(' -e 'LOGGER\.error(' -e 'logger\.warn(' -e 'logger\.error(' -e 'printStackTrace' src/main/java` 在本轮 HEAD 上输出 28 行，减去 `ExceptionManager.java:50` 那行——它是把 `Throwable::printStackTrace` 装成默认落点的方法引用，不是一次报告——即 27 个调用点；写成多个 `-e` 而不是 `-E` 的正则分支，是因为分支符在 Markdown 表格里会把这一格切开，照抄下来反而跑不通）。分档结论——**① 逐包由客户端决定**：2 处，`EntityTrackerImpl` 的「移动未注册实体」（已随 `SOAK-3` 修）与 `PacketReading` 的「包未读完」（本轮修：同样按 10 秒限流、行内带区间累计次数；计数器是进程级而非每连接，因为它是拿不到连接的静态解码器，而每连接的额度仍会随连接数放大）；**② 每连接至多一行、同一分支紧跟断开**：4 处（`PlayerSocketConnection` 协议错误、`LoginListener` 登录插件应答、`ConnectionManager` 的 known-packs 超时与 spawn 失败；清点时另有 `HandshakeListener` BungeeGuard 一处，随 `Auth.Bungee` 在生产就绪第 2 波删除）——这一档成立的前提是那次断开**真的把活停下来**：一次读上来的是一整批包（默认读缓冲 16,383 字节、最小帧 4 字节 ⇒ 一批约 4,000 帧），所以 `PlayerSocketConnection#processPackets` 在派发每个包之前先看连接还在不在、不在就 `break`。没有这一下，kick 只是排了一个断开包，同批剩下的帧照样逐个进监听器，②「每连接一行」就退化成「每包一行」（实测：一条连接发 3,000 个最小登录插件应答 ⇒ 2,994 行 `Error handling Login Plugin Response`、日志 5.8 MB）。批与批之间同样要停：断开之后读线程照样读 socket、读到的丢掉，不再解码（`PlayerSocketConnection#read`），迟到的踢人对已下线的连接什么都不做（`PlayerConnection#kick`）；否则解码失败的帧留在读缓冲里，每读一次就再踢一次、再写一行，缓冲满了读还不等 socket（`docs/DESIGN-PROD.md` v0.9.85，§16「断开之后的读线程与迟到的踢人」）；**③ 按类型去重一次**：1 处（`PacketListenerManager` 的「无默认监听器」，客户端加不了包类型）；**④ 由启动 / 注册表数据决定**：10 处，进程生命周期内次数固定；**⑤ 交给宿主 `ExceptionHandler`**：4 处（`ExceptionManager`、`EventNodeImpl`、`TickThread`、`Entity`，引擎默认 `printStackTrace`）——**宿主只决定这份报告落到哪里，写多少次仍由调用点决定**，所以 `ExceptionManager#handleException` 与 WARN / ERROR 同等对待，一并进门禁的禁止目标；**⑥ 由服务端代码决定、客户端够不着**：4 处；**⑦ 客户端够得着但未设门**：1 处，见下一条。⑥ 里点名复核过 `InstanceContainer` / `DynamicChunk` 的 `tried to set a block outside the world bounds`：客户端两条入口都在到达它之前就挡住了——`BlockPlacementListener` 在 `placeBlock` 之前显式判 `placementPosition.y()` 是否在 `[minY, maxY)`，`InstanceContainer#breakBlock` 对越界 y 拿到的是 `Chunk#getBlock` 返回的 `Block.AIR` 并在 `block.air()` 处返回，`executeNeighboursBlockPlacementRule` 也先判 `neighborY`；同理 `TagNbtSeparator` 的 `printStackTrace` 只在 `TagHandler.fromCompound` 之下，而该路径按 F49 残留的处置已不接客户端 NBT。纪律本身做成门禁：`ArchitectureTest#clientDrivenPathsDoNotWriteALinePerPacket` 禁止 `network..` / `listener..` 两个包树调用 `Logger#warn` / `Logger#error` / `Throwable#printStackTrace` / `ExceptionManager#handleException`，豁免名单是八个类（`PacketReading`、`PlayerSocketConnection`、`Server`、`ConnectionGate`、`SessionClient`、`ConnectionManager`、`PacketListenerManager`、`LoginListener`）且逐条写明其上界，`ConnectionGate`（生产就绪 1A）与 `SessionClient`（2A）的上界都是 10 s 至多一行汇总；规则会不会真的红由两个 fixture 反证——`theClientDrivenLogRuleRejectsALinePerPacket`（逐包 WARN）与 `theClientDrivenLogRuleRejectsAReportPerPacket`（逐包交给宿主，fixture 就放在 `network` 包本身，一并反证包清单覆盖整棵网络栈）。**已知盲点**三个：① 豁免是按类而非按调用点，这八个类里再加一条无界的行不会红；② 包清单只到 `network..` / `listener..`，而 PLAY 态的包并不在这两棵树里派发——`PlayerSocketConnection` 只内联派发 `IMMEDIATE_PROCESS_PACKETS` 那 12 个握手 / 登录 / 配置期的包（含重新配置的确认），其余一律进 `Player#addPacketToQueue`，由 `entity` 包的 `Player#interpretPacketQueue` 每 tick 取 `playerPacketPerTick` 个交给监听器，`entity..` 不在清单里（实测：在 `Player#addPacketToQueue` 插一行 `LOGGER.warn` 仍然全绿），所以这条规则守住的是「谁写」而不是「哪条路径」；③ 它只看引擎根模块的类——`ArchitectureTest` 扫的是自己模块 classpath 上的包，别的模块的类根本不在里面，所以这条规则对 `:server-app` / `:game-rts` 完全失明，见下一条 | Bearisbug | 已于 2026-09-18 处置，回归用例 `PacketReadingUnreadBytesTest`、`PreAuthConnectionIntegrationTest`、`ArchitectureTest` |
| 已决 | 清点范围纠正（引擎根模块 → 全部模块的主源码）与宿主侧的四处处置（2026-09-18）：上一条的 27 处**只是引擎根模块一个模块的数字**，扫描没有进别的模块，而宿主 `:server-app` 坐在同一条客户端可达路径上——`PluginChannelRouter.attach(hostNode)` 在 `PluginHost` 构造函数里无条件执行，引擎的 `listener/common/PluginMessageListener` 对**每个** `ClientPluginMessagePacket` 无条件 fire `PlayerPluginMessageEvent`，而该包的 channel 是客户端给的任意 ≤ 256 字符串，于是一个进了 PLAY 的客户端循环发 `ClientPluginMessagePacket("x:y", 空负载)` 就是一包一行，上界只有 §15 那条「单连接 500 包/秒」（而它本身没有实现，见本节第一条技术债）。**逐模块重扫**（口径同上一条，把路径换成各模块的主源码）：引擎根模块 28 行 − 1 = **27**、`server-app` **15**、`game-rts` **2**、`protocol` / `plugin-api` / `testing` / `example-plugin` 各 **0**，合计 **44** 个报告点（`settings.gradle.kts` 里还有 `jmh-benchmarks` 与 `jcstress-tests` 两个模块，它们**没有主源码**——命中全在 `src/jmh` 与 `src/jcstress` 下，共 8 处 `printStackTrace`，都只在基准与并发压测里跑，不计入运行期报告点）。`server-app` 那 15 处的分档——**① 逐包由客户端决定**：4 处，全部本轮修，`PluginChannelRouter` 的「丢弃未注册频道的负载」与「频道 handler 抛异常」（带栈）、`PluginEventsImpl` 的「插件事件监听器抛异常」（带栈）、`EventLedger` 的「台账写不进去」（带栈）——最后这处的频率不是宿主定的：`PluginHost` 把 `ledger::record` 当作 `PluginAnalytics` 能力交给每个插件，而 `DESIGN-M2` §20 要求逐条指令记 `EVT:unit_order_issued` / `unit_order_rejected` / `unit_selected` / `unit_order_superseded`，即每个 `C2S_ORDER` / `C2S_SELECT` 包一次 `record`，`logs/` 不可写或磁盘满时就是一包一行带栈（上界只有 `DESIGN-M2` §14 那条 300 包/秒的洪水闸门）；**② 每连接至多一行**：2 处（`AdmissionGate` 的准入回调抛异常，同分支断开；与超预算的 WARN）；**④ 由启动 / 停机 / 插件启用决定**：9 处（`Main` 5、`PluginHost` 4）；**由插件排了什么任务决定**：1 处（`PluginSchedulerImpl`，一次失败的任务运行一行；周期与任务条数都由插件在 `scheduleTask` 时定，客户端发包本身加不了任务——插件若在收包路径上给每个包排一个一次性任务，无界的就是插件自己排的那些任务，不是这一行）。`game-rts` 的 2 处都在 `Match`，一次对局创建 / 结束各至多一行。**修法与取舍**：四处改为经 `LogBudget` 输出，形状照抄引擎已有的两处（`PacketReading#reportUnreadBytes` / `EntityTrackerImpl#reportUnregisteredMove`：10 秒一条、行内带区间累计次数），所以 grep 里这 4 处现在塌缩成 `LogBudget` 自己的 3 行（`warn` 两个重载 + `error`）：`server-app` 的计数由修复前的 16 降为 15，全仓合计由 45 降为 44（引擎侧两棵树都是 28，未变；复核命令见本条开头那条 `git grep`，把 revision 换成 `b315557585` 即可跑出修复前的数）。带栈的那三处不能靠限流把「插件真的坏了」吞掉，所以预算的粒度按**宿主注册过什么**切、而不是按客户端发了什么切：每个频道注册一份、每个事件监听器一份（数量由插件决定），客户端唯一能相乘的那一维（它自己编的 channel id）共用一份；台账那处整份文件共用一份（目录不可写是关于文件的一个事实，与谁在写无关）。这个取舍由 `PluginPayloadLogBudgetIntegrationTest#aFloodIntoOneChannelLeavesTheNextChannelItsFirstStack` 钉住——对一个必抛的频道刷 10,000 次之后，另一个插件的第一次失败仍写出自己那条带栈的 ERROR；改成整个路由器共用一份预算，这条就红（实测 `expected: <2> but was: <1>`）。一个区间里第一条报告保留完整的栈，同区间的其余次数只进计数，由**下一次触发**写出的那一行报出来——洪水停在区间中途时积压的计数就留在计数器里等下一次触发（`LogBudgetTest` 第一条把这个行为钉住：洪水后 `suppressed` 停在 10,000，要再来一次才报 10,001），丢的是重复栈，不是「有插件在抛异常」这件事。**宿主门禁**：`:server-app` 测试源集新增 `ClientDrivenLogArchitectureTest`，规则与引擎那条同形（禁止 `io.github.bearisbug.synaxis.app..` 调用 `Logger#warn` / `Logger#error` / `Throwable#printStackTrace` / `ExceptionManager#handleException`），豁免名单是 `LogBudget`、`Main`、`PluginHost`、`AdmissionGate`、`PluginSchedulerImpl` 五个类且逐条写明上界；`PluginChannelRouter`、`PluginEventsImpl` 与 `EventLedger` **不进**豁免名单——它们经 `LogBudget` 输出，于是引擎那条规则的盲点 ①（豁免按类，同一个类里再加一行不会红）对**这三个类**不成立；**只对这三个**，宿主的豁免同样是按类写的（`boundedReporters()` 用 `name(...)` 整类豁免那五个），所以盲点 ① 在那五个类里照样成立，见本条的剩余盲点。有齿的证据：临时在 `PluginChannelRouter#route` 插一行无节制的 `LOGGER.warn`，`hostPathsDoNotWriteALinePerPacket` 变红并点名 `PluginChannelRouter.route(…) calls method <org.slf4j.Logger.warn(java.lang.String, java.lang.Object)>`（消息尾部的 `in (PluginChannelRouter.java:N)` 是插入行的行号，随文件变动，不作判据）；在 `EventLedger#record` 的 `catch` 里并排插一行直写的 `LOGGER.warn` 同样变红并点名 `EventLedger.record(…)`；两次撤销后都复绿；常驻反证是 `theHostLogRuleRejectsALinePerPayload`（测试源集里的 `UnboundedHostLogFixture`）。**剩余盲点**四条：① 宿主的豁免也是按类的，那五个豁免类里再加一行客户端可驱动的 WARN / ERROR 门禁照样绿——实测在 `AdmissionGate`（它本身就是挂在 hostNode 上的监听器类）里再挂一个 PLAY 期事件的监听器写一行 `LOGGER.warn`，`hostPathsDoNotWriteALinePerPacket` 不红；`PluginSchedulerImpl` 那一行上面已经写明「插件若在收包路径上给每个包排一个一次性任务」就变成逐包一行带栈，而它整类豁免，门禁抓不到。② 规则只认 `Logger#warn` / `Logger#error` 两个方法名，SLF4J 的流式写法 `LOGGER.atWarn().log(…)` 绕得过去（`atWarn` 名字不匹配，`log` 的宿主类型是 `LoggingEventBuilder`）——实测在不在名单里的 `PluginChannelRouter#route` 插一行流式 WARN，门禁是绿的；这是与引擎那条规则共有的缺口。③ import 与包谓词都只到 `io.github.bearisbug.synaxis.app..`，今天该模块主源码全在这棵树里所以没有漏扫，但将来新开一个平级顶层包（如 `io.github.bearisbug.synaxis.hosting`）会静默失明。④ `:game-rts` 没有这条门禁（它那 2 处都不由客户端驱动，且本轮范围不含给游戏侧加东西），再有新模块要自己加一份——一条 ArchUnit 规则只守得住它自己模块 classpath 上的类 | Bearisbug | 已于 2026-09-18 处置，回归用例 `PluginPayloadLogBudgetIntegrationTest`、`LogBudgetTest`、`EventLedgerTest`、`ClientDrivenLogArchitectureTest` |
| 已决 | 宿主日志门禁的豁免名单去掉 `PluginSchedulerImpl`（2026-10-18，`docs/DESIGN-PROD.md` v0.9.105）：上一条按「客户端发包本身加不了任务」把它整类豁免，之后加进来的 `runOn`（`docs/DESIGN-PROD.md` `API-PROD-017`）与 `docs/PLUGINS.md` §10 让插件对每个跨实体效果按包投递任务，任务数随客户端的操作走，一个每次都抛的效果就是每包一段栈。插件任务的失败改经 `LogBudget` 输出：每个重复任务一份预算，一次性任务、`runOn` 与 `runAsync` 共用一份，行首文字 `Plugin task failed: plugin_id=` 不变、行尾带合并次数；`ClientDrivenLogArchitectureTest` 的名单由 11 个类变为 10 个，上一条剩余盲点 ① 点名的这一处不再成立 | Bearisbug | 已于 2026-10-18 处置，回归用例 `PluginSchedulerLogBudgetIntegrationTest`、`ClientDrivenLogArchitectureTest` |
| 技术债 | `CommandParserImpl` 执行命令抛异常时每次写一行带栈的 ERROR，频率由「客户端发多少条命令 × 该命令是否必抛」决定，是上一条 ⑦ 档的那一处；没有一并处理是因为引擎自带的命令都不抛，要触发得由游戏侧注册一个必抛的命令，且限流会改变命令错误上报的语义（调试时要的就是每次一条）。`command` 包因此没有进 `ArchitectureTest` 的客户端可驱动包清单。原定的「M2 评估 `command` 包去留」没有做：生产就绪第 4 波起宿主注册了内置命令与 GM 命令（`docs/DESIGN-PROD.md` `API-PROD-011`），这个包留下，不再评估去留。按 `docs/DESIGN-PROD.md` `API-PROD-011`（v0.9.90）：建命令包时 condition 抛出的 `RuntimeException` 按不可见处理，每次一行 WARN 带玩家名、命令名、节点参数 id 与栈，不限流；执行时的前置条件判定仍不接，留在本行。参数的错误回调在最深的失败只落在一个设了回调的参数上、且持有它的语法的 condition（按建命令包时的方式问，命令串为 null）放行发送者时调用，结果 `INVALID_SYNTAX`，不执行任何执行器；同一深度有兄弟节点也失败、那个参数没设回调或语法的 condition 不放行时，命令照旧执行；回调抛的异常与执行器同样处理 | 生产就绪会话（命令面，安全审查报告 §5 第 4 行同属该会话） | 原定 M2，已过。改为：安全审查报告 §5 第 4 行（命令面）排期时一并定；最晚在第一次注册一条执行时会抛异常的命令之前 |
| 技术债 | 目标选择器只实现 `type`、`gamemode`、`limit`、`sort`、`level`、`distance`，其余 15 个原版参数报 `INVALID_ARGUMENT_NAME`（`docs/DESIGN-PROD.md` `API-PROD-011`，v0.9.90）；接通 `name` 与坐标另立条目 | 生产就绪会话（命令面） | 第一次有命令要用这 15 个参数之一选目标之前 |
| 已决 | 「监听器抛了异常就每包交一次 `ExceptionManager#handleException`」（引擎默认落点是 `printStackTrace`）在**两条派发路径上各有一处**，两处都只有 `rejectMisusedPacket` 才在同一分支断开，而它默认是关的：默认配置下一个能让某个监听器必抛的包，客户端发多少次就上报多少次。① `PlayerSocketConnection#processPackets`，管 `IMMEDIATE_PROCESS_PACKETS` 里那 11 个握手 / 登录 / 配置期的包；`docs/DESIGN-PROD.md` v0.9.39（`API-PROD-003`「登录前的监听器异常」，安全审查 A-01）之后，一批包的起始状态早于 configuration 时监听器抛异常一律断开、不看 `rejectMisusedPacket`，configuration 与 play 态照旧只靠它；`LoginListener` 对陌生登录插件应答不再写自己的 ERROR 行，每连接一次的报告只交 `handleException`（宿主经 `LogBudget` 限量），仍与踢人在同一分支；② `Player#interpretPacketQueue`，管其余**全部 PLAY 态的包**，即主路径——它每 tick 至多取 `playerPacketPerTick` 个（默认 50），频率仍由客户端决定，且 `suppressMisusedPacketErrorLevel` 默认 0 只压住 HANDSHAKE 态，PLAY 态照报。本轮没改默认值，也没给它限流——引擎自带的监听器里客户端够得着的那几条都自己 catch（`LoginListener` 三处、`PacketListenerManager` 一处），要触发得先有一个会把异常抛出监听器的实现；而限流会吞掉宿主要的那份异常。门禁对两处都不设防：①（`PlayerSocketConnection`）在豁免名单里，②（`Player`）连包清单都没覆盖到（`entity..` 不在 `network..` / `listener..` 里），见上一条的盲点 ②；两处都写在 `boundedReporters()` 的 Javadoc 与 `docs/ENGINE.md` §8 的盲点清单里。**由 `docs/DESIGN-PROD.md` `REQ-PROD-015` 承接**，生产就绪第 0 波已落地（commit `ee1e0994a4`）：引擎默认落点改为全局 10 s 一行、带区间累计次数，宿主落点经 SLF4J 带上下文并保留限流，上报次数不再由客户端决定；`rejectMisusedPacket` 默认值未改 | Bearisbug | 已于 2026-09-23 由 `REQ-PROD-015` 处置 |
| 已决 | 压测 `SOAK-3`（2026-09-18，NFR 级复测）：离线模式下 UUID 由用户名派生，两个压测进程用同一批用户名 ⇒ 第二批玩家带着**已在线的 UUID** 走完登录与 configuration 进 play，`EntityTrackerImpl.register` 抛 `IllegalStateException`，异常被 `Entity#setInstance` 续体的 `catch` 吞掉，于是玩家「在世界里、连接活着、不在追踪器里」，此后每个移动包写一行 `Attempted to move unregistered entity`（实测 15 分钟 662,803 行 / 84 MiB）。**这是 2026-09-15 审计 F59 的 notes 里点名「不在本批次范围、未处理」的那一条**（「离线模式下 `LoginListener` 直接采信客户端 `profileId`、`ConnectionManager` 无重复 UUID 拒绝」）。处置是三层：① `ConnectionManager#createPlayer` 与 `removePlayer` 同锁地占用 / 释放 profile UUID，占用失败返回 `null`，`LoginListener` 以新原因键 `synaxis:duplicate-login` 拒掉**后来的**那条连接（§11.1、§14）；② 注册失败不再被吞——续体抛 `CompletionException`，`Player#spawnPlayer` 与 `Player#setInstance` 把它交回 future，`updateWaitingPlayers` 既有的 `exceptionally` 以 `synaxis:internal-error` 断开该连接，对**任何**注册失败成立；③ `EntityTrackerImpl` 的「移动未注册实体」改为每 10 秒最多一行、行内带区间内累计次数，日志量与客户端包速率脱钩 | Bearisbug | 已于 2026-09-18 修复，回归用例 `DuplicateUuidLoginIntegrationTest`、`FailedSpawnRegistrationIntegrationTest`、`EntityTrackerUnregisteredMoveTest` |
| 已决 | 审计 F29：`Instance#unloadChunk` 对区块内有在线玩家的调用抛 `IllegalStateException` 且不做任何卸载动作，与 `InstanceManager.unregisterInstance` 对称；Javadoc 与 `UnloadChunkIntegrationTest` 已随之更新 | Bearisbug | 已于 2026-09-16 决定并实施 |
| 已决 | 审计 F49：客户端 NBT 不进 tag 系统。`CustomData` / `TypedCustomData` 的 getTag / withTag 经内部 `TagNbtAccess` 直接对 compound 读写单个 tag，不再整包 `TagHandler.fromCompound`，客户端键名不再登记进 `TagImpl.INDEX_MAP`。写入的值编不出 NBT（`Tag.Transient` 的值、编码失败的文本组件）时，`TagImpl#write` 按值为 null 处理、移除这个键，与 `TagHandler` 的 compound 跳过这类条目一致；物品、方块与 `CustomData` 因此留不住 transient 值（`docs/DESIGN-PROD.md` v0.9.88，RUN-080：修前 compound 里留下键 → null，发包与存档各抛 `NullPointerException`） | Bearisbug | 已于 2026-09-16 决定并实施 |
| 已决 | 审计 F49 残留：按「降级不修」处置——`Tag.Structure` / `Tag.View` / `TagHandler.fromCompound` 的 Javadoc 标明不可用于不可信数据，并由 `:game-rts` 自己的测试源集里一条 ArchUnit 规则**无条件**禁止游戏侧使用这三者（「来源」是运行期污点属性，静态分析判断不了，按来源写等于规则形同虚设）；引擎自身的读取路径不变 | Bearisbug | 已于 2026-09-16 由 `docs/DESIGN-M2.md` 的 `REQ-M2-015` 处置 |
| 已决 | **物品与聊天组件写给 26.2 客户端的四处断线**（生产就绪会话 RUN-080 组 ITEMNET，`docs/DESIGN-PROD.md` v0.9.89）：悬停物品的移除项写成移除，没有编解码器的组件不进悬停事件；物品模板里的空气在设置时拒收，`Particle.ITEM` 默认石头；`container` 的网络格式是至多 256 个可选模板，NBT 是 `{slot, item}` 列表，旧的物品列表仍可读；custom 点击事件的负载可选、可为任意标签。用例 `docs/TEST.md` `TC-PROD-810`、`TC-PROD-811`、`TC-PROD-812`、`TC-PROD-813`、`TC-PROD-814` | 生产就绪会话 | 已于 2026-10-18 修复（RUN-080） |
| 已决 | 审计 F11：视距变更按 tick 节流。`Player` 记录「客户端区块环所依据的视距」`clientViewDistance`，`effectiveViewDistance()` 由它计算，settings 包只改设置，`update()` 每 tick 最多追平一次并按最终值重算一圈；`refreshSettings` 对视距不再立即生效（其它设置仍即时） | Bearisbug | 已于 2026-09-16 决定并实施 |
| 已决 | 审计 F29 残留：`SharedInstance` 整块删除（含 `EntityTracker#viewable` 的多态参数、`Chunk` 的 shared 列表、`Player#setInstance` 的 areLinked 快路径、`InstanceManager` 的两个 shared 工厂），「shared 实例的玩家挡不住容器卸载」随之不复存在 | Bearisbug | 已于 2026-09-16 由 `docs/DESIGN-M2.md` 的 `REQ-M2-012` 处置 |
| 已决 | 审计 F29 残留：`InstanceContainer#unloadChunk` 收集待移除实体时，把「连接已下线但实体尚未 remove」的玩家当普通实体一并走 `Acquirable.sync(Entity::remove)` 同步移除；分区被删前它已经不在 tracker 里，幽灵不再产生 | Bearisbug | 已于 2026-09-16 由 `docs/DESIGN-M2.md` 的 `REQ-M2-013` 处置 |
| 已决 | 审计 F11 残留：`sendPendingChunks` 出队时按 `chunksLoadedByClient` 与 `effectiveViewDistance()` 惰性丢弃越界区块（优先队列没有按值删除，惰性丢弃是正解）；`updateViewDistance` 的中心一并改为 `chunksLoadedByClient`，与客户端环记账同源 | Bearisbug | 已于 2026-09-16 由 `docs/DESIGN-M2.md` 的 `REQ-M2-014` 处置 |
| 已决 | 正式名字 Synaxis（用户 2026-09-15 拍板）：包名 `io.github.bearisbug.synaxis`，仓库 `Bearisbug/synaxis`，品牌 `synaxis`；原占位名 `dev.gameengine` / `game-engine` 的改名提交登记在 `.git-blame-ignore-revs` | Bearisbug | 已于 2026-09-15 决定 |
| 已决 | M1.5（REQ-013～016，阶段 4～6）按用户 2026-09-09「按重构计划并行推进」指示视为确认，M1 阶段 3 完成后开工 | Bearisbug | 已决 |
| 已决 | `command` 包（83 文件）M1 保留：耦合低，开发期调试与运维指令依赖它；M2 自定义协议成型后再评估 | Bearisbug | 已于 2026-09-09 决定 |
| 已决 | `snapshot` 包（11 文件，零测试）删除，纳入 REQ-005 | Bearisbug | 已于 2026-09-09 决定 |
| Open Question | 新仓库公开还是私有；当前私有（`gh repo view` 为 PRIVATE）。`docs/DESIGN-M2.md` §28.2 同一条：社区写插件要拿到 `:plugin-api` 时必须公开 | Bearisbug | 内测全量放量前（与 `docs/DESIGN-M2.md` §28.2 对齐） |
| 风险 | Apache-2.0 §4(b) 修改声明采用 NOTICE 加 README 集中声明而非逐文件头，需法务确认 | Bearisbug | 阶段 3 |
| 已决 | `assertOwnership()` 对未分配线程的实体一律拒绝，`setInstance` / `teleport` 三处仍未接线；接线需先放宽未分配态语义。按 `docs/DESIGN-PROD.md` `API-PROD-016` 的未分配态语义关闭：dispatcher 还没给元素分配 tick 线程时，放行创建它的线程与任何非 `TickThread` 的线程，其他 tick 线程判越权抛 `AcquirableOwnershipException`；未分配元素上的 `lock()` / `sync()` 仍只认创建线程。三处接线与 4 线程严格模式 CI job 随生产就绪第 0 波落地（commit `4a22b96e99`、`df6ff745ae`，`REQ-PROD-019` 前半） | Bearisbug | 已于 2026-09-23 处置 |
| 已决 | jcstress Gradle 插件与 isolated-projects / configuration cache 不兼容，CI 需单独 job 并关闭这两项：已按这个办法规避，`.github/workflows/concurrency.yml` 单独成一个工作流，以 `-Dorg.gradle.isolated-projects=false -Dorg.gradle.configuration-cache=false` 跑 `:jcstress-tests:jcstress`（`concurrency.yml:30-33`），`main` 头的 Concurrency run 36969350284 绿 | Bearisbug | 已于阶段 0 处置 |
| 已决 | `network` 包三处小缺陷（拆分时发现）：`IOUTF8StringType` javadoc 含 `${@link ...}` 坏标签；`ListType`/`SetType` 的 `write` 空值分支写 `BYTE 0` 而 `read` 读 `VAR_INT`，仅因同为单字节 0 而巧合往返；`RawBytesType.write` 局部变量遮蔽记录组件 `length`。2026-10-06 核对三处都还在 | 生产就绪会话（`network/`） | 已于 2026-10-18 处置（`docs/DESIGN-PROD.md` v0.9.94，按本行的约定随改这三个文件同一提交修）：javadoc 改写为 NBT 字符串所用的 modified UTF-8（2 字节无符号长度，至多 65,535 字节），超长时抛带字符数与字节数的 `IllegalArgumentException`，长度改用 `UNSIGNED_SHORT` 写（字节不变）；空值分支写 `VAR_INT 0`；遮蔽的局部变量改名。空值往返测试 `NetworkBufferCollectionTypesTest#aNullListOrSetIsWrittenAsAnEmptyOne` |
| 已决 | 删除 `instance/anvil` 连带删掉的光照 parity 测试已补回（v0.36）：`LightParityIntegrationTest` 对 `parity/*.scene` 的 7 个场景（含方块光、半透明方块与区块边界），与原版 26.2 `LevelLightEngine` 隔离运行的真值逐 nibble 比较 sky 与 block 两层，差异 0，进 `./gradlew check`。真值由 `tools/light-oracle/gen.sh` 生成；需求、修掉的缺陷与门禁见 `docs/DESIGN-M3.md` `REQ-M3-007` 与 §5 末表 | Bearisbug | 已于 2026-09-23 处置（v0.36） |
| 已决 | ArchUnit 冻结存储在纯删除后需要手动剪枝（临时 `allowStoreUpdate=true` 跑一次再改回，核对 diff 只有删除）：仓库里已没有冻结存储，全库没有 `FreezingArchRule` 与 `allowStoreUpdate`，ArchUnit 用的是严格规则（`docs/ENGINE.md` §7），不再需要剪枝 | Bearisbug | 已于 2026-10-06 核对关闭 |
| Open Question | Adventure `ClickCallback.Provider` SPI 是 JVM 全局的，无法选择进程，REQ-013 后未注册；`ClickEvent.callback()` 退化为「不支持」，可用入口是 `process.clickCallbackManager().createClickEvent(...)`。要决定：接受实例入口，还是用「当前进程」线程局部桥接。默认接受实例入口：`META-INF/services` 里没有 `ClickCallback$Provider`，`server-app`、`game-rts` 与 `:plugin-api` 都不调 `ClickEvent.callback()`。原定「M2 设计前」拍板，M2 没有用到，`docs/DESIGN-M2.md` 与 `docs/DESIGN-PROD.md` 都没有承接 | Bearisbug | 改为：游戏模块或插件第一次要直接调 Adventure 的 `ClickEvent.callback()` 之前 |
| 技术债 | Adventure 数据组件转换 SPI 与无参的 `NbtComponentSerializer.nbt()` 用惰性构建的原版 `Registries` 快照，看不到服务端注册的条目。原定 M2，M2 没有用到（`docs/DESIGN-M2.md`、`docs/DESIGN-PROD.md` 都没有承接这一条）。`docs/DESIGN-PROD.md` v0.9.55 移植上游 #3400 与 #3388（`REQ-017` 第二批）之后部分解决：网络写悬停物品不再经转换 SPI，引擎值与 Gson 值按缓冲区带的注册表编码；`NbtComponentSerializer` 多了绑定注册表的 `nbt(Registries)`。仍开着的是转换 SPI 本身（`EngineDataComponentValueConverterProvider` 仍用 `AdventureRegistries.vanilla()`，插件自己调 `dataComponentsAs` 时看不到自注册的条目）与无参的 `nbt()` | M3 会话（引擎底座里不属生产就绪会话的部分） | 改为：第一次有插件或游戏模块用 Adventure 的 NBT 组件序列化读写服务端注册的条目（自定义数据组件、自定义注册表项）之前 |
| 技术债 | `Tag.Structure` 不再接受含 `ItemStack`/`Component` 组件的记录（REQ-013 副作用）。原定 M2；M2 只承接了 F49 的禁用规则（`docs/DESIGN-M2.md` `REQ-M2-015`：`:game-rts` 的 ArchUnit 规则无条件禁止游戏用 `Tag.Structure`），这一条没人承接 | M3 会话（同上） | 改为：第一次有插件要在 `Tag.Structure` 里存 `ItemStack` 或 `Component` 之前 |
| 已决 | 14 个开关结构上是 JVM 级（TPS 喂给 `Tick.SERVER_TICKS` 时间单位、NBT/模板 codec 与包池是 `static final`、Adventure flattener 是 JVM 单例等），放在 `JvmFlags`/各包常量而非 `ServerConfig`；一个开关只住一处，避免两半不一致。现状（2026-09-26）是 16 个：`JvmFlags` 12 项、各包 4 项；生产就绪第 6 波 6C 加的 `synaxis.compression-level` 按同一理由进 `JvmFlags`（`Deflater` 池是全 JVM 共用的静态池），同批的 `synaxis.compression-threshold` 是进程级，进 `ServerConfig`。`server-app` 在 `Main` 类初始化的第一步把 `synaxis.properties` 设成系统属性，早于 `JvmFlags` 类加载，所以文件里写的 JVM 级键同样生效；JVM 级布尔键不跟 `ServerConfig` 布尔键只认 `true` / `false` 的规则（见 `docs/DESIGN-PROD.md` `API-PROD-023` 与 §28.2） | Bearisbug | 已于 2026-09-09 决定 |
| 已决 | JFR 事件类型名仍带 `minestom.` 前缀（改名会改变录制格式），与 `Minestom*` 类名、`build-src` 的 `minestom.*.gradle.kts` 插件 id 一起在分层合入后统一处理：`375eaa9214` 先把前缀改成 `gameengine.`，2026-09-15 的改名提交 `15f619f46d` 再改成 `synaxis.`（`EventsJFR.java:36-42`），`build-src` 的插件 id 是 `synaxis.*.gradle.kts`；`src/main/java` 与 `build-src` 里已没有 `minestom.` 前缀。另有 `ClickCallbackManager.java:26` 的键 `minestom:click_callback`，是 Adventure 点击回调的命名空间，不在本行范围 | Bearisbug | 已于 2026-09-15 处置 |
| 已决 | 上游未修的 `ObjectPoolTest` 在 jcstress pre-test 阶段 NPE：问题已消失，`main` 头的 Concurrency run 36969350284 日志里 `[OK] io.github.bearisbug.synaxis.utils.ObjectPoolTest`，10 planned / 10 passed / 0 failed / 0 errs | Bearisbug | 已于 2026-10-06 核对关闭 |
| 已决 | 压测 `SOAK-1`（2026-09-18）：`ConnectionManager.doConfiguration` 等待 known-packs 应答的虚拟线程，在连接先断开时滞留到 `knownPacksResponseTimeout`（默认 5 分钟），与之绑定的 `Player` / `PlayerSocketConnection` 一并滞留。**这是 2026-09-15 审计 F02 未修完的那一半**（F02 只修了同一方法的 `packFuture`）。处置：`removePlayer` 在 `releaseResourcePackWait()` 之外再调 `PlayerConnection#releaseKnownPacksWait()`，用空清单完成这个 future，`doConfiguration` 拿到结果后按 `isOnline()` 直接退出；同一条 `WARN` 补上 `connection_id`（§14 的日志关联口径） | Bearisbug | 已于 2026-09-18 修复，回归用例 `ConfigurationKnownPacksIntegrationTest#configurationStopsWaitingForKnownPacksWhenTheClientLeaves` |
| 已决 | 压测 `SOAK-2`（2026-09-18，与 `SOAK-1` 同一条早退路径但没有时间上界）：configuration 阶段早退的连接里约 1% 留下**永不回收**的 `Player`。持有者是 `configurationPlayers` / `keepAlivePlayers`——`doConfiguration` 先把玩家放进这两个集合，断线处理若在这之前跑完，`removePlayer` 就摘了个空，之后的插入再没有人来撤销。处置：三处插入（`doConfiguration` 与 `updateWaitingPlayers` 的两条 drain）统一走 `ConnectionManager#trackPlayer`，它与 `removePlayer` 同锁，并在插入前复核连接仍在 `connectionPlayerMap` 里，插不进去就直接返回 | Bearisbug | 已于 2026-09-18 修复，回归用例 `ConfigurationKnownPacksIntegrationTest#aClientGoneBeforeConfigurationStartsLeavesNothingBehind` |
| 已决 | 攻击面盘点（2026-09-22，`docs/research/attack-surface-2026-09/README.md`）之「慢读对端」：对端登录后停止读取，服务端的每一条出站包都进无界的 `PlayerSocketConnection#packetQueue`（`mpscUnboundedArrayQueue`），写线程在 socket 发送缓冲（`socketSendBufferSize` 262 KB）与对端接收窗口填满后阻塞在 `channel.write`。keep-alive 在 `keepAliveKick`（15 s）后把它踢掉，但断线包由这条写线程写、channel 也由它在 `Server#playerWriteLoop` 退出时关——它退不出来，于是 channel、读写两条虚拟线程、16 KB 堆外读缓冲与队列里的全部包都留到对端主动断 TCP 为止，而对端可以永远不断（TCP 零窗口探测无限期）。实测（`SlowReaderIntegrationTest` 修复前）：`disconnect()` 5 s 后 channel 仍 open、两条线程仍活着。处置：`disconnect()` 之后由一条 `Syn-Socket-Closer` 虚拟线程 `join` 写线程 `CLOSE_AFTER_DISCONNECT_MILLIS`（1 s，与 `ConnectionManager#shutdown` 的冲刷预算相同）再 `channel.close()`，阻塞写以 `AsynchronousCloseException` 结束、两条线程各走既有的 `ClosedChannelException` 分支退出（§16 新增一行）；写线程自己调 `disconnect` 时不起这条线程，它不在写里、退出时自己关。**队列本身仍是无界的**，它的上界由 keep-alive 给出——对端读不到 keep-alive 就答不上，15 s 后被踢，所以一条连接最多积 15 s 的出站量；把队列改成有界会让「发得多」变成断线理由，本轮不动 | Bearisbug | 已于 2026-09-22 修复，回归用例 `SlowReaderIntegrationTest#aDisconnectClosesTheChannelUnderAWriteThePeerNeverReads`，探针 `slow-reader`（`docs/TEST.md` TC-GAME-069） |
| 已决 | 同一盘点之「离线身份」：`LoginListener#loginStartListener` 原样采信 `ClientLoginStartPacket#profileId`。原版客户端离线时填的是按用户名算的 name-UUID，所以看起来「同名即同 UUID」，但那是**客户端**算的，改一个字节就能带任意 UUID 登录，而 `synaxis:duplicate-login` 按 UUID 判：换个 UUID 就能同名上线两次，也能占住别人名字派生的 UUID 让那个人登不进来（§10 / §14 写的「离线模式下 UUID 由用户名派生」在实现里并不成立）。处置：离线模式下 profile UUID 由服务端 `GameProfile#offlineUuid(username)` 派生（`OfflinePlayer:<name>` 的 name-UUID，与原版客户端同一算法，所以合法客户端看不出变化），`LoginStart` 里的 UUID 只读不用；`Auth.Velocity` / `Auth.Bungee` 走各自的转发身份，不受影响。**边界**：离线模式没有任何凭据，谁都能用任何名字登录，「冒名」是固有的；这道闸只保证「一个名字永远对应同一个 UUID、同一时刻只有一条在线连接」，不保证名字属于谁——要保证归属只能上 Mojang 会话鉴权或代理转发 | Bearisbug | 已于 2026-09-22 修复，回归用例 `PreAuthConnectionIntegrationTest#theOfflineUuidComesFromTheUsernameNotFromThePeer`（`docs/TEST.md` TC-GAME-070） |
| 已决 | 同一盘点之「连接数」：`Server#start` 的 accept 循环对连接数没有任何上限，每条连接在登录前就占 3 条虚拟线程（读、写、登录看门狗）、一个 16 KB 堆外读缓冲与一个 `PlayerSocketConnection`，configuration 阶段再加一条等 `C2S_HELLO` 3 s 的虚拟线程与一个 `Player`。每条都有界——登录前 `loginTimeout`（30 s）收、登录后 keep-alive（15 s）收——但条数由对端定，一个对端以 100 连接/秒开合就能常驻 3,000 条。fd 用尽时 `serverSocket.accept()` 抛 `IOException`，默认 `suppressConnectionAcceptErrors=true` 把它吞掉后 `continue`，循环空转吃满一个核直到有 fd 释放，日志一行都不写。要修得定一个上限（全局还是按 IP、超限时直接关还是回 `Disconnect`）与 accept 失败后的退避，两者都是新的生产开关，本轮不加。**由 `docs/DESIGN-PROD.md` `REQ-PROD-001` 承接**（连接闸门 L0～L4 与 accept 失败退避，见该分册 `API-PROD-003`），已随生产就绪第 1 波落地（`ConnectionGate`，`13a416caa2`，2026-09-23） | Bearisbug | 已于 2026-09-23 由 `REQ-PROD-001` 处置 |
| 技术债 | 同一盘点之「读超时」：`ServerConfig#socketTimeout`（15 s）经 `Socket#setSoTimeout` 设的是 `SO_TIMEOUT`，它只对 `Socket` 的流式 `InputStream#read` 生效，`SocketChannel#read` 不看它——`Server#playerReadLoop` 用的正是后者，这项配置对读循环是空操作。真正的空闲上界是登录前 `loginTimeout`、登录后 `keepAliveKick`（`FinishConfiguration` 发出到确认之间同样由它截止；`ConnectionManagerIntegrationTest#aPlayerThatStopsAnsweringKeepAlivesIsKicked` 钉住后者，`PreAuthConnectionIntegrationTest` 钉住前者）。留着它会让人以为有读超时；删掉是删一个公开配置项与系统属性 `synaxis.socket-timeout`，走 §8 演进与弃用。**由 `docs/DESIGN-PROD.md` `REQ-PROD-043` 承接**（`synaxis.socket-timeout` 标 deprecated 并走弃用流程删除，keep-alive 超时断线改用 `synaxis:keep-alive-timeout`，见该分册 `API-PROD-003`）。第 1 波落地了一半：设置 `synaxis.socket-timeout` 时起服打弃用告警（`ServerConfig.java:257-260`，「It is removed in the next release」），keep-alive 超时已改用 `synaxis:keep-alive-timeout`（`ConnectionManager.java:848`）；配置项本身还没删 | 生产就绪会话 | 原定第 1 波（已到）。改为：删除最晚在第一次对外发布服务端之前（弃用告警写的是下一个版本删除） |
| 已决 | REQ-005 删包打乱了 clientbound 包 ID（2026-09-23，客户端 spike S1 首次用真 26.2 客户端连进来时暴露）。包 ID 按包在注册表里的位置编号，删掉 `minecraft:hello`（离线模式不发加密请求）、统计、配方、进度、计分板与调试这些包之后，后面的包整体前移：login 前移 1 位，play 的 141 个原版 ID 里有 18 个被删、其后的包跟着错位。服务端把 `login_compression` 发在 `login_finished` 的 ID 上，原版客户端登录即断。**删包之后一直没被发现，是因为此前所有自动化测试、协议探针与压测 harness 的包 ID 都从本仓库的注册表推导**——编码和解码用的是同一张错表，自洽地通过；只有真实客户端带着原版的表连进来才会错。处置：commit `cb44131ef1` 用 `ReservedPacket` 占住每个空位（login 1 个、play 18 个），注册表不按类、不按 ID 解析它，迭代时跳过它；`PacketVanillaIdTest` 把 26.2 原版 login、configuration、play 三个状态两个方向共六张表的每个 ID ↔ 资源名写成常量钉死，期望值由 `javap` 读 26.2 客户端 jar 的 `LoginProtocols` / `ConfigurationProtocols` / `GameProtocols` 与各 `*PacketTypes` 得到，删掉任何一个占位它就红（`docs/TEST.md` TC-036）。升 Minecraft 版本时这六张表要重读一次 | Bearisbug | 已于 2026-09-23 处置 |
| 已决 | **引擎位置、挖掘与放置判定的安全修复**（M3 侧安全审查 2026-10-02：S1-V01 high、S1-01 medium、S1-02 low；生产就绪会话修，随 RUN-070、RUN-071 合入 `main`）：位置包越过世界边界（由内向外，或在边界外离边界更远）、单包三维位移超过 64 格的拉回，挖掘与使用方块离眼睛不小于方块交互距离 + 1 格的拒绝（`docs/DESIGN-PROD.md` v0.9.47，`API-PROD-030`、`ADR-PROD-016`，§15「客户端位置与方块操作」一行）；客户端不回 `ChunkBatchReceived` 时发送队列在开头修剪（v0.9.49）；核心模式的平坦实例设边长 4,096 格的世界边界，载具移动包同判单包 64 格与世界边界（v0.9.53）；`Player#sendPendingChunks` 在区块写锁里取包并交给连接，一批共用 1 ms 的等锁预算（交包窗口，v0.9.54）。挂地图分片按大区 `bounds` 设世界边界，由同一判定覆盖（`docs/DESIGN-M3.md` `REQ-M3-029`；分片另外默认不可改地形）。仍开着的在 `docs/DESIGN-PROD.md` §28.2：挖掘时长不校验（待办，玩法开始依赖挖掘时长之前）、载具移动包不判目标区块未载入（风险）；每包 64 格的连续小跳不判速度与碰撞是接受的残余（`docs/DESIGN-M3.md` §28.2 发现表 S1-02 一行） | 生产就绪会话 | 已于 2026-10-07 处置（RUN-071 合入 `main` `f7a9756f7b`） |
| 已决 | **`TC-020` 的注册表样本早于宿主加的 `synaxis:match`**（冒烟级，RUN-055 起失败）：`probe.py record-registry` 录得 9,409 B，样本 `registry-data-26.2.bin` 是 9,063 B，只差 `minecraft:dimension_type` 里 `server-app` 自 `254ae8aa44` 起注册的 `synaxis:match`，其余 26 个注册表与 4 个原版维度类型逐字节相同。处置（M3-S 轨道 G，`b43f36511c`）：`TC-020` 废弃，由 `docs/TEST.md` `TC-037` 取代，判据是「除宿主加的 `synaxis:match` 外逐字节相同」：`probe.py` 加子命令 `compare-registry <样本> <录得> --ignore <注册表>/<条目>`，按注册表与条目拆开比，带忽略项时 `same=27 differences=0 ignored=1`，不带时（反证）只报 `differs minecraft:dimension_type/synaxis:match: only in the recording`；不重录样本（重录之后样本就不只代表引擎）。§19「删除波次 1 与 2 后协议不变」的宿主那一句由 `TC-037` 追溯，引擎那一句由 `RegistryDataByteParityIntegrationTest` 判；主源码文件数取 §3 规模行的 ≤ 1,150（RUN-069 实数 1,132）。`probe.py` 在 Python 3.12 的虚拟环境里跑 | M3 会话（`docs/DESIGN-M3.md` §26 M3-S 轨道 G） | 已于 2026-10-07 处置（随 M3-S 合入） |
| 已决 | **`docs/ENGINE.md` §7 漏列工作流**：手册原写「三个工作流」，漏了 `client-gametest.yml`（2026-09-23 由 `3085e0edab` 加入）与 `ci.yml` 的两个 4 线程严格 job。生产就绪会话的 chores 组（`docs/DESIGN-PROD.md` v0.9.50，随 RUN-070）把 §7 改成只列名字、不写个数，逐个写出工作流与 job（含 `multithreaded`、`multithreaded-grid` 与 gametest 的触发），`docs/TEST.md` `TC-PROD-656` 用 grep 核这份清单；M3-S 轨道 C 加 `farview-gametest.yml` 时在同一节补了一条，照字面重跑 `TC-PROD-656` 通过 | 生产就绪会话 | 已于 2026-10-06 处置（RUN-070） |
| Open Question | **Dependabot PR #6 合不合**（2026-09-30 开）：Gradle wrapper 9.7.1 → 9.8.0（只改根构建，`client-fabric` 的 wrapper 仍是 9.7.1）、slf4j 2.0.20、archunit 1.5.1、logback 1.6.4、Flyway 13.7.0 → 13.8.0、graalvm native buildtools 1.1.8 → 1.1.14。它的 CI 是对 2026-09-30 的基 `28a6e1bcf6` 跑的，`main` 之后又前进了 106 个提交；`.github/dependabot.yml` 只扫根目录，`client-fabric` 是独立的 Gradle 构建，收不到升级 PR。生产就绪会话评估：rebase 到 `main` 重跑 CI，查 Flyway 13.8.0 与 logback 1.6.4 的变更，定 wrapper 要不要同时配 `distributionSha256Sum`（安全审查 D-04），给出合或关的建议；合不合由用户定 | 生产就绪会话评估，用户拍板 | 2026-10-13 的 Java-WebSocket CVE 复查同一轮 |
| Open Question | **`main` 的分支保护与必需检查**（`docs/DESIGN-PROD.md` §28.1 ops #1：`Client gametest` 连续 20 次全绿后设为必需检查，条件已满足；现在 protection 返回 404，rulesets 为空）：要 GitHub 管理员操作，等用户。设之前要定两件：该工作流的 `pull_request` 带路径过滤，只改 `docs/` 或 `gradle/` 的 PR（含 PR #6）产不出这项检查，设成必需之后会一直等；两会话都直接推 `main`，管理员能不能绕过 | 用户 | 仓库改为公开之前（本表「新仓库公开还是私有」一行） |
| 已决 | 维度没有默认时钟时 `Instance#setTime(long)` 静默不生效（`docs/DESIGN-PROD.md` v0.9.99，RUN-081 组 WORLD 修）：原版 `minecraft:the_nether` 与 `DimensionType.builder()` 建的维度都没有默认时钟，插件以为改了时间，客户端仍停在原来的时刻；方法上的 Javadoc 是从 `getTime` 抄来的「没有默认时钟时返回 -1」。现在它抛 `IllegalStateException`，消息写出维度键，`getTime()` 照旧返回 -1；`DimensionType#defaultClock()`、`DimensionType#create` 的对应参数与 `Builder#defaultClock` 标 `@Nullable`，`builder(existing).defaultClock(null)` 清掉继承来的时钟。要日夜循环就给维度一个时钟（如 `minecraft:overworld`），否则用 `setTime(clock, time)` 设具体的时钟（`docs/TEST.md` `TC-PROD-918`） | 生产就绪会话 | 已于 2026-10-18 处置（RUN-081） |
