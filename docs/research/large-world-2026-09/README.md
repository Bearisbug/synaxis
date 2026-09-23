# 大世界、远视距与光影：Synaxis 做大型 MMORPG 的技术路线（2026-09）

- 日期：2026-09-23
- 范围：只读调研，未改仓库代码。输入是 6 个调研维度（客户端远景、客户端光照与光影、服务端流送、服务端光照、MMO 先例、自研渲染器）、3 套候选方案、3 位评审（工程成本 / 玩家体验 / 服务端规模与运营）和一轮逐条事实核验。核验判为不成立的说法没有写进本文；核验判为「无法复现」的数字标「未核实」。
- 读法：第 1 节是结论，第 7 节是需要你拍板的问题，其余各节是依据。

## 1. 结论

**推荐「混合 LOD」方案**：近处 12 区块用原版区块，区块包在出图时预先烘焙好（含光照），服务端运行时只读盘写 socket；远处到 4,096 格由我们在 client-fabric 里自写的高度场 clipmap 渲染，数据在出图时烘焙，客户端按地图内容哈希从 CDN 下载一次；光影由我们自己的着色器实现，OpenGL 和 Vulkan 两个后端共用，Iris 只作玩家自选项。

调高原版视距到不了 MMO 要的视距。26.2 客户端渲染距离上限 32 区块（512 格），全分辨率网格的显存按 πr² 增长，r=64 仅顶面就要 264～369 MB；服务端每多发一个区块，按本轮实测要多付 0.84 ms（不带光照）或 5.18 ms（带光照）的 CPU（未核实，见第 8 节）。远景只能走 LOD（细节层次），而 LOD 的数据从哪来、谁来画，决定了三套候选方案的区别。

| 方案 | 远景由谁画 | 三位评审打分（工程 / 体验 / 运营） | 不选它的原因 |
| --- | --- | --- | --- |
| **混合 LOD（推荐）** | 自写高度场 clipmap | 6 / 7 / 7.5 | ——（代价见下） |
| 保守：原版数据 + Distant Horizons | Distant Horizons 3.3.x（DH） | 7.5 / 6 / 7 | 整套方案压在一个没读过源码的假设上：DH 会认放进多人数据目录的预烘焙数据库。每个大区的 DH 包按 0.2～0.4 GB 估（系数没有出处），比高度场包的 5～21 MiB 大 1～2 个数量级；每次升级 DH 要重烘全部大区包。光影只能靠 Iris，Iris 不支持 Vulkan，且 26.2 上有一例同配置 138 → 84 FPS 的回归（Iris #3241）。不开光影时画面只有原版 lightmap，达不到「光影渲染」的要求 |
| 激进：客户端接管整张地图 | 自写高度场 clipmap | 4 / 5 / 5.5 | P1～P5 自估 16～27 人周（未核实），挂点最多（ClientPacketListener、ClientChunkCache、FogRenderer、Blaze3D 四组），每次升 MC 都要重对。整张地图落在客户端，隐藏区域可被解包。在 fabric.mod.json 里把 Iris 声明为 breaks。它的核心收益「游戏服区块带宽降为 0」在内测档 200 人时不是瓶颈（混合方案算出 108～184 Mbit/s） |

评审意见不一致：工程成本评审选了保守方案，理由是自研渲染器会长期占掉 1～3 人团队里的一个人。另两位选了混合方案。本文采纳多数意见，同时把保守方案的 DH 路线保留为「高画质档」和「自研远景失败时的退路」，切换条件写在第 7 节 Q1 和第 5 节 P3。

混合方案的代价有三条：高度场画不出洞穴口、拱桥、建筑立面，192 格外的城堡会变成色块；地图必须基本静态、按版本发布；远景和光影的着色器要自己写和维护，每次升 MC 估 2～5 人日（未核实）。

## 2. 现状与瓶颈

### 2.1 原版 26.2 客户端的硬限制

| 限制 | 数值 | 出处 |
| --- | --- | --- |
| 渲染距离滑块 | 2～32 区块，默认 12；`Runtime.maxMemory() < 1e9` 时上限 16 | javap `net.minecraft.client.Options` 构造器 offset 4896-4953（已核验） |
| 雾终点 | 渲染距离 × 16 格，r=32 时远景止于 512 格 | client-spikes-2026-09 README S3；`FogRenderer#setupFog` |
| 远平面 | `Camera.depthFar = max(有效渲染距离×16×4, cloudRange×16)` | javap `Camera#update`（已核验） |
| 地形顶点 | 28 B（Position 12 + Color 4 + UV0 8 + UV2 4）；Sodium 0.9.2 压到 20 B | `DefaultVertexFormat.BLOCK`、terrain.vsh；`CompactChunkVertex.STRIDE=20`（已核验） |
| 显存随视距增长 | 64 格高平地只算顶面：r=32/64/128/256 约 92 MB / 369 MB / 1.48 GB / 5.9 GB（原版），66 MB / 264 MB / 1.05 GB / 4.2 GB（Sodium）；复杂地形 ×2～3 | 按 πr² 区块数推算，不是实测 |
| 客户端区块填充速度 | `ChunkBatchSizeCalculator` 冷启动值 7 ms ÷ 2 ms/区块 = 3.5 区块/tick ≈ 70 区块/s；之后按实测批耗时（含网络往返）加权自适应，每批变化 clamp 在 1/3～3 倍 | javap（已核验）。**70 区块/s 只是冷启动值，不是稳态**，r=12 填满 625 块「约 9 s」只能当估值 |
| 相机高度 | `SectionOcclusionGraph` 要求相机与地形 section 的 y 差 ≤ 渲染距离；r=12 时相机最多高出地面 192 格 | client-spikes README S5 |
| 光照模型 | 每顶点 UV2 = (方块光, 天光) 各 0～15，查 16×16 lightmap；方块光全局只有一个 `BlockLightTint` 色相，做不出逐光源彩色光 | terrain.vsh、lightmap.fsh（已核验） |
| 图形后端 | 自带 OpenGL 与实验性 Vulkan（"Prefer Vulkan (Experimental)"），默认先试 GL | `PreferredGraphicsApi#getBackendsToTry`（已核验） |
| Blaze3D 能力 | 没有 compute、SSBO、image store；有 D32_FLOAT 深度格式和 render pass，可以做阴影贴图，做不了 GPU 体素 floodfill | javap `GpuBuffer`/`UniformType`/`GpuTexture`/`CommandEncoder`（已核验） |
| 自定义负载单包上限 | 下行 1,048,576 B，上行 32,767 B | javap `MAX_PAYLOAD_SIZE`（已核验） |

