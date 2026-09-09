# Minestom 只读代码审计 —— 物品 / 背包 / 命令 / 编解码 / 注册表 / 标签

- 仓库：`/Users/bug/Documents/Projects/Minecraft/Minestom`，HEAD `16842f1954`（2026-09-04，upstream master 的 fork）
- 方式：纯阅读 + `grep` / `git log` / `git blame`，未修改任何文件、未运行 Gradle
- 覆盖：`inventory`(23) / `item`(74) / `component`(6) / `command`(83) / `codec`(14) / `registry`(21) / `gamedata` / `tag`(14) / `recipe`(8) / `dialog`(9)，`advancements`·`scoreboard`·`map` 快速扫描
- 所有行号均已用 `sed -n 'N,Mp'` 复核，可直接按 `文件:行号` 打开验证

---

## ① TL;DR

这一层的整体质量是**「新 API 外壳 + 旧实现内核」**：`inventory/click/Click.java` 是一套干净的 sealed union，但它背后仍然接的是 2022 年代的 `InventoryClickProcessor` + `TransactionType`，两者的语义假设并不一致，缝隙里藏着这次审计最严重的几个问题。`item` / `component` 层设计得相当好（不可变性、`equals`/`hashCode`、`isSimilar` 我逐条验过，都是对的），但 NBT 与网络两条序列化路径存在几处**互不对称**的实现漏洞。`command` 层的图构建本身是正确的（子节点索引不会越界），问题集中在参数解析对非受检异常的失控和 `CommandManager` 的线程安全上。

最严重的三个：

1. **P0 — 递归网络类型没有深度上限，一个报文即可远程打爆调用栈**：`CustomPotionEffect.Settings.NETWORK_TYPE` 是 `NetworkBuffer.Recursive` + `self.optional()`，而 `NetworkBufferTypeImpl.RecursiveType` 完全没有深度计数。每层嵌套只要约 6 字节，2 MB 的包上限（`ServerFlag.MAX_PACKET_SIZE`）足够堆出数十万层 → 解码时 `StackOverflowError`。它是 `Error` 而非 `Exception`，网络层唯一的 `catch (Exception)` 接不住。
2. **P0 — 拖拽（quick craft）事务无槽位边界校验，异常中断导致物品复制**：`InventoryClickProcessor.dragging` 用 `slot - inventory.getSize()` 直接索引玩家背包，一旦拖拽缓存跨背包切换存活（P1-2 给出了完全由客户端驱动的触发链），循环会在写入了一部分槽位之后抛 `ArrayIndexOutOfBoundsException`，异常被 `PacketListenerManager` 吞掉，**已写入的物品保留、光标却从未扣减 → 物品复制**。上游 #2546 的堆栈就是这个形状。
3. **P0 — 共享 `Inventory` 的点击事务是无锁的读-改-写**：`AbstractInventory.setItemStack` 只对写加锁，`Inventory.leftClick` 等把「读槽位 → 计算 → 写槽位」拆成三步且全程不持锁。两个玩家同时点同一个箱子（`minestom.dispatcher-threads > 1`），或插件从异步线程 `addItemStack`，都能稳定复制物品。

另有一条同为 P0 的数据损坏：`ItemStackTemplate.java:12` 把已 resolve 的组件表当作 patch 发出去，`BUNDLE_CONTENTS` / `CONTAINER` 里嵌套物品的组件移除标记全部丢失，且 NBT 与网络两条路径结果不一致。

统计：**P0 4 条、P1 14 条、P2 10 条、P3 9 条**。

---

## ② 发现清单

### P0-1 拖拽事务不校验槽位边界，异常中断导致物品复制

**严重度**：P0（远程可触发的物品复制 + 异常刷屏）
**位置**：`src/main/java/net/minestom/server/inventory/click/InventoryClickProcessor.java:153-158`（左键拖拽）、`:188-193`（右键拖拽）

```java
for (int slot : slots) {
    final boolean isInWindow = slot < inventory.getSize();
    final var inv = isInWindow ? inventory : player.getInventory();
    final int s = isInWindow ? slot : slot - inventory.getSize();

    ItemStack slotItem = inv.getItemStack(s);          // ← 这里越界
    final int amount = slotItem.amount();
    ...
    inv.setItemStack(s, slotItem);                     // ← 前面的槽位已经写进去了
}
```

**为什么是 bug**：`s` 完全没有对 `inv.getSize()` 做校验。`AbstractInventory.getItemStack`（`AbstractInventory.java:258-260`）直接 `ITEM_UPDATER.getVolatile(itemStacks, slot)`，越界即 `ArrayIndexOutOfBoundsException`。异常从 `Inventory.dragging`（`Inventory.java:301`）冒泡到 `WindowListener.clickWindowListener`，最终被 `PacketListenerManager.processClientPacket`（`listener/manager/PacketListenerManager.java:238-243`）的 `catch (Exception e)` 吞掉并只记日志。关键在于：`Inventory.dragging:306` 的 `playerInventory.setCursorItem(clickResult)` 因此**永远不会执行**，而循环前半段已经通过 `inv.setItemStack` 把物品写进去了。

**触发场景**（全部由客户端报文驱动，需要服务端至少会打开两个不同大小的 GUI —— 这是所有 Minestom 服务端的常态）：

1. 服务端为玩家打开 `CHEST_6_ROW`（size 54）。客户端发 `QUICK_CRAFT` button=0 起手，再发 button=1 若干次，其中包含 window slot 0（→ Minestom slot 0）和 window slot 80（→ Minestom slot 89）。`ClickPreprocessor.leftDrag` 记下 `{0, 89}`。
2. 客户端发 `ClientCloseWindowPacket(windowId = 0)`。命中 P1-2：`Player.openInventory` 字段被置 null，但 `Inventory.removeViewer`（也就是 `ClickPreprocessor.clearCache()`，`Inventory.java:110`）**没有被调用**，拖拽缓存原样保留。
3. 服务端打开一个更小的 GUI，例如 `CHEST_3_ROW`（size 27）。
4. 客户端发 `QUICK_CRAFT` button=2（结束拖拽），windowId 用新 GUI 的 id。`Click.toWindow` / `fromWindow` 把槽位原样透传，`clickProcessor.dragging` 拿到 `{0, 89}`：slot 0 正常写入新 GUI；slot 89 → `s = 89 - 27 = 62`，`player.getInventory()` 长度只有 46 → AIOOBE。

**影响**：光标 64 个钻石、拖 2 格时，第一格已经写入 32 个，光标仍然是 64 → 净增 32。可重复执行，纯客户端驱动。每次还向日志写一条完整堆栈（日志洪水）。

**建议修复**：在写入前统一做边界检查，并把整个拖拽事务改成「先算完、再一次性提交」：

```java
final int s = isInWindow ? slot : slot - inventory.getSize();
if (s < 0 || s >= inv.getSize()) continue;   // 或者整体 return null 取消这次拖拽
```

同时在 `ClickPreprocessor` 收到结束包时按当前 `containerSize` 重新过滤一遍槽位列表，而不是信任缓存。

**复现测试**（不要运行）：

```java
package net.minestom.server.inventory.click.integration;

import net.kyori.adventure.text.Component;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.client.play.ClientCloseWindowPacket;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static net.minestom.server.inventory.click.ClickUtils.clickPacket;
import static net.minestom.server.network.packet.client.play.ClientClickWindowPacket.ClickType.QUICK_CRAFT;
import static org.junit.jupiter.api.Assertions.assertEquals;

@EnvTest
public class StaleDragDuplicationIntegrationTest {

    @Test
    public void staleDragSlotsDuplicateItems(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createConnection().connect(instance, new Pos(0, 42, 0));

        var big = new Inventory(InventoryType.CHEST_6_ROW, Component.empty());   // 54
        player.openInventory(big);

        // 1. 起手 + 记录两个槽位：window slot 0 -> minestom 0；window slot 80 -> minestom 89
        player.addPacketToQueue(clickPacket(QUICK_CRAFT, big.getWindowId(), 0, -999));
        player.addPacketToQueue(clickPacket(QUICK_CRAFT, big.getWindowId(), 1, 0));
        player.addPacketToQueue(clickPacket(QUICK_CRAFT, big.getWindowId(), 1, 80));
        player.interpretPacketQueue();

        // 2. 客户端谎称关闭了 window 0 -> 拖拽缓存不会被清（见 P1-2）
        player.addPacketToQueue(new ClientCloseWindowPacket(0));
        player.interpretPacketQueue();

        // 3. 换一个更小的 GUI
        var small = new Inventory(InventoryType.CHEST_3_ROW, Component.empty()); // 27
        player.openInventory(small);
        player.getInventory().setCursorItem(ItemStack.of(Material.DIAMOND, 64));

        // 4. 结束拖拽：89 - 27 = 62 >= PlayerInventory.INVENTORY_SIZE(46) -> AIOOBE
        player.addPacketToQueue(clickPacket(QUICK_CRAFT, small.getWindowId(), 2, -999));
        player.interpretPacketQueue();

        // 期望：要么整笔取消（光标 64、GUI 空），要么正常结算（光标 0）。
        // 现状：GUI 里已经有 32 个，光标仍然是 64 —— 净增 32。
        assertEquals(ItemStack.AIR, small.getItemStack(0),
                "部分写入 + 光标未扣减 = 复制");
        assertEquals(64, player.getInventory().getCursorItem().amount());
    }
}
```

**判定**：【读代码确认】（越界算式、异常吞没路径、光标未扣减三点均已逐行确认；触发链依赖 P1-2，端到端行为建议跑上面的测试确认）
**关联**：上游 #2546「Inventory drag itemstack error」堆栈完全吻合（`Index 51 out of bounds for length 46`，同一条 `Inventory.dragging → AbstractInventory.getItemStack` 调用链）

---

### P0-2 共享 Inventory 的点击事务是无锁读-改-写，可复制物品

**严重度**：P0（物品复制）
**位置**：`src/main/java/net/minestom/server/inventory/Inventory.java:148-168`（`leftClick`，`rightClick` / `shiftClick` / `changeHeld` / `doubleClick` 同构），配合 `src/main/java/net/minestom/server/inventory/AbstractInventory.java:132-143`

```java
public boolean leftClick(Player player, int slot) {
    final ItemStack cursor = playerInventory.getCursorItem();          // 读 1（无锁）
    final ItemStack clicked = isInWindow ? getItemStack(slot) : ...;   // 读 2（无锁）
    final InventoryClickResult clickResult = clickProcessor.leftClick(clicked, cursor);
    ...
    setItemStack(slot, clickResult.getClicked());                      // 写（内部才加锁）
    playerInventory.setCursorItem(clickResult.getCursor());
```

```java
// AbstractInventory.java:136-143
ItemStack previous;
synchronized (this) {
    previous = itemStacks[slot];
    if (itemStack.equals(previous)) return;
    UNSAFE_itemInsert(slot, itemStack, previous, sendPacket);
}
```

