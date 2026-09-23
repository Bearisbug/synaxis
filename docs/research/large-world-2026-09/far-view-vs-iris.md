# 远景与社区光影包怎么接：clipmap、DH、Iris 三条路的决策备忘录（2026-09）

- 日期：2026-09-23
- 前提：用户 2026-09-23 对 [README.md](README.md) 第 7 节的拍板。Q1 远景接受「高度场地形 + 颜色」；Q2 主画面用社区光影包（Iris + Complementary / Photon），不自研画风；Q3 地图人工制作、静态、按版本发布；Q4 每分片 200～500 人、大区 + 频道；Q5～Q9 取报告默认值（必须支持 Apple Silicon GL 4.1、远景 4,096 格、世界高 256、服务端不读光照、Sodium 不捆绑）。
- 范围：只读调研，未改仓库代码，未启动客户端。输入是三路调研：DH 路线、clipmap 接 Iris、社区光影包在 26.2 上的现状。凡是只读了源码、没有实跑的结论，文中写明「源码结论」；推算与自估的数字标「未核实」或「自估」。
- 读法：第 1 节是结论，第 7 节是要你回答的问题，其余是依据。

## 1. 结论

**推荐方案 B：远景改用 Distant Horizons（DH）。客户端首次进大区时下载 5.3～21 MiB 的高度场包，在本地按 Q1 只烘地表与水面，生成 DH 的 SQLite 库；再经 DH API 钉住数据目录并设为只读。光影由 Iris + Complementary 统一上。** 本地烘焙太慢时退到 CDN 分发预烘库；自写 clipmap 降为退路，走 Iris 公开 API 接入（方案 C）。

原报告推荐「自写 clipmap + 自研着色器」（方案 A），这条路在 Q2 之后不成立。Iris 开光影包时，没有经 `IrisApi.assignPipeline` 登记的自定义 RenderPipeline 整体不可见，Iris 只打一行 `Missing program … in override list`。证据是 malilib #165（2026-09-20，环境为 MC 26.3-rc-1 + Iris 1.11.6）；触发它的 `MixinShaderManager_Overrides.java:69-71` 在 Iris 26.2 分支里同样存在，所以结论适用于 26.2。README §3.8 表里「远景 LOD 层不受 Iris 影响」这句要按此更正：不是不受影响，是看不见。

| 方案 | 远景由谁画 | 光影包开启时远近一致性 | 工程量（自估） | 每个 4k×4k 大区下载量 | 不选它 / 选它的原因 |
| --- | --- | --- | --- | --- | --- |
| A 自写 clipmap + 自研着色器 | 我们的 RenderPipeline | 远景不可见 | 3～4 人周 | 5.3～21 MiB | 主画面开光影时远景消失；「仿色调」没有 API 可读光影包的天空和雾色 |
| **B DH + 本地烘焙 + Iris（推荐）** | DH 3.3.2 | 光照、雾、水面、远近过渡由光影包的 `dh_*` 程序处理；远景不投射也不接收阴影贴图 | 2.5～4 人周 | 本地烘焙：5.3～21 MiB（与 C 相同）；CDN 退路：约 0.05～0.14 GB（推算，未核实） | 唯一一条由光影包作者自己调过远近衔接的路；代价是 DH 运行时开销没有数字、DH 与 Iris 要锁版本同升、本地烘焙依赖 DH core 内部类 |
| C clipmap 经 Iris 公开 API 登记 | 我们的网格，光影包的 `gbuffers_terrain` 着色 | 受光一致；雾走光影包的非 DH 分支，边界雾要玩家手动关，Complementary 的大气雾给远景混 50～70% 雾色；接缝没有交叉淡入 | 4～6 人周 | 5.3～21 MiB | 只依赖 Iris 一个上游；远景观感取决于光影包的非 DH 分支，Complementary 许可禁止改包内容，我们调不了 |

B 与 C 的工程量差距在自估误差之内，下载量在本地烘焙下相同，选 B 的依据是一致性：Complementary 和 Photon 都为 DH 写了专用分支（雾的指数、dither 交叉淡入、LOD 深度识别），而这些分支只在真 DH 加载时由 Iris 打开 `DISTANT_HORIZONS` 宏才生效（Iris `StandardMacros.java:64-67`），C 进不去。

## 2. 三个方案共同面对的约束

这些约束来自 Q2 本身，选哪个远景方案都绕不开。