### 2.2 本引擎的硬限制

| 限制 | 现状 | 出处 |
| --- | --- | --- |
| 地图常驻 | M2 整图常驻，实测 9.4 KiB/区块（整进程存活堆差），台账口径 5.7 KiB/区块 | client-spikes README S6、DESIGN-M2 §15 |
| 原版高度地形的每区块成本 | 高 384 合成自然地形：ChunkDataPacket 压缩后 5,109 B，带光照 5,255 B；首次序列化 0.84 ms（不带光照）/ 5.18 ms（带光照）；存活堆 57 KiB（带光照 64 KiB） | 本轮临时程序实测，程序已删，**未核实** |
| 超大地图整图常驻 | 10k×10k 格 = 390,625 区块，按 57 KiB 算 21 GiB 堆；100k×100k 为 2.1 TiB | 推算 |
| 区块卸载 | 没有按视距或无人观察的自动卸载；`unloadChunk` 只在注销整个实例时被调用（`InstanceManager.java:93`） | 源码 |
| 区块加载器 | 只有 `NoopChunkLoaderImpl`；Anvil 读取器已在 74592d8fdf 删除 | 源码 |
| 线程 | `ServerProcessImpl.java:104` 写死 `ThreadProvider.counter()`，`dispatcherThreads` 默认 1 | 源码 |
| 对局世界全黑 | `DynamicChunk#createLightData`（:329-360）对无光照数组的 section 置 `emptySkyMask`，等于告诉客户端天光为 0 | 源码；client-spikes README S1 |
| LightingChunk | ① 光照在发包路径和 `Instance.getBlockLight/getSkyLight` 里懒算；② `relight` 与 `relightSection` 都在 `synchronized(instance)` 里（:465、:581），与按空间分格多线程冲突；③ 方块变化后对 3×3 区块延迟重发局部光照，`sendLightAfterBlockPlacementDelay` 是全局 ServerConfig 字段（默认 100 tick），不能按世界设置；④ `DIFFUSE_SKY_LIGHT` 手写 23 种方块，注册表里 lightBlock==1 的有 85 种；⑤ BFS 每步固定减 1，不读 `lightBlocked()`；⑥ `flushQueue` 并行任务间读写非 volatile 字段，存在数据竞争（错误结果未复现）；原版对拍测试已删 | 源码；上游 Minestom issue #3158（open） |
| 区块带宽从未实测 | soak harness 不回 `ChunkBatchReceived`，每人只收到首批 9 块（gate-1000：ChunkDataPacket 450 条） | soak-2026-09-22 summary.json、tools/loadtest/soak.py |

### 2.3 现成方案能不能直接用

| 项目 | 能否作依赖或打包 | 关键限制 | 出处 |
| --- | --- | --- | --- |
| Distant Horizons 3.3.2（26.2 版 2026-09-21 发布） | 可以。LGPL-3.0-only，jar 27.7 MB，着色器 GLSL 330，Mac 能编译 | 服务端协议随 DH 版本变（主分支 PROTOCOL_VERSION=16，服务端插件比对 15，不一致即断开）；线上只传 detail level 6（64×64 列、方块级）；默认每玩家 500 KB/s ≈ 5～10 LOD/s；Blaze3D 模式与 Iris 同开会崩溃 | Modrinth；distant-horizons-core `Config.java:178-184`、`ModInfo.java:34`；DH wiki Server-Owners 第 13 行 |
| DH-Support 服务端插件 | 不能抄。GPL-3.0，本仓库服务端是 Apache-2.0 | 只能照 wiki 协议净室实现 | 插件仓库 LICENSE |
| Voxy | 不能。"All rights reserved. Do not redistribute." | 着色器 `#version 460`，要 GL 4.6，Mac 不可用；官方无服务端流送，issue #188 里协作者 xirreal 说明原因是整份 .voxy 目录有几 GB | voxy@534d58ec LICENSE.md；issue #188 |
| Nvidium | 可以（LGPL-3.0） | 只支持 NVIDIA Turing 及更新；Iris 开光影时自动停用；不做 LOD | Modrinth |
| Sodium 0.9.2 | 许可 PolyForm Shield 1.0.0（有不竞争条款），能否随商业客户端分发要法务判断 | —— | CaffeineMC/sodium LICENSE.md |
| Iris 1.11.x | LGPL-3.0 | "Vulkan is not supported"；走自己的 GL 封装；开光影包后覆盖资源包 core shader，服务端无从得知（Iris #2757） | Modrinth changelog；Iris 26.2 分支 |
| Bobby（客户端区块缓存） | 许可口径不清（fabric.mod.json 写 LGPL，仓库带 GPL 全文） | 只省重复访问带宽，显存问题不变 | Johni0702/bobby@47868a67 |

### 2.4 MMO 先例

| 先例 | 做法 | 数字 | 出处 |
| --- | --- | --- | --- |
| Wynncraft | 同一张图复制成多个世界（频道），进度跨世界同步；不提供官方远景 | 地图约 4,400×5,500 格；同时在线纪录 10,716（2026-02）；后端是魔改 1.8.9 | Wikipedia；Wynncraft 论坛 thread 320920。多世界同步只有搜索摘要，未核实 |
| Hypixel SkyBlock | 按岛屿切实例，自研 Slime 格式（zstd、与内存表示相同） | 2019-07：173 万岛 40.2 GB，75 台机器 1,500 个服务端，20k 在线时每分钟加载 904 个岛，加载不到 1 tick | Hypixel Dev Blog #5 |
| Minecraft Middle Earth | 发放社区预制的 DH SQLite 文件 | 要玩家手动放文件 | mcmiddleearth.com resources/171 |
| Hytale | 官方给了远视距的带宽成本 | 192/384/480 格视距分别需 2/4、4.5/7、6/10 Mbps（最低/推荐）；每个 32×32 区块存档约 27 KB | hytale.com 2025-12 硬件需求 |
| MultiPaper（单世界跨服） | Master 协调区块所有权 | 停在 1.20.1 Purpur，public beta，最后推送 2025-05-21；没找到 MMO 上线案例 | GitHub |

