# 05 物品 / 背包 / 命令 / 编解码 —— 修复去向表

- 分支：`fix/item`，worktree `/Users/bug/Documents/Projects/Minecraft/.worktrees/item`
- 基线：`fork/main`（审计时 2488 测试全绿）
- 全量结果：`:test` **2543 个测试，0 失败**（17 skipped），连续 3 次 `--rerun-tasks` 全绿
- 提交数：39（每条发现一个 commit，无 push）

---

## ① P0

| 编号 | 去向 | commit | 测试 |
|---|---|---|---|
| P0-1 拖拽越界导致物品复制 | **已修** | `7dea186942` | `DragClickIntegrationTest.outOfBoundsDragIsRejectedEntirely`、`.staleDragSlotsMustNotDuplicateItems` |
| P0-2 共享 Inventory 点击无锁读-改-写 | **已修** | `f38263b9e2` | `ConcurrentClickIntegrationTest.concurrentLeftClickMustNotDuplicate` |
| P0-3 嵌套物品把 resolved map 当 patch 发出 | **已修** | `6507294e3e` | `ItemStackTemplateTest.nestedItemKeepsComponentRemovalOverNetwork`、`.nestedItemMatchesTheNbtPath` |
| P0-4 递归网络类型无深度上限 | **移交网络同事**（`NetworkBufferTypeImpl.RecursiveType` / `CodecImpl.RecursiveImpl` 均在 `network/**` 与网络层错误处理范围内） | — | — |

**P0-1 实现**：`InventoryClickProcessor#dragging` 在写入任何槽位之前先把全部槽位映射到目标背包并校验边界，任一越界整笔返回 `null`（`Inventory#dragging` 随即 `updateAll` 并返回 false）。

**P0-2 实现**：`AbstractInventory` 覆写 `handleClick`，把整笔点击放进 `synchronized (this)`，与 `setItemStack` / `processItemStack` 同一把锁；`UNSAFE_itemInsert` 的数组写改为 `ITEM_UPDATER.setVolatile`，与 `getItemStack` 的 `getVolatile` 配对。
锁序为「容器 → 玩家背包」，全链路一致。已知残留风险：`PlayerInventory.UNSAFE_itemInsert` 在玩家背包锁内派发 `EntityEquipEvent`，监听器若在其中反向去取容器锁，理论上构成反序（该路径在本次改动前即存在，未新增第二个反序点）。

---

## ② P1

