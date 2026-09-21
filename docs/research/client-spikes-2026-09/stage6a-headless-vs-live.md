# 阶段 6a 交接：哪些已被无头测试钉住，哪些必须等真机（2026-09-22）

`docs/DESIGN-M2.md` §26 把阶段 6a 分成两截验收。**不需要屏幕的那截已经交付完**（设计文档 v0.31 /
v0.33 / v0.36），判据是 `docs/TEST.md` 的 `TC-GAME-017` 第 4 步、`TC-GAME-045` 与 `TC-GAME-066`；
需要屏幕的那截一步也没跑。本文件是这两截的分界线，逐行写清楚**哪几行代码测不到、为什么、由谁来判**，
以及验收人跑完 README 里的 S0～S5 之后按什么顺序往下接。

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

## 二、必须等真机的清单（与设计文档 v0.33 那份相同，一项没减）

1. **`ADR-M2-010` 的运行期一半**——configuration 阶段的负载能不能真发出去。源码结论是「能」
   （`spike4-configuration-send.md`），运行期没跑过，所以 ADR 仍是 Proposed。判据是
   `TC-GAME-046` 第 3 步（README 的 S1）。发不出去时的退路是服务端加
   `-Dsynaxis.app.handshake-phase=login` 重跑，**客户端一行不用改**——login 与 configuration 两条
   路径都已实现，且共用同一个状态机。
2. **`MinecraftWorldView` 的两个方法体**（上表）。
3. **输入层**：哪次点击、用哪条射线调 `RtsClient#selectAt` / `#orderAt`。本轮**刻意没有实现**
   ——它依赖 README 的 S4（屏幕坐标 → 世界射线）与 S5（输入走透明 `Screen` 还是 mixin
   `MouseHandler`）的结论，属阶段 6b。
4. **绘制**：选中框（§5 与 §19 写死是客户端本地画，服务端不下发任何选中相关的实体元数据）与
   落点标记（`OrderFeedback` 已经算好画什么颜色、画在哪、带不带字，6b 只需要读它）。
5. **端到端四环通**（协议 / 渲染 / 拾取 / 下令），`TC-GAME-047` 全篇。

## 三、跑完 spike 之后按什么顺序接

1. **先 S0 + S1**。S1 落 `synaxis:client-mod-required` 时先
   `grep client_rejected logs/events-*.jsonl` 看 `reason`：`mod-missing` 才是「发不出去」，
   `version-mismatch` 说明包发到了。结论回写 `ADR-M2-010`（Proposed → Accepted 或换成 login 形态）。
2. **S2 / S3 出画面结论**，回写 §28.2 的相机与剔除两行。这一步跑完才知道 6b 的相机用正交还是带俯角
   的透视，而这个选择决定 S4 的反投影怎么写。
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
