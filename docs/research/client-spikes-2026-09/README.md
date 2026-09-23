# 阶段 0 客户端 spike 执行清单（2026-09-21）

`docs/DESIGN-M2.md` §26 阶段 0 的 spike ② 要的是**肉眼看画面**的结论，spike ① 与 spike ④ 各剩一半
只有真启动一次客户端才判得掉。本文件是照着能执行的步骤、判据与回写目标，不是重述设计文档。spike ③
不需要客户端，已在服务端跑完（见 S6），列在这里只是为了让四个 spike 的账是齐的。

- 执行方式：2026-09-23 起 S0～S5 都在**无头容器**里用真 26.2 客户端跑（Xvfb + Mesa llvmpipe，输入走
  `fabric-client-gametest-api` 的 `TestInput`，不碰宿主机的鼠标键盘），见下面「无头运行方式」。每节末尾的
  「结论」写明判据是否达成、证据在哪，以及软件渲染下哪些还要在真显卡上复核。
- 阶段 6a 里**不需要屏幕的那截已经交付完**，它与这份清单的分界线（哪几行代码测不到、跑完 spike 之后按什么顺序接）在 `stage6a-headless-vs-live.md`。
- 产物：每个 spike 一段结论 + 截图 / 数字，截图放本目录，结论**回写设计文档对应那一行**（每节末尾点名）。
  入库的截图统一缩到宽 960 并压成 256 色 PNG（原图 1920×1080）；各节写的像素位置与像素误差都是在原图上量的，在入库图上要除以 2。
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
| ① `:protocol` 被 Fabric JiJ / 跨构建消费 | **S0** | 运行期一半**已验**（2026-09-23）：Fabric Loader 把 `protocol-dev.jar` 当作 `synaxis` 的 JiJ 子模组加载，`NoClassDefFoundError` / `ModResolutionException` 0 次。仍剩：CI 工作流 `.github/workflows/client-mod.yml` 本身一次都没跑过（`TC-GAME-045` 第 8 步） |
| ② 相机、剔除、反投影、section 可见性与输入路径 | **S2 / S3 / S4 / S5** | 四节都在无头容器里跑过。S2 的截图已出，**等用户选正交还是透视**；S3 剔除原点已决（按渲染相机），`archetype_view_range = 4.0` 在 50% 档不够，改值待 6b 定；S4 通过；S5 地形 ① 通过、② ③ 不通过，正交 eye 钳位的退路可用，输入选 mixin `MouseHandler`。全部是 llvmpipe 画面，阴影、雾、观感要在真显卡上复核 |
| ③ 1,024 个区块的墙钟与堆增量 | **S6（本轮已跑完）** | 无。数字见该节，验收人不用做 |
| ④ configuration 阶段能不能发包 | **S1** | **运行期已验**（2026-09-23）：configuration 阶段发出的 `C2S_HELLO` 被服务端判 `client_verified`、无 kick，`ADR-M2-010` 转 Accepted。仍剩 §28.2 的技术债「握手两条阶段路径收敛成一条」（删 login 路径与 `synaxis.app.handshake-phase`），那是另一件事 |

## 无头运行方式

两条路都打通了，跑的是同一份 `client-fabric/src/gametest/**`：先跑回归断言 R1～R8（`RegressionClientGameTest`，`docs/DESIGN-PROD.md` `API-PROD-026`），再跑 spike（`SpikeClientGameTest`）。spike 用的 mixin、
相机覆盖、调试绘制只在 gametest 源码集，不进模组 jar（`./gradlew build` 的 `verifyModJar` 报 32 个条目，
`unzip -l build/libs/client-fabric-0.1.0.jar | grep -iE 'gametest|mixin'` 无输出）。环境变量 `SYNAXIS_SPIKES`
（逗号分隔，如 `s2,s5`；`all` 为 S2～S5 全跑）选跑哪几节，不设则整个 spike 不跑、只跑 R1～R8（S3 另要
`SYNAXIS_S3_DISTANCES`，不设就跳过，见 S3 节的复现命令）；设了则 S0、S1 必跑，spike 的行追加在 R1～R8 之后。`SYNAXIS_GAMETEST_NIGHT_VISION=true` 在进世界后给玩家加客户端夜视，
默认关。**判定看
`synaxis-gametest/report.txt` 与服务端的 `logs/events-*.jsonl`，不看退出码**：S1 断线时 CI 也是绿的
（run 35813814226），本地容器则每次都以 1 结束（见下方第 4 步）。

**本地 Docker**（镜像 `synaxis-mc-client:dev` 与 Gradle 卷 `synaxis-mc-gradle` 共用，不要另建）：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
# 0. 镜像只建一次：docker build -t synaxis-mc-client:dev client-fabric/gametest-env
# 1. :protocol 进 ~/.m2（run.sh 把这一段只读挂进容器）
./gradlew :protocol:publishToMavenLocal --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts
# 2. 玩法模式服务端，stdin 用 fifo 保持打开
mkdir -p /tmp/synaxis-gt && mkfifo /tmp/synaxis-gt/stdin.fifo
( sleep 86400 > /tmp/synaxis-gt/stdin.fifo & echo $! > /tmp/synaxis-gt/holder.pid )   # 占住 fifo 写端
./gradlew :server-app:run --no-daemon --console=plain --init-script gradle/init/aliyun-mirror.init.gradle.kts \
  -Dsynaxis.app.port=25566 < /tmp/synaxis-gt/stdin.fifo > /tmp/synaxis-gt/server.log 2>&1 &
