# 大地图存储：.synx、Anvil、Polar、Slime、数据库五条路线对比（2026-09）

- 日期：2026-09-23
- 前提：[README.md](README.md) 第 7 节的用户拍板（Q3 地图人工制作、静态、按版本发布；Q4 每分片 200～500 人、大区 + 频道；Q7 世界高 256）；`docs/DESIGN-M3.md` v0.2 已写出 `.synx` 的具体布局（`API-M3-001`）、覆盖存储（`API-M3-007`）与编辑源格式的开放问题（§28.1）。本文对照的「.synx 形状」同时指 README §3.1 与 `API-M3-001`。
- 范围：只读调研，未改仓库代码，未改 `docs/DESIGN-M3.md` 与另一会话的 PostgreSQL + Redis 玩家数据工作。输入是五路调研（.synx、Anvil、Polar、Slime、数据库）；本文另外复核了五处源码（`BlockUtils.java:93-111`、`ChunkData.java:70-86`、`Chunk.java:45-71`、`DynamicChunk.java:280-306`、`DESIGN-M3.md` 的 `API-M3-001`～`007` 与 ADR）。
- 数字口径：沿用各路调研的核验标记。标「未核实」的是调研时没读到原始出处或程序已删无法复现；标「推算」的是由其他数字算出；标「自估」的是工作量估计。
- 读法：第 1 节结论，第 5 节是要你拍板的五件事，第 7 节是可直接合入 DESIGN-M3 的草案。

## 1. 结论

**推荐组合：Anvil（原版 26.2 存档）作编辑器交换格式 → `:baker` 离线烘焙器 → 运行时 `.synx`（mmap + 已成帧包体）；覆盖层存本地只追加分段文件，按频道、按 `build_id` 隔离；数据库不存区块字节，只存需要跨频道或跨地图版本保留的「世界状态标志」。** 方向与 DESIGN-M3 现文一致；本文补五路对比的依据，并列出 `.synx` 与覆盖存储要改的 9 处（第 4 节）。

Polar 与 Slime 两种格式都不进流水线。两者都是「整个世界一个压缩流、没有区块索引、整图进堆」，Polar README 自述不适合大世界，Slime 的同一结论由格式结构推断（`SLIME_FORMAT` v13 与 ASP README 没有这句）；它们唯一的长处是按方块名存调色板、升 MC 版本时不必回到编辑器，但 Anvil 源存档同样按名字存，且有原版 `--forceUpgrade` 负责升级，所以多一层中间格式没有收益。

| 路线 | 放在流水线哪一段 | 不选作运行时格式的主因 |
| --- | --- | --- |
| **.synx（推荐作运行时）** | 烘焙产物，分片 mmap | 不适用（已选）；代价是包体绑协议 776、压缩阈值 256、生物群系注册表，升版全量重烘，且新 `build_id` 会连带丢弃覆盖层（F1） |
| **Anvil（推荐作源格式）** | 美术产物、烘焙器输入、光照对拍真值 | 负载是 zlib NBT：每次发包要 inflate + NBT 解析（本机 0.080～0.093 ms/区块）+ 调色板重建 + 编码（0.84 ms/区块，未核实）；4 KiB 扇区补齐浪费 25.8%～29.9% |
| Polar | 不用（可选作离线中间格式，没有收益） | 单文件单 zstd 帧，解压体上限 2,147,483,647 B；一个 4k×4k 大区解压约 1.67 GB（推算，算式见第 9 节），距上限只剩 22%；运行时仍要编码 |
| Slime | 不用 | 同上的单流无索引；加载器接口只有整世界 `readWorld / saveWorld`；参考实现 ASP 是 GPL-3.0 |
| 数据库（PG / Redis / LMDB / RocksDB） | 底图不用；覆盖层默认不用 | 吞吐不是瓶颈（每分片 18～41 MB/s 点读，局域网 PG / Redis 能承担）。不选的理由有三：底图不可变，库的写能力与事务没有消费者；库客户端的缓存在进程私有堆里，同机多个频道进程各缓存一份，mmap 的页缓存只占一份；库成为每个分片取区块的运行时依赖，库抖动直接变成全部分片的区块加载失败 |

## 2. 逐维度对比

单元格只写结论和关键数字，依据见第 8 节来源与各路调研原文。

