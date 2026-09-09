# Minestom 审计 04：线程模型 / 调度器 / 事件系统 / 服务器生命周期

- 仓库：`/Users/bug/Documents/Projects/Minecraft/Minestom`，HEAD `16842f1954`（2026-09-04）
- 审计方式：纯静态阅读 + `git blame` + JCTools 4.0.7 上游源码比对，未运行任何构建
- 覆盖：`thread/`(14) `timer/`(10) `event/`(核心 9 个文件) `ServerProcessImpl` `MinecraftServer` `ServerFlag` `monitoring/` `snapshot/` `utils/ObjectPool` `utils/PacketViewableUtils` `jcstress-tests/`

---

## ① TL;DR

这一层的整体设计意图是清晰的（单写者 partition + Acquirable 跨线程借锁 + 事件树编译成扁平 Consumer 链），但**"把非线程安全容器当成发布后只读的不可变快照"这一错误假设贯穿了事件系统和 packet 层**，而 `WeakHashMap` 恰恰是会在 `get()` 里自我结构性修改的容器，这是本轮最系统性的问题。第二类问题是**锁边界画错**：`EventNodeImpl$Handle` 的缓存失效有一处写在 `GLOBAL_CHILD_LOCK` 之外，能让一个事件节点永久静默失聪；`ThreadDispatcherImpl` 把 `updateAndAwait` 和 `shutdown` 放在同一个对象 monitor 上，直接造成上游 #2907 的停服死锁。第三类是**调度器的状态机不完整**：park/unpark 存在经典 lost-wakeup，`unparkTask` 还把 TICK_END 任务错投到 TICK_START 队列。

最严重的三条：
1. **P0-1**：从 tick 线程调用 `stopCleanly()` 必然死锁（上游 #2907，根因是 4459241c1d/#2857 给 `ThreadDispatcherImpl` 加的 `synchronized`）。
2. **P0-2**：`EventNodeImpl$Handle.invalidate()` 在锁外写 `listener=null`，与锁内的 `listener=X; updated=true` 交错后留下 `updated==true && listener==null` 的**永久**状态，该节点从此丢掉全部该类型事件。
3. **P0-3**：`EventNodeImpl.mappedConsumer()` 把一个 `WeakHashMap` 捕获进热路径 lambda，之后被所有 tick 线程/虚拟线程无锁并发 `get()`，而 `WeakHashMap.get()` 内部会 `expungeStaleEntries()` 做结构性修改。

关于用户点名要优先审的 #3353（`9f5f3e254b`，2026-08-29）：这次改动**本身是正确的**（只是换了队列实现类），但它无意中让 `drain()` 的语义在 `minestom.unsafe-collections` 开关的两侧发生了分叉（详见 P1-6），默认路径不受影响。

---

## ② 发现清单

### P0-1　从 tick 线程停服必然死锁

**严重度**：P0　**位置**：`src/main/java/net/minestom/server/ServerProcessImpl.java:241-251`、`src/main/java/net/minestom/server/thread/ThreadDispatcherImpl.java:49`、`:123`

**问题描述**

```java
// ThreadDispatcherImpl.java:48-71
@Override
public synchronized void updateAndAwait(long time) {
    this.updates.drain(update -> { ... });
    CountDownLatch latch = new CountDownLatch(threads.size());
    for (TickThread thread : threads) thread.startTick(latch, time);
    try { latch.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
}
// ThreadDispatcherImpl.java:122-125
@Override
public synchronized void shutdown() { this.threads.forEach(TickThread::shutdown); }
```

`updateAndAwait` 与 `shutdown` 共用 `ThreadDispatcherImpl` 的对象 monitor，而 `updateAndAwait` 在持有 monitor 的状态下 `latch.await()` 等待所有 `TickThread` 完成本 tick。

交错序列：
- T1（`Ms-TickScheduler` 主 tick 线程）：`ServerProcessImpl$TickerImpl.serverTick` → `dispatcher().updateAndAwait(nanoStart)`（:313）→ 拿到 dispatcher monitor → `latch.await()` 阻塞。
- T2（`Ms-Tick-0` 某个 `TickThread`）：正在 `Player.tick()` → `interpretPacketQueue()` → 执行玩家输入的 `/stop` 命令 → `MinecraftServer.stopCleanly()` → `ServerProcessImpl.stop()`（:241）→ 走到 `dispatcher.shutdown()`（:249）→ 阻塞在 dispatcher monitor 上。
- T2 因此永远回不到 `TickThread.run()` 的 `latch.countDown()`（`TickThread.java:62`），T1 的 `latch.await()` 永远不返回。互相等待成环。

`git blame` 确认 `synchronized` 是 `4459241c1d`（"Acquirable strict"，#2857，2025-08-01）加的，与 issue #2907 里 "Any version after #2857" 完全对上。

**触发场景**：任何在 `TickThread` 上执行的代码调用 `stopCleanly()`——玩家命令、实体 scheduler 任务、实体 tick 里的事件监听器。上游 issue 给的复现就是一个 `/stop` 命令。

**影响**：服务器永久挂死，需要 `kill -9`。同一条链路也影响 SIGINT：`ServerProcessImpl.java:237` 注册的 shutdown hook 调的是同一个 `stop()`，若挂死时按 Ctrl+C，JVM 关闭钩子同样卡住。

**建议修复**：`shutdown()` 不应与 `updateAndAwait` 争同一个 monitor——它要做的只是给每个 `TickThread` 打 `stop` 标记并 unpark，本身是无状态的：

```diff
-    public synchronized void shutdown() {
+    public void shutdown() {   // TickThread.stop 是 volatile，无需 dispatcher monitor
         this.threads.forEach(TickThread::shutdown);
     }
```
另外 `ServerProcessImpl.stop()` 应先把 `stopped` 置位（现状已如此）再让 ticker 自然退出，并在最后 `join()` `TickSchedulerThread` 与各 `TickThread`，否则 `stop()` 返回时仍有线程在跑（见 P2-6）。

**建议的复现测试**（JUnit 5，勿运行）：

```java
@Test
void shutdownFromTickThreadMustNotDeadlock() throws Exception {
    record World() {}
    var dispatcher = ThreadDispatcher.<World, Tickable>singleThread();
    var world = new World();
    var latch = new CountDownLatch(1);
    Tickable element = time -> { dispatcher.shutdown(); latch.countDown(); }; // 在 TickThread 上关停
    dispatcher.createPartition(world);
    dispatcher.updateElement(element, world);
    dispatcher.start();
    var t = new Thread(() -> dispatcher.updateAndAwait(0));
    t.start();
    assertTrue(latch.await(5, TimeUnit.SECONDS), "shutdown() from a TickThread deadlocked");
    t.join(5_000);
    assertFalse(t.isAlive(), "updateAndAwait never returned");
}
```

**判定**：【读代码确认】　**关联**：上游 issue #2907（OPEN）

---

### P0-2　`EventNodeImpl$Handle` 缓存失效竞争 → 节点永久丢事件

**严重度**：P0　**位置**：`src/main/java/net/minestom/server/event/EventNodeImpl.java:380-394`、`:534-541`

**问题描述**

```java
// EventNodeImpl.java:380-394
void invalidate() {
    this.updated = false;      // volatile 写
    this.listener = null;      // 普通写（非 volatile 字段，:355）
}
@Nullable Consumer<E> updatedListener() {
    if (updated) return listener;
    synchronized (GLOBAL_CHILD_LOCK) {
        if (updated) return listener;
        final Consumer<E> listener = createConsumer();
        this.listener = listener;   // (a)
        this.updated = true;        // (b)
        return listener;
    }
}
// EventNodeImpl.java:534-541
void callListener(EventListener<E> listener, E event) {
    EventListener.Result result = listener.run(event);
    if (result == EventListener.Result.EXPIRED) {
        node.removeListener(listener);  // 内部取 GLOBAL_CHILD_LOCK，返回时已释放
        invalidate();                   // ← 这一句在锁外执行
    }
}
```