# 3. server.log 出现 "server-app listening" 后跑客户端（冷缓存首轮下载约 1 GB，热缓存 Loom 配置 + 启动 23～47 s）
SYNAXIS_GAMETEST_SERVER=host.docker.internal:25566 OUT=/tmp/synaxis-gt/out client-fabric/gametest-env/run.sh
cat /tmp/synaxis-gt/out/synaxis-gametest/report.txt; ls /tmp/synaxis-gt/out/screenshots
# 4. 停机与回收
echo stop > /tmp/synaxis-gt/stdin.fifo; kill $(cat /tmp/synaxis-gt/holder.pid); rm -rf /tmp/synaxis-gt
```

- 第 3 步以 1 结束是已知问题：客户端打出 `Stopping!` 之后，LWJGL 自带的 linux-aarch64 `libopenal.so` 在
  退出析构里段错误（`libopenal+0x9c670`），Gradle 报 exit value 134；`ALSOFT_DRIVERS=null` 无效。x86_64 上
  是否复现未测。
- 两个容器不能同时挂 `synaxis-mc-gradle` 卷：Gradle 报 `Timeout waiting to lock journal cache`，等前一个跑完。
- 依赖下载走 `gametest-env/mirror.init.gradle.kts`（阿里云 central 直连优先）与
  `-Dorg.gradle.internal.repository.max.tentatives=6`：经宿主代理并发拉约 23 个 LWJGL linux native jar 时
  TLS 握手被断（`Remote host terminated the handshake`）。
- `runClientGameTest` 从 classpath 上去掉 dev 类与 `protocol-dev.jar`，把 `remapJar` 拷进
  `run/clientGameTest/mods/`，所以本地这条路加载的就是发布的模组 jar 与它嵌套的 `:protocol`。

**GitHub Actions**：`.github/workflows/client-gametest.yml`，`ubuntu-latest` + Temurin 25 + Xvfb/Mesa，触发方式与判定见 `docs/DESIGN-PROD.md` `API-PROD-026`（PR 按路径过滤只跑生产 jar 一次、push `main` 与每日 UTC 18:00 跑两次、每日另跑 spike；判定由 `client-fabric/gametest-env/judge.sh` 读报告、台账与 `server.log`）；`workflow_dispatch` 仍可手动触发（输入 `handshake_phase` 可选 `login` 跑对照）。同一个
job 里先起 server-app（`tail -f /dev/null` 撑住 stdin），再依次跑 `runClientGameTest` 与
`runProductionClientGameTest`（Loom 的生产启动器，只装 `remapJar` + Fabric API + gametest jar），截图、
`report.txt`、客户端日志、`server.log` 与事件台账作为 artifact 上传，保留 14 天。run 35815252028 与
35815701720 全绿，每次约 5 分 40 秒；这两次只跑了 S0～S2。本分支把 S3～S5 合进来、`SYNAXIS_SPIKES` 不设即
全跑之后，CI 上还没有跑过。

**合并后的复核**（2026-09-23，本地容器，`SYNAXIS_SPIKES` 不设，约 3 分钟）：四条 spike 分支合进 `client-gametest`
之后跑一遍，`report.txt` 有 S0 3 行、S1 1 行、S2 2 行、S4 94 行、S5 14 行（S3 因未设距离跳过），107 张截图。
`s4_measure.py` 算出的像素表与 S4 原跑逐项相同（35 个静止点 33 个 0.0 px、2 个 0.1 px；平移 12 帧全 0.0 px），
S5 各档的 `visibleSections` 与上表一致，说明四个分支对 `CameraMixin` 的改动合在一起没有互相干扰。

## 前置：把模组装进 26.2（有屏幕的机器上手动复核时用）

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

### 结论（2026-09-23，无头真客户端）

**判据 ① ② 达成，③ 由 S1 达成；「改成 SemVer」那条退路用不上。** 本节原定起核心模式服务端、手点 Mods 列表，
无头跑法换成读 Loader 的模组清单与 gametest 的报告，判的是同一件事。

- 生产启动（CI run 35815252028，`productionClientGameTest/logs/latest.log`）：`Loading 54 mods: … - synaxis 0.1.0`
  下面挂着 `\-- io_github_bearisbug_synaxis_protocol dev`；`report.txt` 有 `S0 mod synaxis 0.1.0` 与
  `S0 mod io_github_bearisbug_synaxis_protocol dev`。本地容器的 `runClientGameTest`（从 `mods/` 加载 `remapJar`）
  得到同样的清单。所有通过的轮次里 `NoClassDefFoundError` / `ModResolutionException` 出现 0 次。
- `dev` 不是 SemVer，Loader 只打一行 WARN：`Mod io_github_bearisbug_synaxis_protocol uses the version dev which
  isn't compatible with Loader's extended semantic version format`，照常加载。
- 截图 `screenshots/s0-title.png`（854×480）：完整标题画面，Logo、按钮文字、左下 `Minecraft 26.2 (Modded)`
  都在。截图前等 40 tick，否则拍到的是淡入中途、按钮没字的一帧。
- 顺带修掉一个构建缺陷：`fabric-loader` 原先挂在 `implementation` 上，Loom 在有 `mappings` 时只从 `mod*`
  配置读 loader 的 `fabric-installer.json`，运行 classpath 上因此没有 ASM 与 Mixin，客户端一启动就
  `ASM not detected on the classpath`。改成 `modImplementation` 后 `gametestRuntimeClasspath` 有
  `org.ow2.asm:asm:9.10.1` 与 `net.fabricmc:sponge-mixin:0.17.4+mixin.0.8.7`。
- 软件渲染的影响：无。本节判的是类加载，与显卡无关。

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

### 结论（2026-09-23，无头真客户端）

**判据 ① 达成：configuration 阶段发得出去，`ADR-M2-010` 转 Accepted。** 技术债「两条阶段路径收敛成一条」
原样保留，删 login 路径是另一件事。

- 前置：真客户端此前根本登录不了本引擎。REQ-005 删包时从按位置编号的注册表里删了条目，1 个 login 包
  （`minecraft:hello`）与 18 个 play 包之后的 id 全部前移一位，客户端报
  `Failed to decode packet clientbound/minecraft:login_finished`（SetCompression 被当成 login_finished 发出）。
  修复在 commit `cb44131ef1`：每个空位放一个不可查、不可发、迭代时跳过的 `ReservedPacket`，
  `PacketRegistryLookupTest` 钉住 login id 0/2/3/4/5 与 15 个 play id（含 `ShowDialogPacket` = 140）。
- 本地容器，默认 `handshake-phase=configuration`：事件台账 `logs/events-20260923.jsonl` 里连接 `42f4dde6-…`
  先记 `session_entered_configuration`（03:56:48.513，`protocol_version` 776），42 ms 后记 `client_verified`
  （`mod_version` 1，`schema_version` 1）；另一轮 `3c5220e4-…` 相隔 53 ms。台账没有 `client_rejected`，
  `grep kick` 0 行。客户端日志 `(synaxis) Handshake accepted: schema_version=1 tick_rate=20`，
  `report.txt` 为 `S1 in world at BlockPos{x=256, y=40, z=256}`。
- CI run 35815252028：dev 与生产两次连接（`4f210882`、`044830bb`）各自依次记下
  `session_entered_configuration` 与 `client_verified`，两份 `report.txt` 都是 `S1 in world`。
- login 对照（`-Dsynaxis.app.handshake-phase=login`）：连接 `113b14e6-…` 的 `client_verified`（03:59:12.620）
  早于 `session_entered_configuration`（.754），客户端 WARN
  `Not sending a second C2S_HELLO on this connection: state=VERIFIED`，同样进世界。
- 截图 `screenshots/s1-in-world.png`：石地、石柱排到地平线、两排单位与 HUD。拍它时**对局世界不带光照数据**，
  默认设置下地形是对着深蓝天空的黑色剪影（CI 那一版就是这样），gamma 1.0 无效；这张是 gametest 给玩家加了
  客户端侧夜视（`MobEffects.NIGHT_VISION`，无限时长）、俯仰设为 20° 之后拍的。
- **光照已由服务端算好（`docs/DESIGN-M2.md` v0.44）**：对局区块改用 `LightingChunk`，整图光照在
  `match_started` 之前算完。夜视改成 `SYNAXIS_GAMETEST_NIGHT_VISION=true` 才开、默认关；同一机位不开夜视重拍的
  `screenshots/light-s1-in-world.png`（2026-09-23，本地容器，`SYNAXIS_SPIKES=s1`）里地面是正常的灰色石纹，
  石柱受光面亮、背光面暗，单位与草边清楚，不再是黑剪影；天空仍是深蓝色，与光照数据无关。
- 软件渲染的影响：握手与显卡无关；「无光照数据时地形全黑」是数据问题，换显卡不会变。

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

### 结论（2026-09-23，无头真客户端）

**出图判据达成，选择题留给用户**：§28.2 的 Open Question 与 `REQ-M2-018` 不改，只在前者附截图路径与观感记录。

条件：锚点 = 玩家脚下 (256.5, 40, 256.5)，yaw 0，俯角 60°，1920×1080，HUD 关，渲染距离 10（server-app 上限；
gametest 框架默认把它设成 5，而服务端按客户端加入时报的距离发区块，加入后再调大不会补发，所以必须在连服前
设好），透视 fov 70。相机只在 gametest 源码集：`camera/SpikeCamera.java` 与 `mixin/CameraMixin.java`。

| 截图 | 形态 | 单位 | 地平线 |
| --- | --- | --- | --- |
| `s2-perspective-p60-d96.png` | 透视，退后 96 格 | 画面中央 8×8 网格，每个约 7～10 px | 顶边整条是渲染距离雾的边缘，外面是天空 |
| `s2-ortho-p60-d96-h224.png` | 正交，画幅高 224 格 | 同一网格，每个 2～3 px，行勉强分得开 | 一个圆形雾盘，四周是天空 |
| `s2-perspective-p60-d64.png` | 透视，退后 64 格 | 同上，更大 | 只有左上、右上两角露天空 |
| `s2-ortho-p60-d64-h96.png` | 正交，画幅高 96 格 | 同上 | 看不到天空，只有地面 |
| `s2-ortho-p30-d64-h96.png` | 正交，俯角 30° | — | 画面底部约 125 px 是天空（见下） |

`report.txt` 对每张图都报 `item_display 66 in frustum of 66`。两张 60° 的主图各自都拍到地形、8 行以上
（≥ 64 个）单位与远处边缘。

- **地平线要靠拉远换**：60° 俯角下唯一的「地平线」是渲染距离雾的边缘（160 格）。透视在 eye 离锚点约 80 格
  以上才露出它；正交没有灭点，只能把画幅放大到碰到雾，h = 224 时单位缩到 2～3 px。同样露出地平线，透视下的
  单位是正交的 3～4 倍大。
- **阴影**：两种形态都没有投影，石柱只在根部有环境光遮蔽，单位没有脚下阴影（`UnitEntity` 没设
  `shadow_radius`）。
- **雾**：渲染距离雾按 `fog.glsl` 的 `fog_cylindrical_distance` = max(水平距离, |垂直差|) 算，不是球面距离。
  透视下它是远端一条柔和的带，正交下是绕 eye 的一个圆盘；正交画面整体比透视更灰蓝，原因没查。
- **单位可读性**：`ITEM_DISPLAY` 的物品贴图是竖着的，60° 往下看被压成一条横线。这是 game-rts 的事（改成
  billboard 或把贴图放平），相机解决不了。
- **正交的两个坑（6b 照抄）**：`Projection#setupOrtho` 的盒子是 [0, w] × [0, h]，eye 在画面左下角，要自己平移
  半帧居中；拿这个矩阵直接建剔除视锥，`Frustum#offsetToFullyIncludeCameraCube` 永远退不出来（渲染线程卡在
  `Frustum.java:60` 593 s），所以剔除要另建一个居中的盒子。`s2-ortho-p30-d64-h96.png` 底部那条天空是剔除视锥
  的近面把 eye 平面之后的地面裁掉了（预测 64·tan30° = 37 格 → 96 格里的 11 格 ≈ 124 px，与图一致）；S5 把
  剔除近面推到 eye 之后，这条约束随之消失，那张截图是改动之前拍的。
