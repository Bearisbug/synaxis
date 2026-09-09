# 01-network 修复去向表

- **分支**：`fix/network`（worktree `/Users/bug/Documents/Projects/Minecraft/.worktrees/network`），基于 `fork/main`（已含上游 PR #3347、#3346）
- **全量测试**：`./gradlew :test --continue --rerun-tasks` → **2513 tests / 0 failures / 0 errors / 17 skipped**，BUILD SUCCESSFUL
- **提交数**：22（每条发现一个 commit），`git status` 干净，未 push

---

## 去向表

| 编号 | 去向 | commit | 测试 |
|---|---|---|---|
| P0-1 客户端抢跑 `ClientFinishConfigurationPacket` | 已修 | `f218b69807` | `PrematureFinishConfigurationIntegrationTest`（2 例） |
| P0-2 PROXY v2 超大 payload 忙等 | 已修 | `97c46f6bf5` | `ProxyProtocolDecoderTest#rejectsV2HeaderLargerThanTheReadBuffer` |
| P1-1 `packetLength == 0`（#3320） | 已修 | `78cf3c7102` | `PacketReadingMalformedTest#zeroLengthFrameIsAProtocolError` |
| P1-2 超长 VarInt 被误判成容量不足 | 已修 | `866545b792` | `PacketReadingMalformedTest`（overlong / truncated 两例） |
| P1-3 `compress` 静默截断 | 已修 | `1772fe6fe4` | `PacketCompressionTest`（2 例） |
| P1-4 超大包卡死写线程 | 已修 | `0e47546755` | `PacketWriteQueueTest#aPacketTooLargeToEverFitIsDroppedNotRetried` |
| P1-5 未守卫的 `ClientConfigurationAckPacket` | 已修 | `8ef1664a65` | `UnsolicitedConfigurationAckIntegrationTest` |
| P1-6 `LazyType#read` 恒返回 null | 已修 | `12fe53b3f4` | `NetworkBufferTypeWrapperTest#lazyTypeRoundTrips` |
| P1-7 `MaxLength#write` 丢 registries | 已修 | `0e358fe61f` | `NetworkBufferTypeWrapperTest#maxLengthKeepsTheBufferRegistries` |
| P1-8 `EntityTrackerImpl` 双重泄漏 | **移交实体** | — | — |
| P2-1 `writePacketSync` 提前推进 `serverState` | 已修 | `eef80470c9` | `SocketWriteStateTransitionTest` |
| P2-2 `unlockWriteThread` 漏唤醒 | 已修 | `b39b5b595a` | 无（见下「无测试说明」） |
| P2-3 `compressionStart` 用已写出包数分界 | **延后** | — | — |
| P2-4 集合/映射负数 size | 已修 | `2e57f47e07` | `NetworkBufferTypeWrapperTest#negativeCollectionSizesAreRejected` |
| P2-5 `NetworkBuffer.Enum` 无边界检查 | 已修 | `5056b2fc14` | `NetworkBufferTypeWrapperTest#enumTypeRejectsOutOfRangeOrdinals`、`ClientPlayerActionPacketTest` |
| P2-6 `StringTerminatedType#read` 返回容量数组 | 已修 | `3db44ae656` | `NetworkBufferTypeWrapperTest#terminatedStringRoundTrips` |
| P2-7 定长类型 `read()` 无 `ensureReadable` | **跳过** | — | — |
| P2-8 `VarLongType#read` 抛 `RuntimeException` | 已修 | `0e0e5a35f9` | `NetworkBufferRegistriesTest#variableLengthBounds`（原 todo 已落实） |
| P2-9 无监听器的包每包一行 WARN | 已修 | `6f9405d40d` | 无（见下「无测试说明」） |
| P2-10 状态请求无一次性守卫 | 已修 | `c29e6d93f6` | `StatusRequestGuardIntegrationTest` |
| P2-11 未加载区块 / null instance 上 NPE | 已修 | `e911f34b0c` | `UnloadedChunkListenerIntegrationTest`（2 例） |
| P2-12 yaw/pitch 无 NaN/Inf 校验 | 已修 | `51b8ebae45` | `PlayerRotationValidationIntegrationTest` |
| P2-13 `PACKET_POOL` 不缩容 + VarInt3 逃逸 | **跳过** | — | — |
| P3-1 `sendPackets` 泛型协变（#3346） | **核实后已在基线修复** | — | — |
| P3-2 `Status.Builder` 未初始化时 NPE（#3342） | **跳过** | — | — |
| P3-3 `ConnectionManager#shutdown` 第二个循环是死代码 | **跳过** | — | — |
| P3-4 HANDSHAKE/STATUS 下 `kick` 抛异常 | 已修 | `d2dd0442de` | `PlayerConnectionKickTest` |
| 05/P0-4 递归网络类型无深度上限 | 已修 | `86db35d2fd` | `NetworkBufferRecursiveDepthTest`（2 例） |
| 04/P1-5 `PacketViewableUtils.storageMap` 无锁 `WeakHashMap` | 已修 | `70eb5ba8d2` | `PacketViewableUtilsConcurrencyTest` |

合计 29 条：**已修 22 条**（含两条跨报告，对应 22 个 commit）、移交 1 条、跳过 4 条、延后 1 条、
核实后基线已修复 1 条。