| 维度 | .synx | Anvil | Polar | Slime | 数据库 |
| --- | --- | --- | --- | --- | --- |
| 人工静态大地图、按版本发布 | 强：不可变构建产物，`content_hash` 即版本；不能当「源」，因为包体绑协议。发布 = 频道进程以新 `build_id` 重启切换（DESIGN-M3 §11.2），全部分片不再挂载旧版本后才能 `retire` | 强（作源）：WorldEdit、Axiom、Litematica、Amulet、WorldPainter 全部读写 Anvil，没有一个写得出 .synx | 作分发单元可以，作运行时不行：读任一区块都要解压整文件 | 部分：ASP 有 readOnly 加载参数（在 API 里，不在格式里：`SLIME_FORMAT` v13 的世界标志只有 `POI_CHUNKS` / `FLUID_TICKS` / `BLOCK_TICKS`）；大区整块约 140 MB（推算，两个系数都未核实）无索引 | 负分：发布变成「导入 4.19M 行」，`payload_sha256` 逐文件校验要改成逐记录逻辑哈希 |
| 每分片 200～500 人 | 强：未改区块发送 = mmap 读 + 一次 memcpy；500 人 3.4k～7.8k 块/s × 5.26 KB（未核实）≈ 18～41 MB/s memcpy | 不达标：3～7 核只做区块编码（推算） | 不改善：装载后仍走 `createChunkPacket`；`PolarLoader.loadSection` 每 section 4,096 次 `palette.set` | 不改善：同 Polar，存的是 NBT 调色板 | 吞吐够：18～41 MB/s 点读局域网 PG / Redis 能承担；但每个进程要自带缓存才能避开逐次往返。LMDB 可做到与文件同等的一次拷贝 |
| 大区 + 频道，同大区多进程共享只读地图 | 强：同机 N 个频道进程 mmap 同一文件，页缓存只占一份。前提是同机且同 inode（频道容器挂同一只读卷）；每个容器各拷一份就不共享 | 只共享文件字节，每进程各自解码进堆 | 无共享：每进程解压到私有堆或私有 arena；一个大区载入成 `DynamicChunk` 约 65,536 × 57 KiB ≈ 3.6 GiB/进程（推算，57 KiB 未核实） | 无共享：每进程整块解压解析 | PG / Redis 的「共享」是网络共享，每进程仍要本地缓存 |
| 区块自动卸载 | 格式让它变便宜：句柄区块只剩 `Chunk` 基类字段 + dispatcher 分区；卸载逻辑引擎自己补 | 与格式正交；只读地图常开句柄即可 | 反作用：`unloadChunk` 触发整块重序列化；Streaming 模式卸载后无来源 | 做不到真释放：ASP 卸载只是转回骨架放回 map，堆下限是整张图 | 与介质正交；覆盖层进 PG 时卸载要等 2 s 超时的网络写，数据库抖动会让卸载与淘汰整批失败、堆上涨 |
| 烘焙光照 | 强：LightData 在段① 里，服务端 0 CPU | 存档光照「可选且有条件」：原版只在 `isLightOn=true` 且状态 ≥ light 时信任；26.1 重存的夹具 0/1,024 区块有光照。适合作对拍真值，不能当成品 | 格式够用（4 态 + 2,048 B），自己不算光，光要从上游带入 | 格式够用（标志位 + 2×2,048 B） | 无影响 |
| 升 MC 版本的重烘成本 | 算力便宜、必须全量：外推 4.19M 区块单线程约 17 分钟（基准未核实），按大区并行。另一项成本：覆盖层与 `build_id` 绑定，地图内容不变、只升协议也会丢弃全部玩家改动（F1） | 最省：源存档按方块名存，原版 `--forceUpgrade --eraseCache` 升 DataVersion，DataFixer 不进我们的代码。`--eraseCache` 会删 `Heightmaps` 与 `isLightOn`（javap `WorldUpgrader`），升级后必须重跑预生成才有光照真值；26.4 起区块根标签 `Status` 改小写 `status`、生物群系逐方块存储（minecraft.wiki Chunk format），读取器要跟着改 | 有 `PolarDataConverter` 钩子，但方块改名要自己写规则 | ASP 靠 Paper DataConverter（GPL-3.0、依赖 net.minecraft），我们不能用 | 救不了段①；覆盖层存逻辑差分才能跨重烘，与介质正交 |
| 编辑器 → 烘焙器流水线 | 不定义这一段 | 所有编辑工具的产物；被删的 `AnvilLoader` + `RegionFile`（917 行）可从 `74592d8fdf^` 复活到 `:baker`，但它只读 `region/`，展示框、画、盔甲架、`*_display` 实体在同级 `entities/` 里，要补读取。服务端专用数据（handler、服务端 NBT）编辑工具写不出，要靠标记约定（REQ-M3-022） | 多一次转换，无收益 | 多一次转换，无收益 | 无位置 |
| 覆盖层持久化 | 覆盖记录复用区块记录编码（`API-M3-007`）；现文只在淘汰与卸载时写，崩溃丢失窗口无上限（F4），每条 `fsync` 在最坏 320 条/tick 时超 tick 预算（D9） | 可用不优：每次写区块重分配扇区、重写 8 KiB 头；原版先写数据扇区再写头，单区块是旧或新二选一，缺的是 fsync（`sync-chunk-writes`）与多区块一致性 | 不适合：保存是整文件 `TRUNCATE` 重写 | 不适合：保存是整世界重序列化 | 唯一有可比性的维度：LMDB 省掉手写整理与崩溃恢复；PG 只在「跨频道共享改动」时值得（F2） |
| 正式档 32k×32k、4k×4k 大区 | 4,096 个 32×32 文件，每大区 64 个；段① 全图 22 GB（5.26 KB/区块，未核实） | 作源：33～35 GB、4,096 个 .mca（实测 7,868～8,296 B/区块含补齐，原版生成地形） | 必须按大区切文件；单大区解压约 1.67 GB，距 2,147,483,647 B 上限只剩 22% | 必须按大区切；MySQL loader 的 MEDIUMBLOB 上限 16,777,215 B 装不下一个大区 | 按 D1 / D2 后的口径约 30 GB + 段③（段① 22 GB + 段② 2.3 GB + 段④ 5.4 GB，段③ 待 T1）；PG 另需每频道本地缓存，Redis 要同量常驻内存 |

三个跨路线一致的发现，直接影响 `.synx` 设计：

1. 三路（Anvil、Polar、Slime）独立量到的压缩后体积落在同一区间：Anvil zlib NBT 5,513～6,153 B/区块，Polar bench.polar zstd 5.56 KB/区块（1.19.4 原版地形 1,024 区块，压缩 5,694,711 B），README 合成地形包体 5.26 KB/区块（未核实）。这说明 5.26 KB 作规划值不离谱，但三者都是高 384 的原版或合成地形，美术地图的方块实体与调色板密度没有任何测量。
2. 三路都指出「原版光照可以作烘焙真值」：Anvil 存档里 `isLightOn=true` 的区块带原版算好的 `SkyLight` / `BlockLight`。DESIGN-M3 的 `ADR-M3-005` 已用原版服务端生成对拍夹具；本文补一条：在真实美术地图上，烘焙器还能免费把 `LightEngine` 输出与源存档光照逐 nibble 对拍，覆盖夹具测不到的方块组合。
3. 数据库路线与 Slime 路线都查到 Hypixel 的先例：Slime 字节存 SeaweedFS、元数据存 Mongo，但运行时是整岛进内存，不是按区块读库。对象存储的正确位置是「地图版本分发通道」（按 `build_id` 内容寻址下载到分片机），不是运行时存储。