| 编号 | 去向 | commit | 测试 |
|---|---|---|---|
| P1-1 `WindowListener` 不校验 `windowId` | **已修** | `681b90c0aa` | `ClickWindowValidationIntegrationTest.clickWithStaleWindowIdMustBeIgnored` |
| P1-2 `CloseWindow(0)` 泄漏容器 viewer | **已修**（`Player.java` 两处，见 ⑥） | `9b56636d55` | `InventoryCloseStateIntegrationTest.clientCloseWindowZeroMustNotLeakOpenContainer` |
| P1-3 `didCloseInventory` 取消机制失效 | **已修**（改在 `WindowListener`，见下） | `2bf8005939` | `ClickWindowValidationIntegrationTest.closingInventoryInPreClickCancelsTheClick` |
| P1-4 双击未做两趟扫描（#3027） | **已修**（扫描顺序另计，见 ⑤） | `cc7696b8a3` | `DoubleClickIntegrationTest.doubleClickPrefersPartialStacks` |
| P1-5 shift-click 塞进结果/输出槽（#2847） | **已修** | `b40523fc61` | `ShiftClickIntegrationTest.shiftClickIntoCraftingMustSkipResultSlot`、`.shiftClickIntoFurnaceMustSkipOutputSlot` |
| P1-6 shift-click 两趟互斥而非串联 | **已修** | `c937eee8b2` | `ShiftClickIntegrationTest.shiftClickOutOfContainerFallsBackToTheMainInventory`、`.shiftClickOutOfCraftingGridFallsBackToTheHotbar` |
| P1-7 未声明网络类型 / codec 的组件 NPE | **已修** | `e2cb23202b` | `UnsyncedComponentTest`（3 个用例） |
| P1-8 `ArgumentItemStack`（#3118） | **已修** | `aa4bef2577` | `ArgumentItemStackTest`（4 个用例） |
| P1-9 兄弟分支被提前短路（#2682/#1617/#1520） | **已修** | `8af89c7a91` | `CommandSuggestionIntegrationTest.suggestionFromSiblingSyntax` |
| P1-10 语法条件只挂最后一个参数（#1326） | **已修** | `db36c17770` | `ConditionalSyntaxFilteringIntegrationTest`（4 个用例） |
| P1-11 `CommandManager` 未加锁 + 大小写不一致 | **已修** | `d16340557d` | `CommandManagerTest.testMixedCaseCommandRegistration` |
| P1-12 `DynamicRegistry#remove` 不重建 `keyToId` | **已修** | `fad09962cc` | `RegistryIntegrationTest.removeRemapsTheIdsOfLaterEntries` |
| P1-13 未知 click/hover action 抛异常 | **已修** | `eb6f727499` | `ComponentCodecsTest.unknownClickEventActionIsAnError`、`.unknownHoverEventActionIsAnError` |
| P1-14 `optional()` 吞错误 + 集合 NPE / 编码抛异常 | **已修** | `3b059b821e` | `CodecTest.optionalInsideListRoundTrips`、`.optionalInsideSetRoundTrips`、`.optionalDoesNotSwallowInnerDecodeErrors`、`.optionalFallsThroughToOrElse` |

**P1-2 语义决定**：容器打开时收到 `CloseWindow(0)` 现在**什么都不做**（原样保留容器与 viewer），而不是关闭容器。理由：Minestom 的 `closeInventory` 本来就有 windowId 校验，客户端用错误 id 不应该能关掉容器；这样也消除了「引用被丢弃但 viewer 没被移除」的不一致。合法的关闭（客户端发容器自己的 id、或服务端 `closeInventory()`）行为不变。P0-1 依赖的拖拽缓存也因此能在下次 `openInventory` 时经 `removeViewer` 正常清掉。

**P1-3 实现位置**：审计建议的两种修法里，改 `Player.java:1950` 的复位会超出主管给的「只动 1934/1946 两处」授权，因此改用第二种：`WindowListener` 在事件前后比较 `player.getOpenInventory()` 的身份，变化即取消点击。
**已知覆盖缺口**：玩家没开容器、只开自己背包时调 `closeInventory()`，`getOpenInventory()` 前后都是 null，检测不到（此时也没有任何 viewer / 状态变化可观测）。相比修复前「该分支永远不成立」是严格改进。

**P1-9 实现**：`NodeResult` 增加 `terminal` 分量；只携带「最后一个成功节点」的错误占位标记为非 terminal，`parseNode` 的短路只对真正走到底的成功生效。附带把 trailing-data 路径的 `chain.suggestionCallback` 赋值加上 null 保护（与 `:208` 对齐），避免清掉上一个参数刚装上的回调。

**P1-10 实现**：中间节点获得一个只带 syntax condition、不带 executor/condition 的 `ExecutionImpl`；`last` 判定改用下标；节点被多条语法共用时按 **OR 合并谓词**、保留第一个非空 executor —— 保证不会比修复前更严格（`ConditionalSyntaxFilteringIntegrationTest.sharedPrefixKeepsTheAllowedSyntax` / `.sharedPrefixKeepsTheShorterSyntaxExecutable` 就是这两条回归护栏）。

---

## ③ P2

