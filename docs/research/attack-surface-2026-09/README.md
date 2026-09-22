# 攻击面盘点 · 2026-09-22

对象只有本仓库自己的服务端：引擎 `network` / `listener` 包、宿主 `:server-app`、插件 `:game-rts`，跑在 `localhost:25567`（核心模式，`-Dsynaxis.app.core-mode=on`）。基线 `c961f0492b`（`main`）。口径：每一面写「威胁 → 现有控制（类与行）→ 证明它的测试或 TC → 结论」，行号是本轮 HEAD 的；缺口分两档——便宜的当场修并先红后绿，其余登记到 `docs/DESIGN.md` §28 或 `docs/DESIGN-M2.md` §28.2 并写明触发条件。

先读过的材料：`docs/DESIGN.md` §8 / §14 / §15 / §28、`docs/DESIGN-M2.md` §8 / §14 / §15 / §20 / §28.2、`docs/research/audit-2026-09-15/fixes.md`（F01～F60 的去向）、`docs/research/soak-2026-09-18/report.md` 组 1～7 与阶段 4 / 5 复跑、`docs/ENGINE.md` §8。它们已经压过的面（六种畸形包、握手四条出口、premature-finish 洪泛、5,000 包/秒、churn）本轮不重跑，只指过去。

结果一句话：**修了两处**（慢读对端断开后 socket 永不关闭；离线 UUID 采信对端自选值），**补了五个测试方法（四个测试类）与一个探针子命令**（`slow-reader`），**登记了六条**（引擎侧两条、游戏侧四条），其余 31 个面各自对上了既有控制与测试。全量 `check` 绿：3,133 个测试 0 失败（15 条 skipped 是既有的按环境跳过）。

## 1. 总表

