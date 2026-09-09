# 03-instance-world 修复去向表

- 分支：`fix/instance`（worktree `/Users/bug/Documents/Projects/Minecraft/.worktrees/instance`），基于 `fork/main`（HEAD `2dbc3ed22b`）
- 全量测试：`./gradlew :test --continue` → **2508 tests / 0 failures / 0 errors / 17 skipped**（基线 2488 + 本轮新增 20 条测试）
- `./gradlew testNamingCheck forbiddenImportsCheck errorproneCheck` 全绿
- 共 21 个 commit，`git log fork/main..fix/instance --oneline` 可见；未 push

## 去向表

| 编号 | 去向 | commit | 测试 |
|---|---|---|---|
| P0-1 光照重算与区块写锁互锁 | 已修 | `735c5717cb` | `LightDeadlockIntegrationTest#relightInsideBlockHandlerDoesNotDeadlock` |
| P0-2 区块加载失败 → setInstance future 永不完成 | 移交实体（`entity/Player.java`） | — | — |
| P0-3 AnvilLoader 双 RegionFile 实例 | 已修 | `02d7b80e5c` | `AnvilLoaderIntegrationTest#saveWhileUnloadingKeepsRegionReadable` |
| P1-1 `EntityTrackerImpl.viewers` 泄漏 | 移交实体（`EntityTrackerImpl.java`） | — | — |
| P1-2 区块 viewer 用全局视距（#3021） | 已修 | `5f936677c0` | `ChunkViewerIntegrationTest#outermostChunkStillReceivesBlockChange` |
| P1-3 unloadChunk 踢掉玩家（#3083） | 已修 | `3196096fb4` | `UnloadChunkIntegrationTest#unloadingChunkRemovesEntitiesButKeepsPlayers` |
| P1-4 `Light#getNeighbors` 边界写错 | 已修 | `7d53920fc8` | `HighSectionLightIntegrationTest#blockLightPropagatesAcrossTopSections` |
| P1-5 `InstanceContainer#copy()` 四连问题 | 已修 | `c8f8109d1f` | `InstanceCopyIntegrationTest#copiedInstanceIsUsable` / `#copiedInstanceKeepsTickingBlocks` |
| P1-6 `DynamicChunk#tick` 无锁遍历 | 已修 | `7e9aab09c7` | `TickableBlockIntegrationTest#handlerRemovingItselfDoesNotSkipOtherBlocks` |
| P1-7 `chunkEntities` 空桶不回收 | 移交实体（`EntityTrackerImpl.java`） | — | — |
| P2-1 `Palette#compare` 混合格式 | 核实后非 bug（基线已含上游 PR #3348，`PaletteImpl:536-542` 已按存储格式分支比较） | — | 现有 `PaletteCompareTest` |
| P2-2 `RegionFile#readChunkData` 不校验长度 | 已修 | `b9d45a806e` | `AnvilLoaderIntegrationTest#corruptedChunkLengthIsReportedAsIO` |
| P2-3 `Heightmap#loadFrom` 不校验长度 | 已修 | `408076b7bb` | `ChunkHeightmapIntegrationTest#tooShortHeightmapDataIsRecomputed` |
| P2-4 `ChunkBatch#singleThreadFlush` 无锁 reset + 早退不 countDown | 已修 | `b6d9e0c516` | `BlockBatchIntegrationTest#fullChunkBatchRewritesTheChunk` / `#batchOnUnloadedChunkReleasesItsInverse` |
| P2-5 `AbsoluteBlockBatch` 空批次 / updated 死代码 / null 逆批次 | 已修 | `3ad3b9505a` | `BlockBatchIntegrationTest#emptyBatchStillCompletes` |
| P2-6 邻居更新顺序 | 核实后非 bug（基线 commit `2dbc3ed22b` 已按上游 PR #3356 改为 WEST,EAST,BOTTOM,TOP,NORTH,SOUTH） | — | 现有 `TargetedBlockUpdateIntegrationTest` |
| P2-6 顶部 Y 边界用 `height()` | 已修 | `1213d76c7b` | 无新增：该条件当前恒为 false，修正后无行为变化，由现有邻居更新测试兜底 |
| P2-7 方块碰撞形状不支持随机偏移（#3049） | 延后 | — | — |
| P2-8 `getBlockLight/getSkyLight` 不做 Y 边界检查 | 已修 | `5e7ddab566` | `InstanceBlockIntegrationTest#lightOutsideTheWorldIsZero` |
| P2-9 `Condition.CACHED` 抛 "Unloaded chunk" | 已修 | `72329dfb15` | `InstanceBlockIntegrationTest#cachedConditionReturnsNullInsteadOfThrowing` |
| P2-10 `sectionHeight` 重复加 minY | 已修 | `1986a7d6b8` | 无新增：纯剪枝范围变化，由 `LightParityIntegrationTest`（对照原版光照的 3×3 region）兜底 |
| P2-11 单值 palette 丢方块实体 / heightmap 不写回 | 已修 | `9f57d1b898` | `AnvilLoaderIntegrationTest#saveKeepsBlockEntitiesOfUniformSections` |
| 上游 PR #3296 可替换方块就地放置 | 已修（手动移植） | `fdf7c1bbc7` | `PlayerBlockPlacementIntegrationTest#placeAgainstReplaceableBlock`（取自该 PR） |
| P3-1 `parseCollisionShape` intern 失效 | 已修 | `12c5753e3d` | `CollisionShapeInternTest#equalShapesShareOneInstance` / `#equalOcclusionShapesShareOneInstance` |
| P3-2 `ChunkData#equals` 对 `long[]` 用引用语义 | 移交网络（`network/packet/server/play/data/ChunkData.java` 不属本次文件归属） | — | — |
| P3-3 `LightingChunk` 死的 `Set<Light> sections` | 已修 | `902dcb9fd1` | 无新增（纯死代码删除，`*Light*` 测试全绿） |
| P3-4 `Palettes.count(int,long[])` 语义可疑 | 跳过 | — | — |
| P3-5 `SharedInstance` 丢失 dimensionName | 已修 | `fb9090543c` | `SharedInstanceIntegrationTest#keepsTheContainerDimensionName` |
| P3-6 `Instance.blockRetriever` 共享可变 `ChunkCache` | 跳过 | — | — |
| P3-7 `BlockManager#getBlockPlacementRule` 全局 synchronized | 已修 | `1329e6dd84` | 无新增（去锁，无行为变化，现有放置规则测试兜底） |
| P3-8 `sweepBlocks` 无速度上限 | 跳过 | — | — |