`invalidate()` 唯一一处**不在 `GLOBAL_CHILD_LOCK` 保护下**的调用点就是 `:539`。交错：
- T1：`handle.call(e)` → `updatedListener()` 见 `updated==false` → 进入 synchronized → 执行 (a) `this.listener = X`。
- T2：另一线程正在跑同一 Handle 的**旧快照** consumer，某个 `expireCount`/`expireWhen` 监听器返回 `EXPIRED` → `:538` `removeListener` 拿锁又放锁 → 在 T1 仍持锁期间执行 `:539` `invalidate()` → `updated=false; listener=null`。
- T1：执行 (b) `this.updated = true`。

终态 `updated == true && listener == null`。此后 `updatedListener()` 走 `:386` 快路径直接返回 `null`，`call()`（`:366-367`）立刻 return，`hasListener()` 返回 false。**这个状态是永久的**，直到该节点上又发生一次显式 `addListener/removeListener/addChild/...`（走 `invalidateEvent` 重新置 `updated=false`）。

次要变体（同样根因）：读者在 `:386` 读到 `updated==true` 后、读 `listener` 之前，`invalidate()` 把 `listener` 置空 → 该次 `call()` 静默丢弃。

`git blame` 显示 `this.listener = null;` 是 `2b661fd48f`（#2212，2024-06-25，"Drop eventnode listener ref on invalidate"）加的，在此之前 `invalidate()` 只写 volatile 的 `updated`，不存在这条数据竞争。

**触发场景**：用户用 `EventListener.builder(X).expireCount(1)` / `expireWhen(...)` 注册一次性监听器（"等下一个 XxxEvent"是极常见写法），同时另一线程首次触发同一节点同一事件类型的 handle 重建。全服 `GlobalEventHandler` 是所有线程共享的，重建触发点很多。

**影响**：受影响节点（可能就是 `GlobalEventHandler`）对某个事件类型**永久静默**，玩家交互全部失效，且无任何异常/日志。属于数据损坏级。

**建议修复**：把 `listener` 声明为 `volatile`，并让 `invalidate()` 的两次写按"先清数据再清标志"的相反顺序也仍不安全——正确做法是把两个字段合并成一个不可变记录做单次 volatile 发布，或至少删掉 `:539` 这句冗余且未加锁的 `invalidate()`（`removeListener` 里的 `invalidateEvent` 已经覆盖了本节点及所有父节点）：

```diff
     void callListener(EventListener<E> listener, E event) {
         var node = (EventNodeImpl<E>) EventNodeImpl.this;
         EventListener.Result result = listener.run(event);
-        if (result == EventListener.Result.EXPIRED) {
-            node.removeListener(listener);
-            invalidate();
-        }
+        // removeListener 已在 GLOBAL_CHILD_LOCK 内 invalidate 本节点与所有父节点
+        if (result == EventListener.Result.EXPIRED) node.removeListener(listener);
     }
```
更稳的版本：`private record Cached<E>(@Nullable Consumer<E> listener) {}` + `private volatile @Nullable Cached<E> cached;`，`invalidate()` 写 `cached = null`，`updatedListener()` 读一次 `cached` 局部变量。

**建议的复现测试**（jcstress，勿运行）：

```java
@JCStressTest
@Outcome(id = "true",  expect = ACCEPTABLE)
@Outcome(id = "false", expect = FORBIDDEN, desc = "handle 永久失聪：updated=true 但 listener=null")
@State
public class EventNodeHandleInvalidateTest {
    static class Ev implements Event {}
    private final EventNode<Event> node = EventNode.all("n");
    private final ListenerHandle<Ev> handle;
    { node.addListener(Ev.class, e -> {}); handle = node.getHandle(Ev.class); }

    @Actor public void actor1() {          // 触发 handle 重建
        node.addListener(EventListener.builder(Ev.class).handler(e -> {}).build());
        handle.hasListener();
    }
    @Actor public void actor2() {          // 模拟 EXPIRED 路径的锁外 invalidate
        node.addListener(EventListener.builder(Ev.class).expireCount(1).handler(e -> {}).build());
        node.call(new Ev());
    }
    @Arbiter public void arbiter(Z_Result r) { r.r1 = node.hasListener(Ev.class); }
}
```

**判定**：【读代码确认】（交错序列可由 `:539` 在锁外这一事实直接推出）　**关联**：无

---

### P0-3　`mappedConsumer()` 把 `WeakHashMap` 捕获进热路径 lambda，多线程无锁 `get()`

**严重度**：P0　**位置**：`src/main/java/net/minestom/server/event/EventNodeImpl.java:492`、`:508-513`

**问题描述**

```java
// EventNodeImpl.java:487-513
private @Nullable Consumer<E> mappedConsumer() {
    ...
    Map<Object, WeakReference<Handle<E>>> handlers = new WeakHashMap<>(mappedNodeCache.size());   // :492
    for (var mappedEntry : mappedNodeCache.entrySet()) { ... handlers.put(...); }
    final BiConsumer<EventFilter<E, ?>, E> mapper = (filter, event) -> {
        final Object handler = filter.castHandler(event);
        final WeakReference<Handle<E>> handleRef = handlers.get(handler);   // :510 ← 无锁，多线程
        final Handle<E> handle = handleRef != null ? handleRef.get() : null;
        if (handle != null) handle.call(event);
    };
```

`handlers` 在 `createConsumer()` 里创建（此时持有 `GLOBAL_CHILD_LOCK`），但被闭包捕获后成为 `Handle.listener` 的一部分，之后**每一次事件分发**都会在无锁状态下调用 `handlers.get(handler)`。

`java.util.WeakHashMap.get()` → `getTable()` → `expungeStaleEntries()`，后者会 poll `ReferenceQueue`、**写 `table[i]`、写 `e.next`、`size--`**。也就是说这是一次**结构性修改**，而不是只读。JDK 文档明确 `WeakHashMap` 非线程安全（包括读）。

交错：实体 A 被移除并被 GC 后，`handlers` 里以 A 为 key 的条目进入 ReferenceQueue。
- T1（`Ms-Tick-0`）分发 `EntityTickEvent` → `handlers.get(entityB)` → `expungeStaleEntries()` 开始把 A 的 Entry 从桶链表上摘下：读 `prev.next`。
- T2（主 tick 线程分发 `InstanceTickEvent`，或虚拟线程上的 `AsyncEvent`）同时进入 `expungeStaleEntries()`，同样摘 A。
- 两者对同一条 `Entry.next` 链做无同步的读-改-写 → 丢失其他条目（某个实体的 mapped 节点从此收不到事件）、`size` 漂移；`table` 数组元素与 `next` 指针的组合写在极端交错下可产生自环，使后续 `get()` 在桶内死循环。

**触发场景**：默认配置即可。`Entity` 构造时就会 `process.eventHandler().map(this, EventFilter.ENTITY)`（`Entity.java:260`），`Instance` 同理（`Instance.java:193`），因此 `GlobalEventHandler` 的 `handlers` 里装着全服所有实体与实例；实体不断创建销毁 → weak key 不断被清 → 不断有 stale entry 待 expunge。事件分发本身天然多线程（`Ms-Tick-*` + 主 tick 线程 + `AsyncEvent` 的虚拟线程）。

**影响**：mapped 事件（`entity.eventNode()` / `instance.eventNode()` 上注册的监听器）随机丢失；理论上可 CPU 死循环。默认开启、无需任何 flag。

**建议修复**：`handlers` 换成 `Map<Object, WeakReference<Handle<E>>>` 的**不可变快照**——因为 value 已经是 `WeakReference`，key 完全不必再用弱引用去持有；用 `IdentityHashMap` 的只读拷贝或 `Map.copyOf` 即可，条目失效由 value 的 `WeakReference.get()==null` 判断（`:511` 已经在判了）。

