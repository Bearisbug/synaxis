# 04-threading-events 修复去向表

- 分支：`fix/threading`（worktree `/Users/bug/Documents/Projects/Minecraft/.worktrees/threading`），基于 `fork/main` 当时的 HEAD `2dbc3ed22b`
- 全量测试：`./gradlew :test --continue --rerun-tasks` → **2504 tests / 0 failures / 0 errors / 17 skipped**，BUILD SUCCESSFUL（基线 2488 + 本轮新增 16 条）
- `./gradlew testNamingCheck forbiddenImportsCheck errorproneCheck` 全绿
- jcstress：`EventNodeHandleInvalidateTest` 实跑 **OK / 1,544,211 samples / 只观测到 `true`**
- 共 12 个 commit，`git log fork/main..fix/threading --oneline` 可见；`git status` 干净；未 push

---

## 去向表

| 编号 | 去向 | commit | 测试 |
|---|---|---|---|
| P0-1 从 tick 线程停服必然死锁（#2907） | 已修 | `052634ef1f` | `ThreadDispatcherTest#shutdownFromTickThread`、`ServerProcessTest#stopFromTickThread` |
| P0-2 `Handle` 缓存失效竞争 → 节点永久丢事件 | 已修 | `98e62bd7b1` | jcstress `EventNodeHandleInvalidateTest`（实跑 OK） |
| P0-3 `mappedConsumer()` 热路径无锁读 `WeakHashMap` | 已修（方案与报告不同，见下） | `2d96548739` | `EventNodeMappedConcurrencyTest#concurrentDispatchAfterMappedOwnersCollected`（回归护栏，非确定性复现） |
| P1-1 `setInstance().join()` 在任意线程驱动全局 Scheduler | 已修（消费侧加锁，未动 `Player.java`） | `5bc253b1ac` | `TestScheduler#concurrentProcess` |
| P1-2 `refreshThreads` 迁移 partition 后不 `assign` | 已修 | `84a9f7113d` | `ThreadDispatcherTest#refreshThreadsReassignsAcquirableOwner` |
| P1-3 `park()`/`unpark()` lost-wakeup | 已修 | `6f3fde0d07` | `TestScheduler#unparkWhileTaskIsStillRunning` |
| P1-4 `unparkTask` 忽略 `executionType` | 已修 | `6f3fde0d07` | `TestScheduler#unparkTickEndTask` |
| P1-5 `PacketViewableUtils.storageMap` | **移交网络**（已在 `fork/main` 修复，见下） | — | — |
| P1-6 #3353 让 `drain()` 语义分叉 | 已修 | `5bc253b1ac` | `TestScheduler#immediateRepeatRunsOncePerProcess` |
| P2-1 `refreshThreads` 超时参数毫秒当纳秒 | 已修 | `00f2f6df58` | 无（私有 `TickerImpl` 内部，见「无测试说明」） |
| P2-2 `unregister` 用 `return` 而非 `continue` | 已修 | `2520ecf3f7` | `EventNodeBindingTest#unregisterSkipsUnknownEventTypes` |
| P2-3 `unmap` 后再加监听器不生效 | 已修 | `2520ecf3f7` | `EventNodeMapTest#remapAfterUnmap` |
| P2-4 `map` 不替换已回收的缓存条目 | 已修 | `2520ecf3f7` | `EventNodeMapTest#mapAfterNodeCollected` |
| P2-5 `AcquirableCollection.acquireSync` 对未分配线程 NPE | 已修 | `891f6180f6` | `AcquirableCollectionTest`（2 例） |
| P2-6 `stop()` 的关闭顺序与不 join | 已修（第 3 点跳过，见下） | `052634ef1f` | 同 P0-1 |
| P2-7 `ThreadDispatcherImpl` 的两个 `WeakHashMap` 名不副实 | 已修 | `e89861d2b1` | `ThreadDispatcherTest#removedElementIsReleased`、`#deletedPartitionIsReleased` |
| P2-8 `Entity.updateSnapshot` 无空值保护 | 已修（**跨文件小 hunk**，见下） | `08fab83eaf` | `EntitySnapshotTest#snapshotEntityWithoutInstance` |
| P3-1 `AcquirableImpl.enter` 自旋无退避 | 已修 | `0a56cea764` | 无 |
| P3-2 启动顺序（监听早于 tick 线程） | **跳过**（理由见下） | — | — |
| P3-3 `TickMonitor.acquisitionTime` 语义可疑 | 核实后非 bug，只补文档；2026-09-30 改为每条引擎线程记自己的阻塞时间、整 tick 取串行段加最长的一条 tick 线程并导出（`docs/DESIGN-PROD.md` v0.9.35 `API-PROD-016`「获取等待」） | `0a56cea764` | 无；2026-09-30 起 `docs/TEST.md` `TC-PROD-565`～`TC-PROD-569` |
| P3-4 `mpmcSpecialUnboundedArrayQueue` 名不副实 | 已修（重命名为 `mpmcArrayQueue`） | `0a56cea764` | 无 |
| P3-5 `ObjectPool` 死代码 | **跳过**（只提示不删，见下） | — | — |