| # | 面 | 威胁 | 现有控制（类与行） | 证明它的测试 / TC | 结论 |
| --- | --- | --- | --- | --- | --- |
| 1 | 资源耗尽 · 帧长度先于分配 | 声明一个 2 MiB 的帧长让服务端先分配 | `PacketReading#readPacket` L227（`packetLength <= 0` 抛）、L228（`> maxPacketSize` 抛）都在切片之前；缺容量时 `PlayerSocketConnection#processPackets` L221 只在 `writableBytes() == 0` 时按 `min(声明值, 2 倍)` 扩；登录前上限 8 KiB、之后 2 MiB（`JvmFlags.MAX_PACKET_SIZE_PRE_AUTH` / `MAX_PACKET_SIZE`） | `PacketReadingMalformedTest#zeroLengthFrameIsAProtocolError` / `#statusFramesUseThePreAuthPacketSizeLimit`、`PreAuthConnectionIntegrationTest#anAnnouncedPacketLengthDoesNotGrowTheReadBuffer`（F54）；TC-026、TC-027 | 已覆盖 |
| 2 | 资源耗尽 · 压缩炸弹 | 压缩帧声明超大解压长度，或流解出的字节多于声明 | `PacketReading#readFramedPacket` L264：声明的 `dataLength` 超过 `maxPacketSize` 在分配之前拒；L275 解出的字节数必须等于声明；`NetworkBufferImpl#decompress` L349 输出缓冲到头而流未结束即抛，解压在声明长度处截停。池里的缓冲最大长到 2 MiB，`SoftReference` 持有 | 本轮补 `PacketReadingMalformedTest#aDeclaredDecompressedLengthOverTheLimitIsRefusedBeforeInflating` / `#aStreamInflatingPastItsDeclaredLengthIsAProtocolError`；TC-GAME-071 第 2 步反证三处有齿 | 已覆盖（控制早就在，测试是本轮补的） |
| 3 | 资源耗尽 · VarInt 畸形 | 帧长 VarInt 超长 / 截断 | `NetworkBufferVarNumberTypes.VarIntType#read` L43（第 6 字节即抛）；`PacketReading#readPacket` L215（同一段内放得下 5 字节却读不出 = 畸形，否则要求扩容） | `PacketReadingMalformedTest#overlongVarIntLengthIsAProtocolError` / `#truncatedVarIntLengthAtBufferEndRequiresABiggerBuffer` | 已覆盖 |
| 4 | 资源耗尽 · 每玩家入站包队列 | 一条连接以超过排空速度发包 | `Player` L294 `mpscArrayQueue(config.playerPacketQueueSize())`（`ServerConfig` 默认 1000，数组队列取 2 的幂实为 1024）；`Player#addPacketToQueue` L2344～2346 `offer` 失败即 `synaxis:protocol-error`；`interpretPacketQueue` L2364 每 tick 取 `playerPacketPerTick=50`。§15 写的 500 包/秒速率闸没有实现，`docs/DESIGN.md` §28 已登记 | `DisconnectReasonsIntegrationTest#aFloodedPacketQueueDisconnectsWithAProtocolError`；TC-034；组 6 / 组 7 | 已覆盖 |
| 5 | 资源耗尽 · 以客户端数据为键的游戏侧集合 | `order_seq`、选中集合、镜头锚点、寻路队列被撑大 | `GameSession`：`acceptedOrderSeq` 是一个 `int`（L56），锚点是两个 `float`（L51～52，`REQ-M2-018` 未落地，没有传送队列），选中集合 `Set.copyOf` 且 `C2SSelect.MAX_UNITS = 8` 在记录构造器里抛（`C2SSelect` L21 / L28）；寻路队列 `Units.MAX_QUEUE_DEPTH = 32` 的 `LinkedBlockingQueue`（L78 / L111），满了回 `busy` | `GameSessionTest` 9 条、`GameChannelIntegrationTest#aSelectionKeepsOnlyTheUnitsTheSenderCommands`、`UnitMovementIntegrationTest#theQueueRunsFourSearchesATickAndAnswersBusyOnceItIsFull`；TC-GAME-026、TC-GAME-042 | 已覆盖 |
| 6 | 资源耗尽 · known-packs 等待 | configuration 阶段不答 known-packs，占住虚拟线程与 `Player` | `PlayerConnection#requestKnownPacks` L363 一连接只有一个 future；`ConnectionManager#removePlayer` L351 断线即释放（SOAK-1）；等待 5 min 之前 keep-alive 15 s 先到（配置阶段玩家在 `keepAlivePlayers` 里）。答 keep-alive 但不答 known-packs 的对端能占满 5 min，每条有界 | `ConfigurationKnownPacksIntegrationTest` 两条；组 F | 已覆盖；条数无上界归 #10 |
| 7 | 资源耗尽 · 每玩家区块队列 | 反复切视距或跨区块把区块队列撑大 | `Player#sendChunk` L864 `queuedChunks` 去重（F11）；`sendPendingChunks` L889 出队时按 `isOutOfClientRange` 惰性丢弃（`REQ-M2-014`）；视距变更每 tick 至多追平一次（F11 残留处置）；对局实例整图预载且 `enableAutoChunkLoad(false)`（`Match` L211） | `PlayerChunkQueueIntegrationTest#viewDistanceChangesDoNotQueueAChunkTwice`；TC-GAME-035 | 已覆盖；核心模式例外见 #16 |
| 8 | 资源耗尽 · `LogBudget` 的键 | 用客户端可编的 channel id / `msg_id` 把预算表撑大 | 预算按宿主注册过什么切：每个频道注册一份（`PluginChannelRouter` L105）、每个事件监听器一份；客户端能相乘的维度共用一份——未注册频道（L45）、未知 `msg_id`（`GameChannel` L90、`HandshakeGate` L149）各一个对象，键不是客户端数据 | `PluginPayloadLogBudgetIntegrationTest` 四条、`LogBudgetTest`；TC-035；组 E（11,000 条恶意负载 4 行 WARN） | 已覆盖 |
| 9 | 资源耗尽 · 会话表 | 「连上 → 应答 → 关 socket」一轮留一项 | `HandshakeGate#reclaimClosedSessions` L385 每 tick 扫；`GameSessions#admitted` L45～46 与断线同锁判活（`ConnectionManager#isConnected`）；引擎 `trackPlayer` L390 同形 | `HandshakeIntegrationTest#aConnectionThatLeavesBeforeItIsAPlayerIsReclaimed`、`GameChannelIntegrationTest#aClosedConnectionGivesItsBookkeepingBack` / `#aPayloadCannotPutBackTheBookkeepingOfAConnectionThatWentAway` / `#anAdmissionThatLostTheRaceToItsOwnDisconnectMakesNoBookkeeping`、`ConfigurationKnownPacksIntegrationTest#aClientGoneBeforeConfigurationStartsLeavesNothingBehind`；组 F | 已覆盖 |
| 10 | 资源耗尽 · 连接数上限 | 一个对端开几千条连接 | 没有。`Server#start` L82～117 的 accept 循环不计数；每条登录前 3 条虚拟线程 + 16 KB 堆外读缓冲；fd 用尽时 L85 `accept()` 抛 `IOException`，默认 `suppressConnectionAcceptErrors=true`（L89）吞掉后 `continue` 空转 | 无 | **缺口 G1**，登记 `docs/DESIGN.md` §28 |
| 11 | 资源耗尽 · 半开与空闲连接 | 连上不说话；登录后不说话 | 登录前 `Server#loginTimeoutWatch` L125（30 s 关 socket，看的是客户端到没到 configuration，F01 / F53）；登录后 `ConnectionManager#handleKeepAlive` L502（上一条 keep-alive 之后 15 s 踢）；`ServerConfig#socketTimeout` 是 `SO_TIMEOUT`，对 `SocketChannel#read` 无效 | `PreAuthConnectionIntegrationTest#aConnectionIsTimedOutOnTheMillisecondTheWatchdogWakesUp` / `#aLoginTheClientNeverAcknowledgesStillTimesOut`、`DisconnectReasonsIntegrationTest#aConnectionStuckBeforeConfigurationTimesOut`；本轮补 `ConnectionManagerIntegrationTest#aPlayerThatStopsAnsweringKeepAlivesIsKicked`（TC-GAME-071） | 已覆盖；`socketTimeout` 空操作是 **缺口 G2**，登记 |
| 12 | 资源耗尽 · 慢读对端 | 登录后不读，让服务端出站缓冲撑满 | 出站队列 `PlayerSocketConnection#packetQueue` L97 无界；写线程阻塞在 `channel.write`；keep-alive 15 s 给队列一个时间上界（读不到 keep-alive 就答不上）；**断开后 channel 由写线程退出时关，而它退不出来** → 本轮修：`closeOnceFlushed` L586 在断开 1 s 后由独立虚拟线程关闭 | 本轮补 `SlowReaderIntegrationTest#aDisconnectClosesTheChannelUnderAWriteThePeerNeverReads`（先红后绿）、探针 `slow-reader`；TC-GAME-069 | **缺口 G0，已修** |
| 13 | 资源耗尽 · configuration 3 s 等待 | 大量连接同时停在等 `C2S_HELLO` | 每连接一条虚拟线程、`HandshakeGate.BUDGET` 3 s，断线立即唤醒（`abandon` L373）；核心模式不等 | `HandshakeIntegrationTest#aSilentConnectionIsKickedOnceTheBudgetIsSpent` / `#aDisconnectDuringTheWaitWakesTheConfigurationThreadAtOnce`；TC-GAME-050 第 3、5 步 | 每条有界；条数归 #10 |
| 14 | 资源耗尽 · 出站放大 | 一包进、N 包出 | 位置包 → viewer 广播是 Minecraft 固有（RTS 玩家应不可见，属 #25）；**聊天**：`ChatMessageListener#chatMessageListener` L45 把每条 `ClientChatMessagePacket` 广播给全部在线玩家，宿主没有取消 `PlayerChatEvent` | 无 | **缺口 G5**，登记 `docs/DESIGN-M2.md` §28.2 |
| 15 | 资源耗尽 · 事件台账 | 不断重连让 `logs/events-*.jsonl` 按连接数增长 | 每连接 2～3 行、约 315 字节；`stale` / `rate-limited` 不记 | `docs/DESIGN.md` §28 第一行（技术债，触发条件「端口对不受控的连接来源开放之前」） | 已登记，指过去 |
| 16 | 资源耗尽 · 核心模式区块加载 | 位置包自我瞬移，让平坦世界无限生成区块 | 无：`CoreMode` L138 的实例保持自动加载，`PlayerPositionListener` L56～61 只判有限与 `MAX_COORDINATE`，不判速度；区块从不卸载 | 无 | **缺口 G3**，登记 `docs/DESIGN-M2.md` §28.2（核心模式只服务压测与冒烟） |
| 17 | 授权 · 对别人的单位下令 | `unit_id` 指向中立 / 对方 / 不存在的单位 | `GameChannel#commands` L238～241：`team != 0 && team == session.team()`，不存在的 id 由 `Match#unitTeam` 回 0 → 同样 `not-owner` | `GameChannelIntegrationTest#anOrderOnAUnitThatIsNotOursComesBackNotOwner`（含 id 404）；TC-GAME-039、TC-GAME-028 第 5 步 | 已覆盖 |
| 18 | 授权 · 选中兴趣集之外的单位 | 选中或指挥屏幕外的本方单位 | 只判归属，不判兴趣集——与 `API-M2-004` 的判据一致；M2 没有战争迷雾，全图单位对所有人可见 | `GameChannelIntegrationTest#aSelectionKeepsOnlyTheUnitsTheSenderCommands`；TC-GAME-024 | 按设计确认（`docs/DESIGN-M2.md` §28.2 一条「已决」，战争迷雾立项时重开） |
| 19 | 授权 · 镜头锚点出图 | `C2S_CAMERA` 带地图外坐标 | `GameChannel#receiveCamera` L166～167 `Math.clamp` 到 `[0, size-1]`；非有限值在解码层拒 | `GameChannelIntegrationTest#theCameraAnchorIsClampedOntoTheMap` / `#cameraReportsOverTheBudgetAreDropped`；TC-GAME-044 | 已覆盖 |
| 20 | 授权 · 地图外目标 | `C2S_MOVE_ORDER` 目标出图或不可站立 | `GameChannel#judge` L227 `standable`：`preset.contains` 且 `navGrid.isWalkable` → `out-of-bounds`，不断线 | `GameChannelIntegrationTest#aTargetOffTheMapOrOnAnObstacleComesBackOutOfBounds`；TC-GAME-040、TC-GAME-041 | 已覆盖 |
| 21 | 授权 · 字段取值 | NaN / Inf 坐标、`count` 越界、`unit_id < 1`、`mods` 非 u8 | `MsgBuf#readF32` L157 非有限即 `MalformedMessageException`；各 record 紧凑构造器（`C2SMoveOrder` L32～35、`C2SSelect` L28～33）→ `synaxis:protocol-error` | `MsgBufTest`、`GameMessagesTest`、`GameChannelIntegrationTest#aMalformedPayloadIsAProtocolError` / `#aMessageOnlyTheServerSendsIsAProtocolError`；TC-GAME-026、TC-GAME-062 | 已覆盖 |
| 22 | 授权 · 状态机之外的负载 | verified 之前发游戏消息、发到未注册通道、未知 `msg_id` | `GameChannel#receive` L137 非 PLAY 一律丢；`PluginChannelRouter#route` L126 未注册频道丢并限流；未知 `msg_id` 丢不惩罚（§9） | `GameChannelIntegrationTest#aPayloadBeforeThePlayPhaseIsDropped` / `#anUnknownMessageIdIsDroppedRatherThanPunished`、`HandshakeIntegrationTest#anUnknownMessageIdIsDroppedAndTheConnectionStays`；TC-GAME-014、TC-GAME-015；组 E | 已覆盖 |
| 23 | 授权 · 玩法限流与洪水闸门 | 超频下令 / 脚本洪水 | `GameSession#allowCamera` / `allowSelect` / `allowOrder`（10/s，按 `order_group` 计）与 `payloadFloods`（300 包/秒 × 3 s → `synaxis:game-flood`） | `GameSessionTest` 9 条、`GameChannelIntegrationTest#goingOverTheOrderBudgetIsAnsweredNotPunished` / `#threeSecondsOfFloodDisconnectTheConnection`；TC-GAME-063、TC-GAME-064 | 已覆盖 |
| 24 | 授权 · 握手 | 重复 `C2S_HELLO`、`schema_version` 不等、不发、发服务端方向的消息 | `HandshakeGate#receive` L218（`complete` 输了即重复）、`decide` L269～288（3 s 超时、版本不等由等待线程踢）、`decode` L330～346 | `HandshakeIntegrationTest` 22 条；TC-GAME-019、TC-GAME-022、TC-GAME-050、TC-GAME-060～062；组 E | 已覆盖 |
| 25 | 授权 · 玩法模式的位置与方块 | 改过的客户端自己挪实体、`FINISHED_DIGGING` 删地图方块 | 无：`RtsPlugin#admit` 只 `Accept(instance, team)`，不设游戏模式；`ADR-M2-013` 的旁观 + 服务端传送是未落地的 `REQ-M2-018`；`PlayerActionListener` L43 不校验挖掘时长 | 无 | **缺口 G4**，登记 `docs/DESIGN-M2.md` §28.2（随 `REQ-M2-018` 消失） |
| 26 | 授权 · 命令与补全 | `ClientCommandChatPacket` 执行命令 | 宿主与插件一条命令都没注册，未知命令只回发送者；`TabCompleteListener` 超过 256 字符直接丢（F45） | `CommandSuggestionIntegrationTest#oversizedSuggestionRequestIsNotParsed` | 无攻击面 |
| 27 | 身份 · 离线 UUID | `LoginStart` 带任意 UUID：同名两个 UUID 同时在线，或占住别人名字的 UUID | 原来 `LoginListener` 原样采信 `packet.profileId()` → 本轮修：L80 `GameProfile.offlineUuid(username)` 服务端派生，与原版客户端同一算法（`OfflinePlayer:<name>` 的 name-UUID） | 本轮补 `PreAuthConnectionIntegrationTest#theOfflineUuidComesFromTheUsernameNotFromThePeer`（先红后绿）；TC-GAME-070 | **缺口 G0b，已修**；边界见 §2 |
| 28 | 身份 · 重复登录 | 同名第二条连接 | `ConnectionManager#trackNewPlayer` L375～379（`uuidPlayerMap.putIfAbsent` 与 `removePlayer` 同锁），`LoginListener` L147 以 `synaxis:duplicate-login` 拒**后来者**；生命周期集合的插入走 `trackPlayer` L390 同锁（SOAK-2） | `DuplicateUuidLoginIntegrationTest` 三条、`DisconnectReasonsIntegrationTest#aDuplicateUuidIsRejectedWithItsOwnReason`；组 5 | 已覆盖 |
| 29 | 身份 · 用户名与登录包 | 非法用户名、重复 `LoginStart` | `LoginListener` L41 `claimLoginStart`（F13）、L47 `isValidUsername`（`Malformed username!` 纯文本，SOAK-6 第 ① 档已登记） | `PreAuthConnectionIntegrationTest#aRepeatedLoginStartIsRejected`、`StringUtilsTest` | 已覆盖 |
| 30 | 身份 · 加密与代理 | 伪造 `EncryptionResponse` / 代理转发头 | `Auth` 只有 `Offline` / `Velocity` / `Bungee`，没有 Mojang 会话鉴权，`ClientEncryptionResponsePacket` 没有监听器（按类型去重一次 WARN 后丢）；Velocity / Bungee 单机不开，其校验分支未测（组 D 的「局限」同款） | — | 无加密路径；代理分支未测、单机不开 |
| 31 | 日志 · 客户端可驱动路径不逐包写 | 一包一行把日志写满 | 三条 ArchUnit 规则：引擎 `ArchitectureTest#clientDrivenPathsDoNotWriteALinePerPacket`、宿主 `ClientDrivenLogArchitectureTest#hostPathsDoNotWriteALinePerPacket`、游戏 `ArchitectureTest#gameSidePathsDoNotWriteALinePerPacket`；盲区七条在 `docs/ENGINE.md` §8（按类豁免、`entity..` 不在清单、`command` 包、`:plugin-api` 无门禁、每包 `handleException` 两处、流式 `atWarn()`、包谓词只到 `app..`） | TC-035（含两处插桩反证）；组 E 门禁复核三条 | 已覆盖；盲区已登记 |
| 32 | 日志 · 每连接固定行数 | 不断重连刷 INFO | 每连接 `Login success` / `Kicking`（仅 translate 键）/ `Connection closed` 各一行，与连接数成正比——§15 明写这一档达标，要挡的是连接数（#10） | — | 按设计 |
| 33 | 日志 · 限流的两处引擎报告 | 尾部多余字节、未注册实体移动 | `PacketReading#reportUnreadBytes`、`EntityTrackerImpl#reportUnregisteredMove` 10 s 一行带累计 | `PacketReadingUnreadBytesTest`、`EntityTrackerUnregisteredMoveTest` | 已覆盖 |
| 34 | 磁盘 · 台账 | 同 #15 | 同 #15 | 同 #15 | 已登记 |
| 35 | 崩溃面 · tick 线程 | 一个客户端让某个 `Tickable` 抛异常 | `TickThread#run` L56 / `#tick` L86 逐 `Tickable` `catch Throwable`，处理器自身抛错也兜住（F14），latch 在 `finally` 里 countDown | `ThreadDispatcherTest#tickThreadSurvivesAFailingExceptionHandler` / `#tickThreadSurvivesAnErrorEscapingTheTickLoop` | 已覆盖 |
| 36 | 崩溃面 · 读线程 | 畸形帧 / 监听器抛错 | `Server#playerReadLoop` L156～168：`ClosedChannel` / `EOF` 静默断，其余 `IOException` 默认压制，`Throwable` 上报后只断**该**连接；`PlayerSocketConnection#processPackets` L166 / L199 `rethrowFatal` 只把 `OutOfMemoryError` 抛回 | `PreAuthConnectionIntegrationTest#aKickStopsTheRestOfTheBatchFromBeingHandled`、`DisconnectReasonsIntegrationTest#aZeroLengthFrameIsRejectedWithAProtocolError`（不上报为引擎异常） | 已覆盖 |
| 37 | 崩溃面 · 负载处理器抛异常 | 插件 handler 或包监听器对某个包必抛 | `PluginChannelRouter#route` L133 `catch Throwable` → `LogBudget`，连接继续（`API-M2-008` 只归因不熔断）；PLAY 包由 `Player#interpretPacketQueue` L2355～2363 逐包 catch，`rejectMisusedPacket` 默认关 → 丢包不断线；每包一次 `handleException` 的日志债在 `docs/DESIGN.md` §28 | `PluginPayloadLogBudgetIntegrationTest#aFloodOfPayloadsIntoAFailingHandlerWritesOneLine`、`PrematureFinishConfigurationIntegrationTest#aTickSurvivesAnUnexpectedConfigurationTransition` | 已覆盖 |
| 38 | 崩溃面 · configuration 线程与准入回调 | 配置流程或 `admit` 抛异常 | `LoginListener#executeConfig` L193～200 `catch Throwable` → `Error during login!`；`AdmissionGate#admit` L73 → `synaxis:match-unavailable`；失败路径 kick 后 `return`，不碰 `spawningInstance` | `HandshakeIntegrationTest#aRejectedConnectionIsToldTheMatchIsUnavailableRatherThanAnInternalError`；TC-GAME-050 第 6、7 步 | 已覆盖 |
| 39 | 崩溃面 · 出生失败 | 注册被拒、出生区块加载失败 | `ConnectionManager#updateWaitingPlayers` L471～475 `exceptionally` → `synaxis:internal-error`，异常不再被续体吞（SOAK-3） | `DisconnectReasonsIntegrationTest#aFailingSpawnDisconnectsWithAnInternalError`、`FailedSpawnRegistrationIntegrationTest` | 已覆盖 |