```diff
-            Map<Object, WeakReference<Handle<E>>> handlers = new WeakHashMap<>(mappedNodeCache.size());
+            // 不能用 WeakHashMap：它在 get() 里做结构性修改，而这个 map 会被多线程无锁并发读
+            Map<Object, WeakReference<Handle<E>>> handlers = new HashMap<>(mappedNodeCache.size());
```
（key 的强引用不会造成实体泄漏：整个 `handlers` 随 `Handle.listener` 一起在下一次 `invalidate()` 时被丢弃；如果担心，改成 `ConcurrentHashMap` 也可。）

**建议的复现测试**（jcstress，勿运行）：

```java
@JCStressTest
@Outcome(id = "2", expect = ACCEPTABLE)
@Outcome(expect = FORBIDDEN, desc = "WeakHashMap 并发 get 后条目丢失")
@State
public class MappedHandlerConcurrentReadTest {
    static final class Owner {}
    private final Map<Object, WeakReference<Object>> handlers = new WeakHashMap<>();
    private final Owner a = new Owner(), b = new Owner();
    { for (int i = 0; i < 1024; i++) handlers.put(new Owner(), new WeakReference<>(new Object())); // 制造可回收 key
      handlers.put(a, new WeakReference<>(a)); handlers.put(b, new WeakReference<>(b)); System.gc(); }
    @Actor public void actor1() { handlers.get(a); }
    @Actor public void actor2() { handlers.get(b); }
    @Arbiter public void arbiter(I_Result r) {
        r.r1 = (handlers.get(a) != null ? 1 : 0) + (handlers.get(b) != null ? 1 : 0);
    }
}
```

**判定**：【读代码确认】（`WeakHashMap.get` 会 expunge 是 JDK 实现事实）　**关联**：`79ce9570ea`（#1835，2023-04-23）引入

---

### P1-1　`Player#setInstance(...).join()` 在任意线程上驱动全局 Scheduler（违反 MPSC 单消费者）

**严重度**：P1　**位置**：`src/main/java/net/minestom/server/entity/Player.java:705-723`（关键行 `:718`）、`src/main/java/net/minestom/server/timer/Scheduler.java:17-36`、`src/main/java/net/minestom/server/timer/SchedulerImpl.java:25`、`:61-72`

**问题描述**

```java
// Player.java:705-723
final Thread runThread = Thread.currentThread();
CountDownLatch latch = new CountDownLatch(1);
Scheduler scheduler = MinecraftServer.getSchedulerManager();   // ← 全局 scheduler
CompletableFuture<Void> future = new CompletableFuture<>() {
    @Override public Void join() {
        if (runThread == Thread.currentThread()) {   // "Prevent deadlock"
            try { latch.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            scheduler.process();                     // :718 ← 在调用者线程上 drain 全局队列
            assert isDone();
        }
        return super.join();
    }
};
```

`Scheduler#process()` 的 javadoc 自己写着 "This method is not thread-safe"（`Scheduler.java:21`），而底层 `tasksToExecute` 是 `Mpsc...`（Multi-**Producer Single-Consumer**）队列（`SchedulerImpl.java:25`），`runTasks` 的 `drain()`（`:67`）在 `synchronized (this)` **之外**执行（`:61`）。也就是说消费端完全没有互斥。

交错：
- T1（`Ms-TickScheduler`）：`TickerImpl.tick` → `scheduler().processTick()`（`ServerProcessImpl.java:276`）→ `runTasks` → `tasksToExecute.drain(...)`。
- T2（任意用户线程/虚拟线程，例如在 `AsyncPlayerConfigurationEvent` 里 `player.setInstance(inst).join()`）：`:718` → `scheduler.process()` → 同一个 `tasksToExecute.drain(...)`。

MPSC 队列的 consumerIndex 是单消费者假设下的 plain/ordered 字段，两个消费者会各自推进它：同一个 slot 可能被两个线程同时读到（任务被执行两次），或某个 slot 被跳过（任务永远丢失）。

**触发场景**：从非 tick-scheduler 线程调用 `player.setInstance(x).join()` / `player.setInstance(x, pos).join()`，且目标区块尚未加载。`AsyncPlayerConfigurationEvent` 的监听器跑在虚拟线程上，是这个写法最常见的落点。

**影响**：全局定时任务被重复执行或静默丢失；`MpscUnboundedXaddArrayQueue` 路径下还可能在 chunk 链表上产生非法状态。

**建议修复**：`join()` 里不应去驱动**全局** scheduler。要么把 `scheduler.process()` 改成只在调用线程确实是 scheduler 的宿主线程时才执行，要么彻底删掉这个"自助驱动"逻辑、让调用者自己不要在 tick 线程上 `join()`（并在 javadoc 里写明）。如果一定要保留，`SchedulerImpl.runTasks` 至少要把 `drain` 放进 `synchronized (this)`，或把队列换成 MPMC。

**建议的复现测试**（jcstress，勿运行）：

```java
@JCStressTest
@Outcome(id = "1", expect = ACCEPTABLE)
@Outcome(expect = FORBIDDEN, desc = "任务被执行 0 次或 2 次")
@State
public class SchedulerConcurrentProcessTest {
    private final Scheduler scheduler = Scheduler.newScheduler();
    private final AtomicInteger runs = new AtomicInteger();
    { for (int i = 0; i < 64; i++) scheduler.scheduleNextProcess(runs::incrementAndGet); }
    @Actor public void actor1() { scheduler.process(); }   // 模拟 TickerImpl
    @Actor public void actor2() { scheduler.process(); }   // 模拟 Player#setInstance().join()
    @Arbiter public void arbiter(I_Result r) { scheduler.process(); r.r1 = runs.get() == 64 ? 1 : 0; }
}
```

**判定**：【读代码确认】（契约违反确定）/【需要运行验证】（具体损坏形态需 jcstress 观测）　**关联**：无

---

### P1-2　`refreshThreads` 迁移 partition 后不重新 `assign` 元素的 `assignedThread`

**严重度**：P1　**位置**：`src/main/java/net/minestom/server/thread/ThreadDispatcherImpl.java:90-94`（对比 `:169-186` 的 `processUpdatedElement`）

**问题描述**

```java
// ThreadDispatcherImpl.java:88-94
final TickThread previous = partitionEntry.thread;
final TickThread next = retrieveThread(partition);
if (next != previous) {
    partitionEntry.thread = next;
    previous.entries.remove(partitionEntry);
    next.entries.add(partitionEntry);
}
```

partition 换线程后，`partitionEntry.elements` 里所有 `Tickable` 的 `AcquirableImpl.assignedThread` 仍然指向 `previous`。全仓库唯一调用 `assign()` 的地方是 `processUpdatedElement`（`:183`），而它只在收到 `Update.ElementUpdate` 时才跑——实体没换 chunk 就不会有这个信号。

后果：`next` 线程在 tick 该实体，但 `entity.acquirable().sync(...)` 会去锁 `previous.lock()`（`AcquirableImpl.java:56-68`），**锁到了错误的线程**，等于没有互斥。开启断言时会直接在 `TickThread.assertElement`（`TickThread.java:90-94`，`source.acquirable().assignedThread() == this`）触发 `AssertionError`。

**触发场景**：自定义 `ThreadProvider` 且 `refreshType()` 返回 `RefreshType.ALWAYS`。Minestom 默认 `ThreadProvider.counter()` 用的是 `NEVER`（`ThreadProvider.java:33-35`），所以默认配置不受影响——这也是它一直没被发现的原因。

**影响**：多线程负载均衡一旦启用，Acquirable 的整个互斥保证失效 → 实体状态数据竞争；断言开启时服务器刷 `AssertionError`。

**建议修复**：

```diff
     if (next != previous) {
         partitionEntry.thread = next;
         previous.entries.remove(partitionEntry);
         next.entries.add(partitionEntry);
+        for (Tickable element : partitionEntry.elements) {
+            if (element instanceof AcquirableSource<?> src)
+                ((AcquirableImpl<?>) src.acquirable()).assign(next);
+        }
     }
```

