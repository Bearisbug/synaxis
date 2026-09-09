# Minestom 只读代码审计：instance / chunk / block / light / collision / world

- 仓库：`/Users/bug/Documents/Projects/Minecraft/Minestom`（upstream master fork，HEAD `16842f1954`，2026-09-04）
- 范围：`net.minestom.server.instance`（77 文件）、`world`（33）、`collision`（14）、`coordinate`（9），以及为定位根因而追踪到的 `entity/Player`、`utils/chunk`、`network/packet/server/play/data`
- 方法：全文件阅读 + `grep` + `git blame` + 上游 issue/PR 交叉比对。**未运行任何构建或测试**（按约束禁止 Gradle）。

---

## ① TL;DR

这一层的代数逻辑（坐标换算、位打包、调色板、扫掠碰撞）质量相当高，边界推导基本经得起推敲；**真正的问题几乎全部集中在"并发生命周期"和"跨模块契约"上**：谁持有哪把锁、future 失败后谁来兜底、缓存条目由谁回收，这三件事在 instance 层没有统一约定，于是同一类缺陷在区块加载、光照、批量方块、Anvil 存档里各出现了一遍。

最严重的三个：
1. **光照重算会与区块写锁互锁**（`LightingChunk#flushQueue` 把任务丢到共享线程池并 `join()`，池线程去抢调用方已持有的写锁）——一个在 `BlockHandler#onPlace` 里查光照的插件就能把 tick 线程永久冻住。
2. **区块加载失败会让 `Player#setInstance` 的 future 永不完成**，同线程 `join()` 落到 `latch.await()` 上，服务器主循环直接停摆。
3. **`EntityTrackerImpl` 有两处无界泄漏**，其中 `viewers` 那处因为 `ChunkViewKey#equals` 用引用比较、而 key 里的 list 每次都是新对象，**每加载一次区块就永久新增一条**——这正是 issue #2649 至今未愈的部分原因。

另外还定位到 issue #3021（视距与 setBlock）和 issue #3083（PlayerChunkUnloadEvent）的确切根因，二者都不在事件系统里，而在 `EntityTrackerImpl$ChunkView` 和 `InstanceContainer#unloadChunk` 里。

统计：**P0 × 3，P1 × 7，P2 × 11，P3 × 8**。

---

## ② 发现清单

### P0-1　光照重算与区块写锁互锁，tick 线程永久挂死

**严重度**：P0（服务器冻结）
**位置**：`src/main/java/net/minestom/server/instance/LightingChunk.java:409-439`、`src/main/java/net/minestom/server/instance/Instance.java:1073-1087`、`src/main/java/net/minestom/server/instance/InstanceContainer.java:161-228`

**问题描述**

`flushQueue` 把每个待重算的 section 丢进 **静态共享线程池**，池线程第一件事就是抢该区块的读锁，然后调用方原地 `join()`：

```java
// LightingChunk.java:409-439
CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
    lightingChunk.lockReadLock();          // 池线程在这里等写锁释放
    try { toAdd = switch (queueType) { ... }; }
    finally { lightingChunk.unlockReadLock(); }
    ...
}, pool);
tasks.add(task);
...
tasks.forEach(CompletableFuture::join);    // 调用线程在这里等池线程
```

而 `InstanceContainer#UNSAFE_setBlock`（`InstanceContainer.java:171`）在调用 `chunk.setBlock(...)` 之前已经拿了 **同一个区块的写锁**，`chunk.setBlock` 会同步回调 `BlockHandler#onPlace` / `onDestroy`（`DynamicChunk.java:126-135`）。只要回调里调用 `Instance#getBlockLight/getSkyLight`，就会走到：

```java
// Instance.java:1084-1086
if (light.requiresUpdate())
    LightingChunk.relightSection(chunk.getInstance(), chunk.chunkX, sectionCoordinate, chunk.chunkZ);
```

`ReentrantReadWriteLock` 的读锁在有线程持写锁时会阻塞，于是：调用线程持写锁 → `join()` 等池线程 → 池线程等写锁。**经典的锁-线程池死锁**，且是永久性的（没有超时）。

触发条件还特别容易满足：`LightingChunk#setBlock`（`LightingChunk.java:169-178`）在 `super.setBlock` 之后立刻 `invalidateNeighborsSection`，把本 section 的 `isValidBorders` 置 false，因此 `requiresUpdate()` 在 handler 回调时**必然**为 true。

**触发场景**

使用 `LightingChunk` 作为 `ChunkSupplier`（Minestom 官方文档推荐做法），注册一个在 `onPlace` 里读取光照的 `BlockHandler`（发光方块、红石灯、生长逻辑都会这么写），然后放置该方块。

**影响**

tick 线程与该分区上所有实体/区块的 tick 永久停止；由于 `ThreadDispatcher` 的 tick 用 `CountDownLatch` 汇合，整个服务器主循环挂死。远程可由普通玩家放置方块触发。

**建议修复**

- 让 `flushQueue` 在 `INTERNAL` 阶段不要重新抢锁：由调用方在进入 `relight` 之前统一取锁，或改用"快照 palette + 无锁计算"。
- 更小的改动：在 `Instance#getBlockLight/getSkyLight` 里，若 `chunk.holdsWriteLock()` 为真则跳过 relight，直接返回当前（可能过期的）值：

```diff
-        if (light.requiresUpdate())
+        if (light.requiresUpdate() && !chunk.holdsWriteLock())
             LightingChunk.relightSection(chunk.getInstance(), chunk.chunkX, sectionCoordinate, chunk.chunkZ);
```

- 长期方案参考 open PR #3295（把 LightEngine 抽出来），把光照重算移出方块写路径，做成 tick 末尾的批处理。

**建议的复现测试**（`src/test/java/net/minestom/server/instance/LightDeadlockIntegrationTest.java`）

```java
@EnvTest
public class LightDeadlockIntegrationTest {
    @Test
    public void relightInsideBlockHandlerDoesNotDeadlock(Env env) {
        var instance = env.createFlatInstance();
        instance.setChunkSupplier(LightingChunk::new);
        instance.loadChunk(0, 0).join();

        var key = Key.key("test:light_probe");
        var probed = new AtomicInteger();
        BlockHandler handler = new BlockHandler() {
            @Override public Key getKey() { return key; }
            @Override public void onPlace(Placement placement) {
                // 此时 InstanceContainer 正持有该区块写锁
                probed.set(placement.getInstance().getBlockLight(1, 41, 1));
            }
        };
        var thread = Thread.ofVirtual().unstarted(() ->
                instance.setBlock(1, 41, 1, Block.GLOWSTONE.withHandler(handler)));
        thread.start();
        assertDoesNotThrow(() -> thread.join(Duration.ofSeconds(5)));
        assertFalse(thread.isAlive(), "setBlock deadlocked inside the lighting engine");
    }
}
```

**判定**：【读代码确认】（锁结构与 `join()` 关系可静态确认；实际挂死建议再跑一次上面的测试确认）
**关联**：issue #3158（Lighting 总目）、open PR #3295

---

### P0-2　区块加载失败 → `Player#setInstance` 的 future 永不完成，同线程 `join()` 永久阻塞

**严重度**：P0（线程永久阻塞 / 玩家卡在加载界面）
**位置**：`src/main/java/net/minestom/server/entity/Player.java:704-733`、`src/main/java/net/minestom/server/instance/InstanceContainer.java:377-394`

**问题描述**

`retrieveChunk` 在链上挂了一个 `whenComplete` 记录异常，但**并没有吞掉异常**——返回给调用方的 future 依然是异常完成的：

```java
// InstanceContainer.java:385-394
var _ = chain.whenComplete((chunk, e) -> {
    if (e != null) MinecraftServer.getExceptionManager().handleException(...);
    else EventDispatcher.call(new InstanceChunkLoadEvent(this, chunk));
});
return chain;
...
return future.whenComplete((_, _) -> loadingChunks.remove(index, future));
```