**为什么是 bug**：`synchronized` 的粒度是「单次写」，不是「一次点击事务」。`processItemStack`（`AbstractInventory.java:154`）是 `synchronized` 的，但点击路径完全绕过它。两个执行流并发进入 `leftClick` 会读到同一个 `clicked`，各自算出「光标 = 该物品」，然后都把槽位写成 AIR —— 一份物品变两份。

**触发场景**：
1. `ServerFlag.DISPATCHER_THREADS`（`minestom.dispatcher-threads`，`ServerFlag.java:20`）设为 >1 时，处于不同 chunk 分区的两个玩家会在不同 `TickThread` 上跑 `Player.update` → `interpretPacketQueue`（`Player.java:440, 2275-2278`），同时点击同一个共享 `Inventory`。这是 Minestom 明确支持的配置。
2. 默认单线程配置下也可触发：插件从调度器的异步任务里调 `inventory.addItemStack(...)` / `setItemStack(...)`，与 tick 线程上的点击事务交错。

另外 `UNSAFE_itemInsert`（`AbstractInventory.java:145-148`）对 `itemStacks[slot]` 是**普通数组写**，而 `getItemStack` 用 `getVolatile` 读，两者之间在 JMM 上并没有 happens-before 关系（release 边只在 monitorexit 上）。

**影响**：物品复制（经济崩坏）；轻则背包/箱子内容与客户端不一致。

**建议修复**：把整笔点击事务放进锁里。最小改动是让 `InventoryClickHandler.handleClick` 的实现在 `synchronized (this)`（对 `Inventory`）与玩家背包锁上串行；更彻底的做法是像上游后来的 click 重构那样，把点击算成一个纯函数产生的「变更集」，再在单个临界区里原子提交。同时把 `UNSAFE_itemInsert` 的数组写改成 `ITEM_UPDATER.setVolatile`。

**复现测试**（不要运行；需 `-Dminestom.dispatcher-threads=4`，属于压力型测试）：

```java
@Test
public void concurrentLeftClickMustNotDuplicate(Env env) throws Exception {
    var inv = new Inventory(InventoryType.CHEST_1_ROW, Component.empty());
    var instance = env.createFlatInstance();
    var p1 = env.createConnection().connect(instance, new Pos(0, 42, 0));
    var p2 = env.createConnection().connect(instance, new Pos(0, 42, 0));
    p1.openInventory(inv);
    p2.openInventory(inv);

    var barrier = new java.util.concurrent.CyclicBarrier(2);
    Runnable click = () -> { /* 各自 addPacketToQueue(clickPacket(PICKUP, inv.getWindowId(), 0, 0)) */
        try { barrier.await(); } catch (Exception e) { throw new RuntimeException(e); }
    };
    for (int round = 0; round < 500; round++) {
        inv.setItemStack(0, ItemStack.of(Material.DIAMOND, 64));
        p1.getInventory().setCursorItem(ItemStack.AIR);
        p2.getInventory().setCursorItem(ItemStack.AIR);
        var t1 = new Thread(() -> { click.run(); p1.interpretPacketQueue(); });
        var t2 = new Thread(() -> { click.run(); p2.interpretPacketQueue(); });
        t1.start(); t2.start(); t1.join(); t2.join();

        int total = inv.getItemStack(0).amount()
                + p1.getInventory().getCursorItem().amount()
                + p2.getInventory().getCursorItem().amount();
        assertEquals(64, total, "第 " + round + " 轮出现复制");
    }
}
```

**判定**：【读代码确认】（缺锁事实确认）／【需要运行验证】（复制的实际概率与 dispatcher 配置相关）
**关联**：无直接 open issue，但与 #1212「ConcurrentModificationException on TickThread」同一类根因

---

### P0-3 嵌套物品组件把 resolved map 当作 patch 上线，移除标记丢失且协议体积暴涨

**严重度**：P0（数据损坏 + 协议膨胀；NBT 与网络两条路径不对称）
**位置**：`src/main/java/net/minestom/server/item/ItemStackTemplate.java:12`

```java
NetworkBuffer.Type<ItemStack> NETWORK_TYPE = NetworkBufferTemplate.template(
        Material.NETWORK_TYPE, ItemStack::material,
        NetworkBuffer.VAR_INT, ItemStack::amount,
        DataComponent.PATCH_NETWORK_TYPE, (i) -> ((ItemStackImpl) i).components(),  // ← 应为 componentPatch()
        ItemStack::of);
Codec<ItemStack> CODEC = ItemStack.CODEC                                            // CODEC 走的是 componentPatch
        .orElse(Material.CODEC.transform(ItemStack::of, ItemStack::material));
```

**为什么是 bug**：`ItemStackImpl.components()`（`ItemStackImpl.java:82-85`）= `DataComponentMap.applyPatch(prototype, patch)`，而 `applyPatch`（`DataComponentMap.java:121-127`）遇到 null 值就 `result.remove(key)` —— 返回的 map **不含任何 null（移除标记）**，且包含材质原型的全部组件。它被交给 `PATCH_NETWORK_TYPE`（`DataComponent.java:33`，`isPatch = true`）后：
- `DataComponentMapImpl.java:186` 写出的 `removedCount = size - added = 0` → 移除标记全丢；
- `:188-202` 把原型的每一个组件（`max_stack_size`、`attribute_modifiers`、`tool`、`repairable`、`equippable` …）都当作显式 patch 写上线。

**触发场景**：`ItemStackTemplate.NETWORK_TYPE` 正是所有**嵌套物品**的网络类型 —— `USE_REMAINDER`(`DataComponents.java:121`)、`CHARGED_PROJECTILES`(:145)、`BUNDLE_CONTENTS`(:146)、`CONTAINER`(:171)、`SULFUR_CUBE_CONTENT`(:174)，另外 `AdvancementsPacket` 图标、`SlotDisplay`、`Particle.Item` 也用它。把 `ItemStack.of(Material.COOKED_BEEF).without(DataComponents.CONSUMABLE)` 放进一个 bundle，移除就消失了；而且连 Minestom→Minestom 都不 round-trip（读侧 `ItemStack::of` 会重新 diff 掉）。

**影响**：① 嵌套物品的组件移除被静默丢弃（数据损坏）；② 一个 256 格 `CONTAINER` 会把 256 份完整原型写上线，是可观的带宽/CPU 放大；③ 同一件嵌套物品经 NBT 存盘再读出来 ≠ 发给客户端的版本。

**建议修复**：

```diff
-        DataComponent.PATCH_NETWORK_TYPE, (i) -> ((ItemStackImpl) i).components(),
+        DataComponent.PATCH_NETWORK_TYPE, ItemStack::componentPatch,
```

**复现测试**（不要运行）：

```java
@Test
public void nestedItemKeepsComponentRemoval() {
    var beef = ItemStack.of(Material.COOKED_BEEF).without(DataComponents.CONSUMABLE);
    var bundle = ItemStack.of(Material.BUNDLE).with(DataComponents.BUNDLE_CONTENTS, List.of(beef));

    var buf = NetworkBuffer.resizableBuffer(net.minestom.server.MinecraftServer.process());
    buf.write(ItemStack.NETWORK_TYPE, bundle);
    var back = buf.read(ItemStack.NETWORK_TYPE);

    var inner = back.get(DataComponents.BUNDLE_CONTENTS).getFirst();
    assertNull(inner.get(DataComponents.CONSUMABLE), "嵌套物品的组件移除必须保留");
    assertEquals(bundle, back);
}
```

**判定**：【读代码确认】
**关联**：无 open issue（新发现）

---

### P0-4 递归 `NetworkBuffer.Type` / `Codec` 无深度上限，客户端可远程触发 `StackOverflowError`

**严重度**：P0（远程拒绝服务）
**位置**：`src/main/java/net/minestom/server/potion/CustomPotionEffect.java:57-66`（定义）、`src/main/java/net/minestom/server/network/NetworkBufferTypeImpl.java:954-971`（缺少深度计数）

```java
// CustomPotionEffect.Settings
public static final NetworkBuffer.Type<Settings> NETWORK_TYPE = NetworkBuffer.Recursive(self ->
        NetworkBufferTemplate.template(
                VAR_INT, Settings::amplifier,
                VAR_INT, Settings::duration,
                BOOLEAN, Settings::isAmbient,
                BOOLEAN, Settings::showParticles,
                BOOLEAN, Settings::showIcon,
                self.optional(), Settings::hiddenEffect,     // ← 无界自嵌套
                Settings::new));
```

```java
// NetworkBufferTypeImpl.RecursiveType —— 只是一层转发，没有任何深度计数
final class RecursiveType<T> implements NetworkBufferTypeImpl<T> {
    final NetworkBuffer.Type<T> delegate;
    @Override public T read(NetworkBuffer buffer) { return delegate.read(buffer); }
}
```

**为什么是 bug**：可达链完全由客户端控制 ——
`ClientCreativeInventoryActionPacket.java:13`（`ItemStack.UNTRUSTED_NETWORK_TYPE`）
→ `DataComponentMapImpl.read`（`:218-229`）
→ `DataComponents.java:147` `POTION_CONTENTS`
→ `PotionContents.java:26` `CustomPotionEffect.NETWORK_TYPE.list(...)`
→ `CustomPotionEffect.java:21` `Settings.NETWORK_TYPE`。

现有的两道防线都拦不住深度：`DataComponentMapImpl.java:216` 的 `Check.stateCondition(added + removed > 256)` 限制的是**组件个数**；untrusted 路径的逐组件长度前缀（`:226-228`）限制的是**字节数**。而 `ServerFlag.MAX_PACKET_SIZE` 默认 `2_097_151`（约 2 MB），每层嵌套约 6 字节 → 约 35 万层递归。

关键在于抛出的是 `StackOverflowError`（`Error`，不是 `Exception`）：网络层唯一的兜底是 `PlayerSocketConnection.java:175` 的 `catch (Exception e)`，`PacketListenerManager.java:240` 同样只捕 `Exception`。解码发生在报文读取阶段，业务侧的 `CreativeInventoryActionListener.java:16`「非创造模式直接 return」根本轮不到执行——**任何已登录玩家都能触发，与游戏模式无关**。

`Codec` 侧同构：`CodecImpl.RecursiveImpl`（`CodecImpl.java:450-467`）也没有深度计数，`CustomPotionEffect.Settings.CODEC`（`:67-75`）同样自嵌套。它在**出站**方向也会被踩到 —— `ItemStackHashImpl.of:24` 会把同一个对象重新 encode 一遍，而 `WindowListener.java:79` 每次窗口点击都调用它。相比之下 `ComponentCodecs.COMPONENT`（`ComponentCodecs.java:313`）是间接有界的：客户端只能经 NBT 进入，而 `BinaryTagTypeImpl.java:134/145` 有 `ServerFlag.NBT_MAX_DEPTH = 512` 的限制（该 flag 自己的注释也承认「调高会爆 Java 栈」）。

**影响**：单个报文即可让承载解码的线程抛 `StackOverflowError`。如果解码跑在共享的网络 worker 上，就是全服拒绝服务；即便是每连接线程，也会以未捕获 `Error` 的形式异常终止连接处理。

