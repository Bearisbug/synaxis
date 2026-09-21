# 阶段 0 客户端 spike 执行清单（2026-09-21）

`docs/DESIGN-M2.md` §26 阶段 0 的 spike ② 要的是**肉眼看画面**的结论，spike ① 与 spike ④ 各剩一半
只有真启动一次客户端才判得掉。这台仓库所在的机器没有图形环境，所以这几件事由**验收人在自己机器上**
做；本文件是照着能执行的步骤、判据与回写目标，不是重述设计文档。spike ③ 不需要客户端，已在服务端
跑完（见 S6），列在这里只是为了让四个 spike 的账是齐的。

- 执行人：验收人（Bearisbug）。仓库里的 agent 不启动客户端。
- 产物：每个 spike 一段结论 + 截图 / 数字，截图放本目录，结论**回写设计文档对应那一行**（每节末尾点名）。
- 顺序有依赖：S0 → S1 之后才谈得上 S2～S5（没有握手就进不了玩法模式，屏幕上也就没有单位）。
- 26.2 是不混淆的，下面点名的类与方法都可以在本机现有的 jar 上复核，不必下载任何东西：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
J=~/.gradle/caches/fabric-loom/26.2/minecraft-client.jar   # Loom 第一次构建时已解包，勿重下
$JAVA_HOME/bin/javap -p -cp $J net.minecraft.client.Camera
```

§26 阶段 0 的四个 spike 与本文件各节的对应：

| spike | 在本文件的哪一节 | 还剩什么 |
| --- | --- | --- |
| ① `:protocol` 被 Fabric JiJ / 跨构建消费 | **S0** | 构建期那一半已验（`REQ-M2-020a`）、跨构建消费的**本机等价链**也已验（`REQ-M2-020b`：`:protocol:publishToMavenLocal` + `client-fabric` 的 `build`），CI 工作流 `.github/workflows/client-mod.yml` 本身**一次都没跑过**，待首次推分支后确认（`TC-GAME-045` 第 8 步）；只剩「Fabric Loader 真加载这个嵌套 jar」这一步要启动一次客户端 |
| ② 相机、剔除、反投影、section 可见性与输入路径 | **S2 / S3 / S4 / S5** | 整个 spike 都要肉眼看画面，一步也没做 |
| ③ 1,024 个区块的墙钟与堆增量 | **S6（本轮已跑完）** | 无。数字见该节，验收人不用做 |
| ④ configuration 阶段能不能发包 | **S1** | 源码结论已出（见 `spike4-configuration-send.md`：**能发**），但那是读 jar 得出的；运行期这一半仍要跑，`ADR-M2-010` 在跑出来之前保持 Proposed。**2026-09-22 起 S1 不用再加临时代码**：握手的两条阶段路径已随阶段 6a 一起交付，起服登录即可 |

## 前置：把模组装进 26.2

```bash
# 1. 发布 :protocol 到 ~/.m2（客户端构建按坐标消费它，ADR-M2-015）
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
./gradlew :protocol:publishToMavenLocal --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts
# 2. 出模组 jar
cd client-fabric && ./gradlew build --no-daemon      # → build/libs/client-fabric-0.1.0.jar
```

- 启动器里装 **Fabric Loader 0.19.5 / Minecraft 26.2** 的 profile（版本钉死见 `docs/ENGINE.md` §5）。
- `.minecraft/mods/` 放两个 jar：`client-fabric-0.1.0.jar` 与 `fabric-api-0.161.0+26.2.jar`。
  `:protocol` **不要**单独放——它已经 JiJ 在模组 jar 的 `META-INF/jars/protocol-dev.jar` 里。
- 服务端在仓库根起（`docs/TEST.md` §3）：核心模式 `-Dsynaxis.app.core-mode=on`，玩法模式不加该属性。

## S0 客户端能起、模组能加载（判掉 spike ① 的运行期一半）

- 要判的：Fabric Loader 真加载嵌套的 `protocol-dev.jar` 时，会不会被 `module-info.class` 或
  「`dev` 不是 SemVer」绊住。构建期那一半已经验过（§28.2 spike ① 那行）。
- 步骤：起**核心模式**服务端（核心模式豁免握手，没装握手通道也能进世界）→ 启动客户端 →
  多人游戏直连 `127.0.0.1:25565` → 进世界后打开 Mods 列表。
- 判据：① 游戏日志里有 `synaxis` 模组被加载、**没有** `NoClassDefFoundError` /
  `ModResolutionException`；② Mods 列表里 `synaxis` 存在（嵌套的 `protocol` 作为它的 JiJ 依赖，
  Loom 给它生成的 id 是 `io_github_bearisbug_synaxis_protocol`）；③ 能进世界。
- 不通过时：把 `:protocol` 的发布版本改成 SemVer 再试。版本号**只认环境变量** `GAME_ENGINE_VERSION`
  —— `build-src/src/main/kotlin/synaxis.java-library.gradle.kts` 与 `client-fabric/build.gradle.kts` 都写的是
  `System.getenv("GAME_ENGINE_VERSION") ?: "dev"`，Gradle 的 `-P` 是 project property，进不了 `System.getenv`，
  加了等于没加。**发布与客户端构建必须带同一个变量**：只给发布那条，客户端仍会去解析 `protocol:dev` 而直接失败。

  ```bash
  export GAME_ENGINE_VERSION=0.1.0                      # 两条命令共用同一个 shell
  ./gradlew :protocol:publishToMavenLocal --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts
  cd client-fabric && ./gradlew build --no-daemon
  ```

  此时嵌套 jar 的名字随之变成 `META-INF/jars/protocol-0.1.0.jar`，上面判据 ② 照此看。仍不行则去掉 `include`、
  改成把 `:protocol` 的类 shade 进模组 jar，并回来改 `ADR-M2-015`。
- 回写：`docs/DESIGN-M2.md` §28.2 里 spike ① 那一行（目前写着「剩运行期一半」）。

## S1 configuration 阶段能不能发包（spike ④，`ADR-M2-010` 的全部赌注）

- 要判的：Fabric 客户端能否向一个**从不发 `minecraft:register`** 的服务端在 configuration 阶段
  发自定义负载。发不出去，则每个装了模组的玩家 3 s 后被 `synaxis:client-mod-required` 踢掉。
- **先读 `spike4-configuration-send.md`**：那是同目录的源码级结论（`fabric-networking-api-v1:6.3.4+2989c6a09e`
  的源码与字节码），一句话是**发得出去** —— `send()` 这条路上根本不读对端声明的通道集合，`canSend()`
  只是一句与 `send()` 无关的查询。本节要跑的就是把那个桌面结论变成运行期事实；**跑出来之前
  `ADR-M2-010` 保持 Proposed，不要提前改它的 Status。**
- **不需要再加临时代码**（2026-09-22 起）：模组已经把握手按两条阶段路径一起交付，起服登录即是本节。
  落点与当初写在这里的三件事一致，复核时照着看即可：

  1. `SynaxisClientMod#onInitializeClient` 把 `synaxis:handshake` 注册进
     `PayloadTypeRegistry.serverboundConfiguration()` 与 `clientboundConfiguration()`，
     `ClientConfigurationNetworking.registerGlobalReceiver(...)` 收 `S2C_HELLO`。
     两个方向都要注册——编码器按这张表找 codec，漏一个是「本地就发不出去」，与服务端无关。
  2. `ClientConfigurationConnectionEvents.START` 里发一次
     `C2SHello(mod_version, ProtocolSchema.SCHEMA_VERSION)`（`SCHEMA_VERSION` 当前 = 1）；编解码
     委托 `HandshakeMessages`，两端只有这一份。
  3. `START` 的回调跑在 netty 线程上（`ClientConfigurationNetworkAddon.isOnReceiveThread()` 对
     configuration 返回 `true`），发包本身线程安全，模组的回调里也不碰任何客户端主线程状态。

  **不要挂 `READY`**：它已经 `@Deprecated`，实现上与 `COMPLETE` 同句触发，也就是收到
  `ClientboundFinishConfigurationPacket` 之后 —— 那时服务端的 3 s 早就超时了。四个事件里只有 `START`
  的 Javadoc 写着「Packets may be sent during this event」。
  `START` 由服务端发来的 `minecraft:brand` 点着（Fabric 把「只发 brand、不发 register」当成原版服务端），
  而本引擎 `ConnectionManager#doConfiguration` 的第一句就是发 brand、之后才进握手等待，顺序是对的。
