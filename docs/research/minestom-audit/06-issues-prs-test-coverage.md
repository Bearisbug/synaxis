# Minestom 质量工程横向核查：已知 bug 矩阵 + 测试覆盖 + CI 门禁

> 核查对象：`Minestom/Minestom`，本地 fork `HEAD = 16842f1954`（2026-09-04）。
> 核查日期：2026-09-09。核查方式：**只读**（未修改仓库，未运行 Gradle，未切分支）。
> 视角：issue 跟踪器 / PR 队列 / 测试覆盖 / CI 门禁的横向视角，不重复子系统读码找 bug。

---

## ① TL;DR

1. **上游的 bug 修复能力其实不差，差的是"进队列后被看见"**：近 12 个月合并了 255 个 PR，PR 合并中位耗时只有 **2 天**；同期关闭的 bug issue 中位存活 **14 天**。但这个"快"高度集中在两名核心维护者（kermandev 84 个、TheMode 43 个 = 合并量的 50%）自己开的 PR 上，社区 PR 大量沉底：**80 个 open PR 中 47 个已冲突（CONFLICTING），中位闲置数月，最老的一个 1628 天**。
2. **68 个 open bug 是一个被放弃的池子，不是一个待办清单**：中位年龄 **626 天**，**60/68（88%）超过 6 个月没有任何人类评论**，20 个从开单起零评论。2025-01 有过一次由社区成员发起的"能给个复现吗"批量分诊（15 条），此后再无回应——这些 issue 事实上是死信。
3. **其中 8 条经代码/commit 核对属于"疑似已修复但没人关单"**（含 #2668 / #2682 / #1918 / #2061 / #3231 / #2527 及 3 条 palette 崩溃），另有约 6 条属于非 bug 或 API 已被删除而失效。真正需要我们扛的是剩下的 ~54 条，且**寻路、ViewEngine/passenger、batch、碰撞箱、命令 argument callback 五族问题上游 3 年没动过**。
4. **测试覆盖极度不均**：全库 247 个测试文件 / 1461 个 `@Test`。协议编解码是最强的一块（**238 个包类中 232 个有 write→read 往返测试**）；但 `entity/metadata`（202 类，9% 被测试引用）、`listener`（42 类，14%）、`adventure`（29 类，7%）、`scoreboard`（7 类，14%）、`crypto` 的签名校验、`map` 包（8 类，0%）几乎是裸奔。更关键的是 `testing/` 框架的**结构性盲区**：握手/登录/压缩/加密/代理 0 集成覆盖，`trackIncoming` 断言的是 Java 对象而不是字节，多线程竞态被 `DISPATCHER_THREADS=1` + 每 tick barrier 结构性压掉。
5. **CI 门禁看着现代实则单薄**：`./gradlew check` + Javadoc + GraalVM nativeTest + 二进制兼容性四道门，errorprone 启用了 100+ 项检查且 `-Werror`；但 **jcstress 并发测试和 JMH 基准完全不在 CI 里跑**（`.github/` 里零引用），`nativeTest` 带 `continue-on-error: true` 且有自动 fallback 重跑，唯一真正阻塞的只有 JVM `check`。

---

## ② Open bug issue 矩阵（68 条）

**列说明**
- **严重度（用户视角）**：`崩溃` = 服务端异常/死锁/客户端被踢或掉线；`数据错` = 世界/背包/内存状态错误或泄漏、客户端-服务端不同步；`功能错` = 功能行为与原版/预期不符；`体验` = API 人体工学、命名、日志噪音、文档。
- **最后人工评论**：取 issue 最后一条 comment 的日期（无评论则取创建日）。**不用 `updatedAt`**——很多 issue 的 `updatedAt` 只是打标签动作，会高估活跃度。
- **复现**：issue 模板 "Steps to reproduce" 有实质内容或正文含可运行代码/视频 = 有。
- **状态判断**：`活跃`（6 个月内有人类回复）/ `陈旧`（>6 个月无回应）/ `疑似已修复`（附 commit 依据）/ `非bug或已失效`。

### 网络 / 协议 / 编解码（11 条）

| # | 标题 | 严重度 | 最后人工评论 | 复现 | 关联 PR | 状态判断 |
|---|---|---|---|---|---|---|
| 3320 | Packet id 0x# isn't recognized + MemorySegment IndexOutOfBounds | 崩溃(日志风暴) | 2026-08-03 | 无（仅堆栈） | #3347 | 活跃；共识是端口扫描器触发，诉求是"别抛全栈异常" |
| 3057 | switchEntityType 后设 entity meta 触发 Network Protocol Error | 崩溃(踢人) | 2026-03-06 | 有 | — | 陈旧；与 #2152 同根（metadata 类型未随 EntityType 切换） |
| 3050 | Client times out in this scenario | 崩溃(超时) | 2026-03-02 | 有 | — | 陈旧；他人复现失败，报告者自测调大 `SOCKET_SEND_BUFFER_SIZE` 可绕过 → 疑为写背压 |
| 3141 | Missing/Incorrect Codec for NBT serialization of EntityMeta | 数据错 | 2026-05-11 | 无 | — | 陈旧；由 #3133 派生的审计任务，`needs investigation` |
| 2639 | tag 不含 feature flag datapack 的数据 | 功能错 | 2025-01-31 | 有 | — | 陈旧 |
| 2334 | 相对传送 ACK 导致高延迟下玩家位置冻结 | 功能错 | 2025-08-16 | 有（2 段视频） | **#3048** | 陈旧但有可用 PR；维护者已明说"大概不会修，请自行覆盖 listener" |
| 2314 | 无正当手段控制 tab list 可见性 | 功能缺失 | 2024-08-16 | 有 | #2980 / #2113 | 陈旧；两个 PR 都停摆 |
| 2241 | Handshake packet reading 报错 | 体验(日志噪音) | 2025-01-19 | 无 | — | 陈旧；与 #3320 同根 |
| 2169 | Kick with reason: Network Protocol Error | 崩溃 | 2025-01-19 | 无 | — | 陈旧；线索指向客户端/版本问题，非 Minestom |
| 246 | Selector / score 组件未在服务端渲染 | 功能错 | 2021-04-15 | 无 | — | 极陈旧（1978 天）；`confirmed` 标签但 5 年无人动 |
| 221 | Javadoc 重复注解 | 体验 | 2021-05-24 | 有 | — | **非 Minestom bug**：报告者自己定位为 JDK bug 8175533 |

### instance / 世界 / 区块 / palette（16 条）

| # | 标题 | 严重度 | 最后人工评论 | 复现 | 关联 PR | 状态判断 |
|---|---|---|---|---|---|---|
| 3158 | Lighting（一组光照设计缺陷） | 数据错 | 2026-05-12 | 部分（设计层描述） | #3295 部分相关 | 陈旧；作者列了 3 个结构性问题（光照 section 不覆盖世界上下各一段、无玩家时不重算、闪烁） |
| 3083 | PlayerChunkUnloadEvent 不对所有区块触发 | 功能错 | 2026-03-29 | 有 | 待 #2654 | 陈旧；维护者态度是"这个事件本就不该用来管卸载" |
| 3054 | GroundNodeGenerator 访问未加载区块 NPE | 崩溃 | 2026-03-04 | 部分 | **#3056** | 陈旧；PR 存在但已 CONFLICTING |
| 3021 | 视距边界外设方块，客户端收不到 | 数据错(不同步) | 2026-01-22 | 有 | — | 陈旧 |
| 2900 | 玩家 spawn 期间注销 instance 抛异常 | 崩溃 | 2025-08-30 | 无 | — | 陈旧；零评论 |
| 2766 | 空 `AbsoluteBlockBatch` 的完成回调永不触发 | 功能错 | 2025-06-17 | 有 | #2982 | **已核实仍存在**：`AbsoluteBlockBatch.java` 中回调只在按 chunk 遍历的循环体内触发，空 map → 循环不执行 |
| 2668 | Incorrect palette serialization（linear palette） | 数据错 | 2026-06-07 | 无 | — | **疑似已修复**：`219f16bd4f fix: linear palette deserialization bug (#3097)`（2026-04-11）并在 `PaletteTest` 加了回归用例；报告者本人 2026-06-07 也确认"某个时间点被修了" |
| 2638 | `InstanceContainer#retrieveChunk` 竞态导致区块生成两次 | 数据错+泄漏 | 2025-01-31 | 有（含行号分析） | 待 #2654 | 陈旧；**分析质量很高但零回应**，同时报告了 ThreadDispatcher 分区泄漏 |
| 2598 | 方块属性变更未下发客户端 | 数据错(不同步) | 2025-01-15 | 有 | #2599 | 陈旧；有一个 8 行的 PR 停在那 601 天 |
| 2547 | `Instance#setBlock` 抛 ArrayIndexOutOfBounds | 崩溃 | 2025-01-01 | 无 | — | **疑似已修复(代码路径已重写)**：堆栈中的 `AdaptivePalette`/`PaletteIndirect` 已在 `a04ce40dbb Palette API cleanup (#2780)`（2025-06-22）中删除 |
| 2540 | `AbsoluteBlockBatch#setBlock` 抛 AIOOBE（`PaletteIndirect.getPaletteIndex`） | 崩溃 | 2025-01-19 | 无 | — | 同上，**疑似已修复**；但根因（多线程并发改 `IntArrayList`）未被单独验证 |
| 2507 | `Instance#getBlock(Pos)` 抛 IndexOutOfBounds | 崩溃 | 2025-01-24 | 无 | — | 同上，**疑似已修复**；`Wait for user Action` 标签，报告者 2025-01-23 称仍复现 |
| 2527 | 低视距下区块加载跟不上（chunk cache） | 功能错 | 2024-12-15 | 有（视频） | — | **疑似已修复**：报告者自述"likely related to #2455"，而 #2455 已由 `0602bde228 Update Chunk View Distance (#2892)` 关闭（2025-08-26） |
| 2526 | BlockBatch 丢方块（10 万+ 时明显） | 数据错 | 2024-12-14 | 有 | #2982 / #2791 | 陈旧；零评论 |
| 2486 | `autoChunkLoad=true` 时 ChunkBatch 不加载区块 | 功能错 | 2024-11-18 | 有（含行号） | #2448 | 陈旧；等一个 684 天没动的 PR |
| 2353 | `BlockHandler#onTouch` 触发不一致 | 功能错 | 2024-08-24 | 有（含视频） | — | 陈旧；零评论 |