**建议修复**：给 `NetworkBufferTypeImpl.RecursiveType` 和 `CodecImpl.RecursiveImpl` 加一个深度计数（`ThreadLocal<int>` 或在 `NetworkBuffer` 上带一个 depth 字段），超过阈值返回错误/抛 `ArgumentSyntaxException` 级别的受检失败；同时给 `hidden_effect` 加一个硬上限（vanilla 侧本身受组件体积限制约束）。另外建议网络读取循环把 `catch (Exception)` 放宽到 `catch (Throwable)`（上游 PR #3347「network error handling behind server flags」正在动这一块）。

**复现测试**（不要运行）：

```java
@Test
public void deeplyNestedHiddenEffectMustNotOverflowStack() {
    // 构造 200k 层嵌套的 hidden_effect 报文体
    var buf = NetworkBuffer.resizableBuffer(MinecraftServer.process());
    final int depth = 200_000;
    for (int i = 0; i < depth; i++) {
        buf.write(NetworkBuffer.VAR_INT, 0);      // amplifier
        buf.write(NetworkBuffer.VAR_INT, 0);      // duration
        buf.write(NetworkBuffer.BOOLEAN, false);  // isAmbient
        buf.write(NetworkBuffer.BOOLEAN, false);  // showParticles
        buf.write(NetworkBuffer.BOOLEAN, false);  // showIcon
        buf.write(NetworkBuffer.BOOLEAN, true);   // hiddenEffect 存在
    }
    buf.write(NetworkBuffer.VAR_INT, 0);
    buf.write(NetworkBuffer.VAR_INT, 0);
    buf.write(NetworkBuffer.BOOLEAN, false);
    buf.write(NetworkBuffer.BOOLEAN, false);
    buf.write(NetworkBuffer.BOOLEAN, false);
    buf.write(NetworkBuffer.BOOLEAN, false);      // 终止

    // 期望：抛出可控的解码异常；现状：StackOverflowError
    assertThrows(RuntimeException.class,
            () -> buf.read(CustomPotionEffect.Settings.NETWORK_TYPE));
}
```

**判定**：【读代码确认】（缺少深度限制、可达链、`Error` 不被捕获三点均已确认）／【需要运行验证】（实际溢出深度与线程落点取决于 `-Xss` 和网络层线程模型）
**关联**：与 #3320「Packet id isn't recognized and Memory Segment IndexOutOfBoundsException」同属网络层错误处理缺口；PR #3347 相关

---

### P1-1 `WindowListener` 完全不校验 `packet.windowId()`

**严重度**：P1
**位置**：`src/main/java/net/minestom/server/listener/WindowListener.java:25-31`

```java
final int windowId = packet.windowId();
final boolean playerInventory = windowId == 0;
final AbstractInventory inventory = playerInventory ? player.getInventory() : player.getOpenInventory();

// Prevent some invalid packets
if (inventory == null || packet.slot() == -1) return;
```

**为什么是 bug**：任何非 0 的 windowId 都被当成「当前打开的那个背包」。同文件的 `inventoryButtonClickListener`（`:98`）就正确地做了 `packet.windowId() != (int) inventory.getWindowId()` 校验，点击路径却没有。另外 `Inventory.generateId()`（`Inventory.java:44-46`）在 1..127 之间循环，超过 127 个存活背包时 id 还会重复。

**触发场景**：分页 GUI 的经典时序 —— 客户端还开着 A 页，服务端已经 `openInventory(B)`；客户端在收到 B 的 `OpenWindowPacket` 之前发出的、携带 A 的 windowId 的点击包，会被原样应用到 B 的同名槽位上。

**影响**：GUI 插件里最常见的「翻页刷物品」漏洞面；点击落在错误的容器上，可能导致发放/扣除错误的物品。

**建议修复**：在 `:31` 的早退判断里加上 `if (!playerInventory && windowId != inventory.getWindowId()) { inventory.update(player); return; }`。顺带把 `stateId` 也纳入判定（目前 `ClientClickWindowPacket.stateId` 与 `changedSlots` 都读进来了却从未被使用）。

**复现测试**：

```java
@Test
public void clickWithStaleWindowIdMustBeIgnored(Env env) {
    var a = new Inventory(InventoryType.CHEST_1_ROW, Component.empty());
    var b = new Inventory(InventoryType.CHEST_1_ROW, Component.empty());
    b.setItemStack(0, ItemStack.of(Material.DIAMOND, 64));
    var player = env.createConnection().connect(env.createFlatInstance(), new Pos(0, 42, 0));
    player.openInventory(b);                                   // 服务端已经切到 B

    player.addPacketToQueue(clickPacket(PICKUP, a.getWindowId(), 0, 0)); // 客户端还在 A
    player.interpretPacketQueue();

    assertEquals(64, b.getItemStack(0).amount(), "带旧 windowId 的点击不应作用于 B");
    assertEquals(ItemStack.AIR, player.getInventory().getCursorItem());
}
```

**判定**：【读代码确认】

---

### P1-2 `closeInventory(fromClient=true, windowId=0)` 泄漏容器 viewer，并让该容器再也打不开

**严重度**：P1（内存泄漏 + 状态卡死；同时是 P0-1 的触发前提）
**位置**：`src/main/java/net/minestom/server/entity/Player.java:1934`、`:1946-1947`

```java
public void closeInventory(boolean fromClient, byte windowId) {
    AbstractInventory openInventory = windowId == 0 ? getInventory() : getOpenInventory();
    if (openInventory == null || windowId != openInventory.getWindowId()) return;
    ...
    this.openInventory = null;                                   // 真正打开的容器引用被丢弃
    if (openInventory != inventory) openInventory.removeViewer(this);  // 局部变量是玩家背包 -> 不会执行
    inventory.update();
```

**为什么是 bug**：当客户端在开着容器时发 `CloseWindow(0)`，局部变量 `openInventory` 被解析成**玩家背包**（windowId 0 与 `PlayerInventory.getWindowId()` 相等，校验通过），于是：
- `this.openInventory = null` 把真正的容器引用扔掉；
- `openInventory != inventory` 为 false，容器的 `removeViewer` 从不调用 → 容器的 `viewers` 集合永久持有该玩家，`Inventory.removeViewer` 里的 `player.getClickPreprocessor().clearCache()`（`Inventory.java:110`）也不会执行。

后续 `Player.openInventory(同一个容器)` 时，`getOpenInventory()` 已是 null 不会移除旧 viewer，而 `Inventory.addViewer` 因为 `viewers.add(player)` 返回 false 直接 `return false`（`Inventory.java:92`）—— **`OpenWindowPacket` 不发**，但 `this.openInventory = newInventory` 照常赋值。玩家从此持有一个自己看不见、也关不掉的「打开中」背包。

`git log -L 1932,1955` 显示该分支是 `b455ade05d`（"Send InventoryCloseEvent on player inventory close (#2829)"）引入的。

**影响**：① 长生命周期的全局 GUI 会永久持有已下线玩家的 `Player` 对象（内存泄漏），并持续向其发送 SetSlot 包；② 玩家进入不可恢复的 GUI 状态；③ 让 P0-1 的拖拽缓存跨背包存活。

**建议修复**：把「关闭哪个背包」的判定改成先看 `getOpenInventory()`：

```java
AbstractInventory open = getOpenInventory();
AbstractInventory target = (open != null && windowId == open.getWindowId()) ? open
        : (windowId == 0 ? getInventory() : null);
if (target == null) return;
...
if (target != inventory) { this.openInventory = null; target.removeViewer(this); }
```

并且无论走哪条路都调用一次 `getClickPreprocessor().clearCache()`。

**复现测试**：

```java
@Test
public void clientCloseWindowZeroMustNotLeakOpenContainer(Env env) {
    var inv = new Inventory(InventoryType.CHEST_1_ROW, Component.empty());
    var player = env.createConnection().connect(env.createFlatInstance(), new Pos(0, 42, 0));
    player.openInventory(inv);

    player.addPacketToQueue(new ClientCloseWindowPacket(0));
    player.interpretPacketQueue();

    assertFalse(inv.getViewers().contains(player), "容器不应继续把玩家当作 viewer");
    assertTrue(player.openInventory(inv));
    // 且必须重新发出 OpenWindowPacket —— 现状是 addViewer 返回 false，什么都不发
}
```

**判定**：【读代码确认】

---

### P1-3 `didCloseInventory` 在 `closeInventory` 末尾被重置，「事件里关背包取消点击」机制永久失效

**严重度**：P1（取消语义失效，GUI 插件的常见防护被绕过）
**位置**：`src/main/java/net/minestom/server/entity/Player.java:1950` 与 `src/main/java/net/minestom/server/listener/WindowListener.java:57-60`

```java
// Player.java
if (!fromClient) didCloseInventory = true;
this.openInventory = null;
if (openInventory != inventory) openInventory.removeViewer(this);
inventory.update();
didCloseInventory = false;                   // ← 1950：无条件复位
```

```java
// WindowListener.java:46-66
player.UNSAFE_changeDidCloseInventory(false);
...
EventDispatcher.call(inventoryPreClickEvent);
...
if (player.didCloseInventory()) {
    // Cancel the click if the inventory has been closed by Player#closeInventory
    player.UNSAFE_changeDidCloseInventory(false);
    successful = false;
} else if (inventoryPreClickEvent.isCancelled()) { ... }
else { successful = inventory.handleClick(player, click); }
```

**为什么是 bug**：`didCloseInventory` 的唯一置位点是 `closeInventory`，而它在方法末尾又无条件置回 false。因此 `WindowListener:57` 的判断**永远为 false**。注释写明这条分支的目的就是「监听器里调了 `Player#closeInventory` 就取消点击」，实际做不到。

**触发场景**：`InventoryPreClickEvent` 监听器里调 `player.closeInventory()`（GUI 库里最常见的「点一下就关菜单」写法）。点击会继续在已经关闭的背包上执行 `handleClick`。

**影响**：菜单被关闭后点击仍然生效 —— 对于「点击发放物品」类 GUI，配合 P1-1 就是一条完整的重复发放路径。

**建议修复**：`didCloseInventory` 只在 `removeViewer` 消费完之后复位（把 `:1950` 的复位挪进 `removeViewer` 里），或者干脆让 `WindowListener` 改为比较 `player.getOpenInventory()` 在事件前后是否变化，不再依赖这个布尔量。

**复现测试**：

```java
@Test
public void closingInventoryInPreClickCancelsTheClick(Env env) {
    var inv = new Inventory(InventoryType.CHEST_1_ROW, Component.empty());
    inv.setItemStack(0, ItemStack.of(Material.DIAMOND, 64));
    var player = env.createConnection().connect(env.createFlatInstance(), new Pos(0, 42, 0));
    player.openInventory(inv);
    env.process().eventHandler().addListener(InventoryPreClickEvent.class, e -> player.closeInventory());

    player.addPacketToQueue(clickPacket(PICKUP, inv.getWindowId(), 0, 0));
    player.interpretPacketQueue();

    assertEquals(ItemStack.AIR, player.getInventory().getCursorItem());
    assertEquals(64, inv.getItemStack(0).amount());
}
```

**判定**：【读代码确认】

---