| 约束 | 事实 | 出处 |
| --- | --- | --- |
| Q2 与 Q9 冲突 | Iris 硬依赖 Sodium 0.9.x（`fabric.mod.json` 的 `depends.sodium`）。装 Iris 就必须装 Sodium，「Sodium 不捆绑」只剩「玩家自装」一种含义 | Iris 26.2 分支 f61d950f；Sodium 许可 PolyForm Shield 1.0.0 |
| Iris 版本 | 26.2 最新 1.11.4+26.2（2026-09-13）。26.2 上 Fabric 版共 4 个（1.11.0/1.11.1/1.11.2/1.11.4），加 NeoForge 共 6 个；没有 1.11.5/1.11.6+26.2 | Modrinth API |
| 没有 Vulkan | Iris 1.11.0+26.2 更新日志「Vulkan is not supported」；options.txt 选 Vulkan 时 Iris 跳过除 `VKOnly` 外的全部 mixin，并弹窗提示切回 OpenGL。Vulkan 玩家没有光影 | Iris `IrisMixinPlugin.java:18-73`、`ShaderPackScreenPlaceholder.java:30` |
| 26.2 上的性能回归（M3 开工前重估） | Iris #3241：138 → 84 FPS（RTX 3060 Laptop，Complementary r5.8.1；138 是在 140 FPS 帧率上限下测的，实际回归幅度更大），截至 2026-09-23 仍 open，26.2 分支无修复提交。#3274：正文报 GPU 利用率 50～90%（70～90% 出自评论者 martin-djakovic），也 open。原版 MC-307596（OpenGL 后端卡顿）登记的受影响版本是 26.2 Snapshot 3 / Pre-Release 4，确认状态 Plausible，未在 26.2 正式版确认，26.3 Snapshot 9 修复。ADR-M2-016 定的是「M2 不跟 26.3，重估留到 M3 开工前」，而远景与光影都落在 M3（README P3/P5）；那时 26.3（2026-09-15）、Iris 1.11.6+26.3、DH 3.3.2-26.3（09-21）都已发布，这一行按 ADR-M2-016 的重估处理，不当硬约束 | GitHub Iris issues；mojira.dev 镜像；DESIGN-M2 §1 v0.6、ADR-M2-016 |
| Mac（GL 4.1） | Iris 在 macOS 上没有 compute、SSBO、custom images；包声明必需特性不满足时 Iris 自动关光影。Complementary r5.9.3 在 Mac 上关彩色光照和 compute 程序后能加载（源码结论）；Photon v1.3b 默认开的 SH Skylight 是 `#version 430` compute，README 写「NOT Apple Metal」，默认配置跑不起来 | Iris `FeatureFlags`、`ShaderPack.java:205-240`；两包源码 |
| 光影包许可 | Complementary 许可 1.7：§1.2 允许把原包加进整合包，条件是默认启用时署名、不改任何内容（含变量默认值，§1.2c）、必须经 Modrinth 或 CurseForge 的整合包系统添加、禁止直接上传文件（§1.2d）；§1.3 允许以改名且观感明显不同的 Modified Pack 再分发。所以走 Modrinth/CurseForge 整合包渠道不需要书面许可，自建启动器直传文件才需要。Photon 允许随商业整合包捆绑（附 LICENSE、不单独收费），但没有标 26.2 的发布，最后提交 2026-07-26 | Complementary `License.txt` §1.2、§1.3；Photon LICENSE；Modrinth API |
| Iris 许可 | Iris 是 LGPL-3.0-only，jar 内嵌 AGPL-3.0 的 glsl-transformer 3.0.0-pre3；捆绑 Iris 就是分发 AGPL 代码，要法务判断。玩家自装 Iris 不触发 | 解包 `iris-fabric-1.11.4+mc26.2.jar` 的 `META-INF/jars/`；Iris `LICENSE-DEPENDENCIES` |
| RTS 相机必须改透视 | 我们的正交投影经 Sodium 原样成为光影包的 `gbufferProjection`；Complementary 与 Photon 的线性深度公式写死透视投影，正交下雾、SSAO、水面、体积光的距离全算错（源码结论，未截图） | 26.2 `GameRenderer.renderLevel` javap；Iris `MixinLevelRenderer.java:104-114`；两包 GLSL |
| 阴影绕相机，不绕锚点 | 阴影贴图以相机位置为原点，沿光方向只覆盖 −100.05～156 格；两包都没设 `shadowNearPlane` / `shadowFarPlane`，沿用这组默认值。正午光线竖直时，eye 离地超过 156 格（减去地形起伏）地面就落在远平面外：S5 的 200 格档没有阴影，S3 的 84 格档有。太阳斜照时光线方向上的距离是 h / sin(高度角)，eye 离地 100 格时太阳高度角 ≥ 40° 都在 156 格内（100 / sin 40° ≈ 156） | Iris `ShadowMatrices.java:18-19`、`ShadowRenderer.java`；两包 `shaders.properties`；S3/S5 实测的 eye 高度 |
| 远景一律没有阴影贴图阴影 | 近景雾界在 rd×16 = 192 格（rd=12）。shadowDistance 默认值：Complementary HIGH/VERYHIGH 档 192、ULTRA 224、COMPLEMENTARY 档 256，滑块上限 1024；Photon 默认 128，滑块上限 4096。默认档下远景区全在阴影范围外。B 里两包都写 `dhShadow.enabled = false`、没有 `dh_shadow` 程序；C 经 shadow 回调画出的远景也只在 shadowDistance 内有效 | Complementary `shaders.properties:2-9`、`lib/common.glsl:19`；Photon `settings.glsl`；Iris `ShadowRenderer.java:136,141,299-345` |
| 失败路径的画面 | 不装光影包，或 Mac 上包被 Iris 自动关掉时，Iris 走 `VanillaRenderingPipeline`，画面等于原版 lightmap | Iris `VanillaRenderingPipeline.java` |