`Player#setInstance` 用 `allOf(...).thenRun(...)` 收敛，而 `thenRun` **在上游异常时不会执行**：

```java
// Player.java:725-732
var _ = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
        .thenRun(() -> {
            scheduler.scheduleNextProcess(() -> {
                runnable.accept(instance);
                future.complete(null);
            });
            latch.countDown();          // ← 异常时永远不会执行
        });
```

返回的 `future` 覆写了 `join()`：如果调用线程就是发起线程，它会 `latch.await()`（`Player.java:711`）。`latch` 永不 `countDown` ⇒ **该线程永久阻塞**；即使不 `join()`，`spawnPlayer` 也永远不会被调用，玩家永远停在 "Loading terrain"。

**触发场景**

任何自定义 `ChunkLoader#loadChunk` 抛异常（读数据库/网络超时/文件损坏），或自定义 `ChunkSupplier` 返回 null（`InstanceContainer.java:401` 的 `requireNonNull`），或 `Chunk#onLoad/onGenerate` 抛异常。视距范围内**任意一个**区块失败即可。

**影响**

单个坏区块导致玩家永久卡死；若 `setInstance` 在 tick 线程上被 `join()`（常见写法），整个服务器停摆。

**建议修复**

用 `handle` 代替 `thenRun`，失败时也走完流程（可选择踢出玩家或用空区块占位）：

```diff
-        var _ = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
-                .thenRun(() -> {
+        var _ = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
+                .whenComplete((_, error) -> {
+                    if (error != null) MinecraftServer.getExceptionManager().handleException(error);
                     scheduler.scheduleNextProcess(() -> { runnable.accept(instance); future.complete(null); });
                     latch.countDown();
                 });
```

同时建议 `ChunkUtils#optionalLoadAll`（`utils/chunk/ChunkUtils.java:33-44`）也做同样处理——它的 javadoc 明说"任一区块失败则异常完成"，但所有调用方都没有异常分支。

**建议的复现测试**

```java
@EnvTest
public class ChunkLoadFailureIntegrationTest {
    @Test
    public void failingLoaderDoesNotHangSpawn(Env env) {
        var instance = env.process().instance().createInstanceContainer(new ChunkLoader() {
            @Override public @Nullable Chunk loadChunk(Instance i, int cx, int cz) {
                if (cx == 3 && cz == 0) throw new IllegalStateException("boom");
                return null;
            }
            @Override public boolean supportsParallelLoading() { return true; }
        });
        var connection = env.createConnection();
        var future = CompletableFuture.supplyAsync(
                () -> connection.connect(instance, new Pos(0, 42, 0)));
        assertDoesNotThrow(() -> future.get(5, TimeUnit.SECONDS),
                "setInstance never completed after a chunk load failure");
    }
}
```

**判定**：【读代码确认】
**关联**：issue #2900（unregister 时 spawn 抛异常）语义相邻

---

### P0-3　AnvilLoader 保存与卸载并发 → 同一 region 文件出现两个 `RegionFile` 实例，存档可被写坏

**严重度**：P0（数据损坏）
**位置**：`src/main/java/net/minestom/server/instance/anvil/AnvilLoader.java:366-397`、`AnvilLoader.java:571-598`、`src/main/java/net/minestom/server/instance/anvil/RegionFile.java:94-140`

**问题描述**

三处配合出问题：

1. `AnvilLoader#saveChunk` 用 `fileCreationLock` 保护 `alreadyLoaded.put(...)`（`AnvilLoader.java:380`），而 `getMCAFile` 用的却是 **另一把锁** `perRegionLoadedChunksLock`（`AnvilLoader.java:189-209`）。两条创建路径互不排斥。
2. `AnvilLoader#unloadChunk` 在该 region 最后一个区块卸载时把 `RegionFile` 从 `alreadyLoaded` 里摘掉并 `close()`：

```java
// AnvilLoader.java:583-592
if (chunks.isEmpty()) {
    perRegionLoadedChunks.remove(regionIndex);
    RegionFile regionFile = alreadyLoaded.remove(RegionFile.getFileName(regionX, regionZ));
    if (regionFile != null) regionFile.close();   // ← 不持有 RegionFile 内部的 lock
}
```

3. `RegionFile#close()`（`RegionFile.java:137-140`）**没有获取 `RegionFile.lock`**，而 `writeChunkData` 的整段扇区分配 + 数据写 + 头写都在这把锁里。

于是有两种损坏路径：

- **写到一半被 close**：`saveChunksToStorage()` 走虚拟线程（`InstanceContainer.java:328-331` + `optionalAsync`），tick 线程同时 `unloadChunk` → `file.write(...)` 抛 `IOException: Stream Closed`，`writeHeader()` 不执行，扇区位图与磁盘头不一致。
- **两个 `RegionFile` 并存**：close 之后 `alreadyLoaded` 里没有条目，另一线程 `getMCAFile` 会 `new RegionFile(path)` 重新打开同一文件。此时两个对象各自维护 `locations[]` / `freeSectors`，**会把同一批空闲扇区分配给不同区块，并各自把整份 8KB 头写回去**——后写者覆盖先写者的头，先写者的数据成为孤儿扇区，对应区块永久丢失。`AnvilLoader.java:200` 的 `assert previousVersion == null` 正是为这种情况准备的，但断言在生产环境默认关闭。

**触发场景**

任何"自动卸载远处区块 + 周期性 `saveChunksToStorage()`"的常见服务器配置，玩家移动时必然产生并发。

**影响**

Anvil 存档静默损坏（区块回退到未生成状态），且 `loadMCA` 的容错设计会把损坏区块当作"没有数据"重新生成，**再一次保存就把损坏固化了**。

**建议修复**

- `RegionFile#close()` 加 `lock.lock()`，并加 `closed` 标志让后续 `read/writeChunkData` 快速失败。
- `AnvilLoader` 用单一锁（或直接把 `alreadyLoaded` 的创建全部收敛到 `getMCAFile`），删掉 `fileCreationLock` 这条平行路径。
- `unloadChunk` 关闭 region 前，应确认没有 in-flight 的 `saveChunk`（引用计数或 `StampedLock`）。

**建议的复现测试**

```java
public class RegionFileConcurrencyTest {
    @Test
    public void closeDuringWriteDoesNotCorrupt(@TempDir Path dir) throws Exception {
        var loader = new AnvilLoader(dir, DimensionType.OVERWORLD.key());
        var instance = new InstanceContainer(UUID.randomUUID(), DimensionType.OVERWORLD, loader);
        var chunk = instance.loadChunk(0, 0).join();
        var stop = new AtomicBoolean();
        var saver = Thread.ofVirtual().start(() -> {
            while (!stop.get()) loader.saveChunk(chunk);
        });
        for (int i = 0; i < 200; i++) loader.unloadChunk(chunk);
        stop.set(true); saver.join();
        // 重新打开必须仍能读回区块
        var reopened = new AnvilLoader(dir, DimensionType.OVERWORLD.key());
        assertNotNull(reopened.loadChunk(instance, 0, 0));
    }
}
```

**判定**：【读代码确认】（竞态窗口与双实例路径可静态确认）/ 实际损坏概率【需要运行验证】

---

### P1-1　`EntityTrackerImpl.viewers` 无界泄漏：每加载一次区块就永久多一条

**严重度**：P1（内存泄漏）
**位置**：`src/main/java/net/minestom/server/instance/EntityTrackerImpl.java:216-220, 262-270, 279`、`src/main/java/net/minestom/server/instance/Chunk.java:73-75`、`src/main/java/net/minestom/server/instance/InstanceContainer.java:585-587`

**问题描述**

`Chunk` 构造时把"共享实例列表"当作 view 的 key 的一部分：

```java
// Chunk.java:73-75
final List<SharedInstance> shared = instance instanceof InstanceContainer ic ? ic.getSharedInstances() : List.of();
this.viewable = instance.getEntityTracker().viewable(shared, chunkX, chunkZ);
```