| 编号 | 去向 | commit | 测试 |
|---|---|---|---|
| P2-1 `StaticRegistry#tagRegistry` 写出 -1 | **已修** | `5baa682476` | 无独立用例（需要覆盖随包数据才能构造；改动为「id < 0 跳过 + 告警」，由全量回归保证不回退） |
| P2-2 `DynamicRegistryImpl#tagRegistry` 拆箱 NPE + 迭代竞态 | **已修** | `4ad7bf4300` | 同上（先快照再定长 + 跳过未知 key） |
| P2-3 `getTagValues` 无环检测、无 null 检查 | **已修** | `012de65e52` | 无独立用例（需在工作目录放自定义 `tags/*.json`） |
| P2-4 `RegistryData.load` 静默吞掉格式错误 | **已修** | `1dff3223cf` | 无独立用例（区分「文件不存在」与「存在但解析失败」，后者抛出） |
| P2-5 `RegistryTag` codec 用 `HashSet` 破坏顺序契约 | **已修** | `612cd9199e` | `RegistryTagCodecTest.directTagCodecPreservesEntryOrder` |
| P2-6 `Tag.Transient` 值读成别的类型 NPE | **已修** | `6a77691d2d` | `TagTransientTest.readingTransientValueThroughAnotherTagType` |
| P2-7 `readableCopy` 永久发布陈旧快照 | **已修** | `1fc4fde044` | 无确定性用例（竞态）；实现用失效计数：锁内二次检查 + 快照期间发生失效则不缓存 |
| P2-8 `RecipeManager` 缓存永不失效 | **已修** | `ab09d086cf` | `RecipeManagerIntegrationTest.addingARecipeInvalidatesTheCachedPacket` |
| P2-9 `TranscoderJsonImpl.getBoolean/getString` 不校验类型 | **已修** | `55ae1865b2` | `TranscoderTest.jsonBooleanAccessorRejectsNonBooleans`、`.triStateRoundTripsInJson` |
| P2-10 `convertTo` 把所有 JSON 数字压成 double | **已修** | `9901ae9d2a` | `TranscoderTest.jsonToNbtKeepsIntegerTypes` |

P2-1~P2-4 四条都是「触发需要外部数据」的健壮性修复，没有为它们构造数据夹具，改动本身很小且全量测试无回退；如果主管认为需要夹具，可以后补一个临时 `tags/` 目录的用例。

---

## ④ P3 与尾部健壮性

| 编号 | 去向 | commit | 测试 |
|---|---|---|---|
| P3-1 SHIFT_CLICK 的 `InventoryClickEvent` 语义（#2514） | **延后**：修它必然改变公开事件的语义（对哪个槽发、发什么内容、写入前还是写入后），会打断现有监听器，且审计也没给出目标语义。需要一次 API 决策，不适合夹在 bug 修复里 | — | — |
| P3-2 `callClickEvent` 传窗口坐标 | **已修** | `54bd2f41fa` | 由现有 `LeftClick`/`RightClick`/`HeldClick` 集成测试覆盖 |
| P3-3 关背包回填光标静默销毁物品 | **已修** | `4b3f03b7bf` | `CursorItemOnCloseIntegrationTest`（2 个用例） |
| P3-4 F 键换手（#1932） | **部分已修**：取消时向客户端重同步已修（`3629574c7c`，`SwapItemHandIntegrationTest.cancelledSwapResynchronisesTheClient`）。**「改走 `InventoryPreClickEvent`」延后**：`PlayerSwapItemEvent` 本身可取消，插件并非无法拦截；把 F 键并进点击管线是事件模型统一的 API 决策 | `3629574c7c` | `SwapItemHandIntegrationTest.cancelledSwapResynchronisesTheClient` |
| P3-5 `NetworkBuffer.Enum(Class)` 无范围检查 | **移交网络同事**（`network/NetworkBuffer.java`，不在本人文件归属内） | — | — |
| P3-6a `LevelBasedValue.Lookup.calc` 越界 | **已修** | `0902434f42` | 无独立用例（一字符边界修正） |
| P3-6b `DialogInput.validateKey` 与报错信息矛盾 | **已修** | `ae930c20b6` | 无独立用例 |
| P3-6c `LargeFramebuffer#preparePacket` stride 错误 | **已修** | `d7286192e4` | 无独立用例 |
| P3-6d `AdvancementTab.PLAYER_TAB_MAP` 并发 | **已修** | `7e322981c7` | 无独立用例 |
| P3-7 `TransformImpl.encode` catch 内再抛 NPE | **已修** | `f92b6a8e88` | `CodecTest.transformEncodeFailureWithoutMessageIsAnError` |
| P3-8 集合 encode 超限 throw 而非 `Result.Error` | **已修** | `06ffebce0d` | `CodecTest.collectionEncodeOverMaxSizeIsAnError` |
| P3-9 `MapImpl.encode` 非字符串 key 静默丢弃 | **已修** | `687220e7a9` | `CodecTest.mapWithNonStringKeysIsAnError` |
| 尾部：`ArgumentTime` 空串、`ArgumentRelativeVec` 空 token、`StringUtils` `\uZZZZ` | **已修** | `655a476453` | `ArgumentRobustnessTest`（3 个用例） |

