# 阶段 6a 交接：哪些已被无头测试钉住，哪些必须等真机（2026-09-22，2026-09-23 按无头实跑更新）

`docs/DESIGN-M2.md` §26 把阶段 6a 分成两截验收。**不需要屏幕的那截已经交付完**（设计文档 v0.31 /
v0.33 / v0.36），判据是 `docs/TEST.md` 的 `TC-GAME-017` 第 4 步、`TC-GAME-045` 与 `TC-GAME-066`；
需要屏幕的那截里，README 的 S0～S5 已于 2026-09-23 在无头容器（Xvfb + Mesa llvmpipe）里用真 26.2 客户端跑完，
第二节据此把清单分成「已做」「无头能做、属 6b」「必须等真机」三组。本文件是这两截的分界线，逐行写清楚
**哪几行代码测不到、为什么、由谁来判**，以及 spike 结论出来之后按什么顺序往下接。

- 客户端测试现状：`cd client-fabric && ./gradlew test --no-daemon` 共 92 条，0 失败
  （`ClientHandshakeTest` 13、`RtsClientTest` 23、`UnitPickerTest` 15、`UnitBindingsTest` 13、
  `FrozenVectorTest` 20、`GamePayloadTest` 5、`OrderFeedbackTest` 3）。
- 这 92 条**一条也没有启动 Minecraft**：跑起来的只有 `net.minecraft` 里的纯数据类（`Vec3`、
  `AABB`）与 `:protocol` 的编解码。

## 一、接缝：碰 Minecraft 的只有一个接口、两个方法

`ClientWorldView` 是整条选中与下令链路上唯一碰 Minecraft 运行期状态的地方，生产实现
`MinecraftWorldView` 的两个方法体各五行。判据在 `TC-GAME-066` 第 5 步：
`grep -rn "Minecraft.getInstance()" client-fabric/src/main/java` 只应命中这一个文件。

| 接缝方法 | 生产实现有几行 | 为什么测不到 | 谁来判 |
| --- | --- | --- | --- |
| `renderPosition(int entityId)` | 5 行（取 `Minecraft.getInstance()`、取 `client.level`、`level.getEntity(entityId)`、按 `getGameTimeDeltaPartialTick(true)` 求插值位置） | `Minecraft.getInstance()` 只在真客户端里有值；`partialTick` 只有在渲染循环里才是当前帧的值。单测里连一个 `ClientLevel` 都造不出来 | `TC-GAME-047` 第 6、8 步（单位移动途中还点得中） |
| `groundHit(origin, direction, maxDistance)` | 5 行（取 `level`、按方向与距离算终点、`level.clip(ClipContext…)`、只认 `HitResult.Type.BLOCK`） | 同上，外加它要一个真加载了区块的世界 | `TC-GAME-047` 第 7、9 步（点地面走过去、点边界外变红） |

接缝**上方**的东西全部有测试，这是本轮的判据本身：拾取的数学（正中、擦边内外、贴着底面的平行射线、
身后不命中、多候选取最近、渲染位置缺失时不可拾取、未知原型不可拾取）、绑定表的生命周期（绑、解绑、
快照只覆盖已绑的、未匹配条目计数、每连接清空）、消息编排（一次点击发什么包、`order_seq` 怎么走、
七个结果码各自的去向、两条 10 次/秒的节流）。

## 二、必须等真机的清单（2026-09-23 按无头实跑更新）

v0.33 那份清单原有五项。无头容器能起真客户端之后，「需要屏幕」不再等于「需要真机」：画面能截图、输入能经
`TestInput` 注入，只有真显卡的观感与真操作系统的光标还得人来看。跑法见 README 的「无头运行方式」。

**已做**

1. **`ADR-M2-010` 的运行期一半**——configuration 阶段的负载发得出去，`ADR-M2-010` 转 Accepted。证据：事件台账
   `session_entered_configuration` 之后 42 ms / 53 ms 记 `client_verified`，无 `client_rejected`、无 kick；CI run
   35815252028 的 dev 与生产两次连接同样通过；login 对照也进世界（README S1 节的「结论」）。