**建议的复现测试**（JUnit 5，勿运行）：

```java
@Test
void refreshThreadsMustReassignAcquirableOwner() {
    record World() {}
    final class E implements Tickable, AcquirableSource<E> {
        final Acquirable<E> a = Acquirable.unassigned(this);
        public void tick(long t) {}
        public Acquirable<? extends E> acquirable() { return a; }
    }
    var provider = new ThreadProvider<World>() {
        int n;
        public int findThread(World p) { return n++; }          // 每次都换线程
        public RefreshType refreshType() { return RefreshType.ALWAYS; }
    };
    var dispatcher = ThreadDispatcher.<World, E>dispatcher(provider, 2);
    var world = new World(); var e = new E();
    dispatcher.createPartition(world);
    dispatcher.updateElement(e, world);
    dispatcher.start();
    dispatcher.updateAndAwait(0);
    var before = e.a.assignedThread();
    dispatcher.refreshThreads();                                 // partition 迁到另一线程
    dispatcher.updateAndAwait(0);
    assertNotSame(before, e.a.assignedThread(), "assignedThread 未随 partition 迁移更新");
    dispatcher.shutdown();
}
```

**判定**：【读代码确认】　**关联**：`4459241c1d`（#2857）引入 `assertElement`，使该缺陷从"静默"变成"断言必炸"

---

### P1-3　`TaskSchedule.park()` / `Task#unpark()` 的 lost-wakeup

**严重度**：P1　**位置**：`src/main/java/net/minestom/server/timer/SchedulerImpl.java:124`、`src/main/java/net/minestom/server/timer/TaskImpl.java:26`、`:44-46`、`SchedulerImpl.java:83-86`

**问题描述**

```java
// SchedulerImpl.java:124
case TaskScheduleImpl.Park _ -> task.parked = true;      // 普通 volatile 写
// TaskImpl.java:44-46
boolean tryUnpark() { return PARKED.compareAndSet(this, true, false); }
// SchedulerImpl.java:83-86
void unparkTask(TaskImpl task) { if (task.tryUnpark()) this.tasksToExecute.relaxedOffer(task); }
```

交错：
- T1（执行任务的线程）：`handleTask` → `task.task().get()` 返回 `TaskSchedule.park()`，**尚未**执行 `:124` 的 `task.parked = true`。
- T2（业务线程，例如数据加载完成）：`task.unpark()` → `tryUnpark()` 的 `CAS(true → false)` 因为 `parked` 还是 `false` 而失败 → 什么都不做。
- T1：执行 `task.parked = true`。

任务从此永久 park，唤醒信号丢失，且 `isParked()` 返回 true 让人以为"只是还没 unpark"。

**触发场景**：`TaskSchedule.park()` 的标准用法就是"任务自己 park，另一个线程在数据就绪时 unpark"——窗口正是任务返回 `park()` 与调度器写标志之间的那几条指令。

**影响**：任务永久卡死，无异常无日志。

**建议修复**：把 park/unpark 变成三态 CAS（`RUNNING → PARK_REQUESTED → PARKED`），或让 `handleTask` 用 CAS 落 park：`if (!PARKED.compareAndSet(task, false, true)) tasksToExecute.relaxedOffer(task);`——即"落 park 失败说明期间已有人 unpark，直接重新入队"。

**建议的复现测试**（jcstress，勿运行）：

```java
@JCStressTest
@Outcome(id = "1", expect = ACCEPTABLE, desc = "unpark 生效")
@Outcome(id = "0", expect = FORBIDDEN,  desc = "unpark 丢失，任务永久 park")
@State
public class TaskUnparkLostWakeupTest {
    private final Scheduler scheduler = Scheduler.newScheduler();
    private final AtomicInteger runs = new AtomicInteger();
    private final Task task = scheduler.submitTask(new Supplier<>() {
        boolean first = true;
        public TaskSchedule get() {
            if (first) { first = false; return TaskSchedule.park(); }
            runs.incrementAndGet(); return TaskSchedule.stop();
        }
    }, ExecutionType.TICK_START);
    @Actor public void actor1() { task.unpark(); }
    @Arbiter public void arbiter(I_Result r) { scheduler.process(); r.r1 = runs.get(); }
}
```
（注意：`submitTask` 在调用线程同步执行第一次 `get()`，实测需要把 park 的返回挪到第二次以制造窗口。）

**判定**：【读代码确认】　**关联**：无

---

### P1-4　`unparkTask` 忽略 `executionType`，TICK_END 任务被投进 TICK_START 队列

**严重度**：P1　**位置**：`src/main/java/net/minestom/server/timer/SchedulerImpl.java:83-86`

**问题描述**

```java
void unparkTask(TaskImpl task) {
    if (task.tryUnpark())
        this.tasksToExecute.relaxedOffer(task);   // 永远投 TICK_START 队列
}
```

对比同文件里另外两处都正确分流：`safeExecute`（`:88-95`）按 `task.executionType()` 分 `tasksToExecute` / `tickEndTasksToExecute`；`Immediate` 分支（`:126-130`）也分流。只有 `unparkTask` 漏了。

后果：一个 `ExecutionType.TICK_END` 的任务一旦 park 过再被 unpark，就会被 `processTick()`（tick 开头）执行，而不是 `processTickEnd()`（tick 末尾）。这直接违反了 `ExecutionType` 的语义（`ExecutionType.java:8-11`："Schedule tasks to execute at the end of the tick"）。

**触发场景**：`scheduler.buildTask(r).executionType(TICK_END).delay(TaskSchedule.park()).schedule()`，随后 `task.unpark()`。

**影响**：本该在 tick 末（实体位置已同步、数据包已准备好之后）跑的逻辑跑在了 tick 首，读到的是上一 tick 的状态。表现为间歇性的一 tick 偏差，很难定位。

**建议修复**：

```diff
 void unparkTask(TaskImpl task) {
-    if (task.tryUnpark())
-        this.tasksToExecute.relaxedOffer(task);
+    if (!task.tryUnpark()) return;
+    switch (task.executionType()) {
+        case TICK_START -> tasksToExecute.relaxedOffer(task);
+        case TICK_END -> tickEndTasksToExecute.relaxedOffer(task);
+    }
 }
```

**建议的复现测试**（JUnit 5，勿运行）：

```java
@Test
void unparkedTickEndTaskMustRunAtTickEnd() {
    Scheduler scheduler = Scheduler.newScheduler();
    AtomicBoolean ran = new AtomicBoolean();
    Task task = scheduler.buildTask(() -> ran.set(true))
            .executionType(ExecutionType.TICK_END)
            .delay(TaskSchedule.park())
            .schedule();
    task.unpark();
    scheduler.processTick();
    assertFalse(ran.get(), "TICK_END 任务不应在 processTick() 中执行");
    scheduler.processTickEnd();
    assertTrue(ran.get(), "TICK_END 任务应在 processTickEnd() 中执行");
}
```
（现状下第一个断言会失败。）

**判定**：【读代码确认】　**关联**：无。现有测试 `src/test/java/net/minestom/server/timer/TestScheduler.java:81-96` 覆盖了 park/unpark，但只测 TICK_START，正好漏掉这条。

---

### P1-5　`PacketViewableUtils.storageMap`：又一处被当作只读快照的 `WeakHashMap`

**严重度**：P1　**位置**：`src/main/java/net/minestom/server/utils/PacketViewableUtils.java:29`、`:47-63`、`:65-70`

**问题描述**