## 跳过 / 延后的理由

- **P2-7（碰撞随机偏移）延后**：需要两件本轮做不到的事——① 注册表数据里目前没有 `offset_type` 字段（`grep -rn "offset_type" src` 无命中），要么重新生成注册表数据要么维护硬编码方块清单；② 偏移量必须与原版 `Mth.getSeed` 的位运算逐位一致，否则服务端与客户端的错位只是换了个方向。这是一个独立的、需要对照原版实现并配套 parity 测试的改动，不适合塞进本轮。
- **P3-4（`Palettes.count`）跳过**：全仓（`src`/`testing`/`jmh-benchmarks`/`jcstress-tests`/`demo`）无任何调用者，只有 `countEquals` 在用。它是 `public` 方法，"求和"还是"计数"无法从用法反推；改语义会静默改变公共 API 行为，删除又是 ABI 变更。留给上游澄清意图。
- **P3-6（共享 `ChunkCache`）跳过**：并发调用只会互相踢缓存，不会返回错误结果（每次调用都把 `chunk` 读进局部变量后再用）。可选的修法要么每次调用都新建对象（热路径上制造垃圾），要么用 ThreadLocal（虚拟线程下会按线程数膨胀），两者都需要 benchmark 支撑，不在"修 bug"范围内。
- **P3-8（`sweepBlocks` 无速度上限）跳过**：给速度设上限会改变物理行为（大速度弹射、坐骑、插件自定义抛射物都会受影响），属于产品决策而非缺陷修复。

## 需要主管注意的合并点

1. **`EntityTrackerImpl.java`（实体同事同文件）**：本轮按分工在这个文件里只改了 `ChunkView#collectPlayers`（原 `:340-345`）。为了让改动能编译，一并删掉了随之失效的 `ChunkView.point` 字段、它的构造赋值、以及只被它用到的 `Vec` import 与 `CHUNK_SIZE_X/CHUNK_SIZE_Z` 静态 import（Error Prone 的 `UnusedVariable`/`RemoveUnusedImports` 是 error 级，留着无法编译）。实体同事的 P1-1 会改同文件的 `ChunkViewKey#equals`、`viewers`、`TargetEntry`，与本改动**不重叠**，但 `ChunkView` 类体有邻接冲突风险。
   - 语义说明：新实现改为遍历 `tracker.entities(Target.PLAYERS)` 并用每个玩家自己的 `effectiveViewDistance()` 做切比雪夫距离判定，不再用 `nearbyEntitiesByChunkRange` 扫 17×17 区块。玩家数远小于 289 时更快，超大单实例（>289 在线）时更慢。
2. **`entity/Player.java` 未被本分支修改**，P0-2 / P1-1 / P1-7 三条完全留给实体同事。
3. **`listener/BlockPlacementListener.java`**：上游 PR #3296 的移植落在这里（不在文件归属清单里，若有同事也在动 listener 需要留意）。
4. **`InstanceContainer#copy()` 行为变化**：副本现在继承源实例的 chunkSupplier / generator / dimensionName，但**不**继承 `ChunkLoader`（共享 loader 会让副本写进源世界的存档）。如果有下游依赖"副本也能存盘"，需要显式 `setChunkLoader`。
5. **`AnvilLoader` 内部结构变化较大**：`getMCAFile` 被 `acquireRegionFile/releaseRegionFile/closeRegionFile` 取代，`fileCreationLock` 删除。任何基于旧结构的补丁都需要重写。