- **要在真显卡上复核的**：阴影、雾的观感、正交画面偏灰这三条都只在 llvmpipe 上看过；光照也还是 S1 的客户端
  夜视，明暗不是玩家会看到的样子。

### 重拍（2026-09-23）

上一轮的图里单位只占画面中央一小块，没法据此比较两种相机。这一轮以单位方阵为主体重拍：三档距离各出正交、透视
一张，近档再补一对 45° 俯角。每一对同锚点、同朝向、同一次客户端运行里前后相隔 5 s 拍（单位全程静止），
两种投影按「锚点处的方阵在画面里一样宽」配平：透视 fov 70 时锚点处的画幅高是 2·d·tan35° = 1.40·d，
所以正交的 orthoHeight 取 1.40 × 透视距离，比的是投影而不是缩放。

条件：锚点 (256.5, 40, 256.5)（出生点，方阵中心），yaw 0，1920×1080 拍、入库前 `sips --resampleWidth 960`
缩到 960×540，HUD 关，渲染距离 10，**光照仍是客户端夜视**（服务端不发光照数据）。正交的 eye 与透视放在同一点
（锚点后方 d 格），所以两者的雾都从同一个 eye 算起。服务端默认 rts-512，`report.txt` 每张都报
`item_display 66 in frustum of 66`。方阵是 `Roster.deploy` 绕出生点一圈圈铺的 64 个己方单位，横向 8 列，
纵向 8 行满行加上下两行半行。复现：`SYNAXIS_SPIKES=s2`，`SYNAXIS_CAMERA_PITCHES` / `_DISTANCE` /
`_ORTHO_HEIGHT` 取下表的值，每档一次 `run.sh`（约 1 分 45 秒）。