`InstanceContainer#getSharedInstances()` 返回 `Collections.unmodifiableList(sharedInstances)`——因为底层是 `CopyOnWriteArrayList`（`RandomAccess`），JDK 每次都会 **new 一个 `UnmodifiableRandomAccessList`**。

而 `ChunkViewKey#equals` 对这个字段用 **引用比较**：

```java
// EntityTrackerImpl.java:267-269
return sharedInstances == instances &&
        chunkX == x &&
        chunkZ == z;
```

`viewable(...)` 则是 `viewers.computeIfAbsent(new ChunkViewKey(...), ChunkView::new)`（`EntityTrackerImpl.java:218`），`viewers` 是一个 **从来没有 remove 调用** 的 `ConcurrentHashMap`（`EntityTrackerImpl.java:279`）。

结果：同一个坐标的区块每卸载再加载一次，就会因为 key 不相等而新增一条 `ChunkViewKey + ChunkView` 且旧条目不可回收；即使从不卸载，每个新坐标也永久占一条。`ChunkView` 又是 `EntityTrackerImpl` 的内部类，`ChunkViewKey` 持有那个 unmodifiable wrapper → 持有 `InstanceContainer`。

**触发场景**

玩家在开启自动卸载的世界里持续飞行/探索。issue #3083 的复现步骤（"飞几分钟看 `instance.getChunks().size()`"）恰好也会触发这一条。

**影响**

长期运行的服务器持续增长的堆占用，`ConcurrentHashMap` 条目无上限。按每条约 150 字节估算，100 万次区块加载 ≈ 150 MB 纯泄漏，且随桶数增长会放大。

**建议修复**

- `ChunkViewKey#equals` 改成正常的内容比较（record 默认实现即可，直接删掉手写的 `equals`），并让 `getSharedInstances()` 返回一个稳定的缓存视图；
- 在 `InstanceContainer#unloadChunk` 里加上 `entityTracker` 侧的 view 回收（新增 `EntityTracker#releaseViewable(chunkX, chunkZ)`）。

**建议的复现测试**

```java
@EnvTest
public class EntityTrackerViewLeakTest {
    @Test
    public void reloadingChunksDoesNotGrowViewers(Env env) throws Exception {
        var instance = env.createFlatInstance();
        var tracker = instance.getEntityTracker();
        var field = tracker.getClass().getDeclaredField("targetEntries");
        field.setAccessible(true);
        Object[] entries = (Object[]) field.get(tracker);
        var viewersField = entries[EntityTracker.Target.PLAYERS.ordinal()].getClass()
                .getDeclaredField("viewers");
        viewersField.setAccessible(true);

        for (int i = 0; i < 50; i++) {
            instance.loadChunk(0, 0).join();
            instance.unloadChunk(0, 0);
        }
        var viewers = (Map<?, ?>) viewersField.get(entries[EntityTracker.Target.PLAYERS.ordinal()]);
        assertTrue(viewers.size() <= 1, "viewers leaked one entry per chunk load: " + viewers.size());
    }
}
```

**判定**：【读代码确认】
**关联**：issue #2649

---

### P1-2　区块 viewer 用全局 `ServerFlag.CHUNK_VIEW_DISTANCE`，最外圈区块收不到方块更新

**严重度**：P1（功能错误，客户端-服务端不同步）
**位置**：`src/main/java/net/minestom/server/instance/EntityTrackerImpl.java:340-345`、`src/main/java/net/minestom/server/entity/Player.java:2538-2545`

**问题描述**

`Chunk#getViewers()` 最终落到：

```java
// EntityTrackerImpl.java:342-344
tracker.nearbyEntitiesByChunkRange(point, ServerFlag.CHUNK_VIEW_DISTANCE,
        EntityTracker.Target.PLAYERS, (player) -> map.putIfAbsent(player.getEntityId(), player));
```

但玩家实际收到的区块半径是：

```java
// Player.java:2542-2545
private int effectiveViewDistance(@Nullable Instance instance) {
    int maxViewDistance = instance != null ? instance.viewDistance() : ServerFlag.CHUNK_VIEW_DISTANCE;
    return Math.min(settings.viewDistance(), maxViewDistance) + 1;   // ← 注意 +1
}
```

两者**永远差 1**（默认配置下），而且 `instance.viewDistance()` 是 per-instance 可变的（`Instance.java:909-925`），`ChunkView` 完全没读它。

于是"玩家已经收到过区块数据、但不在该区块 viewer 集合里"的一圈区块始终存在。`InstanceContainer#UNSAFE_setBlock` 的 `chunk.sendPacketToViewers(new BlockChangePacket(...))`（`InstanceContainer.java:217`）对这些玩家不发包 → 客户端保留旧方块。

这与 issue #3021 的复现描述完全吻合：报告者设 `minestom.chunk-view-distance=8`，飞到 **第 9 个区块**（"服务器视距 +1"）后 setBlock，客户端拿不到方块、穿模并进入 crawl 状态。

**触发场景**

普通游玩：站在视距边缘的区块里，服务端 `setBlock`。或把 `instance.viewDistance()` 设成大于 `ServerFlag.CHUNK_VIEW_DISTANCE` 的值——此时差距不止 1 圈。

**影响**

方块不同步（穿模、幽灵方块、crawl 状态卡死）、`BlockEntityDataPacket` / `MultiBlockChangePacket` / 光照重发同样丢失。`ChunkBatch#updateChunk`（`ChunkBatch.java:234`）里的 `!chunk.getViewers().isEmpty()` 也会误判。

**建议修复**

`ChunkView` 需要知道它属于哪个 instance，用 `instance.viewDistance() + 1` 取代常量；更稳妥的做法是反过来查：对每个候选玩家用 `player.effectiveViewDistance()` 判断（因为不同玩家的客户端设置不同）。open PR #2947 修的是 `JoinGamePacket` 与设置变更路径，**没有覆盖这里**，建议合并时一并处理。

**建议的复现测试**

```java
@EnvTest
public class ChunkViewerDistanceIntegrationTest {
    @Test
    public void outermostChunkStillReceivesBlockChange(Env env) {
        var instance = env.createFlatInstance();
        int vd = ServerFlag.CHUNK_VIEW_DISTANCE;
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        env.tick();
        // 边缘区块：距离 == effectiveViewDistance == vd + 1
        int edgeX = (vd + 1) * 16;
        instance.loadChunk(vd + 1, 0).join();
        var tracker = connection.trackIncoming(BlockChangePacket.class);
        instance.setBlock(edgeX, 41, 0, Block.STONE);
        tracker.assertSingle(p -> assertEquals(Block.STONE.stateId(), p.blockStateId()));
    }
}
```

**判定**：【读代码确认】
**关联**：issue #3021、open PR #2947

---

### P1-3　`unloadChunk` 会把区块里的玩家一起 `remove()` 掉（即踢下线），与 Javadoc 契约相反

**严重度**：P1（功能错误）
**位置**：`src/main/java/net/minestom/server/instance/InstanceContainer.java:301`、契约声明在 `src/main/java/net/minestom/server/instance/Instance.java:316`

**问题描述**

契约写得很清楚：

```java
// Instance.java:314-318
 * Schedules the removal of a {@link Chunk} ...
 * WARNING: during unloading, all entities other than {@link Player} will be removed.
```

实现却没有过滤 Player：

```java
// InstanceContainer.java:300-301
// Remove all entities in chunk
getEntityTracker().chunkEntities(chunkX, chunkZ, EntityTracker.Target.ENTITIES).forEach(Entity::remove);
```

`Target.ENTITIES` 是 `create(Entity.class)`（`EntityTracker.java:106`），注册时用 `type().isInstance(entity)` 判断，**Player 是 Entity，必然在这个列表里**。`Entity#remove()` → `remove(true)`（`Entity.java:1617-1619`），Player 覆写后会触发 `PlayerDisconnectEvent` 并在 `Player.java` 末尾 `kick(REMOVE_MESSAGE)`。

