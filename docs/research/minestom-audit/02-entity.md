# Minestom 实体系统只读代码审计

- 仓库：`/Users/bug/Documents/Projects/Minecraft/Minestom`，HEAD `16842f1954`（2026-09-04），与 `upstream/master` 一致
- 范围：`src/main/java/net/minestom/server/entity/**`（300 个文件）+ `instance/EntityTracker*` + `thread/Acquirable*` + `collision/PhysicsUtils` 交界
- 方式：纯阅读 + `grep` + `git blame` + `gh issue/pr view`，未运行任何构建或测试
- 所有行号可直接用 `文件:行号` 打开复核

---

## ① TL;DR

实体层的**单线程逻辑质量尚可，但并发模型是虚的**：`Acquirable` 提供的所有权断言 `assertOwnership()` 在整个仓库里**零调用点**，而 `minestom.acquirable-strict=true` 却已经默认打进了测试 JVM——一个宣称存在、实际完全不生效的安全网，这是本层最危险的结构性问题。其次，`Entity#setInstance` / `Entity#teleport` 的异步续体运行在区块加载的**虚拟线程**上，直接改写非 volatile 的 `position` / `velocity` / `currentChunk`，并能在实体已被 `remove()` 之后把它重新注册进 tracker，造成永久幽灵实体。第三，`EntityTracker` 存在两处无界内存泄漏（`ChunkView` 永不回收 + 空 `chunkEntities` 列表永不回收），**玩家正常走动与区块加载即可触发**，upstream #2649 附带的复现代码在本地 master 上仍然成立，而修复分支 `upstream/fix/viewable-leak` 从 2026-01 起一直没合。

最严重的三条：**P0-1 EntityTracker 双重泄漏（远程可触发 OOM）**、**P0-2 异步续体跨线程改实体状态**、**P1-3 acquirable-strict 是死代码**。

统计：**P0 3 条，P1 10 条，P2 11 条，P3 5 条。**

---

## ② 发现清单

### P0-1 `EntityTracker` 两处无界内存泄漏，玩家走动即可触发

**严重度 P0** · `src/main/java/net/minestom/server/instance/EntityTrackerImpl.java:218`、`:285-296`
**关联**：issue #2649（open，带复现代码）、分支 `upstream/fix/viewable-leak`（commit `b73ca0c`，2026-01-19，未合并）

**泄漏 A —— `chunkEntities` 的空列表永不删除**

```java
List<T> chunkEntities(long index) {
    return chunkEntities.computeIfAbsent(index, _ -> new CopyOnWriteArrayList<>());   // :286
}
void addToChunk(long index, T entity) { chunkEntities(index).add(entity); }
void removeFromChunk(long index, T entity) {
    List<T> entities = chunkEntities.get(index);
    if (entities != null) entities.remove(entity);                                    // :295 只删元素，不删桶
}
```

实体每进入一个新区块就 `computeIfAbsent` 建一个桶，离开时只清空不删桶。四个 `Target`（ENTITIES / PLAYERS / ITEMS / EXPERIENCE_ORBS，`EntityTracker.java:106-111`）各一份。此外公开 API `chunkEntities(chunkX, chunkZ, target)` 本身也走 `computeIfAbsent`，所以 `Instance#getChunkEntities`（`Instance.java:728`）和 `DynamicChunk#updateSnapshot`（`DynamicChunk.java:370`）**查询空区块也会建桶**。

**泄漏 B —— `ChunkView` 永不注销，且完全无法去重**

```java
public Viewable viewable(List<SharedInstance> sharedInstances, int chunkX, int chunkZ) {
    var entry = targetEntries[Target.PLAYERS.ordinal()];
    return entry.viewers.computeIfAbsent(new ChunkViewKey(sharedInstances, chunkX, chunkZ), ChunkView::new);
}
```

`Chunk` 构造函数无条件调用它（`Chunk.java:73-75`）：

```java
final List<SharedInstance> shared = instance instanceof InstanceContainer instanceContainer ?
        instanceContainer.getSharedInstances() : List.of();
this.viewable = instance.getEntityTracker().viewable(shared, chunkX, chunkZ);
```

master 上**没有任何注销路径**（`InstanceContainer#unloadChunk`，`InstanceContainer.java:294-308`，不碰 `viewers`）。更糟的是 `ChunkViewKey.equals` 用引用比较（`EntityTrackerImpl.java:267` `sharedInstances == instances`），而 `InstanceContainer#getSharedInstances()`（`InstanceContainer.java:585-586`）每次返回**新的** `Collections.unmodifiableList` 包装对象 —— 所以同一坐标的区块每加载一次就新增一个 `ChunkView`，永远不去重。又因为 record 自动生成的 `hashCode` 用的是 `List` 的内容哈希（空列表恒为 1），这些键**哈希完全相同**，`ConcurrentHashMap` 的同一个桶会退化成长链，`computeIfAbsent` 随之变慢。

**触发场景**：玩家在视距边界来回走动 → 区块反复加载/卸载 → `ChunkView` 线性累积；或玩家用 `/tp` 在世界里跳跃 → 每个途经区块在 4 个 target 里各留一个空桶。#2649 的复现代码（`register`/`unregister` 循环 10000×10000 个区块坐标）在 1.7 GB 堆上 OOM。

**影响**：远程可触发的无界内存增长 → OOM 拒绝服务；`viewers` 桶退化还带来 CPU 退化。

**建议修复**：直接采纳 `upstream/fix/viewable-leak`：`removeFromChunk` 在列表变空时 `chunkEntities.remove(index)`，`chunkEntities(...)` 返回 `null` 时给 `List.of()`；新增 `EntityTracker#unregisterViewable(Viewable)` 并在 `unloadChunk` 里调用。额外补一条该分支没修的：让 `ChunkViewKey` 用 `List#equals` 而不是 `==`，或者在 `Chunk` 里缓存 `sharedInstances` 的稳定引用。

**复现测试**

```java
// src/test/java/net/minestom/server/instance/EntityTrackerLeakTest.java  (package 需为 net.minestom.server.instance)
@Test
public void chunkBucketIsReleased() {
    var tracker = (EntityTrackerImpl) EntityTracker.newTracker();
    var entry = tracker.targetEntries[EntityTracker.Target.ENTITIES.ordinal()];
    var entity = new Entity(EntityType.ZOMBIE);
    for (int x = 0; x < 1000; x++) {
        tracker.register(entity, new Vec(x << 4, 0, 0), EntityTracker.Target.ENTITIES, null);
        tracker.unregister(entity, EntityTracker.Target.ENTITIES, null);
    }
    assertEquals(0, entry.chunkEntities.size(), "empty chunk buckets must be removed");
}

@EnvTest
class ChunkViewLeakTest {
    @Test
    public void chunkViewIsReleasedOnUnload(Env env) {
        var instance = env.createFlatInstance();
        var tracker = (EntityTrackerImpl) instance.getEntityTracker();
        var viewers = tracker.targetEntries[EntityTracker.Target.PLAYERS.ordinal()].viewers;
        for (int i = 0; i < 20; i++) {
            instance.loadChunk(0, 0).join();
            instance.unloadChunk(instance.getChunk(0, 0));
        }
        assertEquals(0, viewers.size(), "ChunkView must not accumulate across load/unload");
    }
}
```

**判定**：【读代码确认】

---