## 3. 逐项对比

### 3.1 远近画面一致性

| | A 自写 clipmap + 自研着色器 | B DH + 本地烘焙 | C clipmap 经 Iris API |
| --- | --- | --- | --- |
| 光影包开启时远景是否可见 | 否（malilib #165） | 是，Iris 用包的 `dh_terrain` / `dh_water` 画 | 是，登记为 `TERRAIN_SOLID` 后用包的 `gbuffers_terrain` 画（源码结论） |
| 受光与色调 | —— | 同一光影包着色 | 同一光影包着色 |
| 雾 | —— | 包的 DH 分支：Complementary 边界雾指数 16 → 4、大气雾改为 1−2^(−max(d−40,0)·0.4/100)；Photon 用 `lod_render_distance` 代替 `far` | 包的非 DH 分支：两包边界雾默认开，在 rd×16 处把远景雾成天空（Complementary 95%、Photon 99.6%），必须玩家在包设置里关掉。Complementary 大气雾在 192 格内收满，晴天上限 70%（`ATM_FOG_MULT − 0.25`），实际值随相机高度变：y=40 约 35%、y=63 约 50%、y=80 约 62%、y ≥ 108 为 70%。所以 MMO 平视时远景混约 50% 雾色，RTS 俯视时混 70%，地形辨认得出但发灰（`mainFog.glsl:122-179` 手算，未截图）。Photon 大气雾按每格密度积分，远处可用 |
| 远近接缝 | —— | 包内 dither 交叉淡入：Photon 在 far−32～far−16 格，Complementary 在 0.4far～0.6far（Bayer64，开 TAA 时按帧滚动，不开也能淡入）；DH 自己还有 overdraw 防护 | 顶点和片元着色器都被换成包的，我们没法在着色器里 dither 或 discard，只能在 CPU 网格上挖掉近圈，接缝是一条硬边（推论，未验证） |
| 阴影 | —— | 远景不投射、不接收阴影贴图；Photon 对 LOD 像素用屏幕空间阴影近似 | 可以经 `registerShadowRenderCallback` 投影，但只在 shadowDistance 内，默认档下远景区够不到 |
| 方块轮廓 | 高度场 | 按 Q1 只烘地表与水面，也是高度场。DH 支持体素列，改成体素烘焙能保留建筑轮廓，代价见 3.5 与 R3 | 高度场 |

C 里推远 `Camera.depthFar` 只能推远投影远平面，推不动两包的雾：Iris 的 `far` uniform 取 `effectiveRenderDistance × 16`（`CameraUniforms.java:27,38-40`），与 `depthFar` 无关。所以 C 的边界雾只能由玩家在包设置里关。

### 3.2 工程量（全部自估，未核实）

| 方案 | 人周 | 拆分 |
| --- | --- | --- |
| A | 3～4 | README §8.1 的 clipmap 自估（约 2,500 行）。Q2 之后这份工作在主画面上没有产出 |
| B | 2.5～4 | 烘焙器 1.5～2.5：链接 DH core（该子工程不依赖 Minecraft，`FullDataSourceV2Repo` 建表、`save(dto)` 写入），自己实现 `IBlockStateWrapper` / `IBiomeWrapper`，从高度场包生成 64×64 列的数据源与各层金字塔，每列只写地表与水面。同一份代码放进客户端模组运行，另需处理后台线程与「DH 打开库之前写完」的时机。客户端 0.5～1：`IDhApiSaveStructure` 钉数据目录、`IDhApiWorldProxy.setReadOnly(true)`、按内容哈希下载与校验、DH/Iris 版本锁。LGPL 声明与许可文本 ≤ 0.5 |
| C | 4～6 | clipmap 3～4，另加 1～2：顶点改成 Iris 的 52 B `TERRAIN` 格式并在 CPU 上出网格（约 39 万顶点、约 20 MB，环移时重传）、Camera.depthFar mixin（只管投影远平面，见 3.1）、`assignPipeline` / `assignPipelineShadow` / shadow 回调接入、两个包的观感调参。光影关闭时还要一套自己的着色器 |

### 3.3 升 MC 版本的维护代价