2. **输入层的前置结论**：S4 定了「屏幕坐标 → 世界射线」的取法（35 个静止点 33 个 0.0 px、2 个 0.1 px，平移 12 帧
   全 0.0 px，`s4-screen-ray.md`），S5 定了输入走 mixin `MouseHandler`（README S5 节）。输入层本身仍未实现。

**无头能做，属阶段 6b，还没做**

3. **`MinecraftWorldView` 的两个方法体**（上表）。S4 的 gametest 用自己的 `level.clip` 验过射线，但没有调这两个
   方法；接上输入层后，`TC-GAME-047` 第 6～9 步可以写成 client gametest 在容器里跑。
4. **输入层**：哪次点击、用哪条射线调 `RtsClient#selectAt` / `#orderAt`。
5. **绘制**：选中框（§5 与 §19 写死是客户端本地画，服务端不下发任何选中相关的实体元数据）与落点标记
   （`OrderFeedback` 已经算好画什么颜色、画在哪、带不带字，6b 只需要读它）。正交下选中描边不能走原版线条
   着色器（S4：`VIEW_SHRINK` 把线往中心拉 3.6～4.7 px）。
6. **端到端四环通**（协议 / 渲染 / 拾取 / 下令），`TC-GAME-047` 全篇。截图判据能在容器里跑，录屏不能。

**必须等真机**

7. **真显卡上的观感**：阴影、雾、正交画面偏灰（S2），以及对局世界补上光照之后的明暗。无头画面全是 llvmpipe，
   地形能看清还靠 gametest 给玩家加的客户端夜视。
8. **真实系统光标**：fabric-client-gametest 取消了 GLFW 的抓鼠标，S5 只能按 `MouseHandler.isMouseGrabbed()` 判光标
   是否释放；HiDPI / Retina 下的光标缩放（S4）也没测。
9. **客户端帧率**：§28.2「同屏单位数上限」的客户端那一半，llvmpipe 的帧率不代表玩家机器。

## 三、跑完 spike 之后按什么顺序接

1. **先 S0 + S1**（2026-09-23 已完成，`ADR-M2-010` 已转 Accepted）。以后 S1 若落
   `synaxis:client-mod-required`，先 `grep client_rejected logs/events-*.jsonl` 看 `reason`：`mod-missing` 才是
   「发不出去」，`version-mismatch` 说明包发到了。
2. **S2 / S3 出画面结论**（2026-09-23 已出图，剔除一行已回写 §28.2）。相机用正交还是带俯角的透视**等用户选**，
   截图在 README S2 节；S4 的反投影两种形态都验过，这个选择不再卡住 S4。
3. **S4 给出「屏幕坐标 → 世界射线」的写法**，这是 6b 第一件要落地的事：把那条射线接到
   `RtsClient#selectAt` / `#orderAt` 上。接上之后不需要再改 `RtsClient`——它收的就是一条射线。
4. **S5 定输入路径**，然后 6b 按顺序做：相机 mixin → 输入层 → 选中框与落点标记的绘制
   （读 `RtsClient#selected()` 与 `#feedback()`）→ 跑 `TC-GAME-047`。
5. **接输入层的同时还掉一笔技术债**：§28.2 那条「`not-owner` 没有对上 `order_seq`」。现在客户端
   收到 `not-owner` 就清空本地选中，而答复里只有 `order_seq`、没有 `unit_id`；只要玩家能在一次往返
   之内改选，晚到的 `not-owner` 就会清掉他刚选中的那个单位。修法是记住每个 `order_seq` 是为哪个单位
   发的——在输入层能连点之前，这条债不会被触发。
6. **6b 加了多选之后，下令的节流要改口径**：现在客户端按包计（M2 单选，一次点击就是一个包），
   而服务端的令牌桶按不同 `order_group` 的个数计（`API-M2-005`）。一次点击给 8 个单位下令 = 8 个包
   共用一个 `order_group`，按包计会在第二次点击就把自己掐掉。