```java
private static volatile Map<Viewable, ViewableStorage> storageMap = new WeakHashMap<>();   // :29

private static ViewableStorage retrieveStorage(Viewable viewable) {
    Map<Viewable, ViewableStorage> map = storageMap;
    ViewableStorage storage = map.get(viewable);        // :49 ← 无锁，任意线程
    if (storage == null) { synchronized (...) { ... map = new WeakHashMap<>(map); ... } }   // copy-on-write
    return storage;
}
public static void flush() {
    Map<Viewable, ViewableStorage> map = storageMap;
    map.entrySet().parallelStream().forEach(...);       // :68 ← ForkJoin 多线程遍历同一个 WeakHashMap
}
```

写路径用了标准的 copy-on-write + volatile 发布，说明作者假定"发布后的 map 是只读的"。但 `WeakHashMap.get()`（`:49`）与 `WeakHashMapSpliterator.getFence()`→`size()`（`:68` 的 parallelStream 内部，每个子 spliterator 都会调）都会触发 `expungeStaleEntries()`，对 `table` 与 `Entry.next` 做无同步的结构性修改。

交错：`Ms-Tick-0` 在 `:49` 做 `get()` 触发 expunge，同一时刻主 tick 线程在 `:68` 的 parallelStream 里由多个 ForkJoin worker 各自触发 expunge → 多写者改同一条桶链表。

**触发场景**：默认配置（`ServerFlag.VIEWABLE_PACKET` 默认 `true`，`ServerFlag.java:51`）。chunk 卸载后 `Viewable` key 被 GC → 持续产生 stale entry。

**影响**：`ViewableStorage` 条目丢失（该 chunk 的批量数据包这一 tick 全部不发 → 玩家看不到实体动作）；`size` 漂移；极端交错下 `get()` 在桶内死循环。

**建议修复**：改用 `ConcurrentHashMap` + 显式在 chunk 卸载时移除条目，或 `Collections.synchronizedMap(new WeakHashMap<>())` 并把 `flush()` 的遍历也放进同一把锁（会牺牲并行度）。最小改动是把 `storageMap` 换成 `ConcurrentHashMap<Viewable, ViewableStorage>` 并在 `Instance` 卸载 chunk 时清理。

**判定**：【读代码确认】　**关联**：与 P0-3 同一类根因

---

### P1-6　#3353 让 `drain()` 语义在 `unsafe-collections` 开关两侧分叉

**严重度**：P1　**位置**：`src/main/java/net/minestom/server/utils/collection/ConcurrentMessageQueues.java:20-22`、`src/main/java/net/minestom/server/timer/SchedulerImpl.java:25-26`、`:64-72`、`:126-130`

**问题描述**

`9f5f3e254b`（#3353，2026-08-29）把两个队列从

```java
ServerFlag.UNSAFE_COLLECTIONS ? new MpscUnboundedArrayQueue<>(64) : new MpscUnboundedAtomicArrayQueue<>(64)
```
换成 `ConcurrentMessageQueues.mpscUnboundedArrayQueue(64)`，后者是
```java
ServerFlag.UNSAFE_COLLECTIONS ? new MpscUnboundedXaddArrayQueue<>(chunkSize)
                              : new MpscUnboundedVarHandleArrayQueue<>(chunkSize);
```

对照 JCTools 4.0.7 源码（已下载 `jctools-core-jdk11:4.0.7-sources.jar` 逐行核对）：

| 类 | `drain(Consumer)` 实现 | 每次 drain 上限 |
|---|---|---|
| `MpscUnboundedArrayQueue`（改动前，unsafe 路径） | `drain(c, 4096)` | 4096 |
| `MpscUnboundedAtomicArrayQueue`（改动前，安全路径） | `drain(c, 4096)` | 4096 |
| `MpscUnboundedVarHandleArrayQueue`（改动后，安全路径） | `drain(c, 4096)` | 4096 |
| `MpscUnboundedXaddArrayQueue`（改动后，**unsafe 路径**） | `MessagePassingQueueUtil.drain(this, c)` = `while ((e = relaxedPoll()) != null) c.accept(e);` | **无上限** |

而 `SchedulerImpl.handleTask` 对 `Immediate` 的处理是**把任务重新塞回正在被 drain 的同一个队列**（`:126-130`）。于是在 `-Dminestom.unsafe-collections=true` 下：

```java
scheduler.scheduleTask(runnable, TaskSchedule.immediate(), TaskSchedule.immediate());
// -> handleTask 每次 relaxedOffer 回 tasksToExecute -> relaxedPoll 永远不返回 null -> drain 死循环
```
tick 线程当场卡死。改动前同样的代码是"每次 process 跑 4096 遍"（很吵但服务器活着）。

同一分叉也影响 `ThreadDispatcherImpl.updates`（`ThreadDispatcherImpl.java:31`/`:51`）：安全路径下每 tick 最多消费 4096 条 partition/element 更新，超出部分推迟到下一 tick；unsafe 路径下无上限。行为随 flag 变化，不是好事。

**触发场景**：`-Dminestom.unsafe-collections=true`（该 flag 存在于 `ServerFlag.java:76`，注释写着 "Likely to be removed in the future"）+ 任何以 `TaskSchedule.immediate()` 为 repeat 的任务。

**影响**：unsafe 路径下服务器硬卡死；两条路径行为不一致，使 flag 无法用作纯性能开关。

**建议修复**：在 `ConcurrentMessageQueues` 层面统一 drain 语义，或在 `SchedulerImpl.runTasks` 里显式限界并把本轮新入队的 immediate 任务留到下一次 `process()`：

```diff
 private void runTasks(MessagePassingQueue<TaskImpl> targetQueue) {
-    if (!targetQueue.isEmpty()) {
-        targetQueue.drain(task -> { if (!task.isAlive()) return; handleTask(task); });
-    }
+    // 只消费进入本方法时已在队列中的任务，避免 immediate 任务把 drain 变成死循环
+    int budget = targetQueue.size();
+    for (int i = 0; i < budget; i++) {
+        TaskImpl task = targetQueue.relaxedPoll();
+        if (task == null) break;
+        if (task.isAlive()) handleTask(task);
+    }
 }
```

**建议的复现测试**（JUnit 5，勿运行；需以 `-Dminestom.unsafe-collections=true` 跑）：

```java
@Test
@Timeout(5)
void immediateRepeatMustNotSpinForever() {
    Scheduler scheduler = Scheduler.newScheduler();
    AtomicInteger n = new AtomicInteger();
    Task task = scheduler.scheduleTask(n::incrementAndGet, TaskSchedule.immediate(), TaskSchedule.immediate());
    scheduler.process();           // unsafe 路径下这里永不返回
    task.cancel();
    assertTrue(n.get() > 0);
}
```

**判定**：【读代码确认】（JCTools 两个 `drain` 实现已逐行核对）/【需要运行验证】（死循环需实测确认）　**关联**：#3353 / `9f5f3e254b`、`a00c601608`（#3345）

---

### P2-1　`refreshThreads` 的超时参数把毫秒当纳秒用

**严重度**：P2　**位置**：`src/main/java/net/minestom/server/ServerProcessImpl.java:316-319`

```java
final long tickDuration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nanoStart);   // 毫秒
final long remainingTickDuration = Tick.SERVER_TICKS.getDuration().toNanos() - tickDuration; // 纳秒 - 毫秒
dispatcher().refreshThreads(remainingTickDuration);
```

`Tick.SERVER_TICKS.getDuration().toNanos()` = 50_000_000（`Tick.java:17` 由 `MinecraftServer.TICK_MS` 构造），`tickDuration` 是毫秒级的两位数。相减结果恒等于约 49_999_9xx ns —— **无论这一 tick 已经用掉多少时间，`refreshThreads` 拿到的预算永远是接近满 tick 的 50ms**。注释 `// the nanoTimeout for refreshThreads is the remaining tick duration` 与实现不符。

`git blame`：`1b109c6a2f`（#2651，2025-06-18，"Use System.nanoTime() instead of System.currentTimeMillis()"）迁移单位时漏改。