| 方案 | 每次升版要等谁 | 我们要改什么 | 已知记录 |
| --- | --- | --- | --- |
| A | 无 | Blaze3D 管线，README 自估 2～5 人日/次 | Blaze3D 约每季度一改，26.3 已宣布渲染重构 |
| B | DH 与 Iris 两个上游，且必须一起升 | DH API 调用、烘焙器跟 DH core 同版本重编。烘焙器调用的 `FullDataSourceV2Repo` 等是 DH 内部类，不在 DH API 的兼容承诺内，每次升 DH 都要重对。DH 数据格式变更时自带就地迁移（`FullDataSourceProviderV2.java:220,326` 有 V1 → V2 迁移），是否覆盖我们写出的库未验；本地烘焙下重烘只是客户端再跑一次。自估 2～4 人日/次，不含等待 | Iris 1.11.4 调用 DH API 7.1.0 新增的 `getDhBlockRatioAtlasTextureGlId()`，配 DH 3.2.0-b 直接 NoSuchMethodError（Iris #3332、Photon #664）；Photon + DH 在 Iris 1.11.4 上 `dh_terrain.fsh` 编译失败（Iris #3355）；DH 3.3.0（09-17）支持 26.3，比 MC 26.3（09-15）晚 2 天 |
| C | Iris 一个上游 | Blaze3D 管线 + Iris API（v0.4，`assignPipelineShadow` 2026-07-18 才合入，是新接口），自估 3～6 人日/次 | Iris 自 1.20.4 起多数版本在 MC 当天发版，例外有两次：1.21.2 → Iris 1.8.0 晚 26 天（2024-10-22 → 11-17），26.1.1 → Iris 1.10.9 晚 2 天（04-01 → 04-03）。新版本常带回归：1.10.9 打断 Mac（#3153），1.11.5+26.3 当天打断 Mac（1.11.6 同日修） |

### 3.4 Mac、Vulkan、低配、运行时开销

| | B | C |
| --- | --- | --- |
| Mac GL 4.1 | DH OpenGL 引擎只要求 GL 3.3（`GLProxy.java:171-174`）；40 个含 `#version` 的着色器文件里 23 个 `#version 330`、17 个 `#version 330 core`；另有 macOS 专用的 256 KiB 分块上传绕过 GL→Metal 崩溃（`GLBuffer.java:71-82`）。以上是源码结论，未在 Mac 上运行。光影包是否能跑与 C 相同 | 我们的管线 GLSL 330（按 jar 结构推出，未运行）；光影包约束见第 2 节 |
| Vulkan | 不开光影时 DH 的 BLAZE_3D 引擎支持 Vulkan；装 Iris 时 DH 自动切 OpenGL，玩家手动设 BLAZE_3D 则崩（Iris #3098，open） | 不开光影时靠 Blaze3D 的 GLSL→SPIR-V，未验证；开光影时 Iris 不支持 Vulkan |
| 光影关闭或被自动关闭 | DH 用自己的着色器画远景（自带远雾、AO、dither 淡入），画面等于「原版 + DH」 | 我们自己的 clipmap 着色器 |
| 低配档 | DH 有自带的质量设置；光影包用 Complementary POTATO/LOW 或 Photon low。Iris 视频设置里的阴影距离滑块对这两包无效（两包 `shadowDistanceRenderMul = 1.0`） | clipmap 常驻 1.5～1.75 MiB（估算）；光影包档位同左 |
| 运行时开销 | **未核实，B 最大的未知。** 4,096 格半径下 DH 的常驻堆内存、显存、首次进场从 SQLite 载入并建 GPU 缓冲的耗时、每帧 draw 开销都没有数字。对照基线是 #3241 的 Complementary 单开 84 FPS（11.9 ms/帧），离 README P3 的 60 FPS P1%（16.7 ms）只有 4.8 ms 余量，由 V8 实测 | 约 20 MB 顶点显存、约 39 万顶点经包的 `gbuffers_terrain` 着色（估算）；帧时间同样未测 |

### 3.5 带宽与磁盘

| | B | C（A 相同） |
| --- | --- | --- |
| 默认分发物 | 高度场包 5.3～21 MiB（与 C 相同），客户端本地烘焙成 DH 库 | 5.3～21 MiB（未压缩，README §4.2） |
| 本地 DH 库大小 / CDN 退路下载量 | 约 47～140 MB（0.05～0.14 GB）。算式：4,096² 列 × 每列 1～3 点 × 8 B × 4/3 金字塔 × zstd 压缩比 0.2606。每点 8 B 来自 DH `FullDataPointUtil`（id 32 + height 12 + minY 12 + skyLight 4 + blockLight 4 bit），压缩比来自 `EDhApiDataCompressionMode.Z_STD_BLOCK` 的 javadoc。每列 1～3 点指地表、水面，以及可能需要显式写入的一个带天空光的空气点（见第 8 节）。**没有实测** | —— |
| 玩家首次进一个大区 | 本地烘焙：下载 1～4 s + 烘焙耗时（V2 实测）；CDN 退路：50 Mbit/s 下 8～22 s | 50 Mbit/s 下 1～4 s |
| 正式档 64 个大区全量 | 本地烘焙：85～341 MiB；CDN 退路：3～9 GB 存储 | 85～341 MiB |
| 体素烘焙（不选，供 R3 对照） | 每列 4～8 点时 0.19～0.37 GB（同一算式），按 DH wiki「500 KB/s ≈ 5～10 LOD/s」反推 0.27～0.55 GB；50 Mbit/s 下 30～88 s | —— |
| 隐藏区域 | DH 库含整个大区地形，README §8.3 对 LOD 包的剧透警告同样适用。本地烘焙的输入是已按隐藏区域清单排除过的高度场包，自动继承排除；CDN 退路的烘焙器要按同一份清单排除 | 高度场包按 README §8.3 排除 |
| 复制注意 | 库文件必须连 `-wal`、`-shm` 一起复制（DH wiki Server-Owners） | —— |