---

## 与报告方案不同 / 需要主管注意的地方

### P0-3：报告建议的 `HashMap` 会破坏既有的 GC 语义

报告建议把 `mappedConsumer()` 里的 `handlers` 从 `WeakHashMap` 换成 `HashMap`
（「key 的强引用不会造成实体泄漏」）。**实测不成立**：换成 `HashMap` 后
`EventNodeMapTest#ownerGC` 与 `InstanceUnregisterIntegrationTest#testGCWithEventsLambda`
双双失败——编译好的 consumer 只在下一次 invalidate 时才被丢弃，而实体/实例被移除时
并不会触发 invalidate，于是强引用的 key 把 owner 一直吊着。

改为**构建后不再变更的弱键查找结构**：`Int2ObjectOpenHashMap<List<MappedHandler<E>>>`，
以 `owner.hashCode()` 分桶，每个条目同时弱引用 owner 与 handle，查找时按 `equals` 匹配
（保持 `WeakHashMap` 原本的 equals 语义，`EventFilter.ITEM` 依赖它）。
分发路径仍是 O(1) 且完全只读。

另外一处对报告结论的修正：`WeakHashMap.expungeStaleEntries()` 的表结构修改是在
`synchronized (queue)` 里做的，所以**多个 expunge 写者之间其实是互斥的**，报告里
「两个线程同时摘同一条链」的交错不成立。真正剩下的问题是无锁读者与 expunge 写者之间
没有 happens-before、`size` 会漂移，以及 JDK 明确不保证 `get()` 的只读性。修复仍然必要，
但严重度实际上低于报告给出的 P0。**本条没有确定性复现测试**：
`EventNodeMappedConcurrencyTest` 是回归护栏，修复前后都可能通过（中途一版「能复现」的
配置后来查明是 `ensureMap()` 的 O(n³) 拷贝导致超时，不是数据竞争，已弃用）。

### P2-6：关服顺序与报告不同

报告建议 `… → connection.shutdown() / server.stop() → 最后 scheduler.shutdown()`。
实际采用 `… → dispatcher.shutdown() + join → scheduler.shutdown() → connection.shutdown() → server.stop()`：
把用户关服任务放在踢人之后会让「存盘 / 广播 / 踢人时给理由」这类最常见的 shutdown task
拿到一个空的在线玩家列表，属于行为回退。报告真正要解决的是「用户任务与 tick 并发」，
放在 join 之后已经解决，无需再挪到 connection 之后。

**P2-6 第 3 点（static `SchedulerImpl.SCHEDULER` 守护线程池从不关闭）跳过**：该线程池是
所有 `SchedulerImpl` 实例共享的 static 字段，`MinecraftServer.updateProcess()` 会反复
创建新进程，关掉它会让后续进程的 `DurationSchedule` 直接失效。要修必须改成每进程一个
executor，属于 API 层面的改动，超出本轮范围。

### P0-1：`TickSchedulerThread` 的引用获取方式

`stop()` 需要 join 驱动 ticker 的线程，但 `MinecraftServer.start()` 创建 `TickSchedulerThread`
后连引用都不保存，而 `ServerProcess` 是公开接口。为了不给接口加公开方法，改成
**ticker 自注册**：`TickerImpl.tick()` 开头判断当前线程是不是 `TickSchedulerThread` 并记下来。
好处是 `ServerProcessTest#tick` 这类由主线程手动驱动 ticker 的用法不会被误 join。