**影响**：默认 `RefreshType.NEVER` 下 `refreshThreads` 什么都不做（`ThreadDispatcherImpl.java:76-78`），所以默认无感；一旦启用 `ALWAYS`，重平衡不再受"tick 剩余时间"约束，服务器卡顿时会继续吃满 50ms 做迁移，放大卡顿。

**建议修复**：`final long tickDuration = System.nanoTime() - nanoStart;`（直接用纳秒），并考虑 `Math.max(0, ...)` 防负。

**判定**：【读代码确认】

---

### P2-2　`EventNodeImpl.unregister` 用 `return` 而非 `continue`，漏卸载后续事件类型

**严重度**：P2　**位置**：`src/main/java/net/minestom/server/event/EventNodeImpl.java:217-226`

```java
for (var eventType : binding.eventTypes()) {
    ListenerEntry<T> entry = listenerMap.get(eventType);
    if (entry == null) return;                       // :221 ← 应为 continue
    final boolean removed = entry.bindingConsumers.remove(binding.consumer(eventType));
    if (removed) invalidateEvent((Class<? extends T>) eventType);
}
```

一个 `EventBinding` 可以绑定多个事件类型（`EventBinding.FilteredBuilder.map(...)`，`EventBinding.java:35-39`）。若其中任意一个类型在 `listenerMap` 里没有条目（例如它从未被 `register` 成功过，或已被清理），整个 `unregister` 就提前返回，**剩余类型的 binding consumer 全部残留**。

注意 `eventTypes()` 来自 `Map.copyOf(mapped).keySet()`（`EventBinding.java:42-43`），迭代顺序不确定，因此这是个不稳定复现的泄漏。

**影响**：`unregister` 后事件仍在触发已注销的 binding；binding 捕获的对象无法回收。

**建议修复**：`if (entry == null) continue;`

**判定**：【读代码确认】

---

### P2-3　`unmap` 后再向同一 mapped 节点加监听器不会生效

**严重度**：P2　**位置**：`src/main/java/net/minestom/server/event/EventNodeLazyImpl.java:67-78`、`src/main/java/net/minestom/server/event/EventNodeImpl.java:189-200`

```java
// EventNodeLazyImpl.ensureMap()
if (MAPPED.compareAndSet(this, false, true)) {          // :69 一次性开关
    synchronized (GLOBAL_CHILD_LOCK) { ... holder.registeredMappedNode = registered; ... }
}
// EventNodeImpl.unmap(value)
registered.remove(value); this.registeredMappedNode = registered;   // :193-194 只清 registeredMappedNode
```

`unmap()` 把节点从 `registeredMappedNode` 摘掉，但**没有**把 `EventNodeLazyImpl.mapped` 复位，也没有从 `mappedNodeCache`（`EventNodeImpl.java:40`）里移除。于是：
1. `holder.map(value, filter)` 会从 `mappedNodeCache` 命中并返回**同一个** lazy 节点（`EventNodeImpl.java:180-183`）；
2. 对它 `addListener(...)` → `ensureMap()` 的 CAS 失败（`mapped` 已是 `true`）→ 不再重新注册到 `registeredMappedNode`；
3. 该节点永远收不到事件。

**触发场景**：`node.unmap(entity)` 之后又给 `entity.eventNode()` 加监听器。Minestom 主干里没有任何地方调用 `unmap`（全仓 grep 只有定义），所以这是纯用户侧路径。

**影响**：静默失效，无异常。

**建议修复**：`unmap()` 里同时 `mappedNodeCache.remove(value)` 并把对应 `EventNodeLazyImpl` 的 `mapped` 复位为 `false`（需要给 `EventNodeLazyImpl` 加一个包级 `resetMapped()`）。

**判定**：【读代码确认】

---

### P2-4　`EventNodeImpl.map` 遇到已被回收的 `WeakReference` 时不会替换缓存条目

**严重度**：P2　**位置**：`src/main/java/net/minestom/server/event/EventNodeImpl.java:174-187`

```java
WeakReference<EventNodeLazyImpl<T>> previousRef = this.mappedNodeCache.putIfAbsent(value, new WeakReference<>(node));
EventNodeImpl<T> previous;
if (previousRef != null && (previous = previousRef.get()) != null) return (EventNode<E>) previous;
node.parent = this;
```

若 `previousRef != null` 但 `previousRef.get() == null`（之前创建的 lazy 节点已被 GC，而 `value` 本身还活着），`putIfAbsent` **不会**覆盖那个失效条目。方法返回新 `node`，但 `mappedNodeCache` 里 `value` 仍指向已清空的引用。下次再 `map(value)` 又会造出第三个节点。此时若两个节点都调过 `ensureMap()`，`registeredMappedNode.putIfAbsent`（`EventNodeLazyImpl.java:72`）只会保留先到的那个，另一个的监听器永远不触发。

**触发场景**：对**非 Entity/Instance** 的对象做 `map()`（Entity/Instance 会在字段里强引用自己的节点，`Entity.java:210`/`:260`，不会被回收），且用户丢弃返回值后再次 `map`。

**建议修复**：命中失效引用时用 `put` 覆盖：

```diff
-        WeakReference<EventNodeLazyImpl<T>> previousRef = this.mappedNodeCache.putIfAbsent(value, ...);
-        EventNodeImpl<T> previous;
-        if (previousRef != null && (previous = previousRef.get()) != null) return (EventNode<E>) previous;
+        WeakReference<EventNodeLazyImpl<T>> previousRef = this.mappedNodeCache.get(value);
+        EventNodeImpl<T> previous;
+        if (previousRef != null && (previous = previousRef.get()) != null) return (EventNode<E>) previous;
+        this.mappedNodeCache.put(value, new WeakReference<>((EventNodeLazyImpl<T>) node));
```

**判定**：【读代码确认】

---

### P2-5　`AcquirableCollection.acquireSync` 对未分配线程的元素 NPE

**严重度**：P2　**位置**：`src/main/java/net/minestom/server/thread/AcquirableCollection.java:117-135`、`:23-38`、`src/main/java/net/minestom/server/thread/AcquirableImpl.java:121-124`

```java
final TickThread elementThread = element.assignedThread();       // :124 可能为 null
if (Thread.currentThread() == elementThread) { consumer.accept(value); }
else { threadCacheMap.computeIfAbsent(elementThread, _ -> new ArrayList<>()).add(value); }  // :130 null 作 key
...
ReentrantLock lock = AcquirableImpl.enter(tickThread);           // :28 tickThread == null
// AcquirableImpl.isOwnedImpl:
if (Thread.currentThread() == elementThread) return true;
return elementThread.lock().isHeldByCurrentThread();              // :123 NPE
```

`assignedThread()` 在元素被 dispatcher 处理前是 `null`（`AcquirableImpl.java:23`，`ThreadDispatcherTest.java:76` 也断言了这一点）。`HashMap` 允许 null key，所以问题一路传到 `enter(null)` 才炸。`AcquirableImpl` 自身的 `lock()/sync()/isOwned()` 都对 null 做了处理（`:32`/`:50`/`:57`），只有 `AcquirableCollection` 漏了。

**触发场景**：把刚创建、还没经历一次 `updateAndAwait` 的实体放进 `AcquirableCollection` 并 `acquireSync`。

**影响**：`NullPointerException`。该类目前在主干无调用点（纯对外 API），因此影响面小。

**建议修复**：`retrieveOptionalThreadMap` 里对 `elementThread == null` 的元素单独收集，在 `acquireSync` 中按 `AcquirableImpl` 的 init-thread 规则处理（或直接跳过并抛出更清晰的异常）。

**判定**：【读代码确认】

---

### P2-6　`stop()` 的关闭顺序与不 join

**严重度**：P2　**位置**：`src/main/java/net/minestom/server/ServerProcessImpl.java:240-251`、`src/main/java/net/minestom/server/MinecraftServer.java:401-406`

```java
public void stop() {
    if (!stopped.compareAndSet(false, true)) return;   // 幂等，OK
    scheduler.shutdown();     // ① 立刻跑所有 shutdown task
    connection.shutdown();
    server.stop();
    dispatcher.shutdown();    // ④
}
```