### 3.6 许可

| | B | C |
| --- | --- | --- |
| 远景组件 | DH 是 LGPL-3.0-only。独立 jar 放 `mods/` 分发满足 §4d(1)；另需 §4a 显著声明、§4b 附 GPL 与 LGPL 全文、§4c 在显示版权的界面列出 DH。我们的客户端模组在运行期链接 DH（含 core 内部类），DH 仍是独立 jar，同样落在 §4d(1)。改 DH 源码则改动版按 LGPL 公开。CDN 退路的烘焙器只在内部运行、不分发，按条文不触发分发义务（本人阅读，非律师意见） | 全部自写，只在编译期引用 Iris API；Iris 由玩家自装时不触发任何分发义务 |
| 共同项 | 第 2 节的 Iris / Sodium / 光影包许可 | 同左 |

### 3.7 失败退路

| 方案 | 失败条件 | 退到哪 |
| --- | --- | --- |
| B | P1 证实预写的库在多人客户端上加载不了 | 先试 `overwriteChunkDataAsync` 注入伪造的 LevelChunk（要在客户端造真 `LevelChunk` 且带邻居，未实跑）；再不行转 C。README 保守方案的「净室实现 DH 协议 + 限速推送」按 README §4.2 是 r=256 首次同步 21～43 min/人，不作为默认退路 |
| B | 本地烘焙一个大区的耗时 > 同一库在 50 Mbit/s 下的下载时间 | 改为 CDN 分发预烘库（0.05～0.14 GB/大区，推算） |
| C | `assignPipeline` 对自建 GpuBuffer 不生效，或 Complementary 关掉边界雾后远景仍糊 | 转 B |
| B 与 C 都不成立 | —— | 开光影时只有原版 192 格视距 + 光影包；远景只在光影关闭时由 A 提供。这等于 Q2 与「远景 4,096 格」二选一，要你重新拍板 |

## 4. 调研后排除的变体

| 变体 | 为什么不成立 | 出处 |
| --- | --- | --- |
| A′：clipmap 自己仿光影包的色调 | Iris 只把原版派生值（`skyColor`、`fogColor`、`sunAngle`、`far`）喂给光影包；包自己算的天空、雾色全在 GLSL 里，没有任何 API 能读回来。两包的天空模型不同：Photon 查预计算的 `scattering.dat` 大气散射表，Complementary 在 `GetSky()` 里解析计算，仿一个就对不上另一个 | Iris `CommonUniforms.java:183,233-242`、`FogUniforms.java:46-55`；Complementary `GetSky()`；Photon `scattering.dat` |
| C′：clipmap 伪装成 DH，蹭 `dh_terrain` | Iris 的 DH 兼容层先查 mod id `distanthorizons`，再反射绑定 DH 的 11 个渲染事件；`modelPos` uniform 由 Iris 内部设置，第三方没有公开入口；DH 通用渲染 API 只收轴对齐盒子，Iris 侧对它抛 `Only indirect is supported with Iris.` | Iris `DHCompat.java`、`LodRendererEvents.java`、`IrisGenericRenderProgram.java:321,326`；DH `DhApiRenderableBox.java` |
| 只靠雾藏接缝 | 不用 Iris 时原版雾在 rd×16 收满（S3 实测），开光影时两包的边界雾也在同一处；把雾推到接缝处等于放弃远景。业界做法是交叉淡入或几何 morph：Losasso & Hoppe 2004 §6.2 用 n/10 宽的过渡带，全文不提雾；Unity LOD Group 用 Cross Fade | https://hhoppe.com/geomclipmap.pdf ；https://docs.unity3d.com/Manual/class-LODGroup.html |
| 正交 RTS 相机 + 光影包 | 两包深度公式写死透视（第 2 节）；DH 按透视公式改写投影矩阵的 m22/m23，Iris `DHCompat.getProjection` 用 `perspectiveFov()` 重建透视矩阵；OrthoCamera #2 报开正交后 LOD 画面异常（MC 1.20.1、DH 2.0.1-a），是否崩溃无法核实（日志链接已失效，issue 于 2025-09-21 无说明关闭） | DH `RenderUtil.java:78-121`；Iris `DHCompat.java:48-56`；GitHub DimasKama/OrthoCamera#2 |

