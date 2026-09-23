# Distant Horizons 本地注入 + Iris 光影 spike（2026-09-23，无头真客户端）

给大世界报告（`docs/research/large-world-2026-09/README.md`）第 5 节 P1⑥ 与第 8.2 节两条「未实测」补实测证据：
DH 的 `overwriteChunkDataAsync` 在连 Synaxis 的多人客户端上能不能产出并画出 LOD，Iris 光影包会不会给 DH 的 LOD 着色，
以及 gametest 的 RTS 相机 mixin 与 DH、Iris 同开会不会崩。只做实测，不给「clipmap 还是 DH」的结论。

全部在 `synaxis-mc-client:dev` 容器里跑（Xvfb + Mesa llvmpipe，GL 4.5 core，aarch64），画质、帧率、阴影效果都要在真显卡上复核。

## 装了什么

| 组件 | 版本 | 来源（容器内下载，SHA-1 钉死在 `Fetch.java`） |
| --- | --- | --- |
| Distant Horizons | 3.3.2（API major 7，网络协议 16） | Modrinth `DistantHorizons-3.3.2-26.2-fabric-neoforge.jar` |
| Sodium | 0.9.2+mc26.2 | Modrinth，Iris 的硬依赖 |
| Iris | 1.11.4+mc26.2 | Modrinth |
| 光影包 | Complementary Reimagined r5.9.3 | Modrinth；包内有 `dh_terrain` / `dh_water` 程序 |

第三方 jar 与光影包都不进仓库，也不落宿主机：`Fetch.java` 在容器里下到 `/dh/mods`、`/dh/shaderpacks`，jar 经
`-Dfabric.addMods=/dh/mods` 交给 Fabric Loader。不能预先放进 `build/run/clientGameTest/`：Loom 每次启动前清空 gametest
运行目录（第一次尝试时 DH jar 被清掉，报告里是 `distanthorizons absent`）。光影包因此由 spike 在进世界后拷进 Iris 的
shaderpacks 目录，再用 `IrisConfig#setShaderPackName` 选中。

## 复现

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
# 服务端：同 ../README.md「无头运行方式」第 2 步，端口换成 25577，目录换成 /tmp/synaxis-dhiris
# 客户端：MODS=dh 只装 DH；MODS=dh-iris 装 DH + Sodium + Iris + 光影包
MODS=dh      SYNAXIS_GAMETEST_SERVER=host.docker.internal:25577 OUT=/tmp/synaxis-dhiris/out-dh     docs/research/client-spikes-2026-09/dh-iris/run.sh
MODS=dh-iris SYNAXIS_GAMETEST_SERVER=host.docker.internal:25577 OUT=/tmp/synaxis-dhiris/out-dhiris docs/research/client-spikes-2026-09/dh-iris/run.sh
cat /tmp/synaxis-dhiris/out-dh/synaxis-gametest/report.txt
```

- `run.sh` 用的是同一个镜像与 Gradle 卷（`synaxis-mc-gradle`），容器名 `synaxis-mc-client-dhiris`，`--rm`。同一时刻只能有一个容器挂这个卷。
- 退出码恒为 1，是 `../README.md` 记过的 `libopenal.so+0x9c670` 退出段错误；两次最终运行都先打出了 `Stopping!`，没有 `crash-reports/` 目录。
- 耗时：`runClientGameTest` 在 `MODS=dh` 下 Gradle 报 1 分 20 秒，`MODS=dh-iris` 下 3 分 11 秒，另加容器内下载与 Loom 配置。
  光影开着的那一轮（300 tick 等待 + 两个机位各 20 tick）在日志里从 08:27:03 到 08:27:47，约 44 s。
- spike 代码在 `client-fabric/src/gametest/.../dh/DhIrisSpike.java`，`SYNAXIS_SPIKES` 点名 `dh` 才跑（不设时不跑，S2～S5 原有行为不变）。
  DH 与 Iris 都走反射，gametest 源码集编译不依赖它们。
- 两份最终运行的 `report.txt` 原样放在本目录：`report-dh.txt`、`report-dh-iris.txt`；同一次运行的 `logs/latest.log` 按固定 grep 抽成
  `logs-excerpt-dh.txt`、`logs-excerpt-dh-iris.txt`（文件头写着 grep 式），下文引用的日志原文都出自这两份。
- 截图缩到宽 960、256 色，原图 1280×720，下文像素数都按原图算。单张：`magick <原图> -resize 960x -colors 256 PNG8:<名>.png`；
  `cam-sheet-*.png` 是 6 个相机形态按 3×2 拼的，每格缩到 320×180，两行之间一条 3 px 白线：
  `magick \( 1.png 2.png 3.png -resize 320x180 +append \) -size 960x3 xc:white \( 4.png 5.png 6.png -resize 320x180 +append \) -append -colors 256 PNG8:cam-sheet.png`。

## 做法

对局世界是 rts-512（x、z 各 0～511），高 64 格（`getMinY()` 0、高度 64；第一次按高 384 写地形时 `getSection` 越界，就是这么发现的），
玩家出生在 (256, 40, 256)，客户端视距 10。spike 在 x 544～927、z 64～447 造 24×24 = 576 个 `LevelChunk`（不进客户端区块缓存，
服务端也从没发过这些区块）：起伏地形顶面按高度用草、石、雪，每 64 格立一根 14 格高的红色混凝土柱。每块以
`new Object[] {chunk, clientLevel}` 调 `IDhApiTerrainDataRepo#overwriteChunkDataAsync`。DH 3.3.2 的这个接口只收
`[ChunkAccess, Level]`（`WrapperFactory#createChunkWrapper` 反汇编），不收 `DhApiChunk`。