## 2. 缺口逐条

### G0 慢读对端：断开之后 socket 永不关闭（已修）

- 复现：登录后不读。服务端每一条出站包都进无界队列，写线程在 socket 发送缓冲（262 KB）与对端接收窗口填满后阻塞在 `channel.write`。keep-alive 15 s 后 `kick`，`PlayerConnection#disconnect` 写下 `Connection closed`、摘掉 `Player`——但断线包由写线程写，channel 也由写线程在 `Server#playerWriteLoop` 退出时关，它卡在写里退不出来。`SlowReaderIntegrationTest` 修复前：`disconnect()` 5 s 后 channel 仍 open、读写线程仍活着（`SlowReaderIntegrationTest.java:80` 红）。
- 影响：每条这样的连接留下 channel、两条虚拟线程、16 KB 堆外读缓冲与队列里的全部包，直到对端主动断 TCP；TCP 零窗口探测无限期，对端可以永远不断。乘上 #10 的「连接数无上限」就是一台机器能挂多少 socket 的问题。
- 处置：`PlayerSocketConnection#closeOnceFlushed`——断开方起一条 `Syn-Socket-Closer` 虚拟线程，`join` 写线程 1 s（与 `ConnectionManager#shutdown` 的冲刷预算相同）后 `channel.close()`；阻塞写以 `AsynchronousCloseException` 结束，两条线程各走既有的 `ClosedChannelException` 分支退出。写线程自己调 `disconnect` 时不起这条线程（它不在写里，退出时自己关）。队列本身仍是无界的，上界由 keep-alive 给：读不到 keep-alive 就答不上，一条连接最多积 15 s 的出站量。
- 证明：`SlowReaderIntegrationTest`（真起 `Server`、真登录、对端接收缓冲 4 KB、排 16 MB 出站）先红后绿；探针 `slow-reader` 在修复后的进程上退 0（`Connection closed` 与 `Login success` 相隔 15 s），在把 `closeOnceFlushed` 首行守卫改成 `!=` 的进程上退 1（末行 `the server is still holding the socket after 30.0s`）。TC-GAME-069。