## 5. 需要 P1 在真显卡上验证的项

机器沿用 README §5 P1：一台 RTX 3060 级 Windows、一台 Apple Silicon Mac。组合固定为 MC 26.2 + Sodium 0.9.2 + Iris 1.11.4+26.2 + Complementary r5.9.3，B 另加 DH 3.3.2-26.2。若 M3 开工前按 ADR-M2-016 升到 26.3，组合换成 26.3 + Iris 1.11.6+26.3 + DH 3.3.2-26.3，V3、V7、V8 重跑。

| # | 验证项 | 属于 | 判据 | 不通过时 |
| --- | --- | --- | --- | --- |
| V1 | 用 DH core 写一个 64×64 数据源进 SQLite，客户端装 DH + 我们的 `IDhApiSaveStructure` 覆盖，连 Synaxis 服务端能画出该 LOD。DH 的目录标识是 hashedSeed@dimension，hashedSeed 取 `level.getBiomeManager().biomeZoomSeed`，即服务端在登录/重生包里发的值（DH `ClientLevelWrapper.java:449-459`）；默认目录名模式 `NAME_ONLY` 用玩家在服务器列表里起的名字 | B | 截图里远处出现该 LOD；重连和改服务器列表名后仍命中同一目录 | 按 3.7 表退路 |
| V2 | 实烘一张 4k×4k 测试图，每列只写地表与水面：① 库大小（含 `-wal`/`-shm`）；② 客户端从高度场包本地烘焙的耗时，Windows 机与 Apple Silicon 机各测一次；③ 不写空气点时 LOD 地表亮度是否正常 | B | 两台机的本地烘焙耗时都 ≤ ① 的库在 50 Mbit/s 下的下载时间 | 按 3.7 表改 CDN 分发；③ 发暗则每列加一个空气点，重记 ① |
| V3 | B 开 Complementary 后的远近衔接 | B | 光照、雾、水面各一张截图；远景是否有阴影；dither 过渡带有无闪烁（录屏 10 s） | 记录现象，与 V5 对比后定 |
| V4 | 把一张 256×256 的 52 B `TERRAIN` 格式网格经 `assignPipeline` / `assignPipelineShadow` / shadow 回调画出来，并确认 Fabric `LevelRenderEvents.START_MAIN` 在 Iris + Sodium 下照常触发。可参考 malilib 仓库里整份注释掉的 `IrisCompat.java`（对每个 RenderPipeline 调 `assignPipeline`，malilib #165 报告人指出） | C | 光影开启时可见、受光、受雾；记环移一次重传的耗时 | C 不成立 |
| V5 | C 在 Complementary 下关边界雾前后各截一张 4,096 格远景；Photon 同样一组（Photon 只在 Windows 机上测） | C | 远景地形能辨认；接缝在美术评审里可接受 | C 只能配 Photon，而 Photon 在 Mac 默认跑不起来 → 转 B |
| V6 | 透视 RTS 相机、eye 离地 ≤ 100 格时，光影包的雾、水面、阴影是否正常；再切正交截一张留档 | 共同 | 透视正常；正交失真记录在案 | 透视也失真 → RTS 模式关光影 |
| V7 | Mac 上 Iris 1.11.4 + Complementary r5.9.3 能否加载；B 加 DH 后是否崩溃 | 共同 | 能加载且 1080p、LOW 档 P1% ≥ 45 FPS（README Q5 的低配线） | Mac 默认关光影，走原版 + 远景 |
| V8 | RTX 3060、1080p、rd=12、远景 4,096 格、Complementary 默认档的 P1%；B 在同一场景下开/关 DH 各测一次，并记 DH 的堆内存、显存、首次进场载入耗时 | 共同 | P1% ≥ 60 FPS（README P3 判据），与 #3241 的 84 FPS 基线对照；开 DH 比关 DH 的平均帧时间多 ≤ 2 ms（自定预算：4.8 ms 余量里给 DH 不到一半） | P1% 不达标 → 默认档降一级；DH 超 2 ms → 把 DH 半径降到 2,048 格重测，仍超则回到你这里重议 Q6 |

V1、V2 与 V4 互不依赖，可以并行，总计 1 周内可做完（自估）。

## 6. 推荐与切换条件

默认选 B。理由按分量排：① 只有 B 能进入光影包作者为 LOD 写的专用分支，雾和接缝有人调过；C 要靠玩家手动关边界雾，而且 Complementary 的非 DH 大气雾给远景混 50～70% 雾色。② 按 Q1 只烘高度场、在客户端本地生成库之后，B 的下载量与 C 相同；DH core 不依赖 Minecraft，烘焙器能直接写出字节兼容的库，比原报告设想的 `overwriteChunkDataAsync` 更直。③ 光照不经服务端，与 Q8「服务端不读光照」一致。④ 不开光影时 DH 仍能独立画远景，失败路径比 C 好看。