---

## 跳过 / 延后 的理由

**P2-3（延后）**：`compressionStart = sentPacketCounter.get()` 记录的是「已写出」包数，正确的分界是
`SetCompressionPacket` 在队列里的位置。队列长度在并发 `sendPacket` 下不可靠取样，包本身也没有序号，
要修必须给出「按包标记分界」的新机制（例如在写循环里识别 `SetCompressionPacket` 后翻转标志），
属于设计改动而非局部修复。原报告对触发条件的判定也是【疑似】：正常登录流程下队列为空，
需要插件/调度器在登录过程中并发向该连接发包才会踩到。留给一次专门的压缩路径重构。

**P2-7（跳过）**：给全部定长 `read()` 补 `ensureReadable` 会在最热的解析路径上每字段加一次分支，
涉及 30 余个类型，需要基准测试支撑。当前实际可达的读路径由 `PacketReading` 的
`buffer.slice(offset, packetLength, ...)` 保护（切片容量恰好等于包长），越界会由 segment 边界兜住，
没有可被客户端触发的缺口。

**P2-13（跳过）**：两半都不适合本轮改。① 池化缓冲不缩容是刻意的性能取舍，靠 `SoftReference` 兜底，
改成缩容需要分配率/GC 基准；② `VarInt3 out of bounds` 逃逸在 P1-4 修好后不再导致写线程空转——
超规格包现在要么被 `writeQueue` 丢弃并报 `IllegalStateException`，要么以 `IllegalArgumentException`
断开连接，都是可见的终止，只是诊断信息不如「Packet too large」明确。

**P3-2（跳过）**：`ping/Status.java` 不在本人文件归属范围（`network/**`、`listener/**`、`crypto/**`），
且上游 PR #3342 已在处理；影响面限于「`MinecraftServer.process()` 为 null 时构造 Status」，
运行中的服务器不受影响。

**P3-3（跳过）**：`ConnectionManager#shutdown` 第二个 `for (playPlayers)` 循环确认是死代码
（第一个循环的 `kick()` → `disconnect()` → `removePlayer()` 已清空 `playPlayers`），但删除它没有行为变化，
属于纯清理；且 shutdown 语义与 #2907/#1636 挂钩，归线程子系统同事。已核实、不改。

**P1-8（移交实体）**：`EntityTrackerImpl` 的 `viewers` 与 `chunkEntities` 泄漏在 `instance/**`，按分工归实体同事。

---

## 无测试说明（2 条）

- **P2-2**：只在 `-Dminestom.new-socket-write-lock=true` 下生效，且该 flag 在 `ServerFlag` 里是
  类初始化时读取的常量，无法在同一 JVM 内切换；缺陷本身是 park/unpark 的时序窗口，
  没有可稳定复现的断言。修改是把 `writeSignaled.set(false)` 挪到队列判空之前的三行重排，
  正确性由「先清标志再检查队列」这一标准模式保证。
- **P2-9**：改动是日志去重（每个包类只 WARN 一次）。测试环境没有 SLF4J provider
  （测试输出里可见 `No SLF4J providers were found`），无法断言日志行数。

`PacketViewableUtilsConcurrencyTest`（04/P1-5）是压力测试，修复前后都能通过——数据竞争不会稳定复现。
它锁定的是可断言的契约（并发 prepare/flush 全程不抛异常、每个注册过的 storage 最终都被 flush 到），
作为回归护栏而非复现用例。这一点在此如实记录。

---

## 需要主管注意的合并点

本分支只在两个非 `network/**`、`listener/**` 的文件里各改了一个小 hunk，均为 P0-1 所需：

1. `src/main/java/net/minestom/server/entity/Player.java`，`UNSAFE_init()`（:339 附近）
   —— 新增 2 行 `pendingInstance` 空检查。**实体同事会改同一文件的其他位置。**
2. `src/main/java/net/minestom/server/ServerProcessImpl.java`，`TickerImpl#tick`（:279 附近）
   —— 把 `connection().tick(nanoTime)` 包进 try/catch。**线程同事会改同一文件的 `stop()`。**

另外两处可能与其他人重叠：

- `src/main/java/net/minestom/server/utils/PacketViewableUtils.java` 整体重写了 `storageMap` 的并发方案
  （04/P1-5）。若线程同事也在 04 报告里动这个文件，以本分支版本为准或合并时统一。
- `src/main/java/net/minestom/server/network/player/PlayerConnection.java` 新增了一个
  `@ApiStatus.Internal boolean acquireStatusRequest()`（P2-10），是纯增量 API，不影响既有调用方。

## 顺带记录（未修，非本轮范围）

- `PlayerSocketConnection#read` 的 `Empty` 分支不做 `compact()`：读缓冲被一个「装得下但还没收全」的包填满、
  且 `readIndex > 0` 时，`readChannel` 会对零剩余空间反复返回 0，形成与 P0-2 同形的忙等。
  报告里没有这条，也不在指派范围内，仅记录以便后续排期。
- `CodecImpl.RecursiveImpl`（`codec/**`）与 `NetworkBufferTypeImpl.RecursiveType` 同构，同样没有深度计数
  （05/P0-4 的另一半）。按文件归属留给 codec 同事。