**触发场景**

任何"手动卸载区块"的代码。issue #3083 的报告者正是照着 `PlayerChunkUnloadEvent` 卸载区块，然后遇到"大量异常和不该卸载的区块被卸载"。注意 `PlayerChunkUnloadEvent` 在 `Player.java:229-232` 是玩家视野收缩时触发的，此时另一个玩家可能仍在那个区块里——直接卸载就把他踢了。

**影响**

玩家被无声踢出；配合 P1-1 会让 `getChunks().size()` 与实际 viewer 集合进一步偏离。

**建议修复**

```diff
-        getEntityTracker().chunkEntities(chunkX, chunkZ, EntityTracker.Target.ENTITIES).forEach(Entity::remove);
+        for (Entity entity : getEntityTracker().chunkEntities(chunkX, chunkZ, EntityTracker.Target.ENTITIES)) {
+            if (entity instanceof Player) continue; // 契约见 Instance#unloadChunk
+            entity.remove();
+        }
```
并在 `unloadChunk` 里对"仍有玩家"的情况给出明确的拒绝或警告。

**建议的复现测试**

```java
@EnvTest
public class UnloadChunkKeepsPlayersTest {
    @Test
    public void unloadingChunkDoesNotRemovePlayer(Env env) {
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));
        env.tick();
        instance.unloadChunk(0, 0);
        assertFalse(player.isRemoved(), "unloadChunk removed a player, contradicting Instance#unloadChunk javadoc");
    }
}
```

**判定**：【读代码确认】
**关联**：issue #3083

---

### P1-4　`Light#getNeighbors` 边界判断写错：`minSection != 0` 的维度里最顶上 3 个 section 完全没有跨 section 光照传播

**严重度**：P1（功能错误）
**位置**：`src/main/java/net/minestom/server/instance/light/Light.java:61-62`

**问题描述**

```java
// Light.java:61-62
if (y - foundChunk.getMinSection() > foundChunk.getMaxSection() || y - foundChunk.getMinSection() < 0)
    continue;
```

正确的判断应该是 `y >= maxSection || y < minSection`。写成上面这样，实际接受区间是 `y ∈ [minSection, maxSection + minSection]`。

主世界：`minSection = -4`，`maxSection = 20`（`Chunk.java:71-72`），于是接受区间是 `[-4, 16]`，**section 17/18/19（方块 Y 272~319）被整体排除**。

对比同文件里另一处**写对了**的判断（`LightingChunk.java:380`、`392`）：`if (y - lighting.getMinSection() < 0 || y - lighting.getMaxSection() >= 0) return null;`——两者不一致本身就是强证据。

后果：为 section 18 求邻居时，6 个方向的 `y` 全落在 `{17,18,19}`，**全部被拒**，`neighbors` 数组全 null → `buildExternalQueue` 返回空队列 → `calculateExternal` 得到 `EMPTY_CONTENT`。也就是说这三层 section 只有 section 内部光照，任何跨 section / 跨区块的方块光传播都消失。

值得注意的是：`minSection == 0` 的维度（下界/末地，`minY=0`）这个表达式恰好退化成正确形式——所以 bug 只在主世界这类负 minY 维度显形，也解释了为什么现有测试（`BlockLightMergeIntegrationTest` 全部在 section 2 和 6 上）没有覆盖。

**触发场景**

在主世界 Y≥272 处建造并放置光源（信标塔、天空城）。

**影响**

高空建筑漆黑、光照跨区块接缝错误；`compareBorders` 也永远不会把这些 section 加进 `toUpdate`，问题不会自愈。

**建议修复**

```diff
-            if (y - foundChunk.getMinSection() > foundChunk.getMaxSection() || y - foundChunk.getMinSection() < 0)
+            if (y >= foundChunk.getMaxSection() || y < foundChunk.getMinSection())
                 continue;
```

**建议的复现测试**

```java
@EnvTest
public class HighSectionLightIntegrationTest {
    @Test
    public void blockLightPropagatesAcrossTopSections(Env env) {
        var instance = env.createEmptyInstance();
        instance.setChunkSupplier(LightingChunk::new);
        instance.loadChunk(0, 0).join();
        // section 18 底部放光源，检查它是否照到 section 17 的顶部
        instance.setBlock(1, 288, 1, Block.GLOWSTONE);
        LightingChunk.relightSection(instance, 0, 18, 0);
        assertEquals(15, instance.getBlockLight(1, 288, 1));
        assertTrue(instance.getBlockLight(1, 287, 1) > 0,
                "light did not cross the section 18 -> 17 boundary");
    }
}
```

**判定**：【读代码确认】
**关联**：issue #3158

---

### P1-5　`InstanceContainer#copy()`：不持读锁（触发断言）、不调 `onLoad()`（LightingChunk 副本永远 `isLoaded()==false`）、丢失 tickable / generator / loader

**严重度**：P1（功能错误）
**位置**：`src/main/java/net/minestom/server/instance/InstanceContainer.java:622-634`、`src/main/java/net/minestom/server/instance/DynamicChunk.java:242-248`、`src/main/java/net/minestom/server/instance/LightingChunk.java:589-601`

**问题描述**

```java
// InstanceContainer.java:622-634
public synchronized InstanceContainer copy() {
    InstanceContainer copiedInstance = new InstanceContainer(UUID.randomUUID(), getDimensionType());
    ...
    for (Chunk chunk : chunks.values()) {
        final Chunk copiedChunk = chunk.copy(copiedInstance, chunkX, chunkZ);   // ① 未持读锁
        copiedInstance.cacheChunk(copiedChunk);                                  // ② 没有 onLoad()
    }
    return copiedInstance;
}
```

四个问题叠在一起：

1. **① 未持读锁**：`DynamicChunk#copy` 第一行就是 `assertReadLock()`（`DynamicChunk.java:243`）。Gradle `Test` 任务默认 `enableAssertions = true`，所以任何"复制含区块的 instance"的测试会直接 `AssertionError`；生产环境（无 `-ea`）则退化为无同步读取 palette，与并发 `setBlock` 竞争。
2. **② 没有 `onLoad()`**：`LightingChunk#onLoad` 才把 `doneInit` 置 true，而 `LightingChunk#isLoaded()` 是 `super.isLoaded() && doneInit`（`LightingChunk.java:598-601`）。因此 **副本实例里所有区块 `isLoaded()` 恒为 false**：`InstanceContainer#setBlock` 的 `if (isLoaded(chunk))`（`InstanceContainer.java:147`）静默丢弃写入，`Player#sendChunk` 的 `if (!chunk.isLoaded()) return`（`Player.java:847`）让玩家永远收不到区块。
3. **`tickableMap` 没被复制**：`DynamicChunk#copy` 只做了 `dynamicChunk.entries.putAll(entries)`（`DynamicChunk.java:246`），`tickableMap` 保持为空 → 副本里所有可 tick 的 `BlockHandler` 永久停摆。`LightingChunk#copy`（`LightingChunk.java:589-596`）同样。
4. **实例级配置丢失**：新 `InstanceContainer` 用的是默认 `DynamicChunk::new` 供应器、默认 `NoopChunkLoader`、`generator == null`、默认 `dimensionName`。也就是说 `LightingChunk` 世界复制出来会退化成 `DynamicChunk`（新加载的区块），且与已复制的 `LightingChunk` 混用。

**触发场景**

`InstanceContainer#copy()` 的典型用途——小游戏地图模板复制。

**影响**

副本世界不可写、不可见、方块不 tick；在测试环境直接 `AssertionError`。

**建议修复**