判据三样：DH 数据库里有没有这块的数据（`getColumnDataAtBlockPos`）；同一机位注入前后截图的像素差（任一通道差 > 24 计一个像素）；
肉眼看截图。两个机位都是 spike 相机的透视模式：`FAR_VIEW` 眼睛约在 (302, 51, 256) 朝东，远处是注入区；`LOD_VIEW`
眼睛约在 (591, 88, 256)，已经在注入区上空，画面里没有任何原版区块。

## ① DH 本地注入能否在多人客户端产出并渲染 LOD：能

| 指标 | 只装 DH（`report-dh.txt`） | DH + Iris，光影关（`report-dh-iris.txt`） |
| --- | --- | --- |
| DH 世界类型 | `CLIENT_ONLY`，进世界 0 tick 后 `worldLoaded()` 为真 | 同左 |
| 造 576 个 `LevelChunk` + 逐块调用的总耗时 | 148 ms，576 块全部返回 success | 170 ms，576 块全部 success |
| 注入前 (736, 256) 的列数据 | `empty` | `empty` |
| 数据可查到的时间（每 20 tick 查一次） | 注入后 60 tick | 注入后 40 tick |
| (736, 256) 的列数据 | 顶 y 48 `snow_block`，天光 15，detail 0（方块级） | 同左 |
| (560, 80) 的列数据 | 顶 y 11 `grass_block`，天光 15，detail 0 | 同左 |
| 对照 (1200, 256)，未注入 | `empty` | `empty` |
| `FAR_VIEW` 注入后 400 tick 对注入前 | 12,675 px 变化（1.4%），集中在地平线 y 299～345 一带 | 14,919 px（1.6%） |
| `LOD_VIEW` 注入后对注入前 | 561,290 px 变化（60.9%） | 561,724 px（61.0%） |

耗时一行是 `DhIrisSpike#injectAndCompare` 里从造第一块到最后一次 `overwriteChunkDataAsync` 返回的墙钟，含 `synthetic()` 造块，
不是纯 API 耗时；`overwriteChunkDataAsync` 本身是异步的，返回时 LOD 还没建完。本分支此前一版运行（提交 `0cc2244386` 里的 report）
两列分别是 153 ms / 219 ms、都是 40 tick，四次运行合起来是 148～219 ms、40～60 tick。

截图：`screenshots/dh-far-before.png` → `dh-far-after-400.png`（注入后 400 tick 那张；地平线上多出雪顶山丘与红柱），
`dh-lod-before.png`（只有天空）→ `dh-lod-after.png`（整片注入地形，草、石、雪分层与红柱清楚，光照正常，没有发黑）。
均取自只装 DH 的那次运行。

结论与观察：

- P1⑥ 成立：不装 DH 服务端插件时，`DhClientLevel#shouldProcessChunkUpdate` 因 `networkState` 未就绪直接返回 true，注入的区块走正常的
  LOD 构建与渲染。天光按 15 进了 LOD，不需要客户端光照引擎里有这些区块。
