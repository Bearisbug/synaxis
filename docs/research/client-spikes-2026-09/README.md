# 阶段 0 客户端 spike 执行清单（2026-09-21）

`docs/DESIGN-M2.md` §26 阶段 0 的 spike ② 要的是**肉眼看画面**的结论，spike ① 与 spike ④ 各剩一半
只有真启动一次客户端才判得掉。这台仓库所在的机器没有图形环境，所以这几件事由**验收人在自己机器上**
做；本文件是照着能执行的步骤、判据与回写目标，不是重述设计文档。

- 执行人：验收人（Bearisbug）。仓库里的 agent 不启动客户端。
- 产物：每个 spike 一段结论 + 截图 / 数字，截图放本目录，结论**回写设计文档对应那一行**（每节末尾点名）。
- 顺序有依赖：S0 → S1 之后才谈得上 S2～S5（没有握手就进不了玩法模式，屏幕上也就没有单位）。
- 26.2 是不混淆的，下面点名的类与方法都可以在本机现有的 jar 上复核，不必下载任何东西：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
J=~/.gradle/caches/fabric-loom/26.2/minecraft-client.jar   # Loom 第一次构建时已解包，勿重下
$JAVA_HOME/bin/javap -p -cp $J net.minecraft.client.Camera
```

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
- 不通过时：把 `:protocol` 的发布版本改成 SemVer（`-PGAME_ENGINE_VERSION=0.1.0` 一类）再试；仍不行
  则去掉 `include`、改成把 `:protocol` 的类 shade 进模组 jar，并回来改 `ADR-M2-015`。
- 回写：`docs/DESIGN-M2.md` §28.2 里 spike ① 那一行（目前写着「剩运行期一半」）。

## S1 configuration 阶段能不能发包（spike ④，`ADR-M2-010` 的全部赌注）

- 要判的：Fabric 客户端能否向一个**从不发 `minecraft:register`** 的服务端在 configuration 阶段
  发自定义负载。发不出去，则每个装了模组的玩家 3 s 后被 `synaxis:client-mod-required` 踢掉。
- 本轮交付的骨架**只注册了 play 两个方向**，做这个 spike 要先加一次性的三行（做完删掉，或按结论
  留下）：`PayloadTypeRegistry.serverboundConfiguration()` 注册 `synaxis:handshake` 的
  `C2S_HELLO`、`clientConfiguration()` 收 `S2C_HELLO`，并在
  `ClientConfigurationNetworking` 的 ready 事件里发一次
  `C2SHello(mod_version, ProtocolSchema.SCHEMA_VERSION)`（`SCHEMA_VERSION` 当前 = 1）。
  编解码照 `GamePayload` 的做法委托 `HandshakeMessages`，不要另写一份。
- 步骤：起**玩法模式**服务端（默认 `synaxis.app.handshake-phase=configuration`）→ 客户端登录。
- 判据（三选一，都可观察）：① 进世界且服务端日志出现 `client_verified` / 无 kick → **发得出去，
  `ADR-M2-010` 转 Accepted**；② 客户端被 `synaxis:client-mod-required` 踢 → 发不出去；
  ③ 客户端侧抛「channel not registered / cannot send」一类异常 → 同样是发不出去。
- 不通过时的两条退路（设计文档已写死，不要临场发明第三条）：服务端在 configuration 阶段主动补发
  一次 `minecraft:register`；或 `-Dsynaxis.app.handshake-phase=login` 走 login 形态并把它定为默认。
- 回写：`ADR-M2-010` 的 Status、§28.2 那条风险行与那条「两条阶段路径必须收敛成一条」的技术债。

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

## 做完之后

1. 每节的「回写」目标逐条改掉，`docs/DESIGN-M2.md` §1 加一行变更记录。
2. spike 结论**不进 `docs/TEST.md`**——§2 的不测表已写明 spike 不是验收。
3. S1 与 S2 之外临时加的 mixin / 注册代码，按结论要么正式落地（进 6a / 6b 的需求），要么删干净。
