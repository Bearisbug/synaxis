# 02-entity 修复去向表

- 分支：`fix/entity`（worktree `/Users/bug/Documents/Projects/Minecraft/.worktrees/entity`）
- 基线：`2dbc3ed22b`（我开工时的 `fork/main`；期间主管已把 item / inventory / command 合进 `fork/main`，我的分支未 rebase）
- 全量测试：**2543 个用例，0 失败，0 错误，17 skipped**（`./gradlew :test --continue --rerun-tasks`）
  - 基线为 2488，新增 55 个用例（21 个新测试类 + 3 个已有类里追加的用例）
- 提交数：31，全部 `fix(entity): ...`，无任何 AI / Claude 署名

---

## ① 02-entity.md 的发现

| 编号 | 去向 | commit | 测试 |
|---|---|---|---|
| P0-1 EntityTracker 双重泄漏 | 已修 | `df60770c0b` | `instance/EntityTrackerLeakTest`（4 个用例，修前 4 个全红） |
| P0-2 异步续体跨线程改状态 | 已修 | `dbfd5d9444` | `entity/EntityAsyncContinuationIntegrationTest` |
| P0-3 remove 与异步 setInstance 竞态 | 已修 | `7584fd66ff` | `entity/EntityRemoveDuringSpawnIntegrationTest` |
| P1-1 switchEntityType 踢玩家（#3057）+ getPlayerMeta 硬转型（#2152） | 已修 | `caa32741d4` | `entity/SwitchEntityTypeIntegrationTest` |
| P1-1 附带 P2：switchEntityType 丢弃全部 metadata | 已修 | `31d77b11b2` | `SwitchEntityTypeIntegrationTest#switchKeepsTheSharedMetadata` |
| P1-2 manualViewers 泄漏断线玩家 | 已修 | `30bd0dc5ce` | `entity/ManualViewerLeakIntegrationTest` |
| P1-3 assertOwnership 零调用点 | **部分修**（见 ③） | `c2fcdbccf6` | `entity/AcquirableStrictIntegrationTest` |
| P1-4 任何非空气方块阻断行走 / WaterNodeGenerator 失效 | 已修 | `9bcf23794f` | `pathfinding/NodeGeneratorBlockingTest` |
| P1-5 寻路读未加载区块 NPE（#3054） | 已修 | `a4c5cf5f25` | `pathfinding/PathfindingUnloadedChunkIntegrationTest` |
| P1-6 setHealth 不钳下界 / isDead 单向闩 / kill 不幂等 | 已修 | `7e1cff0e55` | `entity/LivingEntityDeathIntegrationTest` |
| P1-7 MetadataHolder.entries 无同步读-复制-写 | 已修 | `f8230e35c6` | `MetadataHolderConcurrencyTest#concurrentWritesAreNotLost` |
| P1-7 同族 P2：setNotifyAboutChanges 不可重入 + 丢更新 | 已修 | `313f9927f9` | `MetadataHolderTest#nestedNotifySuppressionKeepsOneBatch` |
| P1-8 位掩码从 0 起算（Mannequin） | 已修 | `f8230e35c6` | `MetadataHolderConcurrencyTest#disablingOneMannequinPartKeepsTheOthers` |
| P1-9 AttributeInstance 缓存丢更新 | 已修 | `c4c200a39e` | `attribute/AttributeInstanceConcurrencyTest`（500 轮压测，修前 3/3 稳定复现） |
| P1-10 药水效果不重发 | 已修 | `adf9304718` | `entity/EntityEffectResendIntegrationTest` |
| P1-11 target / goalPosition 永不清除 | 已修 | `d0aa05ce58` | `ai/GoalStateResetIntegrationTest` |
| P1-12 setInstance 取消时返回 null | 已修（`.join()` 部分见 ③） | `a9682a5b68` | `entity/CancelledInstanceChangeIntegrationTest` |
| P1-13 EntityPose.SWIMMING 不可达 | 已修 | `2f0fff72d9` | `player/PlayerSwimmingPoseIntegrationTest` |
| P2-1 updatePose 覆盖 DYING | 已修 | `726e923377` | `entity/EntityPoseIntegrationTest` |
| P2-2 Player#setHealth 发未钳制值 | 已修 | `e4e113b962` | `LivingEntityHealthIntegrationTest#playerHealthPacketIsClamped` |
| P2-3 改 MAX_HEALTH 不重新钳制 | 已修 | `08502278bb` | `LivingEntityHealthIntegrationTest#loweringMaxHealthClampsCurrentHealth` |
| P2-4 裸 LivingEntity 只有 1 血 | 已修 | `fcb8da5c9e` | `LivingEntityHealthIntegrationTest#livingEntitySpawnsAtFullHealth` |
| P2-5 updateViewerRule 对非玩家 NPE | 已修 | `98f93b8e1c` | `entity/NonPlayerViewerOptionIntegrationTest` |
| P2-6 EntityView.Option#update 持锁回调锁序反转 | **跳过**（见 ③） | — | — |
| P2-7 手动 addViewer 不发乘客包 | 已修 | `b3b7dfae36` | `entity/ManualViewerPassengerIntegrationTest` |
| P2-8 refreshPosition 早退比错对象 | 已修 | `4d7dad31b6` | `entity/EntityRefreshPositionIntegrationTest` |
| P2-9 MetadataDef.count 拆箱 NPE | 已修 | `6b8cd00ab8` | `entity/MetadataDefCountTest` |
| P2-10 URLUtils 无超时 + errorStream NPE（#3231） | 已修 | `21fe22d7ee` | `utils/url/URLUtilsTest`（本地 HttpServer，修前复现 NPE） |
| P2-11 Rabbit/Parrot 枚举名与原版不符（#3141） | 已修 | `9731669883` | `metadata/VanillaVariantNameTest` |
| P3-1 Entity#updatePose 缺 SLEEPING | 已修 | `726e923377` | `EntityPoseIntegrationTest#sleepingEntityUsesTheSleepingPose` |
| P3-2 SnowGolem 掩码字节默认值 | 已修 | `7b333dd128` | `MetadataHolderConcurrencyTest#snowGolemFlagsDefaultToTheHatBeingOn` |
| P3-3 nextSynchronizationTick 绝对赋值 | 已修 | `b1937ea8b3` | 无专门用例（纯时序，靠全量回归兜底） |
| P3-4 PhysicsUtils#updateVelocity 丢弃 x/z 速度 | **跳过**（见 ③） | — | — |
| P3-5 unloadChunk 连带 remove 玩家 | **跳过**（不归我） | — | — |