下表像素宽度是在仓库里的 960 px 图上量的最左到最右一个单位（原始 1920 图乘 2），误差约 ±5 px；透视方阵
前后行不一样宽，列出锚点所在行、最远满行、最近满行三个值。

| 截图 | 模式 | 俯角 | 距离 d | orthoHeight | 方阵宽（px，960 宽图） |
| --- | --- | --- | --- | --- | --- |
| `s2r-near-ortho-p60.png` | 正交 | 60° | 7 | 10 | 425，每行相同 |
| `s2r-near-perspective-p60.png` | 透视 fov 70 | 60° | 7 | — | 锚点行 422，最远行 365，最近行 545 |
| `s2r-mid-ortho-p60.png` | 正交 | 60° | 14 | 19.6 | 216，每行相同 |
| `s2r-mid-perspective-p60.png` | 透视 fov 70 | 60° | 14 | — | 锚点行 210，最远行 197，最近行 245 |
| `s2r-far-ortho-p60.png` | 正交 | 60° | 130 | 182 | 约 25（原图约 50） |
| `s2r-far-perspective-p60.png` | 透视 fov 70 | 60° | 130 | — | 约 25（原图约 50） |
| `s2r-near-ortho-p45.png` | 正交 | 45° | 7 | 10 | 425，每行相同 |
| `s2r-near-perspective-p45.png` | 透视 fov 70 | 45° | 7 | — | 锚点行 420，最远行 333，最近行 630 |

近档 orthoHeight 取了整数 10，比配平值 9.8 大 2%，锚点行宽 425 对 422 与之相符。

看图记下的差异：

- **近大远小**：正交里方阵是一个规整的矩形，行距（60° 约 47 px，45° 约 38 px）和每个单位大小从上到下都一样。
  透视 60° 近档最近满行比最远满行宽 1.5 倍（545 / 365），行距从上往下由约 30 px 拉到约 80 px，最靠近 eye 的
  半行已经贴着画面底边被裁掉一截；45° 近档这个比例到 1.9 倍（630 / 333）。中档透视只剩 1.24 倍
  （245 / 197），肉眼看已接近正交。远档两者方阵都约 25 px，看不出差别。
- **单位本身**：`ITEM_DISPLAY` 是竖着的物品贴图，俯视下两种投影里都成了一个个扁半圆（单位没改，照实拍）。
  正交里 64 个一模一样；透视里离 eye 越近压得越扁，60° 近档最近一行只剩一条线，最远一行还看得出贴图轮廓。
  45° 比 60° 扁得少，单位更像「站着」。
- **远处单位能否辨认**：近档与中档两种投影都能数清每一个单位。远档在 1920 原图上方阵约 50 px，每个单位是
  3～4 px 的点，行能分开、单个认不出；缩到 960 后成了一小块灰色纹理，两种投影一样。
- **地形与石柱**：透视里石柱朝画面四周放射状倒开，看得到侧面，高低关系一眼可读，离中心越远倾斜越大
  （中档画面边缘的石柱几乎横躺）。正交里石柱只是一块块带模糊暗边的深灰方块，看得出位置、看不出高度，
  45° 下变成竖长方块，稍好分辨。