### 实体 / 玩家 / 寻路 / 碰撞（23 条）

| # | 标题 | 严重度 | 最后人工评论 | 复现 | 关联 PR | 状态判断 |
|---|---|---|---|---|---|---|
| 3224 | 农作物阻挡寻路 | 功能错 | 2026-07-16 | 有（截图） | #3006 | **活跃**（68 条中仅有的近期活动之一）；与 #2813 重复 |
| 2813 | 蔬菜/浆果阻挡寻路 | 功能错 | 2025-07-04 | 有（截图） | #3006 | 陈旧；#3224 的前身 |
| 2252 | 寻路不考虑台阶/地毯的 step-up | 功能错 | 2025-01-19 | 有（含代码分析） | #3006 | 陈旧；`PreciseGroundNodeGenerator` 层面的设计缺陷 |
| 2831 | Navigator 里 `instance is null` NPE | 崩溃 | 2025-11-26 | 部分 | — | 陈旧；评论已定位为 `Entity.remove` 与 tick 的竞态，且指出 `Entity.refreshCoordinate` 用的是默认关闭的 `assert` |
| 3231 | `PlayerSkin#fromUsername` 返回 null | 功能错 | 2026-08-23 | 有 | #3349（已关闭未合） | **疑似已修复**：`810c317250 Match vanilla username regex (#3350)`（2026-08-28）已在本地 master；最后一条评论也指出原始 null 更可能是 Mojang 限流 |
| 3057 / 2152 | switchEntityType 后 meta / 伤害触发 ClassCastException | 崩溃 | 2025-07-14 | 有 | — | 陈旧；同一根因（切换 EntityType 不换 metadata 实现），两条 issue 已被独立报了三次 |
| 2797 | passenger 被隐藏再显示后不可见且脱离载具 | 功能错 | 2025-06-27 | 有 | #2217 | 陈旧；报告者已定位到 `updateNewViewer` 不处理 vehicle |
| 2081 | ViewEngine passenger bug | 功能错 | 2025-06-05 | 部分 | #2217 | 陈旧；诉求是"ViewEngine 需要重写" |
| 1750 | 加入服务器时 passenger 信息下发不正确 | 功能错 | 2025-10-14 | 有 | #2217 | `confirmed`，1300 天；2025-10 仍有人确认在复现并给了 workaround |
| 2352 | `EntityCreature` 传非生物 EntityType 直接踢人 | 崩溃 | 2024-08-24 | 有 | — | **已核实仍存在**：`EntityCreature` 两个构造器均无 living 类型校验 |
| 2702 | PhysicsUtil 负速度取不到正确 ground block | 功能错 | 2025-04-13 | 无 | — | **疑似非 bug**：讨论收敛为报告者用了 block position（角点）而非实体位置 |
| 2693 | PhysicsUtils 不考虑鞘翅飞行 | 功能错 | 2025-03-20 | 有 | — | 陈旧；零评论 |
| 1107 | 飞行/爬梯/落地时 velocity 语义错误 | 数据错 | 2026-02-22 | 有（含视频） | — | `confirmed`，1571 天；2026-02 仍有人在问 workaround |
| 1622 | ItemEntity 与客户端不同步 | 功能错 | 2022-12-31 | 部分 | — | 陈旧 |
| 1618 | EntityProjectile 移动很卡 | 体验 | 2023-01-09 | 有 | — | 陈旧 |
| 1526 | 手动视野实体 `Entity#remove` 抛 CME | 崩溃 | 2025-01-19 | 有（最小复现） | — | 陈旧；评论给了 `Collections.synchronizedSet` 的绕法 |
| 2141 | `Team.addPlayer()` 在 configure 前调用应报错 | 体验(API) | 2025-02-05 | 部分 | — | 陈旧；本质是"静默失败"的可用性问题 |
| 2061 | 伤害红光用错了包 | 功能错 | 2025-01-19 | 有 | — | **疑似已修复**：`d5c6126a6b Fix LivingEntity Damage Event Packet (#2209)`（2024-06-25），`LivingEntity.java:353` 现已发 `DamageEventPacket` |
| 1918 | `EntityFinder` sort 不生效 | 功能错 | 2023-08-02 | 有 | — | **疑似已修复**：`aca0e0e7f1 Entity selector fix (#3225)`（2026-07-10），同时新增 227 行 `EntityFinderIntegrationTest` |
| 2586 | 药水效果粒子不显示 | 功能错 | 2025-01-12 | 有 | — | 陈旧；讨论跑偏成"Minestom 是否该实现" |
| 3049 | 碰撞箱不考虑方块随机偏移（竹子、石笋） | 功能错 | 2026-02-28 | 有（截图） | — | 陈旧；零评论 |
| 2131 | 栅栏等方块碰撞箱比实际大 | 功能错 | 2024-05-07 | 有（截图） | — | 陈旧；零评论 |
| 1071 | 物品展示框碰撞箱异常 | 功能错 | 2025-01-25 | 有（视频） | — | `confirmed`，1579 天；2022 年就有人指出"只是部分修了" |

### 物品 / 背包（8 条）

| # | 标题 | 严重度 | 最后人工评论 | 复现 | 关联 PR | 状态判断 |
|---|---|---|---|---|---|---|
| 3118 | `ArgumentItemStack` 对部分组件不工作 | 功能错 | 2026-04-24 | 有 | **#3119** | 陈旧；PR 因未 rebase 膨胀到 +5130/-1097、164 文件 |
| 3027 | "拾取全部"（双击收集）事务实现错误 | 功能错 | 2026-02-02 | 有（视频） | — | 陈旧；零评论 |
| 2847 | 玩家背包 shift-click 进合成格导致不同步 | 数据错(不同步) | 2025-11-24 | 有 | — | 陈旧；评论承认这是"与原版背包机制的系统性差异"，不是单点 bug |
| 2830 | 点合成格时 `InventoryPreClickEvent` slot = -999 | 体验 | 2025-07-11 | 有 | — | **疑似非 bug**：维护者解释为客户端把它识别成 drag click |
| 2546 | 背包拖拽抛 ArrayIndexOutOfBounds（Index 51 / length 46） | 崩溃 | 2025-01-19 | 无 | — | 陈旧；缺复现，卡在"能给个复现吗" |
| 2514 | `InventoryClickEvent` 未正确跟踪 SHIFT_CLICK 移动的物品 | 功能错 | 2025-01-19 | 有 | — | 陈旧 |
| 1932 | 换手绕过 inventory condition | 功能错 | 2024-02-12 | 有 | #1992（已关闭未合） | **原始描述已失效**：`InventoryCondition` API 已在 `7d9b94c1fc Improve inventory event pipeline (#2531)`（2025-04-07）中删除。但底层语义问题仍在——`PlayerActionListener.swapItemHand` 只发 `PlayerSwapItemEvent`，不走点击管线 |
| 950 | `InventoryPreClickEvent`/`PlayerSwapItemEvent` 不继承 `ItemEvent` | 体验(API) | 2025-01-26 | 有 | — | **非 bug，属增强**；`EventFilter.ITEM` 语义不完整 |

### 命令（6 条）