### P1-4 双击「拾取全部」没有做 vanilla 的两趟扫描（先取非满堆）

**严重度**：P1（功能错误 + 客户端预测不一致）
**位置**：`src/main/java/net/minestom/server/inventory/click/InventoryClickProcessor.java:228-240`，根因在 `src/main/java/net/minestom/server/inventory/TransactionType.java:85-112`

```java
// TransactionType.TAKE —— 单趟、按槽位顺序、不区分满堆
for (int i = start; step > 0 ? i < end : i > end; i += step) {
    final ItemStack inventoryItem = inventory.getItemStack(i);
    if (inventoryItem.isAir()) continue;
    if (itemStack.isSimilar(inventoryItem)) {
        ...
        if (itemStackAmount < itemAmount) {
            itemChangesMap.put(i, inventoryItem.withAmount(itemAmount - itemStackAmount));
            itemStack = ItemStack.AIR;
            break;                                  // 直接从满堆里掏空
        }
```

**为什么是 bug**：vanilla 的 `PICKUP_ALL` 分两趟扫描槽位——第一趟**只取 `count != maxStackSize` 的堆**，第二趟才允许动满堆。Minestom 只有一趟且按槽位序号 0→35（即热键栏优先），于是遇到的第一个同类满堆就会被掏空，散落在背包各处的零星物品原封不动。

**触发场景**（= 上游 #3027 的复现步骤）：热键栏槽 1 放满一组圆石，把另一组拆成 1 个 × 27 铺满背包，然后双击收集。

**影响**：与客户端预测不一致（客户端按 vanilla 算，服务端按自己的算）→ 每次双击都要靠 `WindowListener:78-80` 的光标重同步兜底；玩家看到的整理结果是错的。

**建议修复**：给 `TransactionType` 增加一个「两趟」的 TAKE 变体，或在 `InventoryClickProcessor.doubleClick` 内部先用 `slotPredicate` 过滤 `item.amount() < item.maxStackSize()` 跑一趟、剩余量再跑第二趟不带过滤的。顺带把扫描顺序改成 vanilla 的「主背包 9-35 → 热键栏 0-8」。

**复现测试**：

```java
@Test
public void doubleClickPrefersPartialStacks(Env env) {
    var player = env.createConnection().connect(env.createFlatInstance(), new Pos(0, 42, 0));
    var inv = player.getInventory();
    inv.setItemStack(1, ItemStack.of(Material.COBBLESTONE, 64));            // 热键栏里的满堆
    for (int i = 9; i < 36; i++) inv.setItemStack(i, ItemStack.of(Material.COBBLESTONE, 1));
    inv.setCursorItem(ItemStack.of(Material.COBBLESTONE, 1));               // 刚从槽 0 拿起来

    // window0 的 hotbar slot 0 = window slot 36
    player.addPacketToQueue(clickPacket(PICKUP_ALL, 0, 0, 36));
    player.interpretPacketQueue();

    assertEquals(64, inv.getCursorItem().amount());
    for (int i = 9; i < 36; i++)
        assertEquals(ItemStack.AIR, inv.getItemStack(i), "散堆应当先被收走：slot " + i);
    assertEquals(28, inv.getItemStack(1).amount(), "满堆只应补足差额");
}
```

**判定**：【读代码确认】
**关联**：#3027「Pickup all inventory transaction is improperly implemented」——**仍存在**

---

### P1-5 shift-click 进入容器时从 slot 0 开始，会把物品塞进结果/输出槽

**严重度**：P1
**位置**：`src/main/java/net/minestom/server/inventory/Inventory.java:216-221`

```java
} else {
    clickResult = clickProcessor.shiftClick(
            playerInventory, this,
            0, getInnerSize(), 1,        // ← 从 slot 0 开始，对所有 InventoryType 一视同仁
            player, clickSlot, clicked, cursor);
}
```

**为什么是 bug**：`Inventory.getInnerSize()` 就是 `getSize()`，扫描范围覆盖整个窗口。而 `InventoryType.CRAFTING`（size 10）的 slot 0 是**合成结果槽**，`FURNACE`/`BLAST_FURNACE`/`SMOKER`（size 3）的 slot 2 是**产出槽**，`ANVIL`/`GRINDSTONE`/`CARTOGRAPHY`（size 3）的 slot 2 同理，`MERCHANT` 的 slot 2 是交易结果。`TransactionType.ADD` 完全没有「哪些槽位可接受输入」的概念。

**触发场景**：给玩家 `openInventory(new Inventory(InventoryType.CRAFTING, ...))`，然后从背包 shift-click 任意物品。

**影响**：物品落进结果槽 → 客户端预测（它认为第一个可用槽是 index 1）与服务端不一致，产生 ghost item；对熔炉/铁砧类窗口则是逻辑上不该发生的状态。

**建议修复**：在 `AbstractInventory` 上加一个可覆盖的「可接受输入的槽位序列」（issue #2847 里 reporter 提的 `Iterator<Integer> getFillSlotsIterator` 是合理形状），让 `InventoryType` / 各 `type/*Inventory` 子类声明输入槽范围；`Inventory.shiftClick` 与 `TransactionType` 改为消费该序列而不是 `start/end/step`。

**复现测试**：

```java
@Test
public void shiftClickIntoCraftingMustSkipResultSlot(Env env) {
    var inv = new Inventory(InventoryType.CRAFTING, Component.empty());   // size 10, slot 0 = result
    var player = env.createConnection().connect(env.createFlatInstance(), new Pos(0, 42, 0));
    player.openInventory(inv);
    player.getInventory().setItemStack(0, ItemStack.of(Material.STICK, 64));

    // size=10 的窗口里，Minestom hotbar slot 0 对应 window slot 37
    player.addPacketToQueue(clickPacket(QUICK_MOVE, inv.getWindowId(), 0, 37));
    player.interpretPacketQueue();

    assertEquals(ItemStack.AIR, inv.getItemStack(0), "不得写入合成结果槽");
    assertEquals(64, inv.getItemStack(1).amount());
}
```

**判定**：【读代码确认】
**关联**：#2847「Shift clicking from player inventory to crafting inventory puts item in crafting slot」——**仍存在**

---

### P1-6 从容器 shift-click 出来时，热键栏只要装进去一点就不再尝试主背包

**严重度**：P1（功能错误）
**位置**：`src/main/java/net/minestom/server/inventory/Inventory.java:205-215`

```java
clickResult = clickProcessor.shiftClick(this, playerInventory,
        8, -1, -1, player, clickSlot, clicked, cursor);     // 第一趟：热键栏 8→0

if (clickResult.isCancel()) {                               // 只有「一格都没装进去」才走第二趟
    clickResult = clickProcessor.shiftClick(this, playerInventory,
            playerInventory.getInnerSize() - 1, -1, -1, player, clickSlot, clicked, cursor);
}
```

**为什么是 bug**：`clickResult` 的 `cancel` 标记由 `InventoryClickProcessor.shiftClick:120-127` 的 slotPredicate 副作用置位——**只要有任何一个候选槽被写入就置为 false**。因此「热键栏装进去一部分、还剩一大半」时 `isCancel()` 为 false，第二趟（覆盖主背包 9-35）永不执行，剩余物品被写回容器（`:229`）。vanilla 会继续往主背包填。

**触发场景**：热键栏里已有 63 个钻石且其余 8 格被占满，从箱子 shift-click 一组 64 个钻石 —— 只走进去 1 个，剩下 63 个留在箱子里，而主背包 9-35 全空。

**影响**：shift-click 行为与 vanilla 不一致、客户端预测错误。同类问题在 `PlayerInventory.shiftClick:243-287` 的三个分支里同样存在。

**建议修复**：两趟改成串联而非互斥 —— 第一趟返回的 `clickResult.getClicked()`（剩余量）作为第二趟的输入：

```java
clickResult = clickProcessor.shiftClick(this, playerInventory, 8, -1, -1, player, clickSlot, clicked, cursor);
if (!clickResult.getClicked().isAir()) {
    clickResult = clickProcessor.shiftClick(this, playerInventory,
            playerInventory.getInnerSize() - 1, 8, -1, player, clickSlot, clickResult.getClicked(), cursor);
}
```

**判定**：【读代码确认】

---

### P1-7 未声明网络类型 / 未声明 codec 的组件不会被过滤，直接 NPE

**严重度**：P1（可被普通玩法触发的服务端异常）
**位置**：`src/main/java/net/minestom/server/component/DataComponentMapImpl.java:188-201`（写网络）、`src/main/java/net/minestom/server/item/ItemStackHashImpl.java:22-29`（算 hash）

```java
for (var entry : patch.components.int2ObjectEntrySet()) {
    if (entry.getValue() == null) continue;
    buffer.write(NetworkBuffer.VAR_INT, entry.getIntKey());
    DataComponent<Object> type = (DataComponent<Object>) this.idToType.apply(entry.getIntKey());
    Check.notNull(type, "Unknown component id: {0}", entry.getIntKey());
    if (isTrusted) type.write(buffer, entry.getValue());   // networkType == null -> NPE
```

**为什么是 bug**：`DataComponent.isSynced()` / `isSerialized()`（`DataComponent.java:58-59`，实现在 `DataComponentImpl.java:39-47`）就是为了过滤这类组件而存在的，但 `grep` 全仓库显示它们**只在测试里被调用过**（`ItemComponentReadWriteRegistriesTest.java:84`、`UnitTest.java:44`、`AbstractItemComponentRegistriesTest.java:38/48`），生产路径一次都没用。`DataComponentImpl.write/encode`（`:56-71`）遇到 null 直接 `Check.notNull` → NPE。

受影响的注册（`DataComponents.java`）：
- `networkType == null` → 发给客户端时 NPE：`INTANGIBLE_PROJECTILE`(:118)、`MAP_DECORATIONS`(:143)、`LOCK`(:175)、`CONTAINER_LOOT`(:176)
- `codec == null` → 写 NBT / 算 hash 时 NPE：`CREATIVE_SLOT_LOCK`(:116)、`ADDITIONAL_TRADE_COST`(:137)、`MAP_POST_PROCESSING`(:144)

**触发场景**：`ItemStack.of(Material.CHEST).with(DataComponents.CONTAINER_LOOT, loot)` 发给任何玩家；或者让带 `MAP_POST_PROCESSING` 的物品出现在光标上 —— `WindowListener.java:78-79` 每次窗口点击都会调 `ItemStack.Hash.of(cursorItem, ...)`，于是**每一次点击**都抛 NPE。

**影响**：服务端异常（被 exception manager 吞掉）、物品无法送达客户端、点击事务被中断（同 P0-1 的「中途异常」模式，存在状态不一致风险）。

**建议修复**：在 `DataComponentMapImpl.NetworkTypeImpl.write` 里跳过 `!component.isSynced()` 的条目（同时从 `:179-184` 的 `added` 计数里排除），在 `CodecImpl.encode` 里跳过 `!isSerialized()`，`ItemStackHashImpl.of` 两者都跳过。

**判定**：【读代码确认】

---

### P1-8 #3118：`ArgumentItemStack` 无法解析组件移除语法，且 key 读取器抛非受检异常