- **地平线 / 雾界**：近档与中档两种投影都看不到天空，满屏是地面。远档透视里渲染距离雾是一条向下弯的弧，
  画面中央的弧顶在 960 图 y ≈ 100（上面约 18% 是天空），弧两端在左右边缘落到 y ≈ 290～330；正交里是一个
  几乎撑满画面的雾盘，盘顶在 y ≈ 40，天空只剩上沿一条和四角。同样把方阵缩到约 25 px，透视露出的天空多、
  能看到更远处（画面上沿对应的地面比正交远），正交露出的地面在画面里分布均匀、雾盘以内每处比例一样。
- **正交整体偏灰**：与上一轮一致，正交各档画面都比透视灰蓝，远档最明显，原因仍没查。

近档拍摄暴露了剔除视锥的第三个坑：orthoHeight 10 时画幅比 eye 所在 8 格小格的投影（最宽 8·√3 ≈ 13.9 格）
还窄，`Frustum#offsetToFullyIncludeCameraCube` 再次死循环。`CameraMixin` 现在让剔除盒子至少 32 × 32 格
（只放宽剔除，画面仍按 orthoHeight 画），所以本节正交图的 `in frustum` 计数是按放宽后的盒子算的。

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

### S3 实跑结果（2026-09-23，容器 Xvfb + llvmpipe，客户端 render distance 10）

**布置**：玩法模式服务端、默认花名册，客户端收到 66 个单位（`ITEM_DISPLAY`，出生点那一簇；220 格外的
前哨不在跟踪范围内）。玩家实体一直站在出生点，66 个单位离它 1.0～5.7 格，远在服务端 128 格跟踪半径内；
只有 spike 相机动：透视、俯角 60°、fov 70，沿视线从单位簇退后 D 格。每个机位稳定 60 tick 后连续采样
40 tick，数的是 `LevelExtractor#extractVisibleEntities` 真正交给渲染的列表（gametest 源码集的
`LevelExtractorMixin` 挂在 `extractEntity` 上），不是视锥估算。

| 机位 | 相机到单位 | 100% 渲染数 | 50% 渲染数 |
| --- | --- | --- | --- |
| D = 97 / 130 / 150 | 95～152 | 66 | 66 |
| D = 170 | 168～172 | 66 | **0** |
| D = 200 / 250 / 300 | 198～302 | 66（画面全是雾色，见下） | 0 |
| D = 340 | 338～342 | **0** | 0 |
| 眼高 84（= D 97），单位在正前方水平 120 格 | 143～150 | 66 | 66 |
| 同上，水平 135 格 | 156～163 | 66 | **45**（渲染的 ≤ 159.9，剔除的 ≥ 160.8） |
| 同上，水平 140 / 150 / 158 格 | 160～183 | 66 | 5 / 0 / 0 |
| D = 97，单位放在画面两个下角（80% 处） | 109～114 | 66 | 66 |
| D = 97，单位放在画面两个上角（80% 处） | 213～223 | 66（全在雾里） | 0 |

每个机位 40 tick 内渲染数的最小值都等于最大值，**没有闪**。

1. **剔除按渲染相机算，不按玩家实体。** 玩家到单位全程 ≤ 5.7 格，渲染数却只随相机距离变：50% 档在相机
   150→170 格之间从 66 掉到 0，100% 档在 300→340 之间掉到 0；「水平 135 格」那张把 66 个单位切成两半，
   切口落在相机距离 160.0 上。与静态预读一致，§28.2 那条风险可以关。
2. **半径公式比 §15 多一个因子**：`r = view_range × 64 × clamp(renderDistance / 8, 1, 2.5) × entityDistanceScaling`。
   第三项是 `extractVisibleEntities` 开头 `Entity.setViewScale(...)` 的实参，比较在
   `Display#shouldRenderAtSqrDistance`。运行时读 `Entity.getViewScale()` 得 1.250 / 0.625，
   `view_range = 4.0` 在 render distance 10 下是 **320 / 160 格**；render distance ≤ 8 时退回 256 / 128（后者只读字节码，未实跑）。
3. **另一道同样按相机算的界是 render distance 雾**：`FogRenderer#setupFog` 把 `renderDistanceEnd` 设为
   render distance × 16 = 160、起点 144。D ≥ 200（眼高 ≥ 173）时整幅画面只剩雾色，100% 档虽然交了 66 个
   单位去渲染，截图与 50% 档（0 个）**逐字节相同**（md5 一致，`s3-s100-d200.png`）；D = 170（眼高 147、
   单位离眼 170）时地面与单位都清楚。所以雾量的不是球面距离，从这两张推断是 max(水平距离, 垂直高差)。
   100% 档在 render distance 10 下半径 320，雾先到，单位不会比地形先消失。
4. **`archetype_view_range = 4.0` 在 50% 档不够**，丢的是画面上部那一条：实体半径 160 是球面距离，雾界 160
   量的是水平距离，眼高 84（D = 97）时水平 136～160 格处的地形仍在画面里、也在服务端跟踪范围内（离玩家约
   88～112 格），单位却全被剔掉。`s3-s100-d97-h140-crop.png` 与 `s3-s50-d97-h140-crop.png` 是同一机位：
   地形和石柱一模一样，50% 档只剩离眼最近的 5 个单位。画面中央（D = 97 时单位离眼 95～99 格）两档都在，
   离 160 还有 61 格余量。
   要让 50% 档覆盖到雾界，需要 `view_range ≥ √((16·rd)² + h²) / (64 × clamp(rd/8, 1, 2.5) × 0.5)`，
   h 是眼高：rd 10、h 84 时是 **4.52**。默认建议把 `archetype_view_range` 抬到 **5.0**：rd 10 下能覆盖到眼高 120
   （俯角 60° 时 D ≤ 138），rd 8 下到眼高 96（D ≤ 110）；相机要推得比这更高时，改由客户端模组在 RTS 模式里把
   `Options.entityDistanceScaling()` 钳到 ≥ 1.0。两条都要连带改 §15 与 `ADR-M2-013`。