先例里没有一家做「一个无缝大世界横跨多台服务器」，都是整图复制成频道加副本。

## 3. 推荐架构

总原则：看得远和能交互拆成两份数据、两条通道；计算量大的工作（光照、区块编码、LOD 生成）全部挪到离线出图流水线。

```
出图流水线（离线）                       游戏服（每个大区一个进程）              客户端（client-fabric）
编辑源地图 ──► 烘焙器 ──► .synx 区域文件 ──mmap──► SynxChunkLoader ──原版区块包──► 近景：原版管线 r=12
                    │                               覆盖层 DynamicChunk ─world 增量─►  光照：原版 LevelLightEngine 增量
                    └─► LOD 金字塔 + 内容哈希 ──► CDN ───────────────────────HTTP──► 远景：自写 clipmap（Blaze3D）
                                                    synaxis:terrain_lod（增量 tile）─►  光影：自写 lightmap/阴影/点光源
```

### 3.1 服务端·存储：.synx

DESIGN-M2 §28.3 已把 .synx 推到 M3，这里给出它的形状。

- 每个文件 32×32 区块，头部放偏移索引，可随机读、可 mmap。
- 每个区块存四段：① 已压缩的 ChunkDataPacket 包体（含高度图和烘焙光照），形状与 `CachedPacket` 缓存的「已成帧已压缩字节」一致；② 碰撞位集（1 bit/方块，高 256 时 8 KiB/区块）加导航层（沿用 M2 的 walkable 位集与 surface_y）；③ 调色板原始数据，只在区块被改、要升级成 DynamicChunk 时解码；④ LOD 金字塔的源数据。
- 文件头记地图内容哈希和协议版本（776）。客户端 LOD 包与服务端 .synx 共用这个哈希，握手时比对，不一致就拒绝进服。
- 包体是原版网络格式，与协议版本绑定：**升级 MC 版本要重烘全部 .synx**，烘焙器要支持按协议版本批量重跑，重烘耗时写进升版清单。

### 3.2 服务端·生成与光照（离线）

- MMO 地图不在运行时生成。M2 的程序化 `Generator` 保留为烘焙器的一种输入；RTS 对局地图仍在进程内生成（见第 6 节）。
- 光照在烘焙器里算，用从 LightingChunk 抽出来的光照引擎（思路同上游 Minestom PR #3295：跑在 `WorldView` 上，不需要 Instance）。烘焙之前必须修掉第 2.2 节的 ④⑤（lightBlocked 名单、BFS 衰减），并恢复原版光照对拍测试，否则错误会写进所有 .synx。
- 烘焙耗时按本轮实测线性外推：16,384 区块 4.08 s，262,144 区块约 65 s（外推且原始基准未核实），全图可按区域并行。

### 3.3 服务端·运行时流送

- 新写 `SynxChunkLoader` 替换 Noop。未改区块只登记一个句柄（坐标 + mmap 偏移），发送时直接把烘焙字节写进 socket，不建调色板、不算光照、不进堆。
- 被玩法改动的区块升级成 DynamicChunk，进覆盖层，改动写到单独的 overlay 存储（不改 .synx）。
- 视距与模拟距离分开：视距默认 12、上限 16；模拟距离 4 区块，只在这个范围内解码出碰撞、寻路、实体要用的数据。
- 补上自动卸载：按观察者引用计数，无人观察后卸载；覆盖层设 LRU 硬上限（内测档 16,384 区块），堆用上限兜住，不依赖「改动率 5%」这类没有出处的假设。
- 光照：未改区块随烘焙包体下发，服务端 0 CPU。被改区块放进 tick 线程外的有界线程池做 3×3 局部重算（本轮实测单次 3～43 ms，未核实），结果只用于之后发给新观察者的区块包和服务端玩法查询。**服务端不向已在看的客户端发 UpdateLightPacket**，由客户端收到方块变化后用原版 `checkBlock` 自己增量重算，避开上游 #3158 描述的互相覆盖闪烁。这条同时绕开了 LightingChunk 的 `synchronized(instance)` 全局锁。
- 线程：用按空间分格的 ThreadProvider（16×16 区块一格，参照 Folia 的 grid-exponent=4）替换写死的 `counter()`，`dispatcherThreads` 调到核数，跨格交互走 Acquirable。主城、世界 Boss 聚在一格时这一格仍是单线程，Folia 也一样。
- 兜底下发（客户端缺 LOD、哈希不符）按玩家限速 64 区块/s，超出改走 CDN。

### 3.4 服务端·分服

- 按地理切大区，每个大区一个进程，只挂自己那份 .synx；人数超过单进程上限时同一大区开多个频道（Wynncraft 模式）。
- 跨大区用原版 `ClientboundTransferPacket` + `ClientboundStoreCookiePacket`（26.2 jar 里都有），客户端显示过场；背包与任务状态放外部存储。
- 两处要补的：Transfer 会重新走一次登录，online 模式下 Mojang sessionserver 就是切服的外部故障点；Cookie 保存在客户端，必须带 HMAC 签名，否则可伪造背包或任务状态。
- 不做无缝跨服。远景 LOD 是静态数据，边界另一侧的地形照样看得见，看不见的只是对面进程里的玩家；设计上用地形屏障或关卡门做大区分隔。

### 3.5 网络

| 通道 | 方向 | 内容 | 上限 |
| --- | --- | --- | --- |
| 原版区块包 | S2C | 近圈 r=12 的区块；流控沿用原版 ChunkBatch + ack | —— |
| `synaxis:terrain_lod` | S2C | LOD tile 增量与地图版本号 | 单包 1 MiB |
| `synaxis:lights` | S2C | 动态光源列表 {pos, color, radius, ttl} | 单包 1 MiB |
| HTTP/CDN | 下载 | LOD 基础包，按内容哈希寻址、永久缓存、断点续传 | —— |
| 请求类负载 | C2S | 缺 tile 的请求等 | 32,767 B，不用 fabric:split |