B 的代价：DH 在 4,096 格半径下的运行时开销没有数字（V8 定）；每次升 MC 要等 DH 与 Iris 两个上游对齐版本；本地烘焙依赖 DH core 内部类，每次升 DH 要重对；客户端要附 LGPL 声明。

切换条件（按先后判定）：

1. V1 不通过，且伪造 LevelChunk 的注入也不通过 → 转 C。
2. V2 里任一台机的本地烘焙耗时 > 该库在 50 Mbit/s 下的下载时间 → 改为 CDN 分发预烘库，方案仍是 B。
3. V8 里开 DH 的帧时间增量在 2,048 格半径下仍 > 2 ms → 回到你这里，在「远景 4,096 格」与「光影包主画面」之间取舍。
4. V3 截图里远近衔接不如 V5 → 转 C（这条要两组截图放一起给美术评审）。
5. V4 与 V1 都不通过 → 回到你这里，在「开光影」与「远景 4,096 格」之间二选一。

光影包默认选 Complementary Reimagined：它有 26.2 发布（r5.9.3，2026-09-15）、能在 Mac 上降配加载，符合 Q5。分发走玩家自装，或经 Modrinth/CurseForge 整合包引用原包（许可 §1.2）。Photon 可以捆绑，但没有 26.2 发布、Mac 默认跑不起来，只作 Windows 玩家的可选项。

## 7. 需要你回答的问题

| # | 问题 | 默认选项 | 换另一个的代价 |
| --- | --- | --- | --- |
| R1 | Q9 定了「Sodium 不捆绑」，而 Iris 硬依赖 Sodium。Iris、Sodium、光影包是否全部由玩家自装，客户端只做检测和引导？ | 全部玩家自装，我们只捆绑自己的模组和 DH（LGPL，附声明） | 要一键安装时分两种：走 Modrinth/CurseForge 整合包，Complementary 按许可 §1.2 引用原包即可，不需要书面许可，但不能改包内任何默认值（如关边界雾）；自建启动器直传文件，Complementary 要拿作者书面许可。两种都要法务对 Sodium 的 PolyForm Shield 不竞争条款与 Iris 内嵌的 AGPL glsl-transformer 出意见 |
| R2 | RTS 相机改为带俯角的透视、eye 离地 ≤ 100 格，可以吗？（DESIGN-M2 §28.2 的「正交 vs 透视」仍待你选，Q2 之后正交与光影包、DH 都冲突；100 格让太阳高度角 ≥ 40° 时地面仍在阴影贴图的 156 格远平面内） | 透视 | 坚持正交 → RTS 模式下关闭光影和 DH，主画面在 RTS 里只剩原版 + 自写远景 |
| R3 | B 按 Q1 只烘高度场（每列 1～3 点，不保留建筑轮廓），由客户端从 5.3～21 MiB 的高度场包本地生成 DH 库，可以吗？ | 可以；本地烘焙慢于下载时按切换条件 2 改 CDN 分发（0.05～0.14 GB/大区） | 要远景保留建筑轮廓 → 体素烘焙每列 4～8 点，只能走 CDN，每大区 0.19～0.55 GB、50 Mbit/s 下首次进入 30～88 s，正式档 64 个大区 CDN 存储 12～35 GB |

## 8. 未核实事项

- DH 库大小：只有推算（0.05～0.14 GB/大区）。每列要不要显式写一个带天空光的空气点未查：DH 的面光照取自相邻数据点，不写时地表可能发暗，V2 ③ 定。DH GitLab #981 报的「550 block radius = 12 GB」单位可疑，没有采信。
- 烘焙链路没有编译运行过；客户端本地烘焙与 DH 打开同一 SQLite 库的先后与并发未查；DH 自带的 V1 → V2 迁移是否覆盖我们写出的库未验。
- DH 在 4,096 格半径下的堆内存、显存、载入耗时与帧时间开销没有任何数字，V8 定。
- Complementary r5.8 更新日志称 DH 获得「shadows」，与所读的 `mainLighting.glsl` 第 6、150 行排除 DH 地形的代码不一致，含义未查明，V3 截图定案。
- C 的全部结论都是源码推断：自建 GpuBuffer 能否按 52 B 格式取属性、`START_MAIN` 在 Iris 下的触发时机、CPU 挖洞后接缝观感、约 20 MB 顶点显存与环移重传耗时。
- Complementary 大气雾 50～70% 的饱和值是按 `mainFog.glsl` 公式手算（晴天、rd 12、`ATM_FOG_MULT` 0.95、`ATM_FOG_ALTITUDE` 63），未截图。
- 我们的模组能否替玩家写 Iris 保存在 shaderpacks 目录下的每包设置文件来默认关边界雾，未查；这不改包内容，不受 Complementary 许可 §1.2c 约束，改包内默认值则受约束。
- Iris #3241 与 #3274 在 26.2 分支会不会修复：26.2 分支最后一次提交 2026-09-14，之后无动静。两者在 26.3 + Iris 1.11.6 上是否复现，未查。
- 所有工程量与升版人日都是自估。
- GTX 1650、Apple M1 在 26.2 + Iris 1.11.4 下开光影的帧率没有一手数据；唯一的 Apple Silicon 数据是 2023 年 MC 1.20.2 上的社区表（M1 Pro、Complementary Reimagined 70/61/57 FPS，MoustacheOff/AppleSilicon-Minecraft-Shaders DATA.md），只能当量级参考。