- DH 在多人客户端上**也会自己收集服务端发来的区块**：日志 `Loaded [673] waiting chunk wrappers`（dh-iris 那次是 676），数据库落在运行目录
  `Distant_Horizons_server_data/synaxis/<服务器>@<维度>/`。也就是说装了 DH 的玩家会把走过的近圈区块都存成 LOD，
  隐藏区域的剧透问题（大世界报告 8.3）对 DH 路线同样成立。
- 查询接口有线程约束：`getColumnDataAtBlockPos` 在 gametest 线程上报 `Minecraft.getInstance() cannot be called from the gametest thread`，
  放到渲染线程上又会阻塞在一个要渲染线程完成的 future 上，客户端卡死（当时用 jstack 确认，那次的 jstack 输出没有留档）。spike 改为在独立平台线程上查询。
  高画质档真接入时，注入与查询都不能在渲染线程上同步等结果。
- 不传 `IDhApiTerrainDataCache`（传 null）时查询直接失败：`Missing [IDhApiTerrainDataCache]`，要先 `createSoftCache()`。

## ② Iris + Complementary 在 llvmpipe 下：能启动，DH 的 LOD 进了光影管线，但画面被一层浅蓝雾盖住，阴影与色调看不出来

- 启动：Iris 1.11.4 + Sodium 0.9.2 + DH 3.3.2 同装能启动。DH 启动时打出
  `Changing Distant Horizons' rendering engine to [OPEN_GL] to allow for Iris rendering`，即 DH 默认的 `AUTO` 在检测到 Iris
  时退回 OpenGL 渲染器，不走 Blaze3D；Iris 打出 `DH Iris events bound`。大世界报告 2.3 写的「Blaze3D 模式与 Iris 同开会崩溃」
  在 3.3.2 里由 DH 自己规避了，代价是这条路径不能跟着 Minecraft 切 Vulkan（DH 日志原话：「will be unavailable once Minecraft moves to Vulkan」）。
- llvmpipe 报 GL 4.5，Iris 开 DSA（`OpenGL 4.5 detected, enabling DSA.`）；Complementary 编译通过，`isShaderPackInUse()` 为 true。
  以上日志原文见 `logs-excerpt-dh-iris.txt`。
- 光影开关对比（`report-dh-iris.txt`，原图 921,600 px）：

| 机位 | 光影开对光影关的像素变化 |
| --- | --- |
| `FAR_VIEW`（近处原版区块 + 远处 LOD） | 870,296 px（94.4%） |
| `LOD_VIEW`（只有 LOD） | 921,432 px（100.0%） |
| `FAR_VIEW`，光影包参数改 `ATM_FOG_ALTITUDE=0`、`ATM_FOG_DISTANCE=300`、`BORDER_FOG=false` | 864,318 px（93.8%）；对默认参数那张 12 px（0.0%） |
| `LOD_VIEW`，同上三个参数 | 921,369 px（100.0%）；对默认参数那张 1,914 px（0.2%），散在 y 215～404 |

  「对默认参数那张」与其余各行同一判据（任一通道差 > 24），由 spike 算好写进 report。

- 截图：`iris-off-lod.png` 与 `iris-on-lod.png`、`iris-on-lowfog-lod.png`；`iris-off-far.png` 与 `iris-on-far.png`、`iris-on-lowfog-far.png`。
  光影开着时 LOD 地形整体变成浅蓝白、红柱变成灰蓝，山丘轮廓只剩很淡的明暗；原版近圈区块上看得到光影包的色调与体积云，
  LOD 上看不出阴影。
- 判断：LOD 确实经过了光影包（包里有 `dh_terrain`，关掉 DH 雾 `enableDhFog` 的是 Iris 的 `LodRendererEvents`，LOD-only 机位 100% 像素改变），
  所以「DH 的 LOD 被光影包着色」在管线层面成立；**但这层浅蓝雾的来源没有查清**：改三个雾参数后 Iris 日志确认
  `Profile: HIGH (+3 options changed by user)`，画面只变了 0.2%（LOD 机位）和 12 px（远景机位），雾没有散，说明不是这三项。怀疑方向有两个，都未验证：对局世界只有 64 格高、整张地形低于包的
  海平面假设；或 llvmpipe 下深度合成有问题。阴影与色调要在真显卡、正常高度的世界上复核，本轮不下结论。