**严重度**：P1
**位置**：`src/main/java/net/minestom/server/command/builder/arguments/minecraft/ArgumentItemStack.java:66`、`:74`、`:82`、`:142-149`

```java
DataComponentMap.Builder components = DataComponentMap.builder();   // :66 —— Builder 没有 remove()
...
final Key componentId = reader.readKey();                            // :74
...
components.set((DataComponent<Object>) component, componentValueResult.orElseThrow());  // :82
...
Key readKey() {                                                      // :142-149
    char c;
    int start = index;
    while (hasMore() && (c = peek()) != '{' && c != '[' && c != '=') index++;
    return Key.key(input.substring(start, index));
}
```

**为什么是 bug**：三个独立缺陷叠加。
1. `readKey()` 的停止字符集里没有 `]`、`,`、`!`。`/give <player> cooked_beef[!consumable]` 走到 `:74` 时会把 `!consumable]` 整个吃掉，`Key.key("!consumable]")` 抛 adventure 的 **`InvalidKeyException`**（非受检）。`CommandParserImpl.parseArgument:544/554` 只捕获 `ArgumentSyntaxException`，异常直接逃逸到 `PacketListenerManager` 变成一条裸堆栈，玩家得不到任何反馈。
2. 即便修好读取器，`:66` 用的是 `DataComponentMap.builder()`，而 `DataComponentMap.Builder`（`DataComponentMap.java:195-204`）**没有 `remove()`**，只有 `PatchBuilder`（`:206-224`）才有 —— 移除语义根本表达不了。
3. `:82` 的 `Result.orElseThrow()`（`codec/Result.java:105-110`）在 `Result.Error` 上抛 `IllegalArgumentException`，同样不是 `ArgumentSyntaxException`。`/give stone[minecraft:damage=notanint]` 会以裸堆栈告终，而该类明明定义了 `INVALID_COMPONENT = 4` 却只用于「未知组件名」。

同一个 `Key.key` 陷阱还出现在 `:59`（材质名），`/give Diamond_Sword`（大写）即抛异常；对照 `instance/block/Block.java:587-592` 已经知道要 `catch (InvalidKeyException)`，`ArgumentResourceLocation.java:26` 也知道要先 `Key.parseable`。

**建议修复**：`:66` 改用 `DataComponentMap.patchBuilder()`；`readKey()` 停止集加入 `]`、`,`，并在返回前 `if (!Key.parseable(raw)) throw new ArgumentSyntaxException(...)`；解析循环里识别前导 `!` 走 `components.remove(component)` 且不消费 `=`；`:82` 改成对 `Result.Error` 抛 `ArgumentSyntaxException(..., INVALID_COMPONENT)`。

**复现测试**：

```java
@Test
public void argumentItemStackSupportsComponentRemoval() {
    var arg = ArgumentType.ItemStack("item");
    var result = assertDoesNotThrow(() -> arg.parse(null, "cooked_beef[!consumable]"));
    assertNull(result.get(DataComponents.CONSUMABLE));
    // 非法值必须是 ArgumentSyntaxException，而不是 IllegalArgumentException / InvalidKeyException
    assertThrows(ArgumentSyntaxException.class, () -> arg.parse(null, "stone[minecraft:damage=notanint]"));
    assertThrows(ArgumentSyntaxException.class, () -> arg.parse(null, "Diamond_Sword"));
}
```

**判定**：【读代码确认】
**关联**：#3118「ArgumentItemStack doesn't work with some components」——**仍存在**；PR #3119 未合入

---

### P1-9 命令解析：兄弟分支被提前短路，建议（suggestion）回调被吞

**严重度**：P1
**位置**：`src/main/java/net/minestom/server/command/CommandParserImpl.java:239-244`，产出点在 `:271` 与 `:282-283`

```java
NodeResult error = null;
for (Node child : node.next()) {
    NodeResult childResult = parseNode(sender, child, chain, reader);
    if (childResult.argumentResult instanceof ArgumentResult.Success<Object>) {
        // Assume that there is only one successful node for a given chain of arguments
        return childResult;                        // ← 后面的兄弟分支再也不会被访问
    } else {
        ...
        NodeResult lastSuccess = Objects.requireNonNull(childResult.chain().lastSuccessfulResult());
        ...
        error = lastSuccess;                       // :271 —— error 的 argumentResult 是 Success
    }
}
...
if (error != null) return error;                   // :283
```

**为什么是 bug**：`:283` 返回的「失败结果」`error` 其实是 `lastSuccessfulResult()`，它的 `argumentResult` **就是一个 `Success`**。于是上一层递归在 `:242` 无法区分「这条分支真的走通了」和「这条分支失败、只是携带了最后一个成功节点」，直接 `return`，后面所有兄弟分支（包括真正匹配用户输入、并且持有相关 `SuggestionCallback` 的那条）永远不被解析。

另外 `NodeResult.callback` 字段（`:479-480`）是**只写不读**的 —— 所有 suggestion 最终都来自粘性的 `Chain.suggestionCallback`，一旦分支被跳过就不可恢复。

**触发场景**：同一命令注册两条首参类型不同的语法，例如先注册 `/cmd <word:带建议>`，再注册 `/cmd <int> <target:带建议>`。输入 `/cmd 5 ` 后按 Tab：`word` 分支成功吃下 `5`、其子节点失败、返回携带 Success 的 error，`:243` 触发短路，`int` 分支从未被访问 → 客户端收不到任何补全。

**影响**：多语法命令的 Tab 补全静默失效（这正是 #2682 / #1617 / #1520 的用户可见症状）。

**建议修复**：给「部分成功的错误占位」一个独立的 `ArgumentResult` 变体（或在 `NodeResult` 上加一个 `terminal` 布尔），让 `:242` 只对真正走到底的成功短路，其余情况继续遍历兄弟并挑最深的一条。

**次生问题（同文件 `:306`）**：`chain.suggestionCallback = suggestionCallback;` 没有像 `:208` 那样做 null 保护，且赋的是 `chain` 而返回的 `NodeResult` 用的是 `error == null ? chain : error.chain`（`:302`）—— 这条语句要么是死代码，要么会清掉前一个参数刚装上的回调。`:297` 的 `returnNode = nextNodes.getFirst()` 也是按 `nodePriority` 取第一个而非匹配用户输入的那个。

**判定**：【读代码确认】（短路机制逐行确认）／【需要运行验证】（用户可见的补全缺失需要真实 Tab 场景确认）
**关联**：#2682「Default Executors Breaking Suggestions」、#1617、#1520；PR #3129 / #3058 均未合入（`git blame` 显示该区域最近一次改动是 `3d764861a9` "Fix tab complete suggestions (#3318)"，只加了 `deepestSuggestion` 透传，没有解决 `:242` 的短路）

---

### P1-10 语法条件只挂在最后一个参数上（#1326）

**严重度**：P1
**位置**：`src/main/java/net/minestom/server/command/GraphImpl.java:183-191`，消费点在 `src/main/java/net/minestom/server/command/GraphConverter.java:44-47`

```java
for (CommandSyntax syntax : command.getSyntaxes()) {
    ConversionNode syntaxNode = root;
    for (Argument<?> arg : syntax.getArguments()) {
        boolean last = arg == syntax.getArguments()[syntax.getArguments().length - 1];
        var ex = last ? ExecutionImpl.fromSyntax(syntax) : null;      // 条件只装在最后一个节点
        syntaxNode = syntaxNode.nextMap.computeIfAbsent(arg, argument -> new ConversionNode(argument, ex));
        if (syntaxNode.execution == null) syntaxNode.execution = ex;
    }
}
```

**为什么是 bug**：`GraphConverter.append` 只在 `graphNode.execution() != null` 时才用 `execution.test(player)` 过滤节点。对 `addConditionalSyntax(cond, exec, Literal("reload"), Word("target"))`，只有 `target` 携带 `cond`，条件不满足的玩家仍然会在 `DeclareCommandsPacket` 里收到 `reload` 字面量（只是没有子节点）。

同一段还有两个附带缺陷：
- `:186` 用**引用相等** `arg == args[len-1]` 判断「是否最后一个」。同一个 `Argument` 实例出现两次（`addSyntax(e, a, a)`）时，第一次出现也会被判为 last，executor/condition 装到了中间节点上。
- `:188-189` 是「先到先得」：两条语法共享前缀时，节点保留第一条语法的 execution，第二条终止于该共享节点的语法永远装不上自己的条件/executor。

**建议修复**：把 syntax 的 **condition**（不是 executor）传播到该语法创建的每一个节点，或者在 `GraphConverter.append` 递归时携带一个累积谓词做逐路径判定；`:186` 改用下标比较。

**判定**：【读代码确认】
**关联**：#1326「ConditionalSyntax only affect last argument in CommandNode」——**仍存在**

---

### P1-11 `CommandManager.unregister` 未加锁，且命令名大小写处理左右不一致

**严重度**：P1（并发损坏 + 逻辑错误）
**位置**：`src/main/java/net/minestom/server/command/CommandManager.java:35-36`、`:50`、`:61`、`:85`、`:101`

```java
private final Map<String, Command> commandMap = new HashMap<>();   // :35
private final Set<Command> commands = new HashSet<>();             // :36

public synchronized void register(Command command) { ... commandMap.put(name, command); ... }   // :50/:61 原样存
public void unregister(Command command) { ... }                                                  // :85 未加锁
public @Nullable Command getCommand(String commandName) {
    return commandMap.get(commandName.toLowerCase(Locale.ROOT));                                 // :101 小写查
}
```

**两个独立问题**：

1. **线程安全**：`register` 是 `synchronized`、`getGraph()`（`:212-224`）在 `synchronized (this)` 里读，唯独 `unregister` 裸奔，而底层是普通 `HashMap`/`HashSet`。任意线程上的 `unregister` 与玩家登录时的 `Graph.merge(getCommands())` 并发即可损坏结构或抛 `ConcurrentModificationException`。`getCommands()`（`:198`）返回的还是活集合的不可变**视图**，调用方遍历时同样在裸奔。此外 `unregister` 在锁外调 `invalidateGraphCache()`，如果它落在 `getGraph()` 的 merge 与赋值之间，这次失效会被静默丢弃（缓存变陈旧直到下一次 register/unregister）。

2. **大小写不一致**：写入用原名、读取用小写。`new Command("Foo")` 之后 `getCommand("Foo")` 与 `getCommand("foo")` 都返回 null；更糟的是 `register` 的重复检查走的是 `commandExists`→`getCommand`，于是**同一个命令可以注册两次**，`Graph.merge` 再静默丢掉一份。而 `unregister` 用原名删除，所以它反而是好的 —— 这种不对称让问题很难被发现。

**建议修复**：`unregister` 加 `synchronized`；`commands`/`commandMap` 换成并发集合或在锁内做快照；`commandMap.put` 与 `remove` 统一 `toLowerCase(Locale.ROOT)`（或者去掉 `:101` 的小写化，与 `ArgumentLiteral` 的大小写敏感匹配保持一致）。

**判定**：【读代码确认】

---

### P1-12 `DynamicRegistry#remove` 移位了 id 数组却不重建 `keyToId`