### P0-2 `setInstance` / `teleport` 的异步续体运行在区块加载虚拟线程上，直接改写实体状态

**严重度 P0** · `src/main/java/net/minestom/server/entity/Entity.java:432-453`、`:938-953`

```java
final Runnable endCallback = () -> {
    final float previousHeadRotation = this.headRotation;
    this.previousPosition = this.position;
    setPositionInternal(globalPosition, globalPosition.yaw());
    this.velocity = globalVelocity;
    refreshCoordinate(globalPosition);          // → tracker.move + currentChunk 重绑
    ...
};
if (!currentPosition.sameChunk(globalPosition)) {
    return instance.loadOptionalChunk(globalPosition).thenRun(endCallback);   // :453
}
```

完成线程由 `InstanceContainer.java:377-383` 决定，两条分支都显式跳到虚拟线程：

```java
final CompletableFuture<Chunk> chain = loader.supportsParallelLoading()
        ? CompletableFuture.supplyAsync(() -> processChunk.apply(loaderSupplier.get()), Thread::startVirtualThread)
        : AsyncUtils.empty().thenApply(_ -> loaderSupplier.get())
        .thenApplyAsync(processChunk, Thread::startVirtualThread);
```

被写的字段全是普通非 volatile 字段（`Entity.java:157-171`：`instance` / `currentChunk` / `position` / `previousPosition` / `lastSyncedPosition` / `velocity`）。同时该实体的 `movementTick`（`Entity.java:707-725`）正在它自己的 `TickThread` 上读写同一批字段。

**触发场景**：`entity.teleport(远处坐标)`（目标区块未加载）或首次 `setInstance` 到未生成区块 —— 玩家首次登录就走这条路（`Player.java:411`）。

**影响**：① tick 线程用传送前的位置算出的 `position` / `velocity` 覆盖传送结果，传送静默失效；② `refreshCoordinate` 读到的 `currentChunk` 与 `position` 不匹配 → `Check.notNull(newChunk, "The entity {0} tried to move in an unloaded chunk")`（`Entity.java:1487`）抛异常，或者把实体注册进错误的区块桶，**永久污染 viewer 差集计算**；③ `previousPosition` 被设成从未生效过的位置，导致后续 `EntityPositionAndRotationPacket` 的 delta 是错的。

**建议修复**：`endCallback` 不要内联执行，改成 `scheduleNextTick(_ -> endCallback.run())`，或包进 `acquirable().sync(...)`。

**复现测试**

```java
@EnvTest
class TeleportThreadIntegrationTest {
    @Test
    public void teleportContinuationRunsOnEntityThread(Env env) {
        var instance = env.createFlatInstance();
        var entity = new Entity(EntityType.ZOMBIE);
        entity.setInstance(instance, new Pos(0, 42, 0)).join();
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        entity.eventNode().addListener(EntityTickEvent.class, e -> {});
        // 目标区块未加载 -> 续体走虚拟线程
        entity.teleport(new Pos(4000, 42, 4000)).thenRun(() -> callbackThread.set(Thread.currentThread())).join();
        assertInstanceOf(TickThread.class, callbackThread.get(),
                "teleport continuation must not mutate entity state off the tick thread");
    }
}
```

**判定**：【读代码确认】（跨线程写是代码事实；具体损坏形态【需要运行验证】）

---

### P0-3 `remove()` 与异步 `setInstance` 竞态 → 实体永久留在 tracker（幽灵实体 + 泄漏）

**严重度 P0** · `src/main/java/net/minestom/server/entity/Entity.java:938-953` 与 `:1621-1652`

```java
return instance.loadOptionalChunk(spawnPosition).thenAccept(chunk -> {
    try {
        Objects.requireNonNull(chunk, "Entity has been placed in an unloaded chunk!");
        refreshCurrentChunk(chunk);                                        // 重新加入 tick 分区
        ...
        instance.getEntityTracker().register(this, position, trackingTarget, trackingUpdate);
        spawn();
        EventDispatcher.call(new EntitySpawnEvent(this, instance));
    } catch (Exception e) { MinecraftServer.getExceptionManager().handleException(e); }
});
```

续体里**没有 `isRemoved()` 检查**。而 `remove(boolean)` 的第一行是 `if (isRemoved()) return;`（`:1622`），最后才 `this.instance = null`（`:1650`）。

**触发场景**：
```
entity.setInstance(instance, pos);   // 区块未加载，续体挂起
entity.remove();                     // tracker 里还没有它 -> unregister 空操作；removed = true, instance = null
// 区块加载完成 -> 续体执行 register(...) + refreshCurrentChunk(...)
```
之后再调 `remove()` 会在第一行返回，实体**永远不会被注销**。`dispatcher().removeElement` 与 `updateElement` 都是排队信号（`ThreadDispatcher.java:123-129`），FIFO 处理，所以 remove 先、update 后，实体还会被重新塞回 tick 分区。

**影响**：`instance.getEntities()` 里出现一个 `instance == null` 的实体；新进入范围的玩家会收到它的 spawn 包却永远收不到 destroy 包（幽灵实体）；`EntitySpawnEvent` 对已移除实体触发；对象永久泄漏。

**建议修复**：续体开头加 `if (isRemoved()) return;`，并在 `remove()` 里用 CAS/`synchronized` 保证 `removed` 翻转是原子的（现在 `remove()` 既不同步也不原子，两个线程可以同时通过 `:1622` 的检查，重复触发 `EntityDespawnEvent`）。

**复现测试**

```java
@EnvTest
class EntityRemoveDuringSpawnTest {
    @Test
    public void removedEntityMustNotBeRegistered(Env env) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        var instance = env.createFlatInstance(new ChunkLoader() {
            @Override public @Nullable Chunk loadChunk(Instance i, int x, int z) {
                try { gate.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                return null; // 交给 generator
            }
            @Override public void saveChunk(Chunk chunk) {}
        });
        var entity = new Entity(EntityType.ZOMBIE);
        var future = entity.setInstance(instance, new Pos(2000, 42, 2000));
        entity.remove();                 // 续体尚未运行
        gate.countDown();
        future.join();
        assertTrue(entity.isRemoved());
        assertNull(instance.getEntityTracker().getEntityById(entity.getEntityId()),
                "removed entity leaked into the tracker");
        assertFalse(instance.getEntities().contains(entity));
    }
}
```

**判定**：【读代码确认】

---

### P1-1 玩家 `switchEntityType` 后任何 metadata 写入都会把该玩家踢下线（#3057）

**严重度 P1** · `src/main/java/net/minestom/server/entity/Entity.java:644-657`、`:1723-1726`；`Player.java:659-662`

issue 猜测的「旧类型索引残留」是**错的**：`switchEntityType` 第 646 行整个换掉 `MetadataHolder`，不可能残留。真正的链路是：

```java
public synchronized void switchEntityType(EntityType entityType) {
    ...
    Set<Player> viewers = new HashSet<>(getViewers());
    getViewers().forEach(this::updateOldViewer);   // :655 只对 viewers 发 destroy
    viewers.forEach(this::updateNewViewer);        // :656 只对 viewers 发 spawn
}
```

`EntityView.manualAdd/manualRemove` 都有 `if (player == this.entity) return false;`（`EntityView.java:123`、`:134`），**玩家永远不是自己的 viewer**，所以它自己的客户端从来没有收到销毁 + 重建。但 `notifyMetadataChanges` 走的是：