| # | 标题 | 严重度 | 最后人工评论 | 复现 | 关联 PR | 状态判断 |
|---|---|---|---|---|---|---|
| 3004 | 把 `ArgumentResourceLocation` 改名 | 体验(命名) | 2025-12-26 | N/A | **#3316** | **非 bug**（被误打 bug 标签）；PR 已冲突 |
| 2682 | default executor 破坏 suggestion | 功能错 | 2025-11-24 | 有 | — | **疑似已修复**：PR #2917 "Fix wrong default executor being called"（2025-10-04 合并）随 `d721bd11a8 1.21.9 (#2927)` 落地，同时带入 `CommandSuggestionSubcommandTest.java` |
| 1617 | Argument callback 不按预期工作 | 功能错 | 2025-02-17 | 有 | #2661（已关闭未合）/ #3058 / #3129 | 陈旧；三个修复尝试全部失败 |
| 1520 | Command argument callback 不工作 | 功能错 | 2022-12-31 | 有 | 同上 | 与 #1617 **重复**；评论里做过很好的定位（`ArgumentResult.IncompatibleType` 走进 default 分支） |
| 1326 | `ConditionalSyntax` 只影响 CommandNode 的最后一个参数 | 功能错 | 2022-08-12 | 有 | — | 陈旧；零评论 |
| 466 | Tab 补全忽略尾随空格 | 功能错 | 2021-09-18 | 有 | #2503 | `confirmed`，1817 天；PR #2503 停摆 597 天 |

### 线程 / 生命周期 / 事件（4 条）

| # | 标题 | 严重度 | 最后人工评论 | 复现 | 关联 PR | 状态判断 |
|---|---|---|---|---|---|---|
| 2907 | 从 tick 线程调 `stopCleanly()` 死锁 | 崩溃(死锁) | 2025-09-04 | 有 | — | **已核实仍存在**：`ServerProcessImpl.stop()` 直接 `dispatcher.shutdown()`，无 tick 线程防护。回归自 `4459241c1d Acquirable strict (#2857)` |
| 2649 | `EntityTracker` 内存泄漏 | 数据错(泄漏) | 2025-02-04 | 有（含测试代码 + profiler 会话） | #2655 | **已核实仍存在**：`EntityTrackerImpl.removeFromChunk`（约 292–295 行）只从 list 移除元素，**不把空的 `CopyOnWriteArrayList` 从 `chunkEntities` map 里删掉**；无限世界下按 chunk index 累积 |
| 1636 | 停服时 `PlayerDisconnectEvent` 不触发 | 数据错(玩家数据丢失) | 2025-01-19 | 有 | — | 陈旧；对"停服存档"是致命的 |
| 1212 | TickThread 上的 ConcurrentModificationException | 崩溃 | 2025-01-19 | 无 | — | 陈旧；缺复现 |

### 矩阵汇总数字

| 指标 | 数值 | 复现命令 |
|---|---|---|
| open bug 总数 | 68 | `gh issue list --repo Minestom/Minestom --state open --label bug --limit 200` |
| 中位年龄 | **626 天**（均值 746，最老 1983 天 = #221） | 见 ⑧ 脚本 |
| 最后人工评论 > 6 个月 | **60 / 68（88%）** | 同上 |
| 最后人工评论 > 24 个月 | 13 / 68 | 同上 |
| 零评论 | 20 / 68 | 同上 |
| 有可执行复现信息 | 44 / 68（65%） | 正则匹配 `### Steps to reproduce` 段非 `_No response_` |
| 有关联 open PR | 6 条直接互引（#2486↔#2448、#2649↔#2655、#2766↔#2982、#3004↔#3316、#3054↔#3056、#3118↔#3119） | 扫描 open PR 标题+正文中的 `#\d{3,4}` |
| 严重度分布 | 崩溃 17、数据错 12、功能错 31、体验 8 | 人工判定 |
| 状态判断分布 | 活跃 2、陈旧 52、疑似已修复 8、非bug/已失效 6 | 人工判定 |

**"疑似已修复"清单（8 条，全部附依据）**

| # | 依据 commit / PR | 落地时间 |
|---|---|---|
| 2668 | `219f16bd4f fix: linear palette deserialization bug (#3097)` + `PaletteTest` 新增用例 | 2026-04-11 |
| 2682 | PR #2917（merge commit `276b647b` 不在 master，实际经 `d721bd11a8 1.21.9 (#2927)` 落地），新增 `CommandSuggestionSubcommandTest.java` | 2025-10-11 |
| 1918 | `aca0e0e7f1 Entity selector fix (#3225)` + 新增 `EntityFinderIntegrationTest`（227 行） | 2026-07-10 |
| 2061 | `d5c6126a6b Fix LivingEntity Damage Event Packet (#2209)`；`LivingEntity.java:353` 现用 `DamageEventPacket` | 2024-06-25 |
| 3231 | `810c317250 Match vanilla username regex (#3350)`；`StringUtils.isValidUsername` 现允许 1–2 字符名 | 2026-08-28 |
| 2527 | 关联 issue #2455 由 `0602bde228 Update Chunk View Distance (#2892)` 关闭 | 2025-08-26 |
| 2547 / 2540 / 2507 | `a04ce40dbb Palette API cleanup (#2780)` 删除了堆栈中的 `AdaptivePalette` / `PaletteIndirect` | 2025-06-22 |

> 注：最后一组（palette 三连崩溃）只能判定为"**代码路径已重写**"，不能判定为"根因已消除"——三条 issue 的共同特征是 `IntArrayList` 在并发下被撕裂，而新的 `PaletteImpl` 仍在用 `IntArrayList` 且没有同步（`PaletteImpl.java` 多处），open PR **#3324「refactor!: rework palette storage」（+4980/-1399）正是在重做这块存储**。建议我们的 fork 把它当"未验证"处理。

---

## ③ Open PR 评估

> **口径修正**：任务书写"11 个 open PR"，实测**开着的 PR 是 80 个**（`gh pr list --state open --limit 200 | wc -l`），提供的 `upstream-open-prs.md` 只导出了最近 60 个（缺 #804/#1032/#1549/#1719/#2077/#2113/#2217/#2271/#2273/#2274/#2364/#2386/#2448/#2483/#2498/#2502/#2503/#2534/#2599/#2601 共 20 个）。下表按"对我们 fork 的价值"排序，**先列 12 个值得决策的，再给全量分布**。

**CI 口径**：`checks` 里 `SUCCESS/SKIPPED` 是最近一次 workflow 结果；**33 个老 PR 的 `checks` 为空**——它们上一次跑 CI 时现行 workflow 还不存在，重跑结果未知。`merge=CONFLICTING` 表示已无法直接合入 master。

### 值得决策的 12 个

| # | 修什么 | diff | CI | Review | 已开 / 闲置 | 建议 |
|---|---|---|---|---|---|---|
| **3348** | `Palette#compare` 在 single-value 与 indirect palette 间误判（修 #3334） | +96/-3，2 文件，**含 89 行新测试** | 未跑（`checks={}`） | 1 条 COMMENTED | 16d / 11d | **建议合入**。改动 7 行、自带回归测试、语义明确；`count` 字段双用途是真 bug |
| **3356** | 邻居更新顺序对齐原版（west,east,down,up,north,south） | +36/-1，2 文件，**含 34 行集成测试** | 全绿 | 无 | 10d / 1d | **建议合入**。红石/重力方块行为正确性问题，改 2 行；注意标了 `breaking` |
| **3296** | 点击可替换方块（草、雪层、水）时原地替换 | +33/-1，2 文件，**含 29 行集成测试** | 全绿 | 2 条 COMMENTED | 52d / 49d | **建议合入**（需先解冲突）。修的是放置预测不同步，玩家可感知 |
| **3346** | `Player#sendPackets(Collection)` 泛型协变 | +3/-3，3 文件 | 全绿 | 无 | 17d / 17d | **建议合入**。纯签名放宽（`Collection<? extends SendablePacket>`），零风险；标 `breaking` 只因二进制签名变了 |
| **3288** | 对齐原版/Paper 的 registry tag 顺序 | +50/-9，5 文件，含测试 | 全绿 | **APPROVED 1** | 58d / 52d | **建议合入**（需解冲突）。已被 approve 却没人按合并键，是"队列断档"的典型样本 |
| **3347** | 网络错误处理下沉到 server flag（抑制扫描器噪音） | +100/-22，7 文件，含 47 行测试 | 全绿 | 无 | 16d / 16d | **建议合入**。直接缓解 #3320/#2241 的日志风暴；作者是核心维护者 kermandev |
| **3056** | GroundNodeGenerator 未加载区块 NPE（修 #3054） | +25/-3，2 文件，**无测试** | 部分 SKIPPED | 无 | 188d / 160d | **观望**。修的是真崩溃，但方案是加 null/loaded 检查（掩盖症状），且无测试；建议我们自己写一版带测试的 |
| **3048** | 丢弃回显的玩家输入（缓解 #2334 位置冻结） | +257/-1，8 文件，**含 188 行测试** | 全绿 | **CHANGES_REQUESTED** | 192d / 189d | **观望**。测试很扎实，但被 request changes 后作者 6 个月没动；先自测再决定 |
| **3324** | palette 存储重写 | +4980/-1399，33 文件 | 全绿 | 2 条评论 | 32d / 4d | **观望**。是 palette 三连崩溃（#2540/#2547/#2507）的真正根治路径，但体量大、标 `breaking`，等上游先合 |
| **3119** | `ArgumentItemStack` 解析（修 #3118） | **+5130/-1097，164 文件** | 部分 | 无 | 137d / 60d | **不合**。分支严重落后导致 diff 被噪音淹没，无法审；如需修复自行 cherry-pick 语义 |
| **3129** | 参数解析失败时传播 suggestion callback（修 #1617/#1520） | **+15499/-5538，602 文件** | **FAILURE** | 无 | 129d / 96d | **不合**。同上，且 CI 红 |
| **3006** | 寻路重写 + step-up（修 #3224/#2813/#2252） | +1038/-297，16 文件 | **FAILURE** | 无 | 255d / 244d | **不合（但要盯）**。它是三条寻路 issue 的唯一在途方案，CI 红且 8 个月没动 —— 寻路大概率要我们自己重写 |