## 3. 推荐方案与理由

```
美术（原版 26.2 创造模式 + WorldEdit 7.4.5 / Axiom 6.0.5 / Litematica 0.28.8）
   │   服务端专用数据：DATA 模式结构方块 + metadata 标记（REQ-M3-022）
   └─► 源存档 <world>/dimensions/minecraft/overworld/{region,entities}/*.mca（DataVersion 4903）
         │  ① 版本不对：原版服务端 --forceUpgrade --eraseCache（会删 isLightOn 与 Heightmaps）
         │  ② 原版 26.2 专用服务端 + Chunky 预生成，把 bounds 内区块逼到 full、isLightOn=true
         │  ③ 校验 isLightOn 覆盖率 = 100%，然后交付；①→②→③ 顺序不能换
         ▼
:baker --source anvil:<dir>（复活的只读 Anvil 读取器，不回引擎）
   ├─ LightEngine 算光照 ──► 与源存档光照逐 nibble 对拍（有光照的区块）
   ├─ PacketWriting 同一条成帧路径 ──► 段①
   └─ 导航层 / 全部方块实体 NBT + 标记映射出的 handler / 静态实体 / 段④ ──► 段②③④
         ▼
<maps_root>/<map_id>/<build_id>/regions/r.<rx>.<rz>.synx（不可变，对象存储按 build_id 分发）
         ▼
分片进程：SynxChunkLoader mmap（同机频道进程挂同一只读卷）──► 句柄区块发 BufferedPacket
          被改区块 ──► DynamicChunk 覆盖层 ──► <work_dir>/overlay/<build_id>/seg.<n>.synx（每频道一份）
          跨频道 / 跨版本的世界状态 ──► 玩家与世界状态库（PostgreSQL，另一会话的轨道）
```

**源格式选 Anvil 而不是自研或 Polar。** 美术工具生态只产出 Anvil；Anvil 的 1,024 项位置表支持按区块随机读，烘焙器按 32×32 区域并行（`ADR-M3-004`）正好对齐一个 `.mca`；原版服务端能替我们做 DataVersion 升级，Mojang 的 DataFixer schema 与 fix 类（26.2 server jar 的 `util/datafix/schemas/` 下 125 个 class、不含内部类 119 个；`fixes/` 下 293 个、不含内部类 271 个；按 `unzip -l` 数 `.class` 条目）在专有 jar 里，本来就不能随烘焙器分发。复活读取器自估 4～5 人日，要改五处：只读打开（被删的 `RegionFile` 以 `"rw"` 打开，文件短于 8 KiB 时会写头）、`Status` / `status` 两种大小写都读且只接受 full（被删代码只读小写 `status`，26.2 写大写，于是原型区块被当成完整区块，`anvil_vanilla_sample` 里有 23 个这样的区块；minecraft.wiki Chunk format 写 26.4 起改为小写），版本只靠 `DataVersion == 4903` 门禁；删掉标 `@Deprecated` 的 `region/` 构造器（`dimensions/<ns>/<path>/region` 路径已在 `AnvilLoader(Path, Key)` 里，`git show 74592d8fdf^:…/AnvilLoader.java:95`）；方块实体按坐标保存完整 NBT，不经 `Block.fromKey`（见 D2）；补读 `entities/r.<x>.<z>.mca`（26.2 `EntityStorage`，被删代码没有）。

**运行时选 .synx + mmap。** 用户要求同一大区多个频道进程共享只读地图，mmap 是五条路线里唯一能让同机频道进程共用一份地图内存的方案，前提是这些进程在同一台机器上映射同一个文件（同一只读卷，不是各拷一份）；引擎已有 `BufferedPacket(buffer, index, length)` 与 `NetworkBuffer.wrap(MemorySegment, …)`，发送路径不需要新类型。这已由 `ADR-M3-001` 接受；DESIGN-M2 §28.3 ② 登记的「构造时整文件读进不可变快照」口径与此冲突，按 DESIGN-M3 §1 的处置由 `API-M3-001` 取代。

**覆盖层存本地只追加分段，不进数据库。** 理由有三。第一，`ADR-M3-007` 定了「同大区不同频道的玩家互相不可见」，若地形改动跨频道同步，玩家会看到门被看不见的人打开，语义自相矛盾，所以覆盖层天然每频道一份，不需要共享存储。第二，覆盖层进 PG 会把 PG 的故障半径从「登录、转服失败」扩大到「每个分片的卸载与淘汰被阻塞、堆上涨」。第三，覆盖层每频道私有、只有一个写者，数据库的并发控制与事务没有消费者。确实需要跨频道一致的东西（任务解锁的桥、世界事件开的门）建模成世界状态标志存 PG，由各频道进程按标志改自己的覆盖层。标志是这类方块的唯一真相源：频道进程起服、切换新 `build_id` 时按全部标志重放一遍，重放是幂等的「把目标格设成标志规定的状态」；覆盖层与标志冲突时以标志为准并记 WARN。

切换条件：`API-M3-007` 的整理、引用计数、崩溃尾部截断在测试里反复出问题，或实现超过 2 人周 → 覆盖存储换 LMDB（lmdbjava，Apache-2.0；LMDB 本体 OpenLDAP Public License），wrapper 自估 3～5 人日，值内沿用 crc32c 补 LMDB 没有的页校验和；JPMS 与原生库分发的接入成本未核实。

## 4. 与报告 .synx 形状的差异，需要改的地方

对照 README §3.1 与 DESIGN-M3 `API-M3-001` / `API-M3-005` / `API-M3-007` 的现文。D1～D8 改格式与加载，D9 改覆盖存储的写入时机。已经被 DESIGN-M3 吸收的不再列（碰撞位集按 section 标志稀疏存、头部记压缩阈值与注册表摘要、覆盖存储只追加）。