压缩维持引擎现有 zlib（Deflater 默认级别，阈值 256）。两端都由我们控制后换 zstd 加字典的收益没测，放到 P2 之后评估。

### 3.6 客户端·近景

- 原版 26.2 管线（已按 `ChunkSectionLayer` 放进 UberGpuBuffer 批量绘制），渲染距离跟随服务端视距 12～16。不强制 Sodium。
- 可选的本地区块缓存：按内容哈希缓存去过区域的区块包，传送或切服时本地命中就不必等服务端重传；隐藏区域不进缓存。这一项在 P4 之后按实测填充时间决定做不做。

### 3.7 客户端·远景

- client-fabric 内的高度场 clipmap：6～7 层，每层 256×256 采样，第 L 层每采样覆盖 2^L 格，覆盖半径 4,096～8,192 格。每采样 4 B：高度 12 bit、材质 id 12 bit、色调或烘焙天光 8 bit。常驻 1.5～1.75 MiB，与地图尺寸无关；约 39 万顶点、6～7 次 draw（估算）。
- 挂点：Fabric `LevelRenderEvents.START_MAIN` 先画 LOD（原版渲染半径以内 discard），清深度，再让原版画近景。mixin 只有 `FogRenderer#setupFog`（把渲染距离雾推到 LOD 外缘），必要时加 `Camera` 的 depthFar。环境雾由 `EnvironmentAttributes` 数据驱动，不用 mixin。
- 用 Blaze3D 的 `RenderPipeline` 与 `GpuDevice.createBuffer` 写，GLSL 330；Vulkan 后端由 `GlslCompiler` 转成 SPIR-V。「两个后端都能跑」是按 jar 结构推出的，没有运行过，P1 验证。
- 远景不经过 `SectionOcclusionGraph`，RTS 相机高于 192 格时由远景层填画面。
- 高画质档（可选）：显卡达到 RTX 3060 级、玩家主动开启时，经 DH API 的 `overwriteChunkDataAsync` 在本地注入数据，由 DH 画出带方块轮廓的远景。前提是 P1 证实这个接口在连 Synaxis 的多人客户端上能产出 LOD，目前只读过源码。

### 3.8 客户端·光照与光影

| 层 | 内容 | 做法 | 依据 |
| --- | --- | --- | --- |
| 基础光照 | 烘焙光照 + 原版增量 | `applyLightData → LevelLightEngine → BlockModelLighter → lightmap`；方块变化走 `setBlockState → checkBlock`，`ClientLevel.update()` 每帧 `runLightUpdates` | javap（已核验） |
| 昼夜 | GPU 上完成，不重发数据 | lightmap.fsh 的 `SkyFactor`/`SkyLightColor`，由 `LightmapRenderStateExtractor` 从 `EnvironmentAttributes` 读取 | javap + 着色器源码（已核验） |
| 动态彩色光 | 最多 64 个点光源 | 覆盖 terrain/entity 核心着色器，片元着色器从 UBO 读光源、逐像素累加，与 lightmap 取 max；光源变化不重建区块网格 | 取自激进方案，Blaze3D 有 UBO |
| L1 调色 | 零额外 pass | 覆盖 lightmap.fsh、terrain/entity 着色器，调色、调雾、远近色彩衔接 | —— |
| L2 阴影与体积光 | 太阳阴影贴图 + 全屏 raymarch 体积光 | 新增 D32_FLOAT 深度 pass，阴影距离 ≤ 128 格；RTS 固定俯角时阴影视锥按地面覆盖拟合 | Blaze3D 无 compute/SSBO，彩色 floodfill 只能 CPU 预算 |
| Iris | 玩家自选 | 检测到 Iris 光影包启用时关闭 L1/L2 的核心着色器覆盖（Iris 会覆盖它们）；远景 LOD 层不受 Iris 影响，也拿不到 Iris 的阴影 | Iris #2757 |

## 4. 目标规模估算

### 4.1 推算依据

| 系数 | 取值 | 出处与可信度 |
| --- | --- | --- |
| 区块包体（压缩后，含光照） | 5.26 KB/区块 | 高 384 合成自然地形，本轮临时程序实测，**程序已删，未核实**；不是美术地图，也不是原版世界生成 |
| M2 平地区块包 | 89～113 B/区块 | soak 50,800 B ÷ 450；核验阶段重建测得 87.6 B（已核验） |
| 存活堆 | 57 KiB/区块（带光照 64 KiB） | 同上，**未核实** |
| 新进视野区块数 | (2r+1)·v/16 区块/s，轴向移动 | 视野是正方形 (2r+1)²（`ChunkRange.java:14-17`） |
| 实体流量 | 14.5～32.5 KB/s/人 | soak-2026-09-22，50 人 × 1,000～4,000 单位（RTS 口径）。MMO 主城的可见对数按 n² 增长，这个值**不能当 MMO 上界** |
| 速度 | 步行 4.317、坐骑 10、飞行 30 格/s | 原版步行；另两档是设定值 |

M3 拿到第一张真地图后，所有带宽与磁盘数字要按实测包体重算，它们随包体线性变化。

### 4.2 内测档：单分片 50～200 人，地图 4,096×4,096 格（65,536 区块），视距 12，远景 4,096 格