```java
void notifyMetadataChanges(Map<Integer, Metadata.Entry<?>> changes) {
    if (!isActive()) return;
    sendPacketToViewersAndSelf(new EntityMetaDataPacket(getEntityId(), changes));   // Entity.java:1725
}
// Player.java:659 -> sendPacket(packet) 先发给自己
```

客户端本地实体仍是 `class_746`（Player 布局），收到 Sheep 布局的字段就抛 `Invalid entity data item type for field 17 … old=0.0(Float), new=16(Byte)`，与 issue 里的报错逐字吻合。

**触发场景**：`player.switchEntityType(EntityType.SHEEP)` 后任意 `setSneaking` / `setPose` / `setSheared`。

**影响**：该玩家被以 Network Protocol Error 断线。原版客户端无法通过 destroy/spawn 重建自身实体，因此正确修法只能是**把自己从 `notifyMetadataChanges` 里排除**，或干脆禁止对 `Player` 调用 `switchEntityType`。

**同族缺陷（#2152，仍未修）**：`Player#getPlayerMeta()`（`Player.java:1131-1133`）是硬转型，两个内部调用者用户躲不掉 —— `LivingEntity.java:362-363` 的额外心数处理（伤害时必经）和 `Player.java:1693` 的 `refreshSettings`（每次客户端改设置必经）。对比 `LivingEntity#getLivingEntityMeta()`（`LivingEntity.java:729`）是判空返回 `null` 的，`getPlayerMeta` 应当照做。注意 `LivingEntity.java:362` 的 CCE 抛在 `callCancellable` 的成功回调里，导致**伤害没被应用但 `damage()` 仍返回 `true`**（`:395` 只看 `isCancelled()`）。

**附带缺陷（P2）**：`switchEntityType` 第 646 行丢弃全部既有 metadata，`setGlowing(true); switchEntityType(x); isGlowing()` 返回 `false`。

**复现测试**

```java
@EnvTest
class SwitchEntityTypeTest {
    @Test
    public void metaChangeAfterSwitchMustNotReachSelf(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        player.switchEntityType(EntityType.SHEEP);
        var tracker = connection.trackIncoming(EntityMetaDataPacket.class);
        player.setSneaking(true);
        tracker.assertEmpty("player must not receive metadata for its own switched type");
    }

    @Test
    public void damageAfterSwitchDoesNotThrow(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));
        player.switchEntityType(EntityType.ZOMBIE);
        assertDoesNotThrow(() -> player.damage(new Damage(DamageType.GENERIC, null, null, null, 1f)));
    }
}
```

**判定**：【读代码确认】 · 关联 #3057、#2152

---

### P1-2 手动 viewer 集合泄漏已断线的 `Player` 对象

**严重度 P1** · `src/main/java/net/minestom/server/entity/EntityView.java:28`、`Entity.java:977`

`EntityView.manualViewers` 是普通 `HashSet<Player>`，全仓库只有两处清理入口：`Entity#removeViewer`（用户显式调用）和 `Entity#removeFromInstance` 里的 `this.viewEngine.forManuals(this::removeViewer)`（`Entity.java:977`）—— 后者清理的是**实体自己**的 manual viewers。玩家断线时，**其他实体把该玩家加为 manual viewer 的记录没有任何地方清理**。

```java
// Entity.java:569-574
public final boolean addViewer(Player player) {
    Check.stateCondition(!isActive(), "Entities must be in an instance before adding viewers");
    if (!viewEngine.manualAdd(player)) return false;
    updateNewViewer(player);
    return true;
}
```

**触发场景**：`hologram.addViewer(player)`（全息文字实体、私有掉落物、NPC 的常见写法）→ 玩家退出。

**影响**：`Player` 对象（连同 `PlayerConnection`、背包、事件节点）被该实体强引用，直到实体被移除为止；`viewableOption.bitSet` 里也留下死 id。次生影响：`hasPredictableViewers()`（`EntityView.java:151-156`）因 `manualViewers` 非空而永远返回 `false`，该实体退化为逐 viewer 发包，失去区块级包缓存。

**建议修复**：在 `Player#remove` 里遍历自身可见的实体反向摘除，或把 `manualViewers` 换成弱引用集合并在 `SetImpl` 之外也做失效清理。

**复现测试**

```java
@EnvTest
class ManualViewerLeakTest {
    @Test
    public void disconnectedPlayerIsNotAManualViewer(Env env) {
        var instance = env.createFlatInstance();
        var p1 = env.createPlayer(instance, new Pos(0, 42, 0));
        var p2 = env.createPlayer(instance, new Pos(0, 42, 5000));
        assertTrue(p1.addViewer(p2));
        p2.remove();
        assertTrue(p1.hasPredictableViewers(), "manual viewer set still holds the removed player");
        assertFalse(p1.removeViewer(p2), "removed player is still registered as a manual viewer");
    }
}
```

**判定**：【读代码确认】

---

### P1-3 `acquirable-strict` 是死代码：`assertOwnership()` 零调用点

**严重度 P1** · `src/main/java/net/minestom/server/thread/AcquirableImpl.java:106-114`

```java
@Override
public void assertOwnership() {
    if (!ASSERTIONS_ENABLED && !ServerFlag.ACQUIRABLE_STRICT) return;
    if (isOwned()) return;
    ...
    throw new AcquirableOwnershipException(initThread, assignedThread, unwrap().toString());
}
```

全仓库搜索 `assertOwnership` 只有两处命中：接口声明（`Acquirable.java:177`）和这个实现。没有任何实体字段、setter、`MetadataHolder` 写入、`setInstance`/`remove`/`teleport` 调用它。`git log -S assertOwnership --all` 只有引入它的 `4459241c1d`（Acquirable strict, #2857），从来没有调用者。

与此同时该开关在测试 JVM 里默认打开：`build-src/src/main/kotlin/minestom.java-library.gradle.kts:73` 的 `jvmArgs("-Dminestom.acquirable-strict=true")`、`build.gradle.kts:81,85`。唯一用来证明它有效的断言从引入之日起就是注释掉的：`src/test/java/net/minestom/server/thread/AcquirableEntityIntegrationTest.java:23`

```java
//assertThrows(AcquirableOwnershipException.class, () -> zombie.setInstance(instance, new Pos(1, 41, 1)).join());
```

**影响**：用户在 CI 里开着 strict 模式、看不到任何失败，据此认为自己的多线程插件是线程干净的；P0-2、P0-3 以及下面 P1-4/P1-5/P1-6 这一整类跨线程写入在运行期完全不可检测。

**建议修复**：在 `Entity#setPositionInternal` / `refreshCoordinate` / `remove(boolean)` / `setInstance` / `setVelocity` / `addPassenger` / `LivingEntity#setHealth` / `Player#setGameMode` / `MetadataHolder#set` 顶部调用 `acquirable.assertOwnership()`，并取消那行注释。

**复现测试**