## ③ RTS 相机 mixin 与 DH、Iris 同开：不崩，但 DH 在正交相机下不画 LOD，在透视相机下会在相机周围挖一个洞

- 不崩：gametest 的四个 mixin（`CameraMixin`、`GameRendererMixin`、`LevelExtractorMixin`、`MouseHandlerMixin`，`required: true`）
  在「只装 DH」与「DH + Sodium + Iris + 光影开」两种组合下都成功应用，两次都跑完 6 个相机形态后正常 `Stopping!`，
  没有 `MixinApplyError`、没有崩溃报告。判据：mixin 配置 `required: true`、`defaultRequire: 1`，应用失败会在启动时直接崩；
  report 里 6 行 `CAM ... rendered` 都在；两份 `logs-excerpt-*.txt` 里 mixin 报错一行都没有，结尾是 `Stopping!`。
- 截图：`cam-sheet-dh.png`（只装 DH）与 `cam-sheet-dh-iris.png`（光影开），每张 3×2，依次为：出生点正交 h96、出生点透视、
  注入区上空正交 h96、注入区上空正交 h480、注入区上空透视 d64、注入区上空透视 d400。

| 形态 | 只装 DH | DH + Iris 光影开 |
| --- | --- | --- |
| 出生点正交 h96（原版区块） | 只画出中间一圈，其余是暗色；只装 DH 共跑过三次，两次是这样，较早一版 spike 代码的一次是完整的，**不稳定** | 完整，光影色调 |
| 出生点透视 d64 | 正常 | 正常 |
| 注入区上空正交 h96 / h480 | **全空**，只有天空色 | **全空**，只有天空、雾与一块八边形天空盘 |
| 注入区上空透视 d64（眼睛高 75） | 画面上半有 LOD，眼睛下方一大片空洞 | 同样只剩两根红柱，其余是雾 |
| 注入区上空透视 d400（眼睛高 366） | 整块注入地形完整 | 注入区是一块被雾盖住的矩形 |

- 正交全空的原因（反汇编推断，未单步验证）：DH 的 `RenderUtil#setDhProjectionMatrix` 拿 Minecraft 的投影矩阵，再用
  `setClipPlanes` 按透视矩阵的形式改写 `m22`/`m23` 放进自己的近远平面；我们的正交矩阵经这一步后深度范围错位，LOD 全被裁掉。
- 透视下的空洞：DH 的近平面 = `overdrawPrevention`（默认 -1，自动）× 原版渲染距离，按**相机**位置算，假设相机附近由原版区块填满。
  RTS 相机离开玩家、飞到没有原版区块的地方时，相机周围约一个原版视距内什么都没有（d64 有洞、d400 完整，与此一致）。

## 对「clipmap 还是 DH 本地注入」有用的事实

1. 数据通路走得通：客户端自己造 `LevelChunk` 喂给 DH，576 块造块加调用共 148～219 ms、40～60 tick 内可查询、画面出现。前提是 DH 3.3.x 的
   `[ChunkAccess, Level]` 入参形状；这是 DH 内部包装层的约定，不是稳定 API，DH 升版要复核。
2. 对 RTS 相机不友好：正交投影下 DH 一个 LOD 都不画；透视下相机周围有一个原版视距的空洞。要用 DH，得在 DH 的投影改写与
   `overdrawPrevention` 上打补丁（mixin 进 DH，或者 DH API 的渲染事件里改矩阵），这部分工作量没估。
3. 与 Iris 同开时 DH 固定走 OpenGL 渲染器，不跟 Blaze3D/Vulkan。
4. DH 在多人客户端默认把收到的近圈区块也存成 LOD。

## 未完成与限制

- 全部是 llvmpipe 画面；阴影、光影包在 LOD 上的色调、帧率都没有有效数据。
- ② 的浅蓝雾来源未查清。
- Photon 光影包没有试：Modrinth 上 `photon-shader` 标到 26.1.2 为止，没有 26.2 标签，本轮只用了 Complementary。
- 出生点正交 h96 在只装 DH 时两次运行结果不一致，原因未查。
- 没有测 DH 的 `overdrawPrevention` 设为 0、或改写 DH 投影后正交能否出图；这是③要不要继续的下一步。