```diff
     for (Chunk chunk : chunks.values()) {
-        final Chunk copiedChunk = chunk.copy(copiedInstance, chunkX, chunkZ);
-        copiedInstance.cacheChunk(copiedChunk);
+        chunk.lockReadLock();
+        final Chunk copiedChunk;
+        try { copiedChunk = chunk.copy(copiedInstance, chunkX, chunkZ); }
+        finally { chunk.unlockReadLock(); }
+        copiedInstance.cacheChunk(copiedChunk);
+        copiedChunk.onLoad();
     }
```
并在 `copy()` 里传递 `chunkSupplier` / `generator` / `chunkLoader` / `dimensionName`；`DynamicChunk#copy` 里补上 `tickableMap.putAll(tickableMap)`。

**建议的复现测试**

```java
@EnvTest
public class InstanceCopyIntegrationTest {
    @Test
    public void copiedLightingInstanceIsUsable(Env env) {
        var instance = env.createFlatInstance();
        instance.setChunkSupplier(LightingChunk::new);
        instance.loadChunk(0, 0).join();
        instance.setBlock(0, 41, 0, Block.STONE);

        var copy = assertDoesNotThrow(instance::copy);     // -ea 下当前会 AssertionError
        var copiedChunk = copy.getChunk(0, 0);
        assertNotNull(copiedChunk);
        assertTrue(copiedChunk.isLoaded(), "copied LightingChunk never received onLoad()");
        copy.setBlock(0, 41, 0, Block.DIRT);
        assertEquals(Block.DIRT, copy.getBlock(0, 41, 0));  // 当前静默丢弃
    }
}
```

**判定**：【读代码确认】

---

### P1-6　`DynamicChunk#tick` 无锁遍历 `tickableMap`，与 `setBlock` 的写入竞争

**严重度**：P1（数据竞争 / 静默行为错误）
**位置**：`src/main/java/net/minestom/server/instance/DynamicChunk.java:190-201` 对比 `DynamicChunk.java:117-121`

**问题描述**

写入侧在写锁保护下修改 `tickableMap`（`Int2ObjectOpenHashMap`，非线程安全）：

```java
// DynamicChunk.java:117-121（持写锁）
if (handler != null && handler.isTickable()) this.tickableMap.put(index, block);
else this.tickableMap.remove(index);
```

读取侧完全不加锁，且用的是 fastutil 的 `fastForEach`（复用 entry 对象、直接遍历开放寻址表）：

```java
// DynamicChunk.java:191-200
if (tickableMap.isEmpty()) return;
tickableMap.int2ObjectEntrySet().fastForEach(entry -> {
    ...
    handler.tick(new BlockHandler.Tick(block, instance, blockPosition));
});
```

两类问题：
- **跨线程**：`Chunk#tick` 由 `TickThread`（`thread/TickThread.java:82`）调用，`setBlock` 可以来自任意线程 → 对开放寻址哈希表的并发 put/rehash 与遍历，可能读到半更新的表、漏 tick、重复 tick。
- **重入**：`handler.tick(...)` 里调用 `instance.setBlock(...)`（把自己变成非 tickable 是很常见的写法，如熔炉烧完、作物成熟）会在遍历过程中 `remove`。fastutil 的 shift-down 删除会把后面的 entry 前移，**遍历会静默跳过条目**，而不是抛 `ConcurrentModificationException`。

**触发场景**

任何 `isTickable()` 的 `BlockHandler` 在 `tick()` 里改自己或邻居的方块。

**影响**

方块 tick 静默丢失（作物停止生长、熔炉卡住），且难以定位；理论上还有并发 rehash 导致读线程死循环的风险。

**建议修复**

`tick` 里先在读锁下把条目拷贝到本地数组，再释放锁执行 handler：

```diff
-        tickableMap.int2ObjectEntrySet().fastForEach(entry -> { ... });
+        final int[] indexes; final Block[] blocks;
+        lockReadLock();
+        try {
+            indexes = tickableMap.keySet().toIntArray();
+            blocks = tickableMap.values().toArray(new Block[0]);
+        } finally { unlockReadLock(); }
+        for (int i = 0; i < indexes.length; i++) { ... }
```

**建议的复现测试**

```java
@EnvTest
public class TickableBlockSelfModifyTest {
    @Test
    public void handlerModifyingItselfDoesNotSkipNeighbours(Env env) {
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();
        var ticked = ConcurrentHashMap.<Point>newKeySet();
        BlockHandler h = new BlockHandler() {
            @Override public Key getKey() { return Key.key("test:selfmod"); }
            @Override public boolean isTickable() { return true; }
            @Override public void tick(Tick tick) {
                ticked.add(tick.getBlockPosition());
                tick.getInstance().setBlock(tick.getBlockPosition(), Block.STONE); // 移除自己
            }
        };
        for (int i = 0; i < 16; i++) instance.setBlock(i, 41, 0, Block.DIRT.withHandler(h));
        env.tick();
        assertEquals(16, ticked.size(), "tick iteration skipped entries after self-removal");
    }
}
```

**判定**：【读代码确认】（并发部分）/ 漏 tick 的具体数量【需要运行验证】

---

### P1-7　`TargetEntry.chunkEntities` 的空列表永不回收（issue #2649 的另一半）

**严重度**：P1（内存泄漏）
**位置**：`src/main/java/net/minestom/server/instance/EntityTrackerImpl.java:285-295`、`InstanceContainer.java:301`

**问题描述**

```java
// EntityTrackerImpl.java:285-295
List<T> chunkEntities(long index) {
    return chunkEntities.computeIfAbsent(index, _ -> new CopyOnWriteArrayList<>());
}
void removeFromChunk(long index, T entity) {
    List<T> entities = chunkEntities.get(index);
    if (entities != null) entities.remove(entity);   // 空了也不删 key
}
```

而 `chunkEntities(int,int,Target)`（公开 API，`EntityTrackerImpl.java:148-155`）走的是 `computeIfAbsent` —— **任何一次查询都会给该区块坐标创建一条永久条目**。`InstanceContainer#unloadChunk:301` 每卸载一个区块就调用它一次，等于卸载动作本身在制造泄漏条目。乘以 4 个 `Target`（ENTITIES/PLAYERS/ITEMS/EXPERIENCE_ORBS，`EntityTracker.java:111`）。

**影响**：与 P1-1 叠加，无限世界探索时线性增长。

**建议修复**：`chunkEntities(int,int,Target)` 改用 `get` + `List.of()` 兜底；`removeFromChunk` 在列表空时 `chunkEntities.remove(index, entities)`（`Long2ObjectSyncMap` 支持条件删除）。

**判定**：【读代码确认】　**关联**：issue #2649（原报告即此处）

---

### P2-1　`Palette#compare`：单值调色板与内容相同的间接调色板被判为不等

**严重度**：P2
**位置**：`src/main/java/net/minestom/server/instance/palette/PaletteImpl.java:532-538`

`count` 字段是双语义的——`bitsPerEntry == 0` 时它是**单值本身**，否则是**非空方块计数**（`PaletteImpl.java:27` 有注释）。但 `compare` 的短路检查直接混着比：

```java
if (palette.count != this.count) return false;   // 拿"值"和"计数"比
if (palette.count == 0) return true;
if (palette.bitsPerEntry == 0 && this.bitsPerEntry == 0) return true;
```

一个 `fill(stateId=1)` 的单值调色板（`count == 1`）与一个 4096 格全填 stateId 1 的间接调色板（`count == 4096`）内容相同，却在第一行就返回 false。

**影响**：`Palette#compare` 是公开 API（`Palette.java:185` "Compare palettes content independently of their storage format"），目前主源码只在测试里用，但任何依赖它做区块去重/diff 的下游代码都会拿到错误结果。
**建议修复**：直接采用 open PR #3348 的补丁（只在存储格式相同的两个调色板之间比较 `count`，混合格式落到逐格循环）。
**判定**：【读代码确认】　**关联**：open PR #3348 / issue #3334

---

### P2-2　`RegionFile#readChunkData` 不校验长度、忽略 `read()` 返回值

**严重度**：P2（健壮性 / 静默丢档）
**位置**：`src/main/java/net/minestom/server/instance/anvil/RegionFile.java:72-88`