```java
@EnvTest
class AcquirableStrictTest {
    @Test
    public void foreignThreadMutationThrows(Env env) throws Exception {
        var instance = env.createFlatInstance();
        var zombie = new Entity(EntityType.ZOMBIE);
        zombie.setInstance(instance, new Pos(0, 42, 0)).join();
        env.tick(); // 让 dispatcher 完成 assign
        var thrown = new CompletableFuture<Throwable>();
        Thread.startVirtualThread(() -> {
            try { zombie.setVelocity(new Vec(1, 0, 0)); thrown.complete(null); }
            catch (Throwable t) { thrown.complete(t); }
        });
        assertInstanceOf(AcquirableOwnershipException.class, thrown.get(2, TimeUnit.SECONDS));
    }
}
```

**判定**：【读代码确认】

---

### P1-4 寻路：任何非空气方块都阻断行走；`WaterNodeGenerator` 100% 失效

**严重度 P1** · `src/main/java/net/minestom/server/entity/pathfinding/generators/NodeGenerator.java:56-63`
**关联**：#3224、#2813，部分 #2252

```java
default boolean canMoveTowards(Block.Getter getter, Point start, Point end, BoundingBox boundingBox) {
    final Point diff = end.sub(start);
    if (getter.getBlock(end) != Block.AIR) return false;          // :59
    PhysicsResult res = CollisionUtils.handlePhysics(getter, boundingBox, start.asPos(), diff.asVec(), null, false);
    return !res.collisionZ() && !res.collisionY() && !res.collisionX();
}
```

第 59 行是**引用相等**的类型短路，走到这里就返回，下面基于 `collisionShape()` 的精确模拟根本不执行。小麦长在泥土上时 `gravitySnap` 落脚点就是作物所在格，随后 `WHEAT != Block.AIR` 直接作废 —— 这就是「1/16 高的作物挡路」的完整机制，作物的碰撞箱信息从头到尾没被读过。

连带受害：地毯、雪层、活板门、火把、草、水，以及 **`Block.CAVE_AIR` / `VOID_AIR`**（不是 `Block.AIR` 这个引用）。用 CAVE_AIR 填充的世界寻路完全瘫痪。

**`WaterNodeGenerator` 是自相矛盾的**：`WaterNodeGenerator.java:44/49/54/66/76` 每个候选节点都要求终点方块**是 WATER**，但它不覆写 `canMoveTowards`（`grep` 确认只有 `PreciseGroundNodeGenerator.java:139` 覆写了），落到上面这个要求终点**是 AIR** 的默认实现。两条件互斥 → `getWalkable` 恒返回空集 → 路径被标 `INVALID`。水生生物寻路是静默失效的死功能。

**建议修复**：删掉第 59 行短路，依赖下面已有的 `handlePhysics`；若要保留快速路径，用 `collisionShape().isEmpty()` 而非 `!= Block.AIR`。一处改动同时修 #3224、#2813、CAVE_AIR 与 `WaterNodeGenerator`。

**次生**：`GroundNodeGenerator.java:124` 与 `NodeGenerator.java:77` 用 `solid()` 而不是 `blocksMotion()` 判落脚点。`Block.java:280-291` 的 javadoc 明确写着 `solid()` 不是移动阻挡判据（蜘蛛网 solid=true 但不挡路），后果是蜘蛛网被当地面、DIRT_PATH/SOUL_SAND（碰撞高 0.9375）被 snap 到 `blockY+1` 从而抖动。

**复现测试**

```java
@EnvTest
class PathfindingCropTest {
    @Test
    public void canPathOverCrops(Env env) {
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();
        for (int x = 0; x <= 5; x++) instance.setBlock(x, 40, 0, Block.WHEAT);
        var creature = new EntityCreature(EntityType.ZOMBIE);
        creature.setInstance(instance, new Pos(0, 40, 0)).join();
        assertTrue(creature.getNavigator().setPathTo(new Pos(5, 40, 0)),
                "1/16-height crops must not block pathfinding");
        assertNotNull(creature.getNavigator().getNodes());
    }
}
```

**判定**：【读代码确认】

---

### P1-5 寻路穿越未加载区块直接抛 NPE（#3054），PR #3056 修不掉

**严重度 P1** · `src/main/java/net/minestom/server/entity/pathfinding/generators/GroundNodeGenerator.java:119-129`、`Navigator.java:106`

`Navigator` 把 `instance` 本身当 `Block.Getter` 传进 `PathGenerator`，而：

```java
// Instance.java:745-750
public @Nullable Block getBlock(int x, int y, int z, Condition condition) {
    final Block block = blockRetriever.getBlock(x, y, z, condition);
    if (block == null) throw new NullPointerException("Unloaded chunk at " + x + "," + y + "," + z);
    return block;
}
```

`Navigator.java:88-91` 只校验**目标点**所在区块，而 A* 会向四周扩展最多 `maxDistance*10` 个节点，跨越到未加载区块是必然的。未保护的读块点还有 `NodeGenerator.java:59/77`、`PathGenerator.java:26/30`（`maxFall=100`，向下扫 100 格）、`WaterNodeGenerator.java:44/49/54/66/76`。

**PR #3056 无效**：它加的是 `if (blockAtFloor == null) continue;`，但 `Instance#getBlock` 在未加载时是**抛异常**不是返回 null，那些 null 分支在 `Instance` 作为 getter 时永远不可达。

**建议修复**：`Navigator.java:106` 与 `:162` 改传 `new ChunkCache(instance, entity.getChunk(), Block.STONE)` —— 实体物理路径（`CollisionUtils.java:105`）已经是这么做的，把未加载区块当 STONE 在语义上正确。

**判定**：【读代码确认】 · 关联 #3054、PR #3056

**复现测试**

```java
@EnvTest
class PathfindingUnloadedChunkTest {
    @Test
    public void pathingNearUnloadedChunkDoesNotThrow(Env env) {
        var instance = env.createFlatInstance();
        instance.setAutoChunkLoad(false);
        instance.loadChunk(0, 0).join();     // 只加载一个区块
        var creature = new EntityCreature(EntityType.ZOMBIE);
        creature.setInstance(instance, new Pos(8, 40, 8)).join();
        assertDoesNotThrow(() -> creature.getNavigator().setPathTo(new Pos(14, 40, 14)));
    }
}
```

---

### P1-6 `setHealth` 不钳制下界 + `isDead` 单向闩锁 → 实体永久无敌

**严重度 P1** · `src/main/java/net/minestom/server/entity/LivingEntity.java:425-434`

```java
public void setHealth(float health) {
    this.health = Math.min(health, (float) getAttributeValue(Attribute.MAX_HEALTH));   // 只钳上界
    if (this.health <= 0 && !isDead) {
        kill();
    }
    LivingEntityMeta meta = getLivingEntityMeta();
    if (meta != null) meta.setHealth(this.health);
}
```

两个后果：① `isDead` 已为 `true` 时（例如监听器自己调了 `kill()`），`setHealth(getHealth() - dmg)`（`:376`）落到负值，`!isDead` 挡住重新 kill，负血量被写进 `LivingEntityMeta` 发到客户端。② 「取消死亡」的标准写法 —— 在 `EntityDeathEvent` 里 `heal()` —— 恢复了 `health` 却留下 `isDead == true` 永远为真（`refreshIsDead` 是 `protected`，`:666`，没有公开的复活 API），此后 `damage()` 在 `:339` 永远返回 `false`：满血且永久无敌。

**建议修复**：`Math.clamp(health, 0, max)`；health > 0 时重置 `isDead`，或公开 `setDead(boolean)`。