| # | 现文 | 问题 | 改法 | 依据 |
| --- | --- | --- | --- | --- |
| D1 | 段② 含 1 bit/方块碰撞位集（上界 512 MiB/大区） | 位集没有消费者：实体物理走 `BlockCollision` → `getter.getBlock(pos, Condition.TYPE).collisionShape()`，要的是 `Block` 对象；`API-M3-005` 的 `getBlock` 已经按需解码 section 进 LRU 缓存 | 段② 只留 walkable 位集 32 B + surface_y 512 B（M2 导航层）；碰撞位集删除，等自研寻路或服务端射线真的要它时再以新 `format_version` 加回 | `collision/BlockCollision.java:93`、`:307-339`；`Instance.java:741-783` |
| D2 | 段③ = deflate 的引擎 section 序列化（调色板 + 光照）+ 方块实体 NBT，估 ≈ 345 MB/大区 | 调色板与光照在段① 里已有一份：段① inflate 后按 `ChunkDataPacket.SERIALIZER` 读回，`Palette.read` 能重建调色板，heightmap（`Heightmap.loadFrom`）、光照（`Light.set(byte[])`）都在包里。**方块实体不能从段① 重建**，原因有两层：`ChunkData.readBlockEntities` 用 `Block.fromKey(blockEntity.key())` 找同名方块，找不到就 `continue` 丢掉 NBT，26.2 的 49 个方块实体类型里 7 个没有同名方块（`sign`、`hanging_sign`、`mob_spawner`、`skull`、`banner`、`shelf`、`brushable_block`），告示牌文字、旗帜图案、头颅皮肤会静默丢失；有 `BlockHandler` 的方块只下发 `getBlockEntityTags()` 声明的标签，包里也不带 handler 命名空间 | 段③ 收窄为「方块实体与静态实体表」：删掉调色板与光照的重复，保留**全部**方块实体的 {section 内坐标, handler 命名空间（可空）, 完整 NBT}，另加一张静态实体表（源自 `entities/`，见 REQ-M3-020）。物化覆盖层与 `getBlock` 的方块状态从段① 解码，方块实体只从段③ 取，不经 `readBlockEntities`。`region_content_hash` 改为对段① 的**未压缩**包体（帧内 zlib 流解开后）+ 段②③④ 取哈希，保持「换 JDK 自带 zlib 不改变内容哈希」 | `BlockUtils.java:93-111`（本文复核）；`Palette.java:262-290`；`ChunkData.java:70-90`（`:78-80` 为 `fromKey` 丢弃）；`Section.java:30-41`；26.2 `block_entity_type.json` 对 `block.json`（评审者核对，本文未重跑） |
| D3 | `getBlock` 「只解码被读的那个 section」 | 段③（或 D2 后的段①）是整块一个 deflate 流，不能只解一个 section，任何读取都先付一次整块 inflate | 缓存粒度改为「整块解码结果」或写明「先整块 inflate、只保留被读 section」；`synaxis.synx.section-cache-bytes` 的计量对象随之写清 | `API-M3-001` 段③ 定义 |
| D4 | handle 模式 `SynxChunk`「堆里只有坐标、段偏移与票据计数」 | `SynxChunk extends Chunk` 会带基类的 `UUID.randomUUID()`、`ReentrantReadWriteLock`、`TagHandler`、`Viewable`，且每个已载入区块在 `ThreadDispatcher` 占一个分区；每块几百字节（结构推算，未测），65,536 块约数十 MB | 描述改为实测口径，由 `REQ-M3-013` 量「句柄区块常驻字节」；只有实测超过预算才考虑把基类字段改成按需创建（触碰面大，默认不做） | `Chunk.java:45-71`（本文复核）；`InstanceContainer.java:789-798` |
| D5 | 覆盖存储整理后「句柄区块持有段的引用计数，计数归零后才删文件」 | 出站队列是无界的 `mpscUnboundedArrayQueue`，排队中的 `BufferedPacket` 也引用映射段；每玩家最坏 10 批 × 64 块在飞。若按区块引用计数就关闭 Arena，排队中的包会读到已解除映射的内存 | 覆盖段用 `FileChannel.map(…, Arena.ofAuto())` 映射，解除映射交给 GC 可达性（排队中的包持有段就不会被回收）；删文件只做 unlink。区域文件进程内常驻，用 `Arena.ofShared()` 不关。另一处缺口：映射长度在 `map` 时固定，之后追加的字节对已有映射不可见，现文没说活动段怎么读；改为活动段预分配 256 MiB 稀疏文件、创建时一次映射到封口上限，读者按索引里的 offset 读 | `PlayerSocketConnection.java:97`、`:472-477`；`Player.java:208,851`；FFM 语义为 JDK 知识，未实测 |
| D6 | 覆盖层 LRU 上限按 57 KiB/区块堆算 | `DynamicChunk` 的 `CachedPacket` 包体经 `NetworkBufferImpl.copy()` 放在 `Arena.ofAuto` 分配的堆外内存，挂在 `SoftReference` 上，不计入堆；57 KiB 不含它 | `overlay_cap` 的内存预算写成「堆 + 堆外包体」两项，监控加 NMT 或 `BufferPoolMXBean` 口径 | `NetworkBufferImpl.java:247-253`；`CachedPacket.java:104` |
| D7 | 物化覆盖层「解码段③ 全部 section 与光照」 | 光照数组必须灌回 `Section.skyLight/blockLight`：`DynamicChunk.createLightData` 对无数组的 section 置 `emptySkyMask`，漏灌一次，改过一个方块的区块对新观察者全黑。现文已写「overlay 模式的 section 带烘焙光照」，缺一条可证伪的测试 | §19 加场景：句柄区块 setBlock 一次后，给新观察者发的包里地表以上 section 的 sky mask 与段① 一致 | `DynamicChunk.java:329-360` |
| D8 | `SynxChunkLoader` 构造时「映射全部区域文件」；§2 写「每个大区进程只 mmap 自己那份」 | 两处口径不一：若一个 `build_id` 是整张 32k 地图，「全部」是 4,096 个文件；而视距 12 在大区边缘会跨进邻区 | 写明映射范围 = 本大区 64 个文件 + 视距覆盖到的邻区边缘文件；mmap 惰性，多出的只是虚拟地址与 fd。或者规定大区边界由地形屏障保证视距内看不到邻区，则 `bounds` 外返回 `null` 就够，需在 `API-M3-010` 写死这条美术约束 | `API-M3-005`；README §3.4 |
| D9 | 覆盖存储只在 `overlay.evict` 与 `chunk.unload` 时写（§11.1），每条记录追加后 `fsync` | 两处缺口。其一，没有周期性落盘：常驻覆盖层最多 `overlay_cap` = 24,576 区块，进程崩溃时这些区块自上次淘汰以来的改动全部丢失，窗口无上限。其二，最坏 320 条/tick（`overlay.evict` 64 + `chunk.unload` 256），单次 `fsync` 按 0.2 ms 计（常识值，未实测）就是 64 ms/tick，超过 50 ms tick 预算 | 脏区块每 `synaxis.overlay.flush-interval-s`（默认 10 s，F4）重编码追加一次，每 tick 最多 64 条，分摊到多个 tick；group commit：一个 tick 末尾阶段的全部追加合并成一次 `fsync`，之后再更新内存索引，每秒最多 20 次 `fsync` | `DESIGN-M3.md:394`、`:404`、`:514-518`；T8、T10 |