```java
int length = file.readInt();          // 完全来自文件，未校验
int compressionType = file.readByte();
...
byte[] data = new byte[length - 1];   // length==0 → NegativeArraySizeException
file.read(data);                      // 返回值被丢弃，短读留下零填充
```

- `length == 0` → `NegativeArraySizeException`；
- `length` 为大数 → 直接分配到 `OutOfMemoryError`（`Error`，逃出 `AnvilLoader#loadChunk` 的 `catch (Exception e)`，`AnvilLoader.java:129`）；
- 短读被静默接受，NBT 解析出的区块可能残缺。

而 `AnvilLoader#loadChunk` 的容错是"出异常就返回 null"，返回 null 会导致 **区块被重新生成**（`InstanceContainer.java:364-370`），下一次保存就把玩家的建筑永久覆盖。vanilla 会拿 `length` 和头里的扇区数交叉校验。

**建议修复**：校验 `1 <= length <= sectorCount * 4096 - 4`，用 `file.readFully(data)`，并在校验失败时抛出可区分的 `CorruptedRegionException`，让上层选择"拒绝生成"而不是静默重建。
**判定**：【读代码确认】

---

### P2-3　`Heightmap#loadFrom` 不校验数组长度

**严重度**：P2
**位置**：`src/main/java/net/minestom/server/instance/heightmap/Heightmap.java:101-117`、调用点 `src/main/java/net/minestom/server/instance/anvil/AnvilLoader.java:152, 179-188`

`loadFrom(long[] data)` 直接按 `heights.length`（256）× `bitsPerEntry` 索引 `data[containerIndex]`，没有任何长度检查。当存档的维度高度与当前 `DimensionType` 不一致（跨版本存档、把下界 region 挂到主世界 instance），`data` 会短于预期 → `ArrayIndexOutOfBoundsException` → 被 `AnvilLoader#loadChunk` 吞掉 → 区块重新生成。与 P2-2 是同一类"容错反而造成数据丢失"。

**建议修复**：`if (data.length < requiredLength) { needsRefresh = true; return; }`（退化为重新计算而不是丢弃整块）。
**判定**：【读代码确认】

---

### P2-4　`ChunkBatch#singleThreadFlush`：未持写锁调用 `chunk.reset()`；多条早退路径不 `countDown` 逆批次的 latch

**严重度**：P2
**位置**：`src/main/java/net/minestom/server/instance/batch/ChunkBatch.java:171-209`

```java
if (!chunk.isLoaded()) { LOGGER.warn(...); return; }        // :174-178 —— 不 countDown、不回调
if (this.options.isFullChunk()) chunk.reset();               // :182 —— 未持写锁
if (blocks.isEmpty()) { OptionalCallback.execute(callback, chunk); return; } // :185-189 —— 不 countDown
chunk.lockWriteLock(); try { ... } finally { chunk.unlockWriteLock(); }
if (inverse != null) inverse.readyLatch.countDown();          // :204 —— 只有正常路径会到这
```

- `chunk.reset()` → `DynamicChunk#reset()` 第一行是 `assertWriteLock()`（`DynamicChunk.java:252`），`-ea` 下直接 `AssertionError`；无 `-ea` 时是对整块 palette 的无同步清空。
- 三条早退路径（未加载、空 batch、`catch (Exception)` 于 `:206`）都不会 `countDown` 逆批次的 latch，于是逆批次的 `apply()` 里 `awaitReady()`（`ChunkBatch.java:161`）**永久阻塞**。
- 空 batch 时 `AbsoluteBlockBatch` 的 `inverse.readyLatch` 同理，直接对应 issue #2766。

**建议修复**：把 `reset()` 移进写锁；用 `try/finally` 保证 `inverse.readyLatch.countDown()` 在所有路径上执行。
**判定**：【读代码确认】　**关联**：issue #2766、#2526、#2540

---

### P2-5　`AbsoluteBlockBatch#apply`：`updated` 集合恒为空（光照重发是死代码）；空 batch 不触发回调

**严重度**：P2
**位置**：`src/main/java/net/minestom/server/instance/batch/AbsoluteBlockBatch.java:126-175`

```java
Set<Chunk> updated = ConcurrentHashMap.newKeySet();     // :132 —— 全文件没有任何 updated.add(...)
...
for (Chunk chunk : updated) { ... expanded.add(toAdd); }  // :157 —— 永远空转
for (Chunk chunk : expanded) if (chunk instanceof LightingChunk dc) dc.sendLighting(); // :169-171
```

`updated` 从未被填充，所以"批量修改后重发 3×3 区块光照"这段逻辑**从来没有生效过**。另外 `for (var entry : Long2ObjectMaps.fastIterable(chunkBatchesMap))` 在 map 为空时不执行，`callback` 与 `inverse.readyLatch` 都不会被触发（issue #2766 的另一半）。还有：`ChunkBatch#apply(instance, chunkX, chunkZ, cb)` 在区块未加载时返回 **null**（`ChunkBatch.java:118`），这个 null 被 `put` 进 `inverse.chunkBatchesMap`（`AbsoluteBlockBatch.java:172`），应用逆批次时 NPE。

**建议修复**：在 per-chunk 回调里 `updated.add(chunk)`；`chunkBatchesMap` 为空时直接执行回调并 `countDown`；null 的 chunkInverse 不入 map。
**判定**：【读代码确认】　**关联**：issue #2766、#2486

---

### P2-6　邻居更新顺序与原版不一致；顶部 Y 边界用了 `height()` 而非 `maxY()`

**严重度**：P2
**位置**：`src/main/java/net/minestom/server/instance/InstanceContainer.java:76-78`、`InstanceContainer.java:760-761`

```java
private static final BlockFace[] BLOCK_UPDATE_FACES = new BlockFace[]{
        BlockFace.WEST, BlockFace.EAST, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.BOTTOM, BlockFace.TOP
};
```
vanilla 的 `NeighborUpdater.UPDATE_ORDER` 是 `WEST, EAST, DOWN, UP, NORTH, SOUTH`（自 1.8 未变）。顺序会改变"两个邻居同时响应同一次变更时谁先赢"，红石/流体/连接类方块行为与原版分叉。open PR #3356 已给出一行修复 + 测试。

同一方法里还有：

```java
// InstanceContainer.java:760-761
if (neighborY < getCachedDimensionType().minY() || neighborY > getCachedDimensionType().height())
    continue;
```
上界应该是 `maxY()`（`= minY + height`）。写成 `height()` 后，主世界里 `neighborY` 最大只能是 320，而 `height()` 是 384，**这个条件永远不成立**，等于顶部边界检查失效。目前不会崩（`DynamicChunk#getBlock` 对越界 Y 返回 `Block.AIR`，`DynamicChunk.java:206-207`），但这是一处"看起来在防御、实际是死条件"的定时炸弹。

**判定**：【读代码确认】　**关联**：open PR #3356

---

### P2-7　方块碰撞形状不支持随机偏移（bamboo / pointed_dripstone）

**严重度**：P2
**位置**：`src/main/java/net/minestom/server/collision/BlockCollision.java:303-348`、`src/main/java/net/minestom/server/collision/ShapeImpl.java:107-124`

整个 `collision` 与 `instance/block` 包里 **没有任何 `offset_type` / 随机偏移概念**（`grep -rn "offsetType\|offset_type\|randomOffset" src/main` 无命中）。`checkBoundingBox` 直接用 `currentBlock.collisionShape()`，`intersectBoxSwept(..., new BlockVec(blockX, blockY, blockZ), ...)` 把方块坐标当作形状原点。

vanilla 对 `offset_type = XZ / XYZ` 的方块（竹子、滴水石锥、多数花草）会按方块坐标哈希产生 ±0.25 的水平偏移（以及 XYZ 类型的向下偏移），客户端也会这么渲染。服务端不做 → 服务端认为碰撞、客户端认为没有（或反之），玩家被塞进 crawl 状态。