三个问题：
1. **顺序**：`scheduler.shutdown()`（`SchedulerManager.java:32-34`，直接 `drain(Runnable::run)`）在 ticker 还在跑的时候就执行用户的关服任务（存盘、踢人）。此时 `TickSchedulerThread` 可能正处在一次 tick 中间，用户的存盘逻辑与实体 tick 并发。应先让 ticker 停下来（`stopped` 已置位，但没有等待）。
2. **不 join**：`stop()` 返回时 `TickSchedulerThread` 与所有 `TickThread` 可能都还在运行。`MinecraftServer.start()`（`:402-405`）创建 `TickSchedulerThread` 之后连引用都没保存，无从 join。`ThreadDispatcher.shutdown()` 只设 `stop=true` + unpark（`TickThread.java:132-135`），不等待线程退出。
3. **`SchedulerImpl.SCHEDULER`（`SchedulerImpl.java:19-23`）是 static 的守护单线程池**，`stop()` 从不关它；停服后仍在跑的 `DurationSchedule` 回调会继续 `safeExecute` 往一个再也不会被 drain 的队列里塞任务（`:88-95`），条目直到 `SchedulerImpl` 整体被 GC 才释放。

**影响**：关服过程中的竞态与数据丢失风险；测试里反复 `MinecraftServer.updateProcess()` 时线程与队列残留。

**建议修复**：`stop()` 顺序调整为 —— 置 `stopped` → 等 ticker 退出（保存 `TickSchedulerThread` 引用并 `join(timeout)`）→ `dispatcher.shutdown()` + join 各 `TickThread` → `connection.shutdown()` / `server.stop()` → 最后 `scheduler.shutdown()` 跑用户任务。

**判定**：【读代码确认】　**关联**：#2907（同一函数）、#1636

---

### P2-7　`ThreadDispatcherImpl` 的两个 `WeakHashMap` 名不副实

**严重度**：P2　**位置**：`src/main/java/net/minestom/server/thread/ThreadDispatcherImpl.java:24`、`:26`、`:28`、`:144`、`:181`

```java
private final Map<P, Partition> partitions = new WeakHashMap<>();          // :24
private final Map<Tickable, Partition> elements = new WeakHashMap<>();     // :26
private final ArrayDeque<P> partitionUpdateQueue = new ArrayDeque<>();     // :28
```

- `partitions` 的 key（chunk）被 `partitionUpdateQueue`（`:144` `partitionUpdateQueue.add(partition)`）**强引用**，只有 `processUnloadedPartition`（`:156`）才移除。
- `elements` 的 key（实体）被 `Partition.elements`（`ArrayList`，`:193`，在 `:181` 加入）**强引用**，只有 `processRemovedElement`（`:165`）才移除。

也就是说两个 map 的弱引用语义从来没有生效过——回收完全依赖显式的 `deletePartition` / `removeElement` 信号。这不是内存泄漏（`Instance`/`Entity` 都有对应的显式移除路径），但它给了"忘了 unload 也没关系，GC 会兜底"的错误安全感，而实际上一旦某条移除路径漏发信号，chunk 与其中全部实体会被永久强引用。

**建议修复**：要么换成普通 `HashMap`/`IdentityHashMap` 并在注释里写明"生命周期完全由 signalUpdate 管理"，要么把 `partitionUpdateQueue` 和 `Partition.elements` 也改成弱引用容器。倾向前者——把隐含假设写出来比伪装成弱引用更诚实。

**判定**：【读代码确认】　**关联**：#2649（EntityTracker 泄漏，属实体追踪层，本轮未判定）

---

### P2-8　`Entity.updateSnapshot` 无空值保护、无 acquire，快照可撕裂

**严重度**：P2　**位置**：`src/main/java/net/minestom/server/entity/Entity.java:1793-1802`、`src/main/java/net/minestom/server/snapshot/SnapshotUpdaterImpl.java:47-58`、`src/main/java/net/minestom/server/ServerProcessImpl.java:258-269`

```java
public EntitySnapshot updateSnapshot(SnapshotUpdater updater) {
    final Chunk chunk = currentChunk;               // :1794  非 volatile，可能为 null
    ...
    return new SnapshotImpl.Entity(..., updater.reference(instance),
            chunk.getChunkX(), chunk.getChunkZ(), ...);   // :1799 无 null 检查
}
```

`ServerSnapshot.update()`（`ServerSnapshot.java:23-25`）可以从任意线程调用，内部 `ServerProcessImpl.updateSnapshot`（`:262-267`）遍历 `instance.getEntities()` 并对每个实体建引用，随后 `SnapshotUpdaterImpl.update()`（`:52`）在 `parallelStream` 上并发调用各实体的 `updateSnapshot`。整个过程**没有任何 `Acquirable` 获取**：
- `currentChunk`（`Entity.java:158`）与 `instance` 都是普通字段，实体在别的线程上正被 tick / `remove()`（`Entity.java:1621-1652`，`:1650` 会把 `instance` 置 null）。
- 拿到 `instance == null` 时，`updater.reference(null)` 会入队一个 null `Snapshotable`，`update()` 里 `snap.updateSnapshot(this)` 直接 NPE（在 `parallelStream` 内，异常会被包装抛出）。
- `chunk == null`（实体尚未 spawn 或刚被移出）时 `:1799` 直接 NPE。
- 即使不 NPE，`position` / `velocity` / `viewEngine.viewableOption.bitSet`（`:1795`）来自不同时刻，快照是撕裂的。

`ServerSnapshot.update()` 标了 `@ApiStatus.Experimental`，所以严重度按 P2 记。

**建议修复**：`updateSnapshot` 里做 null 短路（返回一个"未 spawn"快照或跳过该实体），并在文档里明确 `ServerSnapshot.update()` 只应在 tick 边界（例如 `scheduleEndOfTick`）调用；更彻底的做法是在 `ServerProcessImpl.updateSnapshot` 里对每个实体走 `acquirable().sync(...)`。

**判定**：【读代码确认】（null 解引用）/【疑似】（撕裂的具体可观测后果需实测）

---

### P3 级（代码异味 / 健壮性，不展开）

- **P3-1**　`AcquirableImpl.enter` 的自旋无退避：`AcquirableImpl.java:130-134` 的 `while (!GLOBAL_LOCK.tryLock()) { tickThread.lock().unlock(); tickThread.lock().lock(); }` 在非公平 `ReentrantLock` 上是纯忙等，且重入 `lock()` 会立刻 barging 抢回自己的锁，等待方可能被饿死。建议加 `Thread.onSpinWait()` 或改用带超时的 `tryLock`。
- **P3-2**　启动顺序：`MinecraftServer.start()`（`MinecraftServer.java:402-405`）先 `serverProcess.start(address)`（内部 `server.start()` 开始接受连接，`ServerProcessImpl.java:232`）再 `dispatcher().start()` 与 `new TickSchedulerThread(...).start()`。存在一个"已在监听但还没有任何 tick 线程"的窗口。
- **P3-3**　`TickMonitor` 的 `acquisitionTime` 语义可疑：`Acquirable.resetAcquiringTime()`（`Acquirable.java:36-38`）对全局 `AtomicLong` 做 `getAndSet(0)`，而 `WAIT_COUNTER_NANO.addAndGet` 由所有获取线程累加（`AcquirableImpl.java:140`）。主线程在 `ServerProcessImpl.java:294` 读取时 tick 线程可能仍在累加，读到的是跨线程等待时间之和且带截断，不能与 `tickTimeMs` 直接比较。
- **P3-4**　`ConcurrentMessageQueues.mpmcSpecialUnboundedArrayQueue`（`ConcurrentMessageQueues.java:25-27`）方法名带 "Unbounded"，但默认（安全）路径返回的 `MpmcVarHandleArrayQueue` 是**定长环形队列**，参数从 unsafe 路径的 "chunkSize" 变成了 "capacity"。`ObjectPool`（`ObjectPool.java:35`，`QUEUE_SIZE = 32_768`）在两条路径下的行为因此不同：安全路径满了以后 `add()` 静默丢弃归还的对象。文件里的注释解释了这个折中，但方法名应改。
- **P3-5**　死代码（按约定只提示、不改）：`ObjectPool.register(Object, AtomicReference)`（`ObjectPool.java:68-70`）、`register(Object, Collection)`（`:76-78`）、`hold()`（`:80-82`）、`use()`（`:84-91`）在 `src/main/java` 内无任何调用点，只有 `getAndRegister` 被 `PacketViewableUtils.java:83` 使用。其中 `BufferRefCleaner.run()`（`:93-98`）对 `objectRef.get()` 无 null 检查，若被启用会在 Cleaner 线程上 NPE。