### G0b 离线身份：profile UUID 采信对端自选值（已修）

- 复现：`LoginStart{username=Notch, profileId=随机}`。原版客户端离线时填的是 `OfflinePlayer:Notch` 的 name-UUID，所以看起来「同名即同 UUID」，但那是客户端算的；`LoginListener` 原样采信。
- 影响：`synaxis:duplicate-login` 按 UUID 判，换个 UUID 就能同名上线两次；也能占住别人名字派生的 UUID，让那个人在自己还没上线时就被拒。`docs/DESIGN.md` §10 / §14 写的「UUID 由用户名派生」在实现里并不成立。
- 处置：`GameProfile#offlineUuid(username)`（与原版客户端同一算法，合法客户端看不出变化），`LoginListener` L80 只用它；`Velocity` / `Bungee` 走各自的转发身份不受影响。
- 边界（写清楚，不是缺口）：离线模式没有任何凭据，谁都能用任何名字登录，「冒名」是固有的。这道闸只保证「一个名字永远对应同一个 UUID、同一时刻只有一条在线连接」，不保证名字属于谁；要保证归属只能上 Mojang 会话鉴权或代理转发。
- 证明：`PreAuthConnectionIntegrationTest#theOfflineUuidComesFromTheUsernameNotFromThePeer` 先红（`PreAuthConnectionIntegrationTest.java:181`）后绿；探针 `login` 在 25567 上拿到 `uuid=a0d7d0d3-bb61-3352-b332-52bfa31d6c0e`，即 `OfflinePlayer:probe` 的 name-UUID。TC-GAME-070。