**建议修复**：`ShapeImpl` 增加按 `CoordConversion.hashBlockCoord(x, y, z)` 计算的偏移量（Minestom 已有该哈希函数，`CoordConversion.java:246`），在 `intersectBoxSwept` / `intersectBox` 里把 `shapePos` 加上偏移；偏移量需要与 vanilla 的 `Mth.getSeed` 派生方式一致。
**判定**：【读代码确认】（缺失）/ 与客户端的具体偏差【需要运行验证】
**关联**：issue #3049

---

### P2-8　`Instance#getBlockLight/getSkyLight` 不做 Y 边界检查

**严重度**：P2
**位置**：`src/main/java/net/minestom/server/instance/Instance.java:1073-1103`

```java
var chunk = getChunkAt(blockX, blockZ);
if (chunk == null) return 0;
Section section = chunk.getSectionAt(blockY);   // 无边界检查
```
`getSectionAt` → `getSection(blockY >> 4)` → `sections.get(section - minSection)`（`DynamicChunk.java:164-166`）。传入 `blockY = -100` 或 `400` 直接 `IndexOutOfBoundsException`。同一个类里的 `getBlock` 路径（`DynamicChunk.java:206-207`）是做了边界返回 AIR 的，这里不一致。
**建议修复**：越界时返回 0（或 15，视 `hasSkylight()`）。
**判定**：【读代码确认】

---

### P2-9　`Instance#getBlock(..., Condition.CACHED)` 在**已加载**区块上抛出 "Unloaded chunk" NPE

**严重度**：P2（错误信息误导 + 契约违反）
**位置**：`src/main/java/net/minestom/server/instance/Instance.java:745-750`、`src/main/java/net/minestom/server/instance/DynamicChunk.java:210-216`

```java
// DynamicChunk.java:213-215：CACHED 语义就是"没有缓存条目则返回 null"
if (entry != null || condition == Condition.CACHED) {
    return entry;
}

// Instance.java:747-749：把 null 一律解释成"区块没加载"
final Block block = blockRetriever.getBlock(x, y, z, condition);
if (block == null) throw new NullPointerException("Unloaded chunk at " + x + "," + y + "," + z);
```

方法签名声明 `@Nullable`（`Block.Getter`），实现却抛异常；而且抛的是完全误导的原因。用户拿 `Condition.CACHED` 查一个普通石头方块就会得到"Unloaded chunk"。
**建议修复**：只在 `condition != Condition.CACHED` 时抛，或对 CACHED 直接返回 null。
**判定**：【读代码确认】　**关联**：issue #2507（同一异常文案）

---

### P2-10　`LightingChunk#getNearbyRequired` 的 `sectionHeight` 重复加了一次 `minY`

**严重度**：P2（性能 / 逻辑不自洽）
**位置**：`src/main/java/net/minestom/server/instance/LightingChunk.java:528-531`

```java
for (int y = point.blockY() - 1; y <= point.blockY() + 1; y++) {
    Point sectionPosition = new Vec(x, y, z);
    int sectionHeight = instance.getCachedDimensionType().minY() + 16 * y;   // ← y 已经是绝对 section 索引
    if ((sectionHeight + 16) > highestRegionPoint && type == LightType.SKY) continue;
```

`y` 在这里是**绝对 section 索引**（见 `LightingChunk.java:466-469` 与 `:533` 的 `getMinSection()/getMaxSection()` 比较），所以该 section 的最低方块 Y 就是 `16 * y`，不该再加 `minY`。主世界里 `sectionHeight` 被低估 64，天空光的"高于最高方块则跳过"优化少剪掉 4 个 section 的工作量。

不会产生错误光照（少剪枝是安全方向），但 `collectRequiredNearby` 是不动点循环（`LightingChunk.java:547-566`），每次方块变更都会多做一圈，是 issue #3158 里"光照卡"的贡献项之一。
**建议修复**：`int sectionHeight = 16 * y;`
**判定**：【读代码确认】　**关联**：issue #3158

---

### P2-11　Anvil 保存：单值 palette 分支丢失方块实体；heightmap 从不写回

**严重度**：P2（存档数据丢失）
**位置**：`src/main/java/net/minestom/server/instance/anvil/AnvilLoader.java:449-483`、`AnvilLoader.java:400-413`

1. `saveSectionData` 在 `section.blockPalette().singleValue() != -1` 时走快速分支（`:449-453`），**完全跳过 `blockEntities` 的收集**——收集逻辑只在 `else` 分支的 `getAll` 回调里（`:469-481`）。一个整段同一 stateId、但个别方块带 handler/NBT 的 section（例如全是 `chest` 的储物层）保存后方块实体全丢。
2. `loadMCA` 会读 `Heightmaps`（`AnvilLoader.java:152`），但 `saveChunk` 从不写 `Heightmaps`，并且 `loadMCA` 构造 handlerData 时把它 `remove` 掉了（`:156-162`），所以存档往返一次高度图就消失。Minestom 自己会重算（`needsCompleteHeightmapRefresh`），但导出的存档给 vanilla 客户端/工具用时是缺字段的。

另外 `:158-160` 有一行重复的 `.remove("sections")`。
**建议修复**：单值分支也遍历 `chunk` 的 `entries` 收集方块实体；`saveChunk` 补写 `Heightmaps`。
**判定**：【读代码确认】

---

### P3（代码异味 / 低风险，逐条一句话）

| # | 位置 | 说明 |
|---|---|---|
| P3-1 | `collision/CollisionUtils.java:180-186` | `parseCollisionShape` 把**未规范化**的实例 `put(shape, parsedShape)`，却返回 `computeIfAbsent(parsedShape, ...)` 的规范实例，两者可能不同 → intern 缓存失效、同一形状字符串每次拿到非规范对象。 |
| P3-2 | `network/packet/server/play/data/ChunkData.java:88-102` | `equals`/`hashCode` 对 `Map<Type, long[]>` 走 `Map.equals`，`long[]` 用引用语义 → 两个内容相同的 `ChunkData` 几乎必然不等。 |
| P3-3 | `instance/LightingChunk.java:370, 427` | `Set<Light> sections` 被填充但从不读取，死代码。 |
| P3-4 | `instance/palette/Palettes.java:80-89` | `count(int, long[])` 把所有 lane 的**值累加**（而非计数），且不按 `size` 截断 padding lane；主源码无调用者，语义可疑。 |
| P3-5 | `instance/SharedInstance.java:24-27` | 构造时 `super(uuid, container.getDimensionType())` 丢弃容器自定义的 `dimensionName`，与容器 `getDimensionName()` 不一致（影响 `Player#setInstance` 的 `dimensionChange` 判定）。 |
| P3-6 | `instance/Instance.java:133` | `blockRetriever` 是**共享可变** `ChunkCache`（`utils/chunk/ChunkCache.java:14` 的 `chunk` 字段非 volatile），多线程调用 `Instance#getBlock` 会互相踢缓存；单次调用内取本地副本所以不出错，但缓存基本失效。 |
| P3-7 | `instance/block/BlockManager.java:60-74` | `getBlockPlacementRule` 是全局 `synchronized`，位于 `UNSAFE_setBlock` 与邻居更新热路径上，跨所有 instance 串行化。 |
| P3-8 | `collision/BlockCollision.java:254-288` | `sweepBlocks` 遍历量与速度立方成正比，没有速度上限；插件设置极大 `velocity` 时是一次同步的十亿级循环。 |

---

## ③ 上游 issue 复核表（本范围内）