5. 顺带看到的：D = 97 上角那两张第一轮有 36 个单位没过 `isSectionCompiledAndVisible`（其余 30 个在雾里），
   第二轮同机位 0 个 —— section 还没编译完的时序差异，不是稳态剔除，而且那一块本来就在雾里。

截图（1920×1080 原图；`-crop` 是中央或上缘 400×225 放大 4 倍）：`s3-s100-d170.png` / `s3-s50-d170.png`（同机位，
50% 档单位簇消失，`-crop` 两张看得清）、`s3-s100-d97-h140-crop.png` / `s3-s50-d97-h140-crop.png`（见第 4 条）、
`s3-s50-d97.png`（50% 档、D = 97，66 个都在）、`s3-s100-d200.png`（100% 档交了 66 个仍只见雾）。

复现（服务端照「无头运行方式」起，容器里跑，约 4 分钟；`report.txt` 每机位一行 `S3 ...`，写明渲染数、未渲染的单位各卡在
距离 / 视锥 / section 哪一关、两侧的相机距离区间）：

```bash
SYNAXIS_SPIKES=s3 SYNAXIS_S3_DISTANCES=97,130,150,170,200,250,300,340 SYNAXIS_S3_HORIZONTALS=120,135,140,150,158 \
  SYNAXIS_GAMETEST_SERVER=host.docker.internal:25566 OUT=/tmp/synaxis-s3 client-fabric/gametest-env/run.sh
grep '^S3' /tmp/synaxis-s3/synaxis-gametest/report.txt
```

### 结论（2026-09-23，无头真客户端）

- **剔除按渲染相机算：已证实**，§28.2 那条风险的这一半关掉。证据：同一机位的 `s3-s100-d97-h140-crop.png`
  与 `s3-s50-d97-h140-crop.png`，玩家全程离单位 ≤ 5.7 格，渲染数只随相机距离变；字节码里
  `LevelExtractor#extractVisibleEntities` 先取 `Camera.position()`。
- **半径公式：已证实**，比 §15 多一项：`r = view_range × 64 × clamp(rd/8, 1, 2.5) × entityDistanceScaling`。
  rd ≤ 8 那一档（256 / 128 格）只读了字节码，没实跑。
- **`archetype_view_range = 4.0` 在 50% 档不够：已证实**（判据「任一档丢单位即不通过」落在不通过）。
  眼高 84 时离眼水平 136～160 格处的地形还在画面里，单位全被剔掉。建议值 5.0（rd 10 覆盖到眼高 120，
  rd 8 到眼高 96），更高的相机改由客户端把 `entityDistanceScaling` 钳到 ≥ 1.0；改值与改公式要连带
  §15、`ADR-M2-013`，留给 6b 定，本轮只回写 §28.2 的风险行。
- **四角「可见且不闪」：未证实。** 「40 tick 内最小值等于最大值」与「上角两档截图 md5 相同」出自当轮的
  `report.txt` 与上角截图，这两样都没有留下来；而按判据，50% 档上角渲染 0 个本身就是丢单位，「那里已经在雾里」
  的说法缺一张上角截图佐证。补法：按上面的复现命令重跑一次，把上角两张截图与 `report.txt` 存进本目录。
- **要在真显卡上复核的**：渲染数来自 `LevelExtractor` 交给渲染的列表，是 CPU 侧逻辑，与显卡无关；雾的截断
  位置（160 格、从 144 格起淡出）是着色器的事，llvmpipe 与真显卡跑同一段 GLSL，但画面观感没在真显卡上看过。

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
- **2026-09-23 已在无头容器里跑完，三条判据通过**：35 个静止点的像素误差 33 个 0.0 px、2 个 0.1 px，
  平移中按本帧相机求的射线 12 帧全是 0.0 px（忽略 partialTick 则偏 6～25 px）。前提是近平面取 NDC
  z = 1（26.2 是反向深度），且正交下反投影与渲染用同一个居中的矩阵。数字、截图与复现命令在
  `s4-screen-ray.md`。

### 结论（2026-09-23，无头真客户端）

**三条判据都达成**，§28.2 那条风险行已转已决（`docs/DESIGN-M2.md`）。证据：`screenshots/s4-sheet-still.png`
（7 种相机 × 5 个点，洋红标记都在光标上）、`s4-sheet-pan.png`（本帧相机的标记都在光标上，按 tick 时刻相机
求的红色标记全部偏开）、`s4-pan-ortho-p60-d64-h48-c-t25-tick.png`（红标记在中心左侧 17 px）。

- 正交 d240 那一行整格是天空（`s4-ortho-p60-d240-h96-c-face.png`），那 5 个点只能证明射线与渲染一致，
  看不出高亮是否落在光标下那一格。画面是天空的原因有两条叠加：eye 约在 y = 248，section 15，与地面
  section 2 差 13 > 10，撞上 S5 的可见性图 y 限制；垂直高差 208 也超过了 160 格的圆柱雾。
- 只拿方块测过拾取，没测单位（display 实体的包围盒）；只测了 yaw 0、俯角 60°。
- **要在真机上复核的**：光标来自 `TestInput`，不是 GLFW，HiDPI / Retina 的缩放没测；`VIEW_SHRINK` 把线条往中心
  拉 3.6～4.7 px 是着色器行为，与显卡无关，但像素级偏差只在 llvmpipe 上量过。

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