## 9. 参考来源

本机核对：

- `iris-fabric-1.11.4+mc26.2.jar`（Modrinth，2026-09-13）：`javap net.irisshaders.iris.api.v0.IrisApi`、`IrisApiV0Impl`；`META-INF/jars/` 列表
- `ComplementaryReimagined_r5.9.3.zip`、`photon_v1.3b.zip`（Modrinth）：`shaders.properties`、`lib/common.glsl`、`lib/lighting/mainLighting.glsl`、`lib/atmospherics/fog/mainFog.glsl`、`settings.glsl`、`include/fog/*`、`program/dh_terrain.*`、`program/d4_deferred_shading.fsh`、License
- 26.2 `minecraft-client.jar`：`GameRenderer.renderLevel`
- 本仓库：`docs/research/large-world-2026-09/README.md`、`docs/research/client-spikes-2026-09/README.md`（S2、S3、S5）、`docs/DESIGN-M2.md` §1 v0.6、§28.2、ADR-M2-016、`client-fabric/src/gametest/.../mixin/CameraMixin.java`

外部：

- Iris：https://github.com/IrisShaders/Iris 分支 26.2（HEAD f61d950f）：`api/v0/IrisApi.java`、`compat/dh/*`、`shadows/*`（含 `ShadowMatrices.java`）、`uniforms/*`（含 `CameraUniforms.java`）、`gl/shader/StandardMacros.java`、`mixin/MixinShaderManager_Overrides.java`、`mixin/IrisMixinPlugin.java`、`features/FeatureFlags.java`、`shaderpack/ShaderPack.java`；PR #3239；issues #3050、#3098、#3153、#3202、#3241、#3274、#3288、#3332、#3355、#3357；Modrinth 版本列表（26.2 与 26.3）
- Distant Horizons：https://gitlab.com/distant-horizons-team/distant-horizons （main@fe0b1331：`WrapperFactory.java`、`ClientLevelWrapper.java`、`GLProxy.java`、`GLBuffer.java`、`AbstractModInitializer.java`、versionProperties/26.2.0.properties）；https://gitlab.com/distant-horizons-team/distant-horizons-core （main@effbc0a2：`DhClientLevel.java`、`ServerLevelModule.java`、`ClientOnlySaveStructure.java`、`FullDataSourceV2Repo.java`、`FullDataSourceProviderV2.java`、`FullDataPointUtil.java`、`EDhApiDataCompressionMode.java`、`IDhApiSaveStructure.java`、`IDhApiWorldProxy.java`、`RenderUtil.java`、`Config.java`、sqlScripts）；wiki Server-Owners；releases 3.3.0、3.3.2；https://modrinth.com/mod/distanthorizons
- ShaderDoc：https://raw.githubusercontent.com/IrisShaders/ShaderDoc/master/dh-support.md ；macOS 限制 https://shaders.properties/current/reference/miscellaneous/macos/
- Complementary：https://github.com/ComplementaryDevelopment/ComplementaryReimagined （main，2026-09-14）：`License.txt`（Complementary License Agreement 1.7）、`shaders/lib/atmospherics/fog/mainFog.glsl`、`program/dh_terrain.glsl`
- Photon：https://github.com/sixthsurge/photon （main；README、LICENSE、`settings.glsl`、`scattering.dat`；issues #443、#664）
- malilib #165：https://github.com/sakura-ryoko/malilib/issues/165 ；同仓库注释掉的 `IrisCompat.java`
- OrthoCamera #2：https://github.com/DimasKama/OrthoCamera/issues/2
- Mojira：MC-307596、MC-308887（mojira.dev 镜像）
- Losasso & Hoppe 2004：https://hhoppe.com/geomclipmap.pdf ；Unity LOD Group：https://docs.unity3d.com/Manual/class-LODGroup.html
- Minecraft Middle Earth 分发预生成 DH 库：https://www.mcmiddleearth.com/community/resources/distant-horizons-lods.171/
- Apple Silicon 光影帧率社区表：https://raw.githubusercontent.com/MoustacheOff/AppleSilicon-Minecraft-Shaders/main/DATA.md
