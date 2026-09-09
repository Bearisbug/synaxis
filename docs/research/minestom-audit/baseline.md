# Minestom 基线（fork HEAD 16842f1954，2026-09-09，JDK 25.0.4.1，Gradle 9.7.1）

- `./gradlew :test`：BUILD SUCCESSFUL，1m41s，2480 个测试，0 失败，0 错误，17 跳过
- 结果文件 246 个；依赖解析需走阿里云镜像 init 脚本（Maven Central 经本机代理握手不稳）

## 跳过的测试

- net.minestom.server.utils.TestMojangUtils#testValidNameWorks()  
- net.minestom.server.utils.TestMojangUtils#testNonExistentUuidReturnsNull()  
- net.minestom.server.utils.TestMojangUtils#testGetValidUUIDWorks()  
- net.minestom.server.utils.TestMojangUtils#testInvalidNameReturnsNull()  
- net.minestom.server.utils.TestMojangUtils#testValidUuidWorks()  
- net.minestom.server.utils.TestMojangUtils#testValidUUIDWorks()  
- net.minestom.server.utils.TestMojangUtils#testGetInvalidNameThrows()  
- net.minestom.server.utils.TestMojangUtils#testInvalidUuidReturnsNull()  
- net.minestom.server.utils.TestMojangUtils#testGetValidNameWorks()  
- net.minestom.server.entity.PlayerSkinTest#validName()  
- net.minestom.server.entity.PlayerSkinTest#invalidName()  
- net.minestom.server.utils.TranslationIntegrationTest#testItemStackTranslation(Env)  org.opentest4j.TestAbortedException: Assumption failed: assumption is not true
- net.minestom.server.utils.TranslationIntegrationTest#testTranslationDisabled(Env)  org.opentest4j.TestAbortedException: Assumption failed: assumption is not true
- net.minestom.server.utils.TranslationIntegrationTest#testTranslationEnabled(Env)  org.opentest4j.TestAbortedException: Assumption failed: assumption is not true
- net.minestom.server.utils.TranslationIntegrationTest#testUpdateScorePacketTranslations(Env)  org.opentest4j.TestAbortedException: Assumption failed: assumption is not true
- net.minestom.server.item.enchant.SerializationContextTest#testValueEffectSerializationCompoundCustom()  org.opentest4j.TestAbortedException: Assumption failed: TODO(1.21.5)
- net.minestom.server.tag.TagNbtTest#emptyCompoundSerialization()  

## 按包统计测试数

- network: 687
- coordinate: 380
- tag: 151
- command: 138
- instance: 128
- codec: 117
- entity: 110
- collision: 106
- instance.palette: 84
- item.component: 61
- instance.generator: 54
- instance.block.predicate: 48
- entity.player: 43
- utils: 35
- inventory: 34
- instance.light: 32
- listener: 28
- utils.block: 25
- event: 24
- item: 18
- instance.anvil: 17
- thread: 16
- utils.entity: 14
- component: 12
- timer: 11
- world: 9
- inventory.click.integration: 8
- entity.pathfinding: 7
- utils.collection: 7
- inventory.click: 7
- dialog: 6
- network.player: 5
- particle: 5
- instance.block.handler: 5
- (root): 4
- network.packet: 4
- network.socket: 4
- item.enchant: 4
- registry: 3
- utils.chunk: 3
- snapshot: 3
- advancements: 3
- utils.time: 3
- entity.attribute: 2
- adventure.nbt: 2
- instance.gamerule: 2
- recipe: 2
- entity.metadata.animal: 2
- color: 2
- game: 1
- test: 1
- ping: 1
- network.debug: 1
- entity.ai: 1

## 最慢的 8 个测试

- 1.31s net.minestom.server.timer.TestScheduler#durationTask()
- 1.31s net.minestom.server.timer.TestScheduler#durationEndOfTick()
- 0.53s net.minestom.server.ServerProcessTest#registries()
- 0.46s net.minestom.server.instance.anvil.AnvilLoaderIntegrationTest#loadVanillaRegion(Env)
- 0.33s net.minestom.server.instance.anvil.AnvilLoaderIntegrationTest#saveChunks(Env)
- 0.29s net.minestom.server.instance.BlockLightMergeIntegrationTest#testDiagonalRemoval2(Env)
- 0.19s net.minestom.server.advancements.AdvancementIntegrationTest#addAndRemoveViewer(Env)
- 0.16s net.minestom.server.instance.anvil.AnvilLoaderIntegrationTest#loadAndSaveBlockNBT(Env)