另有两处口径更正，不改格式：

- README §3.3「兜底下发按玩家限速 64 区块/s」与引擎单位不一致：`ServerConfig.maxChunksPerTick` 是 64 块/tick = 1,280 块/s。要 64 块/s 须把 `synaxis.chunk-queue.max-per-tick` 设为 3.2（`ServerConfig.java:207-209`、`Player.java:870-913`）。零编码之后速率完全由客户端 `ChunkBatchReceived` 回报决定，流控本身不用改。
- README §4.3 与 DESIGN-M3 §28.2 的「逐玩家 AES-CFB8 CPU」在当前引擎不存在：`Auth` 是 sealed 的 Offline / Velocity / Bungee 三种，`NetworkBuffer.cipher()` 在 `src/main` 无调用点。它在切 online 模式时才出现（DESIGN-M3 §28.2 已登记对外发布前要切）；原版协议的加密层包在成帧之外、对整条出站字节流做 CFB8，已成帧字节在 flush 时整段加密即可（协议分层为调研者知识，未重读协议页，未核实）。`REQ-M3-013` 的 AES 基准保留，但它衡量的是未来成本。

## 5. 需要你拍板的分叉

| # | 问题 | 默认选项 | 切换条件 | 切换代价 |
| --- | --- | --- | --- | --- |
| F1 | 玩家对地形的改动，在地图发新版本（新 `build_id`）后要不要保留？ | 不保留。覆盖层随 `build_id` 整体丢弃（`API-M3-007` 现状）；要跨版本保留的东西（开过的门、修好的桥）建模成世界状态标志存 PG，由玩法代码在新版本上重放 | 玩法要求玩家造的、炸的地形跨地图版本保留；或升版频率高到玩家可感知（`build_id` 每次升 MC 协议也会变，地图内容不改也会清空覆盖层；按一年升 2 次以上协议算即应切换） | 覆盖层改存方块级逻辑差分（坐标 → 命名空间方块状态 + NBT），装载时应用到新底图，底图该格也变了时按「覆盖层优先并记日志」；重启后差分区块要重跑 3×3 光照。自估 1～2 人周，与存储介质无关 |
| F2 | 同一大区的多个频道，地形改动要不要互相可见？ | 不可见，每频道一份覆盖层（与 `ADR-M3-007` 一致） | 设计上「频道只是分流，世界是同一个」 | 覆盖层进 PG 做单写者 + LISTEN/NOTIFY 失效 + 给观察者重发 3×3，自估 2～3 人周；要先改 `ADR-M3-007`；PG 成为每个分片的运行时硬依赖，故障半径扩大到全部分片的卸载路径 |
| F3 | 美术用什么工具做图？ | 原版 26.2 创造模式 + WorldEdit 7.4.5（GPL-3.0-only，免费）+ Litematica 0.28.8（LGPL-3.0-only）；地形底稿可用 WorldPainter（GPL-3.0，26.2 支持未核实）。这些工具都是独立进程，与 Apache-2.0 引擎无链接关系 | 美术需要 Axiom 的多人协作建图或它的专属笔刷 | 购买 Axiom Commercial License（Modrinth 标 LicenseRef-All-Rights-Reserved，多人与商用须购买，价格与「commercial purposes」定义未读原文）；Amulet 同为付费专有，只在跨版本转换或 Bedrock 时才用得上 |
| F4 | 进程崩溃时，覆盖层改动可以丢多少秒？ | 10 s：脏区块每 10 s 重编码追加一次（D9） | 玩法把地形改动与物品产出挂钩（挖方块得稀有物品），丢窗口会造成刷物或回档投诉 → 降到 1～2 s | 窗口每缩一半，定期写入的条数翻一倍；T10 量出每 tick 开销后才知道 1 s 是否可行。窗口不能降到 0：真正要求不丢的改动应建模成世界状态标志存 PG |
| F5 | 美术地图里允许实体（物品展示框、画、盔甲架、`block_display` / `item_display` / `text_display`）吗？ | 允许：`:baker` 读 `entities/`，产出段③ 静态实体表，`SynxChunkLoader` 载入区块时生成 | 美术约定只用方块造景 | 禁止时 `verify` 检查 `entities/` 为空即可，省下读取器与静态实体表约 2～3 人日（自估）；代价是展示类实体全部要由玩法代码在运行时生成 |

DESIGN-M3 §28.1 的「编辑源地图的格式」开放问题，本文建议关闭为已决（Anvil），不再占拍板名额：五路调研没有任何一路给出替代源格式，自研编辑器的工作量未估且 M2 已明确不做编辑器。