| 项 | 数值 | 算法 |
| --- | --- | --- |
| 服务端磁盘 .synx | ≈ 345 MB | 65,536 × 5.26 KB |
| 碰撞位集 | 512 MiB（高 256，mmap，不进堆） | 65,536 × 8 KiB |
| 服务端堆（覆盖层） | ≤ 0.89 GiB | LRU 上限 16,384 × 57 KiB；200 人完全分散时模拟区 200 × 81 × 57 KiB ≈ 0.88 GiB |
| 进程目标 | RSS < 4 GiB，堆维持 §15 的 < 2 GB | —— |
| 未改区块的服务端 CPU | 光照 0，编码 0，只剩读盘和 memcpy | 对照现状 0.84 / 5.18 ms/区块（未核实） |
| 每玩家近圈带宽 | 进场 3.3 MB（625 块）；步行 35 KB/s，坐骑 82 KB/s，飞行 247 KB/s | 5.26 KB × 区块数 |
| 200 人合计出站 | 步行 13.5 MB/s（108 Mbit/s）；全员骑乘 23 MB/s（184 Mbit/s） | (近圈 + 32.5 KB/s 实体) × 200 |
| 远景 LOD 包（一次下载） | 从第 1 层起约 21 MiB，从第 2 层起约 5.3 MiB（未压缩，压缩率未测） | 2,048² × 4 B × 4/3；1,024² × 4 B × 4/3 |
| 远景运行期增量 | ≤ 11 KiB/s/人（未压缩） | 按每层走 64·2^L 格更新一行 tile 推算，未核实 |
| 客户端近景显存 | 约 13 MB（平地顶面），复杂地形 26～39 MB | 从 r=32 平地 92 MB 按面积缩放 |
| 客户端远景 | 常驻数据 1.5～1.75 MiB，约 39 万顶点，6～7 次 draw | 估算，无原型 |
| 帧预算（RTX 3060、1080p、60 FPS = 16.7 ms） | 近景 ≤ 6 ms，LOD ≤ 2 ms，阴影 ≤ 4 ms，后处理与 UI ≤ 3 ms | 目标值。唯一的真显卡参照：Iris #3241，RTX 3060 Laptop、1080p、Complementary、渲染距离 25、阴影 15，26.2 下 84 FPS（11.9 ms） |
| 对照：走 DH 原协议 | r=256 首次同步 0.64～1.29 GB，按默认 500 KB/s 要 21～43 min/人 | 12,868 个 LOD × 50～100 KB |

### 4.3 正式档：32,768×32,768 格（4,194,304 区块），切成 64 个 4k×4k 大区，每进程 200～500 人

| 项 | 数值 | 算法 |
| --- | --- | --- |
| .synx 全世界 | ≈ 22 GB；每个大区进程只 mmap 自己的 345 MB | 4.19M × 5.26 KB |
| 服务端堆（覆盖层） | LRU 上限 24,576 区块 ≈ 1.34 GiB | 500 人完全分散最坏 500 × 81 × 57 KiB ≈ 2.2 GiB，由上限兜住 |
| 每进程出站 | 步行 34 MB/s（270 Mbit/s），骑乘 57 MB/s（460 Mbit/s）；千兆网卡够用 | (近圈 + 32.5 KB/s) × 500 |
| 区块吞吐 | 3.4k～7.8k 区块/s，全部是 mmap 读 + memcpy | 500 × 6.7～15.6 |
| 远景 | 全世界金字塔从第 2 层起 341 MiB、从第 3 层起 85 MiB；客户端按所在区域加周边 8 km 分块拉，本地缓存 20～90 MiB | 8,192² × 4 B × 4/3；4,096² × 4 B × 4/3 |
| 客户端显存 | 与内测档相同 | clipmap 与地图尺寸无关 |
| 未测的最大项 | 500 人聚集时的实体流量；逐玩家 AES-CFB8 加密与 socket 写的 CPU | 所有 500 人数字都是从 50 连接的 soak 线性外推 |

## 5. 分阶段路线图

| 阶段 | 与 M2/M3 的关系 | 产物 | 验收判据 |
| --- | --- | --- | --- |
| **P0** | M2 内，当前工作流 | 方案 B 收尾（对局世界载入期整图算光照），处置见第 6 节 | ① `EVT:match_started` 的 load_ms 含 relight，合计 < 3 s（独立基准 relight 0.68～1.05 s + 载入 0.27 s，未核实）；② heap_delta_kib < 50 MB 且 heap_gc_runs > 0；③ 真客户端截图地形不再是黑色剪影；④ `setChunkSupplier(LightingChunk::new)` 只出现在对局实例构造处 |
| **P1** | M2 收尾到 M3 开工之间，1～2 周 spike，不改引擎主干 | 一台 RTX 3060 级 Windows 机 + 一台 Apple Silicon Mac（llvmpipe 不算）上的测量报告，放 docs/research/ | 每项给数字或结论：① 客户端 `propagateLightSources` 自算光照，抽 1,000 个 section 与服务端 LightingChunk 逐 nibble 对拍，差异为 0 或逐条可解释，1,024 与 4,225 区块两档的最坏单帧增量；② Blaze3D 画一张 256×256 高度场，GL、Vulkan、macOS GL 4.1 各跑一次，FogRenderer mixin + 清深度衔接可行；③ 3060 机与 150 ms RTT 两种条件下填满 625 块的实际耗时；④ soak harness 回 `ChunkBatchReceived`，量出真实区块带宽；⑤ 每 MB 的 AES-CFB8 + socket 写 CPU；⑥ `overwriteChunkDataAsync` 在多人客户端能否产出 LOD（决定高画质档）；⑦ 低配档：GTX 1650 或 Apple M1、1080p、r=8、远景 2,048 格的 P1% 帧率 |
| **P2** | M3 前半，补回 DESIGN-M2 §28.3 ② 的 .synx | .synx 格式与测试向量、离线烘焙器、`SynxChunkLoader`、自动卸载、覆盖层、光照引擎两处修复与原版对拍测试 | ① 4k×4k 测试图烘焙成功，记耗时与磁盘（预期 ≈ 345 MB，高 384）；② 抽 1,000 个区块，.synx 发出的包体与「解码成 DynamicChunk 再 createChunkPacket」逐字节一致；③ 光照对拍在测试集上 0 差异；④ JFR 里未改区块的发送路径看不到 `createChunkPacket`；⑤ 无人观察的区块会被卸载，有可证伪的测试；⑥ 200 个机器人、视距 12、步行与坐骑两档跑 600 s：tick P99 < 50 ms、超 50 ms 的 tick 为 0、区块下行 KB/s/人与模型（步行 35、坐骑 82）偏差 ≤ ±20% |
| **P3** | M3 后半 | 远景 LOD v1：烘焙器产出 LOD 金字塔与内容哈希包，客户端下载校验、clipmap 渲染、雾衔接 | RTX 3060、1080p、近圈 12 + 远景 4,096 格时 P1% ≥ 60 FPS，LOD pass GPU ≤ 2 ms，显存增量 < 64 MiB；Mac GL 4.1 与 Vulkan 都出图；RTS 相机 400 格高空俯视无空洞；远近接缝过一次美术评审。**切换条件**：6 人周内过不了，远景改由 DH 渲染、数据经本地注入（前提 P1⑥ 成立）；P1⑥ 也不成立，退回保守方案的「净室实现 DH 协议 + 限速推送」 |
| **P4** | M3 之后 | 运行时增量：覆盖层 tick 外 3×3 重算、不下发 UpdateLightPacket；`synaxis:terrain_lod` 增量 tile；`synaxis:lights` 与 64 光源 UBO | 放一支火把，所有在看的客户端 1 tick 内变亮，录屏 10 s 不闪；JFR 验证 tick 线程上光照耗时为 0；远处被炸的地形在 LOD 上 ≤ 2 s 更新；64 光源同屏着色开销比 P3 基线多 ≤ 1.5 ms/帧 |
| **P5** | 可与 P4 并行 | 光影 L1/L2，Iris 检测后自动让位 | 3060、1080p、r=12 开阴影时帧时间 ≤ 16.7 ms、阴影 pass ≤ 4 ms；GL 与 Vulkan 截图逐像素差异 < 2%；装 Iris 启用光影包时我们的覆盖自动关闭且不崩溃；低配档默认关 L2 |
| **P6** | 正式档 | 空间分格 ThreadProvider、大区分服 + 频道、Transfer + HMAC 签名 Cookie、外部状态存储 | 单分片 200 机器人 30 min：TPS 20、tick P99 < 50 ms、RSS < 4 GiB；出站带宽实测与估算偏差 ≤ ±20%。再压 500 机器人，并单列「500 人挤在 1 个分格」一档量 tick P99 与每人出口；不达标就把频道上限降到 200～300 人。跨大区转服从点击到新大区首批区块可见 P95 < 5 s（「首批」指原版首批 9 块，不是填满 625 块） |