**严重度**：P1（注册表 id 全线偏移 → 客户端协议错乱）
**位置**：`src/main/java/net/minestom/server/registry/DynamicRegistryImpl.java:175-181`

```java
// Remove value from all mappings (shifting down indices)
idToValue.remove(id);          // List.remove(int)：后面的元素整体前移
idToKey.remove(registryKey);   // List.remove(Object)：同样前移
keyToId.remove(registryKey);   // ← 只删了这一个 key，其余的 id 全部没更新
var value = keyToValue.remove(key);
valueToKey.remove(value);
packById.remove(id);
```

**为什么是 bug**：`idToValue` / `idToKey` / `packById` 被压缩了（`id` 之后的下标各减一），但 `keyToId` 只少了一条映射。所有排在被删条目之后的 key，其 `getId()` 仍然是删除前的旧值，整体偏移 1，最后一条的 id 甚至等于 `size()`。`DynamicRegistry#remove` 的 javadoc 明确承诺会做重映射。

**触发场景**：`minestom.registry.unsafe-ops=true`（或测试环境）下对非末位条目调 `registry.remove(key)`。

**影响**：`RegistryNetworkTypes.RegistryKeyImpl.write` / `HolderNetworkTypeImpl.write` 把错误（可能越界）的 id 写上线；`getPack(key)` 返回错误的 datapack；`tagRegistry()` 发出偏移的 tag 条目。客户端表现为「维度/生物群系/伤害类型对不上」乃至直接断线。

**建议修复**：删除后重建尾部映射：

```java
for (int i = id; i < idToKey.size(); i++) keyToId.put(idToKey.get(i), i);
```

**判定**：【读代码确认】

---

### P1-13 `Codec.COMPONENT` 遇到未知 click/hover action 会抛异常而非返回 `Result.Error`（远程可达）

**严重度**：P1
**位置**：`src/main/java/net/minestom/server/codec/ComponentCodecs.java:90-98`（click event）、`:181` + `:197-202`（hover event）

```java
private static final Codec<ClickEvent.Action<?>> ACTION_CODEC =
        Codec.STRING.transform(ClickEvent.Action.NAMES::value, ClickEvent.Action::name);
...
if (!(actionResult instanceof Result.Ok(var action)))
    return actionResult.cast();
return switch (action) {                      // action 为 null 时 -> NullPointerException
    case ClickEvent.Action.OpenUrl _ -> ...
```

```java
private static StructCodec<? extends HoverEvent<?>> hoverEventCodec(HoverEvent.Action<?> action) {
    if (action == HoverEvent.Action.SHOW_TEXT) return SHOW_TEXT;
    ...
    throw new IllegalStateException("Unknown hover event action: " + action);   // 应该 return null
}
```

**为什么是 bug**：adventure 的 `Index#value(K)` 对未知名字返回 `null`，`TransformImpl.decode` 把它包成 `Ok(null)`，record pattern `Result.Ok(var action)` 会匹配到 null，随后无 `case null` 的 pattern switch 抛 NPE。hover 侧则更直接：`CodecImpl.UnionImpl.decodeFromMap`（`CodecImpl.java:384-385`）写的是「mapper 返回 null 就产出 `Result.Error`」，而 `hoverEventCodec` 选择 `throw`，让那道守卫成了不可达代码。

**触发场景**：`{"text":"a","hover_event":{"action":"bogus"}}` → `IllegalStateException`；`{"text":"a","click_event":{"action":"bogus"}}` → `NullPointerException`。两者都能由客户端送达：`ComponentNetworkBufferTypeImpl.java:65` 接收 `ClientTestInstanceBlockActionPacket.errorMessage`，以及 `ClientCreativeInventoryActionPacket` → `CUSTOM_NAME`/`ITEM_NAME`/`LORE`。

**影响**：违反 `Decoder` 契约 —— 所有期待拿到 `Result` 的调用方（`ItemStack.fromCompound`(`ItemStack.java:138`)、`ArgumentItemStack`(`:82`)、`DynamicRegistryImpl.loadStaticJsonRegistry`）都无法给出干净的错误，只能让非受检异常逃逸。

**建议修复**：`NAMES::value` 换成会产生 `Result.Error` 的查找（`Index::valueOrThrow` 即可，`TransformImpl` 已经 catch）；`hoverEventCodec` 改成返回 `null` 让 `UnionImpl` 的既有守卫生效。

**判定**：【读代码确认】

---

### P1-14 `optional()` 组合子既吞错误也毁编码：`Ok(null)` → 集合 NPE；NBT 下 encode 直接抛异常

**严重度**：P1（`Codec` 公共 API 契约被破坏）
**位置**：`src/main/java/net/minestom/server/codec/CodecImpl.java:118-121`（吞错误）、`:177-184`（解码 NPE）、`:192-199` 与 `:232-238`（编码抛异常）

```java
// OptionalImpl.decode —— 任何解码失败都被压成 Ok(null)
public <D> Result<T> decode(Transcoder<D> coder, D value) {
    return new Result.Ok<>(inner.decode(coder, value).orElse(defaultValue));
}
```

```java
// ListImpl.decode
decodedList.add(valueItem);                   // valueItem 可能是 null
return new Result.Ok<>(List.copyOf(decodedList));   // List.copyOf 拒绝 null -> NPE
```

**为什么是 bug**：`StructCodec.get`（`StructCodec.java:3423-3429`）刻意绕开了这个包装（它直接调 `inner.decode`），所以结构体字段是安全的；但**其他所有组合子**都会看到被吞掉的 null：`List.copyOf`(`:184`) / `Set.copyOf`(`:224`) / `Map.copyOf`(`:273`,`:335`) 全都拒绝 null 元素 → NPE 从 `decode` 里逃出来。同一个根因还让 `X.optional().orElse(Y)`（`CodecImpl.java:503-506`）里的 `Y` 变成死代码，让 `Codec.Either(A.optional(), B)`（`:562-568`）永远走左分支。

编码方向同样坏：`OptionalImpl.encode`（`:124-127`）返回 `coder.createNull()`，而 `TranscoderNbtImpl.createNull()` 是 `EndBinaryTag`（`TranscoderNbtImpl.java:29-32`）—— 它**不是 Java null**，`ListImpl.encode` 的 `if (encodedItem != null)` 守卫接不住，`ListBinaryTag` 的 `noAddEnd` 会抛 `IllegalArgumentException`。`SetImpl.encode`（`:232-238`）连那个守卫都没有。

**触发场景**：`Codec.STRING.optional().set()` —— 这正是 `Codec.java:46-48` 的**类文档示例**；`Codec.INT.optional(0).list().encode(Transcoder.NBT, List.of(0, 1))`。当前 `src/main` 里没有 `optional()` 嵌套在集合里的用法，所以这是潜在缺陷而非线上故障，但它是公开 API 且被文档鼓励使用。

**建议修复**：让 `OptionalImpl.decode` 区分「值缺失」与「内层真的解码失败」，后者向上传播错误；集合组合子改用 `Collections.unmodifiableList(new ArrayList<>(...))`；`ListImpl`/`SetImpl` 的 encode 用 `Objects.equals(encodedItem, coder.createNull())` 判定并跳过。

**复现测试**：

```java
@Test
public void optionalInsideCollectionRoundTrips() {
    var codec = Codec.STRING.optional().list();
    // 现状：encode 在 NBT 下抛 IllegalArgumentException
    var encoded = assertDoesNotThrow(() -> codec.encode(Transcoder.NBT, Arrays.asList("a", null)));
    // 现状：decode 抛 NPE（List.copyOf 拒绝 null）
    assertDoesNotThrow(() -> codec.decode(Transcoder.NBT, ((Result.Ok<BinaryTag>) encoded).value()));
}
```

**判定**：【读代码确认】

---

## P2

### P2-1 `StaticRegistry#tagRegistry` 会把 `-1` 写进 `TagsPacket`
`src/main/java/net/minestom/server/registry/StaticRegistry.java:127`。tag 里的条目来自 JSON，持有的是 `RegistryKeyImpl` 而非 `StaticProtocolObject`，所以 `instanceof` 分支对数据加载的 tag 是死代码，实际走 `getId()`，而 `StaticRegistry.getId` 对不存在的 key 返回 `-1`（`:81`），`-1` 被当作 VarInt 注册表下标发给客户端。触发条件是 tag 引用了注册表里没有的条目（正是 #2639 的 feature-flag datapack 场景）。修复：过滤未知 key 并记一次日志。**【读代码确认】**（当前随包的 `data-26.2-rv3.jar` 里 13 个 tag 文件自洽，需要覆盖数据才会触发）

### P2-2 `DynamicRegistryImpl#tagRegistry` 对缺失条目拆箱 NPE，并与迭代器竞态
`src/main/java/net/minestom/server/registry/DynamicRegistryImpl.java:256`。`entries[i++] = keyToId.get(registryKey)` 自动拆箱，key 缺失即 NPE；`new int[tag.size()]` 先定长再取 `CopyOnWriteArraySet` 快照，中间的并发 `add` 会 AIOOBE、`remove` 会留下尾部 `0`（等于把注册表第 0 项悄悄加进 tag）。`Backed.add/remove` 会调 `invalidateTags()`，说明运行时改 tag 是被支持的流程。修复：先快照成 List 再定长，未知 key 跳过并记日志。**【读代码确认】**

### P2-3 `RegistryData.getTagValues` 递归无环检测、无 null 检查
`src/main/java/net/minestom/server/registry/RegistryData.java:193`。`Properties#section` 标注了 `@Nullable`（`PropertiesMap.section` 在 `:914-918` 对缺失 key 返回 null），紧接着 `section.getList("values")` 直接解引用 → NPE；`#a → #b → #a` 的循环引用会无限递归成 `StackOverflowError`。触发条件是工作目录下的自定义 `tags/<registry>.json`（`loadRegistryFile` 第二步，`:126-129`）。修复：null 检查 + 报出出错的 tag 名 + 递归携带 visited 集合。**【读代码确认】**

### P2-4 `RegistryData.load` 静默吞掉格式错误的 tag 文件
`src/main/java/net/minestom/server/registry/RegistryData.java:136`。`loadTags` 以 `required=false` 调用；Gson 的 `MalformedJsonException` 继承 `IOException`，被交给 exception manager 后方法返回**空** `Properties` —— 服务端带着零 tag 正常启动，所有依赖 tag 的行为（工具规则、`BlockPredicate`、配方原料）静默降级。而 `JsonReader` 抛出的 `IllegalStateException` 又完全不被捕获，同一类故障因失败模式不同而行为不同。修复：区分「文件不存在」与「文件存在但解析失败」。**【读代码确认】**