## ② 跨报告归我的四条

| 编号 | 去向 | commit |
|---|---|---|
| 01-network P1-8（EntityTracker 双重泄漏） | 已修，与 02/P0-1 同一处 | `df60770c0b` |
| 03-instance P0-2（区块加载失败 latch 永不 countDown） | 已修 | `377edda484` |
| 03-instance P1-1（viewers 无界泄漏） | 已修，与 02/P0-1 同一处 | `df60770c0b` |
| 03-instance P1-7（chunkEntities 空桶不回收） | 已修，与 02/P0-1 同一处 | `df60770c0b` |

额外：③ 上游 issue 复核表里的 **#2352**（`LivingEntity#updateNewViewer` 无条件发 `set_equipment`）也顺手修了，
commit `c8597a4f99`，测试 `entity/NonLivingEquipmentIntegrationTest`。它不是编号发现，但是一行修复且有开放 issue。

## ③ 跳过 / 部分修复的理由

**P1-3 assertOwnership —— 部分修复**

已加调用点：`Entity#setVelocity`、`#addPassenger`、`#removePassenger`、`#remove(boolean)`、`LivingEntity#setHealth`。
全量测试只暴露 4 个 `EntityVelocityIntegrationTest` 用例是从 JUnit 主线程直接改已分发实体，已改成
`entity.acquirable().sync(...)`（这本来就是文档要求的写法）。

审计建议里剩下的没加，逐条理由：

- `setInstance` / `teleport`：这两个方法**设计上就允许任何线程调用**（返回 future、内部自己切线程）。
  在入口断言所有权会让 `acquirable().sync(e -> e.teleport(x).join())` 这种正确写法直接死锁
  ——调用方持有该实体 tick 线程的锁，而续体在虚拟线程上又要拿同一把锁。
- `setPositionInternal` / `refreshCoordinate`：加上后有 14 个既有用例失败，实测原因是
  `AcquirableImpl.assertOwnership` 对**尚未分发**的实体（`assignedThread == null`）要求当前线程等于
  `initThread`，而区块加载续体永远跑在虚拟线程上。这种状态下**任何线程都无法取得所有权**
  （`sync()` 会抛 `IllegalStateException`），要修必须改 `AcquirableImpl` 本身 —— 那是线程同事的文件。
  **建议线程同事考虑**：`assignedThread == null` 时让 `assertOwnership` 放行，或让 `sync()` 在未分发时可用。
- `MetadataHolder#set`：`MetadataHolder` 不持有实体引用（构造参数只是一个 `Consumer`），拿不到 acquirable。