## 6. 正在做的「方案 B」怎么处置

**保留并做完，但只用于 M2 的 RTS 对局世界，不进 MMO 主线，不再追加投入。**

状态（2026-09-23 本报告写完之后）：B 已合入 `client-gametest`。`Match#load` 在整图生成之后同步调一次 `LightingChunk.relight`，然后才进 running；实测 rts-512 的 `load_ms` 771～968 ms、`heap_delta_kib` 9,251～9,320（此前 126～167 ms、约 6,030），下面第 1 条已满足、第 4 条的「重测」已完成，光照数组的实际堆增量约 3.2 MiB，本节按推算写的「约 24 MiB」偏大。第 4 条里登记 ④⑤ 两处缺陷的事尚未做。

理由有三条。rts-512 共 1,024 区块，本轮独立基准整图 relight 0.68～1.05 s、载入 0.27 s（未核实），放得进 §15「冷启动生成 + 载入 < 3 s」；对局地形在 M2 里不变，LightingChunk 最有问题的运行时路径（延迟 100 tick 重发局部光照）不会被触发；整图 relight 这段代码正是 P2 离线烘焙器的核心，也是 P1① 对拍用的服务端真值。

具体调整：

1. relight 只在对局载入阶段调，耗时并进 `EVT:match_started` 的 load_ms，让 3 s 判据能变红。
2. 不去修或扩展 `sendLightAfterBlockPlacementDelay`、局部光照重发、运行时光照查询这几条路径，它们在目标架构里会被整体替换。这个配置是全局字段，改它会影响全进程，M2 对局地形不变，不需要动。
3. LightingChunk 不用到多局并行（M3）或任何大世界实例上。按实测外推，16,384 区块就要 4.08 s、315 MiB（未核实），已超出单局预算。
4. 在 DESIGN-M2 §28.2 登记第 2.2 节的 ④⑤ 两处缺陷与对拍测试缺失，作为 P2 烘焙器的前置修复项；同时把 §28.2 已决行「光照 M2 不算（1,024 区块 137 ms）」改写为「M2 对局用 LightingChunk 整图算一次，M3 起改为 .synx 烘焙光照 + 客户端增量」，并重测 load_ms 与 heap_delta_kib。光照数组整图约 24 MiB（未压缩，按每柱 6 个 light section × 2 层 × 2048 B 推算）要计入 §15「地图数据 < 50 MB」重核。

**止损条件**：满足任一条就停掉 B，改用常量天光。条件是：rts-512 的 relight + 载入超过 3 s（hybrid 评审建议更早的警戒线 2 s），或堆增量超过 50 MB，或那个工作流剩余工作量超过 2 人日。常量天光的做法是 `DynamicChunk#createLightData` 对地表以上的 section 不再置 `emptySkyMask`，改为共享一份全 0xFF 的 2048 B 数组（天光 15），方块光仍空。服务端不跑光照算法，改动几十行；带宽上一个数组 gzip 后 36 B，6 个拼接后 47 B（本机 gzip -9 经 stdin 实测）。代价是屋檐下和洞穴里不变暗，RTS 开阔地图可以接受。客户端收到常量天光后若有方块变化，自己的 LevelLightEngine 可能算出与常量不一致的值，这一点没验证。

## 7. 需要你拍板的问题