### P2-5 `RegistryTag` 的 codec 用 `HashSet` 解码，破坏 `Direct.equals` 的顺序契约
`src/main/java/net/minestom/server/registry/RegistryCodecs.java:142`。网络侧的读取器用 `ArrayList` 保序（`RegistryNetworkTypes.java:106`），而 `RegistryTagImpl.Direct` 的 `equals`/`hashCode`（`RegistryTagImpl.java:117-133`）是**顺序敏感**的，其注释明说目的就是让「从注册表构造的 tag」等于「从网络读回的 tag」。codec 侧的 `HashSet` 让这个保证失效。影响面是所有带 direct holder-set 的组件（`REPAIRABLE`、`Tool.Rule#blocks`、`Equippable#allowedEntities`、`BlocksAttacks#bypassedBy`、`DamageResistant#types`…）：NBT 存盘读回后与网络版本 `ItemStack.equals` 为 false。修复：换 `ArrayList` / `LinkedHashSet`。**【读代码确认】**，关联 PR #3288

### P2-6 `TagHandlerImpl.Node#getTag` 对 `Tag.Transient` 值 NPE
`src/main/java/net/minestom/server/tag/TagHandlerImpl.java:275-278`。`entry.updatedNbt()` 标注 `@Nullable`（`:370`），而 `Serializers.EMPTY`（`Serializers.java:52`，`Tag.Transient` 背后的序列化器）的 writer 恒返回 null，于是 `nbt.type()` 直接 NPE。同文件的两个兄弟调用点 `compound()`（`:293-294`）和 `copy()`（`:324`）都做了 null 检查，唯独这里没有。复现：`h.setTag(Tag.Transient("a"), "x"); h.getTag(Tag.Integer("a"));`。修复：`if (nbt == null) return tag.createDefault();`。**【读代码确认】**

### P2-7 `TagHandler#readableCopy` 可能永久发布陈旧快照
`src/main/java/net/minestom/server/tag/TagHandlerImpl.java:146`。`copy == null` 的判断在锁外，进锁后没有二次检查；同时 `setTag` 的快路径（`:68-87`）在**锁外**原地改 `Entry.value` 后才调 `node.invalidate()`（把 `copy` 置 null）。交错顺序「readableCopy 算出快照 S → setTag 改值并置 null → readableCopy 赋 `this.copy = S`」会让这次失效永久丢失。此外写侧的 `VarHandle.fullFence()` 打在 `traversePathWrite` **之前**（`:63`），而读侧的 fence 在读之前（`:43`）—— 写侧缺的是尾部 release fence，内存序意图是反的。修复：锁内二次检查 + 写侧在变更后发布。**【读代码确认】机制**／**【需要运行验证】竞态**

### P2-8 `RecipeManager` 的 `DeclareRecipesPacket` 缓存永不失效
`src/main/java/net/minestom/server/recipe/RecipeManager.java:38`。`declareRecipesPacket = new CachedPacket(this::createDeclareRecipesPacket)` 在全仓库只有两处引用（构造与 getter），`invalidate()` 一次都没调过，`addRecipe`(`:44/:48`) 与 `removeRecipe`(`:68`) 都不触发失效。第一个玩家登录（`Player.java:609`）触发计算后，之后新增/删除的配方对所有后续玩家都不可见。对照 `DynamicRegistryImpl` 每次 register/remove 都会 `vanillaRegistryDataPacket.invalidate()`。修复：两个方法末尾各加一次 `declareRecipesPacket.invalidate()`。同方法 `:131` 还在不持锁的情况下迭代 `Int2ObjectMaps.synchronize(...)` 包装器（fastutil 要求手动同步）。**【读代码确认】**

### P2-9 `TranscoderJsonImpl.getBoolean` 不校验 JSON 类型，`Codec.TRI_STATE` 在 JSON 下无法 round-trip
`src/main/java/net/minestom/server/codec/TranscoderJsonImpl.java:24-29`。该文件其余访问器都做了 `primitive.isNumber()` 之类的守卫，`getBoolean`（以及 `:121` 的 `getString`）没有。gson 的 `JsonPrimitive.getAsBoolean()` 内部退化成 `Boolean.parseBoolean(getAsString())`——**永不抛异常，非 `"true"` 一律返回 `false`**。于是 `Codec.TRI_STATE`（`CodecImpl.java:88-110`）在 JSON 下永远停在第一个 `getBoolean` 分支，`NOT_SET` 编码出的 `"default"` 会被解回 `TriState.FALSE`；NBT 下反而正常（`StringBinaryTag` 会让 `getBoolean` 报错，从而落到字符串分支）——纯粹的 NBT/JSON 不对称。线上可达路径：`EnvironmentAttributeTypes.TRI_STATE`（`world/attribute/EnvironmentAttributeTypes.java:21`）支撑的 `EYEBLOSSOM_OPEN` 默认值就是 `NOT_SET`，而维度类型经 `DynamicRegistryImpl.loadStaticJsonRegistry`（`:348-354`）用 `Transcoder.JSON` 加载。同理 `Codec.BOOLEAN` 在 JSON 下会把 `"yes"` / `5` 静默当成 `false`，影响 `ComponentCodecs.STYLE`（`:207-211`）的每一个可选布尔字段。修复：加 `p.isBoolean()` 守卫。**【读代码确认】**（gson 2.14.0 行为已实测）

### P2-10 `TranscoderJsonImpl.convertTo` 把所有 JSON 数字压成 double
`src/main/java/net/minestom/server/codec/TranscoderJsonImpl.java:259-260`：`case JsonPrimitive primitive when primitive.isNumber() -> new Result.Ok<>(coder.createDouble(primitive.getAsDouble()))`。对照 NBT 侧 `TranscoderNbtImpl.java:278-283` 按 tag 类型分派 `createByte/Short/Int/Long/Float/Double`，JSON 侧完全没有分派，`Transcoder.createNumber`（`Transcoder.java:148-158`）形同虚设。影响：任何 `Codec.RAW_VALUE` / `Codec.NBT` / `Codec.NBT_COMPOUND` 从 JSON 转到 NBT 都会丢失整数类型，超过 2^53 的整数还会丢精度。线上路径：`adventure/provider/MinestomDataComponentValueConverterProvider.java:39-40` 用 `Transcoder.JSON` 解物品组件，而 `CustomData.CODEC`（`item/component/CustomData.java:18`）是 `Codec.NBT_COMPOUND`，其 `decodeFromMap`（`CodecImpl.java:644-654`）逐条走 `RawValue.of(coder, nbt).convertTo(Transcoder.NBT)` —— 插件的 `custom_data` 里一个 long 会变成 `DoubleBinaryTag` 再发给客户端。修复：按是否有小数部分分派到 `createInt`/`createLong`。**【读代码确认】**

| # | 位置 | 问题 |
|---|---|---|
| P3-1 | `inventory/click/InventoryClickProcessor.java:131-134` | SHIFT_CLICK 的 `InventoryClickEvent` 只对**目标槽**触发、携带的是目标槽的**新内容**，源槽从不触发；且事件在 `setItemStack` **之后**才发出，无法用来追踪被移动的物品。对应 #2514 —— 仍存在（形态已变） |
| P3-2 | `inventory/Inventory.java:166`、`:189`、`:259` | 打开容器时点击玩家背包，`callClickEvent` 传的是窗口坐标 `slot`（如 32）而 inventory 传的是玩家背包，两者坐标系不一致；应传 `clickSlot` |
| P3-3 | `inventory/AbstractInventory.java:99-106` | 关闭背包时回填光标物品用的是 `addItemStack(item)` = `ALL_OR_NOTHING` 且**忽略返回值**，背包满或 `dropItem` 被取消时物品被静默销毁 |
| P3-4 | `listener/PlayerActionListener.java:175-183` | F 键换手只触发 `PlayerSwapItemEvent`，不经过 `InventoryPreClickEvent`；且事件被取消时不向客户端重同步，客户端会停留在已交换的预测状态。对应 #1932 —— 仍存在 |
| P3-5 | `network/NetworkBuffer.java:100-103` | `Enum(Class)` = `VAR_INT.transform(i -> values[i], ...)`，**无范围检查**。`ClientClickWindowPacket.clickType` 等客户端可控字段用越界 VarInt 即可让解码抛 AIOOBE（被 exception manager 吞掉 → 日志洪水）。同类：`ObjectArrayImpl.SingleThread.get` 对负下标 `index < array.length` 判定为真 |
| P3-6 | `item/enchant/LevelBasedValue.java:150` / `dialog/DialogInput.java:28` / `map/LargeFramebuffer.java:33-45` / `advancements/AdvancementTab.java:31` | 依次是：`Lookup.calc(0)` 因 `level < 0` 应为 `level <= 0` 而 `values.get(-1)` 越界；`validateKey` 用 Unicode 感知的 `isLetterOrDigit` 且放行空串，与自己报错信息里的 `[a-zA-Z0-9_]+` 矛盾；`LargeFramebuffer#preparePacket` 按 stride 128 填数组却把 `columns` 声明为 `width`，非 128 倍数的边缘图块渲染错位（对照 `Framebuffer.java:31` 的正确写法）；`AdvancementTab.PLAYER_TAB_MAP` 是静态 `HashMap`，却由每个实例各自的 `synchronized (this)` 监视器修改 |
| P3-7 | `codec/CodecImpl.java:154-161` | `TransformImpl.encode` 的 catch 块写 `new Result.Error<>(e.getMessage())`，而 `Result.Error` 的紧凑构造器是 `Objects.requireNonNull(message)`（`Result.java:39-41`）—— 异常若无 message 就在 catch 里再抛 NPE。5 行之上的 decode 路径（`:150`）写法是对的（带上了 `getClass().getSimpleName()`）。`Codec.orElse(other, mapper)`（`Codec.java:582-586`）刻意走这个 catch |
| P3-8 | `codec/CodecImpl.java:188-191`、`:228-231` | `ListImpl.encode` / `SetImpl.encode` 在超出 `maxSize` 时 `throw new IllegalArgumentException`，而同类的 `ListImpl.decode`(`:175`)、`SetImpl.decode`(`:215`)、`MapImpl.encode`(`:279-280`) 都返回 `Result.Error` —— 公共 API 契约不一致；`SetImpl` 的错误文案还写成了 "List size exceeds…" |
| P3-9 | `codec/CodecImpl.java:284-291` + `codec/TranscoderNbtImpl.java:217-222` | `MapImpl.encode` 用 `MapBuilder.put(D key, D value)` 重载，而 NBT/Java 实现对非字符串 key **静默丢弃**整条 entry 并返回 `Result.Ok(空 compound)`。JSON 侧却能正常编码，又是一处 NBT/JSON 不对称。当前 `src/main` 的 `mapValue` 调用点用的都是字符串形 key codec，属潜在缺陷 |

另外两条需要外部核对的**疑似**问题：`item/component/BlocksAttacks.java:40` 的 NBT 字段名写作 `disabled_sound`（访问器叫 `disableSound`，同组的 `disable_cooldown_scale` 是单数形式），vanilla 应为 `disable_sound`；`item/component/FireworkList.java:19` 的 `flight_duration` 是必填字段，但同文件 `EMPTY` 常量把 0 当默认值，vanilla 写法 `[fireworks={explosions:[...]}]` 会解码失败。两者都只在与 vanilla 数据互操作时暴露，Minestom↔Minestom round-trip 正常，因此没有被现有测试覆盖。**【疑似 / 需要运行验证】**

---