**同一片区的 P1**：`kill()`（`:273-289`）没有 `if (isDead()) return;` 幂等保护，第 274 行的注释「So the entity isn't killed over and over again」只挡住了 `setHealth` 的隐式重入，显式二次 `kill()` 会再触发一次 `EntityDeathEvent`（掉落物翻倍、击杀计数翻倍）。`Player#kill()`（`Player.java:495-541`）只把死亡消息块包进 `if (!isDead())`，第 540 行 `super.kill()` 是无条件的，于是 `EntityDeathEvent` 会重复而 `PlayerDeathEvent` 不会 —— 两个事件语义不一致。

**复现测试**

```java
@EnvTest
class LivingEntityDeathTest {
    @Test
    public void healingInDeathEventDoesNotBrickTheEntity(Env env) {
        var instance = env.createFlatInstance();
        var zombie = new EntityCreature(EntityType.ZOMBIE);
        zombie.setInstance(instance, new Pos(0, 42, 0)).join();
        zombie.eventNode().addListener(EntityDeathEvent.class, e -> ((LivingEntity) e.getEntity()).heal());
        zombie.damage(new Damage(DamageType.GENERIC, null, null, null, 100f));
        assertFalse(zombie.isDead(), "healing in EntityDeathEvent must clear isDead");
        assertTrue(zombie.damage(new Damage(DamageType.GENERIC, null, null, null, 1f)),
                "entity became permanently invulnerable");
    }

    @Test
    public void killIsIdempotent(Env env) {
        var instance = env.createFlatInstance();
        var zombie = new EntityCreature(EntityType.ZOMBIE);
        zombie.setInstance(instance, new Pos(0, 42, 0)).join();
        var deaths = env.trackEvent(EntityDeathEvent.class, EventFilter.ENTITY, zombie);
        zombie.kill();
        zombie.kill();
        deaths.assertCount(1);
    }
}
```

**判定**：【读代码确认】

---

### P1-7 `MetadataHolder.entries` 是无同步的读-复制-写，元数据会静默丢失

**严重度 P1** · `src/main/java/net/minestom/server/entity/MetadataHolder.java:171`、`:282-291`

```java
private Metadata.@Nullable Entry<?>[] entries = new Metadata.Entry<?>[0];   // :171 非 volatile
...
private void putEntry(int id, Metadata.Entry<?> entry) {
    var entries = this.entries;
    if (id >= entries.length) this.entries = entries = Arrays.copyOf(entries, id + 1);
    entries[id] = entry;
}
```

同一类里 `notifyAboutChanges` 用了 `volatile` + VarHandle CAS、`notNotifiedChanges` 用了 `synchronized`（`:242`、`:274`）—— 说明跨线程使用是**设计意图**，但真正存数据的数组没有任何保护。两个并发 `set()` 若都触发扩容，其中一个写会被完全覆盖；`getEntries()`（`:293`，`Entity#getMetadataPacket` 在添加 viewer 的任意线程上调用）可能读到旧引用或未发布的元素。

**建议修复**：`entries` 声明为 volatile 并把 `putEntry` 放进锁；或改用 VarHandle CAS 发布。

**同族 P2 —— `setNotifyAboutChanges` 不可重入**（`:266-280`）：标志是布尔而非深度计数器。嵌套序列 `false→false→true→true` 中，第二个 `false` CAS 失败直接返回，第一个 `true` 会**提前 flush 外层批次**。`Entity#editEntityMeta`（`Entity.java:371-381`）和 `LivingEntity#refreshActiveHand`（`LivingEntity.java:616-628`）都在 `try/finally` 里无条件翻转，嵌套调用轻易触发。后果是批次被拆成多个 `EntityMetaDataPacket`，中间态位掩码上线 —— 正是 `:215-218` 注释里引用 PR 3089 想避免的行为。

**同族 P2 —— 丢更新**（`:239-247`）：线程 A 读到 `notifyAboutChanges == false` 但还没进 `synchronized`，线程 B 执行 `setNotifyAboutChanges(true)` 赢得 CAS、看到空 map 在 `:275` 提前返回；A 随后把条目塞进 `notNotifiedChanges`。此后标志已是 `true`，这条更新**再也不会被 flush**：服务端 `get()` 看着正常，客户端永远收不到。

**复现测试**

```java
@Test
public void concurrentMetadataWritesAreNotLost() throws Exception {
    var entity = new Entity(EntityType.ZOMBIE);
    var holder = new MetadataHolder(_ -> {});
    var start = new CountDownLatch(1);
    var done = new CountDownLatch(2);
    Runnable a = () -> { await(start); holder.set(MetadataDef.Zombie.IS_BABY, true); done.countDown(); };
    Runnable b = () -> { await(start); holder.set(MetadataDef.Zombie.IS_BECOMING_DROWNED, true); done.countDown(); };
    Thread.startVirtualThread(a); Thread.startVirtualThread(b);
    start.countDown(); done.await();
    assertTrue(holder.get(MetadataDef.Zombie.IS_BABY));
    assertTrue(holder.get(MetadataDef.Zombie.IS_BECOMING_DROWNED));
}
```

**判定**：【读代码确认】（JMM 层面）/ 触发频率【需要运行验证】 · 关联 #3141

---

### P1-8 位掩码写入从 0 起算而非从字节默认值 → 关掉 Mannequin 一个部件会关掉全部

**严重度 P1** · `src/main/java/net/minestom/server/entity/MetadataHolder.java:225-230`

```java
case MetadataDef.Entry.BitMask bitMask -> {
    Metadata.Entry<?> currentEntry = entryAt(id);
    byte maskValue = currentEntry != null ? (byte) currentEntry.value() : 0;   // 种子恒为 0
    maskValue = setMaskBit(maskValue, bitMask.bitMask(), (Boolean) value);
    yield Metadata.Byte(maskValue);
}
```

`MetadataDef.java:261-268` 声明 `Mannequin.DISPLAYED_MODEL_PARTS_FLAGS` 默认 `0x7F`，七个 `bitMask` 伴生项默认全 `true`。而 `MetadataDef.Entry.BitMask` 只携带单 bit 的 `Boolean` 默认值，`set()` 在结构上拿不回 `0x7F`。

新建的 mannequin 调 `setHatEnabled(false)`：`get(IS_HAT_ENABLED)` 因槽位为空返回 `true`（`:196` `if (value == null) return entry.defaultValue();`），所以 `:219` 的等值早退不触发；随后 `maskValue = 0`，`setMaskBit(0, 0x40, false) = 0`，索引 16 被写成 `0` 而不是 `0x3F`。紧接着 `isCapeEnabled()` / `isJacketEnabled()` 等全部从 `true` 翻成 `false`，并且 `0` 被发给所有 viewer。

**影响**：关闭 mannequin 任意一个模型部件会隐藏全部部件（服务端与线上一致地错）。`Mannequin` 是 `MetadataDef` 里唯一「掩码字节非零默认 + 位默认 true」的类型，因此目前只有它受害（`SnowGolem`，`:550-552`，因为字节默认写成 0 而侥幸躲过 —— 见 P3-2）。

**建议修复**：给 `MetadataDef.Entry.BitMask` / `ByteMask` 附带其容器字节的默认值，`set()` 时以之为种子。

**复现测试**