| # | 问题 | 默认选项 | 切换条件与代价 |
| --- | --- | --- | --- |
| Q1 | 512 格以外的远景接受「高度场地形 + 颜色」，还是必须看到方块轮廓和建筑立面？ | 高度场（混合方案） | 美术确认 512 格外必须认出城堡轮廓 **且** P1⑥ 证实 DH 本地注入可用 → 远景加 DH 高画质档，代价是客户端维护两套远景、DH 包每个大区 0.2～0.4 GB 下载（系数无出处）。P1⑥ 不成立 → 追加 impostor（建筑贴图卡片）或自研体素 LOD，后者参照 Voxy 规模估 3～6 人月（未核实） |
| Q2 | 「光影」指我们自己控制的画面风格，还是让玩家用 Complementary、Photon 这类社区光影包？ | 自研 L1/L2，GL + Vulkan + Mac 都能跑；Iris 允许玩家自装，装了我们的覆盖自动让位 | 要把社区光影包作为主画面 → 改走保守方案，光影只能在 OpenGL 上，Mac 以外的 Vulkan 玩家没有光影，每次升 MC 要等 Iris 跟进 |
| Q3 | MMO 地图是人工制作、基本静态、按版本发布吗？ | 是 | 玩法允许大规模改地形（建造、大范围破坏）→ 覆盖层膨胀，退化为 DynamicChunk 常驻 + 在线重算光照（57～64 KiB/区块，每处改动 3～43 ms），LOD 增量流量要重估 |
| Q4 | 同时在线目标：每分片多少人、全服多少人、要不要「千人同屏」？ | 内测每分片 200 人；正式档每分片 200～500 人，按大区分服加频道，不做无缝跨服 | 要求千人同屏或无缝跨服 → 要自研边界幽灵实体与区块所有权迁移，工作量未估；同时在线 ≤ 数百人时不建议做 |
| Q5 | 目标玩家硬件：Mac 占多少，低配要到哪一档？ | 必须支持 Apple Silicon（GL 4.1）；低配档 GTX 1650 / M1、1080p、r=8、远景 2,048 格时 P1% ≥ 45 FPS | Mac 可以放弃 → Voxy 类 GL 4.6 方案仍因许可不可用，结论不变；低配档不达标 → 默认关 L2 阴影 |
| Q6 | 远景目标半径？ | 4,096 格（256 区块）；RTS 俯视与 MMO 平视共用一套 | 要 8 km → 加第 7 层，常驻从 1.5 升到 1.75 MiB |
| Q7 | MMO 世界高度？ | 256 格 | 美术不需要深洞穴 → 128 格，碰撞层与 .synx 大致按 section 数缩小（高 256 按 section 数折算约 3.4 KB/区块，底部空 section 压缩后接近 0，线性折算可能偏大，未测） |
| Q8 | 服务端玩法要不要读光照值（潜行、按光照刷怪）？ | 不读 | 要读 → 读烘焙值或覆盖层重算值，服务端值为准，并对新观察者重发完整区块包 |
| Q9 | Sodium 能否随客户端分发？ | 不捆绑，只声明兼容 | 法务判定 PolyForm Shield 不竞争条款对商业 MMO 客户端不构成问题 → 可以捆绑，近景顶点从 28 B 降到 20 B |

## 8. 未核实事项与风险

### 8.1 数字层面

- **本轮基准程序都已删除，核验阶段无法复现**：5,109 / 5,255 B/区块、0.84 / 5.18 ms/区块、57 / 64 KiB/区块、LightingChunk 的 relight 耗时（1,024 区块 0.68～1.05 s、4,096 区块 1.17 s、16,384 区块 4.08 s / 315 MiB）、局部重算 3～43 ms。第 4 节全部带宽和磁盘数字都建在 5.26 KB/区块上。另有一处反常未解释：高 64 世界 4,096 区块堆增量 78 MiB，高 384 世界同样区块数只有 46 MiB（section 数是前者 6 倍），建议 P1 复测。
- 所有地形是合成的，不是美术地图或原版世界生成。方块实体、生物群系混排会让包体变大，幅度未测。
- 区块流带宽从未实测（harness 不回 ack）；AES-CFB8 逐玩家加密的 CPU 没测，未改区块零编码后它可能成为新瓶颈。
- 500 人每分片的所有数字从 50 连接 soak 线性外推；MMO 主城的实体流量按可见对数 n² 增长，没有模型。
- 客户端帧率与显存全部没有真显卡实测，仓库里只有 llvmpipe 截图。
- clipmap 3～4 人周 / 约 2,500 行、升版 2～5 人日/次、DH 高画质档 1～2 人周，全是自估。
- DH 每个 LOD 50～100 KB 是从 wiki「500 KB/s ≈ 5～10 LOD/s」反推的，不是测量值。
- 26.2 Iris 性能回归（Iris #3241，138 → 84 FPS）数据属实，但 issue 里 Iris 成员 IMS212 否认了 reversed-Z 归因，另一位贡献者用 Nsight 指向首次绘制实体时的 GPU 空闲；原因未定。该测试用的是 Iris 1.11.2 / Sodium 0.9.1。

### 8.2 机制层面

- 客户端自算光照：LightData 两组 mask 都不置位时 26.2 客户端先按什么值渲染、`skyLightSources` 在客户端是否已填充、625 块同时到达时 `runLightUpdates` 掉多少帧，都没验证。不成立时覆盖层区块改为服务端重发完整区块包。
- DH `overwriteChunkDataAsync` 在多人客户端上能否产出 LOD：只读过源码（`DhApiTerrainDataRepo.java:515-540`、`DhClientLevel.shouldProcessChunkUpdate`），未实测。
- Blaze3D 自定义管线在 Vulkan 后端上能否直接编译运行，未验证。
- Blaze3D 大约每季度一改（MC 26.1 为 03-24、26.2 为 06-16、26.3 为 09-15），26.3 已宣布渲染重构；ADR-M2-016 钉 26.2 能缓解，但拿不到上游修复。
- 我们的 RTS 相机 mixin（正交投影、eye 抬高、剔除外推）与 Sodium 异步遮挡剔除、Iris 阴影剔除、DH 能否共存，完全没测。
- LightingChunk `flushQueue` 的数据竞争在代码上成立，会不会算出错误光照没有复现。
- 服务端不再下发局部光照后，服务端覆盖层重算值与客户端自算值可能有细微差别。

### 8.3 产品与运营层面