## 6. 需要实测才能定的项

| # | 测什么 | 决定什么 | 在哪测 |
| --- | --- | --- | --- |
| T1 | 第一张美术地图（至少 1 个 32×32 区域）的段① 字节/区块、方块实体数/区块、Anvil 源字节/区块 | README §4、DESIGN-M3 §2 的全部磁盘与带宽数字；三路调研的 5.3～6.2 KB 都来自原版或合成地形 | `worldProbe` 加一档美术地图输入（`REQ-M3-013`） |
| T2 | 从段① 解码一个区块（inflate + `ChunkDataPacket.SERIALIZER` 读回 + 建 `DynamicChunk`）的耗时，对照从独立段③ 解码 | D2 是否成立：物化结果与烘焙前逐 section 方块状态相等、方块实体逐项相等（坐标、handler、NBT）是前提；若段① 解码比段③ 慢一个数量级以上且模拟距离内 `getBlock` 缓存命中率低，保留完整段③ | JMH，本机 |
| T3 | 句柄区块常驻字节（`SynxChunk` + dispatcher 分区），65,536 块 | D4；是否需要对未改区块也积极卸载 | `worldProbe` 堆类指标 |
| T4 | mmap 段 + `NetworkBuffer.wrap` + `BufferedPacket` 在写线程上的吞吐；冷页缺页对虚拟线程载体的阻塞 | 500 人档能否只靠页缓存；是否要在起服时 `madvise(WILLNEED)` 或预读 | soak（`REQ-M3-014`），冷缓存与热缓存两档 |
| T5 | `refreshType=ALWAYS` 时 `ThreadDispatcherImpl.refreshThreads` 对 65,536 个分区的轮询耗时 | 空间分格 `ThreadProvider`（`API-M3-009`）的 refreshType 取值 | JMH |
| T6 | 修复后的 `LightEngine` 与源存档原版光照在美术地图上的逐 nibble 差异 | 烘焙器的「源存档光照对拍」能否设为门禁（差异 0）还是只报告 | `:baker verify` |
| T7 | 先 `--forceUpgrade --eraseCache` 再 Chunky 预生成后的 `isLightOn` 覆盖率；Chunky 能否把 `bounds` 外区块排除 | 交付前处理步骤是否可靠；覆盖率不到 100% 时 T6 只能抽样 | 原版 26.2 服务端，一次性 |
| T8 | 覆盖层稳态写入率（加一档「改方块」机器人负载） | 覆盖存储选追加分段还是 LMDB 的容量依据；F2 若切换时 PG 的容量规划 | soak |
| T9 | 客户端收到烘焙光照后、服务端不发 `UpdateLightPacket` 时的行为（P1①） | 覆盖层区块改动后是否要给在看的客户端重发完整区块包 | 真客户端，已在 DESIGN-M3 §17 |
| T10 | 本机 SSD 单次 `fsync` 延迟；脏区块定期写入（每 tick 64 条重编码 + 1 次 `fsync`）的 tick 开销 | D9 的 group commit 是否够用；F4 的窗口下限 | JMH + soak |

## 7. 供 DESIGN-M3 合入的要点

以下是草案，按 DESIGN-M3 的 ID 规则写；合入与否由 DESIGN-M3 那条轨道决定。

**格式（改 `API-M3-001`，`format_version` 仍为 1，因为尚未发布过任何文件）**

- 段②：只含 walkable 位集 32 B + surface_y 256 × i16 = 544 B 定长；删除 section 标志与碰撞位集（D1）。
- 段③：改名「方块实体与静态实体表」，整段 deflate。方块实体子表 `count u32` + 每项 `{index u16（section 内 ((y&15)×16+z)×16+x）, section_y i8, handler_len u8, handler UTF-8（无 handler 时长度 0）, nbt_len u32, nbt（未压缩二进制 NBT，完整，含客户端标签）}`，覆盖区块内**全部**方块实体；静态实体子表 `count u32` + 每项 `{nbt_len u32, nbt（源 `entities/` 里该实体的完整 NBT，含 `id` 与 `Pos`）}`。区块内两者都没有时长度 0（D2、F5）。
- `region_content_hash`：按索引顺序拼接每条记录的「段① 帧内 zlib 流解压后的包体」+ 段②③④ 解压后字节，取 SHA-256（D2）。
- 清单 `manifest.json` 加 `source.data_version`（源存档 DataVersion，必须 = 4903）、`source.kind`（`anvil` / `generator`）与 `source.light_coverage`（源区块 `isLightOn = true` 的比例）。

**接口**

- `API-M3-003` 烘焙器：`--source anvil:<dir>` 读 `<dir>/dimensions/<ns>/<path>/{region,entities}/*.mca`，只读打开；任一区块 `DataVersion ≠ 4903` → 退出码 2，并在错误信息里提示「先用原版 26.2 专用服务端 `--forceUpgrade --eraseCache` 升级源存档，再重跑预生成」；`Status ≠ minecraft:full` 的区块视为源缺失，同样退出码 2。`verify` 增加一项：源区块 `isLightOn = true` 时，`LightEngine` 输出与源 `SkyLight` / `BlockLight` 逐 nibble 比较，差异数写进清单 `verify.source_light_diff`。
- `API-M3-005`：`getBlock` / 物化的方块状态从段① 解码，方块实体只从段③ 取（不经 `ChunkData.readBlockEntities`），按 handler 命名空间经 `BlockManager` 挂回 handler；载入区块时按段③ 静态实体表生成实体；缓存粒度按整块解码写明（D2、D3）。handle 模式的内存描述改为「`Chunk` 基类字段 + dispatcher 分区，实测值见 `REQ-M3-013`」（D4）。映射范围写明（D8）。
- `API-M3-007`：覆盖段用 `Arena.ofAuto()` 映射，整理后只 unlink 旧段、不显式解除映射；活动段预分配 256 MiB 稀疏文件并一次映射（D5）。写入改为：脏区块每 `synaxis.overlay.flush-interval-s`（默认 10）秒重编码追加一次，每 tick 最多 64 条；一个 tick 末尾阶段的全部追加合并为一次 `fsync`，`fsync` 成功后才更新内存索引（D9、F4）。§16 登记「进程崩溃丢失最近 ≤ flush-interval 秒的覆盖层改动」。
- `API-M3-010` 或 `API-M3-012`：频道进程起服与切换新 `build_id` 时，按 PG 里的全部世界状态标志重放；重放幂等（把目标格设成标志规定的状态）；覆盖层与标志冲突时以标志为准并记 WARN。部署前提写进 §2「分布与形态」：同机频道进程挂同一只读卷，映射同一 inode。
- §2「磁盘」行：按 D1 / D2 重算，每大区段① 345 MB + 段② 36 MB + 段④ 80 MiB + 段③（待 T1），全图约 30 GB + 段③。
- `ADR-M3-011` 与 §15 的 `overlay_cap` 内存预算分堆与堆外两项（D6）。