---

## ③ 上游 issue 复核表

| Issue | 标题 | 判定 | 依据 |
|---|---|---|---|
| **#2907** | Server cannot be stopped | **仍存在** | `ThreadDispatcherImpl.updateAndAwait`(:49) 与 `shutdown`(:123) 共用对象 monitor，`updateAndAwait` 持锁 `latch.await()`；`ServerProcessImpl.stop()`(:249) 调 `dispatcher.shutdown()`。`git blame` 确认 `synchronized` 出自 `4459241c1d`(#2857)，与 issue 描述的版本界完全一致。见 P0-1。 |
| **#1636** | Bug in Stopping Server | **无法判断（信息不足）** | issue 只有两条讨论、无复现代码、无栈；讨论内容指向"玩家断开被延后一 tick"，与本层的 `stop()` 路径不是同一件事。若指的是"stop 后线程仍在跑"，则对应本报告 P2-6（`stop()` 不 join 任何线程），该缺陷仍存在。 |
| #2900 | Unregistering an instance while a player is spawning | 出范围（未判定） | 涉及 `InstanceManager` 与玩家 spawn 流程；与本层相关的只是 `dispatcher.deletePartition` 的信号时序，但主因在 instance 层，留给对应审计轮次。 |
| #2649 | EntityTracker leaking memory | 出范围（未判定） | 泄漏点在 `EntityTracker`。本层的相关观察是 P2-7：`ThreadDispatcherImpl` 的两个 `WeakHashMap` 实际是强 map，若某条移除信号漏发会强引用整个 chunk 与其实体——可作为该 issue 排查时的补充线索。 |
| #1526 | Entity#remove causes CME | 出范围（未判定） | 栈顶在 `EntityView.forManuals`（`HashMap` 迭代中修改），属实体视图层；`TickThread.tick` 只是调用者。 |

范围内的 open bug issue 只有 #2907 与 #1636 两条；本报告的其余 P0/P1 均**未见对应上游 issue**。open PR 清单（`upstream-open-prs.md`）中未见针对 `thread/`、`timer/`、`event/` 的修复 PR。

---

## ④ 未覆盖的区域与原因

1. **未运行任何验证**：按约束禁用 Gradle，所有结论来自静态阅读 + JCTools 上游源码比对。标注为【需要运行验证】的两条（P1-1 的具体损坏形态、P1-6 的死循环）需要真实执行才能落实。
2. **`event/` 下 134 个文件中的 ~120 个事件定义类未逐个阅读**：它们是纯数据 record/class，只抽查了 `trait/`（`CancellableEvent` / `RecursiveEvent` / `AsyncEvent`）在 `EventNodeImpl` 中的处理路径。`RecursiveEvent` 的超类失效逻辑（`invalidateRecursiveSuperclasses`，`EventNodeImpl.java:318-326`）与 `forTargetEvents`（`:336-345`）读下来语义自洽，未发现问题。
3. **网络层（`network/`）只在与生命周期相交处扫了一眼**：`Server.stop()`、`ConnectionManager.shutdown()`、`PlayerSocketConnection` 的队列类型。玩家包队列 `ConcurrentMessageQueues.mpscArrayQueue(PLAYER_PACKET_QUEUE_SIZE)`（`Player.java`）是**有界**队列且 `offer` 返回值处理需要单独核查，属网络层审计范围。
4. **`SnapshotUpdaterImpl` 的 fork/join 内存可见性只做了粗判**：`queue` 与 `readOnlyReferenceMap` 都是非 volatile 字段，被 `parallelStream` 的 worker 读写；由于所有 worker 访问都夹在 fork 与 join 之间，JMM 边被 ForkJoinTask 覆盖，判为可接受，但这是全文中我把握最低的一处。
5. **`jcstress-tests/` 现有覆盖**：共 11 个测试。`tag/` 占 6 个（Tag 读写/rehash/path），`thread/` 2 个（`AcquirableSyncTest`、`ThreadDispatcherSyncTest`），`utils/` 2 个（`ObjectPool`、`ObjectArray`）。**完全没有并发测试的高风险结构**：`EventNodeImpl$Handle` 的缓存失效（P0-2）、`mappedConsumer` 的 `handlers`（P0-3）、`SchedulerImpl` 的全部队列与 park/unpark 状态机（P1-1/P1-3/P1-4）、`PacketViewableUtils.storageMap`（P1-5）、`ThreadDispatcherImpl` 的 partition 迁移（P1-2）。现有的 `ThreadDispatcherSyncTest` 只验证多线程并发调 `updateAndAwait` 不丢 tick，没有覆盖 shutdown 与 updateAndAwait 的交错（也就是 #2907）。

---

## ⑤ 参考链接

- 上游 issue #2907 — Server cannot be stopped：https://github.com/Minestom/Minestom/issues/2907
- 上游 issue #1636 — Bug in Stopping Server：https://github.com/Minestom/Minestom/issues/1636
- 上游 PR #3353 — refactor: Use ConcurrentMessageQueues in scheduler queues（本地 `9f5f3e254b`）：https://github.com/Minestom/Minestom/pull/3353
- 上游 PR #3345 — refactor: Use VarHandle from JDK11 JCTools（本地 `a00c601608`）：https://github.com/Minestom/Minestom/pull/3345
- 上游 PR #2857 — Acquirable strict（本地 `4459241c1d`，#2907 的根因）：https://github.com/Minestom/Minestom/pull/2857
- 上游 PR #2212 — Drop eventnode listener ref on invalidate（本地 `2b661fd48f`，P0-2 的根因）：https://github.com/Minestom/Minestom/pull/2212
- 上游 PR #1835 — Allow event node handle to be garbage collected（本地 `79ce9570ea`，P0-3 的根因）：https://github.com/Minestom/Minestom/pull/1835
- 上游 PR #2651 — Use System.nanoTime() instead of System.currentTimeMillis()（本地 `1b109c6a2f`，P2-1 的根因）：https://github.com/Minestom/Minestom/pull/2651
- JCTools 4.0.7 `MpscUnboundedArrayQueue.drain`（`drain(c, 4096)`）：https://github.com/JCTools/JCTools/blob/v4.0.7/jctools-core/src/main/java/org/jctools/queues/MpscUnboundedArrayQueue.java
- JCTools 4.0.7 `MpUnboundedXaddArrayQueue.drain`（无上限）：https://github.com/JCTools/JCTools/blob/v4.0.7/jctools-core/src/main/java/org/jctools/queues/MpUnboundedXaddArrayQueue.java
- JCTools 4.0.7 `MessagePassingQueueUtil.drain`：https://github.com/JCTools/JCTools/blob/v4.0.7/jctools-core/src/main/java/org/jctools/queues/MessagePassingQueueUtil.java
- `jctools-core-jdk11:4.0.7` sources（`MpscUnboundedVarHandleArrayQueue.drain` = `drain(c, 4096)`，本地核对）：https://repo1.maven.org/maven2/org/jctools/jctools-core-jdk11/4.0.7/