### G1 连接数无上限，fd 用尽时 accept 循环空转（登记）

- 复现：一个对端以 100 连接/秒开合，每条在 30 s 登录超时之前常驻，稳态 3,000 条；再开到 fd 上限，`accept()` 抛 `IOException`，默认被吞掉后 `continue`。
- 影响：每条连接 3 条虚拟线程 + 16 KB 堆外读缓冲 + `PlayerSocketConnection`；fd 耗尽后 accept 线程吃满一个核，日志一行都不写。
- 处置：登记 `docs/DESIGN.md` §28（触发条件「端口对不受控的连接来源开放之前」）。上限是全局还是按 IP、超限时直接关还是回 `Disconnect`、accept 失败后退避多久，三个都是新的生产开关，本轮按要求不加。

### G2 `socketTimeout` 对读循环是空操作（登记）

- 事实：`Server#configureSocket` 用 `Socket#setSoTimeout` 设的是 `SO_TIMEOUT`，它只对 `Socket` 的 `InputStream#read` 生效，`SocketChannel#read` 不看它，而读循环用的正是后者。
- 影响：让人以为有 15 s 读超时。真正的空闲上界是登录前 `loginTimeout`、登录后 `keepAliveKick`，本轮各有测试钉住。
- 处置：登记 `docs/DESIGN.md` §28；删掉是删公开配置项与系统属性 `synaxis.socket-timeout`，走 §8 演进与弃用。