| Issue | 结论 | 依据（一句话） |
|---|---|---|
| #3158 Lighting | **仍存在** | 本报告 P1-4（`Light.java:61` 边界写错）、P2-10（`LightingChunk.java:530` 多加 minY）、P0-1（重算死锁）都是它列举症状的具体成因。 |
| #3083 PlayerChunkUnloadEvent 不对所有区块触发 | **仍存在（且更糟）** | 事件本身在 `Player.java:229-232`/`:784` 触发；真正的坑是 `InstanceContainer.java:301` 卸载区块会把里面的玩家 `remove()` 掉（P1-3），以及 `remove()` 用 `position.chunkX()` 而非 `chunksLoadedByClient` 作中心（`Player.java:642-646`）导致漏发。 |
| #3021 视距与 setBlock | **仍存在，根因已定位** | `EntityTrackerImpl.java:343` 用 `ServerFlag.CHUNK_VIEW_DISTANCE`，而玩家实际视距是 `effectiveViewDistance()`（`Player.java:2544`，恒 +1）→ 最外圈区块不在 viewer 集合里（P1-2）。 |
| #3049 碰撞形状不考虑随机偏移 | **仍存在** | 全仓无 `offset_type` 相关代码，`BlockCollision.java:307` 直接用静态 `collisionShape()`（P2-7）。 |
| #2900 unregister 时玩家正在 spawn 抛异常 | **仍存在（疑似）** | `InstanceManager.java:117-137` 只检查 `getPlayers()` 中的在线玩家，而 `Player#setInstance` 在 `spawnPlayer` 之前玩家尚未注册进 tracker（`Player.java:694-733`）；同时 `retrieveChunk` 完成后无条件 `cacheChunk`+`createPartition`（`InstanceContainer.java:785-788`），对已注销的 instance 也照做。【疑似】 |
| #2766 空 AbsoluteBlockBatch 回调不触发 | **仍存在** | `AbsoluteBlockBatch.java:130-174` 的 for 循环在 map 为空时不执行；`ChunkBatch.java:185-189` 空 batch 也不 `countDown` 逆批次（P2-4/P2-5）。 |
| #2649 EntityTracker 泄漏 | **仍存在（两处）** | `EntityTrackerImpl.java:285-295`（原报告位置，空 list 不删）+ `:216-279` 的 `viewers` 从不回收且 key 用引用比较（P1-1/P1-7）。 |
| #2638 `retrieveChunk` 竞态 | **已修复** | 现在用 `loadingChunks.computeIfAbsent`（`InstanceContainer.java:348`）保证同一 index 只有一条加载链；但 P0-2 是这次重构引入/暴露的新问题。 |
| #2486 ChunkBatch 不加载区块 | **仍存在** | `ChunkBatch.java:113-119` 对未加载区块只打 warn 并返回 null，从不调用 `loadChunk`，与 `autoChunkLoad` 无关。 |
| #2526 BlockBatches 丢方块 | **疑似仍存在** | 同上的静默丢弃路径 + `ChunkBatch.java:182` 的无锁 `reset()`（P2-4）。【疑似】 |
| #2507 `Instance#getBlock(Pos)` 异常 | **部分仍存在** | 未加载区块抛 NPE 是有意设计（`Instance.java:748`，测试断言了它）；但 `Condition.CACHED` 在已加载区块上也抛同一异常是 bug（P2-9）。 |
| #2693 PhysicsUtils 未考虑鞘翅飞行 | **仍存在** | `PhysicsUtils.java:66-82` 的 `updateVelocity` 只有 flying / gravity / drag 三条分支，无 elytra 项。 |
| #2702 PhysicsUtils 负速度问题 | **无法判断** | `PhysicsUtils.java:73` 取地面方块用 `y - 0.5000001`，与 vanilla `getBlockPosBelowThatAffectsMyMovement` 一致；报告里用的是 `CollisionUtils.handlePhysics` 直调，需实测。【需要运行验证】 |
| #2668 调色板序列化错误 | **无法判断** | 调色板已整体重写（`PaletteImpl`/`Palette.serializer`），当前 `Palette.java:234-289` 的读写路径自洽（含 `validateIndices`）；原报告版本已不可对照。 |
| #2527 低视距下区块加载跟不上 | **疑似仍存在** | `Player.java:856-903` 的 `sendPendingChunks` 受 `chunkBatchLead`/`maxChunkBatchLead` 限制，且 `ChunkUpdateLimitChecker` 会抑制视野中心更新（`Player.java:2475`）。【疑似】 |
| #2131 栅栏碰撞盒过大 | **需要运行验证** | `BlockCollision.java:314-331` 有专门的"矮方块之下检查高方块"逻辑，看起来是针对性处理过的；需实测栅栏跳跃行为。 |
| #3054 GroundNodeGenerator 未加载区块 | **本范围外** | 属于 pathfinding，但根因是 `Instance#getBlock` 对未加载区块抛 NPE（P2-9 的邻居），open PR #3056 在寻路侧修。 |

---

## ④ 未覆盖的区域与原因

1. **`world/attribute`、`world/timeline`、`world/clock`（约 25 文件）**：纯注册表/编解码数据结构，除 `Instance$ClockInstance`（`Instance.java:1105-1179`，逻辑简单且已审）外没有跨模块状态；本轮按"缺陷密度优先"跳过。
2. **`instance/generator` 的 `AreaModifierImpl` 完整语义**：只审到 `GeneratorImpl.java:1-400`。已注意到 `SectionModifierImpl#fill(Point,Point,Block)` 的 else 分支（`GeneratorImpl.java:281-300`）对跨 section 区域会用 `globalToSectionRelative` 回绕写入，但需要确认 API 契约是否禁止这种调用，未下结论。
3. **`instance/block` 的注册表生成部分**（`BlockImpl.java` 的 stateId 映射、`Block.java` 的 property 处理）：与本次主题（区块/光照/碰撞）关联弱，且大量依赖外部 registry 数据包，静态阅读难以判定正确性。
4. **协议层实际字节**：`ChunkDataPacket` / `UpdateLightPacket` 的 mask 位序（`DynamicChunk.java:315-346`、`LightingChunk.java:296-339`）我核对了 index 从 1 开始与协议一致，但 "section 未出现在任何 mask 中时客户端如何处理" 需要抓包验证，未作为发现列出。
5. **性能量化**：`ChunkView#references()` 每次 `getViewers()` 都要扫 17×17=289 个区块并新建 `Int2ObjectOpenHashMap`（`EntityTrackerImpl.java:330-345`），而 `AbstractSet#isEmpty()` 也会走 `size()` → 再扫一遍。这肯定是热点，但没有 profile 数据支撑，只在 P3-6 附近点到为止。
6. **禁止运行 Gradle**，因此所有"建议的复现测试"均**未执行**；报告中标注【读代码确认】的条目指逻辑可由源码静态推导，标注【需要运行验证】/【疑似】的需要实跑。

---

## ⑤ 参考链接

- 上游仓库：https://github.com/Minestom/Minestom （本地 HEAD `16842f1954`）
- Issue #3158 Lighting：https://github.com/Minestom/Minestom/issues/3158
- Issue #3083 PlayerChunkUnloadEvent：https://github.com/Minestom/Minestom/issues/3083
- Issue #3021 chunk view distance & setBlock：https://github.com/Minestom/Minestom/issues/3021
- Issue #3049 collision random offset：https://github.com/Minestom/Minestom/issues/3049
- Issue #2766 AbsoluteBlockBatch callback：https://github.com/Minestom/Minestom/issues/2766
- Issue #2649 EntityTracker leaking memory：https://github.com/Minestom/Minestom/issues/2649
- Issue #2638 retrieveChunk race（已修复）：https://github.com/Minestom/Minestom/issues/2638
- PR #3348 Fix Palette#compare：https://github.com/Minestom/Minestom/pull/3348
- PR #3356 match vanilla's neighbor update order：https://github.com/Minestom/Minestom/pull/3356
- PR #3295 Extract a public LightEngine：https://github.com/Minestom/Minestom/pull/3295
- PR #2947 Use instance view distance：https://github.com/Minestom/Minestom/pull/2947
- PR #3296 Replace replaceable blocks in place：https://github.com/Minestom/Minestom/pull/3296
- Region file format：https://minecraft.wiki/w/Region_file_format
- 本地报告：`/Users/bug/Documents/Projects/Minecraft/research/minestom-audit/03-instance-world.md`