### P1-1：没有动 `Player.java`

报告建议在 `Player#setInstance(...).join()` 里去掉 `scheduler.process()`。直接删会让
「调用线程正是驱动 scheduler 的线程」这一 deadlock 保护失效，所以改在消费侧：
`SchedulerImpl.runTasks` 用一把 `ReentrantLock` 保证 MPSC 队列只有一个消费者。
`Scheduler#process()` 的 javadoc 从「not thread-safe」改成「同一时刻只有一个调用者消费」；
`processTick()` / `processTickEnd()` 的 javadoc 保持不变，因为它们仍会各自推进 tick 计数。

### P2-8：唯一一处跨文件小 hunk

`src/main/java/net/minestom/server/entity/Entity.java` 的 `updateSnapshot()` 改了 8 行
（null 短路 + `AtomicReference` import）。**这是实体同事的文件**，合并时注意；改动只在这一个方法内。

### P1-5：移交网络

`utils/PacketViewableUtils.java` 属网络轮次。核对 `fork/main` 已包含
`70eb5ba8d2 fix(network): make the viewable packet storage map thread safe`
（改成 `Collections.synchronizedMap(new WeakHashMap<>())`），本轮不再重复处理。

### 合并点

- `ServerProcessImpl.TickerImpl.tick()`：本轮在方法第一行加了 ticker 自注册；网络同事在
  `connection().tick(nanoTime)` 外包了 try/catch。两处不相邻，三方合并应能自动完成。
- `ServerProcessImpl.stop()` 被整体重写为 `stop()` + `shutdownProcess()` + `joinThread()`。
- `utils/collection/ConcurrentMessageQueues.java`：重命名了 `mpmcSpecialUnboundedArrayQueue`
  → `mpmcArrayQueue`（唯一调用点 `utils/ObjectPool.java` 同步改）。网络同事若也动这个文件，
  改的是 `mpscArrayQueue`，不冲突。

---

## 跳过说明

- **P3-2 启动顺序**：`serverProcess.start(address)` 里 `server.start()` 一开始接受连接，
  而 `dispatcher().start()` 与 `TickSchedulerThread` 在其后启动，确实存在微秒级窗口。
  但这个窗口里发生的只有握手 / 状态查询（纯网络线程处理），进入 play 状态本来就要等好几个 tick，
  不会读到任何未初始化状态。把 `dispatcher().start()` 提前只能关掉一半窗口，
  还会在绑定失败时留下一组已启动的 tick 线程；彻底关掉需要重构 `start()` 的分阶段语义。
  判定为纯代码异味，跳过。
- **P3-5 死代码**：`ObjectPool.register(Object, AtomicReference)` / `register(Object, Collection)` /
  `hold()` / `use()` / `BufferRefCleaner` 在 `src/main` 内确无调用点，`BufferRefCleaner.run()`
  对 `objectRef.get()` 也确实缺 null 检查。按「发现无关死代码只提示不删」的约定不动，
  在此登记给主管决定是否单独删。

## 无测试说明

- **P2-1**（refreshThreads 纳秒预算）：计算在私有的 `ServerProcessImpl.TickerImpl.serverTick` 内，
  唯一可观测的外部效果是 `refreshThreads(nanoTimeout)` 的入参，从外部无法在不加钩子的情况下断言。
  单位错误由阅读确认（毫秒减在纳秒上），改动是 2 行。
- **P3-1 / P3-3 / P3-4**：分别是自旋退避、javadoc 澄清、方法重命名，无行为变化可断言。

## 已知的既有失败（非本轮引入）

`./gradlew :jcstress-tests:jcstress` 整体跑不过：`net.minestom.server.utils.ObjectPoolTest`
在 pre-test 阶段就抛 `NullPointerException: serverProcess`
（`PacketVanilla.PACKET_POOL` 的 supplier 调 `MinecraftServer.getRegistries()`，而该测试从不
`MinecraftServer.init()`）。与本轮改动无关（本轮对 `ObjectPool` 只做了方法重命名），
但意味着 jcstress 套件目前不能整体作为门禁。另外该 gradle 插件与本仓库的
configuration cache / isolated projects 不兼容，跑它需要
`-Dorg.gradle.isolated-projects=false -Dorg.gradle.configuration-cache=false`。