### G3 核心模式的区块加载由对端驱动（登记）

- 复现：核心模式登录后，每个位置包把自己挪到一个新区块；`PlayerPositionListener` 只判有限与 `MAX_COORDINATE`，服务端为每次跨区块加载 `chunkViewDistance=10` 的一圈 441 个区块，从不卸载。
- 影响：按 `playerPacketPerTick=50` 折算，每秒 1,000 次跳跃 × 441 个区块的生成 CPU 与常驻内存，全由一条连接决定。对局实例整图预载、`enableAutoChunkLoad(false)`，不受影响。
- 处置：登记 `docs/DESIGN-M2.md` §28.2。核心模式只服务压测与冒烟，不对玩家开放；要修是给核心世界关自动加载，或在引擎侧加按速度的位置校验。

### G4 玩法模式下玩家是可见的生存玩家，位置与方块都由客户端定（登记）

- 事实：`RtsPlugin#admit` 只 `Accept(instance, team)`，没有设游戏模式；`ADR-M2-013` 写的旁观、不可见、位置由镜头锚点传送驱动，是 `REQ-M2-018` 的内容，尚未落地。
- 影响：改过的客户端能用位置包自己挪实体、自己决定兴趣集（M2 没有战争迷雾，全图单位对所有人可见，所以没有信息泄露）；能用 `FINISHED_DIGGING` 删地图方块（不校验挖掘时长），导航网格烘一次不跟着变，方块变更广播给全部 viewer。
- 处置：登记 `docs/DESIGN-M2.md` §28.2，随 `REQ-M2-018`（旁观模式 + 服务端传送）一并消失，不单独修——那半个 REQ 归阶段 6b，本轮不越界。