```java
@Test
public void disablingOneMannequinPartKeepsOthers() {
    var mannequin = new Entity(EntityType.MANNEQUIN);
    var meta = (MannequinMeta) mannequin.getEntityMeta();
    assertTrue(meta.isCapeEnabled());
    meta.setHatEnabled(false);
    assertFalse(meta.isHatEnabled());
    assertTrue(meta.isCapeEnabled(), "disabling the hat cleared every other model part");
}
```

**判定**：【读代码确认】

---

### P1-9 `AttributeInstance` 的 javadoc 承诺线程安全，但缓存值有丢更新

**严重度 P1** · `src/main/java/net/minestom/server/entity/attribute/AttributeInstance.java:19`、`:103`、`:185`

类 javadoc 明写「This class is thread-safe (you do not need to acquire the entity to modify its attributes)」。但 `modifiers`（ConcurrentHashMap）与 `baseValueBits`（AtomicLong）各自安全，**「变更 → 重算 → 发布」这个序列不是原子的**：

```
T1: modifiers.put(M1)
T2: modifiers.put(M2)
T2: cachedValue = compute(M1,M2)
T1: cachedValue = compute(M1)      <-- 永久停留在缺 M2 的值
```

`setBaseValue`（`:79-83`）更糟：`getAndSet` 之后把**过期的局部变量** `baseValue` 传给 `refreshCachedValue`，而不是重新读一次。

**影响**：属性值静默错误，广播出去的 `EntityAttributesPacket` 也带着输掉的那份快照，客户端与服务端不一致。

**核对为正确、不必上报的部分**：`computeValue`（`:163-181`）的三种 operation 数学与原版 `AttributeInstance#calculateValue` 完全一致（ADD_VALUE 折进 base；ADD_MULTIPLIED_BASE 以加后 base 为乘数；ADD_MULTIPLIED_TOTAL 复合），末尾 clamp 也在。`addModifier` / `removeModifier` / `clearModifiers` / `setBaseValue` 四条路径**都**会走 `refreshCachedValue` → 监听器 → 发包，**移除也会广播**。

**判定**：竞态本身【读代码确认】，实际命中【需要运行验证】

---

### P1-10 药水效果从不重发给新 viewer，重生后客户端静默丢失

**严重度 P1** · `src/main/java/net/minestom/server/entity/LivingEntity.java:554-561`

```java
@Override
public void updateNewViewer(Player player) {
    super.updateNewViewer(player);
    player.sendPacket(this.getEquipmentsPacket());
    if (shouldSendAttributes()) player.sendPacket(this.getPropertiesPacket());
}
```

装备 ✅、属性 ✅ 都会补发，**效果 ❌ 不会**。`Potion#sendAddPacket` 只在 `addEffect` 时发一次（`Entity.java:1547-1553`）。`Player#refreshAfterTeleport`、`Player#showPlayer`、`refreshClientStateAfterRespawn` 同样不补发。

**影响**：走出视距再回来的观察者永远看不到效果粒子/图标；`Player#respawn()` 发 `RespawnPacket`（`Player.java:555`）后原版客户端会清空自己的效果列表，而服务端仍认为效果生效 —— 玩家每次死亡后自身效果都从客户端消失。

**建议修复**：在 `LivingEntity#updateNewViewer` 与 `refreshClientStateAfterRespawn` 里按 `getActiveEffects()` 补发 `EntityEffectPacket`，且用**剩余**时长（`startingTicks + duration - getAliveTicks()`）。

**判定**：【读代码确认】 · 与 #2586 相邻但不同根因

---

### P1-11 `EntityCreature.target` 与 `Navigator.goalPosition` 永不清除

**严重度 P1** · `src/main/java/net/minestom/server/entity/EntityCreature.java:32,118-120`；`pathfinding/Navigator.java:69-74`

`EntityCreature.target` 全仓库只有 `FollowTargetGoal.java:54` 会写入，**没有任何地方**在目标被 `remove()` 后置空。怪物锁定玩家 → 玩家下线 → 怪物存活期间一直强引用该 `Player`。

`Navigator#setPathTo(null)` 的 javadoc 写着「null to reset the pathfinder」，实现却只清 `path`：

```java
public synchronized boolean setPathTo(@Nullable Point point, ...) {
    final Instance instance = entity.getInstance();
    if (point == null) { this.path = null; return false; }     // goalPosition / computingPath 都没清
```

对比同类里真正的 `reset()`（`:201-208`）做了三件事。所有 goal 的 `end()` 都走这条路（`FollowTargetGoal.java:96`、`MeleeAttackGoal.java:113`、`RangedAttackGoal.java:156`、`CombinedAttackGoal.java:222`）。后果：`getPathPosition()` 返回 `goalPosition`，实体寻过一次路之后就永不为 null → `RandomLookAroundGoal.java:58` 的 `getPathPosition() == null` 恒假，该 goal 在实体余生**再也不会启动**。

**建议修复**：`point == null` 分支改为 `reset(); return false;`；`EntityCreature#getTarget()` 里对 `target.isRemoved()` 做惰性置空。

**判定**：【读代码确认】

---

### P1-12 `Entity#setInstance` 被事件取消时返回 `null`，`addPassenger` 会因此 NPE

**严重度 P1** · `src/main/java/net/minestom/server/entity/Entity.java:924`、`:1099`

```java
if (event.isCancelled()) return null; // TODO what to return?      // :924
...
if (!currentInstance.equals(entity.getInstance()))
    entity.setInstance(currentInstance, position).join();          // :1099
```

`AddEntityToInstanceEvent` 是 `CancellableEvent`。取消它之后任何 `setInstance(...).thenRun(...)` / `.join()` 都是 NPE。`Entity#addPassenger` 是内部必经路径，`RangedAttackGoal.java:80`、`CombinedAttackGoal.java:184` 也直接 `.join()`。

`Player` 侧同样有问题：`Player#spawnPlayer` 的 `var _ = super.setInstance(instance, spawnPosition);`（`Player.java:792`）吞掉返回值，事件取消后**玩家没被加进实例**却继续走 `synchronizePositionAfterTeleport` 和 `PlayerSpawnEvent`，处于半初始化状态。

**建议修复**：改成返回 `CompletableFuture.failedFuture(...)` 或 `AsyncUtils.VOID_FUTURE`，并在 `spawnPlayer` 里检查结果。同时把 `:1099` 的 `.join()` 换成续体，它现在是在 tick 线程上（持有 `TickThread.lock()`）阻塞等区块加载。

**判定**：【读代码确认】

---

### P1-13 `EntityPose.SWIMMING` 在服务端不可达

**严重度 P1** · `src/main/java/net/minestom/server/entity/Player.java:910-943`、`Entity.java:1301-1313`
**关联**：open PR #3207

两个 `updatePose` 实现都读 `meta.isSwimming()`，但 `grep -rn "setSwimming" src/main src/test` 的结果是：**除了这两处读取以外，全仓库没有任何调用**。`EntityMeta#setSwimming` 从来没有被调用过，因此 `SWIMMING` 姿势在服务端永远不会产生。PR #3207 对 master 问题的描述属实。

**影响**：玩家在水中游泳时姿势仍是 STANDING，碰撞箱保持 1.8 高，所有观察者看到直立姿态。

**判定**：【读代码确认】

---

### P2 组