- 步骤：起**玩法模式**服务端（默认 `synaxis.app.handshake-phase=configuration`）→ 客户端登录。
- 判据（三选一，都可观察）：① 进世界且服务端日志出现 `client_verified` / 无 kick → **发得出去，
  `ADR-M2-010` 转 Accepted**；② 客户端被 `synaxis:client-mod-required` 踢 → 发不出去；
  ③ 客户端侧抛「channel not registered / cannot send」一类异常 → 同样是发不出去。
- 落 ② 或 ③ 时先自查一步再下结论：`grep client_rejected logs/events-*.jsonl` 看 `reason`。
  `mod-missing` 才是「没发出来」；`version-mismatch` 说明包**发到了**，只是 `schema_version` 对不上，
  那不是 spike ④ 失败，是模组里那个常量取错了。
- 不通过时的两条退路（设计文档已写死，不要临场发明第三条）：服务端在 configuration 阶段主动补发
  一次 `minecraft:register`；或 `-Dsynaxis.app.handshake-phase=login` 走 login 形态并把它定为默认
  （切换步骤见 `spike4-configuration-send.md` 末节，已核过一遍：只值一个启动参数）。
- 回写：`ADR-M2-010` 的 Status、§28.2 那条风险行与那条「两条阶段路径必须收敛成一条」的技术债，
  以及 `spike4-configuration-send.md` 开头那句「未在真实客户端上跑过」。