### G5 聊天是一进 N 出的放大面（登记）

- 事实：`ChatMessageListener#chatMessageListener` 把每条 `ClientChatMessagePacket` 经 `PlayerChatEvent` 广播给全部在线玩家；客户端模组不用聊天，宿主也没有取消这个事件。
- 影响：上界是 1,000 包/秒 × 在线人数 × 256 字符，50 人时 12.8 MB/s 出站，全由一条连接决定。
- 处置：登记 `docs/DESIGN-M2.md` §28.2；要不要聊天是玩法决定，宿主在 `PlayerChatEvent` 上取消或限频都是一行，等定了再做。

### G6 选中与下令不判兴趣集（按设计确认）

`GameChannel#commands` 只判归属，与 `API-M2-004` 一致；M2 没有战争迷雾。`docs/DESIGN-M2.md` §28.2 记一条「已决」，触发条件是战争迷雾立项。

## 3. 没测到的

- 模糊测试本身（随机畸形帧的持续跑）——`docs/TEST.md` §2 那行现在指向本盘点，模糊测试仍无归属。
- `Auth.Velocity` / `Auth.Bungee` 的转发头校验分支（单机不开，与 soak 报告「局限」同款）。
- 多 tick 线程（`dispatcherThreads > 1`）下的崩溃隔离。
- 本盘点只读了握手 / 登录 / 配置期的 11 个内联包与游戏侧三条消息的处理路径；PLAY 态其余原版包的监听器（背包、载具、书、告示牌……）只靠 2026-09-15 审计的 F06 / F12 / F45～F48 那批修复，没有逐个重看。