`StringUtils` 里「`Character.toChars` 允许产生孤立代理项」一条**跳过**：孤立代理项在 Java `String` 里是可表示的，且没有可观测的故障路径，改它属于收紧语义而非修 bug。

---

## ⑤ 报告里标「疑似」的三条 component 字段名

已对照随包 `data-26.2-rv3.jar`（`net/minestom/data/item.json` 的 Mojang 生成数据）与 minecraft.wiki 的 Data component format / Dialog 页逐条核实：

| 疑似项 | 结论 | 去向 |
|---|---|---|
| `BlocksAttacks.java:40` 的 `disabled_sound` | **核实后非 bug**。vanilla 自己就是不对称的：`disable_cooldown_scale`（单数）+ `disabled_sound`（过去式）。shield 的生成数据里写的正是 `"disabled_sound": "minecraft:item.shield.break"`，Minestom 现状与 vanilla 一致 | 跳过 |
| `FireworkList.java:19` 的 `flight_duration` | **成立，已修**。vanilla 该字段可选、缺省 **1**（不是审计推测的 0）。改成解码可选（默认 1）、编码照写，与 `ItemStack.CODEC` 对 `count` 的处理同构 | `65f68301e5`，`FireworkListTest.flightDurationIsOptional`、`.flightDurationRoundTrips` |
| `show_decoration` | **核实后非 bug**，且位置也不在 `item/component/`：它属于 `dialog/DialogBody.java` 的 `minecraft:item` dialog body，字段名、类型、默认值（true）与 vanilla 完全一致 | 跳过 |

---

## ⑥ 上游 issue 复核（本次范围内）

| Issue | 去向 |
|---|---|
| #2546 Inventory drag itemstack error | 已修（P0-1） |
| #3027 Pickup all improperly implemented | 已修（P1-4） |
| #2847 Shift click into crafting slot | 已修（P1-5） |
| #3118 ArgumentItemStack components | 已修（P1-8） |
| #2682 / #1617 / #1520 建议回调 | 已修（P1-9） |
| #1326 ConditionalSyntax only last argument | 已修（P1-10） |
| #2639 feature-flag datapack tags | 已修后果面（P2-1 / P2-2 / P2-3）；「加载 feature-flag datapack」本身是缺失特性，不在本次范围 |
| #2514 SHIFT_CLICK 事件追踪 | 延后（P3-1，API 决策） |
| #1932 换手绕过背包条件 | 部分已修（P3-4） |
| #2830 拖拽的 `getSlot()` 返回 -999 | **延后**：`Click.Drag` 没有 `slot` 分量是当前 sealed union 的既定契约，给单槽拖拽补 `slot()` 属于 API 扩展 |
| #3004 ArgumentResourceLocation 改名 | **非 bug**（纯命名重构诉求，上游 PR #3316 待合） |
| #466 Tab-completer 尾随空格 | 部分：由此引发的崩溃面已修（`ArgumentRobustnessTest`）；`'\00'` 占位这个机制本身没动 |
| #3141 EntityMeta 的 NBT codec | **移交实体同事**。已复核：`Metadata.Type` 与 44 项 `TYPES` 表只由 `NetworkBuffer.Type<T>` + 默认值构成，`Metadata*/MetadataHolder/EntityMeta` 里 grep 不到任何 `Codec`/`Transcoder`/`BinaryTag`；实体本身也没有持久化。这是**实体层的缺失特性，不是 codec 层缺陷**，`codec/*` 无需改动 |
| #3320 / PR #3347 网络层错误处理 | 移交网络同事（与 P0-4 同批） |