### 全量 80 个 open PR 的结构分布

| 维度 | 数值 | 说明 |
|---|---|---|
| 总数 | 80（其中 draft 9） | `gh pr list --state open --limit 200` |
| `mergeable = CONFLICTING` | **47 / 80（59%）** | 即近六成 PR 已经无法直接合并 |
| 有 `APPROVED` review 却仍开着 | 5（#3288、#3280、#3249、#2916、#2364、#1549 中含 approve） | 审过没人合 |
| `reviewDecision = CHANGES_REQUESTED` | 7（#3092、#3048、#2980、#2967、#2947、#2891、#2483） | 作者停摆 |
| 完全无 review、无 comment | 25 | 提交后无人看过 |
| 闲置 > 365 天 | 22 | 最老 #804（1628 天）、#1032（1586 天）、#1549（1379 天）、#1719（1320 天） |
| 作者集中度 | TheMode 14、Term4 8、kermandev 6、mudkipdev 6、VegetalDev 5 | TheMode 的 14 个多为 draft/实验分支 |
| 大改造类（>1000 行） | 9（#3324、#3194、#3046、#3006、#2967、#2791、#2655、#2654、#2271、#804） | 长期悬而不决，是冲突的主要来源 |

**总体判断**：PR 队列不是"缺人审"，而是**缺合并纪律**。小而带测试的 PR（#3348/#3356/#3296/#3346）在 CI 全绿、无异议的情况下依然放了 2 周到 2 个月；而 5 个大改造（ChunkSystem #2654、Batch rework #2791、Entity Query #2655、Palette rework #3324、Enchantments #2271）互相锁死彼此依赖的文件，形成"谁都不敢先合"的死锁。对我们的 fork 而言，这意味着**上游合入不可依赖，必须建立自己的 cherry-pick 清单**。

---

## ④ 已关闭 bug 的响应能力

**样本**：最近 60 条被关闭的 `bug` 标签 issue（关闭时间跨度 2025-03-27 → 2026-08-16）。同时给出"近 12 个月（2025-09-09 起）关闭的 34 条"作为对照。

**命令**
```bash
gh issue list --repo Minestom/Minestom --state closed --label bug --limit 200 \
  --json number,title,createdAt,closedAt,labels,stateReason,comments
# 关闭方式经 GraphQL timelineItems(itemTypes:[CLOSED_EVENT]).closer 判定
```

| 指标 | 最近 60 条 | 近 12 个月（34 条） |
|---|---|---|
| 从开单到关单：中位 | **20.5 天** | **14 天** |
| 均值 | 162.6 天 | 123.3 天 |
| P25 / P75 | 2 天 / 227 天 | 2 天 / 143 天 |
| ≤7 天关闭 | 21（35%） | 14（41%） |
| ≤30 天关闭 | 31（52%） | 19（56%） |
| >365 天关闭 | 9（15%） | 5（15%） |
| 最长 | 1200 天 | 706 天 |
| 全程零评论就被关 | 24（40%） | 16（47%） |

**关闭方式分布（最近 60 条）**

| 方式 | 数量 | 说明 |
|---|---|---|
| `COMPLETED` + 有直接关联 PR | **25** | GitHub 自动关联，是"真被修了"的硬证据 |
| `COMPLETED` 但无关联 PR | 30 | 人工关单。抽查其最后一条评论后细分：<br>· 评论里点名了修复 commit/PR（#3171→`9958348d`、#2700→`0473b41b`、#2513→#2752、#2510→#3248、#2354→#2701、#2395→#2781、#1097、#2227、#2745、#3210、#2704、#2481 等）≈ 15<br>· **零评论静默关闭**（#3110、#2789、#2772、#2757、#2637、#2232）= **6**<br>· "无法复现 / 用户配置问题"（#2987、#3104）= 2<br>· "重构后作废，请重开"（#1743、#1597）= 2<br>· 其余为讨论后自然收敛 |
| `NOT_PLANNED` | 4 | #3213（上游依赖已修）、#3103（Query API 将被移除）、#2697（Acquirable 语义澄清）、#1597 |
| `DUPLICATE` | 1 | #2940 → #2170 |

**关闭 bug 的子系统分布（最近 60 条，关键词分类，见 ⑧ 口径）**

| 子系统 | 数量 |
|---|---|
| 实体 / 玩家 | 19 |
| instance / 世界 / 区块 / palette / 光照 | 16 |
| 网络 / 协议 / 编解码 / registry / dialog | 11 |
| 物品 / 背包 | 5 |
| 线程 / 事件 | 3 |
| 构建 / 模块 / 文档 | 3 |
| 命令 | 1 |
| 未归类 | 2 |

**解读**

这组数字讲的是一个**"新鲜度决定命运"**的系统。P25 = 2 天说明：只要 issue 落在维护者当下正在改的那块代码上（多半是当前 Minecraft 版本适配、codec、registry），响应可以是当天级的。但 P75 = 227 天、均值是中位数的 8 倍，说明分布是双峰的——另一峰是"躺到某次大重构顺手带走"。40% 的 issue 全程零评论就被关掉，进一步印证：**上游没有分诊环节，只有"恰好被踩到"和"恰好被重构覆盖"两种命运**。

把它和 ② 的 open 池子对照，结论更清楚：open 池子里中位年龄 626 天、88% 超 6 个月无回应，而 closed 池子里中位 14–20 天。这不是"修得慢"，是**"分流器只有一个入口"**——进不了维护者当期工作面的 issue，进入的是一个只会靠重构被动清空的池子。子系统分布也吻合：关闭最多的是实体/玩家和 instance/世界（各占 1/4 强），正是 1.21.x 版本适配持续改动的区域；而 open 池子里堆积最多的恰恰是**寻路、碰撞箱、batch API、命令 argument callback**——这四块近三年没有做过版本适配驱动的重写。

对我们 fork 的直接推论：**上游的响应能力对"我们提的新 bug"是有效的（尤其是带最小复现的协议/codec 问题），对"我们继承的老 bug"是无效的**。老 bug 必须自己修。

---

## ⑤ 测试覆盖静态估算

> 全部为静态估算，**未运行任何 Gradle 任务**，没有行覆盖率数据（仓库也没有配置 JaCoCo/Kover）。

### 5.1 总量

```bash
find src/main/java -name '*.java' | wc -l          # 1413
find src/test/java -name '*.java' | wc -l          #  247
grep -rh '@Test' src/test/java --include='*.java' | wc -l           # 1461
grep -rh '@ParameterizedTest' src/test/java --include='*.java' | wc -l  #   78
grep -rl '@EnvTest' src/test/java --include='*.java' | wc -l        #   89
grep -rn '@Disabled' src/test testing --include='*.java' | wc -l    #   12
```

| 指标 | 值 |
|---|---|
| 主代码 Java 文件 | 1413（其中非 `package-info` 的类 1251） |
| 测试文件 / `@Test` 方法 | 247 / 1461（另有 78 个 `@ParameterizedTest`） |
| 使用 `@EnvTest`（真 ServerProcess 集成）的类 | 89 |
| `@Disabled` 的测试 | **12**，集中在 `PlayerSkinTest`（2 个）与 `TestMojangUtils`（9 个）—— 全部是需要外网访问 Mojang API 的 |
| 主代码类 : 测试文件 | 约 5.1 : 1 |

### 5.2 按包的"被测试引用率"

**目录镜像法会严重误判**（例如 `command/builder` 有 62 个类、测试目录里 0 个文件，但测试实际写在 `src/test/.../command/` 扁平目录下）。因此这里用**类名引用法**：统计每个包里有多少个类的**简单名**在 `src/test` 或 `testing/src` 的任一 Java 源文件中出现过。