## S2 相机形态出图：正交 vs 带俯角的透视（spike ② 的选择题）

- 要判的：给用户看两张图选一个。两者后续的 mixin 代价不同。
- 26.2 的落点（已在本机 jar 上核对）：`net.minecraft.client.Camera` 自己持有一个
  `net.minecraft.client.renderer.Projection`，而 `Projection` **本来就有**
  `setupOrtho(float,float,float,float,boolean)`，正交不需要自己拼投影矩阵；相机的位置与朝向在
  `Camera.update(DeltaTracker)` 里定，剔除用的视锥来自 `Camera.getCullFrustum()`。
  最小 spike 形态：mixin `Camera.update` 之后覆盖位置 / 朝向，并让投影走 `setupOrtho`。
- 步骤：玩法模式登录 → 开相机 → 固定俯角（先取 60°）→ 分别出正交与透视各一张同场景截图，
  两张都要同时拍到：地形、至少 20 个单位、远处地平线。
- 判据：无「正确 / 错误」，判的是用户选哪个；连带记下每种形态下阴影、雾效、地平线是否可接受。
- 回写：§28.2 的 Open Question「RTS 相机形态」，以及 `REQ-M2-018`。

## S3 实体剔除按谁算 + 「实体距离」50% 档（spike ②(a)(a′)）

- 要判的：MC 按**玩家实体**还是**渲染相机**做实体剔除；`archetype_view_range = 4.0` 在 97 格处
  是否真的够；玩家把「实体距离」调到 50%（全局倍率下限）后是否仍然够。
- **静态预读（是预期，不是结论）**：26.2 的
  `net.minecraft.client.renderer.extract.LevelExtractor#extractVisibleEntities` 取的原点是
  `Camera.position()`，再传给 `EntityRenderDispatcher.shouldRender(E, Frustum, double, double, double)`，
  也就是**按相机算**。复核命令：

  ```bash
  $JAVA_HOME/bin/javap -c -p -cp $J net.minecraft.client.renderer.extract.LevelExtractor \
    | awk '/private void extractVisibleEntities/,/extractEntity/' | grep -E "Camera.position|shouldRender"
  ```

  §28.2 那条风险明写「必须实跑确认，不能从文档推断」，所以这一段只用来预判结果、缩短排查，
  **不算 spike 通过**。
- 步骤：接 S2 的相机 → 把镜头推到离玩家实体 200 格外（单位仍在镜头中央）→ 数屏幕上的单位 →
  视频设置把「实体距离」从 100% 调到 **50%** → 不动镜头再数一次。
- 判据：两档下屏幕中央与四角的单位都**可见且不闪**；任一档丢单位即不通过，记下丢失发生的格数。
- 不通过时：抬 `archetype_view_range`（§15 的公式 `view_range × 64 × 倍率`）、或由客户端模组在进入
  RTS 模式时覆盖 `Options.entityDistanceScaling()`（该 `OptionInstance<Double>` 就是那个倍率），
  两条都要连带更新 §15 与 `ADR-M2-013`。
- 回写：§28.2 那条「按玩家实体还是按渲染相机剔除」的风险行、§15 的 `archetype_view_range`。

## S4 屏幕坐标 → 世界射线（spike ②(b)）

- 要判的：正交投影 + 26.x 渲染管线下，反投影该从哪儿取矩阵、`partialTick` 怎么对齐。
- 26.2 的落点：`Camera.getViewRotationProjectionMatrix(Matrix4f)` 给的是视图旋转 × 投影（**不含
  相机平移**，平移要用 `Camera.position()` 自己补）；`Camera.getCameraEntityPartialTicks(DeltaTracker)`
  是与该帧对齐的 partialTick；鼠标坐标用 `MouseHandler.getScaledXPos(Window)` / `getScaledYPos(Window)`
  （已按 GUI 缩放换算过），别直接用 `xpos()`。
- 步骤：临时在射线落点画一个调试方块高亮 → 把光标放在屏幕四角与正中各点一次，每次截图 →
  再把相机拉高 / 缩放到两个极端值各重复一遍。
- 判据：高亮方块**落在光标下面那一格**，四角与中心误差一致（正交投影下不应出现「越靠边越偏」）；
  移动中截图不应看到高亮滞后一帧——滞后即 `partialTick` 取错。