---

## ⑦ 顺带补齐的测试空白（报告第 ④ 节第 6 点）

审计指出 `inventory/click/integration/` 下只有 Left / Right / HeldClick 三个集成测试，shift-click、双击、拖拽三种最复杂的点击模式零覆盖。现已补齐：

- 拖拽：`DragClickIntegrationTest`（5 个用例：越界拒绝、跨背包陈旧缓存、左拖平分、右拖每格 1 个、跨容器与玩家背包）
- 双击：`DoubleClickIntegrationTest`（2 个用例：优先散堆、从容器收集）
- shift-click：`ShiftClickIntegrationTest`（5 个用例：跳过合成结果槽 / 熔炉产出槽、箱子首个空槽、容器→主背包串联、合成栏→热键栏串联）
- 并发：`ConcurrentClickIntegrationTest`（共享容器双人点击守恒，300 轮）

codec 侧同样补了审计点名的空白：`Codec.TRI_STATE`（`TranscoderTest`）、`optional()` 嵌套进 `list()`/`set()`（`CodecTest`）。

---

## ⑧ 需要主管注意的合并点

1. **`src/main/java/net/minestom/server/entity/Player.java` 有两处小改动**（commit `9b56636d55`，均在 `closeInventory(boolean, byte)` 内，属同一个 hunk）：
   - `:1934` 关闭目标的解析改为「先看 `getOpenInventory()`」；
   - `:1946` `this.openInventory = null` 挪进 `openInventory != inventory` 分支。
   该文件归实体同事，合并时请优先看这一处。除此之外没有碰 `entity/**`。
2. **`src/main/java/net/minestom/server/listener/PlayerActionListener.java` 有一行改动**（commit `3629574c7c`）：`swapItemHand` 在事件被取消时补一次 `player.getInventory().update()`。该文件不在禁止清单里，但与实体行为相邻。
3. **`src/main/java/net/minestom/server/utils/StringUtils.java` 有一处局部改动**（commit `655a476453`）：`unescapeJavaString` 的 `\u` 分支加 `NumberFormatException` 兜底。
4. **本分支基于较旧的 `fork/main`**（写这份文档时 `fork/main` 已经比 `fix/item` 的基线多出 57 个提交，含其他同事已合入的 thread / instance / network 工作）。本人改动相对 merge-base 是 55 个文件、+1683/-175，与那 57 个提交没有文件重叠（除上面第 1、2 点）。
5. **一个与本次改动无关的既有测试脆弱点**（`fork/main` 上同样复现，已验证）：`CommandPacketFilteringTest` 的静态字段 `new Player(null, ...)` 需要 `MinecraftServer.serverProcess` 已被某个先跑的 `@EnvTest` 初始化，单独跑这个类必然 `ExceptionInInitializerError`，多类一起跑则取决于类执行顺序。因此本次 P1-10 的用例没有加进该类，而是新建了自足的 `ConditionalSyntaxFilteringIntegrationTest`（`@EnvTest`）。建议后续单独治理。

---

## ⑨ 环境回收

- 本次验证没有新建模拟器 / AVD / 镜像 tag / 容器 / 卷。
- 每轮测试后均执行 `rm -rf "$TMPDIR"/minestom-test-world-* "$TMPDIR"/minestom-light-parity-test*`，收尾时已确认无残留。
- `git status` 干净，无临时文件。