| 包 | 主代码类数 | 被测试引用 | 引用率 |
|---|---|---|---|
| `network/packet` | 249 | 245 | **98%** |
| `coordinate` | 8 | 7 | 88% |
| `dialog` | 8 | 6 | 75% |
| `collision` | 13 | 9 | 69% |
| `codec` | 13 | 8 | 62% |
| `instance` | 63 | 37 | 59% |
| `item` | 66 | 35 | 53% |
| `utils` | 55 | 26 | 47% |
| `entity`（不含 metadata） | 68 | 31 | 46% |
| `network`（不含 packet） | 27 | 12 | 44% |
| `inventory` | 20 | 8 | 40% |
| `event`（不含 event/player） | 68 | 27 | 40% |
| `registry` | 20 | 7 | 35% |
| `event/player` | 56 | 16 | 29% |
| `command/builder` | 62 | 15 | 24% |
| `world` | 28 | 6 | 21% |
| `sound` | 5 | 1 | 20% |
| **`listener`** | **42** | **6** | **14%** |
| **`scoreboard`** | **7** | **1** | **14%** |
| **`entity/metadata`** | **202** | **19** | **9%** |
| **`adventure`** | **29** | **2** | **7%** |
| **`map`** | **8** | **0** | **0%** |
| `snapshot` | 10 | 1 | 10% |

### 5.3 「主代码 > 20 个文件但测试 < 3 个」的包

按目录镜像口径（`src/test/java/net/minestom/server/<同名目录>` 下的测试文件数）命中 5 个：

| 包 | 主代码文件 | 对应测试目录文件 | 备注 |
|---|---|---|---|
| `entity/metadata` | 222 | 1 | **真实空洞**。引用率也只有 9%，且 #3057/#2152/#3141 三条 open bug 都在这块 |
| `command/builder` | 72 | 0 | **误判**：测试写在 `command/` 扁平目录（20 个文件、138 个 `@Test`）。实际引用率 24%，仍偏低 |
| `event/player` | 57 | 0 | **半误判**：测试散在 `entity/player/` 下。实际引用率 29%，偏低 |
| `adventure` | 34 | 1 | **真实空洞**。引用率 7%，`AdventurePacketConvertor`、`BossBarManager`、`Audiences` 全裸 |
| `world` | 33 | 2 | 引用率 21%；`world/attribute`（16 类）0 引用 |

`network/packet` 虽然 264 个主文件对 3 个测试文件，但**这是最严重的误判**——见下。

### 5.4 协议编解码：实际是覆盖最好的一块

```bash
# 238 个 *Packet.java；PacketWriteReadRegistriesTest.java 942 行
python3  # 见 ⑧ 脚本：比对包类名 vs 测试源引用
```

| 指标 | 值 |
|---|---|
| `network/packet` 下 `*Packet.java` 类 | 238（server 157 + client 80 + wrapper） |
| 在 `PacketWriteReadRegistriesTest` 中被 write→read 往返验证 | **232** |
| 未覆盖的 6 个 | `Packet`、`SendablePacket`、`CachedPacket`、`FramedPacket`、`BufferedPacket`（均为包装接口/装饰器）+ `LegacyServerListPingPacket` |

另有 `SocketWriteIntegrationTest` / `SocketReadIntegrationTest` 覆盖帧长度、半包/粘包、缓冲扩容与**压缩阈值 256 的读写**（`@ValueSource(booleans={false,true})` 参数化）。

**结论**：字节层的 codec 正确性是有保障的，2026 年 codec 类 bug 关得快也印证了这点。风险不在"包写错"，而在"包在什么时机被发出来"。

### 5.5 `testing/` 模块能覆盖到哪一层

关键文件（全部位于 `/Users/bug/Documents/Projects/Minecraft/Minestom/testing/src/main/java/net/minestom/testing/`）：`Env.java`、`EnvImpl.java`、`EnvTest.java`、`EnvTestExt.java`、`TestConnection.java`、`TestConnectionImpl.java`、`Collector.java`、`FlexibleListener.java`、`RegistriesTest.java`、`RegistriesTestExt.java`。

**能覆盖**
- 真实的 `ServerProcess`：`MinecraftServer.updateProcess()` 造出全套 manager（ConnectionManager / InstanceManager / GlobalEventHandler / SchedulerManager / ThreadDispatcher / Registries）。
- **确定性 tick**：`env.tick()` 跑完 `TickerImpl.tick()` 全流程；`ThreadDispatcher.updateAndAwait()` 是每 tick barrier，所以实体/区块 tick 从测试线程看是同步的。调度器只有 `TICK_START`/`TICK_END` 两种执行类型，`TaskSchedule.tick(n)` 完全由 `env.tick()` 驱动。
- **出站包断言**：`TestConnection.trackIncoming(XPacket.class)` + `Collector.assertSingle/assertCount/assertAnyMatch`，仓库中 153 处使用；`CopyOnWriteArrayList` 保序，可按下标断言顺序。
- 事件断言：`FlexibleListener`（顺序敏感，未触发会在 `cleanup()` 里 fail）。
- CONFIGURATION 与 PLAY 阶段的服务端游戏逻辑。

**测不到（结构性盲区）**
1. **不走真实 socket**：`TestConnectionImpl.PlayerConnectionImpl` 是 fake，`getRemoteAddress()` 硬编码 `localhost:25565`，没有 `SocketChannel`、没有 NIO 读写线程。名字里带 Socket 的 `SocketRead/WriteIntegrationTest` 其实只测 buffer。唯一碰真 socket 的 `ServerAddressTest` 带 `assumeTrue(GITHUB_ACTIONS == null)` —— **CI 上直接跳过**。
2. **集成路径完全不过 codec**：`sendPacket` 只把 `ServerPacket` **对象引用**塞进 collector，`NetworkBuffer.Type` 的 write/read 一次都不跑。改错某个包的字段顺序，89 个集成测试全绿。
3. **握手 / 状态查询 / 登录阶段 0 集成覆盖**：`TestConnection.connect()` 直接跳到 `doConfiguration(player, false)`。`AsyncPlayerPreLoginEvent`、`LoginSuccessPacket` 时序、login plugin message 都不走。
4. **压缩与加密的连接级行为不可测**：`startCompression()` 只在 `PlayerSocketConnection` 上调用；加密握手、`SecretKey` 建立完全没有测试。
5. **Velocity / BungeeCord 代理转发 0 覆盖**：`EnvImpl` 用默认 `Auth.Offline()`，`Env` 没有替换 `Auth` 的口子。
6. **Registry 数据下发不可测**：`isFirstConfig=false` 是刻意传的，源码注释写明代价就是"registry 包无法被监听"，`SelectKnownPacksPacket` 往返不走。
7. **区块分批发送被绕过**：`TestPlayerImpl.sendChunk()` 覆写为立即发全量包，`ClientChunkBatchReceivedPacket` 驱动的自适应速率逻辑不可测——而 #2527/#3021 正是这条路上的 bug。
8. **多线程竞态被结构性压掉**：`DISPATCHER_THREADS` 默认 1 + 每 tick barrier + 测试串行执行。**`MinecraftServer.serverProcess` 是静态字段**，事实上禁止并行测试。
9. **测试环境语义与生产有三处系统差异**（`build-src/src/main/kotlin/minestom.java-library.gradle.kts:67-77` 强制注入）：`minestom.viewable-packet=false`（关掉 viewable packet 分组）、`minestom.inside-test=true`（玩家 spawn 被 `ConnectionManager` 内的 `if (INSIDE_TEST) spawnFuture.join()` 同步化、部分虚拟线程断言被放宽）、`minestom.acquirable-strict=true`。**任何依赖这三者的生产 bug 在测试里天然不可见。**
10. **`Duration` 调度走真实墙钟**：`SchedulerImpl` 对 `DurationSchedule` 用独立 executor，`env.tick()` 推不动。
11. **异步区块加载不确定**：`InstanceContainer.retrieveChunk` 的处理阶段无条件 hop 到虚拟线程（`thenApplyAsync(processChunk, Thread::startVirtualThread)`），`InstanceChunkLoadEvent` 时机相对测试线程是异步的。

**一个易踩的坑（对我们写测试有直接影响）**：`Collector.collect()` 是**一次性**的——它先 `incomingTrackers.remove(this)` 再返回快照，所以对同一 tracker 调第二次断言拿到的是冻结的旧快照。`EnvImpl.EventCollector.collect()` 更狠，会 `unmap` 该 actor 上的全部映射监听器。

### 5.6 完全没有测试的关键公开 API 类（挑 15 个最重要的）

判定口径：类的简单名在 `src/test` 与 `testing/src` 的**所有** Java 源中都没出现过（661 / 1251 个主代码类命中此条件）。注意"未被提名"≠"未被执行"——`listener` 包里的类是被集成测试间接跑到的，只是没有任何断言直接针对它们。