- 回写：§28.2 那条「屏幕坐标→世界射线」的风险行。

## S5 section 可见性与输入路径（spike ②(c)）

- 要判的：相机 eye 抬高 / 后退到空气里或已加载区块之外时，会不会大面积地形不渲染；以及框选与
  边缘推屏走透明 `Screen` 还是直接 mixin `MouseHandler`。
- 26.2 的落点：可见性图是 `net.minecraft.client.renderer.SectionOcclusionGraph`，由
  `LevelRenderer` 持有，判单个 section 用 `LevelRenderer.isSectionCompiledAndVisible(BlockPos)`；
  输入侧 `Screen.keyPressed` 的入参在 26.x 已是 `net.minecraft.client.input.KeyEvent`，
  `MouseHandler` 的按键入口是私有的 `onButton(long, MouseButtonInfo, int)`，公开的只有
  `isLeftPressed()` / `xpos()` / `grabMouse()` / `releaseMouse()` 这一组。
- 步骤（地形）：相机分别停在 ① 地面上方 20 格、② 200 格、③ 地图边界外 50 格，各截一张全屏图。
- 判据（地形）：三张图里地形都完整；出现整片区块不渲染就记下相机所在 section 与现象，那一档即不通过。
- 步骤（输入）：先试透明 `Screen`（`isPauseScreen()` 返回 false）拿光标，点一次地面、拖一次框；
  再试直接 mixin `MouseHandler`，同样两个动作。
- 判据（输入）：光标可见且不被游戏吃掉、点击与拖拽坐标连续、松开后视角不跳；两条路径各记一句
  「能做到 / 做不到 + 代价」，选一条写进结论。
- 不通过时（地形）：把相机 eye 钳在最高的已加载 section 内、只改投影不改 eye 高度——这是 RTS 相机
  mod 的常规解法，记进结论供 6b 实现照抄。
- 回写：§28.2 那两条（「相机 eye 位置与 section 可见性图」「框选与边缘推屏的输入路径」）。

## S6 1,024 个区块的墙钟与堆增量（spike ③）—— **本轮已跑完，验收人不用做**

这一条不需要客户端，服务端自己就能量，所以 2026-09-21 这一轮直接跑掉了，留在这里只为让四个 spike
的账是齐的。数字与复现方式如下（本机 macOS 15.3 / Apple Silicon / JDK 25 / G1）：

| 预设 | 区块数 | `load_ms`（两轮） | GC 后存活堆 |
| --- | --- | --- | --- |
| `rts-512`（默认） | 1,024 | 147 / 166 | 32,368 KiB |
| `rts-128` | 64 | 73 | 23,333 KiB |

- **墙钟**：147～166 ms，§15 的预算是 3 s。
- **堆增量**：两个预设差 960 个区块、差 9,035 KiB，折合 **≈ 9.4 KiB/区块**，整图 1,024 区块
  **≈ 9.2 MiB**；§15 的预算是 50 MB，§2「整图常驻 25～35 MB」也是按进程整体算的，实测整进程存活堆
  31.6 MiB 落在里面。
- **量法（重点）**：不要用事件台账里的 `heap_delta_mb`。它是 `totalMemory - freeMemory` 的前后差再
  `max(0, …)`，生成过程中只要发生一次 young GC 它就读 0 —— 实测两个预设都是 `heap_delta_mb=0`。
  真正的数是「起服到就绪后、没有任何连接时，强制 GC 三轮取最后一次的存活堆」，两个预设相减。

复现：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
# 1) 起服（rts-512 与 rts-128 各一次），保持 stdin 打开
./gradlew :server-app:run --no-daemon --console=plain \
  --init-script gradle/init/aliyun-mirror.init.gradle.kts -Dsynaxis.app.map=rts-512
# 2) 另开一个终端，就绪日志出现后：
PID=$($JAVA_HOME/bin/jcmd -l | grep synaxis.app.Main | awk '{print $1}')
for i in 1 2 3; do $JAVA_HOME/bin/jcmd $PID GC.run >/dev/null; sleep 2; \
  $JAVA_HOME/bin/jcmd $PID GC.heap_info | grep garbage-first; done
# 3) 回到服务端终端输入 stop
```

- 回写：`docs/DESIGN-M2.md` §15「地图生成」那一行的「待 §26 阶段 0 的 spike 实测确认」（本轮已填）。

## 做完之后

1. 每节的「回写」目标逐条改掉，`docs/DESIGN-M2.md` §1 加一行变更记录。
2. spike 结论**不进 `docs/TEST.md`**——§2 的不测表已写明 spike 不是验收。
3. S1 与 S2 之外临时加的 mixin / 注册代码，按结论要么正式落地（进 6a / 6b 的需求），要么删干净。