### S5 实跑结果（2026-09-23，Docker 容器 Xvfb + llvmpipe，分支 `spike-s5-terrain-input`）

复现：玩法模式服务端起在 25573，然后
`SYNAXIS_SPIKES=s5 CONTAINER=synaxis-mc-client-s5 SYNAXIS_GAMETEST_SERVER=host.docker.internal:25573 OUT=/tmp/synaxis-s5/run client-fabric/gametest-env/run.sh`，
报告在 `$OUT/synaxis-gametest/report.txt` 的 `S5 …` 行。代码只在 gametest 源码集：
`s5/TerrainSpike.java`、`s5/InputSpike.java`、`s5/SelectionScreen.java`、`s5/MouseCapture.java`、`mixin/MouseHandlerMixin.java`。

**地形：① 通过，② 不通过，③ 部分不通过；退路（正交 + eye 钳位）把 ② ③ 都救回来，透视救不回来。**

条件：`rts-512`、世界高 64、出生点地面 y=40（section y=2）、渲染距离 10（server-app 上限）、俯角 60°、
正交画幅高 96 格、截图 1920×1080。第 ③ 档先把玩家挪到 x=472.5（服务端随之下发地图东缘的区块，
对应 6b 的「镜头上报 → 服务端传送玩家」），eye 放在东缘外 50 格（x=562.5）朝西看 x=480.5。
「地表 section」= 已加载区块里至少含一列最高方块的 section，从上方看只可能被视锥挡住，不会被正常遮挡剔除；
「不在图里」= 不在 `LevelRenderer#visibleSections`；「未编译」= 在图里但 `isSectionCompiledAndVisible` 为 false。

| 档 | 截图 | eye（section y） | visibleSections | 视锥内地表 section | 不在图里 | 未编译 | 画面 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| ① 地面上 20 格，透视 | `s5-eye20-perspective-p60-d23` | y=60（3） | 70 | 40 | 0 | 0 | 完整 |
| ② 地面上 200 格，透视 | `s5-eye200-perspective-p60-d231` | y=240（14） | **0** | 483 | **483** | 0 | 全是天空 |
| ② 地面上 200 格，正交 | `s5-eye200-ortho-p60-d231-h96` | y=240（14） | **0** | 96 | **96** | 0 | 全是天空 |
| ② 退路：正交 eye 钳位 | `s5-eye200-clamped-ortho-p60-d26-h96` | y=62.9（3） | 96 | 96 | 0 | 0 | 完整，画幅与 ② 正交相同 |
| ③ 边界外 50 格，透视 | `s5-outside50-perspective-p60-d164` | y=182，x=562.5（11） | 119 | 322 | **203**（区块 x 18～31） | 21（区块 x=31） | 只剩以 eye 为心约 160 格的圆盘，外圈是雾 |
| ③ 边界外 50 格，正交 | `s5-outside50-ortho-p60-d164-h96` | 同上 | 119 | 72 | 0 | 12（区块 x=31） | 地图内完整，上方两角有雾；x=496～511 一条空 |
| ③ 退路：正交 eye 钳位 | `s5-outside50-clamped-ortho-p60-d27-h96` | y=62.9，x=493.75（3） | 72 | 72 | 0 | 12（区块 x=31） | 雾消失；x=496～511 仍空 |

三处原因都在 26.2 字节码里核实过：

- ② 的 0：`SectionOcclusionGraph#getRelativeFrom` 在「eye 所在 section 的 y 与邻居 section 的 y 相差超过
  `ViewArea.getViewDistance()`（= 渲染距离 10）」时返回 null，图从顶层种子出发一步也扩不出去。
  eye 在 section 14（报告里 eye y = 239.99999…）、地形在 section 0～2，差 12。地面在 y=40 时 eye 必须低于 y=208 才进得了图。
- ③ 透视的圆盘：`LevelRenderer#repositionCamera` 把 `ViewArea` 的中心挪到 eye 所在 section，
  `isInViewDistance` 按 eye 所在区块做 `ChunkTrackingView.isInViewDistance(…, 10)`；离 eye 水平超过
  10 个区块的已加载区块全部不进图，雾也从 eye 算起。
- ③ 那条 x=496～511 的空带与相机无关：`LevelExtractor` 在排编译前调
  `SectionUpdateTracker.hasAllNeighbors(ClientLevel, long)`，四邻区块没全加载的 section 永不出网格。
  地图是 [0, 512)，区块 32 永远不存在，所以**地图最外一圈区块（每边 16 格）在客户端永远不渲染**。
  这条要 6b 处理：服务端在地图外补发一圈空气区块，或接受每边 16 格的死区（本 spike 没实现，也没测另外三条边，按同一段代码推断相同）。

退路的做法（照抄给 6b）：eye 沿视线往锚点收，收到 y < `level.getMaxY()` 且脚下区块已加载为止；
正交近平面同时推到 eye 后面，`near = min(0.05, distance − orthoHeight / 2 / tan(pitch) − 32)`，
裁剪视锥的近面取 `min(−16, near)`（`CameraMixin`）。正交画面与 eye 在视线上的位置无关，所以画幅不变，
截图 ② 正交与 ② 退路的差别只是前者全空、后者完整。代价是深度缓冲的范围多出 eye 后面那一截（这两张约 33～34 格），截图里没看到深度冲突。
这条改动顺带去掉了 S2 写的「正交 distance ≥ orthoHeight/2/tan(pitch)，否则画面下沿空」的约束。

透视没有这条退路：透视画幅随 eye 距离缩放，eye 收到 d≈26.6 还要保持 ② 的画幅，fov 要从 70° 放到约 161°
（`2·atan(tan35°·231/26.6)`），选项上限是 110°。透视能用的最远距离同时受两条限制：eye 所在 section 与地表
section 的 y 差 ≤ 10（地面 y=40 时 eye < y=208），以及雾在距 eye 约 160 格处收满。