## 4. 复跑

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
# 四个新测试类（五个方法）
./gradlew :test --tests '*SlowReaderIntegrationTest*' --tests '*PreAuthConnectionIntegrationTest*' \
  --tests '*PacketReadingMalformedTest*' --tests '*ConnectionManagerIntegrationTest*' \
  --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts
# 核心模式起服后（docs/research/soak-2026-09-18/README.md 的起服段，加 -Dsynaxis.app.port=25567）
python3 tools/protocol-probe/probe.py --port 25567 --username slowprobe slow-reader   # 退 0
python3 tools/protocol-probe/probe.py --port 25567 login --hold 2                      # uuid=a0d7d0d3-…
```

本轮实跑的数字（探针输出与服务端日志的逐行摘录在本目录 `evidence-2026-09-22.txt`）：全量 `check` 3,133 个测试 0 失败；`slow-reader` 在修复后的进程上 `exit code: 0`、服务端 `Login success` 与 `Connection closed` 相隔 15.1 s、0 行堆栈；在守卫反转的进程上 `exit code: 1`、两行相隔 15.2 s（逻辑上已断、socket 还开着）；`alloc-bomb` / `nbt-bomb` / `zero-frame` 各一行 `synaxis:protocol-error` WARN、无堆栈，整份日志就这 3 行 WARN。