## ③ 上游 issue 复核表（限本次审计范围）

| Issue | 结论 | 依据（一句话） |
|---|---|---|
| #3027 Pickup all inventory transaction is improperly implemented | **仍存在** | `TransactionType.TAKE`(`TransactionType.java:85-112`) 单趟按槽位序扫描，没有 vanilla 的「先取非满堆」第一趟，见 P1-4 |
| #3118 ArgumentItemStack doesn't work with some components | **仍存在** | `ArgumentItemStack.java:142-149` 的 `readKey()` 不识别 `]`/`!`，且 `:66` 用的 `DataComponentMap.Builder` 没有 `remove()`，见 P1-8；PR #3119 未合入 |
| #3141 Missing/Incorrect Codec for NBT serialization of EntityMeta | **非 `codec` 层缺陷 / 是 `entity` 层的缺失特性** | `Metadata.Type`(`entity/Metadata.java:315-320`) 与整张 44 项 `TYPES` 表(`:216-269`)只由 `NetworkBuffer.Type<T>` + 默认值构成，`Metadata.java`/`MetadataDef*.java`/`MetadataHolder.java`/`metadata/EntityMeta.java` 里 grep 不到任何 `Codec`/`Transcoder`/`BinaryTag`；实体本身也没有持久化（`toNBT`/`fromNBT`/`writeEntity`/`readEntity` 在 `src/main/java` 零命中）。要修需给每个 `Metadata.Type` 配一个 `Codec<T>` 并补 vanilla NBT 键名，`codec/*` 不需要改动 |
| #2847 Shift clicking from player inventory to crafting inventory puts item in crafting slot | **仍存在** | `Inventory.java:219` 对所有 `InventoryType` 一律从 slot 0 扫到 `getInnerSize()`，`CRAFTING` 的 slot 0 就是结果槽，见 P1-5 |
| #2830 wrong slot on InventoryPreClickEvent when clicking in the Crafting Slots | **部分成立（API 语义）** | `Click.Drag` 没有 `slot` 分量，走 `Click#slot()` 默认值 -999（`Click.java:21-23`）；客户端把「按住拖过一格」发成 QUICK_CRAFT，于是同一个操作时而 37 时而 -999。行为符合当前 API 契约，但 `InventoryPreClickEvent.getSlot()` 对单槽拖拽应当能给出该槽 |
| #2546 Inventory drag itemstack error | **仍存在** | `InventoryClickProcessor.java:154-158` 的 `slot - inventory.getSize()` 无边界校验，堆栈形状完全吻合；且异常中断会造成物品复制，见 P0-1 |
| #2514 InventoryClickEvent does not correctly track the item moved with SHIFT_CLICK | **仍存在（形态已变）** | `InventoryClickProcessor.java:131-134` 只对目标槽发事件、内容是目标槽新值、且在写入之后发出，见 P3-1 |
| #1932 Swapping items into offhand bypasses inventory conditions | **仍存在** | `PlayerActionListener.java:175-183` 的换手只发 `PlayerSwapItemEvent`，不经过 `InventoryPreClickEvent`，见 P3-4 |
| #2682 Default Executors Breaking Suggestions | **仍存在** | `CommandParserImpl.java:242-244` 在遇到「携带 Success 的 error 结果」时短路，后续兄弟分支及其建议回调不再被访问，见 P1-9 |
| #1617 Argument callbacks are not working as expected / #1520 Command argument callback doesn't work | **仍存在（同根因）** | 同 P1-9；另 `NodeResult.callback`(`CommandParserImpl.java:479-480`) 是只写字段，回调只能经由 `Chain.suggestionCallback` 传出 |
| #1326 ConditionalSyntax only affect last argument in CommandNode | **仍存在** | `GraphImpl.java:186-187` 只在语法最后一个参数上挂 `ExecutionImpl`，`GraphConverter.java:44-47` 因此只能过滤最后一个节点，见 P1-10 |
| #2639 tag do not include data from feature flag datapacks | **仍存在（且后果被放大）** | tag 加载完全不校验条目是否在注册表内（`RegistryData.getTagValues`），未知条目在静态注册表里变成 `-1` 上线（P2-1）、在动态注册表里拆箱 NPE（P2-2） |
| #3004 Rename ArgumentResourceLocation to ArgumentIdentifier or ArgumentKey | **非 bug** | 纯命名重构诉求，PR #3316 待合 |
| #466 Tab-completer ignores trailing spaces | **仍存在（缓解）** | `TabCompleteListener.java:32-36` 用追加 `'\00'` 的方式绕过尾随空格，正因如此才把空 token 喂给了参数解析器 —— 见 P3 里 `ArgumentTime` / `ArgumentRelativeVec` 的空串崩溃 |
| #2669 / #2352 / #2152 等 entity 相关 | **不在本次范围** | — |

补充：`ArgumentRelativeVec.java:54`（`"1  2".split(" ")` → 中间空串 → `"".charAt(0)`）与 `ArgumentTime.java:41`（`"".charAt(-1)`）都会抛非受检异常逃逸出 `CommandParserImpl.parseArgument:544/554` 的 `ArgumentSyntaxException` 捕获，命令静默失败并刷日志；`utils/StringUtils.java:155` 的 `\uZZZZ` 会抛 `NumberFormatException`，且 `Character.toChars` 允许产生孤立代理项。三者可由任意玩家用一个多打的空格触发，是 P2 级健壮性问题。

---

## ④ 未覆盖的区域与原因

1. **`codec/*` 里已确认「无问题」的部分**（记录下来以免重复劳动）：`TranscoderCrc32Impl` 的确定性没问题 —— `createMap().build()`(`:139-147`) 按 `(无符号 keyHash, 无符号 valueHash)` 显式排序，`HashMap` 的迭代顺序泄漏不出来，字节序统一小端，`if (value != EMPTY)`(`:128`) 是拆箱数值比较而非引用比较，tag 常量与 Mojang `HashOps` 布局一致。NBT 的异构 list / 空 list / `byte[]`·`int[]`·`long[]` 特例处理对称。`StructCodec` 的字段顺序、`optional(default)` 不回写、absent-vs-显式-null 的统一（`StructCodec.java:3423-3429`）都正确，`INLINE` 的双 `build()` 也是安全的。**未做**：`Codec.Recursive` 之外的栈深度实测、`Transcoder.JAVA` 的完整审查（它在 `src/main` 里只有 `RegistryData.java:546` 一个只读用途，但 `createMap().put` 不丢 null、`getMap().keys()` 只看第一个 key 就可能返回 `List.of()` 这两点值得后续跟进）。
2. **`item/component/*` 的逐字段协议核对**：已对 39 个组件做了「网络模板 vs struct codec」的成对比对（字段数、顺序、可选性、默认值），结论是除本报告列出的三处外一致。但**与真实 vanilla 1.21.x 字段名的比对**没有做（没有可信的本地 vanilla 数据源），因此 `disabled_sound` / `flight_duration` / `show_decoration` 三条只能标为疑似。
3. **`scoreboard/*` 与 `advancements/*`**：按任务约定只做快速扫描，只捞到 `AdvancementTab.java:31` 一条。`scoreboard` 未发现明显越界或协议字段错误，但也没有做逐包核对（PR #3280 正在重写这一块）。
4. **运行时验证一律没有做**：任务禁止跑 Gradle，所有结论均基于阅读 + `git blame`。P0-1、P0-2、P0-4、P1-9 四条我明确标注了需要跑测试确认端到端行为。报告里的 JUnit 片段是按 `testing/` 现有惯例（`@EnvTest` + `player.addPacketToQueue(...)` + `player.interpretPacketQueue()` + `ClickUtils.clickPacket`）写的，但**未编译、未运行**。
5. **`InventoryType.getWindowType()` 返回 `ordinal()`**（`InventoryType.java:40-42`）我逐项核对过与 1.21.x menu 注册表顺序一致，因此没有列为 bug —— 但这是一个只要 Mojang 插入新 menu 类型就会静默错位的脆弱假设，值得改成显式 id。
6. **测试覆盖缺口（顺带记录）**：`src/test/java/net/minestom/server/inventory/click/integration/` 下只有 `LeftClick` / `RightClick` / `HeldClick` 三个集成测试 —— shift-click、双击、拖拽这三种最复杂、也是本次问题最集中的点击模式**完全没有集成测试**。`src/test/java/net/minestom/server/codec/` 下同样缺口明显：`Codec.TRI_STATE` 在整个 `src/test` 零出现，`optional()` 嵌套进 `list()`/`set()`/`mapValue()` 没有任何用例，`TranscoderCrc32Impl` 连测试文件都没有，`Codec.Recursive` 最深的用例只有 `PotionContentsTest:33-35` 的两层 —— P0-4、P2-9、P1-14 正好全落在这些空白里。

---

## ⑤ 参考链接

- 上游仓库：https://github.com/Minestom/Minestom
- #3027 Pickup all inventory transaction is improperly implemented — https://github.com/Minestom/Minestom/issues/3027
- #3118 ArgumentItemStack doesn't work with some components — https://github.com/Minestom/Minestom/issues/3118 ／ PR #3119 https://github.com/Minestom/Minestom/pull/3119
- #3141 Missing/Incorrect Codec for NBT serialization of EntityMeta — https://github.com/Minestom/Minestom/issues/3141
- #2847 Shift clicking from player inventory to crafting inventory — https://github.com/Minestom/Minestom/issues/2847
- #2830 wrong slot on InventoryPreClickEvent — https://github.com/Minestom/Minestom/issues/2830
- #2546 Inventory drag itemstack error — https://github.com/Minestom/Minestom/issues/2546
- #2514 InventoryClickEvent does not correctly track SHIFT_CLICK — https://github.com/Minestom/Minestom/issues/2514
- #1932 Swapping items into offhand bypasses inventory conditions — https://github.com/Minestom/Minestom/issues/1932
- #2682 Default Executors Breaking Suggestions — https://github.com/Minestom/Minestom/issues/2682
- #1326 ConditionalSyntax only affect last argument — https://github.com/Minestom/Minestom/issues/1326
- #2639 tag do not include data from feature flag datapacks — https://github.com/Minestom/Minestom/issues/2639
- PR #3129 fix: propagate suggestion callback when argument parsing fails — https://github.com/Minestom/Minestom/pull/3129
- PR #3058 Fix/command suggestions — https://github.com/Minestom/Minestom/pull/3058
- PR #3288 Match vanilla/Paper registry tag ordering — https://github.com/Minestom/Minestom/pull/3288
- PR #3249 skip empty equipment slots (like vanilla) — https://github.com/Minestom/Minestom/pull/3249
- PR #3347 feat: network error handling behind server flags — https://github.com/Minestom/Minestom/pull/3347
- #3320 Packet id isn't recognized and Memory Segment IndexOutOfBoundsException — https://github.com/Minestom/Minestom/issues/3320
- #466 Tab-completer ignores trailing spaces — https://github.com/Minestom/Minestom/issues/466
- 协议参考（Click Container / Set Container Property / Set Equipment）— https://minecraft.wiki/w/Minecraft_Wiki:Projects/wiki.vg_merge/Protocol