| 类 | 包 | 为什么重要 |
|---|---|---|
| `SignatureValidator` | `crypto` | **聊天消息签名校验**。整个 `crypto` 包 10 个类里，被引用的 7 个都只是作为包字段出现在 codec 往返测试里，**签名验证逻辑本身零测试** |
| `SaltSignaturePair` / `SignedMessageHeader` | `crypto` | 同上，安全相关路径 |
| `ExceptionManager` / `ExceptionHandler` | `exception` | 全局异常出口。`EnvImpl` 反而把它替换成 `Assertions.fail`，所以生产行为（默认 handler 打印堆栈）永远不被测 |
| `ServerProcessImpl` | `(root)` | 服务器生命周期。#2907（停服死锁）正出在 `stop()`；`ServerProcessTest` 只测了"重复 start 抛异常" |
| `InstanceManager` | `instance` | instance 注册/注销。#2900（spawn 期间注销 instance 崩溃）正出在这 |
| `SharedInstance` | `instance` | 共享世界，多 instance 场景的核心 |
| `Explosion` / `ExplosionSupplier` | `instance` | 爆炸逻辑 |
| `Viewable` | `(root)` | 视野系统的顶层接口；#2081/#2797/#1750 三条 open bug 都在这条线上 |
| `AudienceRegistry` / `Audiences` / `PacketGroupingAudience` | `adventure/audience` | 广播/分组发包的公共出口，全库 7% 引用率 |
| `BossBarManager` / `BossBarListener` | `adventure/bossbar` | boss 血条状态机（含 viewer 增删） |
| `AdventurePacketConvertor` | `adventure` | Adventure 对象 → Minecraft 包的转换层 |
| `Sidebar` / `Team` / `TeamManager` / `BelowNameTag` | `scoreboard` | 整个计分板包 7 个类只有 1 个被提名 |
| `TransactionType` / `EquipmentHandler` / `InventoryClickHandler` | `inventory` | **背包事务核心**。#3027（pickup all 事务实现错）、#2847、#2514 三条 open bug 都在这 |
| `ConsoleSender` / `CommandParserImpl` / `ExecutableCommand` | `command` | 命令解析主干（`CommandParseTest` 测的是 `Command`/`Argument`，不直接点名实现类） |
| `MapColors` / `Framebuffer` / `LargeFramebuffer` 等 8 类 | `map` | 整包 0 引用（不过 open PR #3341 正提议整包删除） |

---

## ⑥ CI 与质量门禁评估

### 6.1 workflow 清单

| 文件 | 触发 | 内容 |
|---|---|---|
| `build-pr.yml` | `pull_request` → master、`merge_group` | 4 个 job：**JVM Check**（`./gradlew check`）、**Javadoc Compilation**（`./gradlew javadoc`）、**Native Test**（GraalVM `nativeTest`）、**Binary Compatibility**（对 base commit 的 jar 做 ABI 比对）；外加条件性 `Publish PR` |
| `build-main.yml` | push → master | 同上前三个 job + `Publish Snapshot`（发 `master-SNAPSHOT` 到 Central） |
| `binary-compat-comment.yml` | `workflow_run` (Build PR 失败时) | 把 ABI 违规签名以评论形式贴回 PR |
| `close-invalid-prs.yml` | `pull_request_target` opened | 自动关掉从 `master` 分支或组织账号发起的 PR |
| `release.yml` | `workflow_dispatch` | 打 tag、发 Central、发 javadoc 分支 |

统一使用 GraalVM JDK 25 + `gradle/actions/setup-gradle@v6`。

### 6.2 `./gradlew check` 实际跑了什么

`build-src/src/main/kotlin/minestom.style.gradle.kts` 把三个自定义任务挂进 `check`：

```kotlin
tasks.named(CHECK_TASK_NAME) {
    dependsOn("errorproneCheck", "forbiddenImportsCheck", "testNamingCheck")
}
```

- `errorproneCheck` — 编译所有 source set（errorprone 随 `JavaCompile` 生效）
- `forbiddenImportsCheck` — 禁止 `org.jspecify` / `org.checkerframework` 注解（`CheckImportsTask.kt`）
- `testNamingCheck` — 强制测试类命名：`@EnvTest` → `*IntegrationTest`，`@RegistriesTest` → `*RegistriesTest`，其余 `*Test`（`CheckTestNamingTask.kt`）
- 加上 `test` 任务本身（JUnit Platform）

### 6.3 errorprone 配置

`minestom.style.gradle.kts` 里：

- **全局 `-Werror`**，`-Xlint:all,-requires-automatic,-requires-transitive-automatic,-exports,-missing-explicit-ctor`
- **提升为 error 的检查 ≈ 105 项**，覆盖面相当激进，包括：
  - 空安全族：`FieldMissingNullable`、`ParameterMissingNullable`、`NullArgumentForNonNullParameter`、`NullableConstructor`、`NullablePrimitive`、`NullableVoid`、`NullableWildcard`、`NullableOptional`、`NullableTypeParameter` 等 10+ 项
  - 并发/资源族：`LockNotBeforeTry`、`ClassInitializationDeadlock`、`FutureReturnValueIgnored`、`FutureTransformAsync`
  - 正确性族：`EqualsIncompatibleType`、`EqualsUnsafeCast`、`InconsistentHashCode`、`CompareToZero`、`ErroneousBitwiseExpression`、`IntLongMath`、`NarrowingCompoundAssignment`、`ShortCircuitBoolean`、`BoxingComparator`、`MissingCasesInEnumSwitch`
  - 风格/卫生族：`UnusedMethod`、`UnusedVariable`、`RemoveUnusedImports`、`WildcardImport`、`UnnecessaryStaticImport`、`MissingOverride`、`MissingSummary`、Javadoc 标签系列
- **显式禁用 7 项**，其中两条自带 TODO，值得注意：
  ```
  "ReferenceEquality",   //TODO, likely needs to be fixed
  "ArrayRecordComponent" //TODO, likely needs to be fixed
  ```
  其余禁用：`AvoidCommonTypeNames`、`FallThrough`（由 javac lint 覆盖）、`InlineMeSuggester`、`EnumOrdinal`、`ImmutableEnumChecker`、`ExposedPrivateType`
- 排除路径：`.*/build/generated/.*|.*/src/autogenerated/.*`

**评价**：静态检查这一层是全项目最扎实的部分，强度高于绝大多数同类 Java 项目。但两条 TODO 意味着**引用相等性比较（`==` 用在对象上）目前全库不受检**——对一个大量使用 `Block`/`Material`/`Registry` 值对象的项目，这是个真实的踩坑面。

### 6.4 哪些是"必过"

| 门禁 | 是否阻塞合并 | 依据 |
|---|---|---|
| JVM Check（编译 + 单测 + errorprone + 3 个自定义 check） | **是** | 无 `continue-on-error` |
| Javadoc Compilation | **是** | 同上 |
| Native Test（GraalVM） | **形式上是，实质上被软化** | `continue-on-error: true` + 失败后自动跑 tracing agent → `metadataCopy` → 重跑 `nativeTest` |
| Binary Compatibility | **是，但可被标签绕过** | `if: !contains(labels, 'breaking')`；打上 `breaking` 标签即跳过。80 个 open PR 中 10 个带 `breaking` |
| **jcstress 并发测试** | **否 —— 完全不跑** | `grep -ri 'jcstress\|jmh' .github/` **零命中**。jcstress 插件产生的是独立 `jcstress` 任务，不挂在 `check` 上 |
| **JMH 基准** | **否 —— 完全不跑** | 同上，没有性能回归门禁 |
| 代码覆盖率 | **否** | 仓库无 JaCoCo/Kover 配置 |

### 6.5 flaky 迹象

```bash
grep -rn 'continue-on-error\|retry\|if: failure\|fail-fast' .github/workflows/
# build-main.yml:67  continue-on-error: true
# build-pr.yml:69    continue-on-error: true
```

- **只有一处 `continue-on-error`**，就是 `nativeTest`。它的注释写明用意是"小优化：假设 metadata 不需要更新"，即把"native 元数据过期"当成预期失败并自动补跑。这不是掩盖 flaky，但它确实**让 native 侧的真实失败也会被自动重跑一次**，第二次仍失败才红。
- `release.yml` 里有一句更直白的自白：
  > `- name: Run Tracing Agent # There is a slight race that we have to solve by rerunning`

  即 GraalVM reachability metadata 生成本身存在已知竞态，靠重跑解决。
- 没有 `nick-fields/retry`、没有 `fail-fast: false`、没有 test retry 插件。
- **12 个 `@Disabled` 测试**全是外网依赖（Mojang API），不是 flaky 而是被主动摘除。但其中 `PlayerSkinTest.validName` / `invalidName` 被禁用这件事**直接对应 open issue #3231**——能抓到这个 bug 的两个测试正好是关着的。

### 6.6 综合评价

**强项**：errorprone 强度高且 `-Werror`；ABI 兼容性有自动检查并会把违规签名贴回 PR；测试命名有构建强制；GraalVM native 路径进 CI（同类项目罕见）；dependabot 配了 gradle（周）+ actions（月）并显式忽略 major。

**弱项（按对我们的影响排序）**：
1. **并发测试不进 CI**。`jcstress-tests` 有 11 个精心设计的场景（7 个测 `TagHandler` 的并发写/rehash、`ThreadDispatcherSyncTest` 验证 tick 不重不漏、`AcquirableSyncTest`、`ObjectPoolTest`、`ObjectArrayTest`），但没有任何 workflow 跑它们。而 open bug 里 #2638（chunk 竞态）、#2831（Navigator 竞态）、#1212（TickThread CME）、#2907（停服死锁）全是并发问题。
2. **无覆盖率门禁**，也就无从阻止新代码零测试合入。
3. **无性能回归门禁**，36 个 JMH 基准是手工工具而非守门员。
4. **`breaking` 标签是 ABI 检查的万能钥匙**，靠人工纪律约束。
5. **测试串行**：`MinecraftServer.serverProcess` 静态字段导致不能开并行 fork，1461 个 `@Test`（其中 89 个类要起真 ServerProcess）全串行，CI 时长会随测试增长线性上升——这会反向抑制加测试的意愿。