- 高度场远景的表现上限：洞穴口、拱桥、浮岛、建筑立面在 192 格外变成色块。
- 远近交接的接缝与色差是 P3 最难估工期的部分，超 6 人周触发切换。
- 光照正确性依赖修过的光照引擎，错一次会写进全部 .synx；对拍测试必须在 P2 验收前恢复。
- 大区边界看不见对面玩家；边界外被改动的地形在 LOD 里是旧的，直到增量推到。
- 32k×32k 手工地图需要编辑器到烘焙器的内容流水线，M2 明确不做编辑器；没有内容生产线时正式档尺寸只是技术上限。
- LOD 包把全图地形交给客户端，标记为隐藏的区域必须由烘焙器排除在 CDN 包外，漏标即剧透且无法撤回。
- 三套方案都没给机器成本（每月每分片费用、CDN 出口单价），运营成本只到带宽量级。

## 9. 参考来源

本机核对：

- `~/.gradle/caches/fabric-loom/26.2/minecraft-client.jar`：`net.minecraft.client.Options`、`Camera`、`ChunkBatchSizeCalculator`、`ClientChunkCache`、`ClientPacketListener`、`ClientLevel`、`PreferredGraphicsApi`、`SectionRenderDispatcher`、`SectionOcclusionGraph`、`FogRenderer`、`LightmapRenderStateExtractor`、`BlockModelLighter`、`LevelLightEngine`、`LevelChunk`、`DefaultVertexFormat`、`ClientboundTransferPacket`、`ClientboundStoreCookiePacket`、`Clientbound/ServerboundCustomPayloadPacket`、`com.mojang.blaze3d.*`（含 `vulkan/glsl/GlslCompiler`）；`assets/minecraft/shaders/core/terrain.vsh`、`lightmap.fsh`、`include/sample_lightmap.glsl`；`assets/minecraft/lang/en_us.json`
- `fabric-api-0.161.0+26.2` 内嵌 `fabric-rendering-v1-25.3.3+515ac5339e.jar`：`LevelRenderEvents`、`LevelExtractionEvents`、`RenderStateDataKey`
- `sodium-fabric-0.9.2+mc26.2.jar`：`CompactChunkVertex`、`RenderRegion`
- 本仓库：`src/main/java/io/github/bearisbug/synaxis/instance/LightingChunk.java`、`DynamicChunk.java`、`Instance.java`、`InstanceManager.java`、`ChunkLoader.java`、`instance/light/*`、`network/packet/server/CachedPacket.java`、`ServerProcessImpl.java`、`ServerConfig.java`、`coordinate/ChunkRange.java`、`entity/Player.java`、`thread/ThreadProvider.java`；`tools/loadtest/soak.py`
- 本仓库文档：`docs/DESIGN.md` §15、§28；`docs/DESIGN-M2.md` §2、§15、§28.2、§28.3；`docs/research/client-spikes-2026-09/README.md`（S1～S6）；`docs/research/soak-2026-09-22/report.md` 与 `runs/*/summary.json`；`docs/research/02-performance-forks-folia.md`；`docs/research/05-proxy-network.md`；`docs/research/06-alternative-implementations.md`

外部：

- Distant Horizons：https://modrinth.com/mod/distanthorizons ；https://gitlab.com/distant-horizons-team/distant-horizons （versionProperties、LICENSE.LESSER.txt、releases 3.3.0）；https://gitlab.com/distant-horizons-team/distant-horizons-core （commit effbc0a2：`Config.java:178-184,1984-2070`、`ModInfo.java`、`DhApiTerrainDataRepo.java:515-540`、`SessionConfig.java`）；wiki Server-Owners 与 Protocol 页
- DH-Support：https://gitlab.com/distant-horizons-team/distant-horizons-server-plugin （commit 56a01110：`LodHandler.java:56-60`、`Lod.java:41-43`、`DataPoint.java`、`PluginMessageHandler.java`）；Minestom 移植 https://github.com/CubicPulse/minestom-dhsupport （GPL-3.0，只读预生成库）
- Voxy：https://github.com/MCRcortex/voxy （commit 534d58ec、分支 2622）；issue #188；https://modrinth.com/mod/voxy
- LOD Server Support：https://github.com/VoX/lod-server-support （commit d4b415d5，MIT；默认每玩家 25 MiB/s，见 `ServerConfigBase.java:826`）
- Sodium：https://github.com/CaffeineMC/sodium （LICENSE.md；release mc26.2-0.9.0）
- Iris：https://modrinth.com/mod/iris/version/1.11.0+26.2-fabric ；issues #3241、#2757、#2606；https://github.com/IrisShaders/ShaderDoc （iris-features.md、dh-support.md）；https://shaders.properties/current/guides/your-first-shaderpack/
- Nvidium：https://modrinth.com/mod/nvidium
- Bobby：https://github.com/Johni0702/bobby （commit 47868a67，`BobbyConfig.java:21-22`）
- Minestom：issue #3158、PR #3295（https://github.com/Minestom/Minestom）
- Starlight / Moonrise：https://github.com/PaperMC/Starlight ；https://gist.github.com/Spottedleaf/6cc1acdd03a9b7ac34699bf5e8f1b85c ；https://github.com/Tuinity/Moonrise
- Folia：https://docs.papermc.io/folia/reference/overview/ ；MultiPaper：https://github.com/MultiPaper/MultiPaper
- Polar：https://github.com/hollow-cube/polar
- Anvil 格式：https://minecraft.wiki/w/Region_file_format ；Paper 配置：https://docs.papermc.io/paper/reference/global-configuration/
- Hypixel：https://hypixel.net/threads/dev-blog-5-storing-your-skyblock-island.2190753/ ；https://hypixel.net/threads/dev-blog-12-navigating-the-multivers-ions-of-the-minecraft-world.5708006/
- Wynncraft：https://en.wikipedia.org/wiki/Wynncraft ；https://forums.wynncraft.com/threads/distant-horizons-server-side-support.320920/
- Minecraft Middle Earth：https://www.mcmiddleearth.com/community/resources/distant-horizons-lods.171/
- Hytale：https://hytale.com/news/2025/12/hytale-hardware-requirements
- Mojang 版本清单：https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
- OptiFine 光影规范：https://raw.githubusercontent.com/sp614x/optifine/master/OptiFineDoc/doc/shaders.txt