**需求**

| ID（草案） | 描述 | 优先级 |
| --- | --- | --- |
| REQ-M3-020 | 离线 Anvil 读取器：从 `74592d8fdf^` 复活 `RegionFile` 读路径与 `AnvilLoader` 的 section / 调色板 / 生物群系 / 方块实体解码，放 `:baker`，不回引擎、不进 `module-info`；只读打开；`Status` 与 `status` 两种大小写都接受，版本只靠 `DataVersion` 门禁；只保留 `dimensions/<ns>/<path>/` 构造器；方块实体按坐标保存完整 NBT；另读 `entities/r.<x>.<z>.mca` | P0 |
| REQ-M3-021 | 烘焙器用源存档的原版光照做第二道对拍（仅报告，差异门槛待 T6 定） | P1 |
| REQ-M3-022 | 编辑源交付规范（给美术）：26.2 存档；处理顺序固定为「需要时 `--forceUpgrade --eraseCache` → 原版服务端 + Chunky 预生成到 `full` 且 `isLightOn` → 校验覆盖率 100%」，升级后必须重跑预生成；服务端专用数据用 DATA 模式结构方块标记：`metadata` 写 `<handler 命名空间> <SNBT>`，作用于结构方块正下方一格，烘焙器把结构方块替换为空气并生成段③ 记录，未登记的命名空间 → 退出码 2；隐藏区域用同类标记圈出（供 LOD 排除，README §8.3）；规范写进 `docs/`，不是代码 | P1 |

**验收判据草案（进 §19）**

```gherkin
场景: Anvil 源的 DataVersion 门禁 (REQ-M3-020)
  Given 一个源存档，其中 1 个区块 DataVersion = 4790
  When 运行 baker bake --source anvil:<dir>
  Then 退出码为 2，不产生任何输出目录，错误信息含该区块坐标与 "--forceUpgrade"

场景: 原型区块不被当成完整区块 (REQ-M3-020)
  Given 一个源存档，其中 1 个区块 Status = minecraft:carvers
  When 运行 baker bake
  Then 退出码为 2，错误信息含该区块坐标

场景: 源里的结构方块标记映射成段③ 记录 (REQ-M3-022)
  Given 源存档 (10, 64, 10) 是 DATA 模式结构方块，metadata = "synaxis:door {locked:1b}"，(10, 63, 10) 是 oak_door
  When 运行 baker bake，随后以 handle 模式加载该区块并 setBlock 物化为 overlay
  Then (10, 64, 10) 为 air；(10, 63, 10) 的 handler 命名空间为 synaxis:door，NBT 含 locked:1b
  And 段① 包体里该方块实体只含 handler 声明的客户端标签

场景: 告示牌文字在物化后不丢 (D2)
  Given 源存档一个区块含 1 块 oak_sign，正面第 1 行文字为 "Synaxis"
  When 烘焙后以 handle 模式加载并在同区块另一格 setBlock 物化为 overlay
  Then 物化后该告示牌的 NBT 与源存档逐项相等，正面第 1 行仍为 "Synaxis"

场景: 源存档的实体随区块生成 (F5)
  Given 源存档 entities/ 里一个区块含 1 个 item_frame 与 1 个 text_display
  When 烘焙后分片载入该区块
  Then 区块内生成这 2 个实体，类型、坐标与 NBT 与源一致

场景: 覆盖层崩溃丢失窗口有上界 (D9)
  Given flush-interval = 10 s，一个 overlay 区块在 t = 0 s 被 setBlock
  When 进程在 t = 12 s 被 kill -9，随后重启
  Then 该区块从覆盖存储载入后含 t = 0 s 的改动

场景: 一个 tick 的覆盖追加只 fsync 一次 (D9)
  Given 同一 tick 内 overlay.evict 淘汰 64 个区块、chunk.unload 卸载 256 个 overlay 区块
  When 该 tick 末尾阶段完成
  Then 覆盖存储新增 320 条记录，fsync 调用计数加 1

场景: 物化后新观察者不变黑 (D7)
  Given 一个句柄区块，段① 中地表以上 section 的 sky mask 全部置位
  When 在该区块 setBlock 一次，随后一个新玩家进入视野
  Then 新玩家收到的区块包中地表以上 section 的 sky mask 与段① 相同，且 empty sky mask 不含这些 section

场景: 排队中的句柄包在覆盖段整理后仍可发出 (D5)
  Given 一个玩家出站队列中有 64 个引用覆盖段 seg.3 的 BufferedPacket，且 seg.3 被整理标记为待删
  When 整理完成、seg.3 被 unlink，随后出站队列排空
  Then 64 个包全部发出且字节与整理前一致，进程无 IllegalStateException 与崩溃
```

## 8. 许可汇总