---

## ⑦ 风险热区与优先级建议

### 7.1 最值得我们 fork 优先投入的 5 个问题

**① `EntityTracker` 内存泄漏（#2649）—— 唯一一个"确定会随运行时间累积"的问题**
已核实未修：`EntityTrackerImpl.removeFromChunk`（约 292–295 行）只做 `entities.remove(entity)`，空的 `CopyOnWriteArrayList` 永久留在 `chunkEntities` map 里。无限世界生成场景下按 chunk index 无界增长。报告者当年已给出 profiler 会话和可运行的复现测试。修复代价小（判空后 `chunkEntities.remove(index)`，注意与 `computeIfAbsent` 的竞态），但**必须配 jcstress 场景**，否则容易引入 add/remove 竞态。上游对口 PR #2655（Entity Query API）已停摆 448 天，不能等。

**② 停服路径：死锁 + 丢事件（#2907 + #1636）—— 直接对应"玩家数据丢失"**
已核实未修：`ServerProcessImpl.stop()` 从 tick 线程调用会卡在 `dispatcher.shutdown()`；同时 `PlayerConnection.disconnect()` 把 `PlayerDisconnectEvent` 推到下一 tick，停服路径上这一 tick 不会到来。任何在 `PlayerDisconnectEvent` 里存档的业务都会静默丢数据。#2907 是 `4459241c1d Acquirable strict (#2857)` 引入的回归，2025-09 至今零回应。这两条应合并成一个"优雅停服"工作项。

**③ 视野系统 / passenger 一族（#2081 + #2797 + #1750 + #1526）—— 4 条 issue 一个根因**
`Viewable` / `ViewEngine` / `EntityView` 三个类**在全部测试源中零提名**，而它们上面挂着 4 条 open bug，其中 #1750 带 `confirmed` 标签且 2025-10 仍有人确认复现。上游唯一在途 PR #2217 已闲置 805 天。这块是"上游明确不会修"的典型：没人愿意碰一个没有测试网的核心子系统。**我们的正确切入顺序是先补测试（用 `trackIncoming(SpawnEntityPacket/SetPassengersPacket)` 完全可以覆盖），再改逻辑。**

**④ 寻路（#3224 + #2813 + #2252 + #3054 + #2831）—— 5 条 issue，唯一在途 PR 已死**
`entity/pathfinding` 18 个主文件对 1 个测试文件、7 个 `@Test`。#3224 是 68 条 open bug 里为数不多 2026 年还有活动的；#3054 是崩溃级（NPE）；#2831 是竞态级崩溃。上游 PR #3006（寻路重写 + step-up）CI 红、闲置 244 天，#3056（NPE 修复）冲突且无测试。**结论：寻路必须自己扛**，且建议直接按"重写 + 用 `MockBlockGetter` 建纯算法测试"来做，而不是打补丁。

**⑤ 背包事务语义（#3027 + #2847 + #2514 + #2546）—— 唯一会造成"客户端-服务端物品数量不一致"的一族**
`TransactionType` / `InventoryClickHandler` / `EquipmentHandler` 全部零测试提名。#2847 的评论已经说清楚这不是单点 bug 而是"与原版背包机制的系统性差异"，#3027（双击收集事务）有视频复现且零评论。对任何有经济系统/交易的服务器，物品数量不一致是最高优先级的正确性问题。上游无任何在途 PR。

### 7.2 建议先行合入 fork 的上游 PR（cherry-pick 清单）

| 优先级 | PR | 理由 |
|---|---|---|
| P0 | **#3348** Palette#compare 修复 | 7 行 + 89 行测试，纯正确性 |
| P0 | **#3346** sendPackets 泛型协变 | 3 行，零风险 |
| P1 | **#3347** 网络错误抑制 flag | 直接治 #3320/#2241 的日志风暴；核心维护者作者 |
| P1 | **#3356** 邻居更新顺序 | 2 行 + 34 行集成测试，影响红石/重力方块 |
| P1 | **#3296** 可替换方块原地放置 | 4 行 + 29 行测试，修玩家可感知的放置不同步 |
| P2 | **#3288** registry tag 顺序 | 已被 approve，需先解冲突 |
| P2 | **#3048** 丢弃回显玩家输入 | 188 行测试很扎实，但被 request changes，需我们自己收尾 |

### 7.3 上游短期内不会修、必须我们自己扛的领域

按"open bug 密度 × 测试覆盖率 × 上游在途 PR 健康度"三维判断：

| 领域 | open bug | 测试引用率 | 上游在途 | 判定 |
|---|---|---|---|---|
| **寻路 / Navigator** | 5 | `entity/pathfinding` 18 类 / 7 `@Test` | #3006 CI 红、244 天 | **必须自扛** |
| **ViewEngine / passenger / Viewable** | 4 | `Viewable`/`EntityView` 零提名 | #2217 闲置 805 天 | **必须自扛** |
| **背包事务** | 4 | `TransactionType` 等零提名 | 无 | **必须自扛** |
| **碰撞箱正确性**（#3049/#2131/#1071） | 3 | `collision` 69%，但都是算法测试非数据测试 | 无（#3262/#3162 是性能优化且都是 draft/冲突） | **必须自扛**（本质是方块形状数据问题） |
| **Batch API**（#2766/#2526/#2486/#2540 系） | 4 | `instance/batch` 无独立测试 | #2982（approve 但未合）、#2791（draft 393 天）、#2448（684 天） | **自扛或整体替换**——三个互斥的重写方案锁死 |
| **命令 argument callback**（#1617/#1520/#1326/#466） | 4 | `command/builder` 24% | #2661 已关闭未合、#3058 冲突、#3129 CI 红 602 文件 | **必须自扛**；已有 3 次失败尝试 |
| **光照**（#3158） | 1（但含 3 个子问题） | 无独立光照测试（只有 `LightComputeBenchmark`） | #3295 提取公共 LightEngine，21 天前有动静 | **观望 + 准备自扛** |
| **停服 / 生命周期** | 2 | `ServerProcessImpl` 零提名 | 无 | **必须自扛** |

**反过来，可以继续依赖上游的领域**：协议编解码（232/238 包有往返测试、2026 年这类 bug 关得最快）、registry / codec（近 12 个月关闭的 bug 里占比最高）、版本适配（1.21.x 每次都由核心维护者一次性大 PR 带过）。

### 7.4 给我们 fork 的工程建议（与上游无关）

1. **把 jcstress 接进 CI**（上游没做）。现成 11 个场景 + 我们为 `EntityTracker`、`InstanceContainer#retrieveChunk` 新增的场景，是唯一能守住并发正确性的手段——而 open bug 里崩溃类 17 条中至少 5 条是并发问题。
2. **加覆盖率门禁**（JaCoCo，只对"新增行"设阈值）。不设全库阈值，否则 `entity/metadata` 202 个类会永远拖着。
3. **补一层"真 socket + 真 codec"的端到端测试**。现有 `testing/` 框架的 `trackIncoming` 断言的是对象，握手/登录/压缩/加密全裸。哪怕只写一个"起真服务器 → 用裸 socket 走完 handshake→login→config→play → 断言收到的字节"的冒烟测试，也能堵住 #3320/#2241/#2169 这一族。
4. **别启用测试并行**，除非先把 `MinecraftServer.serverProcess` 的静态共享处理掉——这是 `EnvTestExt` 的硬约束。
5. **重开或本地关闭那 8 条"疑似已修复"的 issue**，避免我们的 fork 继续为已消失的问题分配预算。

---

## ⑧ 数据口径说明与未能核查的项

### 8.1 关键命令（可复现）

```bash
# open bug（含正文与全部评论）
gh issue list --repo Minestom/Minestom --state open --label bug --limit 200 \
  --json number,title,createdAt,updatedAt,labels,comments,body,url

# closed bug
gh issue list --repo Minestom/Minestom --state closed --label bug --limit 200 \
  --json number,title,createdAt,closedAt,labels,stateReason,url,comments

# open PR（含 CI rollup 与 review）
gh pr list --repo Minestom/Minestom --state open --limit 200 \
  --json number,title,createdAt,updatedAt,additions,deletions,changedFiles,isDraft,\
author,labels,reviewDecision,mergeable,statusCheckRollup,reviews,comments

# 已合并 PR 吞吐
gh pr list --repo Minestom/Minestom --state merged --limit 300 \
  --json number,createdAt,mergedAt,author,additions,deletions

# 关闭方式（GraphQL，60 个 issue 一次批量查 CLOSED_EVENT.closer）
gh api graphql -F query=@q.graphql   # 见 8.2

# 测试规模
find src/main/java -name '*.java' | wc -l                            # 1413
find src/test/java -name '*.java' | wc -l                            #  247
grep -rh '@Test' src/test/java --include='*.java' | wc -l            # 1461
grep -rl '@EnvTest' src/test/java --include='*.java' | wc -l         #   89
grep -rn '@Disabled' src/test testing --include='*.java' | wc -l     #   12

# CI flaky 迹象
grep -rn 'continue-on-error\|retry\|if: failure\|fail-fast' .github/workflows/
grep -ri 'jcstress\|jmh' .github/       # 零命中
```