`AcquirableEntityIntegrationTest:23` 那行注释掉的断言**没有取消注释**，因为它断言的正是 `setInstance`，
按上面第一条我没有在那里加断言。等价能力由新增的 `AcquirableStrictIntegrationTest` 覆盖。

**P1-12 `Entity#addPassenger` 里的 `.join()` —— 未改**

审计建议把 `Entity.java:1099` 的 `.join()` 换成续体。核对后：该调用只在
`!currentInstance.equals(entity.getInstance())` 时触发，目标坐标就是**载具当前所在位置**，
其区块必然已加载，`loadOptionalChunk` 直接返回已完成的 future，续体内联执行，不会真的阻塞。
把同步 API 改成异步会改变 `addPassenger` 的公开契约（调用方紧接着就读 `getPassengers()`），
收益不抵风险，留给需要改 API 的那一轮。

**P2-6 EntityView.Option#update 锁序反转 —— 跳过**

修法必须把 `updateAuto` / `updateRule0` 拆成「持锁收集目标 → 出锁执行回调」，
这会改变 viewer 集合更新的原子性（收集与发包之间实体可能已经离开范围）。
审计自己标注死锁「【需要运行验证】」，且 `Entity.java:585` 的 javadoc 已经写明该方法可能死锁。
风险大于收益，建议放进一次专门的 EntityView 锁模型评审，不要混在缺陷修复里。

**P3-4 PhysicsUtils#updateVelocity —— 跳过**

`collision/PhysicsUtils.java` 不在我的文件归属内（实体层只审到与 `movementTick` 的交界），
且改动会移动所有实体的落点，`EntityVelocityIntegrationTest` / `EntityPhysicsIntegrationTest`
里有一批写死到小数点后 15 位的期望坐标，必须先和原版逐 tick 对齐才能动。留给碰撞专项。

**P3-5 InstanceContainer#unloadChunk 连带 remove 玩家 —— 跳过**

`instance/InstanceContainer.java` 归 instance 同事（我只在那里加了一行 `unregisterViewable`）。

## ④ 需要主管注意的合并点

1. **`entity/Player.java`** 改动最多（10 处），但**没有碰 `UNSAFE_init()`（约 :339）附近**，
   与网络同事的 `pendingInstance` 空检查不冲突。我改的位置：
   `setInstance` 的 `whenComplete`、`spawnPlayer` 的取消检查与乘客补发、`remove(boolean)`（加
   `synchronized` + 反向摘 manual viewer）、`respawn`/`refreshClientStateAfterRespawn`、
   `updatePose`（DYING 早退 + `shouldSwim`）、`setHealth`、`getPlayerMeta` / `getAdditionalHearts` /
   `setAdditionalHearts` / `refreshSettings`。
2. **`instance/` 下我只动了三处极小 hunk**，都是 P0-1 需要的：
   - `Chunk.java`：`private final Viewable viewable` → 包级可见（1 行，与上游 `fix/viewable-leak` 一致）
   - `InstanceContainer.java#unloadChunk`：新增一行 `getEntityTracker().unregisterViewable(chunk.viewable)`
   - `EntityTracker.java` / `EntityTrackerImpl.java`：按归属表归我，改动较大
   如果 instance 同事也吸收了上游 `fix/viewable-leak`，**这三处会冲突**，以我的版本为准：
   上游那版没修 `ChunkViewKey` 的引用比较（真正让 `viewers` 永不复用的根因），也没处理
   空桶删除与 `computeIfAbsent` 的竞态。
3. **没有改 `ServerFlag`**。`URLUtils` 的超时用的是文件内私有常量（5s），没有引入新 flag，
   避免和其他分支抢 `ServerFlag.java`。
4. **`utils/url/URLUtils.java` 与 `utils/mojang/MojangUtils.java`** 各一个小 hunk（P2-10），
   不在任何人的禁改清单里，但如果有人也在动 Mojang 请求路径需要留意。
5. **行为变更需要 changelog 提一句的**：
   - `Entity#setInstance` 事件被取消时从返回 `null` 改成返回 failedFuture（原来必 NPE，不算破坏）
   - `Player#getPlayerMeta()` 从抛 CCE 改成返回 `null`（签名加了 `@Nullable`）
   - `MetadataDef.Entry.BitMask` / `ByteMask` 两个 record 各多一个 `containerDefault` 组件（公开 API）
   - `EntityTracker` 新增 `unregisterViewable(Viewable)`（`@ApiStatus.Internal`）
   - `EntityTracker#chunkEntities(x, z, target)` 空区块现在返回 `List.of()` 而不是一个会随后填充的活视图