| 组件 | 许可 | 用法 |
| --- | --- | --- |
| 被删 Anvil 读取器（本仓库 git 历史，原 Minestom） | Apache-2.0 | 复活进 `:baker` |
| adventure-nbt 5.2.0 | 已在 `libs.versions.toml` | 读 Anvil NBT |
| Polar（hollow-cube） | MIT | 不用；若将来要作中间格式，可移植 844 行 Reader/Writer，另需 zstd-jni（BSD-2-Clause） |
| Advanced Slime Paper / SWM | GPL-3.0 | 不能进引擎，只能作离线工具或净室参考 |
| Mojang DataFixer schema / fix | 专有（在 server jar 里） | 只让运维在本地跑原版服务端 `--forceUpgrade` |
| PaperMC DataConverter | GPL-3.0 | 不用 |
| WorldEdit / Chunky 预生成器（pop4959，Modrinth 26.2 Fabric 版 1.5.3，2026-05-04）/ WorldPainter | GPL-3.0(-only) | 美术工具，独立进程 |
| Litematica | LGPL-3.0-only | 美术工具 |
| Axiom、Amulet | 专有，商用须购买 | F3 |
| lmdbjava / LMDB | Apache-2.0 / OpenLDAP Public License | 覆盖存储的备选 |
| RocksDB | GPLv2 / Apache-2.0 双许可 | 不推荐（每进程 block cache 不共享） |
| PostgreSQL JDBC、HikariCP、Lettuce、Jedis | BSD-2 / Apache-2.0 / Apache-2.0 / MIT | 未读 LICENSE 原文，未核实 |

## 9. 未核实事项

- 5.26 KB/区块、57 KiB/区块、0.84 / 5.18 ms/区块、烘焙 65 s / 262,144 区块全部来自已删除的临时程序（README §8.1）。Anvil 路线的 0.080～0.093 ms/区块与 5,513～6,153 B/区块同样来自已删探针（McaProbe.java），输入是 git 历史里的两个原版夹具。
- Slime 大区 140 MB = 5.26 KB ÷ 2.5（Hypixel 博客对自家岛屿数据的压缩比），两端都不是本项目数据。
- Polar 数字的算式：hollow-cube/polar 仓库 `src/test/resources/bench/bench-nozstd.polar` 26,088,862 B（1.19.4 原版地形，1,024 区块）× 64 = 1,669,687,168 B ≈ 1.67 GB（1.56 GiB），即一个大区的解压体（评审者读到文件大小，本文未重读）；每进程载入成 `DynamicChunk` 的 3.6 GiB = 65,536 × 57 KiB，57 KiB 未核实。均未跑。
- `FileChannel.map(…, Arena)` 与本仓库 `NetworkBuffer.wrap` 的组合没在 JDK 25 上编译过；本机默认 `java` 为 17.0.16。
- minecraft.wiki Chunk format 写 26.4 起生物群系逐方块存储、`Status` 改小写 `status`（评审者读到 wiki 原文）；未读 Mojang 快照更新日志。下次升版时 Anvil 读取器的 biomes 解析与段① 都要改。
- 单次 `fsync` 0.2 ms 是常识值，未在本机量（T10）。
- 7 个无同名方块的方块实体类型由评审者对 `data-26.2-rv3.jar` 的 `block_entity_type.json` 与 `block.json` 核对，本文未重跑。
- WorldPainter 对 26.2 的支持，Axiom 价格与商用定义，DFU 引擎的 MIT 许可原文，Sponge Schematic v3 规范原文，均未读。

## 10. 参考来源

- 本仓库源码：`network/packet/server/{CachedPacket,BufferedPacket,SendablePacket}.java`、`network/player/PlayerSocketConnection.java`、`network/{NetworkBuffer,NetworkBufferImpl,Auth}.java`、`instance/{Chunk,DynamicChunk,InstanceContainer,EntityTrackerImpl}.java`、`instance/block/BlockUtils.java`、`instance/palette/Palette.java`、`collision/BlockCollision.java`、`entity/Player.java`、`ServerConfig.java`、`MinecraftConstants.java`；`git show 74592d8fdf^:src/main/java/net/minestom/server/instance/anvil/{AnvilLoader,RegionFile}.java`。
- `docs/DESIGN-M3.md` v0.2：`API-M3-001`～`007`、`ADR-M3-001`～`012`、§2、§28。
- 原版 26.2 server jar（`~/.gradle/caches/fabric-loom/26.2/minecraft-extracted_server.jar`）javap：`RegionFile`、`RegionFileVersion`、`SerializableChunkData`、`ChunkStatusTasks`、`DimensionType#getStorageFolder`、`net.minecraft.server.Main`、`util/worldupdate/WorldUpgrader`（`eraseCache` 分支删 `Heightmaps` / `isLightOn`，评审者 javap）、`chunk/storage/EntityStorage`；`util/datafix/{schemas,fixes}/` 的 class 计数（`unzip -l`）。
- minecraft.wiki：Region file format、Chunk format、Data version（2026-09-23 抓取）。
- github.com/hollow-cube/polar（README、FORMAT.md、源码，2026-09-23）。
- github.com/InfernalSuite/AdvancedSlimePaper（SLIME_FORMAT、LICENSE、源码）；roxymcdev/SlimeLoader；cody-quinn/SlimeLoader。
- Hypixel Dev Blog #5（2019-07-23）：https://hypixel.net/threads/dev-blog-5-storing-your-skyblock-island.2190753/
- Modrinth API（WorldEdit、Axiom、Chunky、Litematica，2026-09-23）；Amulet-Core、WorldPainter、chunky-dev LICENSE。
- PostgreSQL 16 文档 storage-toast；Redis persistence 文档；LMDB `lmdb.h`；RocksDB wiki Read-only-and-Secondary-instances；luben/zstd-jni README。
- https://hytale.com/news/2025/12/hytale-hardware-requirements；https://github.com/luanti-org/luanti/blob/master/doc/world_format.md；https://github.com/MultiPaper/MultiPaper。