**P2-1 `updatePose()` 会在下一 tick 覆盖 `DYING` 姿势** —— `Entity.java:1301` 的分支表里没有 `isDead()`，而 `Player#update` 每 tick 无条件调 `updatePose()`（`Player.java:488`）。`LivingEntity#kill()` 在 `:276` 设的 `EntityPose.DYING` 最多存活一个 tick 就被改回 `STANDING`。死亡玩家渲染成站立。修法：`updatePose` 首个分支加 `if (isDead()) { setPose(DYING); return; }`。【读代码确认】

**P2-2 `Player#setHealth` 发送未钳制的原始参数** —— `Player.java:1119-1122` 先 `sendPacket(new UpdateHealthPacket(health, ...))` 再 `super.setHealth(health)` 钳制。`setHealth(1000f)` 让客户端血条停在 1000 而服务端是 20，且没有任何后续包纠正。修法：交换顺序并回读 `getHealth()`。【读代码确认】

**P2-3 修改 `MAX_HEALTH` 不重新钳制当前血量** —— `LivingEntity#onAttributeChanged`（`:483`）只发包不碰 `health`。把 max 从 20 降到 5 后 `getHealth()` 仍是 20，直到下一次 `setHealth` 才突然掉下来。【读代码确认】

**P2-4 裸 `LivingEntity` 出生只有 1 点血** —— `LivingEntity.java:96` `private float health = 1F;`，而 `heal()` 只在 `EntityCreature.java:40` 和 `Player.java:1585` 被调用。`new LivingEntity(EntityType.ZOMBIE)` 一下就死，且因为从未调 `setHealth`，`LivingEntityMeta.health` 也没写过。【读代码确认】

**P2-5 `Entity#updateViewerRule()` / `setAutoViewEntities()` 对非玩家实体 NPE** —— `EntityView.java:46-48`，`viewerOption` 的 `addition`/`removal` 对非 `Player` 是 `null`；`updateRule0`（`:245-259`）与 `updateAuto`（`:222-230`）都会直接 `action.accept(entity)`。对一个附近有其他自动可见实体的僵尸调 `updateViewerRule()` 就 NPE。这是公开 API。修法：`addition == null` 时短路。【读代码确认】

**P2-6 `EntityView.Option#update` 持锁回调造成锁序反转** —— `updateAuto`/`updateRule` 在 `synchronized (mutex)`（`EntityView.java:225`、`:233`）内调用 `addition`，后者进入 `collectEntityChain`，那里按 entity id 顺序取双锁（`:73-77`）。若本实体 id 大于对方，加锁顺序就与规范顺序相反，与并发跑 tracker 更新的线程构成 ABBA 死锁。`Entity.java:585` 的 javadoc「Method can be subject to deadlocking」表明作者知情。【读代码确认】（死锁实际发生【需要运行验证】）

**P2-7 手动 `addViewer` 不发送乘客包** —— `Entity#addViewer`（`:569-574`）直接调 `updateNewViewer`，而后者第 594 行注释「Passengers are handled in EntityView」—— 只有自动路径 `showEntityToPlayer`（`EntityView.java:63-69`）会发 `SetPassengersPacket`。`Player#respawn` 第 575 行也是直接调 `updateNewViewer`。后果：手动隐藏再显示、或重生之后，骑乘中的实体在客户端表现为已下马。关联 #2797、#1750。【读代码确认】

**P2-8 `refreshPosition` 拿新位置与 `lastSyncedPosition` 比较后提前返回，`this.position` 不更新** —— `Entity.java:1391-1392`：

```java
final Pos lastSyncedPosition = this.lastSyncedPosition;
if (position.equals(lastSyncedPosition)) return;      // 此时 this.position 可能已经不是 lastSyncedPosition
```
第 1396 行的早退（`nextSynchronizationTick <= ticks + 1 || !sendPackets`）会让 `lastSyncedPosition` 落后于 `position`。对 `SYNCHRONIZE_ONLY_ENTITIES`（箭矢、掉落物、TNT 等，`:144-148`）`sendPackets` 恒为 false，`lastSyncedPosition` 只在每 20 tick 的 `synchronizePosition` 更新，窗口很宽。此时若物理把实体送回上次同步的位置，第 1392 行会跳过 `setPositionInternal`，服务端位置停留在错误值。正确的守卫应当比较 `this.position`。可能与 #1622（ItemEntity 与客户端失步）相关。【疑似】

**P2-9 `MetadataDef.count(Slime.class)` NPE** —— `MetadataDefImpl.java:41` 的 `MAX_INDEX.get(name)` 拆箱，而 `MAX_INDEX` 只由 `index`/`bitMask`/`byteMask` 填充。`MetadataDef.Slime`（`MetadataDef.java:677`）与 `MagmaCube`（`:680`）是空类（符合协议，无附加 metadata），没有键。`count(...)` 是有 javadoc 的公开 API。同样的拆箱在 `findSuperIndex`（`:63`）里，意味着「加一个空的中间类」会变成 `ExceptionInInitializerError` 地雷。修法：`getOrDefault(name, -1)`。【读代码确认】

**P2-10 `PlayerSkin#fromUsername` 仍不健壮（#3231）** —— 用户名正则回归已由 `810c317250 "Match vanilla username regex (#3350)"` 修好（`StringUtils.isValidUsername` 现在是 1–16 位可打印 ASCII）。但 `URLUtils.getText`（`utils/url/URLUtils.java:17-43`）**没有连接/读取超时**，且 `connection.getErrorStream()` 在无响应体的错误（如空体 429）时返回 `null`，`new InputStreamReader(null, UTF_8)` 会 NPE —— 而 `MojangUtils.fromUsername`（`:97-104`）只 `catch (IOException)`，NPE 与 `JsonParser` 的 `IllegalStateException`（响应不是 JSON 对象时）都会逃逸到调用方。`retrieve` 还把限流、404、宕机统统折叠成 `null`（`MojangUtils.java:156`）。这与 issue 里「实际是 Mojang 限流，但表现为 null 且无从区分」的结论一致。【读代码确认】

**P2-11 `RabbitMeta.Variant` / `ParrotMeta.Color` 的枚举名与原版不符（#3141）** —— 两个枚举都通过 `Codec.Enum`（`Codec.java:154-159`，`name().toLowerCase()`）注册到 `DataComponents`（`DataComponents.java:184`、`:189`），因此常量名直接进 NBT。`RabbitMeta.java:53-59` 的 `BLACK_AND_WHITE` / `SALT_AND_PEPPER` / `KILLER_BUNNY` 对应原版的 `white_splotched` / `salt` / `evil`；`ParrotMeta.java:48-52` 的 `GREY` 对应原版 `gray`（Minestom 自己的 `HorseMeta.Variant` 拼的是 `GRAY`）。编码产出原版拒收的 NBT，解码原版 NBT 时 `Enum.valueOf` 抛异常。此外 `Metadata.Type` 记录（`Metadata.java:338-345`）根本没有 `Codec` 字段 —— 整个 EntityMeta 的 NBT 序列化能力是缺失而非「不正确」，这正是 #3141 想要审计的前置条件。【读代码确认】+ wiki 核对

---

### P3 组（代码异味 / 低影响）

**P3-1 `Entity#updatePose` 缺 SLEEPING 分支** —— `Player#updatePose`（`Player.java:918`）有 `getBedInWhichSleepingPosition() != null` 判断，基类（`Entity.java:1301-1313`）没有，非玩家 `LivingEntity` 躺床不会进入 SLEEPING 姿势。