**输入：两条路径拿到的坐标完全一致，选 mixin `MouseHandler`。**

动作都走 `TestInput`（它直接调 `MouseHandler` 的 `onMove` / `onButton`）：窗口 854×480、GUI 缩放 2；
在窗口 (427, 360) 点一次，然后从 (200, 150) 按住左键，分 10 帧每帧移 (+20, +10) 像素，再松开。

| 项 | 对照：原版，无 Screen、无 mixin | 透明 `Screen`（`isPauseScreen` = false、不画背景） | mixin `MouseHandler` |
| --- | --- | --- | --- |
| `isMouseGrabbed()` | true | false | false |
| 光标移动 +100 px | 玩家 yaw +15.00°（被游戏吃掉） | — | — |
| 点击 | — | press/release 各 1 次，GUI (213.5, 180.0) | 同左 |
| 拖拽 | — | 10 次 drag，(110, 80) → (200, 125)，每次 (+10, +5)，无跳点 | 同左 |
| 按住期间攻击键 | — | 未按下 | 未按下 |
| 按下到松开后玩家朝向变化 | — | yaw +0.00，pitch +0.00 | yaw +0.00，pitch +0.00 |
| 按聊天键 T | — | 不开聊天，屏幕仍是 `SelectionScreen` | 打开 `ChatScreen`；关掉后 `isMouseGrabbed()` 仍为 false |
| 关掉这条路径 | — | `setScreen(null)` 后原版立刻 `grabMouse`：grabbed 变 true、光标被拉回窗口中心 (427, 240) | 撤掉开关后才恢复原版 |

截图 `s5-input-screen-drag` 是透明 Screen 画框的那一帧：绿色框从窗口 (200, 150) 到 (400, 250)，背后世界照常渲染、没有模糊或压暗。

- 透明 `Screen` 能做到，代价：屏幕开着时原版按键绑定不生效（实测聊天键 T 打不开聊天），
  聊天、背包之类都得在 `keyPressed` 里自己转发；而且这个 Screen 必须整局常开，任何一次 `setScreen(null)`
  （关聊天、关暂停菜单）都会被原版 `grabMouse` 抢回光标并把光标拉到窗口中心，得逐一拦下。
- mixin `MouseHandler` 能做到，代价：三处注入——`grabMouse` 头部取消、私有 `onButton(long, MouseButtonInfo, int)`
  头部记录并取消、`onMove` 尾部记录拖拽；两处是私有方法名，换 MC 版本要重核（26.2 已钉死，`ADR-M2-016` 不跟 26.3）。
  没有 GUI 层，选框要自己挂 HUD 层画（本 spike 没画这一条路径的框）；键盘事件不经过 `MouseHandler`，原版移动键照常生效（按源码推断，未测），要另外处理。
- 选 mixin：坐标与透明 Screen 逐点相同，而它不需要常开一个屏幕去和原版的每一次 `setScreen(null)` 抢光标。

测试环境的一条限制：fabric-client-gametest 把 GLFW 的抓鼠标整个关掉了（`InputConstantsMixin` 取消
`grabOrReleaseMouse`），所以「光标可见」只能按 `MouseHandler.isMouseGrabbed()` 判，真实系统光标的表现没有观察到。

### 结论（2026-09-23，无头真客户端）

- **地形 ① 达成，② ③ 不达成；正交 eye 钳位的退路把 ② ③ 救回来，透视没有退路。** 证据是上表的截图（都打开
  核对过）与 `visibleSections` 计数；② 的两张纯天空截图 md5 与 S3 的 `s3-s100-d200.png` 相同。② 的纯天空还有
  第二个原因：垂直高差 200 已超过 160 格的圆柱雾，即使可见性图不丢，这一档也会被雾盖满；③ 透视那个圆盘的边
  同样和雾界（离 eye 160 格）几乎重合，画面上看到的主要是雾。
- **地图最外一圈区块永不出网格**：只在东缘测到（区块 x=31，截图空带起点与几何推算的 x≈496 吻合）；另外三边按
  同一段 `hasAllNeighbors` 代码推断相同，未测。6b 要在「补发一圈空区块」与「每边 16 格死区」之间选。
- **输入选 mixin `MouseHandler`**：两条路径坐标逐点相同，透明 Screen 的代价（原版按键失效、每次
  `setScreen(null)` 都被 `grabMouse` 抢回光标）更大。证据中能复看的只有 `s5-input-screen-drag.png`
  （框 (200,150)～(400,250) = GUI (100,75)～(200,125)，与拖拽坐标一致，背后无模糊）；表里其余数字来自当轮
  `report.txt`，未留存，`SYNAXIS_SPIKES=s5` 可重跑。
- **要在真机上复核的**：系统光标是否可见（框架关掉了 GLFW 抓鼠标）；mixin 路径下原版移动键是否照常生效
  （按源码推断，未测）；section 可见性与 `hasAllNeighbors` 是 CPU 侧逻辑，换显卡不变。

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
- **量法**：本节的数是「起服到就绪后、没有任何连接时，强制 GC 三轮取最后一次的存活堆」，两个预设相减
  ——量的是整进程，含插件启用与实例注册之后才留下的东西。事件台账里的 `heap_delta_kib` 量的是另一段
  （生成前后各强制两次回收、只圈地形与导航网格与区块），2026-09-22 实测 `rts-512` 6,600 KiB、
  `rts-128` 1,125 KiB，折合 5.7 KiB/区块。两个数不该相等，能在自动化里变红的是后者。

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