### 8.2 统计口径与已知偏差

1. **"测试覆盖"全部是静态估算，不是行覆盖率。** 禁止运行 Gradle，仓库也没有 JaCoCo/Kover。所有百分比是"**类的简单名在测试源中出现过**"，用 `\b[A-Z][A-Za-z0-9_]{2,}\b` 正则从 `src/test/java` + `testing/src` 全部 Java 源中抽取标识符集合后做交集。
   - **偏高方向**：`Foo.class` 作为事件类型参数、或作为某个包的字段类型出现，也算"被引用"，但不代表行为被断言（`crypto` 的 70% 就是这么来的）。
   - **偏低方向**：`listener` 包的类被集成测试间接执行但从不被点名，会被低估。
   - 因此本报告把它称为"**被测试引用率**"而不是"覆盖率"。
2. **`git log --grep "#NNNN"` 对 Minestom 不可靠。** 该仓库使用 merge queue，PR #2917 的 merge commit `276b647b` 根本不在 master 的祖先里；它的改动经 `d721bd11a8 1.21.9 (#2927)` 落地。因此所有"疑似已修复"的判定都改用**文件/测试是否存在 + `git log --diff-filter=A/D <path>`** 作为依据。
3. **"最后活动日期"用最后一条 comment，不用 `updatedAt`。** 68 条中有 15 条的最后评论是 2025-01/02 由 `TheMeinerLP` 发起的批量分诊追问（#2649/#2639/#2546/#2540/#2514/#2507/#2252/#2241/#2169/#2141/#2061/#1636/#1526/#1212/#1071），此后无任何回应。若用 `updatedAt`，这批 issue 会被误判为"2025 年还活跃"。
4. **closed bug 的子系统分类是关键词优先级匹配**，规则为：命令 → 物品/背包 → instance/世界 → 实体/玩家 → 线程/事件 → 网络/协议 → 构建。同一标题命中多类时取先匹配者，因此存在系统性偏差（例如同时提到 chunk 和 player 的会算进 instance）。数字应当作数量级参考，不作精确结论。
5. **PR 的 CI 状态口径**：33 个较老 PR 的 `statusCheckRollup` 为空，表示它们最后一次跑 CI 时现行 workflow 尚不存在，**不代表 CI 通过或失败**，重跑结果未知。
6. **严重度是人工判定**，按"用户能感知到什么"分级，与上游的标签体系无关（上游只有 `bug` / `confirmed` / `needs triage` / `needs investigation` / `Wait for user Action` / `wontfix`，且 43/60 已关闭 bug 到关闭那一刻还挂着 `needs triage`——说明这个标签事实上没有分诊含义）。

### 8.3 未能核查的项

1. **实际测试是否全绿、耗时多少**：禁止运行 Gradle，无法给出本地 `./gradlew check` 的通过率和时长。CI 的 `Build PR` 最近一次在 master 是绿的（`#3356` 的 rollup 显示 `JVM Check: SUCCESS`），但这只是采样。
2. **行/分支覆盖率**：无数据。5.2 的引用率不能替代它。
3. **flaky 的实际发生率**：GitHub Actions 的历史 run 重跑次数需要逐 run 拉 `gh run list --json`，本次未做（会是大量 API 调用）。目前只能从"workflow 里唯一一处 `continue-on-error` + release.yml 自认的 tracing agent 竞态"推断，**没有证据表明存在广泛的测试 flaky**。
4. **`jcstress` 是否曾经在某个历史版本进过 CI**：只核查了当前 HEAD 的 `.github/`，未追历史。
5. **68 条 open bug 中有多少在我们 fork 的实际使用场景下会被触发**：需要业务侧输入，本报告只给严重度不给业务优先级。
6. **"疑似已修复"里 palette 三连（#2540/#2547/#2507）的根因是否真的消除**：只能确认堆栈中的类已被删除，无法确认新 `PaletteImpl` 在并发下不会重现 `IntArrayList` 撕裂。需要一个 jcstress 场景来证伪，本次为只读核查未编写。
7. **`upstream-open-prs.md` 与实际 open PR 的 20 条差异**：已列出缺失编号，但那 20 条（全部超过 600 天、全部 CONFLICTING）未逐条深读，仅计入统计。

---

## ⑨ 参考链接

**上游**
- Issues（open bug）：https://github.com/Minestom/Minestom/issues?q=is%3Aissue+is%3Aopen+label%3Abug
- Pull requests（open）：https://github.com/Minestom/Minestom/pulls?q=is%3Apr+is%3Aopen
- CONTRIBUTING：https://github.com/Minestom/Minestom/blob/master/.github/CONTRIBUTING.md
- CODEOWNERS：`* @Minestom/core-maintainers`

**本报告直接引用的 commit**
| Hash | 说明 |
|---|---|
| `16842f195400d0ff4adb1713cd113dc241b5ad17` | 本次核查的 HEAD（2026-09-04） |
| `219f16bd4fad1c9cecf0654dd99382fedb2105d4` | fix: linear palette deserialization bug (#3097)，2026-04-11 → #2668 |
| `810c31725004b46504c8b846def65de5954d2844` | Match vanilla username regex (#3350)，2026-08-28 → #3231 |
| `aca0e0e7f10f4a1f5b619b5d25a6de7fbc5d4e30` | Entity selector fix (#3225)，2026-07-10 → #1918 |
| `d721bd11a8b8e5fa29cb5895a09f4e437d114d21` | 1.21.9 (#2927)，2025-10-11 → 承载 PR #2917 → #2682 |
| `d5c6126a6b8260dfc06033dc08bf6603bb8f1b57` | Fix LivingEntity Damage Event Packet (#2209)，2024-06-25 → #2061 |
| `0602bde228…` | Update Chunk View Distance (#2892)，2025-08-26 → #2455 → #2527 |
| `a04ce40dbb9b625078b22404fed4f37a9bb2b237` | Palette API cleanup (#2780)，2025-06-22 → #2540/#2547/#2507 的类已删除 |
| `7d9b94c1fcd018358014df4fd3ca616fb59fd1ee` | Improve inventory event pipeline (#2531)，2025-04-07 → 删除 `InventoryCondition` → #1932 失效 |
| `4459241c1d…` | Acquirable strict (#2857)，→ #2907 的回归来源 |

**本地关键路径（绝对路径）**
- 主代码：`/Users/bug/Documents/Projects/Minecraft/Minestom/src/main/java/net/minestom/server/`
- 测试：`/Users/bug/Documents/Projects/Minecraft/Minestom/src/test/java/`
- 测试框架：`/Users/bug/Documents/Projects/Minecraft/Minestom/testing/src/main/java/net/minestom/testing/`
- 并发测试：`/Users/bug/Documents/Projects/Minecraft/Minestom/jcstress-tests/src/jcstress/java/`
- 基准：`/Users/bug/Documents/Projects/Minecraft/Minestom/jmh-benchmarks/src/jmh/java/`
- CI：`/Users/bug/Documents/Projects/Minecraft/Minestom/.github/workflows/`
- errorprone / 自定义 check：`/Users/bug/Documents/Projects/Minecraft/Minestom/build-src/src/main/kotlin/minestom.style.gradle.kts`、`CheckAbiTask.kt`、`CheckImportsTask.kt`、`CheckTestNamingTask.kt`
- 测试 JVM 参数注入：`/Users/bug/Documents/Projects/Minecraft/Minestom/build-src/src/main/kotlin/minestom.java-library.gradle.kts`（第 67–77 行）
- 已核实未修的两处代码：
  - `/Users/bug/Documents/Projects/Minecraft/Minestom/src/main/java/net/minestom/server/instance/EntityTrackerImpl.java`（`removeFromChunk`，约 292–295 行）→ #2649
  - `/Users/bug/Documents/Projects/Minecraft/Minestom/src/main/java/net/minestom/server/instance/batch/AbsoluteBlockBatch.java`（`apply`，约 127–160 行）→ #2766
  - `/Users/bug/Documents/Projects/Minecraft/Minestom/src/main/java/net/minestom/server/ServerProcessImpl.java`（`stop()`，第 241–251 行）→ #2907
- 被禁用的回归测试：`/Users/bug/Documents/Projects/Minecraft/Minestom/src/test/java/net/minestom/server/entity/PlayerSkinTest.java`（`validName`/`invalidName` 均 `@Disabled`）→ 正对应 #3231