**P3-2 `SnowGolem` 掩码字节默认值自相矛盾** —— `MetadataDef.java:551-552`：`SNOW_GOLEM_FLAGS` 默认 `0`，而伴生的 `PUMPKIN_HAT = bitMask(0, 0x10, true)` 默认 `true`。原版该字节默认是 `0x10`。目前只影响直接读 `SNOW_GOLEM_FLAGS` 的用户。

**P3-3 `refreshPosition` 里的 `nextSynchronizationTick = synchronizationTicks + 1`** —— `Entity.java:1414` 是绝对值赋值，其余所有地方都是 `ticks + synchronizationTicks` 的相对形式。对已存活超过 `synchronizationTicks` 的实体，效果恰好等价于「下一 tick 强制同步」，但语义显然写错了。

**P3-4 `PhysicsUtils#updateVelocity` 在位置未变时丢弃全部 x/z 速度** —— `collision/PhysicsUtils.java:68-71`，实体被墙挡住或静止时速度被替换成纯重力向量，向上速度也被抹掉。与 #2702、#1107 描述一致。另外第 40 行传的是**上一 tick 的** `entityOnGround` 而非 `physicsResult.isOnGround()`，落地那一 tick 不施加摩擦。

**P3-5 `InstanceContainer#unloadChunk` 会把区块内的玩家一起 `remove()`** —— `InstanceContainer.java:301` 用的是 `Target.ENTITIES`（含 Player），移除 Player 会触发 `PlayerDisconnectEvent` + kick。手动调 `unloadChunk` 的插件会意外踢人。

---

## ③ 上游 issue 复核表（本范围内）

| Issue | 结论 | 依据 |
|---|---|---|
| #2649 EntityTracker leaking memory | **仍存在** | `EntityTrackerImpl.java:286/295` 空桶不删、`:218` ChunkView 无注销路径；修复分支 `fix/viewable-leak` 未合 |
| #3231 PlayerSkin#fromUsername broken | **部分修复** | 正则回归已由 `810c317250` 修好；`URLUtils.java:17-43` 无超时、`getErrorStream()` 可为 null 导致 NPE 逃逸 `catch(IOException)` |
| #3057 Protocol error after switching entity type | **仍存在** | 根因不是「旧索引残留」（`Entity.java:646` 会整体重建 holder），而是 `Entity.java:1725` + `Player.java:659` 把新类型 metadata 发给玩家自己 |
| #2152 switchEntityType → ClassCastException | **仍存在** | `Player.java:1131` 硬转型，`LivingEntity.java:363`（伤害）与 `Player.java:1693`（改设置）是用户躲不掉的内部调用者 |
| #3141 Missing/Incorrect Codec for EntityMeta NBT | **仍存在** | `Metadata.java:338` 的 `Type` 记录无 `Codec` 字段；`RabbitMeta.java:53`、`ParrotMeta.java:48` 的常量名与原版不符 |
| #3054 Unloaded chunk error in GroundNodeGenerator | **仍存在**，PR #3056 无效 | `GroundNodeGenerator.java:124` 无保护读块；`Instance.java:748` 未加载时抛 NPE 而非返回 null，PR 的 null 分支不可达 |
| #3224 / #2813 作物/蔬菜阻挡寻路 | **仍存在** | `NodeGenerator.java:59` `!= Block.AIR` 短路，碰撞形状根本没被读 |
| #2252 寻路不支持台阶抬升 | **仍存在** | 全仓库无 step height 概念；`GroundNodeGenerator.java:119-129` 只向下扫 |
| #2831 Instance is null in Navigation Entity Tracker | **仍存在** | `Entity.java:1478` 仍是 `assert instance != null`（生产关闭），`Navigator.java:121` tick 入口不判空 |
| #2797 / #1750 乘客隐藏后不可见/下马 | **仍存在** | `Entity.java:590-607` 手动 `updateNewViewer` 不发 `SetPassengersPacket` |
| #2352 EntityCreature + 非生物类型协议错误 | **部分修复** | metadata 侧已由 `getLivingEntityMeta()` 判空解决；`LivingEntity.java:557` 仍无条件发 `set_equipment` |
| #2061 Entity damage display | **已修复** | `LivingEntity.java:353` 已改为 `DamageEventPacket`，可关闭 |
| #1526 Entity#remove on manual view → CME | **已修复** | `EntityView.java:146` `Set.copyOf` 快照 |
| #2702 / #1107 速度不反映真实速度 | **仍存在** | `PhysicsUtils.java:68-71` 位置未变时丢弃 x/z 速度 |
| #2586 Potion effect particles not showing | **无法判断**（根因在注册表数据，超出本范围）；但 `LivingEntity.java:554` 不补发效果是同一现象的独立缺陷 |
| #1622 ItemEntity 与客户端失步 | **无法判断** | 候选机制见 P2-8，需运行验证 |

---

## ④ 未覆盖的区域与原因

- **`entity/metadata/**` 的其余 ~140 个 Meta 类**：只按要求抽查了 10 个常用实体的索引与类型（结论：全部正确，含 1.21.9 的 `Avatar` 重构与 `Mannequin` 的 `index(-1)` 覆盖技巧）。其余类型只做了枚举常量名的横向扫描。
- **`entity/vehicle/**`**：只做了浏览，`PlayerInputs` 是 7 个 boolean 的可变持有类，无缺陷可报。船/矿车的物理不在实体包内。
- **`collision/**` 内部**（`CollisionUtils`、`BoundingBox`、`Shape`）：只审到与 `Entity#movementTick` 的交界，包内的扫描算法留给碰撞专项。#3049（随机偏移未计入碰撞形状）、#2131（栅栏碰撞箱）因此未复核。
- **`Player` 的区块队列 / `chunkAdder` / `sendPendingChunks`**：属于区块流式传输，与 #3083、#3021、#2527 一起留给 instance 专项。
- **网络包编解码**：#3320（Packet id 无法识别 / MemorySegment 越界）、#2334（相对传送 ACK）需要抓包，本次未碰。
- **运行期验证**：按约束未运行 Gradle，所有标【需要运行验证】的条目（P0-2 的具体损坏形态、P0-3 的竞态窗口宽度、P1-9 的交错命中率、P1-8 的客户端表现）都只做了代码层确认。

---

## ⑤ 参考链接

- upstream 修复分支：`upstream/fix/viewable-leak`，commit `b73ca0ccacf7db2fa013bab106fa8fc7bd7ff029`「fix: Memory leak in EntityTracker」（kermandev，2026-01-19，未合并）
- https://github.com/Minestom/Minestom/issues/2649 · #3231 · #3057 · #3141 · #3054 · #3224 · #2813 · #2252 · #2831 · #2152 · #2352 · #2797 · #1750 · #2702 · #1107 · #1622 · #1526 · #2061 · #2586
- https://github.com/Minestom/Minestom/pull/3056（GroundNodeGenerator null 检查，无效）· https://github.com/Minestom/Minestom/pull/3207（游泳姿势）· https://github.com/Minestom/Minestom/pull/3349 / #3350（用户名正则）
- https://minecraft.wiki/w/Java_Edition_protocol/Entity_metadata（26.2 索引表，用于 ② 中 10 个 Meta 的核对）
- https://minecraft.wiki/w/Data_component_format（`rabbit/variant`、`parrot/variant` 的合法字符串）
