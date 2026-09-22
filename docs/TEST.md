# Synaxis 测试文档 · 引擎域（M1 / M1.5）与游戏域（M2）

## 1. 元信息 / 变更记录

| 字段 | 值 |
| --- | --- |
| 状态 | 草稿 |
| Owner | Bearisbug |
| 关联设计文档 | `docs/DESIGN.md`（引擎域 M1 / M1.5）与 `docs/DESIGN-M2.md`（游戏域 M2 分册）；两份文档的 REQ 各自编号，M2 侧一律写作 `REQ-M2-*` |
| 被测系统 | 仓库 `Bearisbug/synaxis`，分支 `main` |
| 最后更新 | 2026-09-22 |

变更记录（登用例增改，不登执行轮次）：

| 日期 | 改动 | 作者 |
| --- | --- | --- |
| 2026-09-22 | 跟进 `docs/DESIGN-M2.md` v0.41（§15 整表阈值的正式测量，报告 `docs/research/soak-2026-09-22/report.md`）。**`TC-GAME-068` 保留 ID，操作列一字未改**：第 2、3 步的实测参考值从「待 v0.41 填」换成 2026-09-22 的实测，数字全部可从 `docs/research/soak-2026-09-22/runs/<档名>/summary.json` 复算——第 2 步四条预算里 tick、堆、寻路三条绿，`S2C_UNIT_STATE` 那条三个字段红（最坏布局把兴趣集顶到 512 条上限）；第 3 步三档 tick P99 8.617 → 9.698 → 11.096、至 4,000 未越线。第 2 步期望列的 `orders.answered = orders.sent` 补上口径：harness 到截止时刻即关连接，最后一秒内发出的下令收不到回包，差 ≤ 50 条不算红（实测差 3 条）。第 5、6 步的反证在正式测量那一轮重跑，参考值补上（`check=mismatch`、退出码 2）。§2 不测表、REQ ↔ TC 表与 §5 回归映射不动——`TC-GAME-068` 自 v0.39 起已登记在三处 | Bearisbug |
| 2026-09-22 | 跟进 `docs/DESIGN.md` v0.32 与 `docs/DESIGN-M2.md` v0.40（攻击面盘点，`docs/research/attack-surface-2026-09/README.md`）。**§2 不测表**那行「安全渗透（畸形包模糊测试之外的攻击面）——无归属」改为指向盘点与本轮用例。**新增三条**：`TC-GAME-069`（慢读对端：断开后 channel 必须关，JUnit 先红后绿，探针新子命令 `slow-reader` 在核心模式实跑）、`TC-GAME-070`（离线 UUID 由服务端按用户名派生，对端自选的 UUID 不采信，先红后绿）、`TC-GAME-071`（压缩帧的两道上限与 keep-alive 空闲上界，一步反证三处有齿）。**§3 探针表**新增 `slow-reader` 一行。§5 引擎域回归映射的「协议」一档补上三条 ID。没有废弃用例；§2 的 REQ ↔ TC 表是 M2 侧的手工表，本轮三条对应的都是引擎域 REQ，由机械联检覆盖，表未动 | Bearisbug |
| 2026-09-22 | 跟进 `docs/DESIGN-M2.md` v0.39（§15「50 玩家、单局、全服 1,000 单位」这一档的测量手段）。**新增 `TC-GAME-068`**（对应 `REQ-M2-016`、`REQ-M2-017`、`REQ-M2-009`）：步骤就是 `docs/research/soak-2026-09-22/README.md` 的命令（`-Dsynaxis.app.units=1000` 起服、`soak.py play` 跑门禁档与 50 × {1,000, 2,000, 4,000} 规模曲线），期望列是 §15 的四条数字，实测参考值一列留给测量那一轮（待 v0.41 填）；三步反证今天都跑过——`synaxis.app.units=3` 与 `=abc` 各起一次服都拒启（红在 `Plugin failed to enable` 的 ERROR 与 `GRADLE_EXIT` 非 0），`--units 900` 对着 1,000 单位的服务端跑出 `units.check=mismatch`，服务端停掉后 harness 退出码 2。**§2**：不测表里「50 连接 + 1,000 单位的全量压测」那一行改成只剩客户端 ≥ 60 FPS 无归属，其余四条指向本用例与 harness；REQ ↔ TC 表的 `REQ-M2-009` / `REQ-M2-016` / `REQ-M2-017` 三行各加 `TC-GAME-068`。**§3**：系统属性段新增 `synaxis.app.units`，种子段的「固定 67 个、没有开关」改成「默认 67、总数由 `synaxis.app.units` 定」并补 1,000 单位时的编号与实测，harness 段补 `play` 子命令。**§5** 单位与寻路一组加 `TC-GAME-068`。没有废弃用例 | Bearisbug |
| 2026-09-22 | 跟进 `docs/DESIGN-M2.md` v0.38（聚合项目 `:architecture-tests` 与 §24 规则 ⑥）。**新增 `TC-GAME-067`**：两条反方向规则与 `Entity#moveTo` 的签名钉住各一步绿、三步反证（`Unit#advance` 改回 `refreshPosition` → 成员规则红；`Unit` 加一个 `TickSchedulerThread` 字段 → 类型规则红；`Entity#moveTo` 加 `@ApiStatus.Internal` → 签名测试红），红在哪一条写进期望列。**`TC-GAME-052` 保留 ID、判据未动**：`UnitModelIntegrationTest` 的兴趣集用例改为先跑一个快照再断言 `S2C_UNIT_SPAWN`（通知自 v0.38 起由快照发出），并新增一条「离开 viewer 集合的单位在下一个快照被 despawn、回来再 spawn」的用例；第 1 步只点名五条建模断言，一字未改。§2 的 REQ ↔ TC 表 `REQ-M2-002` 与 `REQ-M2-025` 两行、§5 的「模块与门禁」与跨域牵连「动了引擎 `Entity` 的位置推进入口」同步加 `TC-GAME-067` | Bearisbug |
| 2026-09-22 | 跟进 `docs/DESIGN-M2.md` v0.37。**`TC-GAME-029` 保留 ID，只改第 2 步的期望列**：`heap_delta_mb < 50` 换成三条一起判——`heap_gc_runs ≥ 2`、`heap_delta_kib` 在 `0` 与 `51200` 之间（实测 `rts-512` 6,600 / `rts-128` 1,125），并写明 `heap_gc_runs` 为 0 时前一个数只是分配量。**§2 的「机械校验」段改写**：`REQ-M2-*` 自本轮起进入 test-doc `check.sh` 的 REQ 双向联检（两份设计文档一起联检，元信息「关联设计文档」行的每个 `*.md` 都解析），REQ ↔ TC 对照表由「唯一判据」降为可读索引；反证：把本文档里的 `REQ-M2-012` 全部改成一个设计文档里不存在的编号 → 悬空引用与无 TC 覆盖两条同时红 | Bearisbug |
| 2026-09-22 | 跟进 `docs/DESIGN-M2.md` v0.36（客户端的下令节流与七个结果码的去向）。**`TC-GAME-066` 保留 ID，原八步的操作列一字未改**：第 3 步的条数由 75 改为 92 并按类列出（新增 `OrderFeedbackTest`），期望列点名节流与结果码各对应哪条测试；**新增第 9、10、11 步**——第 9 步反证节流与 `stale` 不顶掉已画标记有齿（删两处预算守卫 + 让 `show` 无条件覆盖，红 4 条），第 10 步反证拾取的边界有齿（把射线当成无限直线并把盒子放大 0.02，红 3 条），第 11 步反证「该命中的仍然命中」与 §14 那张表有齿（把盒子缩小 0.02，同批把 `out-of-bounds` 挪进黄色一支、`no-path` 的 translate 键改成 lang 文件里没有的，红 4 条）；后置里补上这三步的还原。**没有新增用例、没有废弃用例**，§2 的 REQ ↔ TC 表与 §5 的回归映射因此未动 | Bearisbug |
| 2026-09-22 | 跟进 `docs/DESIGN-M2.md` v0.35（客户端绑定表的回收纪律与未匹配消息的去向）。**`TC-GAME-066` 保留 ID，原六步的操作列一字未改**：第 3 步的条数由 66 改为 75 并按类列出，期望列点名回收纪律四个答案各对应哪条测试；**新增第 7、8 步**——第 7 步反证「匹配不上就计数」有齿（把两处计数改回静默丢弃，红 4 条），第 8 步反证其余三条有齿（清空漏掉计数器 + 选中被无条件清 + 下令依赖渲染位置，红 3 条；拾取缓存上一次渲染位置，红 2 条；墓碑集合，红 1 条），后置里补上这两步的还原。**没有新增用例、没有废弃用例**，§2 的 REQ ↔ TC 表与 §5 的回归映射因此未动。`UnitBindingsTest` 的 `aSnapshotLineForAUnitThatIsNotBoundYetIsIgnored` 改名为 `aSnapshotLineForAUnitThatIsNotBoundIsDroppedAndCounted` 并多一行断言，用例判据里没有一处点名过它 | Bearisbug |
| 2026-09-22 | 跟进 `docs/DESIGN-M2.md` v0.34（客户端绑定表的清空时机）。**`TC-GAME-066` 保留 ID，原五步一字未改，新增第 6 步**：`grep` 断言 `SynaxisClientMod` 里挂的是 `ClientPlayConnectionEvents.INIT` 而不是 `DISCONNECT`，期望列写明 `DISCONNECT` 跑在 netty 线程、而 `rts:game` 的负载处理器跑在 `Minecraft.packetProcessor()` 的主线程队列上。**条数与其余判据未变**（客户端仍是 66 条；`RtsClientTest` 的 `leavingTheServerResets…` 改名为 `aNewConnectionStartsWithNoBindingsNoSelectionAndSequenceOne`，断言一行未动，第 3 步只数条数不点名它） | Bearisbug |
| 2026-09-22 | 跟进 `docs/DESIGN-M2.md` v0.33（阶段 6a 里不需要屏幕的那半：客户端侧握手与 `REQ-M2-019` 的拾取与下令）。**新增 `TC-GAME-066`**：客户端侧的握手编排、绑定表生命周期与拾取数学在不启动游戏的前提下的判据，并在同一条用例里点名**哪两个方法体必须等真机**（`MinecraftWorldView` 的两个）。**`TC-GAME-017` 保留 ID，判据条数与文件数未变**，只把第 4 步里四条握手向量的路径由「走 `HandshakeMessages`」改正为「走 `HandshakePayload.CODEC` 与配置阶段的 `FriendlyByteBuf`」——模组现在有这条通道了，照原文读会以为客户端绕过了自己的编解码路径。**`TC-GAME-046` 保留 ID，步骤表未动**：前置里「第 3 步另有一层阻塞（模组还没有 `synaxis:handshake` 客户端侧）」已不成立，改写成「该步骤现在就是 spike S1 的运行期判据」。**`TC-GAME-038` 保留 ID**：第 5 步的「观察入口待 `REQ-M2-019` 实现后填」填上——点空地时模组发一条空的 `C2S_SELECT`，台账那一行是 `count=0`。§2 的 REQ ↔ TC 表三行与 §5 的回归映射同步 | Bearisbug |
| 2026-09-21 | 跟进 `docs/DESIGN-M2.md` v0.32（阶段 0 的 spike ③ 与 spike ④ 收口）。**没有任何用例的判据被改**，只有两处备注：① §2 不测表里四个 spike 那一行注明每个 spike 还剩什么（③ 已跑完、④ 只剩运行期）；② **`TC-GAME-029` 保留 ID，步骤表与判据一字未改**，第 2 步的期望列后面补一句——`heap_delta_mb < 50` 这半条**不可证伪**，因为该字段是 `totalMemory - freeMemory` 的前后差再 `max(0, …)`，载入中一次 young GC 就让它读 0（`rts-512` 与 `rts-128` 实测都是 0），它实际只保证「≥ 0」；整图真实堆增量 ≈ 9.2 MiB 由 spike ③ 用「就绪后强制 GC 取存活堆、两个预设相减」量出，写在 `docs/research/client-spikes-2026-09/README.md` 的 S6。让这半条可证伪是一条独立改动，登记在设计文档 §28.2，本轮没有动实现，所以判据不改 | Bearisbug |
| 2026-09-21 | 跟进 `docs/DESIGN-M2.md` v0.31（阶段 6a 的 `REQ-M2-020b`：客户端侧双端向量、CI、资源打包与跨构建消费定型）。**`TC-GAME-017` 保留 ID**：第 4 步由「阻塞」改成可执行——客户端构建把 `synaxis.vectors.dir` 指向 `protocol/src/test/resources/vectors`，两端读同一批文件，判据写成 `FrozenVectorTest` 的 20 条（19 条往返 + 一条把向量绑在客户端消费的 `:protocol` 工件上）；前置补上「先 `publishToMavenLocal`」，第 5 步补上第 4 步会红在哪一条。**`TC-GAME-045` 保留 ID**：第 2 步改用 `:protocol:publishToMavenLocal` 并补 `:verifyModJar` 的输出行，第 3 步按 jar 的实际内容写准（`assets/synaxis/lang/*.json` 与 `icon.png`、JiJ 的 `:protocol`；**单位模型资源移出本步**——单位当前用原版物品当可见物，自定义模型要等 `ADR-M2-002` 落到渲染那一步），第 6 步注明同一判据已有构建期门禁，新增第 7 步（反证门禁有齿）与第 8 步（CI 的 `Client mod` 工作流）。**`TC-GAME-046` 保留 ID，步骤表未动**，只在前置写明三步都要人工在有图形环境的机器上做、且第 3 步还压在「模组尚未注册 `synaxis:handshake` 客户端侧」上。第 1 步的期望值由 `9` 改正为 `10`——`archetype_box.bin` 自 v0.28 起就在同一目录，照原文执行第一步就不成立（本轮实跑发现）。§2 不测表后那条说明同步 | Bearisbug |
| 2026-09-21 | 跟进 `docs/DESIGN-M2.md` v0.30（阶段 0 的 `REQ-M2-020a` 落地：客户端模组最小骨架）。**`TC-GAME-017` 保留 ID，只改前置的一句话**——第 4 步的阻塞条件原先写的是「`client-fabric` 工程已建」，而工程已经随 `REQ-M2-020a` 建好了、客户端侧却还没有那份向量的夹具，照原文读会让这一步看起来已解除阻塞；改成按 `REQ-M2-020b` 判（夹具与客户端侧 CI 是阶段 6a 的事，见设计文档 §24 的两阶段验收）。步骤表与判据一个字未改。`TC-GAME-045` 正文未动，只按 `:protocol` 的 JiJ 落地更新了 §2 不测表后那条说明的可测范围（第 6 步已能跑、第 3 步半数成立），并在同一处补上四个 spike 的执行清单路径 | Bearisbug |
| 2026-09-19 | 阶段 5 退出条件逐条实跑的同轮工具补齐（**判据一条没改，也没有换 ID**）。**§3 环境段**：探针表新增 `march` 一行（压测陪跑件：保持连接、把 `unit_id=1..N` 一直派在路上，并按秒采样快照里报 `moving` 的单位数——阶段 5 起「走动中的单位」给每个 viewer 带实体位置包，而基线是在没有这份下行时测的），`order` 行补 `--select`（同一条连接上先选中再下令）；`PacketFlood` 那一段写上**打玩法模式必须加 `--hello true`**（默认不发 `C2S_HELLO` 是 `TC-GAME-060` 在核心模式上判的那条路径，玩法模式 3 秒不握手就踢，不加会 0/50 登录成功）。**语义未变、只把占位填成实际命令**：`TC-GAME-039` 第 4 步（`order --select 1,<N>`，并写上 `unit_selected` 那一行的 `count=1` / `rejected_count=1`）。本轮的实跑数字与核心基线对照见 `docs/research/soak-2026-09-18/report.md`「阶段 5 之后的复跑」一节 | Bearisbug |
| 2026-09-19 | 跟进 `docs/DESIGN-M2.md` v0.29（阶段 5 评审第 1 轮修订：启发式向目标偏置 1.15，`no-path` 那档 `unit_order_rejected` 增记 `path_nodes` / `path_ms`）。**新增** `TC-GAME-065`——默认预设上 200 格外的空旷可站立目标必须回 `ok`，并按台账的 `path_nodes` 把「吃满预算才收手」与「真的没有路」分开；这条此前无人覆盖（`TC-GAME-040` 只打 20 格），而修复前它在实服上是红的。§2 的 REQ ↔ TC 表与 §5 的游戏域回归映射同步补 ID。**§3 环境段**：单位种子那一条按本轮实跑重写（两个预设各一轮，含偏置生效后的 `path_nodes` 与批次耗时，并订正 `elapsed_ms` 那处转录）。一条既有用例都没有换 ID：本轮没有任何既有判据实质变更 | Bearisbug |
| 2026-09-19 | 阶段 5 的 `REQ-M2-016` / `REQ-M2-017` 落地（单位与寻路）。**§3 环境段**：探针表新增 `units` 一行、`order` 行补 `--watch` 与 `--group-size`；「对局与单位种子」的占位填实——固定 67 个单位（并注明 65 / 66 与本方单位共用一列），`unit_id=1..64` 本方、`65` 中立、`66` 对方、`67` 远哨，没有开关也不需要预设；探针占位只剩「记账区块与实体位置包」待 `REQ-M2-018`；M2 环境段按本轮实跑改写单位种子那一条——`TC-GAME-041` 的两档 `out-of-bounds` 与 `TC-GAME-042` 的 64 条并发指令（两个预设各一轮，含每 tick 4 次搜索的批次与耗时）一并实跑并记进该段。**语义未变、只把占位填成实际命令与断言入口**：`TC-GAME-052` 前置与第 1 步（建模断言测试是 `UnitModelIntegrationTest`）、`TC-GAME-051` 第 4 步（`EntityTeleportEvent` 的计数入口是 `UnitMovementIntegrationTest`）、`TC-GAME-040` 第 1～4 步、`TC-GAME-041` 第 1～3 步、`TC-GAME-042` 第 1 步，以及 `TC-GAME-024` / `TC-GAME-039` / `TC-GAME-063` / `TC-GAME-064` 里「等 `REQ-M2-016` 的单位」那几段说明——判据一条没改。一条用例都没有换 ID：本轮没有任何判据实质变更 | Bearisbug |
| 2026-09-19 | 阶段 5 的 `REQ-M2-025` 落地（引擎新增公开的 `Entity#moveTo(Pos)`）：**`TC-GAME-051` 保留 ID，只填第 3 步的占位**——等价性对照测试写成真实命令 `EntityMoveToEquivalenceIntegrationTest`，判据点明四档情形（同区块带 viewer、跨区块边界带 viewer、超 8 格走 `EntityTeleportPacket` 分支、无 viewer）逐包相同。第 1、2、4～6 步与前置未动（第 4～6 步仍等 `REQ-M2-017` 的推进调用点）；用例语义未变 | Bearisbug |
| 2026-09-19 | 阶段 4 评审第 2 轮修订。**废旧 ID 换新 ID**：`TC-GAME-025` → **`TC-GAME-064`**——原第 1 步 `game-flood --rate 400 --seconds 3` 离 300 包/秒的阈值只有 33% 余量，而探针是突发发包、读取与 `pump` 会让单次突发的周期漂过一秒，服务端又按固定 1 秒桶计数，实测 14 次挂 7 次（失败时实际速率 344～349 包/秒），判据的速率实质变更；替代用例用 `--rate 800`（6 次全中）并把探针新报的 `slowest window` 纳入第 1 步的判读。§3 的探针表（`game-flood` 默认速率 400 → 800、新增实际速率那一行输出）与 M2 环境段那句「`--rate 400` 稳过」按实测改写；§2 的 REQ ↔ TC 表与 §5 的游戏域回归映射同步换 ID。其余用例语义未变 | Bearisbug |
| 2026-09-19 | 跟进 `docs/DESIGN-M2.md` v0.25（阶段 4 评审第 1 轮修订：`EVT:unit_order_rejected` 只记 §20 点名的四档，`stale` 与 `rate-limited` 不写台账）。**废旧 ID 换新 ID**：`TC-GAME-023` → **`TC-GAME-063`**——原第 5 步要求那 10 条超频在 `logs/events-*.jsonl` 里各留一行 `code=rate-limited`，现在它们一行都不写（客户端可无限刷的拒绝不落盘），判据实质变更；前四步逐字保留，新用例第 5 步改判「计数 0」并补第 6 步用一条越界指令反证四档照常记。§2 的 REQ ↔ TC 表与 §5 的游戏域回归映射同步换 ID。其余用例语义未变 | Bearisbug |
| 2026-09-19 | 跟进 `docs/DESIGN-M2.md` v0.24（阶段 4 的 `REQ-M2-009` 落地）。**§3 环境段**：探针表新增 `order` / `select` / `game-flood` 三行；M2 段的探针占位填掉「发 `C2S_CAMERA` / `C2S_SELECT` / `C2S_MOVE_ORDER`、按速率发包」这几半，只留「打印收到的 `S2C_UNIT_*`、记账区块与实体位置包」待 `REQ-M2-016` / `REQ-M2-018`；「探针的 M2 子命令未验证」那条按实跑改写。**语义未变、只把「用探针…」写成实际命令**：`TC-GAME-023` 第 1～2 步、`TC-GAME-024` 第 1、2、4 步、`TC-GAME-025` 全四步、`TC-GAME-026` 第 1～2 步、`TC-GAME-039` 第 1、5 步，并在这五条的前置里点名「`ok` 那几步要等 `REQ-M2-016` 的单位（阶段 5）」——判据一条没改，改的是执行时机的说明。一条用例都没有换 ID：本轮没有任何判据实质变更 | Bearisbug |
| 2026-09-19 | 跟进 `docs/DESIGN-M2.md` v0.23（阶段 4 的 `REQ-M2-008` 与 `REQ-M2-024` 第 ② 半落地）。**§3 环境段**：探针表新增 `handshake` / `no-hello` / `bad-schema` / `duplicate-hello` 四行并写明 `no-hello` 只适用于玩法模式；表后补一句「本阶段起每个进 configuration 的子命令都先发一次 `C2S_HELLO`」与 login 阶段形态的说明；M2 段的探针占位填掉 `C2S_HELLO` 那一半（`C2S_CAMERA` / `C2S_SELECT` / `C2S_MOVE_ORDER` 仍待 `REQ-M2-009` / `REQ-M2-018`）；系统属性补 `synaxis.app.handshake-phase`。**语义未变、只把「用探针…」写成实际命令**：`TC-GAME-019`（并把前置收窄到玩法模式）、`TC-GAME-022`；`TC-GAME-020` 前置同样收窄到玩法模式；`TC-GAME-049` 第 1、3 步与 `TC-GAME-050` 第 1、6 步的占位填实。**废旧 ID 换新 ID**：`TC-GAME-021` → **`TC-GAME-062`**——原第 2 步要求 `schema_version=0` 也回 `synaxis:protocol-version-mismatch`，而 `REQ-M2-007` 冻结的取值域是 `≥ 1`，越界属畸形输入回 `synaxis:protocol-error`（实测两档分别命中两个键），判据实质变更。**新增** `TC-GAME-060`（核心模式豁免握手但照常回、照常判，并覆盖 `PacketFlood` 这条不发握手的压测路径）与 `TC-GAME-061`（`synaxis.app.handshake-phase=login` 退路，随 spike ④ 的结论一起废弃）。§2 的 REQ ↔ TC 表两行、§5 的游戏域回归映射与跨域牵连行同步 | Bearisbug |
| 2026-09-19 | 跟进 `docs/DESIGN-M2.md` v0.22（阶段 4 的 `REQ-M2-007` 落地）：**`TC-GAME-017` 保留 ID，只增改步骤与前置**——向量集从「每条消息一个 `.bin`」扩成「常规 + 边界两组」，新增第 2 步点边界集的条数、第 3 步（原第 2 步）补上 18 条逐字节对拍与 `manifest.txt` 的 `schema_version` / `digest` 双绑断言、新增第 6 步用「对调 `C2S_CAMERA` 两个 f32 的写入顺序」反证改布局必红，原第 3、4 步顺延为第 4、5 步（客户端侧那一步仍是阶段 6a 的「阻塞」）。判据本身没有放宽，只是把原来一句话的「逐字节相同」写成可照做的形态。`TC-GAME-018` 一字未动：它是进程 + 探针的黑盒用例，随 `REQ-M2-008` 一起解除阻塞 | Bearisbug |
| 2026-09-18 | 跟进 `docs/DESIGN.md` v0.30（客户端可驱动日志的清点范围纠正为全部模块，宿主侧四处逐包日志按同形状限流）：**废弃 `TC-033`、新增 `TC-035`** —— 原用例第 1、2 步只碰引擎根模块，而同形状的日志在 `:server-app` 里也有、门禁也另起了一条（`ClientDrivenLogArchitectureTest`），覆盖面实质变更故换 ID；新用例在原两步之后补上宿主那半边的四条测试与「在 `PluginChannelRouter#route` 插一行无节制 WARN 会红」的反证。§5 回归映射的引擎域「协议」与「分层」两行跟着换 ID。既有用例语义未变 | Bearisbug |
| 2026-09-18 | 跟进 `docs/DESIGN.md` v0.27（客户端可驱动的日志一律有界）：**新增 `TC-033`**（尾部带多余字节的包刷一万次只写一行日志；一批 1,000 个必失败的登录插件应答只换来一次上报，kick 之后的帧不再被处理；ArchUnit 门禁拦住新加的逐包日志与逐包上报），§5 回归映射的引擎域「协议」与「分层」两行各加上它。既有用例语义未变 | Bearisbug |
| 2026-09-18 | 跟进 `docs/DESIGN.md` v0.26（`SOAK-3`：离线模式下同名玩家的重复 UUID）：**新增 `TC-032`**（同 UUID 的第二条连接被拒于登录、追踪器里恰好一个玩家、「移动未注册实体」的日志有界），§5 回归映射的引擎域「协议」行加上它。既有用例语义未变 | Bearisbug |
| 2026-09-18 | §3 环境段登记入站包发包端 `tools/loadtest/PacketFlood.java`（长跑不对应任何 TC，服务端须先在跑；它闭掉了 `docs/DESIGN.md` §15 的「5,000 包/秒」那条——实测 5,014.9 包/秒 / 6 分钟 / 0 掉线，详见 `docs/research/soak-2026-09-18/report.md` 组 6）。同轮跟进 `SOAK-4` 的修复：**新增 `TC-034`**（填满包队列的断线带 `synaxis:protocol-error`，负控用该发包端打 50 × 1,200 包/秒），§5 回归映射的引擎域「协议」行加上它。既有用例正文未动 | Bearisbug |
| 2026-09-18 | §3 环境段登记压测 / 长稳 harness `tools/loadtest/soak.py`（不对应任何 TC，服务端须先在跑；跑法与采集手段见 `docs/research/soak-2026-09-18/README.md`）。用例正文未动 | Bearisbug |
| 2026-09-18 | 跟进 `docs/DESIGN.md` v0.25 与 `docs/DESIGN-M2.md` v0.19：**新增 `TC-031`**（configuration 阶段早退的连接不留虚拟线程也不留 `Player`，覆盖 `SOAK-1` / `SOAK-2` 的修复）与 **`TC-GAME-059`**（稳定面不把 `@ApiStatus.Internal` 类型递给插件，§24 规则④）；§3 日志关联口径补上 `Player failed to respond to known packs query: username=… connection_id=…` 这一行；§5 回归映射的引擎域「协议」与游戏域「模块与门禁」各加一条；REQ-M2-002 行补 `TC-GAME-059`。既有用例语义未变 | Bearisbug |
| 2026-09-18 | 跟进 `docs/DESIGN-M2.md` v0.18（核心模式）：**废弃 `TC-GAME-032`、新增 `TC-GAME-057`** —— 第 1 步「排除玩法插件即非零码退出、端口未 bind」这条判据在默认配置下不再成立（现在进核心模式并照常 bind），要得到拒启必须同时给 `-Dsynaxis.app.core-mode=off`；判据实质变更，按惯例换 ID，原用例第 2～5 步逐字保留。**新增 `TC-GAME-058`**（核心模式冒烟：默认起服、登录、`synaxis:main` echo 往返、`stop` 干净退出、`core-mode=on` 强制、玩法插件在场时不介入）。§2 Gherkin 场景数 47 → 48、REQ ↔ TC 表三行换 ID；§3 系统属性补 `synaxis.app.core-mode`、改写「去掉 `rts` = 非零码退出」那半句并记上「没有插件应答的 id 记一行 WARN」，探针表 `echo` 行由「无提供方、不适用」改为「核心模式下由宿主提供」；`TC-GAME-049` 第 4 步（换 ID 之外还写上切到核心模式的重启参数 `-Dsynaxis.app.core-mode=on`）、`TC-GAME-050` 第 9 步与 §5 回归映射跟着换 ID；§7「探针 `echo` 子命令归属未定」按设计文档的处置结论结案 | Bearisbug |
| 2026-09-16 | 验收收口：`TC-GAME-008` 第 6 步改判据（插件禁用由引擎 `scheduler.shutdown()` 驱动，不再是「排在它之前」，与 `API-M2-007` 同轮改写对齐）；`TC-GAME-032` 新增一步（对局在拆世界之前就置 `ENDED`）。另补登阶段 2 遗漏的用例改动：`TC-GAME-001` 第 7 步、`TC-GAME-016` 第 4 步、`TC-GAME-048` 第 4 步、`TC-GAME-050` 第 9 步与 §3 的两行宿主日志 | Bearisbug |
| 2026-09-16 | 阶段 3 评审第 2 轮修订，**ID 全部保留，只改步骤与后置**：TC-GAME-034 与 TC-GAME-035 的「先红后绿」两步改成**只回退实现文件、保留测试文件**（`git checkout <修复提交>^ -- src/main/java/.../InstanceContainer.java` 与 `.../entity/Player.java`）——复现测试与修复在同一个提交里，整树 `git checkout <修复提交>^` 之后那些测试方法根本不存在，`--tests` 会跑剩下的老方法并给出 `BUILD SUCCESSFUL`，按字面执行得到的是「没红」；两条的第 2 步与后置随之改为 `git checkout HEAD -- <同一文件>`。TC-GAME-034 第 1 步的失败断言改为实际先红的那条（`assertTrue(player.isRemoved())`，消息 `the disconnected player survived the unload of its chunk`），`getEntityByUuid` 那条排在它后面。TC-GAME-036 第 1 步由两条方法改为三条：规则自测拆成 `theRuleRejectsAGameClassCallingTheTagIndex` 与 `theRuleRejectsAGameClassReferencingTheTagIndex`（方法引用形态；规则改用 `accessTargetWhere` 才拦得住，见 `docs/DESIGN-M2.md` §1 v0.17） | Bearisbug |
| 2026-09-16 | 阶段 3（世界与审计残留，`REQ-M2-010`～`REQ-M2-015`）落地同步，**用例 ID 全部保留，只改步骤、前置与判据**：§3 填掉三处占位——`synaxis.app.map` 的默认值 `rts-512` 与另一个可用预设 `rts-128`、地图预设与 seed 的配置方式（`game-rts` 的 `MapPreset` 常量）、事件台账已落地的事件（补 `match_created` / `match_started` / `match_ended`）；§3 同时写明 `synaxis.entity-view-distance` / `synaxis.chunk-view-distance` / `synaxis.player.chunk-update-limiter-history-size` 三项**自本阶段起由 `server-app` 自己设**、命令行显式给的值优先。TC-GAME-029 第 3 步、TC-GAME-031 第 4 步、TC-GAME-033 第 3 步、TC-GAME-034 与 TC-GAME-035 的前置、TC-GAME-035 第 4 步、TC-GAME-036 第 1 步各填上真实的测试类与方法名；TC-GAME-033 第 4 步的「删除 12 个文件」按实际改动改写；TC-GAME-036 第 4 步的 grep 串由中文「不可用于不可信数据」改为英文 `untrusted data`（引擎源码的注释与 Javadoc 一律英文，判据按原文写）；TC-GAME-032 第 1 步的日志判据钉成实测两行、第 2 步写明 M2 没有运维侧结束对局的入口（对局随 `onDisable` 结束＝停机，真机档随阶段 4 探针一起补）；TC-GAME-056 的前置填上参数改写方式、第 1 步钉成实测日志 | Bearisbug |
| 2026-09-16 | 补上 `TC-012` → `TC-030` 换 ID 的两处遗漏：§5 回归策略的引擎域「协议」行与人工收口轮仍圈着已废弃的 `TC-012`，替代用例 `TC-030` 则一轮都进不了范围（同一惯例的 `TC-003` → `TC-029`、`TC-010` → `TC-028` 当时已同步改过这两处）。只改 §5，用例正文与执行记录不动 | Bearisbug |
| 2026-09-16 | `REQ-M2-021` 合入（`smoke` 删除，`server-app` 承接冒烟职责）与阶段 2 落地同步。**环境段改写**：§3 启动命令改 `:server-app:run`、就绪日志串改 `synaxis server-app listening on …`、端口属性改 `-Dsynaxis.app.port`，新增「运行时产物」一条（仓库根的 `plugins/<plugin_id>/` 与 `logs/events-YYYYMMDD.jsonl`，均已 gitignore）并补宿主日志行格式；M2 段删掉过渡期口径（两段现在是同一个入口），填掉「系统属性默认值 / 日志文件名 / 事件 JSON 键名」三处占位（**没有服务端日志文件，日志只走 stdout**），「未验证」标记收窄到尚未落地的四条。**语义未变、只改环境段**：TC-009 第 4 步（启动器路径）、TC-025 第 1、4 步与后置、TC-004 / TC-018 / TC-029（只依赖环境段，正文一字未改）。**废旧 ID 换新 ID**：TC-012 → **TC-030**（第 2 步依赖 smoke 的 `/echo`，该通道随 smoke 消失；替代用例去掉 echo 那一档，只判登录、日志关联与断开，并写明 `REQ-M2-008` 合入后本用例随前置一起废弃）。**填占位**：TC-GAME-016 的说明路径 `docs/PLUGINS.md` 与示例插件 `example-plugin/`、TC-GAME-055 第 5 步的宿主单测名与方法名。§7 的 echo 归属条目按 smoke 已删除的事实改写 | Bearisbug |
| 2026-09-16 | 跟进 `docs/DESIGN-M2.md` v0.8（阶段 1 模块骨架落地），只改步骤与判据、ID 全部保留：TC-GAME-002 第 2 步把「输出含循环依赖提示」钉成实测字面量 `Circular dependency between the following tasks:` 与那条环，并写明失败发生在编译之前；TC-GAME-003 第 2 步的 grep **改用词边界** `grep -rEn "synaxis\.game\b"`——原来的裸 `synaxis\.game` 同时命中 `synaxis.gamedata`，判据永远到不了 `0`；第 5 步填上两条规则自测的真实方法名（`theGameSideRuleRejectsADependencyOnTheGameSide` / `theGameSideRuleAcceptsTheEngineOwnNetworkPluginPackage`）；第 6 步的扫描从裸串收窄到 Java 字符串字面量 `"..plugin.."`——规则 javadoc 会点名这个错误写法并解释它为什么会命中引擎自己的 `network.plugin`，那段警告应当留着，不能被这条机械扫描逼走；TC-GAME-001 **新增第 5 步**（`./gradlew :protocol:generatePomFileForMavenPublication` 后 `grep -c '<dependency>'` 输出 `0`——`:protocol` 发布出去的坐标里没有任何第三方依赖，判的是 `ADR-M2-008`；约定插件 `synaxis.java-library` 给每个模块加的 `api(libs.jetbrainsAnnotations)` 由该模块摘掉，不摘这里是 `1`），原第 5 步（`./gradlew check` 绿）顺延为第 6 步，ID 保留；TC-GAME-003 第 5 步补上规则自测的**消息断言**（违规消息必须点名合成类与 `GameSideFixture`，否则规则退化成空规则时 `failOnEmptyShould` 抛的 `AssertionError` 会被当成通过） | Bearisbug |
| 2026-09-16 | 跟进 `docs/DESIGN-M2.md` v0.6（用户签字与三条范围裁剪）：**废弃 TC-GAME-007 / TC-GAME-008 / TC-GAME-012 / TC-GAME-013**——依赖拓扑序与环检测（007、008）、熔断触发的禁用（012）与滑窗熔断（013）随「插件宿主砍半」整块推到第二个插件出现时，四条整条不再适用，ID 保留不复用；**新增 TC-GAME-053**（单插件三段生命周期与数据目录，接替 007 的冒烟位）、**TC-GAME-054**（事件 / 任务 / 通道三层异常各带 `plugin_id` 归因且进程继续，接替 013）、**TC-GAME-055**（停机时插件被一次性摘干净且零后续回调，接替 012 —— M2 没有熔断，禁用的唯一触发路径是停机）；**废弃 TC-GAME-030、新增 TC-GAME-056**（`.synx` 推 M3 后没有地图文件可损坏，判据从「改文件字节」改成「未知预设 id / 非法生成参数」，语义实质变更故换 ID）；TC-GAME-011 第 3 步的 `plugin_tripped` 改判 `plugin_enabled` 缺该插件（埋点已删）；TC-GAME-029 前置与第 1 步改为进程内程序化生成、并补「全程不读写任何地图文件」；§2 覆盖描述、Gherkin 场景数（48 → 47）、REQ ↔ TC 表与不测项同步；§3 系统属性去掉 `synaxis.app.plugin.trip-threshold`、`synaxis.app.map` 改为地图预设 id、地图种子改为进程内生成、ROLE-PluginAuthor 去掉「依赖成环 / 持续抛异常」两类探测插件；§5 游戏域回归映射补新 ID | Bearisbug |
| 2026-09-16 | 跟进 `docs/DESIGN-M2.md` v0.5（评审第 4 轮收尾）：TC-GAME-043 第 6／7 步改成在**相邻两个区块 A／B** 之间来回推并按跨区块次数逐次记账（反证判据改为「把限流器配置改回 5 后，至多前两次跨区块还收得到 `ChunkData`，从第三次起再也收不到」，与 `addToHistory` 的滑动窗口语义一致），前置补 A／B 两个锚点；TC-GAME-050 第 7 步拆成两步——抛异常（仍以 `synaxis:match-unavailable` 断开并记 ERROR）与慢返回 500 毫秒（照常放行、只多一行带耗时的 WARN、只拖慢本连接），原第 8 步顺延为第 9 步，前置补并发两条探针；TC-GAME-043 第 5 步与 TC-GAME-051 第 6 步的异常类型由 `IllegalArgumentException` 改为 `NullPointerException`（`Check.notNull` 抛的是 NPE） | Bearisbug |
| 2026-09-16 | 跟进 `docs/DESIGN-M2.md` v0.4（评审第 3 轮收口）：新增 TC-GAME-050（`REQ-M2-024` 准入回调与握手跨线程交接）、TC-GAME-051（`REQ-M2-025` 公开的 `Entity#moveTo` 位置推进入口）；**废弃 TC-GAME-037，新增 TC-GAME-052**（`setViewRange` 的期望值从写死的 ≥ 1.6 改为按 `archetype_view_range=4.0` 配置，并补「实体距离」倍率 50% 档——阈值与判据都变了，按惯例换 ID）；TC-GAME-029 补第 6 步（就绪后 dispatcher 与 `TickSchedulerThread` 都在跑、且载入期间零 tick）；TC-GAME-032 补准入回调缺失与重复注册两档；TC-GAME-043 补来回推镜头 5 个来回不丢区块（区块更新限流器已关）；TC-GAME-047 补 50% 实体距离档；§2 覆盖区间改到 `REQ-M2-025`、Gherkin 场景数改 48、REQ↔TC 表补三行；§3 系统属性补 `synaxis.player.chunk-update-limiter-history-size=0`；§5 回归映射补新 ID | Bearisbug |
| 2026-09-16 | M2 用例评审第 1 轮修订（4 条必改 + 11 条建议）：TC-GAME-001 只判四模块与构建绿，smoke 删除由 TC-GAME-048 独占（与 §3 过渡期口径、设计文档 §26 的阶段划分对齐，阶段 1 gate 不再必然假失败）；TC-GAME-002 第 4 步的 grep 钉到 `docs/DESIGN-M2.md`；§3 种子清单补对方队伍单位、距出生点 ≥ 200 格的单位与 9 个本方单位；TC-GAME-024 第 4 步补令牌回填等待并写明前 8 条 / 第 9 条各回什么；TC-GAME-030 三步补进程非零码退出与端口未 bind；TC-GAME-037 第 5 步归因改服务端 `entity-view-distance`；TC-GAME-042 第 3 步改按结果码分布判定、第 5 步收窄到「无 tick 超时告警行」；TC-GAME-012 的熔断阈值挪进前置；TC-GAME-013 补触发手段；TC-GAME-049 点名的七条存量 TC 归并进 §5 范围列；§2 不测表把并发回归场景的归属改为 TC-021 并补 `docs/DESIGN.md` `REQ-016` 一行；RUN-003 明细的结果标签归入枚举并补证据引用 | Bearisbug |
| 2026-09-16 | 新增游戏域（M2）用例 TC-GAME-001～TC-GAME-049，覆盖 `docs/DESIGN-M2.md` 的 REQ-M2-001～REQ-M2-023 与其 42 个 Gherkin 场景；§2 补 M2 范围、REQ↔TC 对照表与不测项（并删去「Fabric 客户端模组 → 里程碑 M2」一行，M2 已进本文档范围），§3 补 M2 启动 / 地图与对局种子 / 探针 / 客户端模组前置（标「未验证」，首轮执行前补冷启动实测），§5 补 M2 功能域回归映射。存量 TC-001～TC-029 一条未改——M2 是第二个功能域，新 ID 一律带 `GAME` 域段 | Bearisbug |
| 2026-09-16 | 预告（实现前**不改**语义）：`REQ-M2-021`「server-app 取代 smoke」合入时，§3 的启动命令 / 就绪日志串 / 端口属性 / 日志文件名属**环境前置**，语义未变的 TC 保留原 ID 只改环境段（TC-004、TC-018、TC-025 第 4 步、TC-029）；语义实质变更的按惯例废旧 ID 换新 ID——已知的是 TC-012（前置「原版客户端」在强制模组后不再成立，第 2 步又依赖 smoke 的 `/echo`）。本轮不动它们：smoke 仍是唯一启动入口，提前改会把台账改成不可执行 | Bearisbug |
| 2026-09-15 | 项目定名 Synaxis（设计文档 v0.18）：TC-002、TC-005、TC-010、TC-012、TC-013、TC-014、TC-017、TC-018、TC-019、TC-020、TC-021、TC-024、TC-025、TC-026、TC-027 与 §1、§3 中的仓库名、包路径、品牌、原因键、通道名字面量随改名更新；用例语义不变，ID 不变 | Bearisbug |
| 2026-09-15 | 废弃 TC-003，新增 TC-029（第 2 步阈值改为 RUN-012 基线 2,602）；废弃 TC-010，新增 TC-028（第 4 步只要求非空行不指向改名提交） | Bearisbug |
| 2026-09-09 | 初稿，TC-001～TC-018，覆盖 REQ-001～REQ-012 | Bearisbug |
| 2026-09-09 | TC-007 第 4 步增加 `snapshot` 目录检查（设计文档 v0.2） | Bearisbug |
| 2026-09-09 | TC-001：前置与第 3 步按实际数据修正为 74 个 JSON、路径 `src/main/resources/data`、含子目录故改用 `find`（设计文档 v0.2.1）；第 4 步补上镜像 init 脚本 | Bearisbug |
| 2026-09-13 | 废弃 TC-017，新增 TC-027（创造包整包跳过）与 TC-026（分配攻击 / NBT 炸弹）；§3 探针表补 `alloc-bomb`、`nbt-bomb` 并改写 `deep-recursion` 行（设计文档 v0.17） | Bearisbug |
| 2026-09-09 | 新增 TC-025（REQ-014 配置对象） | Bearisbug |
| 2026-09-09 | 新增 TC-024（REQ-015 分层严格规则与依赖矩阵） | Bearisbug |
| 2026-09-09 | 废弃 TC-022，新增 TC-023（第 1 步只计静态访问器） | Bearisbug |
| 2026-09-09 | 新增 TC-022（REQ-013 并行测试与单跑） | Bearisbug |
| 2026-09-09 | 废弃 TC-005，新增 TC-021（CI 变红即可，不限定 jcstress）；TC-006 补「开草稿 PR」步骤说明 | Bearisbug |
| 2026-09-09 | TC-020 第 4、5 步路径随 REQ-009 改为 `dev/gameengine`（设计文档 v0.8） | Bearisbug |
| 2026-09-09 | 废弃 TC-007，新增 TC-020（第 3 步阈值 ≤ 1,150；第 5 步允许 `other/` 两个类） | Bearisbug |
| 2026-09-09 | TC-001 第 3 步与 TC-008 第 2 步按 REQ-007 黄金 NBT 路线修正数据源与数量（设计文档 v0.6） | Bearisbug |
| 2026-09-09 | 废弃 TC-013，新增 TC-019（KeepAlive 预期改为往返不中断，见 RUN-004） | Bearisbug |
| 2026-09-09 | TC-015 第 2 步补 `--rerun-tasks --no-build-cache`：不加时 `:test` 命中构建缓存不真正执行，用例测不到任何东西 | Bearisbug |
| 2026-09-09 | §3 补记实测的就绪判定日志行、`smoke` 启动方式与探针子命令用法，去掉三处「未验证」标记 | Bearisbug |

## 2. 测试范围 / 不测什么

- 覆盖（引擎域 · M1 / M1.5）：里程碑 M1 的 12 条需求的黑盒与半黑盒验收——构建产物与仓库元数据（命令、退出码、文件）、Minecraft 协议行为（裸 socket 与原版客户端可观察的包与断线原因）、文档与合规文件。
- 覆盖（游戏域 · M2）：`docs/DESIGN-M2.md` 的 `REQ-M2-001`～`REQ-M2-025` 的黑盒与半黑盒验收——工程门禁的可观察结论（模块树、构建红绿、CI 结论）、插件宿主的生命周期与隔离行为（启停日志、退出码、回调有无、事件 JSONL）、自定义负载协议行为（探针可见的消息、结果码与断线原因键）、程序化地图生成与对局生命周期、单位 / 寻路 / 镜头的服务端权威行为，以及客户端模组的安装与 RTS 切片的端到端人工验收。
- Gherkin 覆盖口径：`docs/DESIGN-M2.md` §19 的 48 个 Gherkin 场景**全部**由本文档的 TC 可操作化，没有转交自动化测试的场景；逐条对照见下方 REQ ↔ TC 表与各用例标题行。
- 机械校验口径：`bash ~/.claude/skills/test-doc/check.sh docs/TEST.md` 做 REQ 双向联检——本文档引用的每条 REQ 必须在某一份设计文档里存在，每份设计文档的每条 REQ 必须被至少一条 TC 引用。**两份设计文档一起联检**：`check.sh` 把上方元信息「关联设计文档」行里的**每一个** `*.md` 都解析出来，`REQ-001`～`REQ-017` 与 `REQ-M2-001`～`REQ-M2-025` 都在范围内。两处不能动，动了绿灯就不再代表已核：① ID 正则的域段是 `[A-Z][A-Z0-9]*`，收窄成纯字母会让带数字的 `M2` 域段整块静默退出联检；② 元信息那一行必须保留两份文档的路径，只留一份则另一份的 REQ 无人认领。下表因此不是唯一判据，留作可读索引——查一条 REQ 由哪些 TC 覆盖、哪些 TC 已废弃及原因。

REQ-M2 ↔ TC 对照（可读索引，覆盖与否由上述联检判）：

| REQ | TC |
| --- | --- |
| REQ-M2-001 模块拆分 | TC-GAME-001、TC-GAME-002 |
| REQ-M2-002 `game` 改名与依赖方向规则 | TC-GAME-002、TC-GAME-003、TC-GAME-059、TC-GAME-067 |
| REQ-M2-003 插件发现与生命周期 | TC-GAME-053、TC-GAME-009、TC-GAME-010、TC-GAME-011（TC-GAME-007、TC-GAME-008 已废弃：依赖拓扑序与环检测随范围裁剪推到 M3） |
| REQ-M2-004 插件能力边界 | TC-GAME-055（TC-GAME-012 已废弃：M2 没有熔断，禁用只由停机触发） |
| REQ-M2-005 异常归因 | TC-GAME-011、TC-GAME-054（TC-GAME-013 已废弃：滑窗熔断随范围裁剪推到 M3） |
| REQ-M2-006 通道命名空间 | TC-GAME-014、TC-GAME-015 |
| REQ-M2-007 `:protocol` 编解码 | TC-GAME-017、TC-GAME-018 |
| REQ-M2-008 握手与模组校验 | TC-GAME-019、TC-GAME-020、TC-GAME-022、TC-GAME-049、TC-GAME-060、TC-GAME-061、TC-GAME-062、TC-GAME-066（TC-GAME-021 已废弃：`schema_version=0` 是畸形输入而不是版本不符，判据换了口径） |
| REQ-M2-009 切片消息集与限流 | TC-GAME-024、TC-GAME-026、TC-GAME-027、TC-GAME-028、TC-GAME-063、TC-GAME-064、TC-GAME-068（TC-GAME-023 已废弃：`rate-limited` 按 §20 不再写台账，第 5 步判据换了口径；TC-GAME-025 已废弃：第 1 步的 400 包/秒离阈值太近、实测 14 次挂 7 次，速率换了口径） |
| REQ-M2-010 程序化地图生成器 | TC-GAME-029、TC-GAME-056（TC-GAME-030 已废弃：`.synx` 推 M3 后没有地图文件可损坏） |
| REQ-M2-011 对局生命周期 | TC-GAME-029、TC-GAME-031、TC-GAME-057（TC-GAME-032 已废弃：核心模式落地后「排除插件必然非零退出」不再成立） |
| REQ-M2-012 删除 `SharedInstance` | TC-GAME-033 |
| REQ-M2-013 断线幽灵修复 | TC-GAME-034 |
| REQ-M2-014 视距收缩修复 | TC-GAME-035 |
| REQ-M2-015 F49 处置 | TC-GAME-036 |
| REQ-M2-016 单位模型与表现 | TC-GAME-052、TC-GAME-038、TC-GAME-068（TC-GAME-037 已废弃） |
| REQ-M2-017 服务端寻路 | TC-GAME-028、TC-GAME-040、TC-GAME-041、TC-GAME-042、TC-GAME-047、TC-GAME-065、TC-GAME-068 |
| REQ-M2-018 RTS 镜头 | TC-GAME-043、TC-GAME-044、TC-GAME-047 |
| REQ-M2-019 客户端选中与下令 | TC-GAME-038、TC-GAME-039、TC-GAME-047、TC-GAME-066 |
| REQ-M2-020 客户端模组工程 | TC-GAME-045、TC-GAME-046、TC-GAME-066 |
| REQ-M2-021 server-app 取代 smoke | TC-GAME-048、TC-GAME-049、TC-GAME-058 |
| REQ-M2-022 CI 门禁扩展 | TC-GAME-004、TC-GAME-005、TC-GAME-006 |
| REQ-M2-023 社区上手 | TC-GAME-016 |
| REQ-M2-024 宿主准入链 | TC-GAME-050、TC-GAME-057、TC-GAME-060 |
| REQ-M2-025 公开的位置推进入口 | TC-GAME-051、TC-GAME-067 |

- 不测：

| 不测项 | 归属 |
| --- | --- |
| 单元 / 集成自动化（M1 的 2,602 个 JUnit 用例、jcstress 场景、ArchUnit 规则本身） | 代码仓测试套件，`./gradlew check` 与 CI，策略见 `docs/DESIGN.md` §19 |
| `docs/DESIGN.md` 的 `REQ-016`（拆 `NetworkBufferTypeImpl` 为按类型族分文件的实现，零 API 影响） | 纯重构，对外行为与 API 均不变，没有可单独黑盒观察的结论——由 `./gradlew check` 的既有单测覆盖，测试总数与 0 失败由 TC-029 判 |
| M2 新增自动化（`:protocol` 双端向量测试、宿主的 descriptor 校验 / 生命周期 / 通道路由单测、ArchUnit 规则对合成 `JavaClasses` 的自测、F29 与 F11 的复现测试、jacoco 与 diff-cover 本身） | 代码仓测试套件，策略见 `docs/DESIGN-M2.md` §19；本文档只在 TC 步骤里调用它们并判读可观察输出，不复制其断言 |
| `docs/DESIGN-M2.md` §15 里客户端 ≥ 60 FPS 那一行（同屏 200 单位、中端独显） | 无归属——已知风险（待 spike，见设计文档 §28.2）。**50 连接 + 1,000 单位那一档的另外四条（tick P99、`S2C_UNIT_STATE` 体积与频率、进程堆、寻路占用）自设计文档 v0.39 起有归属**：`tools/loadtest/soak.py play`（`docs/research/soak-2026-09-22/README.md`）测量、`TC-GAME-068` 判读，不再在本表；TC-GAME-042 仍只打到 Gherkin 写明的「64 条并发指令」这一档 |
| 安全渗透（畸形包模糊测试之外的攻击面） | 已盘点（2026-09-22）：`docs/research/attack-surface-2026-09/README.md`，资源耗尽 / 授权 / 身份 / 日志与磁盘 / 崩溃面五组，每一面写「威胁 → 现有控制（类与行）→ 证明它的测试或 TC → 结论」。盘点补的用例是 TC-GAME-069～TC-GAME-071，其余每一面在那张表里指到既有 TC 或代码仓测试；查出未修的缺口登记在 `docs/DESIGN.md` §28（连接数无上限、`socketTimeout` 空操作）与 `docs/DESIGN-M2.md` §28.2（核心模式区块加载、玩法模式的位置与挖掘、聊天放大、选中不判兴趣集）。仍然没有归属的只剩**模糊测试本身**（随机畸形帧的持续跑），与上一行的整表压测同一档 |
| `docs/DESIGN-M2.md` §26 阶段 0 的四个 spike（`:protocol` 的 JiJ 消费、相机形态、地图生成与载入实测、Fabric 在 configuration 阶段能否发包） | 设计文档 §26，结论回写设计文档；spike 不是验收，不进本文档（四个 spike 的执行清单在 `docs/research/client-spikes-2026-09/README.md`，开头一张表说明每个 spike 还剩什么：**③ 已于 2026-09-21 在服务端跑完**，**④ 已有源码级结论、只剩运行期那一半**，① 剩运行期一半，② 整个都要肉眼看画面）。**客户端模组本身（`REQ-M2-020a` 的骨架与 `REQ-M2-020b` 的工程完备）不属此列**——它是交付物，由 TC-GAME-045（构建与打包，本机跑得到第 1～7 步，第 8 步要推分支才看得到 CI）与 TC-GAME-066（客户端侧握手与拾取里不需要屏幕的那一半）覆盖 |
| 客户端渲染观感（相机形态、选中框样式、HUD 文案、动效） | 无用例——主观项；只在 TC-GAME-047 的录屏与截图里留证据供人评判 |
| 设计文档 §19 中「CI 门禁拦截并发回归 / 分层违规」两个场景 | 由 TC-021、TC-006 以「故意提交坏改动到临时分支」方式可操作化，见 §4 |

## 3. 环境与前置

- 启动：`export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home && ./gradlew :server-app:run --no-daemon --console=plain --init-script gradle/init/aliyun-mirror.init.gradle.kts`（`REQ-M2-021` 起 `server-app` 是唯一启动入口，`smoke` 已删除）。默认绑定 `0.0.0.0:25565`，端口用 `-Dsynaxis.app.port=<port>` 覆盖（`run` 任务把 `synaxis.*` 系统属性转给 fork 出来的 JVM）。TC-004 与 TC-018 要往服务端 stdin 写 `stop`，因此运行期间必须保持 stdin 打开（`:server-app:run` 已把 Gradle 进程的 stdin 转给应用；后台运行时用 FIFO 喂入或 `( sleep N; echo stop ) | ./gradlew :server-app:run …`）。
- 就绪判定：stdout 出现 `synaxis server-app listening on 0.0.0.0:25565`，且 `nc -z 127.0.0.1 25565` 退出码为 0。
- 运行时产物：进程在**仓库根**写 `plugins/<plugin_id>/`（插件数据目录）与 `logs/events-YYYYMMDD.jsonl`（事件台账），两者均已 gitignore；基线重置 = `rm -rf plugins logs` 后重启。**没有服务端日志文件**，日志只走 stdout（`server-app/src/main/resources/logback.xml` 只配了 ConsoleAppender），要留证据就重定向 stdout。
- 日志关联：与连接相关的服务端日志行统一带 `connection_id=<uuid>` —— `Login success: username=… connection_id=… remote_address=…`、`Kicking connection_id=… identifier=… reason=synaxis:…`、`Connection closed: connection_id=… identifier=…`、`Player failed to respond to known packs query: username=… connection_id=…`；宿主侧的行带 `plugin_id=<id>`——`Plugin enabled: plugin_id=… version=… enable_ms=…`、`Registered channel: plugin_id=… channel=…`、`Dropped a payload on an unregistered channel: connection_id=… channel=… bytes=…`、`Refusing a connection: connection_id=… reason=… cause=…`、`Admission provider is over its 200 ms budget: plugin_id=… connection_id=… admit_ms=…`。启动被拒时的那一行没有这两个 id：`Refusing to start: <原因>`（非法端口属性或非法 descriptor，退出码 1，见 TC-GAME-009 / TC-GAME-010）。
- 入口：TCP `127.0.0.1:25565`；离线模式（`Auth.Offline`），任意用户名；压缩阈值 256 字节。
- 测试账号：

| 角色 | 账号 | 获取方式 |
| --- | --- | --- |
| ROLE-Client | 原版 Minecraft 26.2 客户端，用户名 `tester1` | 官方启动器新建 26.2 配置，多人游戏直连 `127.0.0.1` |
| ROLE-Client（脚本） | 裸 socket 探针 `tools/protocol-probe/probe.py`，用户名 `probe` | 随仓库提交；Python 3.10+，仅用标准库 |
| ROLE-GameApp / ROLE-Operator | 本机 shell | 仓库 clone 即有 |

- 探针用法：`python3 tools/protocol-probe/probe.py [全局参数] <子命令> [子命令参数]`。全局参数 `--host`（默认 `127.0.0.1`）、`--port`（默认 `25565`）、`--username`（默认 `probe`）、`--timeout`（默认 10 秒）**必须写在子命令之前**。

| 子命令 | 参数 | 行为 | 退出码 0 的条件 |
| --- | --- | --- | --- |
| `login` | `--hold N`、`--protocol N`（默认 776） | 走完 login → configuration → play，保持 N 秒并应答 KeepAlive | 进入 play 状态；保持期间被服务端断线则退 1 并打印原因键 |
| `premature-finish` | — | 在服务端发出 FinishConfiguration 之前抢跑应答 | 收到 `Disconnect reason=synaxis:protocol-error` |
| `bad-version` | `--protocol N`（必填） | 用非 776 的协议版本握手 | 收到 `LoginDisconnect reason=synaxis:unsupported-version` |
| `zero-frame` | — | 进入 play 后发单字节 `0x00` | 收到 `Disconnect reason=synaxis:protocol-error` |
| `deep-recursion` | `--depth N`（默认 200） | 创造模式设槽包携带 N 层自嵌套 `CustomPotionEffect.Settings` | REQ-017 后非创造玩家的创造包整包跳过：连接保持、随后 KeepAlive 正常即为成功（递归深度守卫由 `NetworkBufferRecursiveDepthTest` 覆盖） |
| `alloc-bomb` | — | 登录后发送声明 65,535 个元素但只含少量字节的集合包 | 收到 `Disconnect reason=synaxis:protocol-error` |
| `nbt-bomb` | `--mib N`（默认 3） | 通过 `ClientCustomClickActionPacket` 携带解码后估算 N MiB 的 NBT | 收到 `Disconnect reason=synaxis:protocol-error` |
| `echo` | `--payload TEXT`（必填）、`--channel`（默认 `synaxis:main`） | 走 `synaxis:main` 通道做一次自定义负载往返。该通道由**宿主在核心模式下**提供（`synaxis` 是宿主保留的通道命名空间，玩法插件的通道仍归插件所有），因此本子命令只在核心模式的进程上适用——玩法模式没有这条通道 | 收到与发出完全一致的负载 |
| `order` | `--count N`（默认 1）、`--seq N`（默认 1）、`--group N`（默认按序号各不相同）、`--group-size N`（默认 1）、`--unit N`（默认 1）、`--select 1,2,3`（默认不选）、`--x/--y/--z`、`--expect CODE`、`--expect-count N`、`--wait S`（默认 5）、`--watch S`（默认 0） | 进 play 后连发 N 条 `C2S_MOVE_ORDER`，收集 `S2C_ORDER_RESULT` 并打印一行 `answers=收到/发出` 加结果码直方图。给 `--group` 时整批共用一个 `order_group`（一次点击选中多个单位的形态）；给 `--group-size N` 时按每 N 条一组编号（一批超过组内 8 包上限的指令要这么发才不会被限流）。给 `--select` 时先在**同一条连接**上发一条 `C2S_SELECT`、再发这些指令——选中集与下令判据互不相干，要看清「选中里混了一个指挥不动的单位之后，合法项还能不能下令」只有这一条路。给 `--watch S` 时继续读 S 秒的 `S2C_UNIT_STATE`，跟着 `--unit` 那个单位走完 `moving → idle` 并打印一行 `unit_id=… snapshots=… seen_moving=… unit_state=…`（位置不在快照里，走的是引擎实体包，所以这里判的是状态机不是坐标） | 收到的结果数等于发出的条数，且 `--expect` 的结果码恰好出现 `--expect-count` 次（不给 `--expect` 时只判条数）；给了 `--watch` 时还要求该单位先报过 `moving`、并在 S 秒内回到 `idle` |
| `select` | `--units 1,2,3`、`--expect {silence,protocol-error}`（默认 `silence`）、`--wait S`（默认 2） | 进 play 后发一条 `C2S_SELECT{count=给了几个就写几个}`，count 照写不夹取，因此可以故意越过上限 8 | `silence`：连接保持且没有任何回包；`protocol-error`：收到 `Disconnect reason=synaxis:protocol-error` |
| `game-flood` | `--rate N`（默认 800，每秒包数）、`--seconds N`（默认 3）、`--expect {flood,alive}`（默认 `flood`）、`--wait S` | 按给定速率在 `rts:game` 上持续发 `C2S_CAMERA`，发包途中按批读取（不读会先把服务端的写堵住、实际速率达不到设定值），并在跑完时报一行 `asked for <设定值> payloads a second, slowest window <实际值>`——实际值就是最慢那一秒真写进 socket 的包数，判「闸门没响是谁的错」看它 | `flood`：收到 `Disconnect reason=synaxis:game-flood`；`alive`：整段跑完仍在线 |
| `slow-reader` | `--payloads N`（默认 256）、`--bytes N`（默认 30,000）、`--channel`（默认 `synaxis:main`）、`--wait S`（默认 30） | 进 play 后往 echo 通道发 N 个 `--bytes` 字节的随机负载，**一个字节都不读回**：服务端逐条回显，出站队列与 socket 缓冲被撑满，写线程阻塞在 socket 写里；随后保持 `--wait` 秒不读，等 keep-alive 把这条连接踢掉（上一条 keep-alive 之后 15 s）。判「服务端有没有放掉 socket」不靠读——一读服务端就解堵、自己会关——而是隔 0.5 s 连发两个小帧（`ClientPingRequestPacket`），服务端已关则第二次 `send` 报错。末行报的 `within Ns` 是从发完负载算到探测完成，只是上界；真正的关闭时刻看服务端日志。只适用核心模式（echo 通道在那里） | 两次发送里有一次 `OSError`（服务端已关 socket）；`--wait` 到点两次发送都成功则退 1，末行 `the server is still holding the socket` |
| `units` | `--wait S`（默认 5）、`--expect-min N`（默认 1） | 进 play 后收 S 秒的三条下行单位消息，打印一行 `units=… commandable=[…] snapshots=… despawned=[…]` 与一行各单位的 `unit_state`。兴趣集是引擎 viewer 集合的投影，所以这里看到的就是服务端认为这条连接该看见的那些单位（设计文档 `API-M2-006`） | 兴趣集里至少 `--expect-min` 个单位，且至少收到一条 `S2C_UNIT_STATE` |
| `march` | `--units N`（默认 64）、`--seconds S`（默认 300）、`--period S`（默认 10）、`--spacing S`（默认 0.25）、`--ax/--az`、`--bx/--bz`、`--y`、`--expect-moving N`（默认 0） | 进 play 后保持连接，每 `--period` 秒把 `unit_id=1..N` 轮流派往 A、B 两个点（每 8 条一组、组间隔 `--spacing` 秒，正好压在 10 操作/秒的预算与每 tick 4 次的抽取之下），每秒采样一次快照里报 `moving` 的单位数，结束时打印 `periods=… orders=…` 加结果码直方图与 `moving samples=… min=… median=… max=…`。**它是压测的陪跑件而不是判据**：阶段 5 起「走动中的单位」会给每个 viewer 带来实体位置包，而基线是在没有这份下行的情况下测的，要把这份负载摆上台面就得有一条一直在下令的连接——连接一走，它的单位全部停下（§11.4） | 发出的指令全部 `ok`，且至少一半的采样报了 `--expect-moving` 个单位在走 |
| `record-registry` | `--out FILE`（必填） | 按收到顺序写出全部 `RegistryData` 包的解压后字节（含包 ID varint） | 至少收到一个 `RegistryData` 包 |
| `handshake` | `--mod-version N`（默认 1） | 登录并握手，打印服务端回的 `S2C_HELLO` 字段 | 收到 `S2C_HELLO` 且其 `schema_version` 等于仓库里的 `ProtocolSchema.SCHEMA_VERSION` |
| `no-hello` | — | 登录后**不发** `C2S_HELLO`（模拟没装模组的客户端） | 收到 `Disconnect reason=synaxis:client-mod-required`。**只适用于玩法模式的进程**——核心模式不要求握手，会照常放行并让本子命令退 1（见设计文档 `API-M2-010` 第 ⑤ 步） |
| `bad-schema` | `--schema N`（默认 = 仓库值 + 1） | 发 `C2S_HELLO{schema_version=N}` | 收到 `Disconnect reason=synaxis:protocol-version-mismatch`（两种模式都适用） |
| `duplicate-hello` | — | 握手成功后在同一条连接上再发一次 `C2S_HELLO` | 收到 `Disconnect reason=synaxis:protocol-error`（两种模式都适用） |

  探针每次启动时从 `network/packet/PacketVanilla.java` 的注册表顺序推导包 ID、从 `component/DataComponents.java` 的注册顺序推导数据组件 ID、从 `protocol/…/ProtocolSchema.java` 读 `schema_version`，不写死协议常量；注册表顺序变化时探针自动跟随。stdout 逐条打印收到的包名、断线原因键与末行 `exit code: <n>`（首行也打印它这一轮用的 `schema_version`）。

  **`REQ-M2-008` 起，上表每个进入 configuration 阶段的子命令都会先发一次 `C2S_HELLO`**（`no-hello` 例外，它就是来测不发的）——这是强制握手上线时保住这批存量用例的做法，不是测试旁路开关（设计文档 `API-M2-002` 的「连带影响」）。服务端跑在 `synaxis.app.handshake-phase=login` 时探针改为应答服务端的那条 login plugin request，两种形态互斥、不会重发。

- 压测 / 长稳 harness：`python3 tools/loadtest/soak.py run --connections N --duration S --out <目录>`（Python 3.10+，仅用标准库）。它复用探针的握手 / 登录 / configuration，在其上加多连接维持、连接 churn、把上表的恶意子命令按间隔打进负载，并用 `jcmd` / `jstat` / `lsof` / `ps` 与 JFR 事件 `synaxis.ServerTickTime` 采样服务端。**`run` 不对应任何 TC**（长跑观测工具，不是判据），服务端必须已在运行，否则直接以退出码 2 拒绝跑。产物 `summary.json` / `samples.jsonl` / `attacks.jsonl` / `soak.jfr` 写进 `--out` 目录；跑法、采集手段与 2026-09-18 的一轮结果见 `docs/research/soak-2026-09-18/`。**玩法模式 `python3 tools/loadtest/soak.py play --port P --connections N --duration S --units U --server-log <服务端 stdout 文件> --out <目录>`**（设计文档 v0.39）：N 条连接走真实的 `C2S_HELLO` 握手进 play，镜头 5 Hz 沿利萨如曲线游走全图并让玩家实体跟着锚点走（`REQ-M2-018` 服务端侧的传送未落地，不走就没有区块流送）、每秒一条对本方单位的下令、每 10 s 一次选中；入站字节分 `rts:game` 负载（按 `msg_id` 细分，`S2C_UNIT_STATE` 另记单条体积上限、条目数上限与相邻间隔）与引擎包两栏，tick 分布仍取 JFR，每 `--gc-probe-interval` 秒强制 GC 取存活堆并采 RSS，另读 `--server-log` 从开跑起新增的 WARN / ERROR / `Kicking connection_id=` 行数与 `logs/` 台账新增事件的 `path_ms`。`--units` 只把预期单位数记进 `summary.json` 与任一连接见过的最大兴趣集对照，不改服务端。**它是 `TC-GAME-068` 的执行载体**；`summary.json` 每个字段的含义与对应的 §15 行见 `docs/research/soak-2026-09-22/README.md`。

- 入站包发包端：`java tools/loadtest/PacketFlood.java --connections N --hz H --seconds S --out <目录>`（JDK 25 单文件源码启动器，不进任何 source set）。`soak.py` 的 Python 发包端到 2,880 包/秒就是上限，证不了 `docs/DESIGN.md` §15 的「50 玩家峰值 5,000 包/秒」，本工具用每连接一个发送线程 + 一个读取线程补上这一档；包 ID 与探针同口径从 `PacketVanilla.java` 推导。`--hz` 是**每条连接**的速率，总速率 = `--connections × --hz`。**打玩法模式的进程要加 `--hello true`**：本工具默认不发 `C2S_HELLO`（那条不握手的路径由 `TC-GAME-060` 在核心模式上判），而玩法模式 3 秒不握手就踢，不加这个参数会 0/50 登录成功；`schema_version` 与探针同口径从 `ProtocolSchema.java` 读，不写死。长跑本身**不对应任何 TC**（压测工具，不是判据），但 `TC-034` 的负控那一步用它；服务端必须已在运行。产物 `flood.json` 里的 `achieved_packets_per_second` 是实际写进 socket 的包数 ÷ 窗口时长，登录爬坡不计入窗口；跑法与 2026-09-18 实测的 5,014.9 包/秒见 `docs/research/soak-2026-09-18/`。

- 种子数据与重置：无持久数据；每条用例前重启进程即为基线。`RegistryDataPacket` 参考样本 `src/test/resources/protocol/registry-data-26.2.bin` 在阶段 2 开始前由 `tools/protocol-probe/probe.py record-registry --out <file>` 录制（同一进程连续两次录制字节完全一致，已实测）。
- 工具：`git`、`gh`（已登录 Bearisbug）、`nc`、Python 3.10+（探针）、原版 26.2 客户端、`grep`。
- 证据目录：`docs/test-runs/`（按 `RUN-*` 分子目录，文中引用以项目根为基准的相对路径）。

### M2（游戏域）环境 —— 未验证

> 启动、就绪判定、运行时产物与系统属性四条已随 `REQ-M2-021` 实跑核对过（起服到就绪、`nc -z` 通、`stop` 退出码 0、`-Dsynaxis.app.port` 生效）。**核心模式那一档已随设计文档 v0.18 实跑核对过**（排除玩法插件时以核心模式起服、探针 `login` 与 `echo` 均退 0、`stop` 退出码 0；再加 `-Dsynaxis.app.core-mode=off` 则两行 ERROR + 退出码 1 且端口未 bind）。**地图种子与对局种子的「一局一图」那半随 `REQ-M2-010` / `REQ-M2-011` 实跑核对过**（`-Dsynaxis.app.map=rts-512` / `rts-128` / 未知 id 三档各起一次：整图 1,024 区块 137 ms 载入完成、`match_started` 早于就绪日志、未知 id 时两行 ERROR + 非零码退出 + 端口未 bind、事件台账四行齐全）。**探针的 M2 子命令已随 `REQ-M2-009` 实跑核对过**（玩法模式 + `-Dsynaxis.app.map=rts-128`：`order --count 20` 收到 20 条结果、前 10 条不含 `rate-limited` 而后 10 条全是；`order --count 8 --group 1` 8 条全不含 `rate-limited`、`--count 9 --group 1` 的第 9 条是 `rate-limited`；`select --units 1..9` 得 `synaxis:protocol-error`；`game-flood --rate 800 --seconds 3` 得 `synaxis:game-flood`（连跑 6 次全中；**原先记在这里的 `--rate 400` 那一档实测是 flaky 的，14 次挂 7 次**，失败时探针实际只达到 344～349 包/秒、服务端一行洪水 WARN 都不写，见已废弃的 `TC-GAME-025`），`--rate 299 --seconds 5`（实际 268 包/秒）与 `--rate 95 --seconds 20`（实际 75 包/秒）均全程在线；整轮服务端只写了 2 行 WARN，都带 `connection_id`、都不带栈）。**单位种子那一条已随 `REQ-M2-016` / `REQ-M2-017` 实跑核对过**（数字取自设计文档 v0.29 那一轮的实跑，即启发式带 1.15 目标偏置之后。`rts-128`：探针 `units --expect-min 10` 报 67 个单位、`commandable=[1..64, 67]`、中立与对方单位 `flags=0`；`order --unit 1 --x 84.5 --y 40 --z 64.5 --expect ok --expect-count 1 --watch 40` 收到 `code=ok snapped=(84.5, 40.0, 64.5)`，随后 69 条快照里该单位先 `moving` 后 `idle`，台账 `unit_order_issued` 的 `path_nodes=21` / `path_ms=0`、`unit_order_completed` 的 `elapsed_ms=6799`（这一项每轮都会差几十毫秒——指令落在 tick 的哪一段决定它，判据是「为正数」而不是某个具体值）；`order --x 24.5 --z 24.5`（被墙围住的口袋）回 `code=no-path` 并记一行 `unit_order_rejected`（带 `path_nodes=4001` / `path_ms=3`，即吃满 4,000 节点预算才收手），`order --x -4 --z 4`（地图外）与 `order --x 16.5 --z 16.5`（口袋墙体所在的列）各回 `code=out-of-bounds` 且台账那两行不带这两个字段（当场判定，没花搜索）；`order --count 64 --group-size 8 --unit 1 --wait 10` 打向一个单位没站着的可站立点，一次收齐 64 条结果、`ok=36 busy=28`、一条 `rate-limited` 都没有，台账里 36 条 `unit_order_issued` 正好切成 9 批、每批 4 条、批间隔 48.7～49.5 ms（一个 tick 抽 4 条），批内跨度中位数 0.58 ms、最大 1.26 ms，`path_nodes=42～44`、`path_ms=0`。默认预设 `rts-512`：`--x 456.5 --z 336.5`（东南约 215 格的空旷地，**偏置之前这一条回的是 `no-path`**）、`--x 456.5 --z 256.5`、`--x 350.5 --z 350.5` 与四个地图角（`4.5/507.5` 的四种组合，离出生点约 356 格）全部回 `code=ok`；同一个 215 格目标连发 4 轮 64 条，每轮 `ok=36 busy=28`，144 条 `unit_order_issued` 的 `path_nodes` 落在 201～206、`path_ms` 全为 0，36 批每批 4 条、批内跨度中位数 0.47 ms、P95 0.84 ms、最大 1.00 ms；打进口袋（`--x 72.5 --z 72.5`，每次搜索都吃满 4,000 节点预算）同样连发 4 轮，144 条 `unit_order_rejected` 的 `path_nodes` 全是 `4001`、`path_ms` 143 条为 0 / 1 条为 2，36 批每批 4 条、批内跨度中位数 0.77 ms、P95 1.15 ms、最大 1.76 ms（全部在 §15 的 2 ms 以内）。两个预设整轮服务端都零 WARN 零 ERROR）。**客户端模组那一条仍未验证**，它依赖尚未落地的 REQ。按 test-doc 纪律：首轮执行依赖这两条的 `TC-GAME-*` 之前，必须照本段从零走通一次，走不通先修本段并在那时去掉对应的「待 `REQ-M2-xxx` 实现后填」。不编造命令。

- 启动、就绪判定与运行时产物：同上面的引擎域环境段（`REQ-M2-021` 起两者是同一个入口）。停机：控制台 `stop` 或 SIGINT，运行期间必须保持 stdin 打开。
- 系统属性：`synaxis.app.port`（监听端口，默认 `25565`）、`synaxis.app.plugins`（启用的插件 id 列表，逗号分隔，**默认空串 = 全部**；去掉 `rts` 就没有玩法了，默认配置下进程会落进**核心模式**照常 bind，要让它拒启得同时给 `-Dsynaxis.app.core-mode=off`。列表里没有插件应答的 id 记一行 `No plugin answers to selected ids […]: discovered plugin_ids=[…].` 的 WARN，不影响启动——拼错 id 与刻意排除全部插件在行为上是同一档，只有这行日志能分开）、`synaxis.app.core-mode`（`auto` / `on` / `off`，默认 `auto`：没有玩法插件就以核心模式起服——宿主自建的平坦世界、任何连接都放行、`synaxis:main` echo 通道；`on` 强制核心模式且一个插件都不发现；`off` 永不进核心模式，没有玩法即两行 ERROR + 非零码退出。不是这三个值之一时按非法启动参数拒启。见设计文档 `API-M2-010` 启动序列第 ⑤ 步）、`synaxis.app.handshake-phase`（`configuration` / `login`，默认 `configuration`：客户端模组握手跑在哪个协议阶段，见设计文档 `ADR-M2-010` 的「落地形态与退路」；不是这两个值之一时按非法启动参数拒启）、`synaxis.app.map`（**地图预设 id**，不是文件路径——地图在进程内程序化生成，见设计文档 `REQ-M2-010`；默认 `rts-512`，另一个可用预设是 `rts-128`，未知 id 即 `map-invalid`）、`synaxis.app.units`（对局播种的单位**总数**，默认 `67` 即下面种子段的验收阵容；`:game-rts` 在插件 `onEnable` 里读它——非数字或 ≤ 3 在建图之前就拒绝，地图放不下时在一个单位都没播之前拒绝，两者都是 `Plugin failed to enable: plugin_id=rts` 的 ERROR 行（堆栈首行点名 `synaxis.app.units=<值>` 与原因）加 `API-M2-010` 第 ⑤ 步拒启的两行 ERROR、`GRADLE_EXIT` 非 0、端口不 bind。它是 `TC-GAME-068` 把全服单位数推到 1,000 的旋钮，播种规则与默认阵容相同，见设计文档 §15 表后「四条预算的测量手段」），另沿用引擎的 `synaxis.*`。**`synaxis.entity-view-distance=8`、`synaxis.chunk-view-distance=10`、`synaxis.player.chunk-update-limiter-history-size=0` 三项自 `REQ-M2-011` 起由 `server-app` 在建进程之前自己设好，命令行不写也生效**；命令行显式给的值优先（排障时可以单独摘掉某一项）。最后一项是 RTS 镜头能来回推的前置条件，不是调优，见设计文档 §15「可见范围」与 `ADR-M2-013`。
- 事件台账：`logs/events-YYYYMMDD.jsonl`（相对进程工作目录，`:server-app:run` 下即仓库根），一行一个 JSON，事件名与属性见设计文档 §20。每行形如 `{"ts":"…","event":"plugin_enabled","plugin_id":"rts","version":"0.1.0","enable_ms":16}`——事件名的键是 `event`，属性平铺在同一层。多条用例以它为观察面，步骤里统一写「`grep <事件名> logs/events-*.jsonl`」。当前落地的是 `plugin_enabled` 与对局三条（`match_created` / `match_started` / `match_ended`，见 `REQ-M2-011`），其余随各自的 REQ 补。
- 地图种子：由 `REQ-M2-010` 的**程序化生成器**在**进程内**生成，不落文件也不读文件（`.synx` 已随范围裁剪推到 M3，见设计文档 §28.3）。测试地图固定用同一个预设 id + seed，必须含：512×512 / 高度 64、若干障碍、**一块被墙完全围住的可站立区域**（TC-GAME-041 依赖）、可站立的空旷区（TC-GAME-040 依赖）。预设由 `synaxis.app.map` 选（默认 `rts-512` = 512×512 / 高度 64 / 1,024 区块，另有 `rts-128` = 128×128 / 64 区块供回滚演练与快跑用）；seed 固定在预设里（`game-rts` 的 `MapPreset`），同一预设每次启动生成同一张图。基线重置 = 重启进程（每次启动重新生成，没有需要清理的产物）。
- 对局与单位种子：进程启动即有**唯一一个** `running` 对局（M2 写死单局，且对局在 bind 之前创建，见设计文档 `API-M2-010` 启动序列）。玩家加入后由 `:game-rts` 分配 `team=1`。**阵容随 `REQ-M2-016` 落地，默认 67 个单位；总数由 `synaxis.app.units` 定（设计文档 v0.39，见上面的系统属性段），不需要预设**（`game-rts` 的 `Roster`，在 bind 之前按地图铺开，同一预设 + 同一数目每次启动的编号与站位都一样）。默认值下：`unit_id=1..64` 是玩家队伍（`team=1`）的单位，从出生点向外一圈圈铺在可站立格上；`unit_id=65` 是中立单位（`team=0`）、`unit_id=66` 属 `team=2`（两者 TC-GAME-039 依赖，站在出生点斜对角 3 格处——那两格落在 64 个本方单位已经铺满的第 3 圈上，所以它们各与一个本方单位**共用一列、叠在一起**，display 实体没有碰撞体积，依赖它们的用例判的也是归属而不是站位）；`unit_id=67` 是本方的远哨，放在出生点 +x 方向 220 格最近的可站立格上——`rts-512` 上它离出生点 ≥ 200 格（TC-GAME-043 依赖），`rts-128` 放不下 220 格，会被夹到地图边缘（约 63 格），那条用例因此只在 `rts-512` 上跑。64 个本方单位同时满足 TC-GAME-024 的「≥ 9 个」与 TC-GAME-042 的「≥ 64 个」。全部单位 `archetype=0`、`hp=max_hp=100`。**实测**（`rts-128`，探针 `units --expect-min 10`）：兴趣集 67 个单位、`commandable=[1..64, 67]`，65 与 66 的 `flags` 为 0。**`-Dsynaxis.app.units=1000` 时**编号同构：`unit_id=1..997` 本方、998 中立、999 `team=2`、1000 远哨，本方单位铺到出生点周围约 ±17 格；实测（`rts-512`，探针 `--port 25566 units --wait 6 --expect-min 999`）出生点看见 999 个（`1..999`）、`commandable` 997 个（`1..997`）——远哨 `1000` 在 220 格外，超出 128 格的实体视距，看不见就不进列表。 阶段 5 退出条件那一轮的实跑（两个预设各一轮）：`rts-128` 上 `unit_id=1` 从出生点走 20 格用了 `elapsed_ms=6949`（`archetype_speed=0.15` 格/tick、每 2 tick 推进一次 = 3.0 格/秒，算得过来），全程 70 条快照报 `moving`；`rts-512` 上同一条走法、`TC-GAME-041` 的口袋（`[64,80)²`）与两档 `out-of-bounds`、`TC-GAME-042` 的 64 条并发指令（`ok=36` / `busy=28` / `rate-limited=0`、每 tick 恰好 4 次搜索共 9 个 tick）、`TC-GAME-065` 的 215 格目标都照判据复现。
- 探针（复用 M1 的 `tools/protocol-probe/probe.py`，不另起工具）：`REQ-M2-008` 的强制握手一上线，探针必须同一交付实现 `C2S_HELLO`（设计文档 `API-M2-002` 的连带影响），否则上表六个子命令在 configuration 阶段 3 秒被踢、依赖它们的七条存量 TC 整块失效。**`C2S_HELLO` 那一半已随 `REQ-M2-008` 落地**：全部子命令进 configuration 阶段先发一次握手，另有 `handshake` / `no-hello` / `bad-schema` / `duplicate-hello` 四个子命令（见上表）。**发 `C2S_CAMERA` / `C2S_SELECT` / `C2S_MOVE_ORDER`、按指定速率发包、打印收到的 `S2C_ORDER_RESULT` 这几半已随 `REQ-M2-009` 落地**（见上表的 `order` / `select` / `game-flood` 三行）。**打印收到的 `S2C_UNIT_SPAWN` / `S2C_UNIT_STATE` / `S2C_UNIT_DESPAWN` 这一半已随 `REQ-M2-016` 落地**（见上表的 `units` 行与 `order --watch`：三条消息一律逐条打印，`units` 汇总兴趣集，`order --watch` 跟着一个单位走完 `moving → idle`）。M2 仍缺的能力：记账收到的区块与实体位置包——**它的子命令名与参数待 `REQ-M2-018` 实现后填**，依赖它的 `TC-GAME-*` 步骤仍写成「用探针记录……」而不写死命令行；执行时按当时的探针帮助文本翻译成命令，并把实际命令记进证据文件。
- 客户端模组：Minecraft **26.2** profile（不是 26.3）+ Fabric Loader 0.19.5 + Fabric API 0.160.0+26.2 + 本仓库 `client-fabric/` 产出的模组 jar。构建：`./gradlew publishToMavenLocal` 后 `cd client-fabric && ./gradlew build`（独立 Gradle 构建，不进 `settings.gradle.kts`）。安装：把 jar 放进该 profile 的 `mods/`。**jar 名、整合包安装步骤与当前 `schema_version` 值待 `REQ-M2-020` 实现后填**。
- 测试账号（在上表基础上新增）：

| 角色 | 账号 | 获取方式 |
| --- | --- | --- |
| ROLE-Player | 装了模组的 26.2 客户端，用户名 `tester1`、`tester2`（TC-GAME-038 需要同时两个） | 按上一条装模组后多人游戏直连 `127.0.0.1` |
| ROLE-Player（脚本） | 探针，用户名 `probe` | 同上表；M2 起必须先发 `C2S_HELLO` 才能过 configuration |
| ROLE-PluginAuthor | 仓内示例插件 + 临时探测插件（构造非法 id / `api_version` 不匹配 / `onEnable` 抛异常 / 事件·任务·通道三层各抛一次异常等场景；**不再需要「依赖成环」与「持续抛异常触发熔断」两类**——依赖解析与熔断器已随范围裁剪推到 M3） | 以 `REQ-M2-023` 的示例插件为模板，临时插件写在 `:server-app` 的测试源集或一个临时模块里，用完删除并确认 `git status` 干净 |
| ROLE-Operator | 本机 shell + `synaxis.app.*` 系统属性 + 控制台 `stop` | 仓库 clone 即有 |

- 工具（在上表基础上新增）：`jcmd` / JFR（堆与 tick 采样）、`unzip`（查模组 jar 内容）、独立的 `client-fabric` Gradle 构建、录屏工具（TC-GAME-047）。

## 4. 用例库

### 构建与仓库

#### `TC-001` 依赖冻结后离线构建 — 对应 `REQ-001` · 级别: 冒烟 · 执行者: AI

前置：阶段 0 完成，`gradle/libs.versions.toml` 不含 `net.minestom:data`，`src/main/resources/data` 下有 39 个注册表 JSON 与 `opaque/` 下 23 个黄金 NBT。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `grep -rn "net.minestom:data\|minestomData" gradle/libs.versions.toml build.gradle.kts` | 退出码 1，无输出 |
| 2 | `grep -n "requires net.minestom.data" src/main/java/module-info.java` | 退出码 1，无输出 |
| 3 | `find src/main/resources/data -name '*.json' \| wc -l` 与 `ls src/main/resources/data/opaque/*.nbt \| wc -l` | JSON 输出 `39`，NBT 输出 `23`（REQ-007 后 28 个不透明注册表中 23 个改为黄金 NBT，其余并入或删除） |
| 4 | `unset JAVA_TOOL_OPTIONS` 后执行 `./gradlew check --offline --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | stdout 含 `BUILD SUCCESSFUL`，退出码 0 |

后置：恢复代理。

#### `TC-002` 仓库独立 — 对应 `REQ-002` · 级别: 冒烟 · 执行者: AI

前置：本地仓库位于 `synaxis/`。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `git remote -v` | 只有 `origin`，两行均为 `https://github.com/Bearisbug/synaxis.git` |
| 2 | `gh repo view Bearisbug/synaxis --json isFork --jq .isFork` | 输出 `false` |
| 3 | `gh repo view Bearisbug/Minestom 2>&1` | stderr 含 `Could not resolve to a Repository`，退出码非 0 |
| 4 | `git log --format=%h \| grep -c 16842f1954` | 输出 `1` |
| 5 | `git branch --show-current` | 输出 `main` |

后置：无。

#### `TC-003` 止血基线全量测试 — 对应 `REQ-003` · 级别: 冒烟 · 执行者: AI

**已废弃，被 `TC-029` 取代**（RUN-013 暴露第 2 步阈值 2,659 是阶段 0 的数量，阶段 2 按 REQ-005 删除子系统连带删除其测试后为 2,602，RUN-008 起各轮只按 0 失败判定而未对照阈值；阈值改为 2,602 基线属语义修正故换 ID）。

前置：`main` 分支，JDK 25。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `./gradlew :test --continue --rerun-tasks --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | stdout 含 `BUILD SUCCESSFUL` |
| 2 | 统计 `build/test-results/test/*.xml` 的 `failures` 与 `errors` 属性之和 | 为 `0`，`tests` 之和 ≥ 2,659 |

后置：`rm -rf $TMPDIR/minestom-test-world-*`（阶段 0 完成 REQ-012 后不再需要）。

#### `TC-029` 止血基线全量测试 — 对应 `REQ-003` · 级别: 冒烟 · 执行者: AI

前置：`main` 分支，JDK 25。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `./gradlew :test --continue --rerun-tasks --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | stdout 含 `BUILD SUCCESSFUL` |
| 2 | 统计 `build/test-results/test/*.xml` 的 `failures` 与 `errors` 属性之和 | 为 `0`，`tests` 之和 ≥ 2,599（根模块基线；RUN-012 记录的 2,602 含 `testing` 模块 3 个，第 1 步的 `:test` 不跑该模块。数量减少必须能追溯到设计文档登记的删除项） |

后置：无。

#### `TC-004` 从 tick 线程停服不死锁 — 对应 `REQ-003` · 级别: 回归 · 执行者: AI

前置：进程已启动并就绪（§3）。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 用探针 `login` 进入 play 状态，然后在服务端 stdin 输入 `stop` | 进程在 10 秒内退出，退出码 0 |
| 2 | 查看 stdout 最后 20 行 | 含关闭完成日志行，无 `deadlock`、无线程转储 |

后置：无。

#### `TC-005` CI 门禁拦截并发回归 — 对应 `REQ-004` · 级别: 边缘 · 执行者: AI

**已废弃，被 `TC-021` 取代**（RUN-007 暴露第 2 步预期「jcstress job 失败且日志含 FORBIDDEN」与机制不符：死锁类回归由 JUnit 回归测试拦截，jcstress 场景表达不了死锁；预期改为「CI 变红」属语义变更，故换 ID）。


前置：阶段 0 完成，CI 含 jcstress job；本地有推送权限。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 新建分支 `tmp/ci-jcstress-probe`，把 `ThreadDispatcherImpl.shutdown()` 改回 `synchronized`，提交并推送 | 推送成功 |
| 2 | `gh run watch` 等待该分支的 CI | jcstress job 状态为 `failure`，日志含 `FORBIDDEN` |
| 3 | 删除远端与本地临时分支 | `gh api repos/Bearisbug/synaxis/branches/tmp/ci-jcstress-probe` 返回 404 |

后置：确认 `main` 未被改动。

#### `TC-021` CI 门禁拦截并发回归 — 对应 `REQ-004` · 级别: 边缘 · 执行者: AI

前置：CI 含 `check` 与 jcstress 两个工作流，工作流在 PR 上触发；本地有推送权限。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 新建分支 `tmp/ci-jcstress-probe`，把 `ThreadDispatcherImpl.shutdown()` 改回 `synchronized`，提交推送，并为该分支开一个草稿 PR | PR 创建成功，两个工作流开始运行 |
| 2 | 等待该分支的两个工作流完成 | 至少一个工作流结论为 `failure`；失败日志含并发回归测试名（如 `ThreadDispatcherTest > shutdownFromTickThread() FAILED`）或 jcstress 的 `FORBIDDEN` |
| 3 | 关闭 PR 并删除远端与本地临时分支 | `gh api repos/Bearisbug/synaxis/branches/tmp/ci-jcstress-probe` 返回 404 |

后置：确认 `main` 未被改动。

#### `TC-006` CI 门禁拦截分层违规 — 对应 `REQ-004` · 级别: 边缘 · 执行者: AI

前置：同 TC-005，CI 含 ArchUnit 规则。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 新建分支 `tmp/ci-arch-probe`，在 `coordinate` 包任一类加 `import ...entity.Entity;` 并引用，提交推送，并为该分支开一个草稿 PR（工作流只在 `main` 与 PR 上触发） | 推送成功，工作流开始运行 |
| 2 | `gh run watch` | `check` job 状态为 `failure`，日志含 ArchUnit 规则名 |
| 3 | 删除临时分支 | 远端分支 404 |

后置：无。

### 删除与注册表

#### `TC-007` 删除后协议不变且规模达标 — 对应 `REQ-005`、`REQ-006`、`REQ-007` · 级别: 冒烟 · 执行者: AI

**已废弃，被 `TC-020` 取代**（REQ-008 把 49 个生成文件并入 `src/main/java` 后第 3 步阈值口径变化；第 5 步按 REQ-006 v0.6 允许 `other/` 保留两个类）。


前置：`src/test/resources/protocol/registry-data-26.2.bin` 为阶段 2 开始前录制的样本；阶段 2 完成，进程已就绪。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `python3 tools/protocol-probe/probe.py record-registry --out /tmp/registry-now.bin` | 退出码 0，文件生成 |
| 2 | `cmp src/test/resources/protocol/registry-data-26.2.bin /tmp/registry-now.bin` | 无输出，退出码 0 |
| 3 | `find src/main/java -name '*.java' \| wc -l` | ≤ 1,100 |
| 4 | `ls src/main/java/net/minestom/server/entity/pathfinding src/main/java/net/minestom/server/entity/ai src/main/java/net/minestom/server/snapshot 2>&1` | 三个目录均 `No such file or directory` |
| 5 | `ls src/main/java/net/minestom/server/entity/metadata/` | 只含 `display`、`avatar`、`projectile`、`item` 四个子目录和 8 个基类文件 |
| 6 | `./gradlew check --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | `BUILD SUCCESSFUL` |

后置：`rm /tmp/registry-now.bin`。

#### `TC-020` 删除后协议不变且规模达标 — 对应 `REQ-005`、`REQ-006`、`REQ-007`、`REQ-008` · 级别: 冒烟 · 执行者: AI

前置：`src/test/resources/protocol/registry-data-26.2.bin` 为阶段 2 开始前录制的样本；阶段 2 完成，进程已就绪。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `python3 tools/protocol-probe/probe.py record-registry --out /tmp/registry-now.bin` | 退出码 0，文件生成 |
| 2 | `cmp src/test/resources/protocol/registry-data-26.2.bin /tmp/registry-now.bin` | 无输出，退出码 0 |
| 3 | `find src/main/java -name '*.java' \| wc -l` | ≤ 1,150（含 49 个由 REQ-008 并入的原生成文件） |
| 4 | `ls src/main/java/io/github/bearisbug/synaxis/entity/pathfinding src/main/java/io/github/bearisbug/synaxis/entity/ai src/main/java/io/github/bearisbug/synaxis/snapshot 2>&1` | 三个目录均 `No such file or directory` |
| 5 | `ls src/main/java/io/github/bearisbug/synaxis/entity/metadata/` 与 `ls src/main/java/io/github/bearisbug/synaxis/entity/metadata/other/` | 前者只含根基类文件、`package-info.java` 与 `avatar`、`display`、`item`、`projectile`、`other` 五个子目录；后者只含 `ArmorStandMeta.java`、`InteractionMeta.java`、`package-info.java` |
| 6 | `./gradlew check --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | `BUILD SUCCESSFUL` |

后置：`rm /tmp/registry-now.bin`。

#### `TC-008` 不透明注册表拒绝写入 — 对应 `REQ-007` · 级别: 回归 · 执行者: AI

前置：阶段 2 完成。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 运行集成测试 `OpaqueRegistryFrozenTest`（调用 `Registries.opaque("minecraft:enchantment")` 后尝试 `register`） | 测试通过，断言捕获 `IllegalStateException` |
| 2 | 在探针 `login` 后抓取 `RegistryDataPacket` 中 `minecraft:enchantment` 段 | 条目数与 `src/main/resources/data/opaque/enchantment.nbt` 中的条目数相等（43） |

后置：无。

#### `TC-009` 生成链收编 — 对应 `REQ-008` · 级别: 回归 · 执行者: AI

前置：阶段 2 完成。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `ls code-generators demo 2>&1` | 两者均 `No such file or directory` |
| 2 | `ls src/autogenerated 2>&1` | `No such file or directory` |
| 3 | `grep -n "autogenerated" build.gradle.kts` | 退出码 1，无输出 |
| 4 | `ls server-app/src/main/java/io/github/bearisbug/synaxis/app/Main.java` | 文件存在（最小启动器；`REQ-M2-021` 起由 `server-app` 承接，`smoke` 已删除） |
| 5 | `./gradlew check --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | `BUILD SUCCESSFUL` |

后置：无。

### 命名与合规

#### `TC-010` 命名空间改名完整 — 对应 `REQ-009` · 级别: 冒烟 · 执行者: AI

**已废弃，被 `TC-028` 取代**（RUN-013 暴露第 4 步预期「5 行中无一行指向改名提交」过严：git 对无法映射到父提交的行会归到被忽略的提交，本次第 2 行的空行即如此，内容行全部正确穿透；预期改为「非空行不指向改名提交」属语义修正故换 ID）。

前置：阶段 3 改名提交已合入。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `grep -rn "net\.minestom" --include='*.java' --include='*.kts' --include='*.toml' . \| grep -v "net\.minestom\.data" \| wc -l` | 输出 `0` |
| 2 | `cat .git-blame-ignore-revs` | 含改名提交的完整 SHA |
| 3 | `git config blame.ignoreRevsFile` | 输出 `.git-blame-ignore-revs` |
| 4 | `git blame -L 1,5 src/main/java/io/github/bearisbug/synaxis/ServerProcess.java`（包名以设计文档 §28 拍板为准） | 5 行中无一行指向改名提交 |
| 5 | `ls src/main/resources/META-INF/native-image/` | 目录名已改为新坐标，不含 `net.minestom` |

后置：无。

#### `TC-028` 命名空间改名完整 — 对应 `REQ-009` · 级别: 冒烟 · 执行者: AI

前置：改名提交已合入（`net.minestom.server` → `dev.gameengine` → `io.github.bearisbug.synaxis`）。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `grep -rn "net\.minestom" --include='*.java' --include='*.kts' --include='*.toml' . \| grep -v "net\.minestom\.data" \| wc -l` | 输出 `0` |
| 2 | `cat .git-blame-ignore-revs` | 含 `e7eac031ab` 与 `15f619f46d` 两次改名提交的完整 SHA |
| 3 | `git config blame.ignoreRevsFile` | 输出 `.git-blame-ignore-revs` |
| 4 | `git -c blame.markUnblamableLines=true blame -L 1,5 src/main/java/io/github/bearisbug/synaxis/ServerProcess.java` | 非空行无一行指向任一改名提交；空行允许带 `*`（git 无法把该行映射到父提交时归到被忽略的提交，属固有行为） |
| 5 | `ls src/main/resources/META-INF/native-image/` | 目录名为 `io.github.bearisbug.synaxis`，不含 `net.minestom`、`dev.gameengine` |

后置：无。

#### `TC-011` Apache-2.0 合规文件 — 对应 `REQ-010` · 级别: 回归 · 执行者: 皆可

前置：阶段 3 完成。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `git diff 16842f1954 HEAD -- LICENSE \| wc -l` | 输出 `0` |
| 2 | `cat NOTICE` | 含 `Minestom`、`https://github.com/Minestom/Minestom`、`16842f1954`、`Apache License, Version 2.0` 四个字样 |
| 3 | `head -20 README.md` | 含「派生自 Minestom」与「已做大量修改」的显著声明 |
| 4 | `grep -n "minestom.net\|Minestom/Minestom" build-src/src/main/kotlin/*.publishing*.kts` | 退出码 1，无输出 |

后置：无。

### 协议冒烟

#### `TC-012` 原版客户端登录与 echo 往返 — 对应 `REQ-011` · 级别: 冒烟 · 执行者: 人工

**已废弃，被 `TC-030` 取代**（`REQ-M2-021` 删除了 `smoke`，第 2 步依赖的 `/echo` 命令与 `synaxis:main` 通道一起消失——`synaxis` 是宿主保留的通道命名空间，通道归插件所有，见 `REQ-M2-006` 与 §7 遗留问题；替代用例去掉 echo 那一档，只判登录、日志关联与断开。步骤实质变更，按惯例换 ID）。

前置：进程已就绪；原版 26.2 客户端已安装；客户端开启 F3 调试界面。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 多人游戏直连 `127.0.0.1` | 5 秒内进入世界，看到平坦地面，F3 显示服务端品牌为我们的名称 |
| 2 | 在聊天框输入 `/echo ping`（`smoke` 启动器把该命令转成 `synaxis:main` echo 负载） | 1 秒内聊天框出现 `echo: ping` |
| 3 | 查看服务端 stdout | 含一行带 `connection_id` 的登录日志和一行 echo 日志，两者 `connection_id` 相同 |
| 4 | 客户端断开 | 服务端 stdout 出现同一 `connection_id` 的断开日志，进程继续运行 |

后置：无。

#### `TC-030` 原版客户端登录与断开 — 对应 `REQ-011` · 级别: 冒烟 · 执行者: 人工

前置：`server-app` 已就绪（`:game-rts` 的平坦对局）；原版 26.2 客户端已安装；客户端开启 F3 调试界面。**本用例的前置在 `REQ-M2-008`（强制客户端模组握手）合入后不再成立**，届时由 M2 客户端模组侧的用例接替，本用例随之废弃。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 多人游戏直连 `127.0.0.1` | 5 秒内进入世界，看到平坦地面，F3 显示服务端品牌为我们的名称 |
| 2 | 查看服务端 stdout | 含一行带 `connection_id` 的登录日志；该连接没有 `Refusing a connection` 行，也没有 `spawningInstance` 相关的 `NullPointerException`（准入回调放行，见设计文档 `API-M2-011`） |
| 3 | 客户端断开 | 服务端 stdout 出现同一 `connection_id` 的断开日志，进程继续运行 |
| 4 | 向服务端 stdin 输入 `stop` | 进程 15 秒内退出、退出码 0；停机日志里 `Disabling plugin: plugin_id=rts` 出现在引擎的 `synaxis server stopped successfully.` 之前 |

后置：`rm -rf plugins logs`。

#### `TC-031` configuration 阶段早退的连接不留线程也不留玩家 — 对应 `REQ-017` · 级别: 回归 · 执行者: AI

前置：`SOAK-1` / `SOAK-2` 的修复已合入（设计文档 §28 两条「已决」）；`jcmd` 在 `PATH` 上。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `./gradlew :test --tests '*ConfigurationKnownPacksIntegrationTest*' --tests '*ConfigurationResourcePackIntegrationTest*' --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | `BUILD SUCCESSFUL`；三条用例全过——`configurationStopsWaitingForKnownPacksWhenTheClientLeaves`（断线释放 known-packs 等待）、`aClientGoneBeforeConfigurationStartsLeavesNothingBehind`（断线先于插入时不再往 `configurationPlayers` 里塞人）与 F02 原有的资源包那条 |
| 2 | 起核心模式服务端（§3 启动段，`-Dsynaxis.app.core-mode=on`），记下 pid | stdout 出现 `synaxis server-app listening on 0.0.0.0:25565` |
| 3 | `python3 tools/loadtest/soak.py run --connections 0 --duration 90 --attacks premature-finish --attack-interval 0.02 --out <目录>`，`duration` 到点的时刻记为零点 | `summary.json` 落盘，攻击次数为百量级 |
| 4 | 零点 +15 秒 `jcmd <pid> Thread.dump_to_file -format=json <文件>`，数含 `doConfiguration` 栈帧的线程 | 为 `0`（修复前该值等于攻击中赛跑赢在等待侧的那一部分，并保持到「最后一次连接 + `knownPacksResponseTimeout`」，默认 5 分钟） |
| 5 | 同一时刻 `jcmd <pid> GC.class_histogram \| grep -E 'synaxis.entity.Player$\|PlayerSocketConnection$'` | 两个类的实例数都是 `0`（`class_histogram` 自带一次 Full GC；修复前稳定残留约 1% 的攻击次数，且随轮次累积） |
| 6 | `grep -c 'failed to respond to known packs' <服务端日志>`；命中时看行内容 | 计数为 `0`（客户端先走了的那一档不再走超时分支）；若因别的原因命中，该行带 `connection_id`（§3 日志关联口径） |

后置：`stop` 停机，删掉 `--out` 目录与线程转储文件，确认没有残留 java 进程。

#### `TC-032` 重复 UUID 的第二条连接被拒，且不留未注册玩家 — 对应 `REQ-011` · 级别: 回归 · 执行者: AI

前置：`SOAK-3` 的修复已合入（设计文档 §28 的「已决」一条）；引擎为默认离线模式（UUID 由用户名派生）；`docs/research/soak-2026-09-18/README.md` 的核心模式启动段可用。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `./gradlew :test --tests '*DuplicateUuidLoginIntegrationTest*' --tests '*FailedSpawnRegistrationIntegrationTest*' --tests '*EntityTrackerUnregisteredMoveTest*' --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | `BUILD SUCCESSFUL`；六条全过——同 UUID 的第二次登录拿到 `synaxis:duplicate-login`、并发两条恰好一条拿到 `Player`、旧连接被摘掉后 UUID 立刻可用、注册被拒的实体让 spawn future 异常完成、注册失败的玩家收到 `synaxis:internal-error` 并在下一 tick 离开世界、一万次「移动未注册实体」不是一万行日志 |
| 2 | 起核心模式服务端（§3 启动段，`-Dsynaxis.app.core-mode=on`），把服务端 stdout 重定向到一个日志文件 | stdout 出现 `synaxis server-app listening on 0.0.0.0:25565` |
| 3 | **同时**跑两个 `python3 tools/loadtest/soak.py run --connections 25 --duration 200 --move-hz 10 --echo-hz 0 --no-jfr --out <各自目录>`（`soak.py` 的用户名固定为 `soak0000..soak0024`，两个进程必然撞名） | 两个进程退出码 0；先起的那个 `summary.json` 里 `connected_peak` 为 25，后起的那个 `login_failures` 记满 25 条 `synaxis:duplicate-login` |
| 4 | `grep -c 'There is already an entity registered with uuid' <服务端日志>` | `0`（修复前等于撞名的连接数） |
| 5 | `grep -c 'Attempted to move unregistered entity' <服务端日志>` | `0`；即使因别的原因命中，也是每 10 秒至多一行、行内带区间累计次数（修复前是每个移动包一行，实测 200 秒 47,350 行） |
| 6 | `wc -c <服务端日志>` | 与一次同规模的不撞名跑同量级（修复前是 6.3 MB） |

后置：`stop` 停机，删掉两个 `--out` 目录与服务端日志，确认没有残留 java 进程。

#### `TC-033` 客户端可驱动的日志有界，且门禁拦得住新加的 — 对应 `REQ-011` · 级别: 回归 · 执行者: AI

**已废弃，被 `TC-035` 取代**（范围只到引擎根模块：第 1 步的三条测试与第 2 步的反证都只碰 `ArchitectureTest`，而同形状的逐包日志在宿主 `:server-app` 里也有，门禁也另起了一条。判据的覆盖面实质变更，故换 ID）。

前置：`docs/DESIGN.md` §15 的「日志量」一行与 §28 的清点结论已合入；工作副本干净。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `./gradlew :test --tests '*PacketReadingUnreadBytesTest*' --tests '*PreAuthConnectionIntegrationTest*' --tests '*ArchitectureTest*' --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | `BUILD SUCCESSFUL`；`PacketReadingUnreadBytesTest` 里 10,001 个尾部带多余字节的包只换来一行日志（第一次输出后累计计数归零，之后 10,000 次全部积压在计数器里）；`PreAuthConnectionIntegrationTest#aKickStopsTheRestOfTheBatchFromBeingHandled` 里一次读上来的 1,000 个必失败的登录插件应答只换来 1 次上报（kick 之后这一批剩下的帧不再派发）；`ArchitectureTest` 七条全过，含 `clientDrivenPathsDoNotWriteALinePerPacket` 与反证它会红的 `theClientDrivenLogRuleRejectsALinePerPacket`、`theClientDrivenLogRuleRejectsAReportPerPacket` |
| 2 | 在 `src/main/java/io/github/bearisbug/synaxis/listener/PlayerPositionListener.java` 里加两行 `import org.slf4j.Logger;` / `import org.slf4j.LoggerFactory;`、类内加一行 `private static final Logger LOGGER = LoggerFactory.getLogger(PlayerPositionListener.class);`，再在 `playerPacketListener` 方法体首行插一句 `LOGGER.warn("x");`，重跑第 1 步的命令（**不要写成全限定名** `org.slf4j.LoggerFactory.getLogger("t").warn("x")`：Error Prone 的 `UnnecessarilyFullyQualified` 在 `-Werror` 下会让 `:compileJava` 先失败，门禁根本跑不到） | `clientDrivenPathsDoNotWriteALinePerPacket` 失败，消息里点名 `PlayerPositionListener.playerPacketListener(...) calls method <org.slf4j.Logger.warn(java.lang.String)>` |
| 3 | `git checkout -- src/main/java/io/github/bearisbug/synaxis/listener/PlayerPositionListener.java`，重跑第 1 步的命令 | 回到 `BUILD SUCCESSFUL` |

后置：确认 `git status` 干净，没有残留 java 进程。

#### `TC-035` 客户端可驱动的日志有界（引擎与宿主两个模块），且两边的门禁都拦得住新加的 — 对应 `REQ-011` · 级别: 回归 · 执行者: AI

前置：`docs/DESIGN.md` §15 的「日志量」一行与 §28 的两条清点结论已合入；工作副本干净。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `./gradlew :test --tests '*PacketReadingUnreadBytesTest*' --tests '*PreAuthConnectionIntegrationTest*' --tests '*ArchitectureTest*' --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | `BUILD SUCCESSFUL`；`PacketReadingUnreadBytesTest` 里 10,001 个尾部带多余字节的包只换来一行日志（第一次输出后累计计数归零，之后 10,000 次全部积压在计数器里）；`PreAuthConnectionIntegrationTest#aKickStopsTheRestOfTheBatchFromBeingHandled` 里一次读上来的 1,000 个必失败的登录插件应答只换来 1 次上报（kick 之后这一批剩下的帧不再派发）；`ArchitectureTest` 七条全过，含 `clientDrivenPathsDoNotWriteALinePerPacket` 与反证它会红的 `theClientDrivenLogRuleRejectsALinePerPacket`、`theClientDrivenLogRuleRejectsAReportPerPacket` |
| 2 | `./gradlew :server-app:test --tests '*PluginPayloadLogBudgetIntegrationTest*' --tests '*LogBudgetTest*' --tests '*EventLedgerTest*' --tests '*ClientDrivenLogArchitectureTest*' --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | `BUILD SUCCESSFUL`；`PluginPayloadLogBudgetIntegrationTest` 四条——前三条各用 10,000 次触发只换来 1 行（未注册频道的丢弃行、频道 handler 抛异常、插件事件监听器抛异常），第四条钉住预算的粒度：两个都会抛的频道，对第一个刷 10,000 次之后，第二个的第一次失败仍写出自己那条带栈的 ERROR（一共 2 行，第二行带 `plugin_id=quiet`）；`EventLedgerTest` 一条——台账目录不可写时，10,000 次 `record` 只换来 1 行 WARN 且那一行仍带着栈（插件按 `DESIGN-M2` §20 逐条指令记事件，频率是客户端定的）；`LogBudgetTest` 两条——被压掉的 10,000 次由下一个区间的那一行报出来（`(10001 payloads since the last report)`）、写出去的那一条仍带着栈；`ClientDrivenLogArchitectureTest` 两条，含 `hostPathsDoNotWriteALinePerPacket` 与反证它会红的 `theHostLogRuleRejectsALinePerPayload` |
| 3 | 在 `src/main/java/io/github/bearisbug/synaxis/listener/PlayerPositionListener.java` 里加两行 `import org.slf4j.Logger;` / `import org.slf4j.LoggerFactory;`、类内加一行 `private static final Logger LOGGER = LoggerFactory.getLogger(PlayerPositionListener.class);`，再在 `playerPacketListener` 方法体首行插一句 `LOGGER.warn("x");`，重跑第 1 步的命令（**不要写成全限定名** `org.slf4j.LoggerFactory.getLogger("t").warn("x")`：Error Prone 的 `UnnecessarilyFullyQualified` 在 `-Werror` 下会让 `:compileJava` 先失败，门禁根本跑不到） | `clientDrivenPathsDoNotWriteALinePerPacket` 失败，消息里点名 `PlayerPositionListener.playerPacketListener(...) calls method <org.slf4j.Logger.warn(java.lang.String)>` |
| 4 | `git checkout -- src/main/java/io/github/bearisbug/synaxis/listener/PlayerPositionListener.java`，重跑第 1 步的命令 | 回到 `BUILD SUCCESSFUL` |
| 5 | 在 `server-app/src/main/java/io/github/bearisbug/synaxis/app/PluginChannelRouter.java` 的 `route` 方法体首行插一句 `LOGGER.warn("A payload arrived on {}", event.getIdentifier());`（该类已有 `LOGGER` 字段，不必加 import），重跑第 2 步的命令 | `hostPathsDoNotWriteALinePerPacket` 失败，消息里点名 `Method <io.github.bearisbug.synaxis.app.PluginChannelRouter.route(io.github.bearisbug.synaxis.event.player.PlayerPluginMessageEvent)> calls method <org.slf4j.Logger.warn(java.lang.String, java.lang.Object)>`（后面跟的 `in (PluginChannelRouter.java:N)` 是插入行的行号，随文件变动，不作判据）——**插在已经有限流日志的那个类、那个方法里也会红**，宿主的豁免是给整类没有客户端驱动路径的类留的，`PluginChannelRouter` 不在其中 |
| 6 | `git checkout -- server-app/src/main/java/io/github/bearisbug/synaxis/app/PluginChannelRouter.java`，重跑第 2 步的命令 | 回到 `BUILD SUCCESSFUL` |

后置：确认 `git status` 干净，没有残留 java 进程。

#### `TC-034` 填满包队列的断线带得住 §14 的错误码 — 对应 `REQ-011` · 级别: 回归 · 执行者: AI

前置：`SOAK-4` 的修复已合入（设计文档 §28 的 `SOAK-6` 一行记着同形状的其余待办）；`docs/research/soak-2026-09-18/README.md` 的核心模式启动段与入站包发包端可用。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `./gradlew :test --tests '*DisconnectReasonsIntegrationTest*' --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | `BUILD SUCCESSFUL`；七条全过，含 `aFloodedPacketQueueDisconnectsWithAProtocolError`——把超过 `playerPacketQueueSize` 的包塞进 `Player#addPacketToQueue` 后玩家离线，且**恰好一个** disconnect 包、其 `translate` 键为 `synaxis:protocol-error`（修复前这条断言拿到的是 `TextComponentImpl`） |
| 2 | 起核心模式服务端（§3 启动段，`-Dsynaxis.app.core-mode=on`），把服务端 stdout 重定向到一个日志文件 | stdout 出现 `synaxis server-app listening on 0.0.0.0:25565` |
| 3 | `java tools/loadtest/PacketFlood.java --connections 50 --hz 1200 --seconds 30 --out <目录>`（每条连接 1,200 包/秒，越过 `playerPacketPerTick` 默认 50 × 20 TPS = 1,000 包/秒的排空能力，队列必满） | 十秒内 50 条连接全断，`flood.json` 的 `connections_lost` 为 `50`；`disconnect_reasons` 里只有 `synaxis:protocol-error` 这一个键（实测 50；条数可少于 50——有的连接是本地 socket 先关掉、没读到断线包，属正常竞争） |
| 4 | `grep -c 'reason=synaxis:protocol-error' <服务端日志>` | `50`，一条被踢的连接一行（行格式见 §3 日志关联口径） |
| 5 | `grep -c 'Kicking connection_id=' <服务端日志>` | 与第 4 步相等（`50`）。`PlayerConnection#kick` 只对 `TranslatableComponent` 写这一行，所以修复前两个计数**都是 0**：纯文本原因既不进客户端看到的错误码，也不在服务端留一行 |

后置：`stop` 停机，删掉 `--out` 目录与服务端日志，确认没有残留 java 进程。

#### `TC-013` 抢跑 FinishConfiguration 被拒绝 — 对应 `REQ-011` · 级别: 回归 · 执行者: AI

**已废弃，被 `TC-019` 取代**（RUN-004 暴露第 2 步预期「每秒收到 KeepAlive」与引擎事实不符：`KEEP_ALIVE_DELAY` 为 10 秒，设计文档未规定间隔；预期改为「往返不中断」属语义变更，故换 ID）。


前置：进程已就绪；同时用探针 `login` 保持一个正常玩家在线。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `python3 tools/protocol-probe/probe.py premature-finish` | stdout 含 `Disconnect` 且原因键为 `synaxis:protocol-error` |
| 2 | 观察正常玩家的探针连续 3 秒的 KeepAlive 往返 | 每秒收到 KeepAlive，无中断 |
| 3 | 查看服务端 stdout | 含该连接 `connection_id` 的 `protocol-error` 日志，无异常堆栈 |

后置：无。

#### `TC-019` 抢跑 FinishConfiguration 被拒绝，其他玩家不受影响 — 对应 `REQ-011` · 级别: 回归 · 执行者: AI

前置：进程已就绪；同时用探针 `--username holder login --hold 14` 保持一个正常玩家在线。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `python3 tools/protocol-probe/probe.py premature-finish` | stdout 含 `Disconnect reason=synaxis:protocol-error`，退出码 0 |
| 2 | 等 holder 探针的 14 秒保持期结束，查看其 stdout | 至少一次 `recv KeepAlivePacket` 与对应的 `sent KeepAlive`，无 `unexpected disconnect`，`exit code: 0` |
| 3 | 查看服务端 stdout | 含抢跑连接 `connection_id` 的 `reason=synaxis:protocol-error` 日志，无异常堆栈 |

后置：无。

#### `TC-014` 非法协议版本被拒绝 — 对应 `REQ-011` · 级别: 回归 · 执行者: AI

前置：进程已就绪。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `python3 tools/protocol-probe/probe.py bad-version --protocol 775` | stdout 含 `LoginDisconnect` 且原因键为 `synaxis:unsupported-version` |
| 2 | `python3 tools/protocol-probe/probe.py bad-version --protocol 777` | 同上 |
| 3 | `python3 tools/protocol-probe/probe.py login --protocol 776` | stdout 含 `LoginSuccess` |

后置：无。

#### `TC-017` 零长帧与超深递归被断线 — 对应 `REQ-011` · 级别: 边缘 · 执行者: AI

**已废弃，被 `TC-027` 取代**（REQ-017 让非创造玩家的创造包整包跳过，第 2 步「连接被关闭」不再成立；递归深度守卫改由单元测试覆盖）。


前置：进程已就绪。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `python3 tools/protocol-probe/probe.py zero-frame`（登录后发送单字节 `0x00`） | 连接被服务端关闭，stdout 含 `synaxis:protocol-error` |
| 2 | `python3 tools/protocol-probe/probe.py deep-recursion --depth 200`（创造模式设槽包携带 200 层自嵌套药水效果） | 连接被关闭，服务端 stdout 无 `StackOverflowError` |
| 3 | 探针 `login` 再连一次 | `LoginSuccess`，进程仍在运行 |

后置：无。

#### `TC-027` 零长帧断线、创造包被跳过 — 对应 `REQ-011`、`REQ-017` · 级别: 边缘 · 执行者: AI

前置：进程已就绪。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `python3 tools/protocol-probe/probe.py zero-frame` | 连接被服务端关闭，stdout 含 `synaxis:protocol-error` |
| 2 | `python3 tools/protocol-probe/probe.py deep-recursion --depth 200` | 探针退出码 0：连接保持并收到后续 KeepAlive；服务端 stdout 无 `StackOverflowError` |
| 3 | 探针 `login --hold 1` 再连一次 | `LoginSuccess`，进程仍在运行 |
| 4 | 查看服务端 stdout 中第 1 步的记录 | 一行 `WARN` 带 `connection_id` 与原因，无堆栈行（以 `at ` 开头的行为 0） |

后置：无。

#### `TC-026` 分配攻击与 NBT 炸弹被拒绝 — 对应 `REQ-017` · 级别: 回归 · 执行者: AI

前置：进程已就绪。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `python3 tools/protocol-probe/probe.py alloc-bomb` | stdout 含 `Disconnect reason=synaxis:protocol-error` |
| 2 | `python3 tools/protocol-probe/probe.py nbt-bomb --mib 3` | stdout 含 `Disconnect reason=synaxis:protocol-error` |
| 3 | 查看服务端 stdout | 两次断线各一行 `WARN` 带 `connection_id`，无堆栈行 |
| 4 | 探针 `login --hold 1` | `LoginSuccess`，进程仍在运行 |

后置：无。

#### `TC-018` stop 在 10 秒内退出 — 对应 `REQ-011` · 级别: 回归 · 执行者: AI

前置：进程已就绪，探针 `login` 保持 3 个连接。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 记录时间，向服务端 stdin 输入 `stop` | 三个探针均收到原因键 `synaxis:server-stopping` 的 Disconnect |
| 2 | 等待进程退出并记录时间 | 用时 < 10 秒，退出码 0 |

后置：无。

### 运行手册与卫生

#### `TC-015` 测试不留临时目录 — 对应 `REQ-012` · 级别: 回归 · 执行者: AI

前置：阶段 0 完成。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `ls -d $TMPDIR/minestom-test-world-* 2>/dev/null \| wc -l` | 输出 `0`（起点） |
| 2 | `./gradlew :test --rerun-tasks --no-build-cache --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | `BUILD SUCCESSFUL` |
| 3 | 重复第 1 步 | 输出 `0` |

后置：无。

#### `TC-016` 运行手册冷启动 — 对应 `REQ-012` · 级别: 回归 · 执行者: 人工

前置：一台未装 JDK 25、未配置镜像的 macOS 机器，仅有 git 与 Homebrew。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | clone 仓库，只按 `docs/ENGINE.md` 的步骤操作，不查阅其他资料 | 每一步都有可执行命令，无「问一下」类表述 |
| 2 | 执行手册中的构建命令 | `BUILD SUCCESSFUL` |
| 3 | 执行手册中的启动命令并用 `nc -z 127.0.0.1 25565` 探活 | 退出码 0 |

后置：无。

### M1.5 门面与配置

#### `TC-022` 静态门面实例化后测试可并行 — 对应 `REQ-013` · 级别: 回归 · 执行者: AI

**已废弃，被 `TC-023` 取代**（RUN-009 暴露第 1 步的 grep 把 `MinecraftServer.TICK_MS` / `PROTOCOL_VERSION` 这类协议常量也计入；REQ-013 的目标是静态**访问器**清零，常量允许保留，属语义修正故换 ID）。


前置：`main` 分支，JDK 25；`src/test/resources/junit-platform.properties` 已开启类级并行。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `grep -rn "MinecraftServer\." src/main/java --include='*.java' \| grep -v MinecraftServer.java \| wc -l` | 输出 `0` |
| 2 | `./gradlew :test --rerun-tasks --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts -Djunit.jupiter.execution.parallel.enabled=false` 并记录 `:test` 任务耗时 | `BUILD SUCCESSFUL`，0 失败 |
| 3 | 同上命令去掉 `-D` 参数（使用仓库配置的并行）并记录耗时 | `BUILD SUCCESSFUL`，0 失败，`:test` 耗时低于第 2 步 |
| 4 | `./gradlew :test --tests '*CommandPacketFilteringTest*' --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | `BUILD SUCCESSFUL`（单跑通过） |

后置：无。

#### `TC-023` 静态门面访问器清零且测试可并行 — 对应 `REQ-013` · 级别: 回归 · 执行者: AI

前置：`main` 分支，JDK 25；`src/test/resources/junit-platform.properties` 已开启类级并行。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `grep -rnE "MinecraftServer\.(get[A-Z]\w*\(|process\(|init\()" src/main/java --include='*.java' \| grep -v MinecraftServer.java \| wc -l` | 输出 `0`（协议常量如 `TICK_MS`、`PROTOCOL_VERSION` 的引用不计） |
| 2 | 把 `junit-platform.properties` 的 `parallel.enabled` 临时改为 `false`，执行 `./gradlew :test --rerun-tasks --no-build-cache --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts`，记录耗时，然后还原文件 | `BUILD SUCCESSFUL`，0 失败 |
| 3 | 按仓库配置（并行）再执行同一命令并记录耗时 | `BUILD SUCCESSFUL`，0 失败，耗时低于第 2 步 |
| 4 | `./gradlew :test --tests '*CommandPacketFilteringTest*' --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | `BUILD SUCCESSFUL`（单跑通过） |

后置：`git status` 确认 `junit-platform.properties` 未改动。

#### `TC-024` 底层包不依赖上层且规则严格 — 对应 `REQ-015` · 级别: 回归 · 执行者: AI

前置：`main` 分支，REQ-015 已合入；`tools/pkgdeps.py` 在仓库内。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `python3 tools/pkgdeps.py` | 输出含 `lower layer edges into the rest of the engine: 0` |
| 2 | `grep -rn "FreezingArchRule\|archunit_store" src/test` | 退出码 1，无输出（规则已是严格规则，冻结存储已删除） |
| 3 | `./gradlew :test --tests '*ArchitectureTest*' --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | `BUILD SUCCESSFUL` |
| 4 | `ls src/main/java/io/github/bearisbug/synaxis/utils/` | 只剩通用工具类，不含 `chunk`、`block`、`entity`、`inventory`、`time`、`identity`、`location`、`position`、`callback`、`crypto` 子目录 |
| 5 | 在 `coordinate` 包任一类临时加 `import io.github.bearisbug.synaxis.entity.Entity;` 并引用，重跑第 3 步，然后撤销 | 第 3 步失败，日志含 `ArchitectureTest`；撤销后 `git status` 干净 |

后置：无。

#### `TC-025` 配置对象取代全局开关 — 对应 `REQ-014` · 级别: 回归 · 执行者: AI

前置：`main` 分支，REQ-014 已合入。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `grep -rn "ServerFlag" src testing protocol plugin-api server-app game-rts jmh-benchmarks jcstress-tests --include='*.java' --include='*.kts' \| wc -l` | 输出 `0` |
| 2 | `grep -n "minestom\." build-src/src/main/kotlin/*.gradle.kts \| grep -i "jvmArgs\|systemProperty"` | 退出码 1，无输出（测试 JVM 不再注入与生产不同的开关） |
| 3 | `./gradlew :test --tests '*ServerConfigTest*' --tests '*ServerProcessDispatcherTest*' --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | `BUILD SUCCESSFUL`：默认值与系统属性读取一致；两个进程分别按 1 与 2 个 tick 线程分区 |
| 4 | 启动 `server-app`，查看启动日志 | 含一行生效配置（`ServerConfig[...]`）与一行 `JvmFlags[...]` |

后置：`stop` 关闭 `server-app`，`rm -rf plugins logs`。

### M2 · 模块拆分与工程门禁

#### `TC-GAME-001` 四模块拆分后构建绿 — 对应 `REQ-M2-001` · 级别: 冒烟 · 执行者: AI

前置：`REQ-M2-001` 已合入；JDK 25；`git status --porcelain` 无输出。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `./gradlew projects --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | 项目树含 `:protocol`、`:plugin-api`、`:server-app`、`:game-rts` 四个子项目（`:smoke` 的删除在阶段 2 随 `REQ-M2-021` 发生，由 TC-GAME-048 判定，本用例不管它的去留） |
| 2 | `find src/main/java -name '*.java' \| wc -l` | 输出 `1109`（与 RUN-015 记录的基线相同——根模块仍是引擎且一个文件不动；数量变化必须能追溯到设计文档登记的改动） |
| 3 | `ls protocol/src/main/java/io/github/bearisbug/synaxis/game/protocol plugin-api/src/main/java/io/github/bearisbug/synaxis/plugin server-app/src/main/java/io/github/bearisbug/synaxis/app game-rts/src/main/java/io/github/bearisbug/synaxis/game/rts` | 四个目录都存在，各至少含一个 `.java` 文件 |
| 4 | `cat protocol/src/main/java/module-info.java` 与 `ls server-app/src/main/java/module-info.java game-rts/src/main/java/module-info.java 2>&1` | 前者只有 `exports`、无任何 `requires`（零第三方依赖）；后两者均 `No such file or directory`（宿主与插件跑 classpath，见 `ADR-M2-005`） |
| 5 | `./gradlew :protocol:generatePomFileForMavenPublication --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts`，再 `grep -c '<dependency>' protocol/build/publications/maven/pom-default.xml` | 输出 `0`：`:protocol` 发布出去的坐标里没有任何第三方依赖（`ADR-M2-008`、§6）。约定插件 `synaxis.java-library` 给每个模块加的 `api(libs.jetbrainsAnnotations)` 由 `protocol/build.gradle.kts` 摘掉；不摘这里是 `1`，而这个 jar 既要随 Fabric 模组发给客户端、又要在阶段 2 归档成 ABI baseline |
| 6 | `./gradlew check --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | stdout 含 `BUILD SUCCESSFUL`，退出码 0 |
| 7 | 按 `docs/ENGINE.md` §3.6 的五条命令逐个跑 `tools/pkgdeps.py`（引擎一条，四个新模块各一条，带 `--root-package` 与 `--skip-lower-layer`） | 五条都退出码 0；引擎那条含 `lower layer edges into the rest of the engine: 0`，四个新模块各打印自己的 `root package:` 与 `edges: 0`（当前各只有一个扁平包）。一次扫全仓不算数——包名同前缀，会把四个模块折叠进引擎的桶里 |

后置：无。

#### `TC-GAME-002` 引擎依赖游戏侧时构建变红 — 对应 `REQ-M2-001`、`REQ-M2-002` · 级别: 边缘 · 执行者: AI

前置：`REQ-M2-001`、`REQ-M2-002` 已合入；工作区干净（`git status --porcelain` 无输出）。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 在根 `build.gradle.kts` 加一行 `implementation(project(":game-rts"))`，并在引擎任一类里加 `import io.github.bearisbug.synaxis.game.rts.Unit;` 并引用它 | `git status --porcelain` 输出 2 行（两处改动都落盘） |
| 2 | `./gradlew :compileJava --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | 构建失败、退出码非 0，输出含 `Circular dependency between the following tasks:` 与 `:compileJava` → `:game-rts:jar` → `:game-rts:compileJava` → `:compileJava` 的环；失败发生在编译之前，第 1 步那行 `import` 根本没被编译到（第一强制点是 Gradle 主源集循环依赖，ArchUnit 是第二道闸，见设计文档 §24） |
| 3 | `git checkout -- build.gradle.kts <被改的引擎类>`，再 `./gradlew check --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | `git status --porcelain` 无输出；stdout 含 `BUILD SUCCESSFUL` |
| 4 | `grep -n 'implementation(project(":game-rts"))' docs/DESIGN-M2.md` | 至少一处命中（演练命令记在设计文档侧：§19 Gherkin 与 §26 阶段 1 退出条件行）。扫描范围不得放宽到 `docs/`——那会命中本用例第 1 步自己写的同一串字面量，断言恒真 |

后置：确认工作区干净。

#### `TC-GAME-003` 引擎 `game` 包改名完整且禁止规则不误伤 `network.plugin` — 对应 `REQ-M2-002` · 级别: 回归 · 执行者: AI

前置：`game` → `gameevent` 改名已作为单独一个提交合入。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `ls src/main/java/io/github/bearisbug/synaxis/game 2>&1` 与 `ls src/main/java/io/github/bearisbug/synaxis/gameevent` | 前者 `No such file or directory`；后者含 `GameEvent.java`、`GameEventImpl.java`、`GameEventKeys.java`、`GameEventTags.java`、`GameEvents.java`、`package-info.java` 共 6 个文件 |
| 2 | `grep -rEn "synaxis\.game\b" src/main/java --include='*.java' \| wc -l` | 输出 `0`（引擎主源码不再有 `...synaxis.game` 前缀的引用）。词边界 `\b` 不能省——`synaxis.gamedata` 里也含 `synaxis.game` 这个子串，省掉它这一步永远判不到 `0` |
| 3 | `cat .git-blame-ignore-revs` | 含该改名提交的完整 SHA |
| 4 | `./gradlew :test --tests '*ArchitectureTest*' --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | `BUILD SUCCESSFUL`；「引擎不得依赖游戏侧、稳定面与宿主」规则通过（现状下不误命中 `io.github.bearisbug.synaxis.network.plugin`） |
| 5 | 在第 4 步同一次运行的测试报告里看 `ArchitectureTest` 的四条用例 | 含规则自测两条且均通过：`theGameSideRuleRejectsADependencyOnTheGameSide`（合成 `JavaClasses` = 引擎侧类 + 测试源集里的 `io.github.bearisbug.synaxis.game.rts.GameSideFixture`，断言规则判违规**且违规消息里点名这两个类**——ArchUnit 的 `failOnEmptyShould` 在 `that()` 一个类都选不中时抛的也是 `AssertionError`，只判异常类型会把「规则退化成空规则」也判成通过）与 `theGameSideRuleAcceptsTheEngineOwnNetworkPluginPackage`（把被引用类换成 `network.plugin` 的 `LoginPlugin`，断言规则通过） |
| 6 | `grep -rn '"\.\.plugin\.\."' src/test --include='*.java'` | 退出码 1、无输出（禁止列表必须写完整前缀，不得用 `..plugin..`）。扫的是 Java 字符串字面量而不是裸串——规则的 javadoc 会点名这个错误写法并解释它为什么会命中引擎自己的 `network.plugin`，那段警告应当留着 |

后置：无。

#### `TC-GAME-004` 新增模块的新增行受覆盖率门约束 — 对应 `REQ-M2-022` · 级别: 边缘 · 执行者: AI

前置：`REQ-M2-022` 已合入，CI 的 diff-cover 已聚合四个新模块的 jacoco 报告；本地有推送权限。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 新建分支 `tmp/ci-cov-probe`，在 `server-app` 新增一个 50 行、只被 10 行测试覆盖的类，提交推送并开草稿 PR | PR 创建成功，`CI` 工作流开始运行 |
| 2 | 等待该工作流完成 | 结论为 `failure`，日志含 diff-cover 打印的新增行覆盖率数字且低于门槛 80% |
| 3 | 关闭 PR 并删除远端与本地临时分支 | `gh api repos/Bearisbug/synaxis/branches/tmp/ci-cov-probe` 返回 404 |

后置：确认 `main` 未被改动。

#### `TC-GAME-005` 没有 baseline 与 baseline 缺失时 ABI 门的两种结局 — 对应 `REQ-M2-022` · 级别: 回归 · 执行者: AI

前置：`REQ-M2-022` 已合入。**不需要 tag**：本用例验的就是「还没有 baseline」与「baseline 该在却不在」这两半。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `ls gradle/abi-baseline.tag` | 文件不存在（`docs/ENGINE.md` §7.1：它存在才代表已有 baseline，CI 才传 `-PbaselineJarDir`）；若已存在则本步与第 2 步改为「CI 传参」形态，跳到 TC-GAME-006 第 6 步 |
| 2 | `./gradlew :plugin-api:checkBinaryCompatibility :protocol:checkBinaryCompatibility --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | 两个任务结果均为 `SKIPPED`，stdout 含「无 baseline，ABI 门未生效」字样，整体 `BUILD SUCCESSFUL` |
| 3 | `mkdir -p /tmp/abi-empty-baseline`，再 `./gradlew :plugin-api:checkBinaryCompatibility -PbaselineJarDir=/tmp/abi-empty-baseline --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | `BUILD FAILED`，报 `Baseline JAR does not exist: /tmp/abi-empty-baseline/plugin-api.jar`——传了参数就不再退回 `SKIPPED`（缺失即失败） |
| 4 | 查看 CI 上同一提交的工作流日志 | `ABI gate of the stable surface` 步骤含第 2 步那两行 `SKIPPED`，工作流结论为 `success`（不静默假绿，构建仍绿） |

后置：`rmdir /tmp/abi-empty-baseline`。

#### `TC-GAME-006` 稳定面破坏性变更时 ABI 门变红 — 对应 `REQ-M2-022` · 级别: 边缘 · 执行者: AI

前置：`REQ-M2-022` 已合入。第 1～5 步**不需要 tag**——baseline 目录按 `docs/ENGINE.md` §7.1 的本地口径手工造；只有第 6 步（CI 侧真红）要等第一个 tag 与 `gradle/abi-baseline.tag` 落地，且需要推送权限。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `./gradlew :plugin-api:jar :protocol:jar --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts`，再 `mkdir -p .abi-baseline && cp plugin-api/build/libs/plugin-api-*.jar .abi-baseline/plugin-api.jar && cp protocol/build/libs/protocol-*.jar .abi-baseline/protocol.jar` | `.abi-baseline/` 下有 `plugin-api.jar` 与 `protocol.jar` 两个文件 |
| 2 | 不改任何代码，`./gradlew :plugin-api:checkBinaryCompatibility :protocol:checkBinaryCompatibility -PbaselineJarDir=.abi-baseline --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | `BUILD SUCCESSFUL`，且两个任务**真的执行**——输出里是 `> Task :plugin-api:checkBinaryCompatibility`（无 `SKIPPED`），尾部计数含 `2 executed` |
| 3 | 给 `PluginContext` 加一个 `default String probeId() { return "probe"; }` 并重跑第 2 步的命令 | 仍 `BUILD SUCCESSFUL`：兼容变更（只加 `default` 方法）不误报 |
| 4 | 撤销第 3 步，改为删除 `PluginContext#dataDirectory()` **连同随之失效的 `import java.nio.file.Path;`**（不删这行导入，Error Prone 的 `RemoveUnusedImports` 会先把 `compileJava` 弄红，门禁根本轮不到跑），重跑第 2 步的命令 | `BUILD FAILED`，stdout 点名那个方法：`Public/protected method removed or signature changed: io/github/bearisbug/synaxis/plugin/PluginContext#dataDirectory()Ljava/nio/file/Path;`，并以 `ABI Check failed with 1 binary compatibility violations.` 结束 |
| 5 | `git checkout -- plugin-api/src/main/java/io/github/bearisbug/synaxis/plugin/PluginContext.java`，`git status --porcelain` | 无输出（临时破坏已撤干净） |
| 6 | **仅第一个 tag 之后可做**：`gradle/abi-baseline.tag` 已指向该 tag、其 release 有 `plugin-api.jar` 与 `protocol.jar`；新建分支 `tmp/abi-probe`，做第 4 步那次删除（连同调用点）推送并开草稿 PR，等工作流完成，随后关 PR 删分支 | 工作流结论为 `failure`，`ABI gate of the stable surface` 步骤日志含第 4 步那两行；`gh api repos/Bearisbug/synaxis/branches/tmp/abi-probe` 返回 404 |

后置：`rm -rf .abi-baseline`；确认 `git status --porcelain` 无输出、`main` 未被改动。

#### `TC-GAME-067` 游戏侧不得触碰引擎内部、`Entity#moveTo` 签名钉死 — 对应 `REQ-M2-002`、`REQ-M2-025` · 级别: 回归 · 执行者: AI

前置：聚合项目 `:architecture-tests` 已合入（`settings.gradle.kts` 末行；设计文档 §24 规则 ⑥）；工作区干净（`git status --porcelain` 无输出）。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `./gradlew :architecture-tests:check --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | `BUILD SUCCESSFUL`；`architecture-tests/build/test-results/test/` 里 `EngineInternalsArchitectureTest` 共 5 条、0 失败 |
| 2 | 在同一份结果里看类型规则那两条 | `theGameSideNamesNoEngineInternalType` 通过（`:game-rts` 与 `:protocol` 的主类没有一个依赖标 `@ApiStatus.Internal` 的引擎类型）；自测 `theTypeRuleRejectsAGameClassDeclaringAnInternal` 通过，且它断言的是违规消息里点名了 `GameClassDeclaringAnEngineInternal`——只判异常类型会把「规则退化成空规则」也判成通过 |
| 3 | 在同一份结果里看成员规则那两条 | `theGameSideTouchesNoEngineInternalMember` 通过；自测 `theMemberRuleRejectsAGameClassCallingAnInternal` 通过，且违规消息含 `refreshPosition`——夹具是经自己的 `Entity` 子类调的，证明规则按解析后的成员判 owner、不按调用点 |
| 4 | **反证一（成员规则）**：把 `game-rts/src/main/java/io/github/bearisbug/synaxis/game/rts/Unit.java` 里 `advance` 的 `entity.moveTo(position);` 临时改成 `entity.refreshPosition(position, false, true);`，重跑 `./gradlew :architecture-tests:test --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts`，然后还原 | `BUILD FAILED`，5 条里只红 `theGameSideTouchesNoEngineInternalMember`，违规消息是 `Method <…game.rts.Unit.advance(int)> calls method <…game.rts.UnitEntity.refreshPosition(…Pos, boolean, boolean)>`——调用点的 owner 是 `UnitEntity`，红的是解析到 `Entity` 上的那个内部成员 |
| 5 | **反证二（类型规则）**：在 `Unit.java` 临时加 `import io.github.bearisbug.synaxis.TickSchedulerThread;` 与一个字段 `@Nullable TickSchedulerThread probe;`（不能写全限定名，Error Prone 的 `UnnecessarilyFullyQualified` 会先把编译弄红），重跑第 4 步的命令，然后还原 | `BUILD FAILED`，5 条里只红 `theGameSideNamesNoEngineInternalType`，违规消息是 `Field <…game.rts.Unit.probe> has type <io.github.bearisbug.synaxis.TickSchedulerThread>` |
| 6 | **反证三（签名钉住）**：给 `src/main/java/io/github/bearisbug/synaxis/entity/Entity.java` 的 `moveTo(Pos)` 临时加 `@ApiStatus.Internal`，重跑第 4 步的命令，然后还原 | `BUILD FAILED`，红两条：`theEnginePositionEntryPointStillHasTheSignaturePluginsWereGiven`（消息 `moveTo was marked internal … expected: <false> but was: <true>`）与 `theGameSideTouchesNoEngineInternalMember`（`Unit.advance` 调的 `moveTo` 现在也是内部成员）。注解是从 class 文件读的——`ApiStatus.Internal` 只有 `CLASS` 保留级，这一步改用反射的 `isAnnotationPresent` 会假绿 |
| 7 | `grep -rn "DoNotIncludeJars" architecture-tests/src/test --include='*.java'` | 退出码 1、无输出（五个模块都以 jar 进测试运行时 classpath，排除 jar 会让两条规则一个类都扫不到；夹具靠 `DoNotIncludeTests` 排除） |
| 8 | `./gradlew :architecture-tests:jacocoTestReport --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts`，再 `grep -c architecture-tests .github/workflows/ci.yml` | `BUILD SUCCESSFUL`（没有主源集的模块上报告任务照常跑完、不失败）；`grep` 输出 `0`——CI 的 `diff-cover --src-roots` 不列它，没有主源码行可覆盖 |

后置：第 4～6 步都已还原，`git status --porcelain` 无输出。

### M2 · 插件宿主

#### `TC-GAME-007` 插件按拓扑序启用、逆序禁用 — 对应 `REQ-M2-003` · 级别: 冒烟 · 执行者: AI

**已废弃（M2 范围裁剪，随第二个插件补回），冒烟位由 `TC-GAME-053` 接替**（设计文档 v0.6：M2 只有一个编译期插件，`depends` 拓扑排序与环检测整块推到第二个插件出现时，descriptor 里连 `depends` 字段都没有，本用例的前置构造不出来；见设计文档 §28.3 第 ① 条。补回时仍用本 ID）。

前置：`REQ-M2-003` 已合入；两个探测插件 a（`depends=[b]`）与 b（无依赖）可被 `ServiceLoader` 发现（构造方式见 §3 ROLE-PluginAuthor），两者的 `onEnable` / `onDisable` 各打印一行带自身 id 的日志。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 启动 `server-app` 并等待就绪 | stdout 出现 `synaxis server-app listening on 0.0.0.0:25565` |
| 2 | 在 stdout 中定位 b 与 a 的 `onEnable` 行 | 两行都存在，且 b 的行号小于 a 的行号 |
| 3 | `grep plugin_enabled logs/events-*.jsonl` | 两行，`plugin_id` 分别为 a 与 b |
| 4 | 向 stdin 输入 `stop` 并等待退出 | 进程退出码 0；stdout 中 a 的 `onDisable` 行号小于 b 的 `onDisable` 行号 |
| 5 | `ls plugins/a plugins/b` | 两个数据目录都已由宿主创建 |

后置：删除本次生成的 `plugins/a`、`plugins/b`，移除探测插件并确认 `git status --porcelain` 无输出。

#### `TC-GAME-008` 依赖成环时整体拒绝启动 — 对应 `REQ-M2-003` · 级别: 回归 · 执行者: AI

**已废弃（M2 范围裁剪，随第二个插件补回）**（设计文档 v0.6：环检测随依赖解析整块推到第二个插件出现时；M2 的 descriptor 没有 `depends`，构造不出环。见设计文档 §28.3 第 ① 条。补回时仍用本 ID，无替代用例——该行为在 M2 不存在）。

前置：探测插件 a（`depends=[b]`）与 b（`depends=[a]`）。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 启动 `server-app` | 进程在 10 秒内退出，退出码非 0 |
| 2 | 查看输出 | 含环路径打印（形如 `a -> b -> a`） |
| 3 | 在同一份输出里查 `onEnable` | 一行都没有（不调用任何插件的 `onEnable`） |
| 4 | `nc -z 127.0.0.1 25565` | 退出码非 0（端口从未 bind） |

后置：移除探测插件。

#### `TC-GAME-009` 非法与保留插件 id 被拒 — 对应 `REQ-M2-003` · 级别: 回归 · 执行者: AI

前置：一个探测插件，descriptor 的 `id` 可改。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 把 id 设为 `Synaxis RTS`（含大写与空格）后启动 | 进程以非零码退出；日志含该插件被拒绝加载与正则 `[a-z0-9][a-z0-9_-]{0,63}` |
| 2 | 把 id 设为 `synaxis` 后启动 | 同样被拒绝，日志说明 `synaxis` 是宿主保留 id |
| 3 | 把 id 设为空串后启动 | 被拒绝，日志同样指向正则约束 |
| 4 | 把 id 设为 65 个字符的串后启动 | 被拒绝（上限 64） |
| 5 | 把 id 改回合法值 `probe-plugin` 后启动 | 进程进入就绪，日志含该插件的 `onEnable` 行 |

后置：移除探测插件。

#### `TC-GAME-010` `api_version` 不匹配时拒绝加载并阻止启动 — 对应 `REQ-M2-003` · 级别: 回归 · 执行者: AI

前置：宿主当前 `api_version` 为 1（以 `docs/DESIGN-M2.md` §8 为准）。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 把探测插件 descriptor 的 `api_version` 设为 2 后启动 | 进程以非零码退出；日志含该插件 id、期望值 1 与实际值 2 |
| 2 | `nc -z 127.0.0.1 25565` | 退出码非 0（端口未 bind，拒绝只写日志、不下发客户端） |
| 3 | 把 `api_version` 设为 0 后启动 | 同样以非零码退出（约束是 ≥ 1 且等于宿主值） |
| 4 | 把 `api_version` 改回 1 后启动 | 进程进入就绪，日志含该插件的 `onEnable` 行 |

后置：移除探测插件。

#### `TC-GAME-011` `onEnable` 抛异常的插件转 failed 而进程继续 — 对应 `REQ-M2-003`、`REQ-M2-005` · 级别: 回归 · 执行者: AI

前置：探测插件 a 的 `onEnable` 直接抛 `RuntimeException`；`:game-rts` 正常启用并创建对局。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 启动 `server-app` | 进程仍进入就绪（端口 bind、就绪日志出现）——a 被摘干净但进程继续 |
| 2 | 查看 stdout | 有一行 ERROR 带 `plugin_id=a` 与异常堆栈 |
| 3 | `grep plugin_enabled logs/events-*.jsonl` | 有 `plugin_id` 为 `rts` 的一行，**没有** `plugin_id` 为 a 的行（a 从未进入 `enabled`；M2 已无 `plugin_tripped` 埋点，见设计文档 §28.3） |
| 4 | 用探针发 `C2S_HELLO` 登录并进入 play | 收到 `S2C_HELLO` 与至少一条 `S2C_UNIT_SPAWN`（其它插件不受影响） |
| 5 | 观察 30 秒 stdout | 无 a 的任何后续回调日志（事件、任务、通道都已摘掉） |

后置：`stop` 关闭进程；移除探测插件。

#### `TC-GAME-012` 插件禁用后零回调 — 对应 `REQ-M2-004` · 级别: 回归 · 执行者: AI

**已废弃，被 `TC-GAME-055` 取代**（设计文档 v0.6：M2 没有熔断器，`-Dsynaxis.app.plugin.trip-threshold=1` 这个前置与「抛一次异常即禁用」这条触发路径都不存在了，禁用的唯一触发路径改为停机；触发手段与可观察窗口都变了，按惯例换 ID。熔断触发那一档随熔断器一起补回，见设计文档 §28.3 第 ① 条）。

前置：以 `-Dsynaxis.app.plugin.trip-threshold=1` 启动（该属性是启动期系统属性，见 §3，运行中改不了）；探测插件 a 已 enabled，注册了一个事件监听器（玩家加入时打印一行，用户名为 `trip` 时改为抛 `RuntimeException`）、一个每 100 tick 打印一行的任务、一条通道 `a:probe`。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 启动后观察 30 秒，并用探针登录一次 | 任务日志行每 5 秒左右出现一次；探针登录时事件监听器的日志行出现 |
| 2 | 用探针以用户名 `trip` 登录一次，触发 a 的监听器抛异常（阈值已是 1，一次即熔断；运维侧的显式禁用入口待 `REQ-M2-004` 实现后填） | 日志出现 a 的 `onDisable` 行与状态变更行（`failed` 或 `disabled`） |
| 3 | 再观察 30 秒 | a 的任务日志行出现 0 次（提交的 `Task` 已全部 cancel） |
| 4 | 用探针再登录一次 | a 的事件监听器日志行不再出现（事件子节点已 `removeChild`）；登录本身仍成功（收到 `S2C_HELLO`） |
| 5 | 用探针向 `a:probe` 发一条负载 | 连接不断线；服务端记一行带 `connection_id` 的 WARN，无异常堆栈 |

后置：`stop`；移除探测插件。

#### `TC-GAME-013` 持续抛异常触发熔断而进程继续 — 对应 `REQ-M2-005` · 级别: 回归 · 执行者: AI

**已废弃，被 `TC-GAME-054` 取代**（设计文档 v0.6：滑窗熔断器整块推到第二个插件出现时，`REQ-M2-005` 只剩「三个包装层各打一次 `plugin_id` 的归因」；「第 20 次触发即 `failed`」这条判据不再成立，替代用例改判三层各一行归因且插件保持 `enabled`。见设计文档 §28.3 第 ① 条，补回时仍用本 ID）。

前置：探测插件 a 监听玩家加入事件，监听器每次调用都抛 `RuntimeException`（因此一次探针 `login` 即触发一次）；熔断阈值取默认（滑窗 60 s、20 次）。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 启动后在 60 秒内用探针 `login --hold 0` 登录 19 次（每次触发该监听器一次） | a 仍在工作（日志无熔断行）；日志已有 19 行带 `plugin_id=a` 的异常 |
| 2 | 在同一滑窗内再登录一次（第 20 次触发） | 日志出现熔断行与 a 的 `onDisable`；状态变为 `failed` |
| 3 | `grep plugin_tripped logs/events-*.jsonl` | 至少一行，`plugin_id` 为 a 且带 `cause` 与 `exception_count` |
| 4 | `nc -z 127.0.0.1 25565`，并用探针登录 | 退出码 0；探针能进入 play 并收到 `S2C_UNIT_SPAWN`（进程与其它插件不受影响） |
| 5 | 再登录 5 次 | stdout 不再出现带 `plugin_id=a` 的异常行（节点已摘干净） |

后置：`stop`；移除探测插件。

#### `TC-GAME-014` 通道命名空间前缀强制 — 对应 `REQ-M2-006` · 级别: 回归 · 执行者: AI

前置：探测插件 a 可修改其 `onEnable` 里的通道注册调用。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 让 a 调 `channels().register("b:game", handler)` 后启动 | 抛 `IllegalArgumentException`；日志含该异常与 `plugin_id=a` |
| 2 | 改为 `channels().register("game", handler)` 后启动 | 进程就绪；日志显示实际注册的通道 id 为 `a:game` |
| 3 | 用探针向 `a:game` 发一条该插件认识的负载 | 收到该插件的回发负载，内容与约定一致 |
| 4 | 让 a 调 `channels().register("synaxis:handshake", handler)` 后启动 | 抛 `IllegalArgumentException`（`synaxis` 为宿主保留命名空间） |
| 5 | 让 a 调 `channels().send(player, "b:game", bytes)` 后触发该路径 | 抛 `IllegalArgumentException`，连接不断线 |

后置：`stop`；移除探测插件。

#### `TC-GAME-015` 未注册通道的入站负载被丢弃 — 对应 `REQ-M2-006` · 级别: 回归 · 执行者: AI

前置：进程就绪；没有任何插件注册通道 `zz:game`。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 用探针握手后进入 play，向 `zz:game` 发一条 8 字节负载 | 连接保持（后续 KeepAlive 往返正常），未收到 Disconnect |
| 2 | 查看服务端 stdout | 恰有一行 WARN，带 `connection_id` 与通道名 `zz:game`，无异常堆栈（以 `at ` 开头的行为 0） |
| 3 | 连发 100 条同样的负载 | 连接仍保持，未收到 Disconnect；进程无异常退出 |
| 4 | 另起一条连接，在发 `C2S_HELLO` **之前**向 `rts:game` 发一条负载，然后再发 `C2S_HELLO` | 该负载被丢弃、连接不因此断线；随后的 `C2S_HELLO` 仍能换到 `S2C_HELLO`（`verified` 之前只接受 `synaxis:handshake`） |

后置：无。

#### `TC-GAME-016` 示例插件按说明跑通 — 对应 `REQ-M2-023` · 级别: 回归 · 执行者: 皆可

前置：`REQ-M2-023` 的示例插件（`example-plugin/`）与插件开发说明（`docs/PLUGINS.md`）已合入；执行者只读 `docs/PLUGINS.md`，不查阅其它资料。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 通读说明并按它新建一个只实现 `SynaxisPlugin` 的模块 | 说明每一步都有可执行命令，无「问一下」类表述；开篇即写明「不支持热加载，改插件 = 重启进程」 |
| 2 | 执行说明给出的构建命令 | stdout 含 `BUILD SUCCESSFUL` |
| 3 | 执行说明给出的启动命令 | 进程进入就绪；stdout 含该插件 `onEnable` 的打印 |
| 4 | 按说明第 8 节给出的命令 `python3 tools/protocol-probe/probe.py echo --channel hello:echo --payload hi` 向它的通道发一条消息 | 退出码 0，探针打印的回显负载与发出的完全一致；服务端 stdout 含 `echoing 2 bytes back to probe` |
| 5 | `stop` 后查看日志 | 含该插件 `onDisable` 的打印，进程退出码 0 |

后置：删除新建的示例模块目录，确认 `git status --porcelain` 无输出。

#### `TC-GAME-053` 单插件三段生命周期与数据目录 — 对应 `REQ-M2-003` · 级别: 冒烟 · 执行者: AI

前置：`REQ-M2-003` 已合入；一个探测插件 a 可被 `ServiceLoader` 发现（构造方式见 §3 ROLE-PluginAuthor），其 `onEnable` / `onDisable` 各打印一行带自身 id 的日志；descriptor 里**没有** `depends` 字段（M2 范围裁剪，见设计文档 §28.3 第 ① 条）。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 启动 `server-app` 并等待就绪 | stdout 出现 `synaxis server-app listening on 0.0.0.0:25565` |
| 2 | 在 stdout 中定位 a 的 `onEnable` 行与就绪日志行 | 两行都存在，且 `onEnable` 的行号小于就绪日志的行号（插件在 bind 之前启用） |
| 3 | `grep plugin_enabled logs/events-*.jsonl` | 有一行 `plugin_id` 为 a，带 `version` 与 `enable_ms` |
| 4 | `ls plugins/a` | 数据目录已由宿主创建 |
| 5 | 向 stdin 输入 `stop` 并等待退出 | 进程退出码 0；stdout 出现 a 的 `onDisable` 行 |
| 6 | 在停机日志里比较 a 的 `onDisable` 行与进程退出行的先后 | `onDisable` 行在前，且晚于「开始停机」那一行——插件禁用由引擎 `scheduler.shutdown()` 驱动（见设计文档 `API-M2-007`），控制台 `stop` 与 SIGINT 两条路径的日志顺序一致 |

后置：删除本次生成的 `plugins/a`，移除探测插件并确认 `git status --porcelain` 无输出。

#### `TC-GAME-054` 三层异常各带 plugin_id 归因且进程继续 — 对应 `REQ-M2-005` · 级别: 回归 · 执行者: AI

前置：探测插件 a 已 enabled，并在三处各留一条必抛 `RuntimeException` 的路径：① 一个玩家加入事件的监听器；② 一个每 100 tick 执行的任务；③ 一条通道 `a:probe` 的 handler。M2 **没有熔断器**（设计文档 §28.3 第 ① 条），因此抛异常不改变插件状态。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 启动 `server-app` 并等待就绪 | 进入就绪；a 的 `onEnable` 行出现 |
| 2 | 用探针 `login --hold 0` 登录一次，触发事件监听器抛异常 | stdout 恰多一行带 `plugin_id=a` 的 ERROR 与堆栈；探针本次登录仍成功进入 play |
| 3 | 等待任务至少执行一次（约 5 秒），查看 stdout | 每次任务执行各多一行带 `plugin_id=a` 的 ERROR；tick 未中断（无 tick 超时告警行） |
| 4 | 用探针向 `a:probe` 发一条负载 | 连接不断线；stdout 多一行带 `plugin_id=a` 的 ERROR |
| 5 | 在 60 秒内把第 2 步重复 20 次后查看插件状态与事件台账 | a 仍在工作（第 21 次仍打出同样的 ERROR）；`grep plugin_tripped logs/events-*.jsonl` 无输出（该埋点在 M2 不存在）；进程仍 `nc -z 127.0.0.1 25565` 退出码 0 |

后置：`stop`；移除探测插件，确认 `git status --porcelain` 无输出。

#### `TC-GAME-055` 停机时插件被一次性摘干净 — 对应 `REQ-M2-004` · 级别: 回归 · 执行者: AI

前置：探测插件 a 已 enabled，注册了一个事件监听器（玩家加入时打印一行）、一个每 100 tick 打印一行的任务、一条通道 `a:probe`，并注册了一个准入回调；`:game-rts` 正常启用。M2 里插件被禁用的**唯一**触发路径是停机（没有熔断，见设计文档 §28.3 第 ① 条），因此本用例走停机路径，并用宿主单测补齐停机后不可观察的部分。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 启动后观察 30 秒，并用探针登录一次 | 任务日志行每 5 秒左右出现一次；探针登录时事件监听器的日志行出现 |
| 2 | 向 stdin 输入 `stop`，记录停机日志 | 出现 a 的 `onDisable` 行；其后直到进程退出，a 的任务日志行与事件监听器日志行各出现 0 次 |
| 3 | 在停机日志里查 a 注册的准入回调 | 有一行说明该准入回调已被摘掉（宿主随插件禁用一并注销，见设计文档 `API-M2-008`） |
| 4 | 查看进程退出码与 stdout | 退出码 0；无异常堆栈（以 `at ` 开头的行为 0） |
| 5 | 运行宿主的「禁用后零回调」单测：`./gradlew :server-app:test --tests 'io.github.bearisbug.synaxis.app.PluginHostIntegrationTest' --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts`（方法 `disablingTakesBackEventsTasksChannelsAndAdmission`） | `BUILD SUCCESSFUL`；该方法断言禁用后事件子节点已 `removeChild`、提交的 `Task` 不再 `isAlive`、准入回调已摘掉、向其通道发消息被丢弃并恰记一行带通道名的 WARN |

后置：移除探测插件，确认 `git status --porcelain` 无输出。

### M2 · 协议与握手

#### `TC-GAME-017` 双端向量逐字节一致 — 对应 `REQ-M2-007` · 级别: 冒烟 · 执行者: AI

前置：`REQ-M2-007` 已合入，`protocol/src/test/resources/vectors/` 下每条消息各有一个常规 `.bin`、`vectors/edge/` 下各有一个同名的边界 `.bin`，同目录的 `manifest.txt` 冻结着 `schema_version` 与全部 `.bin` 的 SHA-256 摘要；第 4 步的客户端侧夹具随 `REQ-M2-020b` 合入（`client-fabric` 的构建把 `synaxis.vectors.dir` 指到上面这个目录，两端读的是同一批文件、不是各自一份拷贝），执行前先在主构建跑一次 `./gradlew :protocol:publishToMavenLocal --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts`——客户端按坐标消费 `:protocol`（设计文档 `ADR-M2-015`）。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `ls protocol/src/test/resources/vectors/*.bin \| wc -l` | 输出 `10`——9 条消息（`C2S_HELLO`、`S2C_HELLO`、`C2S_CAMERA`、`C2S_SELECT`、`C2S_MOVE_ORDER`、`S2C_ORDER_RESULT`、`S2C_UNIT_SPAWN`、`S2C_UNIT_STATE`、`S2C_UNIT_DESPAWN`）各一个，外加 `archetype_box.bin`（§24 把两端各持一份的原型包围盒表一并冻结，它不是消息，所以不进第 2 步的边界集） |
| 2 | `ls protocol/src/test/resources/vectors/edge/*.bin \| wc -l` | 输出 `9`，文件名与第 1 步一一同名（边界档：`count` 取上限 8 / 512 与取零、VarInt 取满五字节、`zoom` 取区间端点、`flags` / `team` 取 0） |
| 3 | `./gradlew :protocol:test --rerun-tasks --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | `BUILD SUCCESSFUL`；`ProtocolVectorTest` 的 18 条 `aFrozenVectorDecodesAndReEncodesByteForByte` 全绿（decode 后再 encode 与原文件逐字节相同、且解码器把每条向量读到一个字节不剩），`theFrozenVectorsAreBoundToTheSchemaVersion` 也绿（`manifest.txt` 的 `schema_version` 等于 `ProtocolSchema.SCHEMA_VERSION`、`digest` 等于全部 `.bin` 的实算摘要） |
| 4 | `cd client-fabric && ./gradlew test --no-daemon`（客户端侧跑同一份向量） | `BUILD SUCCESSFUL`；`FrozenVectorTest` 20 条全绿——19 条 `aFrozenVectorSurvivesTheClientCodecByteForByte` 把每个 `.bin` 经客户端的编解码路径（`rts:game` 的 14 条走 `GamePayload.CODEC` 与 Minecraft 的 `RegistryFriendlyByteBuf`，四条握手向量走 `HandshakePayload.CODEC` 与配置阶段的 `FriendlyByteBuf`——那两个配置注册表发的就是它，配置阶段的连接还没有注册表（`c2s_hello.bin` / `s2c_hello.bin` 各有正规与 `edge/` 两份，`reEncode` 先剥掉 `edge/` 前缀再查 `HANDSHAKE`），`archetype_box.bin` 走 `ArchetypeBox.writeTable`；14 + 4 + 1 = 19）解出来再写回去，与原文件逐字节相同；`theVectorsAreTheOnesThisBuildsProtocolJarSpeaks` 把这批文件绑在客户端消费的那个 `:protocol` 工件上（`manifest.txt` 的 `schema_version` 等于该工件的 `ProtocolSchema.SCHEMA_VERSION`、`digest` 等于 19 个 `.bin` 的实算摘要、条数为 19） |
| 5 | 改掉某个 `.bin` 的一个字节后重跑第 3、4 步，再 `git checkout -- protocol/src/test/resources/vectors` | 第 3 步红两条：该向量的逐字节对拍点名文件名，摘要那条打出设计文档 §24 的「升 `SCHEMA_VERSION` 并重新冻结向量」提示；第 4 步红在 `theVectorsAreTheOnesThisBuildsProtocolJarSpeaks`（客户端只对拍往返与摘要，期望值那一份事实源在服务端侧，见该测试的类注释）；还原后 `git status --porcelain` 无输出 |
| 6 | 把 `C2SCamera#write` 里 `zoom` 与 `cameraYaw` 的写入顺序对调后重跑第 3 步，再还原该文件 | `c2s_camera.bin` 与 `edge/c2s_camera.bin` 两条红——改了布局而没重新冻结向量，构建不可能绿；还原后第 3 步回到 `BUILD SUCCESSFUL` |

后置：确认工作区干净。

#### `TC-GAME-018` 解码器对未知与畸形输入的行为 — 对应 `REQ-M2-007` · 级别: 回归 · 执行者: AI

前置：进程就绪；探针可向 `rts:game` 发任意字节。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 发一条合法 `C2S_CAMERA`，尾部追加 8 个未知字节 | 解码成功、按前 17 字节取值（玩家实体被传送到该锚点），连接不断线、日志无 ERROR |
| 2 | 发一条 `msg_id=0x7E`（未分配）的消息 | 该包被丢弃并记一行 WARN，连接保持 |
| 3 | 发一条声明 `count=8` 但只带 2 个 `unit_id` 的 `C2S_SELECT`（截断） | 收到 Disconnect，原因键 `synaxis:protocol-error` |
| 4 | 重新握手登录，发一条长度字段为负 VarInt 的消息 | 同样收到 `synaxis:protocol-error`；服务端 stdout 无以 `at ` 开头的堆栈行 |
| 5 | 再握手登录一次 | 进入 play（进程未受前几步影响） |

后置：无。

#### `TC-GAME-019` 未发 `C2S_HELLO` 的连接 3 秒被踢 — 对应 `REQ-M2-008` · 级别: 冒烟 · 执行者: AI

前置：`REQ-M2-008` 已合入；进程以**玩法模式**就绪（默认起法即是；核心模式豁免握手，那一档由 `TC-GAME-060` 判）。探针子命令 `no-hello`。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `python3 tools/protocol-probe/probe.py no-hello`（登录后不发 `C2S_HELLO`，模拟没装模组的客户端），记录进入 configuration 的时刻 | 3.0～3.5 秒内收到 Disconnect，原因键 `synaxis:client-mod-required`，退出码 0 |
| 2 | 查看服务端 stdout | 该次 kick 的日志行带 `connection_id`（引擎只把它写进日志、不拼进断线文本，见设计文档 §13），无异常堆栈 |
| 3 | `grep client_rejected logs/events-*.jsonl \| tail -1` | 最后一行的 `reason` 为 `mod-missing` |
| 4 | `grep session_entered_configuration logs/events-*.jsonl \| tail -1` | 存在对应该连接的一行（分母事件覆盖未握手的会话） |
| 5 | `python3 tools/protocol-probe/probe.py handshake` | 收到 `S2C_HELLO`，`schema_version` 与服务端一致，随后进入 play，退出码 0 |

后置：无。

#### `TC-GAME-020` 原版客户端看到的断线提示 — 对应 `REQ-M2-008` · 级别: 回归 · 执行者: 人工

前置：进程以**玩法模式**就绪（核心模式不要求握手，原版客户端会被放行，测不到本用例）；一台装了**原版（无模组）** Minecraft 26.2 客户端的机器。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 多人游戏直连 `127.0.0.1` | 进入「连接中」后约 3 秒被断开，跳到断线提示界面 |
| 2 | 阅读断线界面文本 | 显示的是 `synaxis:client-mod-required` 的原始文本（原版客户端没有我们的语言文件），含「安装 Synaxis 客户端模组」提示与整合包获取途径 |
| 3 | 点「返回」 | 回到主菜单，客户端不崩溃 |
| 4 | 查看服务端 stdout | 该次 kick 的日志行带 `connection_id`，无异常堆栈 |

后置：无。

#### `TC-GAME-021` `schema_version` 不等即被拒 — 对应 `REQ-M2-008` · 级别: 回归 · 执行者: AI

**已废弃，被 `TC-GAME-062` 取代**（第 2 步的判据实质变更）：原第 2 步要求 `C2S_HELLO{schema_version=0}` 也回 `synaxis:protocol-version-mismatch`，但 `REQ-M2-007` 冻结的编解码层把 `schema_version` 定为 `≥ 1`（设计文档 §9），越界字段在 record 的紧凑构造器里就抛，按 §8 属于**畸形输入**、回 `synaxis:protocol-error`——「不等」与「非法」是两类结果，实测（2026-09-19）`--schema 0` 得到 `synaxis:protocol-error`、`--schema 2` 得到 `synaxis:protocol-version-mismatch`。按惯例换 ID，其余步骤在替代用例里保留。

前置：进程就绪，服务端 `schema_version` 为 1。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 用探针发 `C2S_HELLO{schema_version=2}` | 1 秒内（不等 3 秒超时）收到 Disconnect，原因键 `synaxis:protocol-version-mismatch` |
| 2 | 用探针发 `C2S_HELLO{schema_version=0}` | 同样收到 `synaxis:protocol-version-mismatch` |
| 3 | `grep client_rejected logs/events-*.jsonl \| tail -2` | 两行的 `reason` 均为 `version-mismatch` |
| 4 | 用探针发 `C2S_HELLO{schema_version=1}` | 收到 `S2C_HELLO`，其中 `tick_rate` 为 20、`server_tick` 为非负整数 |

后置：无。

#### `TC-GAME-022` 重复 `C2S_HELLO` 判协议错误 — 对应 `REQ-M2-008` · 级别: 回归 · 执行者: AI

前置：进程就绪。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `python3 tools/protocol-probe/probe.py duplicate-hello`，观察其输出的第一段 | 收到 `S2C_HELLO`（会话进入 `verified`） |
| 2 | 观察该子命令随后在同一条连接上再发一次 `C2S_HELLO` 的结果 | 收到 Disconnect，原因键 `synaxis:protocol-error`，退出码 0 |
| 3 | 查看服务端 stdout | 一行 `Refusing a handshake: connection_id=… reason=synaxis:protocol-error cause=a connection sends one C2S_HELLO and this is the second` 的 WARN，无异常堆栈 |
| 4 | 另起一条连接正常握手并进入 play | 不受上一条连接影响，收到 `S2C_UNIT_SPAWN` |

后置：无。

#### `TC-GAME-062` `schema_version` 不等与非法各走各的出口 — 对应 `REQ-M2-008`、`REQ-M2-007` · 级别: 回归 · 执行者: AI

前置：进程就绪（两种模式都适用——核心模式豁免的只有「不发握手」那一档），服务端 `schema_version` 为 1。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `python3 tools/protocol-probe/probe.py bad-schema --schema 2` | 1 秒内（不等 3 秒超时）收到 Disconnect，原因键 `synaxis:protocol-version-mismatch`，退出码 0 |
| 2 | `python3 tools/protocol-probe/probe.py bad-schema --schema 0` | 收到 Disconnect，原因键 `synaxis:protocol-error`（`schema_version` 的取值域是 `≥ 1`，越界是畸形输入不是版本不符，见设计文档 §8、§9）；子命令按「不等」判故退 1，**本步以原因键为准** |
| 3 | `grep client_rejected logs/events-*.jsonl \| tail -2` | 只有第 1 步那一行，`reason` 为 `version-mismatch`；第 2 步是协议层畸形，不计进握手成功率的分子分母（设计文档 §20、§21） |
| 4 | `python3 tools/protocol-probe/probe.py handshake` | 收到 `S2C_HELLO`，其中 `schema_version` 与服务端一致、`tick_rate` 为 20、`server_tick` 为非负整数，退出码 0 |

后置：无。

#### `TC-GAME-060` 核心模式豁免握手但照常判一条握手 — 对应 `REQ-M2-008`、`REQ-M2-021` · 级别: 冒烟 · 执行者: AI

前置：`REQ-M2-008` 已合入。本用例自己起停两次进程，开始时无进程在 25565 上。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 带 `-Dsynaxis.app.core-mode=on` 起服 | 就绪日志出现；stdout 有 `Registered channel: plugin_id=synaxis channel=synaxis:handshake` 与 `Core mode (synaxis.app.core-mode=on): …`，**没有** `Gameplay mode:` 那一行（通道照常注册，只是不强制） |
| 2 | `python3 tools/protocol-probe/probe.py no-hello` | **不被踢**：进入 play 并保持，末行 `the server let the connection through instead of sending synaxis:client-mod-required`、退出码 1——该子命令按玩法模式判，本步以「未收到 `synaxis:client-mod-required`、连接保持」为准 |
| 3 | `python3 tools/protocol-probe/probe.py handshake` | 退出码 0，收到 `S2C_HELLO` 且 `schema_version` 与服务端一致（不要求握手 ≠ 不回握手） |
| 4 | `python3 tools/protocol-probe/probe.py bad-schema --schema 2` 与 `python3 tools/protocol-probe/probe.py duplicate-hello` | 两条都退出码 0，分别收到 `synaxis:protocol-version-mismatch` 与 `synaxis:protocol-error`（不要求握手 ≠ 不判握手） |
| 5 | `java tools/loadtest/PacketFlood.java --connections 50 --hz 100 --seconds 30 --out <目录>`（该工具是另一套手写实现，**不发** `C2S_HELLO`） | `achieved` ≈ 5,000 包/秒、`0 lost`、50 条连接全程在线——核心模式的压测路径不因强制握手失效（`docs/DESIGN.md` §15 的入站包目标） |
| 6 | `python3 tools/protocol-probe/probe.py echo --payload core-ping` | 退出码 0，回发一致负载（`synaxis:main` 仍在） |
| 7 | 控制台 `stop`；改用**默认起法**（玩法模式）重启，再跑一次第 2 步的命令 | 第一次退出码 0、端口释放；重启后 `no-hello` 退出码 0 并收到 `synaxis:client-mod-required`——强制与豁免的分界就是有没有玩法插件 |

后置：`stop`，确认无残留 java 进程。

#### `TC-GAME-061` 握手阶段可切到 login 作为退路 — 对应 `REQ-M2-008`（`ADR-M2-010` 的「落地形态与退路」） · 级别: 回归 · 执行者: AI

前置：`REQ-M2-008` 已合入。本用例自己起停三次进程。**`ADR-M2-010` 出结论（阶段 0 spike ④）之后本用例随被删掉的那条路径一起废弃**，见设计文档 §28.2 的登记项。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 不给 `synaxis.app.handshake-phase` 起服（默认起法） | 就绪前那行 `Gameplay mode: …` 以 `in the configuration phase (schema_version=1)` 结尾——默认就是 `ADR-M2-010` 的决定 |
| 2 | `stop` 后带 `-Dsynaxis.app.handshake-phase=login` 起服 | 同一行改为 `in the login phase (schema_version=1)` |
| 3 | `python3 tools/protocol-probe/probe.py handshake` | 退出码 0；输出里有 `sent LoginPluginResponse channel=synaxis:handshake understood=True`，`S2C_HELLO` 是**服务端那条 login plugin request 的负载**（不是 configuration 阶段的自定义负载） |
| 4 | `python3 tools/protocol-probe/probe.py no-hello` | 退出码 0，收到 **`LoginDisconnect`** reason=`synaxis:client-mod-required`（login 阶段的断线包类型；判据来自「空负载应答」而不是 3 秒静默） |
| 5 | `python3 tools/protocol-probe/probe.py bad-schema --schema 2` | 退出码 0，收到 `LoginDisconnect reason=synaxis:protocol-version-mismatch` |
| 6 | `python3 tools/protocol-probe/probe.py login` | 退出码 0，进入 play（login 阶段握过手的连接不再在 configuration 阶段重发，不会被判重复） |
| 7 | `grep -E "INVALID_PROXY_RESPONSE\|multiplayer.disconnect" <服务端 stdout>` | 退出码 1、无输出——退路没有落进引擎那条固定的代理错误文案上，断线文案仍全由游戏侧控制（`ADR-M2-010` 的 Alternatives 顾虑不成立） |
| 8 | `stop` 后带 `-Dsynaxis.app.handshake-phase=later` 起服 | 一行 `Refusing to start: synaxis.app.handshake-phase=later is not one of configuration, login` 的 ERROR，退出码 1，`nc -z 127.0.0.1 25565` 非 0（端口未 bind） |

后置：确认无残留 java 进程。

#### `TC-GAME-023` 下令超频回 `rate-limited` 且不断线 — 对应 `REQ-M2-009` · 级别: 回归 · 执行者: AI

**已废弃，被 `TC-GAME-063` 取代**（第 5 步的判据实质变更）：原第 5 步要求那 10 条超频在 `logs/events-*.jsonl` 里各留一行 `code=rate-limited`，而设计文档 §20 现在把 `rate-limited` 与 `stale` 排除出台账——这两档不进任何指标的分母，且客户端能按自己的节奏无限产生它们，而台账是每条事件一次 `open/append/close` 且写在 tick 线程上（实测一秒 1,000 条超频指令 → 台账多 1,002 行 / 215 KB）。前四步逐字保留在替代用例里。

前置：探针已 in_game，持有一个本方单位 U（§3 对局种子）。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 1 秒内发 20 条 `order_group` 互不相同、`order_seq` 递增、目标均为可站立点的 `C2S_MOVE_ORDER` | 收到 20 条 `S2C_ORDER_RESULT`，一条不少（每条请求必回一条结果） |
| 2 | 统计这 20 条结果的 `code` | 前 10 条不含 `rate-limited`，其余 10 条全为 `rate-limited` |
| 3 | 检查连接状态 | 未收到 Disconnect，后续 KeepAlive 往返正常（玩法超频不断线，见设计文档 §14） |
| 4 | 等 1 秒后再发 1 条合法指令 | 收到 `code=ok`（令牌桶已回填） |
| 5 | `grep unit_order_rejected logs/events-*.jsonl \| tail -10` | 10 行的 `code` 均为 `rate-limited` |

后置：无。

#### `TC-GAME-063` 下令超频回 `rate-limited`、不断线、也不写台账 — 对应 `REQ-M2-009` · 级别: 回归 · 执行者: AI

前置：探针已 in_game，持有一个本方单位 U（§3 对局种子）。

**单位已随 `REQ-M2-016` 落地（阶段 5）**：`unit_id=1..64` 与 `67` 是探针队伍的单位，带 `code=ok` 的步骤用它们；`65`（中立）与 `66`（对方）用来测 `not-owner`。`code=ok` 的答复由寻路队列在随后的 tick 里发出（每 tick 至多 4 条搜索，见设计文档 §15），探针的 `--wait` 已覆盖这段。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `python3 tools/protocol-probe/probe.py order --count 20 --seq 1 --unit <U> --x <可站立 x> --y <y> --z <可站立 z>`（`order_group` 默认与 `order_seq` 同步递增，互不相同） | 收到 20 条 `S2C_ORDER_RESULT`，一条不少（每条请求必回一条结果），末行 `answers=20/20` |
| 2 | 看同一行的结果码直方图 | 前 10 条不含 `rate-limited`，其余 10 条全为 `rate-limited`（`--expect rate-limited --expect-count 10` 直接把这一步判成退出码） |
| 3 | 检查连接状态 | 未收到 Disconnect，后续 KeepAlive 往返正常（玩法超频不断线，见设计文档 §14） |
| 4 | 等 1 秒后再发 1 条合法指令 | 收到 `code=ok`（令牌桶已回填） |
| 5 | `grep -c rate-limited logs/events-*.jsonl`（`stale` 同理） | 计数 0、退出码 1——客户端能无限刷的拒绝不写台账（设计文档 §20），这 10 条只在回包里可见 |
| 6 | 再发一条越界指令 `… order --count 1 --seq <更大值> --unit <U> --x -4 --y <y> --z 4`，然后 `grep unit_order_rejected logs/events-*.jsonl \| tail -1` | 回 `code=out-of-bounds`；台账最后一行的 `code` 就是 `out-of-bounds`——§20 点名的四档（`no-path` / `out-of-bounds` / `not-owner` / `busy`）照常逐条记，被排除的只有那两档 |

后置：无。

#### `TC-GAME-024` 同一 `order_group` 的多包只扣一个令牌 — 对应 `REQ-M2-009` · 级别: 回归 · 执行者: AI

前置：探针已 in_game，持有 9 个本方单位（§3 对局种子）。

**单位已随 `REQ-M2-016` 落地（阶段 5）**：`unit_id=1..64` 与 `67` 是探针队伍的单位，带 `code=ok` 的步骤用它们；`65`（中立）与 `66`（对方）用来测 `not-owner`。`code=ok` 的答复由寻路队列在随后的 tick 里发出（每 tick 至多 4 条搜索，见设计文档 §15），探针的 `--wait` 已覆盖这段。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `python3 tools/protocol-probe/probe.py order --count 8 --seq 1 --group 1 --unit <U> --x <可站立 x> --y <y> --z <可站立 z>`（一次点击的形态：`order_group` 相同、`order_seq` 递增） | 收到 8 条 `S2C_ORDER_RESULT`，`code` 全为 `ok`（令牌只扣 1 个，按操作计不按包计）；直方图里 `rate-limited=0` |
| 2 | 同一秒内再发 10 组，每组 1 条、`order_group` 互不相同 | 前 9 组回 `ok`（本秒累计 10 个 `order_group`），第 10 组回 `rate-limited` |
| 3 | 检查连接状态 | 未断线（8 包突发远低于 `synaxis:game-flood` 的 300 包/秒阈值） |
| 4 | 等 1 秒让令牌桶回填（第 2 步已把本秒的桶打空），再发 `order --count 9 --group <新组号>`（组内包数上限是 8） | 按发出顺序前 8 条回 `code=ok`、第 9 条回 `code=rate-limited`（超出组内上限的部分按超频处理）；连接不断线 |

后置：无。

#### `TC-GAME-025` 洪水断线、正常峰值不断线 — 对应 `REQ-M2-009` · 级别: 边缘 · 执行者: AI

**已废弃，被 `TC-GAME-064` 取代**（第 1 步的速率实质变更）：原第 1 步的 `--rate 400` 离 300 包/秒的阈值只有 33% 余量，而探针是突发发包——一秒内把 400 个 `C2S_CAMERA` 打完，再按批读（每 32 包一次 10 ms 的 socket 超时）、剩下的时间 `pump`（`recv` 带 0.5 s 超时，会冲过那一秒的截止），于是单次突发的实际周期漂到 1 s 以上，而服务端按 `nanoTime` 的**固定 1 秒桶**计数、桶与桶之间只要有一个 ≤ 300 就把连续秒清零。实测这一步 14 次挂 7 次，失败时探针实际只达到 344～349 包/秒（服务端侧再被错位的秒桶切一刀就够不到阈值），服务端 stdout 里连一行 `Disconnecting a flooding connection` 都没有。替代用例把速率抬到 800（实测 6 次全中，单窗口实际 622 包/秒），并把探针新报的实际速率纳入判据；其余三步逐字保留。

前置：探针支持按给定速率发包。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 以每秒 400 条的速率连续 3 秒发任意 `rts:game` 消息 | 在第 3 秒末收到 Disconnect，原因键 `synaxis:game-flood` |
| 2 | 查看服务端 stdout | 一行 WARN 带 `connection_id`，无异常堆栈 |
| 3 | 重新握手登录，以每秒 95 条（§2 算出的正常峰值）持续 60 秒 | 全程未断线，末尾仍在收到 `S2C_UNIT_STATE` |
| 4 | 以每秒 299 条持续 3 秒 | 未断线（阈值是连续 3 秒 > 300 包/秒） |

后置：无。

#### `TC-GAME-064` 洪水断线、正常峰值不断线 — 对应 `REQ-M2-009` · 级别: 边缘 · 执行者: AI

前置：探针支持按给定速率发包。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `python3 tools/protocol-probe/probe.py game-flood --rate 800 --seconds 3` | 在第 3 秒末收到 Disconnect，原因键 `synaxis:game-flood`。没收到时先看探针末行的 `slowest window <N>`：`N` 明显低于设定值就说明是探针没打到速率、而不是闸门坏了。**光看 `N > 300` 不够**——服务端按固定 1 秒桶计数，桶与探针的窗口错位后还要再切一刀，所以这一步要的是倍数级余量（实测 `--rate 400` 失败时 N = 344～349 照样不触发，`--rate 800` 的 N 是 622 且六次全中） |
| 2 | 查看服务端 stdout | 一行 WARN 带 `connection_id`，无异常堆栈 |
| 3 | `python3 tools/protocol-probe/probe.py game-flood --rate 95 --seconds 60 --expect alive`（§2 算出的正常峰值） | 全程未断线，末尾仍在收到 `S2C_UNIT_STATE`（快照已随 `REQ-M2-016` 落地，探针每收到一条都打印一行 `S2C_UNIT_STATE server_tick=… count=…`） |
| 4 | `python3 tools/protocol-probe/probe.py game-flood --rate 299 --seconds 3 --expect alive` | 未断线（阈值是连续 3 秒 > 300 包/秒） |

后置：无。

#### `TC-GAME-026` 超量 `count` 与超长负载被拒 — 对应 `REQ-M2-009` · 级别: 回归 · 执行者: AI

前置：探针已 in_game。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 发一条 `count=1000` 的 `C2S_SELECT`（上限 8） | 收到 Disconnect，原因键 `synaxis:protocol-error` |
| 2 | 重新握手登录，`python3 tools/protocol-probe/probe.py select --units 1,2,3,4,5,6,7,8,9 --expect protocol-error` | 同样 `synaxis:protocol-error`（9 即越界） |
| 3 | 重新握手登录，发一条 32,768 字节的自定义负载 | 连接被拒（引擎 `ClientPluginMessagePacket` 构造即抛，上限 32,767），探针观察到断线；服务端 stdout 无未捕获异常堆栈 |
| 4 | 重新握手登录，发一条 `count=8` 的合法 `C2S_SELECT`，再对其中一个本方单位下令 | 连接保持；下令回 `code=ok` |

后置：无。

#### `TC-GAME-027` 乱序与重复 `order_seq` 回 `stale` — 对应 `REQ-M2-009` · 级别: 回归 · 执行者: AI

前置：探针已 in_game，持有本方单位 U，且该连接尚未接受过 `order_seq ≥ 5` 的指令。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 对 U 发 `order_seq=5` 的合法长距离指令 | 收到 `code=ok`；随后的 `S2C_UNIT_STATE` 中 U 的 `unit_state` 为 `1`（moving） |
| 2 | 再发一条 `order_seq=5` 的指令（目标换成另一点） | 收到 `code=stale`；U 仍朝第 1 步的终点移动（不产生第二条路径） |
| 3 | 发 `order_seq=3` 的指令 | 收到 `code=stale` |
| 4 | 在 U 仍 `moving` 时再发 `order_seq=5` | 收到 `code=stale`，U 保持原路径继续移动 |
| 5 | 发 `order_seq=6` 的合法指令 | 收到 `code=ok`；后续 `S2C_UNIT_STATE` 显示 U 朝新终点移动 |

后置：无。

#### `TC-GAME-028` `moving` 中被拒的指令仍收到结果 — 对应 `REQ-M2-009`、`REQ-M2-017` · 级别: 回归 · 执行者: AI

前置：探针已 in_game，持有本方单位 U。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 对 U 下一条合法长距离指令 | 收到 `code=ok`；`S2C_UNIT_STATE` 中 U 的 `unit_state` 为 `1` |
| 2 | 在它移动途中，对它下一条目标在地图外的指令 | 收到一条 `S2C_ORDER_RESULT`，`code=out-of-bounds`（不是静默丢弃） |
| 3 | 继续观察 2 秒的 `S2C_UNIT_STATE` | U 的 `unit_state` 仍为 `1`，没有回到 idle |
| 4 | 等 U 到达第 1 步的终点 | `unit_state` 变为 `0`；`grep unit_order_completed logs/events-*.jsonl \| tail -1` 的 `order_seq` 与第 1 步一致 |
| 5 | 对 U 再下一条合法指令并在途中下一条不属于自己的单位的指令 | 前者 `code=ok`；后者 `code=not-owner`，U 不受影响继续移动 |

后置：无。

### M2 · 世界管理与审计残留

#### `TC-GAME-029` 程序化生成的整图载入并关闭自动加载 — 对应 `REQ-M2-010`、`REQ-M2-011` · 级别: 冒烟 · 执行者: AI

前置：`synaxis.app.map` 填 512×512、高度 64（1,024 个区块）的测试地图**预设 id**（见 §3 地图种子）；地图由 `REQ-M2-010` 的程序化生成器在进程内生成，不读也不写任何地图文件。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 以该预设启动 `server-app` | 进入就绪；`grep match_created logs/events-*.jsonl` 与 `grep match_started logs/events-*.jsonl` **各恰有一行**（M2 单局），`match_id` 相同 |
| 2 | 查看 `match_started` 事件的属性 | `chunk_count` 为 `1024`（全部生成完成才进 running）、`load_ms` < 3000、`heap_gc_runs` ≥ 2、`heap_delta_kib` 落在 `0` 与 `51200` 之间（2026-09-22 实测 `rts-512` 6,600 / `rts-128` 1,125）。**三条要一起看**：`heap_gc_runs` 是那两次存活堆读数触发的回收次数，为 0 时 `heap_delta_kib` 量的是分配量而不是留存量，能落在预算内却什么也证明不了 |
| 3 | 跑 `./gradlew :game-rts:test --tests 'io.github.bearisbug.synaxis.game.rts.MatchIntegrationTest' --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts`（进程内没有打印 `autoChunkLoad` 的运维入口，该断言由 `theWholeMapIsGeneratedBeforeTheMatchRuns` 覆盖） | `BUILD SUCCESSFUL`；该方法断言整图区块数到齐、`hasEnabledAutoChunkLoad()` 为 `false`、实例已注册 |
| 4 | 用探针握手登录进入 play | 进入世界并收到区块包，未出现 `synaxis:internal-error` 或 `synaxis:match-unavailable` |
| 5 | 检查就绪日志与 `match_started` 的先后 | `match_started` 的时间戳早于就绪日志（对局在 bind 之前就已 running，见 `API-M2-010` 启动序列） |
| 6 | 在 `match_created` 与 `match_started` 之间的日志区间里查 tick 计数（tick 计数的打印入口待 `REQ-M2-021` 实现后填） | 全程为 `0`——地图载入跑在启动线程上、不经 `SchedulerManager`（若它被写成 `Scheduler` 任务，tick 线程尚未启动，进程会停在这里不再往下走） |
| 7 | 就绪之后 `jcmd <pid> Thread.print \| grep -c Syn-TickScheduler` 与 `\| grep -c Syn-Tick-` | 两者都 ≥ 1（启动序列第 ⑥ 步已起 `TickSchedulerThread` 与 dispatcher 的 tick 线程） |
| 8 | 就绪日志出现后立刻用探针连接并发 `C2S_HELLO` | 3 秒内收到 `S2C_HELLO`（configuration 阶段的入站包只在 tick 里被解释；tick 没起来这一步必然超时成 `synaxis:client-mod-required`） |
| 9 | 全程用 `lsof -p <pid> \| grep -i 'synx\|\.map'` 或等价手段查地图文件句柄，并在停机后 `git status --porcelain` | 无任何地图文件被打开或写出（M2 不落盘，`.synx` 已推 M3，见设计文档 §28.3 第 ② 条）；工作区干净 |

后置：`stop`。

#### `TC-GAME-030` 损坏或缺失的地图包不产生半载入世界 — 对应 `REQ-M2-010` · 级别: 回归 · 执行者: AI

**已废弃，被 `TC-GAME-056` 取代**（设计文档 v0.6：`.synx` 文件格式整块推到 M3，M2 的地图在进程内生成，既没有 `magic` / `header.version` 可改、也没有文件可截断或指错路径；`creating → ended`（reason=map-invalid）的触发条件改成「未知预设 id / 非法生成参数」。判据与前置都变了，按惯例换 ID。补回 `.synx` 时仍用本 ID，见设计文档 §28.3 第 ② 条）。

前置：有一份可用 `.synx` 的副本可随时还原。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 把 `.synx` 头 4 字节改成 `XXXX` 后启动 | 日志含 ERROR 与 `magic`；`grep match_ended logs/events-*.jsonl \| tail -1` 的 `reason` 为 `map-invalid`；进程随即以非零码退出、端口未 bind（`API-M2-010` 启动序列第 ⑤ 步：没有 `running` 对局即退出） |
| 2 | 在同一次启动的日志里查区块载入与 `match_started` | 无区块载入行；`grep match_started logs/events-*.jsonl` 在本次启动区间内 0 行（不创建任何区块） |
| 3 | 把 `header.version` 改成「当前支持版本 + 1」后启动 | 同样 `reason=map-invalid`，日志打印读到的版本号（读到更高版本直接拒绝，不做向前兼容）；进程同样以非零码退出、端口未 bind |
| 4 | 把文件从中间截断一半后启动 | 对局进入 `ended`，`reason` 为 `map-invalid` 或 `load-failed`；本次启动区间内 `match_started` 0 行；进程以非零码退出、端口未 bind |
| 5 | 把 `synaxis.app.map` 指向一个不存在的路径后启动 | 进程以非零码退出并打印该路径；`nc -z 127.0.0.1 25565` 退出码非 0（端口未 bind） |

后置：用副本还原 `.synx`，确认第 1 步的地图能正常载入。

#### `TC-GAME-056` 非法地图参数不产生半载入世界 — 对应 `REQ-M2-010` · 级别: 回归 · 执行者: AI

前置：`REQ-M2-010` 已合入；已知一个可用的地图预设 id（见 §3 地图种子）。生成参数的改写方式：预设是 `game-rts` 的 `MapPreset` 常量，第 3、4 步临时改那里的数字重新构建，跑完还原（第 6 步与后置查工作区干净）。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 把 `synaxis.app.map` 设为一个不存在的预设 id（如 `no-such-map`）后启动 | 日志含 `ERROR plugin.rts - Unknown map preset: map_id=no-such-map, known presets are [...]`；`grep match_ended logs/events-*.jsonl \| tail -1` 的 `reason` 为 `map-invalid`；随后两行 ERROR 分别说明没有准入回调与没有 `running` 对局，进程以非零码退出（启动序列第 ⑤ 步） |
| 2 | 在同一次启动的日志里查区块生成与 `match_started` | 无区块生成行；本次启动区间内 `grep match_started logs/events-*.jsonl` 为 0 行（不创建任何区块） |
| 3 | 把预设的 `size_x` 改成 500（不是 16 的倍数）后启动 | 同样 `reason=map-invalid`，日志打印该非法参数与约束「必须是 16 的倍数」；进程以非零码退出 |
| 4 | 把预设的 `height` 改成 0 后启动 | 同样 `reason=map-invalid`，日志指向「必须是 16 的倍数且 ≥ 16」；进程以非零码退出 |
| 5 | 对第 1、3、4 三次启动各执行 `nc -z 127.0.0.1 25565` | 三次退出码都非 0（端口从未 bind，不是 bind 之后让玩家撞 `spawningInstance` 的 NPE） |
| 6 | 把预设 id 与参数改回可用值后启动 | 进入就绪；`match_started` 恰一行，`chunk_count` 为 `1024` |

后置：确认 `git status --porcelain` 无输出（参数改写如果落在仓内文件上，必须还原）。

#### `TC-GAME-031` 边界外的区块列不入队也不报错 — 对应 `REQ-M2-011` · 级别: 回归 · 执行者: AI

前置：对局 `running`，`autoChunkLoad=false`；探针 in_game。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 用探针上报一个远超地图边界的镜头锚点（如 `anchor_x=99999`） | 玩家实体被夹取到地图内：随后的实体位置包坐标不超出地图边界 |
| 2 | 查看服务端 stdout | 无 ERROR 行、无 `NullPointerException`、无 `IllegalArgumentException` |
| 3 | 把锚点贴着边界来回移动 60 秒，记录探针收到的区块坐标 | 收到的区块全部落在地图范围内；不出现「请求过但永远收不到」的区块（对照第 4 步的记账） |
| 4 | 运行 `./gradlew :test --tests 'io.github.bearisbug.synaxis.entity.player.PlayerChunkQueueIntegrationTest' --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | `BUILD SUCCESSFUL`；`aColumnOutsideTheLoadedMapIsNeverHandedOverAsNull` 断言越界列既不被交给 `sendChunk`（即 `sendChunk(null)` 的 NPE 不再发生）也不进区块包，地图内的列照常送达 |

后置：无。

#### `TC-GAME-032` 无可用对局时的准入与启动断言 — 对应 `REQ-M2-011` · 级别: 回归 · 执行者: AI

**已废弃，被 `TC-GAME-057` 取代**（设计文档 v0.18：核心模式落地后，第 1 步「排除掉唯一创建对局的插件即非零码退出、端口未 bind」在**默认配置下不再成立**——一个插件都不参与启动时进程改为以核心模式起服并照常 bind，要拿到拒启必须同时给 `-Dsynaxis.app.core-mode=off`。第 1 步的判据实质变更，按惯例换 ID；第 2～5 步在替代用例里逐字保留）。

前置：`REQ-M2-011` 已合入。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 用 `synaxis.app.plugins` 排除掉唯一创建对局的插件后启动 | 进程以非零码退出，日志两行分别为 `No admission provider: …` 与 `No running match: …（found 0）`，随后 `Not binding 0.0.0.0:<port>.`（启动序列第 ⑤ 步的两条断言各报各的）；`nc -z 127.0.0.1 25565` 退出码非 0（不是 bind 之后让玩家撞 NPE） |
| 2 | 正常启动，让唯一对局结束，再用探针握手。**M2 没有运维侧的结束入口**——对局随插件 `onDisable` 结束，等同停机，所以本步骤在 M2 只能由「准入回调在 `match_state ≠ running` 时回 `Reject`」这条代码路径保证，真机档等阶段 4 探针实现 `C2S_HELLO` 后连同「运维结束对局」的入口一起补 | 探针收到 `S2C_HELLO` 之后被断开，原因键 `synaxis:match-unavailable` |
| 3 | `grep client_rejected logs/events-*.jsonl \| tail -1` | `reason` 为 `match-unavailable` |
| 4 | 查看服务端 stdout | 无 `spawningInstance` 相关的 `NullPointerException`，无 `synaxis:internal-error` |
| 5 | `./gradlew :game-rts:test --tests '*MatchIntegrationTest.endMarksTheMatchEndedBeforeItTearsTheInstanceDown'` | `BUILD SUCCESSFUL`：对局在拆掉自己的世界**之前**就已经是 `ENDED`，逐块卸载期间采样到的状态没有一次是 `RUNNING`（准入回调在另一条线程上读它，读到 `RUNNING` 就会把一个正在注销的实例交出去） |

后置：`stop`。

#### `TC-GAME-057` 无可用对局时的准入与拒启断言 — 对应 `REQ-M2-011`、`REQ-M2-024` · 级别: 回归 · 执行者: AI

前置：`REQ-M2-011` 已合入；核心模式已合入（设计文档 v0.18）。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 用 `synaxis.app.plugins` 排除掉唯一创建对局的插件、**并加 `-Dsynaxis.app.core-mode=off`** 后启动 | 进程以非零码退出，日志两行分别为 `No admission provider: …` 与 `No running match: …（found 0）`，随后 `Not binding 0.0.0.0:<port>.`（启动序列第 ⑤ 步的拒启分支，缺的那一半各报各的）；`nc -z 127.0.0.1 25565` 退出码非 0（不是 bind 之后让玩家撞 NPE）。**不给 `core-mode=off` 时是另一档**（进核心模式并照常 bind），由 `TC-GAME-058` 判 |
| 2 | 正常启动，让唯一对局结束，再用探针握手。**M2 没有运维侧的结束入口**——对局随插件 `onDisable` 结束，等同停机，所以本步骤在 M2 只能由「准入回调在 `match_state ≠ running` 时回 `Reject`」这条代码路径保证，真机档等阶段 4 探针实现 `C2S_HELLO` 后连同「运维结束对局」的入口一起补 | 探针收到 `S2C_HELLO` 之后被断开，原因键 `synaxis:match-unavailable` |
| 3 | `grep client_rejected logs/events-*.jsonl \| tail -1` | `reason` 为 `match-unavailable` |
| 4 | 查看服务端 stdout | 无 `spawningInstance` 相关的 `NullPointerException`，无 `synaxis:internal-error` |
| 5 | `./gradlew :game-rts:test --tests '*MatchIntegrationTest.endMarksTheMatchEndedBeforeItTearsTheInstanceDown'` | `BUILD SUCCESSFUL`：对局在拆掉自己的世界**之前**就已经是 `ENDED`，逐块卸载期间采样到的状态没有一次是 `RUNNING`（准入回调在另一条线程上读它，读到 `RUNNING` 就会把一个正在注销的实例交出去） |
| 6 | 让唯一的插件参与启动却什么都不留下（用 `-Dsynaxis.app.map=no-such-map`，判据同 `TC-GAME-056` 第 1 步），**不给** `core-mode=off` | 仍然是两行 ERROR + 非零码退出、端口未 bind——核心模式的条件是「一个插件都没有参与启动」，坏掉的插件不会被核心世界盖过去 |

后置：`stop`。

#### `TC-GAME-058` 核心模式：没有玩法插件时服务端核心自己跑起来 — 对应 `REQ-M2-021`、`REQ-M2-011` · 级别: 冒烟 · 执行者: AI

前置：核心模式已合入（设计文档 v0.18 的 `API-M2-010` 启动序列第 ⑤ 步）；端口 25565 空闲。探针见 §3。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 用 `-Dsynaxis.app.plugins=none` 起服（排除掉唯一的玩法插件，`core-mode` 保持默认 `auto`） | stdout 出现 `Core mode (synaxis.app.core-mode=auto): …  channel=synaxis:main …` 与 `Registered channel: plugin_id=synaxis channel=synaxis:main`，**没有** `No admission provider` / `No running match` 两行 ERROR；随后出现就绪日志 `synaxis server-app listening on 0.0.0.0:25565`，`nc -z 127.0.0.1 25565` 退出码 0。另有一行 `No plugin answers to selected ids [none]: discovered plugin_ids=[rts].` 的 WARN——`none` 不是任何插件的 id，这正是它排除掉全部插件的方式；这行也是把 id 拼错（`rst`）与刻意排除区分开的唯一线索 |
| 2 | `python3 tools/protocol-probe/probe.py login --hold 2` | 退出码 0（任意用户名都放行进核心世界，保持期间不被断线） |
| 3 | `python3 tools/protocol-probe/probe.py echo --payload ping` | 退出码 0，打印 `echo channel=synaxis:main payload=ping`（宿主的诊断通道原样回发） |
| 4 | 向 stdin 输入 `stop` | 进程在 15 秒内退出、退出码 0；stdout 无 ERROR 行、无异常堆栈 |
| 5 | 用 `-Dsynaxis.app.core-mode=on` 起服（**不排除**任何插件） | 仍然是核心模式（`Core mode (synaxis.app.core-mode=on)`），且**没有任何** `Plugin enabled: plugin_id=…` 与 `Match running:` 行——强制核心模式连插件发现都不做 |
| 6 | 不带任何 `synaxis.app.*` 属性起服 | 玩法模式：stdout 出现 `Gameplay mode: … plugin_ids=[rts]` 与 `Match running: …`，**没有** `Core mode` 行（玩法优先，核心模式不介入） |
| 7 | `./gradlew :server-app:test --tests 'io.github.bearisbug.synaxis.app.CoreModeTest' --tests 'io.github.bearisbug.synaxis.app.CoreModeIntegrationTest' --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | `BUILD SUCCESSFUL`：`CoreModeTest` 覆盖第 ⑤ 步三分支与 `synaxis.app.core-mode` 的解析（含非法值拒启、「插件参与了却什么都没留下」仍拒启），`CoreModeIntegrationTest` 覆盖核心世界已注册且有地面、默认准入放行到该世界、`synaxis:main` 原样回发 |

后置：`stop`；`rm -rf plugins logs` 回到基线。

#### `TC-GAME-059` 稳定面不把引擎内部类型递给插件 — 对应 `REQ-M2-002` · 级别: 回归 · 执行者: AI

前置：`:server-app` 测试源集里的 `StableSurfaceArchitectureTest` 已合入（设计文档 §24 规则④）。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `./gradlew :server-app:test --tests '*StableSurfaceArchitectureTest*' --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | `BUILD SUCCESSFUL` |
| 2 | 在同一次运行的测试报告里看这四条用例 | 两条现状断言（`theStableSurfaceReturnsNoEngineInternal`、`theStableSurfaceAsksForNoEngineInternal`）与两条规则自测（`theReturnTypeRuleRejectsALeakedInternal`、`theParameterRuleRejectsALeakedInternal`）全部通过。自测断言的是违规消息里点名了那个方法名——ArchUnit 的 `failOnEmptyShould` 在 `that()` 一个成员都选不中时抛的也是 `AssertionError`，只判异常类型会把「规则退化成空规则」也判成通过 |
| 3 | 临时给 `:plugin-api` 的任一公开接口加一个返回 `@ApiStatus.Internal` 类型（如 `io.github.bearisbug.synaxis.network.packet.server.CachedPacket`）的方法，重跑第 1 步，然后撤销 | 第 1 步失败于 `theStableSurfaceReturnsNoEngineInternal`；撤销后 `git status --porcelain` 无输出 |
| 4 | `grep -rn "DoNotIncludeJars" server-app/src/test --include='*.java'` | 退出码 1、无输出（`:plugin-api` 以 jar 形式进测试运行时 classpath，排除 jar 会让规则一个类都扫不到；合成违规夹具住在同一个包里，靠 `DoNotIncludeTests` 排除） |

后置：确认工作区干净。

#### `TC-GAME-033` `SharedInstance` 已删除且有玩家的区块拒绝卸载 — 对应 `REQ-M2-012` · 级别: 回归 · 执行者: AI

前置：`REQ-M2-012` 已合入。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `grep -rn "SharedInstance" src testing protocol plugin-api server-app game-rts --include='*.java'` | 退出码 1、无输出（全库零命中） |
| 2 | `grep -rn "getSharedInstances\|createSharedInstance\|areLinked" src --include='*.java'` | 退出码 1、无输出 |
| 3 | 运行 `./gradlew :test --tests 'io.github.bearisbug.synaxis.instance.UnloadChunkIntegrationTest' --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | `BUILD SUCCESSFUL`；`unloadingChunkWithPlayerInsideIsRejected` 断言 `unloadChunk` 抛 `IllegalStateException` 且区块状态零变更 |
| 4 | `./gradlew check --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | `BUILD SUCCESSFUL`（删掉 `SharedInstance` 与它的集成测试、改完五处引用之后，编译与既有测试全绿） |

后置：无。

#### `TC-GAME-034` 断线玩家不会变成 tracker 幽灵 — 对应 `REQ-M2-013` · 级别: 回归 · 执行者: AI

前置：`REQ-M2-013` 的修复提交已合入；复现测试是 `io.github.bearisbug.synaxis.instance.UnloadChunkIntegrationTest#unloadingChunkRemovesADisconnectedPlayer`。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | **只回退实现、保留测试**：`git checkout <修复提交>^ -- src/main/java/io/github/bearisbug/synaxis/instance/InstanceContainer.java`，再运行该复现测试（修复与复现测试在同一个提交里，整树 `git checkout <修复提交>^` 会把测试方法一起带走，那样跑出来是绿的） | 测试**失败**（先红），第一条红的是 `assertTrue(player.isRemoved())`，消息 `the disconnected player survived the unload of its chunk`；其后的 `getEntityByUuid(该玩家)` 返回非 null 同样会红 |
| 2 | `git checkout HEAD -- src/main/java/io/github/bearisbug/synaxis/instance/InstanceContainer.java` 后运行同一测试 | `BUILD SUCCESSFUL`（后绿）；断言该玩家被同步移除且 `getEntityByUuid` 返回 null |
| 3 | 真机复现：探针握手登录后立刻断开，随即 `stop`（局末 `unregisterInstance` 逐块卸载是最高频触发场景） | 进程在 15 秒内退出、退出码 0；stdout 无残留实体相关 ERROR、无线程转储 |
| 4 | 查看停机日志 | 含 `unregisterInstance` 完成行；无「玩家仍在区块内」类告警 |

后置：第 2 步已把实现文件恢复到 `HEAD`，确认 `git status --porcelain` 无输出。

#### `TC-GAME-035` 视距收缩后两端区块集合一致 — 对应 `REQ-M2-014` · 级别: 回归 · 执行者: AI

前置：`REQ-M2-014` 的修复提交已合入；复现测试是 `io.github.bearisbug.synaxis.entity.player.PlayerChunkQueueIntegrationTest` 的两条——`shrinkingTheViewDistanceDropsThePendingChunksItUnloaded`（待发队列里的越界区块不再发出）与 `shrinkingTheViewDistanceUnloadsAroundTheRingTheClientHas`（收缩以客户端环的中心为准）。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | **只回退实现、保留测试**：`git checkout <修复提交>^ -- src/main/java/io/github/bearisbug/synaxis/entity/Player.java`，再运行那两条复现测试（修复与复现测试在同一个提交里，整树 `git checkout <修复提交>^` 会把测试方法一起带走，那样跑出来是绿的） | 两条测试都**失败**（先红）。注意同一个文件上还压着更晚的 `REQ-M2-011` 修复，回退到 `<修复提交>^` 会把它一起带走，所以同类里的 `aColumnOutsideTheLoadedMapIsNeverHandedOverAsNull` 也会红——判据只看上面那两条 |
| 2 | `git checkout HEAD -- src/main/java/io/github/bearisbug/synaxis/entity/Player.java` 后运行同一测试 | `BUILD SUCCESSFUL`（后绿）：已判定卸载的区块不再出队发送 |
| 3 | 真机：探针以视距 12 登录，稳定后改报视距 4 | 探针按收到的 `ChunkData` 与 `UnloadChunk` 记账，得到一个区块集合 |
| 4 | 与第 3 步的集合对比服务端的记账。`chunksLoadedByClient` 没有对外入口，两处判据改为：① 第 2 步那两条测试绿（服务端的出队与收缩都以它为中心）；② 第 3 步收到的 `ChunkData` 减去 `UnloadChunk` 之后，剩下的区块全部落在视距 4 的环内 | 两个集合完全相同（没有「服务端以为发了、客户端没有」的区块） |
| 5 | 查看服务端 stdout | 无 ERROR、无异常堆栈 |

后置：第 2 步已把实现文件恢复到 `HEAD`，确认 `git status --porcelain` 无输出。

#### `TC-GAME-036` 游戏侧结构化 tag 无条件禁止 — 对应 `REQ-M2-015` · 级别: 回归 · 执行者: AI

前置：`REQ-M2-015` 已合入；规则住在 `:game-rts` 自己的测试源集里。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `./gradlew :game-rts:test --tests '*Architecture*' --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | `BUILD SUCCESSFUL`；跑的是 `io.github.bearisbug.synaxis.game.rts.ArchitectureTest` 的三条——`gameSideDoesNotUseTheStructuredTags`（游戏侧现状不碰这三者）、`theRuleRejectsAGameClassCallingTheTagIndex`（规则对一个合成的违规类真的会红，排除空规则恒绿）与 `theRuleRejectsAGameClassReferencingTheTagIndex`（同一个违规写成方法引用 `TagHandler::fromCompound` 也会红——ArchUnit 把方法引用建模成 `JavaMethodReference`，不进 `getMethodCallsFromSelf()`，规则若用 `callMethodWhere` 就会放过它） |
| 2 | `grep -rn "Tag.Structure\|Tag.View\|TagHandler.fromCompound" game-rts/src/main/java` | 退出码 1、无输出 |
| 3 | 在 `game-rts` 任一类临时加一处 `Tag.Structure(...)` 调用并重跑第 1 步，然后撤销 | 第 1 步失败并指向该调用点（无条件禁止，与 compound 来源无关）；撤销后 `git status --porcelain` 无输出 |
| 4 | `grep -n "untrusted data" src/main/java/io/github/bearisbug/synaxis/tag/Tag.java src/main/java/io/github/bearisbug/synaxis/tag/TagHandler.java`（引擎源码里的注释与 Javadoc 一律英文，判据按原文写） | 两个文件各至少一处命中：`Tag.Structure` / `Tag.View` / `TagHandler.fromCompound` 的 Javadoc 都写明不可用于不可信数据 |
| 5 | `grep -rln "Tag.Structure" src/test --include='*ArchitectureTest*'` | 无输出（该规则不在引擎的 `ArchitectureTest` 里——写在那边 classpath 上没有游戏类，规则恒绿） |

后置：确认工作区干净。

### M2 · 单位、寻路与镜头

#### `TC-GAME-037` 单位建模五条硬规则生效 — 对应 `REQ-M2-016` · 级别: 回归 · 执行者: AI

**已废弃，被 `TC-GAME-052` 取代**（设计文档 v0.4：第 5 条建模规则的期望值从写死的 `setViewRange ≥ 1.6` 改为按 `archetype_view_range` 配置且取 4.0——客户端剔除半径还要乘「实体距离」视频选项的 0.5×～5× 全局倍率，50% 档下 1.6 只有 51 格有效半径，撑不到 97 格；第 5 步的判据也随之要分服务端跟踪与客户端渲染两层写。阈值与判据都变了，按惯例换 ID）。

前置：对局 `running` 且至少有一个单位；建模断言测试名待 `REQ-M2-016` 实现后填。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 运行单位建模断言测试 | `BUILD SUCCESSFUL`；报告中能点名五条断言——`hasPhysics=false`、`noGravity=true`、spawn 时 `setPosRotInterpolationDuration(2)`、`synchronizationTicks=100`、`setViewRange ≥ 1.6` 且 `setWidth` / `setHeight` 按 `archetype_box` 为非 0 |
| 2 | `grep -rn "setTransformationInterpolationDuration" game-rts/src/main/java` | 退出码 1、无输出（位置插值不得错设成变换插值） |
| 3 | 让一个单位移动 10 秒，用探针记录它的实体位置包 | 位置包按每 2 tick 一条到达，不是每 20 tick 一次的跳变 |
| 4 | 在同一段记录里统计周期性位置重同步包 | 约每 100 tick 一条（重同步被调大而不是关掉，保留纠正通道） |
| 5 | 把锚点推到让该单位落在离玩家实体约 97 格处 | 探针未收到该实体的 `DestroyEntities`（服务端 `synaxis.entity-view-distance=8` 即 128 格，97 格仍在跟踪范围内；display 专属的客户端剔除半径 `view_range × 64` 只管客户端渲染，探针观察不到，屏幕上是否真渲染由 TC-GAME-047 人工判定） |

后置：无。

#### `TC-GAME-052` 单位建模五条硬规则生效（`view_range` 按 archetype 配置） — 对应 `REQ-M2-016` · 级别: 回归 · 执行者: AI

前置：对局 `running` 且至少有一个单位。建模断言测试是 `game-rts` 的 `UnitModelIntegrationTest`，它自己起一个 `running` 对局并在里面 spawn 单位。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `./gradlew :game-rts:test --tests 'io.github.bearisbug.synaxis.game.rts.UnitModelIntegrationTest' --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | `BUILD SUCCESSFUL`；`theDisplayEntityIsBuiltToTheFiveModellingRules` 里能点名五条断言——`hasPhysics=false`、`noGravity=true`、spawn 时 `setPosRotInterpolationDuration(2)`、`synchronizationTicks=100`、`setViewRange(archetype_view_range)` 且 `setWidth` / `setHeight` 按 `archetype_box` 为非 0 |
| 2 | 在断言测试里查 `setViewRange` 的入参来源 | 取自该原型的 `archetype_view_range` 配置项、当前值为 `4.0`；**测试里不得出现写死的 `1.6` 或 `4.0` 字面量与实体元数据直接比对**（写死临界数正是本用例取代 TC-GAME-037 的原因） |
| 3 | `grep -rn "setTransformationInterpolationDuration" game-rts/src/main/java` | 退出码 1、无输出（位置插值不得错设成变换插值） |
| 4 | 让一个单位移动 10 秒，用探针记录它的实体位置包 | 位置包按每 2 tick 一条到达，不是每 20 tick 一次的跳变 |
| 5 | 在同一段记录里统计周期性位置重同步包 | 约每 100 tick 一条（重同步被调大而不是关掉，保留纠正通道） |
| 6 | 把锚点推到让该单位落在离玩家实体约 97 格处 | 探针未收到该实体的 `DestroyEntities`（服务端 `synaxis.entity-view-distance=8` 即 128 格，97 格仍在跟踪范围内）——这一步只判**服务端跟踪**这一层 |
| 7 | 计算该原型在「实体距离」50% 档下的客户端有效剔除半径（`archetype_view_range × 64 × 0.5`） | ≥ 97 格（4.0 → 128 格，有余量）；**客户端是否真渲染由 TC-GAME-047 第 3、4 步人工判定**，探针观察不到 |

后置：无。

#### `TC-GAME-038` 选中框本地绘制且他人不可见 — 对应 `REQ-M2-016`、`REQ-M2-019` · 级别: 回归 · 执行者: 人工

前置：`tester1` 与 `tester2` 两个装了模组的客户端同时在线，都能看见同一个属于 `tester1`（`team=1`）的单位。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `tester1` 左键点中该单位 | `tester1` 屏幕上该单位出现选中框 |
| 2 | 同时观察 `tester2` 的屏幕 | 该单位没有任何视觉变化（不发光、无框、无角标） |
| 3 | 用探针或抓包观察服务端下行 | 该期间没有下发任何与选中相关的实体元数据包（`HAS_GLOWING_EFFECT` 等一条没有） |
| 4 | `grep unit_selected logs/events-*.jsonl \| tail -1` | 有一行，`count` 为 1、`rejected_count` 为 0 |
| 5 | `tester1` 点空地取消选中 | 选中框消失；模组发一条空的 `C2S_SELECT`（射线没拾到单位时按整集合替换语义报空集），服务端记账的选中集合随之清空——`grep unit_selected logs/events-*.jsonl \| tail -1` 那一行是 `count=0`、`rejected_count=0` |

后置：两个客户端退出。

#### `TC-GAME-039` 非己方与中立单位的选中与下令被拒 — 对应 `REQ-M2-019` · 级别: 回归 · 执行者: AI

前置：对局内有一个 `team=0` 的中立单位 N 与一个属于对方队伍的单位 O（种子见 §3）；探针的队伍为 1。

**N 与 O 已随 `REQ-M2-016` 落地（阶段 5）**：`unit_id=65` 是中立单位 N、`unit_id=66` 是对方单位 O（§3 单位种子），本方单位 U 取 `1`。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `python3 tools/protocol-probe/probe.py select --units <N>` | 无任何回包，连接不断线（静默剔除，选中不回错） |
| 2 | 对 N 下一条目标合法的移动指令 | 收到 `S2C_ORDER_RESULT{code=not-owner}`（中立单位对任何人都不是 owner） |
| 3 | 对 O 下一条目标合法的移动指令 | 同样 `code=not-owner` |
| 4 | `python3 tools/protocol-probe/probe.py order --select 1,<N> --unit 1 --x <可站立 x> --y 40 --z <可站立 z> --expect ok --expect-count 1`（同一条连接上先 `C2S_SELECT{unit_ids=[U, N]}`、再对 U 下令） | 连接不断线；对 U 下令回 `code=ok`（合法项未被连坐剔除）；`grep unit_selected logs/events-*.jsonl | tail -1` 那一行是 `count=1`、`rejected_count=1` |
| 5 | `python3 tools/protocol-probe/probe.py select --units 404`（一个不存在的 `unit_id`） | 连接不断线、无回错；服务端 stdout 无异常堆栈 |

后置：无。

#### `TC-GAME-040` 无客户端脚本下令，单位按路径移动并到达 — 对应 `REQ-M2-017` · 级别: 冒烟 · 执行者: AI

前置：对局 `running`；探针 in_game 并持有本方单位 U；目标点选在 20 格外的空旷可站立区。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `python3 tools/protocol-probe/probe.py order --unit 1 --x <目标 x> --y 40 --z <目标 z> --expect ok --expect-count 1 --watch 25` | 300 ms 内收到 `S2C_ORDER_RESULT{code=ok}`，`snapped_*` 与目标点的水平距离 < 1 格 |
| 2 | 看同一次运行里随后的 `S2C_UNIT_STATE` 行 | U 的 `unit_state` 变为 `1`（moving），即探针打印的 `moving=[1]` |
| 3 | 观察引擎的实体位置包（记账入口待 `REQ-M2-018` 实现后填；服务端侧由 `UnitMovementIntegrationTest#theWalkIsMadeOfAdvancesRatherThanJumpsAndTeleportsNothing` 判） | U 对应 `entity_id` 的位置沿航点连续推进，相邻两条位置差 < 1 格（无瞬移） |
| 4 | 等待到达（`--watch` 那一段自己等） | 末行 `seen_moving=True unit_state=idle`，即 `unit_state` 回到 `0`；最终位置与 `snapped_*` 的距离 < 0.1 格（服务端侧由 `UnitMovementIntegrationTest#aUnitWalksToWhereItWasOrderedAndGoesIdleThere` 判） |
| 5 | `grep unit_order_completed logs/events-*.jsonl \| tail -1` | 有一行，`order_seq` 与第 1 步一致、`elapsed_ms` 为正数 |

后置：无。

#### `TC-GAME-041` 不可达与越界目标得到明确拒绝 — 对应 `REQ-M2-017` · 级别: 回归 · 执行者: AI

前置：测试地图含一块被墙完全围住的可站立区域（§3 种子）；探针 in_game 持有本方单位 U（U 在墙外）。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `python3 tools/protocol-probe/probe.py order --unit 1 --x 24.5 --y 40 --z 24.5 --expect no-path --expect-count 1`（`rts-128` 上口袋在 `[16,32)²`，`rts-512` 上在 `[64,80)²`） | 收到 `code=no-path`；U 的 `unit_state` 保持 `0`（idle） |
| 2 | `python3 tools/protocol-probe/probe.py order --unit 1 --x -4 --y 40 --z 4 --expect out-of-bounds --expect-count 1` | 收到 `code=out-of-bounds` |
| 3 | 对 U 下令去一个不可站立的点（地图里任一障碍格，`--x/--z` 取其列中心） | 收到 `code=out-of-bounds` |
| 4 | `grep unit_order_rejected logs/events-*.jsonl \| tail -3` | 三行的 `code` 依次为 `no-path`、`out-of-bounds`、`out-of-bounds` |
| 5 | 再对 U 下一条合法指令 | 收到 `code=ok` 并开始移动（前面的拒绝没有把单位卡住） |

后置：无。

#### `TC-GAME-042` 寻路积压不拖垮 tick — 对应 `REQ-M2-017` · 级别: 边缘 · 执行者: AI

前置：对局内有 ≥ 64 个本方单位（§3 压测预设）；探针能在同一 tick 内发出 64 条指令。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `python3 tools/protocol-probe/probe.py order --count 64 --group-size 8 --unit 1 --x <可站立点 x> --y <y> --z <可站立点 z> --wait 10`（一条连接连发 64 条，`--group-size 8` 把它们切成 8 个 `order_group`、每组 8 包，正好是组内包数的上限） | 64 条各收到一条 `S2C_ORDER_RESULT`，且一条 `code=rate-limited` 都没有（8 组只扣 8 个令牌，未到 10 操作/秒） |
| 2 | 按 `unit_order_issued` 事件的时间戳分桶统计 | 每 tick 被真正处理的请求 ≤ 4 条，其余排队 |
| 3 | 按接收顺序统计第 1 步这 64 条结果的 `code` | 至少 32 条 `code=ok`，其余全为 `code=busy`（队列深度上限 32，见设计文档 §11.4 `ACCEPT` 的短路顺序），两者条数之和为 64 |
| 4 | 采样 60 秒内 `unit_order_issued` 的 `path_ms` 与 `path_nodes` | `path_ms` P99 < 2 ms；`path_nodes` 单次 ≤ 4,000 |
| 5 | 同期观察引擎 tick 日志 | 无 tick 超时告警行（tick P99 的量化档按 §2 不测表归 `docs/DESIGN-M2.md` §23 的专项脚本，本用例不判） |

后置：无。

#### `TC-GAME-065` 远处空旷目标回 `ok`，`no-path` 只留给围住了 — 对应 `REQ-M2-017` · 级别: 回归 · 执行者: AI

前置：进程以**玩法模式**、**默认预设 `rts-512`** 就绪（`synaxis.app.map` 不设即是；这一条只在大图上有意义）；探针 in_game 持有本方单位 U（`unit_id=1`，站在出生点周围一圈，出生点是地图正中最近的可站立列）。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `python3 tools/protocol-probe/probe.py order --unit 1 --x 456.5 --y 40 --z 336.5 --expect ok --expect-count 1`（东南方约 215 格的空旷可站立点） | 收到 `code=ok`、`snapped=(456.5, 40.0, 336.5)`——目标空旷、可站立、与出生点连通，答案不能是 `no-path` |
| 2 | 对四个地图角各下一条：`--x 4.5 --z 4.5`、`--x 507.5 --z 4.5`、`--x 4.5 --z 507.5`、`--x 507.5 --z 507.5` | 四条全部 `code=ok`（各离出生点约 356 格，覆盖整图对角，节点预算够到地图边缘） |
| 3 | `grep unit_order_issued logs/events-*.jsonl \| tail -1` | 第 1 步那次搜索的 `path_nodes` 远小于 4,000（实测 201～206）、`path_ms=0`——它是找到了，不是撞上预算 |
| 4 | `python3 tools/protocol-probe/probe.py order --unit 1 --x 72.5 --y 40 --z 72.5 --expect no-path --expect-count 1`（被墙围住的口袋，`rts-512` 上在 `[64,80)²`），再 `grep unit_order_rejected logs/events-*.jsonl \| tail -1` | 收到 `code=no-path`；台账那一行的 `path_nodes > 4,000`（实测 `4001`）——「吃满预算才收手」与第 3 步的「找到了」在台账里分得开（`RB-M2-003` 的诊断依赖这一点） |
| 5 | `./gradlew :game-rts:test --tests 'io.github.bearisbug.synaxis.game.rts.PathfinderTest' --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | `BUILD SUCCESSFUL`；`aFarTargetOnOpenGroundIsRoutedToRatherThanGivenUpOn` 绿——从地图中心向八个罗盘方向各 200 格，每个方向最近的可站立列都有路，且拉绳之后走过的距离小于直线距离的 1.25 倍（偏置买到的距离没有换成绕路） |

后置：无。

#### `TC-GAME-068` 50 玩家 × 1,000 单位这一档的四条 §15 预算有测量手段 — 对应 `REQ-M2-016`、`REQ-M2-017`、`REQ-M2-009` · 级别: 回归 · 执行者: AI

前置：机器安静（没有别的构建、压测或模拟器在跑——第 2、3 步的实测参考值只在这种条件下才能填）；`jcmd` / `jfr` 在 PATH（`export PATH="$JAVA_HOME/bin:$PATH"`）；端口 25566 空闲；`mkdir -p /tmp/synaxis-play`；harness 的动作、`summary.json` 每个字段的含义与对应的设计文档 §15 行见 `docs/research/soak-2026-09-22/README.md`。四条数字来自设计文档 §15 的「tick 预算」「单位快照」「内存」「寻路开销」四行。**读数是最坏档**：默认播种把全部单位铺在出生点周围，锚点离出生点 128 格内的玩家看见全部单位、`S2C_UNIT_STATE` 顶到 512 条上限（`max_entries=512`），单位快照那一行读到的是上限而不是「视野 50 单位」的画像值（设计文档 §15 表后的说明）。

| # | 操作 | 预期 | 实测参考值 |
| --- | --- | --- | --- |
| 1 | 起服：`mkfifo /tmp/synaxis-play/stdin.fifo; ( sleep 86400 > /tmp/synaxis-play/stdin.fifo & ); ./gradlew :server-app:run --no-daemon --console=plain --init-script gradle/init/aliyun-mirror.init.gradle.kts -Dsynaxis.app.port=25566 -Dsynaxis.app.units=1000 < /tmp/synaxis-play/stdin.fifo > /tmp/synaxis-play/server.log 2>&1 &`，等 `server.log` 出现就绪行；再 `python3 tools/protocol-probe/probe.py --port 25566 units --wait 6 --expect-min 999` | `synaxis server-app listening on 0.0.0.0:25566`，其前有 `Match running: … map_id=rts-512 … chunk_count=1024` 与 `Plugin enabled: plugin_id=rts`；`nc -z 127.0.0.1 25566` 退 0；探针 `units=999`（远哨 `unit_id=1000` 在出生点 220 格外、超出 128 格实体视距）、`commandable` 997 个（`1..997`；远哨 `1000` 也是本方的，但没被看见就不在列表里）、退出码 0 | 2026-09-22 冒烟已核：`units=999`、`commandable` 997 |
| 2 | 门禁档：`python3 tools/loadtest/soak.py play --port 25566 --connections 50 --duration 600 --units 1000 --gc-probe-interval 60 --server-log /tmp/synaxis-play/server.log --out /tmp/synaxis-play/gate`，读 `/tmp/synaxis-play/gate/summary.json` | 退出码 0；`login_failures={}`、`connections_lost=0`、`disconnect_reasons={}`、`units.check` 为 `ok` 或 `ok-outpost-unseen`、`orders.answered` = `orders.sent`（每条下令必回；harness 到截止时刻即关连接，最后一秒内发出的下令收不到回包，差 ≤ 50 条不算红）、`server_log.error_lines=0`、`server_log.kick_lines=0`。四条预算：① `ticks.overall.p99_ms < 50`；② `inbound.rts_game_by_msg.S2C_UNIT_STATE.max_payload_bytes ≤ 256`、`.interval_ms.p50_ms` 在 90～110（10 Hz）、`.bytes_per_s_per_connection ≤ 2,560`、`.bytes_per_s_total ≤ 128,000`；③ `heap.live_mib_max < 2,048`；④ `pathfinding.tick_bucket_ms.p99_ms < 2` 且 `pathfinding.path_nodes_max_issued ≤ 4,000`（`path_nodes_max_rejected` 单独一栏，`no-path` 档吃满预算收手时台账按设计记 4,001，见 TC-GAME-065 第 4 步，它只会是 0 或 4,001、不参与判红）。哪一条不达标就把那个字段的值与 `samples.jsonl` 里同期的 `clients` / `cpu_percent` / `harness_cpu_percent` 一起登记——`harness_cpu_percent` 高于 `cpu_percent` 时瓶颈在 harness，不是服务端 | 2026-09-22 实测（`docs/research/soak-2026-09-22/runs/gate-1000/summary.json`，head `9013cf76fd`，机器上另有一个满核的无关进程）：退出码 0；`login_failures={}`、`connections_lost=0`、`disconnect_reasons={}`、`units.check=ok`（`seen_max=1000`、`commandable_max=998`）、`orders.sent=29903` / `answered=29900`（差 3 条，截止时在飞）、`error_lines=0`、`kick_lines=0`、`warn_lines=57`（全是 `RB-M2-003` 那条「兴趣集超快照上限」的 10 s 预算行）。① `p99_ms=8.617`（P50 2.857、max 32.178、超 50 ms 0 个）绿；② `interval_ms.p50_ms=97.277` 绿，`max_payload_bytes=2023`、`bytes_per_s_per_connection=4420.0`、`bytes_per_s_total=220866.3` **三个红**（7.9 倍 / 1.73 倍 / 1.73 倍），`max_entries=512` 即前置说的最坏档，同期 `cpu_percent` 9.7～28.5、`harness_cpu_percent` ≤ 17.7——瓶颈不在 harness，红的是布局（设计文档 §15 单位快照行的实测段）；③ `live_mib_max=43.0`（`rss_mib_max=230.1`）绿；④ `tick_bucket_ms.p99_ms=1.0`、`path_nodes_max_issued=1552` 绿（`path_nodes_max_rejected=4001`） |
| 3 | 规模曲线：`echo stop > /tmp/synaxis-play/stdin.fifo` 停机，分别以 `-Dsynaxis.app.units=2000` 与 `=4000` 重走第 1 步的起服命令，各跑一次 `python3 tools/loadtest/soak.py play --port 25566 --connections 50 --duration 300 --units <n> --gc-probe-interval 60 --server-log /tmp/synaxis-play/server.log --out /tmp/synaxis-play/scale-<n>` | 两档都：退出码 0、`connections_lost=0`、`server_log.error_lines=0`；逐档登记 `ticks.overall.p99_ms`、`inbound.per_connection_bytes_per_s.engine`、`heap.live_mib_max`、`pathfinding.tick_bucket_ms.p99_ms` 四个数，连同第 2 步的 1,000 档画出 1,000 → 2,000 → 4,000 的曲线；曲线本身不判红绿，它回答的是「哪一档先越过第 2 步的四条线」 | 2026-09-22 实测（`runs/scale-2000`、`runs/scale-4000`）：两档退出码 0、`connections_lost=0`、`error_lines=0`。1,000 → 2,000 → 4,000：`ticks.overall.p99_ms` 8.617 → 9.698 → 11.096；`inbound.per_connection_bytes_per_s.engine` 14456.5 → 20429.3 → 32541.3；`heap.live_mib_max` 43.0 → 51.1 → 64.1；`pathfinding.tick_bucket_ms.p99_ms` 1.0 → 1.0 → 0.0。四条线里只有 ② 在三档都红（`bytes_per_s_per_connection` 4420.0 / 4118.2 / 4513.8，与单位数无关，是 512 条上限的布局效应）；tick、堆、寻路至 4,000 未越线，未见拐点。4,000 档 6,310 个 tick 里 2 个超 50 ms（`ticks.per_minute`：第 0 分钟 `max_ms` 65.403、第 3 分钟 79.431），P99 判据不受影响 |
| 4 | 反证一（播种旋钮有齿）：停机后以 `-Dsynaxis.app.units=3` 重走起服命令（stdout 到 `/tmp/synaxis-play/bad3.log`），再以 `-Dsynaxis.app.units=abc` 起一次（到 `bad-abc.log`）；每次之后 `nc -z 127.0.0.1 25566` | 两次都拒启：`Plugin failed to enable: plugin_id=rts` 的 ERROR 行，紧接的堆栈首行分别是 `IllegalArgumentException: synaxis.app.units=3 is too few: the roster is 3 fixed units plus at least one of the player's own` 与 `IllegalArgumentException: synaxis.app.units=abc is not a number`；随后 `No admission provider`、`No running match … (found 0)`、`Not binding 0.0.0.0:25566` 三行 ERROR；日志里没有就绪行；Gradle 退出码 1；`nc -z` 退 1。**这一步证明第 1 步的 1,000 不是「值被静默换成默认 67」**——旋钮拒绝的值必须拒启，而不是起来一个 67 单位的服 | 2026-09-22 已跑：两次都在 `Plugin failed to enable` 处红、`GRADLE_EXIT=1`、`nc` 退 1 |
| 5 | 反证二（单位数核对有齿）：按第 1 步起回 1,000 单位的服，跑 `python3 tools/loadtest/soak.py play --port 25566 --connections 5 --duration 15 --units 900 --no-jfr --gc-probe-interval 0 --settle 2 --server-log /tmp/synaxis-play/server.log --out /tmp/synaxis-play/mismatch` | 退出码 0（核对不阻断跑，它只是记账），但 `summary.json` 的 `units.check=mismatch`、`units.seen_max` 是 999 或 1,000、`units.expected=900`——`--units` 给错时 harness 必须把它标出来，否则第 2 步的 `ok` 证不了服务端真播了 1,000 个 | 2026-09-22 已跑：`check=mismatch`（`seen_max=1000`、`expected=900`）；正式测量那一轮在跑完门禁档的旧服上重跑得 `seen_max=753`（单位已被 600 s 的下令走散，15 s 看不全）——那次不满足本步「按第 1 步起回 1,000 单位的服」的前置，对着正确的 `--units 1000` 同样会红，不算本步的证据 |
| 6 | 反证三（采不到就不跑）：`echo stop > /tmp/synaxis-play/stdin.fifo` 停机、`lsof -nP -iTCP:25566 -sTCP:LISTEN` 为空后，`python3 tools/loadtest/soak.py play --port 25566 --connections 1 --duration 5 --out /tmp/synaxis-play/noserver`。本机别的端口上有没有服务端（并行 worktree 常见）都不影响本步：harness 按 `--port` 找监听进程，不按进程名找 | 退出码 2，stderr 一行 `no server process listens on TCP port 25566 …`，`--out` 目录里没有 `summary.json`——没有服务端进程可采样时 harness 不得跑出一份看起来干净的产物，也不得采到另一个端口上的 JVM | 2026-09-22 已跑：退出码 2；正式测量那一轮重跑：退出码 2、`--out` 目录里没有 `summary.json` |

后置：`echo stop > /tmp/synaxis-play/stdin.fifo`（若仍在跑）；`rm -rf /tmp/synaxis-play`（含每轮的 `soak.jfr`，一分钟就上 MB）；`rm -rf logs plugins`（服务端在 worktree 根写的运行时产物，已 gitignore）。

#### `TC-GAME-043` 镜头推远后地形与单位进入视野 — 对应 `REQ-M2-018` · 级别: 回归 · 执行者: AI

前置：探针 in_game；出生点 200 格外有单位；已确定出生点所在区块 A 与紧邻它的区块 B 的两个锚点坐标（相距 16 格，一次移动只跨一条区块边界，第 6／7 步依赖）。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 记录当前收到的区块坐标集合与 `S2C_UNIT_SPAWN` 的 `unit_id` 集合 | 两个集合均非空 |
| 2 | 以 5 Hz 持续上报 `C2S_CAMERA`，把锚点从出生点移到 200 格外 | 玩家实体位置包显示它被静默传送到锚点对应的地面点 |
| 3 | 观察 5 秒新收到的 `ChunkData` | 目标区域的区块出现在集合里 |
| 4 | 观察同期的 `S2C_UNIT_SPAWN` / `S2C_UNIT_DESPAWN` | 目标区域的单位通过 `S2C_UNIT_SPAWN` 进入；离开兴趣集的单位有对应 `S2C_UNIT_DESPAWN`（不出现「实体已销毁、快照还在发它」） |
| 5 | 查看服务端 stdout | 无 `NullPointerException`（锚点夹取保证落点在已载入区块内，`refreshCoordinate` 的 `Check.notNull` 不触发），无 ERROR |
| 6 | 把锚点在**相邻区块 A 与 B** 之间来回推 **5 个来回（共 10 次跨区块）**，每次跨越后停 2 秒，全程 5 Hz 上报 | 10 次跨区块每一次之后 2 秒内都至少收到一批新的 `ChunkData`；不出现「某次跨过去那一侧再也不发区块」 |
| 7 | 把 `synaxis.player.chunk-update-limiter-history-size` 改回默认 `5` 重启，重跑第 6 步并按跨区块次数逐次记账 | 至多前两次跨区块还收得到 `ChunkData`，**从第三次跨区块起直到本步结束再也收不到任何 `ChunkData`**（`addToHistory` 每次调用都把当前 `chunkIndex` 追加进长度 5 的窗口，A 与 B 各进过一次之后每次都命中历史而返回 `false`），且折返侧那一列区块保持空洞——**这一步是用来证明第 6 步不是自动成立的**；确认后立刻把该属性改回 `0`（§3 的标准前置） |

后置：确认 `synaxis.player.chunk-update-limiter-history-size` 已改回 `0`。

#### `TC-GAME-044` 镜头超频、越界锚点与 `zoom` 夹取 — 对应 `REQ-M2-018` · 级别: 回归 · 执行者: AI

前置：探针 in_game。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 1 秒内发 30 条 `C2S_CAMERA` | 无任何回错包，连接不断线 |
| 2 | 统计本秒内玩家实体的传送包数量 | ≤ 10（前 10 条被处理，其余丢弃） |
| 3 | 上报一个地图边界外的锚点（`anchor_x = size_x + 500`） | 传送后的实体坐标被夹取到地图边界内 |
| 4 | 上报 `zoom=1000` 与 `zoom=0` | 连接不断线；遥测中记录的 `zoom` 被夹取进 [8, 48]（观察入口待 `REQ-M2-018` 实现后填） |
| 5 | 上报 `camera_yaw=720` 与 `camera_yaw=-1` | 连接不断线；服务端 stdout 无异常堆栈 |

后置：无。

### M2 · 客户端模组与切片验收

#### `TC-GAME-045` 客户端模组独立构建与资源打包 — 对应 `REQ-M2-020` · 级别: 回归 · 执行者: AI

前置：`REQ-M2-020` 已合入（`20a` 骨架 + `20b` 工程完备）；`client-fabric/` 在仓库内但不进主构建。第 1～7 步在本机可跑，第 8 步要把分支推上去才看得到。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `grep -n "client-fabric" settings.gradle.kts` | 退出码 1、无输出（独立 Gradle 构建，见 `ADR-M2-015`） |
| 2 | `./gradlew :protocol:publishToMavenLocal --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts`，再 `cd client-fabric && ./gradlew build --no-daemon` | 两条命令都 `BUILD SUCCESSFUL`；`client-fabric/build/libs/` 下生成模组 jar；客户端 `check` 里 `:verifyModJar` 打出一行 `mod jar: minecraft=26.2, <N> entries, ships META-INF/jars/protocol-<版本>.jar` |
| 3 | `unzip -l <模组 jar>` | 含 `fabric.mod.json`、`assets/synaxis/` 下的资源（`lang/en_us.json`、`lang/zh_cn.json`——§14 那十个 `synaxis:*` 断线原因键的文案，没有它模组玩家看到的也是原始键；以及 `icon.png`），以及 `META-INF/jars/protocol-<版本>.jar`（`:protocol` 以 JiJ 嵌套形式共享）。**单位模型资源不在本步范围内**：单位当前用原版物品当可见物，给 display 实体配 `minecraft:item_model` 指向模组内自定义模型是 `ADR-M2-002` 落到渲染那一步的事，届时这一行补上模型与贴图路径。前三类由构建期的 `:verifyModJar` 断言，不靠人记得 unzip |
| 4 | `unzip -p <模组 jar> fabric.mod.json \| grep minecraft` | minecraft 依赖写死为 `"26.2"`，不是 `">=26.2"` |
| 5 | `grep -n "configuration-cache" client-fabric/gradle.properties` | 值为 `false`（Loom 要求，与主仓库的 configuration-cache 分开） |
| 6 | 在模组 jar 里查服务端引擎类（如 `unzip -l <模组 jar> \| grep -c "synaxis/instance"`） | 输出 `0`（只共享 `:protocol`，不把引擎塞进客户端）；同一判据由 `:verifyModJar` 按「`io/github/bearisbug/synaxis/` 下除客户端包以外的 `.class` 为 0」断言 |
| 7 | 把 `fabric.mod.json` 的 minecraft 依赖临时改成 `">=${minecraftVersion}"` 后重跑第 2 步，再 `git checkout -- client-fabric/src/main/resources/fabric.mod.json` | `:verifyModJar` 红并打出「`fabric.mod.json` 的 minecraft 依赖是 >=26.2，不是钉死的 26.2」；还原后复绿（同样的反证对资源与引擎类两条也成立：删掉 `assets/synaxis/lang/` 会红在「模组 jar 里没有 `assets/` 下的资源」） |
| 8 | 推分支后看 GitHub Actions 的 `Client mod` 工作流 | 三步全绿——`env -u CI ./gradlew :protocol:publishToMavenLocal`（CI 上签名是强制的，这个 job 没有也不需要 GPG 私钥）、`client-fabric` 的 `build`、上传模组 jar 与测试报告；产物里有模组 jar，测试报告含 `FrozenVectorTest` |

后置：确认工作区干净（第 7 步的临时改动已还原）。

#### `TC-GAME-046` 模组只在 26.2 profile 可装 — 对应 `REQ-M2-020` · 级别: 边缘 · 执行者: 人工

前置：本机同时有 26.2 与 26.3 两个 Fabric profile，且已按 `docs/research/client-spikes-2026-09/README.md` 的「前置」装好模组 jar 与 `fabric-api`。**三步都要人工在有图形环境的机器上做，仓库里的 agent 一步也跑不到**（自动化能到的只有 jar 里那条版本钉死，见 `TC-GAME-045` 第 4、7 步）。**第 3 步同时就是 spike S1 的运行期判据**：模组已注册 `synaxis:handshake` 的客户端侧（configuration 两个方向 + login 形态的 query 应答，见设计文档 `API-M2-002` 的「客户端侧」条），但那条路能不能真的在 configuration 阶段把包发出去仍是 `ADR-M2-010` 未跑完的那一半。这一步落 `synaxis:client-mod-required` 时先 `grep client_rejected logs/events-*.jsonl` 看 `reason`：`mod-missing` 才是「发不出去」，`version-mismatch` 说明包发到了、只是版本对不上。发不出去时的退路是给服务端加 `-Dsynaxis.app.handshake-phase=login` 重跑本步，客户端一行不改。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 把模组 jar 放进 26.3 profile 的 `mods/` 并启动 | Fabric Loader 启动时报错并提示 minecraft 版本不符，游戏不进入主菜单 |
| 2 | 把它放进 26.2 profile 的 `mods/` 并启动 | 正常进入主菜单，模组列表里能看到它与其版本号 |
| 3 | 在 26.2 下直连 `127.0.0.1` | 握手通过并进入对局（不出现 `synaxis:client-mod-required`） |

后置：清理 26.3 profile 里放进去的 jar。

#### `TC-GAME-047` RTS 切片端到端验收 — 对应 `REQ-M2-017`、`REQ-M2-018`、`REQ-M2-019` · 级别: 冒烟 · 执行者: 人工

前置：`server-app` 就绪且有 `running` 对局；一台装了模组的 26.2 客户端；已开启录屏。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 多人游戏直连 `127.0.0.1` | 5 秒内进入对局，看到 RTS 俯视镜头与地面，屏幕上有本方单位 |
| 2 | 把镜头锚点推到 200 格外 | 该区域的地形与单位都被渲染出来（不是空白地形、也不是「有地形没单位」） |
| 3 | 缩放到最远（`zoom=48`） | 屏幕角落（离锚点约 97 格）的单位仍然可见，地形无大面积不渲染 |
| 4 | 在视频设置里把「实体距离」调到 **50%**（剔除半径全局倍率的下限），回到游戏重复第 3 步 | 屏幕角落的单位**仍然**可见（`archetype_view_range=4.0` 的余量 + 模组对该倍率的覆盖，见设计文档 §15「可见范围」④⑥）；若不可见，按 `ADR-M2-013` 先查模组是否真的覆盖了该选项，再查 `archetype_view_range` |
| 5 | 把镜头在出生点与 200 格外之间来回推 3 个来回 | 每次推过去那一侧的地形与单位都重新出现（区块更新限流器已关，见 TC-GAME-043 第 6 步） |
| 6 | 左键点中一个本方单位 | 该单位出现选中框；`grep unit_selected logs/events-*.jsonl \| tail -1` 出现对应记录 |
| 7 | 右键点 20 格外一处可站立地面 | 落点出现绿色标记；单位在 300 ms 内起步，平滑走过去（不是瞬移、不是每秒一跳），到达后停下 |
| 8 | 在它移动途中再次点中它 | 能点中（拾取盒取自同一实体的渲染插值位置，看到的与点得中的同源），不出现「看着在这儿点不中」 |
| 9 | 右键点地图边界外 | 落点标记变红且单位保持原状态（不是没有任何反馈） |
| 10 | 把录屏与第 1～9 步的关键截图归档到 `docs/test-runs/RUN-<本轮>/` | 证据文件存在，能看清上述每一步的现象 |

后置：客户端退出；`stop` 关闭服务端。

#### `TC-GAME-066` 客户端侧握手与选中下令的无屏幕判据 — 对应 `REQ-M2-008`、`REQ-M2-019`、`REQ-M2-020` · 级别: 回归 · 执行者: AI

前置：阶段 6a 的客户端侧已合入（设计文档 v0.33，节流与结果码去向见 v0.36）；已按 `TC-GAME-017` 的前置在主构建跑过一次 `:protocol:publishToMavenLocal`。**本用例判的是「不启动游戏也能判的那一半」**：握手的编排、绑定表的生命周期、拾取的数学与一次点击产生哪些包。需要屏幕的那一半由 `TC-GAME-046` 第 3 步与 `TC-GAME-047` 判，第 5 步在此点名它是哪几行。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `grep -cE "serverboundConfiguration\|clientboundConfiguration" client-fabric/src/main/java/io/github/bearisbug/synaxis/game/client/SynaxisClientMod.java`（表格里的 `\|` 在渲染后就是 `grep -E` 的择一符 `|`） | 输出 `2`——`synaxis:handshake` 的 configuration 两个方向都注册了；只注册一个方向就是「本地根本发不出去」，与服务端做什么无关 |
| 2 | `grep -cE "ClientConfigurationConnectionEvents\.START\|ClientLoginNetworking\.registerGlobalReceiver" client-fabric/src/main/java/io/github/bearisbug/synaxis/game/client/SynaxisClientMod.java` | 输出 `2`——两种阶段各一条入口（configuration 主动发、login 应答 query）；阶段由服务端的 `synaxis.app.handshake-phase` 选，客户端两条都答（设计文档 `ADR-M2-010`） |
| 3 | `cd client-fabric && ./gradlew test --no-daemon` | `BUILD SUCCESSFUL`，92 条全绿：`ClientHandshakeTest` 13、`RtsClientTest` 23、`UnitPickerTest` 15、`UnitBindingsTest` 13、`FrozenVectorTest` 20、`GamePayloadTest` 5、`OrderFeedbackTest` 3。其中节流与结果码（设计文档 v0.36）各有一条：`theEleventhSelectionOfASecondIsNotSentAndDoesNotChangeTheSelection`（超额的选中整条作废，本地选中不动）、`theEleventhOrderOfASecondIsAnsweredWithoutTheServer`（超额的下令不发包、客户端自己回 `rate-limited`）、`anOrderTheClientThrottledDoesNotSpendASequenceNumber`（没发出去的不占 `order_seq`）、`aStaleAnswerLeavesTheLastFeedbackWhereItWas`（`stale` 静默，不顶掉已经画着的标记）、`everyCodeOfTheProtocolCarriesTheMarkerDesignSection14GivesIt` 与 `everyTextKeyIsInBothLanguageFiles`（§14 那张表的颜色与文案，且两个 lang 文件都有这三个键）；拾取的边界与退化另有四条：`aRayGrazingTheInsideOfABoxPicksTheUnit`、`aRayGrazingTheOutsideOfABoxPicksNothing`、`aRayLyingInTheBottomFaceOfABoxPicksTheUnit`、`aUnitBehindTheRayIsNotPicked`。其中回收纪律的四个答案（设计文档 `ADR-M2-012` 落地形态第二段）各有一条：`aDespawnDoesNotStopTheSameUnitFromBindingAgainLater`（先 despawn 后 spawn 照常绑定，不留墓碑）、`aSnapshotLineForAUnitThatWasJustDespawnedIsDroppedAndCounted`（已解绑单位的 state 条目只计数）、`aUnitWhoseEntityTheClientHasUnloadedIsNoLongerPickable`（切换世界或卸载后不可拾取，不抛异常）、`aNewConnectionStartsWithBothCountersAtZero`（计数器与绑定同属一条连接） |
| 4 | 反证门禁有齿：删掉 `ClientHandshake#beginConfiguration` 开头那四行 `if (state != State.IDLE)` 守卫，并把 `UnitPicker#pick` 里的 `direction.normalize().scale(maxDistance)` 改成 `direction.scale(maxDistance)`，重跑第 3 步，再 `git checkout -- client-fabric/src/main/java` | 红 3 条——`aSecondConfigurationStartOnTheSameConnectionSendsNothing` 与 `aHandshakeDoneDuringLoginStopsTheConfigurationPhaseFromSendingASecondOne`（两条都对着「一个连接只接受一次 `C2S_HELLO`」那条协议错误），以及 `anUnnormalisedDirectionMeasuresTheSameRange`；还原后复绿 |
| 5 | `grep -rn "Minecraft.getInstance()" client-fabric/src/main/java` | 命中的文件只有 `MinecraftWorldView.java`，共三行——类注释里一行，`renderPosition` 与 `groundHit` 的方法体里各一行。**这两个方法体是本轮唯一测不到的实现**——它们要一个真在跑的客户端，判据是 `TC-GAME-047` 第 6～8 步（人工 + 录屏）；除它们之外，拾取的数学、绑定表的生命周期与消息编排都落在 `ClientWorldView` 接缝的上方，由第 3 步覆盖 |
| 6 | `grep -nE "ClientPlayConnectionEvents\.(INIT\|DISCONNECT)\.register" client-fabric/src/main/java/io/github/bearisbug/synaxis/game/client/SynaxisClientMod.java` | 只有一行，且用的是 `INIT`。**`DISCONNECT` 出现在这里就是错的**：`fabric-networking-api-v1` 的 `ConnectionMixin` 把它注入在 `Connection#channelInactive` 的 HEAD 上，跑在 netty 线程；而 `rts:game` 的负载处理器被 `RunningOnDifferentThreadException` 顶回 `Minecraft.packetProcessor()` 的主线程队列。挂 `DISCONNECT` 就是两个线程同时写 `UnitBindings` 那个 `LinkedHashMap`，且清空可能早于最后一批包的插入。`INIT` 由 `ClientPacketListener` 的构造器点着，而构造器跑在 `handleConfigurationFinished` 里（开头就是 `PacketUtils.ensureRunningOnSameThread`），与收包同一个线程、同一个队列 |
| 7 | 反证「匹配不上就计数」有齿：把 `UnitBindings#apply(S2CUnitDespawn)` 的循环体改回单行 `byUnitId.remove(unitId);`，并删掉 `apply(S2CUnitState)` 里 `bound == null` 分支的计数与 WARN（只留 `continue`），重跑第 3 步，再 `git checkout -- client-fabric/src/main/java` | 红 4 条——`aSnapshotLineForAUnitThatIsNotBoundIsDroppedAndCounted`、`aSnapshotLineForAUnitThatWasJustDespawnedIsDroppedAndCounted`、`aDespawnOfAUnitThatIsNotBoundIsCounted`、`aSecondDespawnOfTheSameUnitIsCounted`；还原后复绿 |
| 8 | 反证回收纪律的其余三条有齿，**三处改动逐个做、逐个还原**（合起来跑会互相污染：第 ② 处那张静态表会跨用例串味）：① `UnitBindings#clear()` 只清 `byUnitId`、不清两个计数器；同批把 `RtsClient#receive` 的 despawn 分支改成无条件 `selected = null`，并在 `orderAt` 开头加一句「取不到选中单位的渲染位置就 `return`」。② `UnitPicker#boxOf` 在 `renderPosition` 返回 `null` 时退回一张静态表里上一次见过的位置。③ `UnitBindings` 加一个 `despawned` 墓碑集合，`apply(S2CUnitSpawn)` 见到墓碑就 `return` | ① 红 3 条——`aNewConnectionStartsWithBothCountersAtZero`、`aDespawnThatDoesNotNameTheSelectedUnitLeavesItSelected`、`aSelectedUnitWhoseEntityWentAwayCanStillBeOrdered`；② 红 2 条——`aUnitWhoseEntityTheClientHasUnloadedIsNoLongerPickable` 与 `aBoundUnitWhoseEntityHasNotArrivedIsNotPickable`（后者正是静态表串味的证据，同时说明缓存渲染位置这条路连「绑定先到」那一分支都会弄坏）；③ 红 1 条——`aDespawnDoesNotStopTheSameUnitFromBindingAgainLater`。每处还原后均复绿 |
| 9 | 反证节流与「`stale` 不顶掉已画的标记」有齿：删掉 `RtsClient#selectAt` 开头的 `if (!allowSelect(second())) return;` 与 `orderAt` 里 `allowOrder` 那三行，并把 `show` 的条件去掉（只留 `feedback = answer;`），重跑第 3 步，再 `git checkout -- client-fabric/src/main/java` | 红 4 条——`theEleventhSelectionOfASecondIsNotSentAndDoesNotChangeTheSelection`、`theEleventhOrderOfASecondIsAnsweredWithoutTheServer`、`anOrderTheClientThrottledDoesNotSpendASequenceNumber`、`aStaleAnswerLeavesTheLastFeedbackWhereItWas`；还原后复绿 |
| 10 | 反证「不该命中的不命中」有齿：把 `UnitPicker#pick` 的 `box.clip(origin, end)` 改成 `box.inflate(0.02).clip(origin.subtract(end.subtract(origin)), end)`（盒子放大 0.02，且把半直线当成无限直线），重跑第 3 步后还原 | 红 3 条——`aRayGrazingTheOutsideOfABoxPicksNothing`、`aRayParallelToTheBottomFaceAndUnderItPicksNothing`（两条都对着「擦边与贴面的判定是包含式的」）、`aUnitBehindTheRayIsNotPicked`（射线是半直线，不是直线）；还原后复绿 |
| 11 | 反证「该命中的仍然命中」与 §14 那张表有齿，**两处一起改一起还原**：把同一行改成 `box.deflate(0.02).clip(origin, end)`；同批把 `OrderFeedback#marker()` 里的 `OUT_OF_BOUNDS` 从 `RED` 一支挪进 `YELLOW` 一支，并把 `NO_PATH` 的 translate 键改成两个 lang 文件里都没有的一个 | 红 4 条——`aRayGrazingTheInsideOfABoxPicksTheUnit`、`aRayLyingInTheBottomFaceOfABoxPicksTheUnit`、`everyCodeOfTheProtocolCarriesTheMarkerDesignSection14GivesIt`、`everyTextKeyIsInBothLanguageFiles`（后者是「文案漏进 lang 文件就会被画成键名本身」那条门禁）；还原后复绿 |

后置：确认工作区干净（第 4、7、8、9、10、11 步的临时改动已还原）。

### M2 · 启动器与文档

#### `TC-GAME-048` server-app 承接冒烟职责 — 对应 `REQ-M2-021` · 级别: 冒烟 · 执行者: AI

前置：`REQ-M2-021` 已合入（`smoke` 删除）。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `ls smoke 2>&1` 与 `grep -n "smoke" settings.gradle.kts` | 前者 `No such file or directory`；后者退出码 1、无输出 |
| 2 | `./gradlew :server-app:run --no-daemon --console=plain --init-script gradle/init/aliyun-mirror.init.gradle.kts` | stdout 出现 `synaxis server-app listening on 0.0.0.0:25565`；`nc -z 127.0.0.1 25565` 退出码 0 |
| 3 | 用 `-Dsynaxis.app.port=25570` 再启动一次 | 就绪日志里的端口为 25570，`nc -z 127.0.0.1 25570` 退出码 0 |
| 4 | 向 stdin 输入 `stop` | 进程在 15 秒内退出、退出码 0；日志里 `Disabling plugin: plugin_id=…` 按启用的逆序出现在 `Stopping synaxis server.` 之后、`Shutting down all thread pools.` 之前——插件的 `onDisable` 跑在 tick 线程已停、引擎线程池未关的那一段（宿主把它注册成引擎的 shutdown task，控制台与 SIGINT 两条路走同一段） |
| 5 | 核对本文档 §3 与 §1 | §3 生效的启动段已是 `:server-app:run`（smoke 段作废）；§1 变更记录里有本次「废旧 ID 换新 ID」的登记 |

后置：无。

#### `TC-GAME-050` 准入回调与握手跨线程交接 — 对应 `REQ-M2-024` · 级别: 冒烟 · 执行者: AI

前置：`REQ-M2-024` 第 ① 半（阶段 2）与第 ② 半（阶段 4）均已合入；进程就绪且有一个 `running` 对局；探针可控制「发不发 `C2S_HELLO`」与「发几次」，且能同时开两条连接并各自记录握手耗时（第 8 步依赖）。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `python3 tools/protocol-probe/probe.py handshake` | 收到 `S2C_HELLO` 后进入 play，退出码 0；`grep client_verified logs/events-*.jsonl \| tail -1` 出现该连接的一行且 `mod_version` / `schema_version` 是探针报的值（不再恒为 0）；放行的连接在 `AdmissionGate` 上**不打任何行**（只有拒绝与超预算才写日志），所以这一步的正面判据是那条台账行加上玩家真的进了对局 |
| 2 | 用一个**故意等 2.5 秒才发 `C2S_HELLO`** 的探针连接 | 仍然握手成功（3 秒有界等待未超时），不出现半成品状态 |
| 3 | 用「登录后不发 `C2S_HELLO`」的探针连接 | 3.0～3.5 秒被断开，原因键 `synaxis:client-mod-required`；服务端 stdout 里**没有** `spawningInstance` 相关的 `NullPointerException`（失败路径 kick 后直接 return，不设 `spawningInstance`） |
| 4 | 用探针握手成功后**再发一次 `C2S_HELLO`** | 被断开，原因键 `synaxis:protocol-error`（`complete` 返回 false 判为重复握手） |
| 5 | 用探针进入 configuration 后在 1 秒时主动断开 TCP | 服务端在 1 秒左右就释放该连接的等待（日志里该 `connection_id` 的最后一行早于 3 秒），不是干等满 3 秒 |
| 6 | 让插件的准入回调返回 `Reject("synaxis:match-unavailable")`（注入方式：在 `:server-app` 测试源集里按 `PluginHostIntegrationTest.ProbePlugin` 的形态临时写一个 `probe` 插件并以 `-Dsynaxis.app.plugins=probe` 起服；同形状的自动化档已由 `HandshakeIntegrationTest#aRejectedConnectionIsToldTheMatchIsUnavailableRatherThanAnInternalError` 覆盖），再用探针握手 | 探针收到 `S2C_HELLO` 之后被断开，原因键 `synaxis:match-unavailable`；`grep client_rejected logs/events-*.jsonl \| tail -1` 的 `reason` 为 `match-unavailable`；无 `synaxis:internal-error` |
| 7 | 让准入回调**抛异常**，再用探针握手 | 同样以 `synaxis:match-unavailable` 断开；日志里有一行带 `plugin_id` 的 ERROR（M2 没有熔断计数，见设计文档 §28.3 第 ① 条） |
| 8 | 让准入回调**睡 500 毫秒后正常返回 `Accept`**，再用探针握手 | 玩家**照常进入 play**（200 毫秒是事后测量的软预算，内联调用中止不了它，不改判为 `Reject`）；日志里有一行带 `plugin_id` 与实测耗时（≥ 500 毫秒）的 WARN；两条探针**同时**发起时，各自从 `S2C_HELLO` 到 `JoinGame` 都是约 500 毫秒（注入对唯一的 provider 全局生效，所以两条都慢），而**不是**串行的约 1,000 毫秒——这证明两条连接各自跑在自己的 configuration 虚拟线程上，没有互相阻塞 |
| 9 | 让第二个插件也调用 `admission().register(...)` 后启动 | 第二次注册抛 `IllegalStateException`，该插件转 `failed`、被摘干净；日志里有一行 `Plugin failed to enable: plugin_id=<第二个>` 的 ERROR，随行的异常消息同时点名持槽的第一个 `plugin_id`。**进程继续走启动序列并照常 bind**——第一个插件仍持有槽位，`API-M2-010` 第 ⑤ 步的断言成立（一个 `failed`、其余照常，见设计文档 §11.2）。「一个插件都没有参与启动 → 核心模式」与「加上 `core-mode=off` → 非零码退出且端口未 bind」是另外两档，由 `TC-GAME-058` 第 1 步与 `TC-GAME-057` 第 1 步分别判 |

后置：撤掉第 6～9 步的注入，`stop`。

#### `TC-GAME-051` 单位位置推进走公开入口 — 对应 `REQ-M2-025` · 级别: 回归 · 执行者: AI

前置：`REQ-M2-025` 已合入（引擎提供公开的 `Entity#moveTo(Pos)`）；`REQ-M2-017` 已合入可下令。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `grep -rn "refreshPosition" game-rts/src/main/java plugin-api/src/main/java server-app/src/main/java` | 退出码 1、无输出（游戏侧与稳定面零命中内部 API） |
| 2 | 查 `Entity#moveTo` 的声明 | 是 `public` 且**没有** `@ApiStatus.Internal`；三个 `refreshPosition` 重载仍然标着 `@ApiStatus.Internal`（新入口是提升原语，不是放宽既有 API） |
| 3 | 运行 `moveTo` 与 `refreshPosition(pos, false, true)` 的等价性对照测试：`./gradlew :test --tests 'io.github.bearisbug.synaxis.entity.EntityMoveToEquivalenceIntegrationTest' --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | `BUILD SUCCESSFUL`、4 个用例全绿；四档情形（同区块带 viewer、跨区块边界带 viewer、超 8 格走 `EntityTeleportPacket` 分支、无 viewer）里两条路径从同一份状态出发，产出逐包相同的出站序列——包类型、顺序与全部字段都相等（零行为变更） |
| 4 | 下一条移动指令让单位走 20 格，记账这段时间内的 `EntityTeleportEvent`：`./gradlew :game-rts:test --tests 'io.github.bearisbug.synaxis.game.rts.UnitMovementIntegrationTest' …`（`theWalkIsMadeOfAdvancesRatherThanJumpsAndTeleportsNothing` 用 `env.trackEvent(EntityTeleportEvent.class, EventFilter.ENTITY, 单位实体)` 数整段走法） | 计数为 `0`（推进不派发传送事件）；同一条用例还判每次推进的位移不超过 `archetype_speed × 2`，即没有瞬移 |
| 5 | 在同一段里观察推进是否产生跨 tick 的异步回调（JFR 采样 tick 线程） | 推进在 tick 线程上同步完成，没有 `loadOptionalChunk(...).thenRun(...)` 一类的异步链 |
| 6 | 让单位沿航点走到一个**未载入**区块的方向（构造方式：临时把航点指到地图边界外一格） | 抛 `NullPointerException`（来自 `refreshCoordinate` 的 `Check.notNull`）并被寻路侧的守卫拦下、记 ERROR，单位不进入不一致状态（越界不静默失败，见 `ADR-M2-013`） |

后置：还原第 6 步的构造。

#### `TC-GAME-049` 强制握手后协议探针的存量回归跑通 — 对应 `REQ-M2-008`、`REQ-M2-021` · 级别: 回归 · 执行者: AI

前置：`REQ-M2-008` 已合入且 `tools/protocol-probe/probe.py` 已在同一交付里实现 `C2S_HELLO`。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `grep -n "C2S_HELLO" tools/protocol-probe/probe.py` | 至少一处命中（探针已实现握手）；`python3 tools/protocol-probe/probe.py --help` 的子命令列表含 `handshake`、`no-hello`、`bad-schema`、`duplicate-hello` |
| 2 | `grep -rn "bypass\|skip-handshake\|no-handshake\|test-mode" tools/protocol-probe/probe.py server-app/src/main/java` | 退出码 1、无输出（没有可绕过准入的旁路开关） |
| 3 | 依次执行探针的 `login`、`zero-frame`、`deep-recursion`、`premature-finish`、`record-registry`、`alloc-bomb`、`nbt-bomb` 子命令 | 每个都能进入 play 态并完成各自原有断言，退出码符合 §3 探针表（它们现在都在 configuration 阶段先发一次 `C2S_HELLO`） |
| 4 | 带 `-Dsynaxis.app.core-mode=on` 重启服务端（第 1～3 步的普通进程没有这条通道），在这个**核心模式**的进程上执行探针的 `echo` 子命令（`synaxis:main` 由宿主在核心模式下提供，见设计文档 `API-M2-010` 第 ⑤ 步） | 按 §3 探针表回发一致负载，退出码 0 |

后置：无。

### 攻击面盘点（2026-09）

盘点表在 `docs/research/attack-surface-2026-09/README.md`；下面三条是盘点补的用例，覆盖的是当天查出的两个缺口（慢读对端、离线 UUID）与两处此前没有测试证明的既有控制（压缩帧上限、keep-alive 空闲上界）。其余每一面在盘点表里直接指到既有 TC 或代码仓测试。三条都跑在引擎的网络栈上，对应引擎域 REQ。

#### `TC-GAME-069` 慢读对端：断开之后 socket 必须关掉 — 对应 `REQ-011`、`REQ-017` · 级别: 回归 · 执行者: AI

前置：`docs/DESIGN.md` v0.32 已合入；核心模式服务端就绪（§3 启动段，本轮用 `-Dsynaxis.app.port=25567 -Dsynaxis.app.core-mode=on`，stdout 重定向到日志文件）。`slow-reader` 只适用核心模式（它靠 `synaxis:main` 的回显把出站缓冲撑满）。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `./gradlew :test --tests '*SlowReaderIntegrationTest*' --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | `BUILD SUCCESSFUL`，`aDisconnectClosesTheChannelUnderAWriteThePeerNeverReads` 绿：真起 `Server`、真登录到 LoginSuccess，对端把接收缓冲设成 4 KB 且从不读，服务端排 16 MB 出站后 `disconnect()`，5 s 内 channel 关闭、读写线程都退出 |
| 2 | 反证有齿：把 `PlayerSocketConnection#closeOnceFlushed` 首行的 `if (Thread.currentThread() == writeThread) return;` 改成 `!=`（让断开方永远不起关闭线程），重跑第 1 步，再 `git checkout -- src/main/java/io/github/bearisbug/synaxis/network/player/PlayerSocketConnection.java`。**不要用「删掉 `disconnect` 里那句 `closeOnceFlushed();`」来反证**：Error Prone 的 `UnusedMethod` 会让 `:compileJava` 先红 | 红 1 条，断在 `assertFalse(connection.getChannel().isOpen(), …)`——「a disconnected connection must close its channel even while the peer is not reading ==> expected: <false> but was: <true>」（修复前的原始红就是同一条，`SlowReaderIntegrationTest.java:80`）；还原后复绿 |
| 3 | `python3 tools/protocol-probe/probe.py --port 25567 --username slowprobe slow-reader` | 退出码 0；末段两行 `holding the socket without reading for 30.0s` 与 `the server closed the socket within 31s ([Errno 32] Broken pipe)`——`within` 后面的数是 `--wait` 加探测那 0.5 s，是上界不是关闭时刻 |
| 4 | `grep 'identifier=slowprobe' <服务端日志>` | 两行：`Login success: username=slowprobe connection_id=<C> …` 与 `Connection closed: connection_id=<C> identifier=slowprobe`，两行相隔 15～25 s（上一条 keep-alive 之后 `keepAliveKick` 15 s 踢人，而上一条 keep-alive 可能早在 configuration 阶段就发过；本轮实测修复后的进程 15.1 s、守卫反转的进程 15.2 s，两份日志的摘录在 `docs/research/attack-surface-2026-09/evidence-2026-09-22.txt`）。这次断线的原因是 keep-alive 超时，走的是 `Timeout` 纯文本（设计文档 §28 `SOAK-6` 的第 ① 档），所以**没有** `Kicking connection_id=` 那一行，只有关闭行；`grep -cE '^\s+at \|Caused by' <服务端日志>` 为 `0` |
| 5 | 反证探针有齿：用第 2 步那个改了 `!=` 的工作副本重新起服，重跑第 3 步，再还原并重新起服 | 退出码 1，末行 `the server is still holding the socket after 30.0s: its write thread is blocked on a peer that does not read`；服务端日志里 `Connection closed … identifier=slowprobe` 那一行照样在（连接在逻辑上已断，socket 却还开着，正是修复前的形状） |
| 6 | 第 3 步之后再 `python3 tools/protocol-probe/probe.py --port 25567 login --hold 3` | 退出码 0，`state=play` 与一次 `KeepAlive #1`：被慢读对端撑过的进程照常接受下一条连接 |

后置：确认工作区干净（第 2、5 步的临时改动已还原）；`stop` 停机，删掉服务端日志。

#### `TC-GAME-070` 离线 UUID 由服务端按用户名派生，对端自选的不采信 — 对应 `REQ-011` · 级别: 回归 · 执行者: AI

前置：`docs/DESIGN.md` v0.32 已合入；第 3 步要一个核心模式服务端（同 `TC-GAME-069` 前置）。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `./gradlew :test --tests '*PreAuthConnectionIntegrationTest*' --tests '*DuplicateUuidLoginIntegrationTest*' --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | `BUILD SUCCESSFUL`；`theOfflineUuidComesFromTheUsernameNotFromThePeer` 绿：`LoginStart{username=Notch, profileId=随机}` 进到 `AsyncPlayerPreLoginEvent` 时 profile 的 uuid 等于 `GameProfile.offlineUuid("Notch")`（`OfflinePlayer:Notch` 的 name-UUID），不等于对端给的那个；`DuplicateUuidLoginIntegrationTest` 三条仍绿（「同名即同 UUID」这道闸的另一半） |
| 2 | 反证有齿：把 `LoginListener#loginStartListener` 里的 `GameProfile.offlineUuid(packet.username())` 改回 `packet.profileId()`，重跑第 1 步，再 `git checkout -- src/main/java/io/github/bearisbug/synaxis/listener/preplay/LoginListener.java` | 红 1 条，断在「the profile uuid must be derived from the username, not taken from the login start」（修复前的原始红，`PreAuthConnectionIntegrationTest.java:181`）；还原后复绿 |
| 3 | `python3 tools/protocol-probe/probe.py --port 25567 --username probe login --hold 2` | `LoginSuccess username=probe uuid=a0d7d0d3-bb61-3352-b332-52bfa31d6c0e`——即 `OfflinePlayer:probe` 的 name-UUID（探针自己也是这么算的，所以合法客户端看不出变化；对端换一个 UUID 的形态只有第 1 步那条 JUnit 能造，探针不提供改 UUID 的开关——它不是攻击工具） |

后置：确认工作区干净（第 2 步的临时改动已还原）。

#### `TC-GAME-071` 压缩帧的两道上限与 keep-alive 空闲上界 — 对应 `REQ-017`、`REQ-011` · 级别: 回归 · 执行者: AI

前置：工作副本干净；第 3 步要一个核心模式服务端（同 `TC-GAME-069` 前置）。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `./gradlew :test --tests '*PacketReadingMalformedTest*' --tests '*ConnectionManagerIntegrationTest*' --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | `BUILD SUCCESSFUL`；`aDeclaredDecompressedLengthOverTheLimitIsRefusedBeforeInflating`（压缩帧声明的解压长度 = `MAX_PACKET_SIZE + 1` → `DataFormatException`，消息以 `Invalid decompressed length` 开头，即在声明这一步就拒、没有按声明分配）、`aStreamInflatingPastItsDeclaredLengthIsAProtocolError`（声明 16 字节、实际解出 4,096 字节的流 → 消息以 `Decompressed payload exceeds` 开头，解压在声明长度处截停）、`aPlayerThatStopsAnsweringKeepAlivesIsKicked`（发出 keep-alive 后到 `keepAliveKick − 1 ns` 仍在线，到点即踢并恰好收到一个 Disconnect 包） |
| 2 | 反证有齿（三处一起改、一起还原）：`PacketReading#readFramedPacket` 的 `dataLength > maxPacketSize` 改成 `dataLength > maxPacketSize * 2`；`NetworkBufferImpl#decompress` 删掉 `if (!inflater.finished()) { throw … }` 那三行；`ConnectionManager#handleKeepAlive` 的 `>=` 改成 `>`。重跑第 1 步，再 `git checkout -- src/main/java/io/github/bearisbug/synaxis/network/packet/PacketReading.java src/main/java/io/github/bearisbug/synaxis/network/NetworkBufferImpl.java src/main/java/io/github/bearisbug/synaxis/network/ConnectionManager.java` | 红 3 条——`…IsRefusedBeforeInflating` 断在「the announcement itself must be what is refused, got: Decompressed length mismatch: expected 2097152, got 0」（2 MiB 已经按声明分配并送去解压了）、`…IsAProtocolError` 断在「Expected java.util.zip.DataFormatException to be thrown, but nothing was thrown」、`…IsKicked` 断在「a player that never answered the keep alive must be kicked」；还原后复绿。**不要只删 `maxPacketSize` 那半个条件**：Error Prone 的 `UnusedVariable` 会让 `:compileJava` 先红，门禁跑不到 |
| 3 | 核心模式服务端上依次 `python3 tools/protocol-probe/probe.py --port 25567 alloc-bomb`、`… nbt-bomb`、`… zero-frame` | 三次都 `recv Disconnect reason=synaxis:protocol-error`、退出码 0（既有的输入上限，`TC-026` / `TC-027` 的口径；本步只证明盘点当天这台进程仍如此）；服务端日志三行 WARN、`grep -cE '^\s+at \|Caused by'` 为 `0` |

后置：确认工作区干净（第 2 步的临时改动已还原）。

## 5. 回归策略

- **全量轮**（全部用例）：首次成稿、每个阶段 gate（引擎域阶段 0、2、3；游戏域 `docs/DESIGN-M2.md` §26 的阶段 1～6b）、跨功能域改动、地基变更（JDK、Gradle、镜像、协议版本、模块拆分、启动入口）。
- **局部轮**（受影响用例 + 全部冒烟级）：单一需求改动。受影响用例 = 本次改动触及的 `REQ-*` / `REQ-M2-*` 所对应的 TC（M2 侧的对照表在 §2，机械校验查不到它）；无 REQ 变更的改动（纯 bug 修、重构）按触及的功能域圈定：
  - 引擎域：构建与仓库 → TC-001、TC-002、TC-004、TC-006、TC-021、TC-029，删除与注册表 → TC-008、TC-009、TC-020，命名与合规 → TC-011、TC-028，协议 → TC-014、TC-018、TC-019、TC-026、TC-027、TC-030、TC-031、TC-032、TC-034、TC-035、TC-GAME-069、TC-GAME-070、TC-GAME-071（后三条是攻击面盘点补的，动了 `network` / `listener` 包或 `ServerConfig` 的连接与超时项就跑），卫生 → TC-015、TC-016，门面与配置 → TC-023、TC-025，分层 → TC-024、TC-035。
  - 游戏域：模块与门禁 → TC-GAME-001～TC-GAME-006、TC-GAME-059、TC-GAME-067，插件宿主 → TC-GAME-009～TC-GAME-011、TC-GAME-014～TC-GAME-016、TC-GAME-053～TC-GAME-055（TC-GAME-007、TC-GAME-008、TC-GAME-012、TC-GAME-013 已废弃），协议与握手 → TC-GAME-017～TC-GAME-028、TC-GAME-049、TC-GAME-050、TC-GAME-060～TC-GAME-064（TC-GAME-021、TC-GAME-023、TC-GAME-025 已废弃），世界与对局 → TC-GAME-029、TC-GAME-031、TC-GAME-033～TC-GAME-036、TC-GAME-056、TC-GAME-057（TC-GAME-030、TC-GAME-032 已废弃），单位与寻路 → TC-GAME-052、TC-GAME-039～TC-GAME-042、TC-GAME-051、TC-GAME-065、TC-GAME-068，镜头 → TC-GAME-043、TC-GAME-044，客户端模组 → TC-GAME-038、TC-GAME-045～TC-GAME-047、TC-GAME-066，启动器 → TC-GAME-048、TC-GAME-049、TC-GAME-050、TC-GAME-058。
  - 跨域牵连（必须一起跑）：动了启动入口或就绪日志 → 加 TC-GAME-048、TC-GAME-029、TC-GAME-057、TC-GAME-058 与全部以进程为前置的引擎域用例（启动序列一变，「对局在 bind 之前就绪、tick 在 bind 之后才起」这条口径就要重验）；动了握手、准入回调或探针 → 加 TC-GAME-049、TC-GAME-050、TC-GAME-060、TC-GAME-061 与 TC-004、TC-014、TC-018、TC-019、TC-020、TC-026、TC-027 七条存量 TC；动了客户端模组侧的握手或拾取 → 加 TC-GAME-066；动了 `:protocol` 消息布局 → 加 TC-GAME-017 与客户端侧同一份向量；动了引擎 `Entity` 的位置推进入口 → 加 TC-GAME-051、TC-GAME-067 与 TC-GAME-040。
  - 影响面不确定时升级为全量轮。
- **复测轮**（上轮失败用例 + 全部冒烟级）：修复失败项之后。
- **人工收口轮**（仅上轮「待人工」用例）：AI 轮之后由人工补测 TC-016、TC-030、TC-GAME-020、TC-GAME-038、TC-GAME-046、TC-GAME-047，不要求冒烟级。
- **未实现即阻塞**：`TC-GAME-*` 的前置多数要求对应 `REQ-M2-*` 已合入；未合入时该用例登记「阻塞」并写明缺哪条 REQ，不得臆测通过，也不得因此把它从范围里悄悄删掉。

## 6. 执行记录

轮次汇总：

| 轮次 | 日期 | 被测版本 | 执行者 | 范围 | 结果 |
| --- | --- | --- | --- | --- | --- |
| RUN-001 | 2026-09-09 | `c77b723235` | AI（Claude Code，Gradle 9.7.1 / JDK 25.0.4.1） | 局部（REQ-003→TC-003；冒烟级 TC-001、TC-002、TC-007、TC-010、TC-012 因阶段 0～3 未开始而跳过） | 通过 1/6 · 跳过 5 |
| RUN-002 | 2026-09-09 | `7d7e956ada` | AI（Claude Code，gh 2.x / git） | 局部（REQ-002→TC-002；冒烟级 TC-001、TC-003、TC-007、TC-010、TC-012 按 RUN-001 同理跳过） | 通过 1/6 · 跳过 5 |
| RUN-003 | 2026-09-09 | `6f7c627da8` + 分支 `freeze-data` 的 REQ-001 / REQ-012 改动（Gradle 9.7.1 / JDK 25） | AI（Claude Code） | 局部（REQ-001→TC-001，REQ-012→TC-015、TC-016；冒烟级 TC-002、TC-003、TC-007、TC-010、TC-012） | 通过 2/8 · 失败 1 · 跳过 4 · 待人工 1 |
| RUN-004 | 2026-09-09 | `e6b8dcaa36`（阶段 1 全部合入 `main`） | AI（Claude Code，Gradle 9.7.1 / JDK 25 / 探针） | 全量（阶段 1 gate） | 通过 7/18 · 失败 1 · 待人工 2 · 跳过 8 |
| RUN-005 | 2026-09-09 | `e6b8dcaa36`（代码与 RUN-004 相同，仅文档变更） | AI（Claude Code，探针） | 复测（RUN-004：TC-019 + 冒烟级 TC-001、TC-002、TC-003、TC-007、TC-010、TC-012） | 通过 2/7 · 跳过 4 · 待人工 1 |
| RUN-006 | 2026-09-09 | `2cbd57ae85`（阶段 2 全部合入） | AI（Claude Code，Gradle 9.7.1 / JDK 25 / 探针） | 全量（阶段 2 gate） | 通过 12/18 · 待人工 2 · 跳过 4 |
| RUN-007 | 2026-09-09 | `a16b4bb928`（阶段 3 合入，M1 代码完成） | AI（Claude Code，Gradle / 探针 / GitHub Actions） | 全量（M1 gate） | 通过 15/18 · 失败 1 · 待人工 2 |
| RUN-008 | 2026-09-09 | `15616dede0`（代码与 RUN-007 相同，仅文档变更） | AI（Claude Code，GitHub Actions） | 复测（RUN-007：TC-021 + 冒烟级 TC-001、TC-002、TC-003、TC-010、TC-012、TC-020） | 通过 1/7 · 跳过 5 · 待人工 1 |
| RUN-009 | 2026-09-09 | `6dc0003213`（REQ-013 合入） | AI（Claude Code，Gradle / 探针） | 局部（REQ-013→TC-022 + 冒烟级 TC-001、TC-002、TC-003、TC-010、TC-012、TC-020） | 通过 1/7 · 失败 1 · 跳过 4 · 待人工 1 |
| RUN-010 | 2026-09-09 | `6dc0003213`（代码与 RUN-009 相同） | AI（Claude Code，Gradle / 探针） | 复测（RUN-009：TC-023 + 冒烟级 TC-001、TC-002、TC-003、TC-010、TC-012、TC-020） | 通过 6/7 · 待人工 1 |
| RUN-011 | 2026-09-09 | `c2f5ad80da`（REQ-014、REQ-015、命名残留清理合入，M1.5 完成） | AI（Claude Code，Gradle / 探针） | 局部（REQ-014→TC-025，REQ-015→TC-024 + 冒烟级 TC-001、TC-002、TC-003、TC-010、TC-012、TC-020；另加回归 TC-004、TC-014、TC-015、TC-017、TC-018、TC-019、TC-023） | 通过 14/15 · 待人工 1 |
| RUN-012 | 2026-09-13 | `5dbe193b2c`（REQ-017 上游加固移植合入） | AI（Claude Code，Gradle / 探针） | 局部（REQ-017→TC-026、TC-027 + 冒烟级 TC-001、TC-002、TC-003、TC-010、TC-012、TC-020；另加回归 TC-004、TC-014、TC-015、TC-018、TC-019、TC-023） | 通过 14/15 · 待人工 1 |
| RUN-013 | 2026-09-15 | `15f619f46d`（项目定名 Synaxis 改名提交） | AI（Claude Code，Gradle / 探针） | 局部（改名字面量→TC-002、TC-010、TC-012、TC-014、TC-018、TC-019、TC-020、TC-021、TC-024、TC-025、TC-026、TC-027 + 冒烟级 TC-001、TC-003；本轮新启用的 TC-028、TC-029 一并执行） | 通过 12/16 · 失败 2 · 待人工 1 · 跳过 1 |
| RUN-014 | 2026-09-15 | `70e86ba348`（2026-09-15 审计修复 13 个分支合入 main，57 条修复） | AI（Claude Code，Gradle / 探针） | 全量 | 通过 18/22 · 待人工 2 · 跳过 2 |
| RUN-015 | 2026-09-16 | `6c86fb18d9`（审计修复第二轮 F29 / F49 / F11 合入） | AI（Claude Code，Gradle / 探针） | 局部（instance / tag / player 域→TC-004、TC-018、TC-026、TC-027 + 冒烟级 TC-001、TC-002、TC-012、TC-020、TC-028、TC-029） | 通过 9/10 · 待人工 1 |
| RUN-016 | 2026-09-16 | `8ea0f97e00`（M2 阶段 1～3 合入：模块拆分、插件宿主、世界管理与审计残留） | AI（Claude Code，Gradle） | 局部（M2 阶段 1～3 的 REQ-M2-001～006、010～015、021～023 → 其对应 TC-GAME-* 的可自动化部分 + 引擎域冒烟 TC-001、TC-029） | 通过 16/29 · 待人工 2 · 阻塞 11 |
| RUN-017 | 2026-09-18 | `91902d12af`（引擎核心模式、压测 harness、configuration 泄漏与重复 UUID 登录的修复） | AI（Claude Code，Gradle / 探针 / soak.py） | 局部（引擎核心：核心模式起服与冒烟、连接生命周期、NFR 级压测；引擎域冒烟 TC-001、TC-029） | 通过 8/8 |
| RUN-018 | 2026-09-18 | `70b61a8192`（日志洪泛门禁、ABI 基线上线、Java 发包端、事件与网络测试密度） | AI（Claude Code，Gradle / PacketFlood / CI） | 局部（TC-033、TC-035、TC-GAME-005、TC-GAME-006 + §15 入站包指标与 event / network 测试密度的实测核对；均属引擎核心） | 通过 6/6 |
| RUN-019 | 2026-09-19 | `bf7ed5c55a`（M2 阶段 4：:protocol 做实、握手与跨线程交接、服务端权威校验与限流、核心回归） | AI（Claude Code，Gradle / 探针 / PacketFlood） | 局部（REQ-M2-007→TC-GAME-017、TC-GAME-018；REQ-M2-008 与 REQ-M2-024②→TC-GAME-019、TC-GAME-062、TC-GAME-022、TC-GAME-050；REQ-M2-009→TC-GAME-063、TC-GAME-024、TC-GAME-026～028、TC-GAME-039、TC-GAME-064 + 核心基线复跑） | 通过 15/17 · 待人工 1 · 阻塞 1 |
| RUN-020 | 2026-09-19 | `10dc8696b9`（M2 阶段 5：Entity#moveTo、单位模型与五条建模规则、自研寻路与三重预算） | AI（Claude Code，Gradle / 探针 / PacketFlood） | 局部（REQ-M2-025→TC-GAME-051；REQ-M2-016→TC-GAME-037、TC-GAME-039、TC-GAME-052；REQ-M2-017→TC-GAME-040～042、TC-GAME-065 + 核心基线复跑） | 通过 11/13 · 待人工 1 · 阻塞 1 |
| RUN-021 | 2026-09-22 | `49f0c01867`（阶段 0 / 6a 的无屏幕部分：客户端模组骨架、双端向量、spike ④ 源码结论） | AI（Claude Code，Gradle / Loom / 探针） | 局部（REQ-M2-020a→TC-GAME-045、TC-GAME-046 的可自动化部分；§24 双端向量→TC-GAME-017 第 4 步；TC-GAME-029 第 2 步备注 + 核心模式冒烟） | 通过 6/9 · 待人工 3 |
| RUN-022 | 2026-09-22 | `3daf64f109`（阶段 6a 的无屏幕部分：客户端握手、绑定表、拾取与下令） | AI（Claude Code，Gradle / Loom） | 局部（REQ-M2-019 的可无头测试部分→TC-GAME-045、TC-GAME-066；§24 双端向量复验；根构建回归） | 通过 4/9 · 待人工 5 |
| RUN-023 | 2026-09-22 | `28e908d301`（三项不等屏幕的交付合入：`:architecture-tests` 聚合规则、攻击面盘点与两处引擎修复、`synaxis.app.units` 与玩法模式压测 harness 及 50 × {1,000, 2,000, 4,000} 测量；含 v0.37 的 `heap_delta_kib` 与文档 lint 放宽） | AI（Claude Code，Gradle / 探针 / harness） | 局部（REQ-M2-002、REQ-M2-025→TC-GAME-067；REQ-M2-016、REQ-M2-017、REQ-M2-009→TC-GAME-068；REQ-011、REQ-017→TC-GAME-069～TC-GAME-071；REQ-M2-010→TC-GAME-029 第 2 步 + 冒烟级 TC-029） | 通过 6/7 · 失败 1 |

明细（仅登非「通过」项）：

| 轮次 | 用例 | 结果 | 现象 / 证据 | 跟进 |
| --- | --- | --- | --- | --- |
| RUN-001 | TC-001 | 跳过 | 阶段 0 未开始，`net.minestom:data` 仍为外部依赖 | 阶段 0 gate 全量轮 |
| RUN-001 | TC-002 | 跳过 | 推送与旧 fork 删除进行中，本轮不判定 | 阶段 0 gate 全量轮 |
| RUN-001 | TC-007 | 跳过 | 阶段 2 未开始 | 阶段 2 gate 全量轮 |
| RUN-001 | TC-010 | 跳过 | 阶段 3 未开始 | 阶段 3 gate 全量轮 |
| RUN-001 | TC-012 | 跳过 | 执行者为人工且 `smoke` 启动器未就绪 | 阶段 3 gate 人工收口轮 |

| RUN-002 | TC-001 | 跳过 | 阶段 0 未开始 | 阶段 0 gate 全量轮 |
| RUN-002 | TC-003 | 跳过 | 本轮范围外（RUN-001 已在 `c77b723235` 通过，docs 提交未触及代码） | 阶段 0 gate 全量轮 |
| RUN-002 | TC-007 | 跳过 | 阶段 2 未开始 | 阶段 2 gate 全量轮 |
| RUN-002 | TC-010 | 跳过 | 阶段 3 未开始 | 阶段 3 gate 全量轮 |
| RUN-002 | TC-012 | 跳过 | 执行者为人工且 `smoke` 启动器未就绪 | 阶段 3 gate 人工收口轮 |

| RUN-003 | TC-001 | 失败 | 第 1～3 步通过（两条 grep 均无命中、`find` 输出 74）。第 4 步离线 `check` 在 `testNamingCheck` 上失败：10 个测试类命名违规，全部是本分支未改动的既有文件；排除该任务后离线全量构建 `BUILD SUCCESSFUL`，2,659 测试 0 失败 0 错误；证据 `docs/test-runs/RUN-003/tc-001.txt` | 见 §7 遗留问题最后一条，随 `REQ-004`（CI 门禁）一并修 |
| RUN-003 | TC-002 | 跳过 | 本轮在功能分支 `freeze-data` 上执行，第 5 步要求当前分支为 `main`；RUN-002 已在 `7d7e956ada` 通过 | 合入 `main` 后的阶段 0 gate 全量轮 |
| RUN-003 | TC-007 | 跳过 | 阶段 2 未开始 | 阶段 2 gate 全量轮 |
| RUN-003 | TC-010 | 跳过 | 阶段 3 未开始 | 阶段 3 gate 全量轮 |
| RUN-003 | TC-012 | 跳过 | 执行者为人工且 `smoke` 启动器未就绪 | 阶段 3 gate 人工收口轮 |
| RUN-003 | TC-016 | 待人工 | `docs/ENGINE.md` 已成稿，冷启动验证需要一台未装 JDK 25 的 macOS 机器 | 阶段 0 gate 人工收口轮 |

TC-003 证据：`docs/test-runs/RUN-001/gradle-test-summary.txt`
TC-002 证据：`docs/test-runs/RUN-002/tc-002.txt`（5 步实际输出）（2,659 个测试，0 失败，0 错误，17 跳过）。
| RUN-004 | TC-013 | 失败 | 第 2 步预期「每秒收到 KeepAlive」，实际 14 秒内 1 次（`KEEP_ALIVE_DELAY`=10 s）；证据 `docs/test-runs/RUN-004/tc-013-holder.txt`。第 1、3 步通过 | 预期写错非实现缺陷：废弃 TC-013，新增 TC-019，RUN-005 复测 |
| RUN-004 | TC-005 | 跳过 | 需向远端推送故意破坏的分支并等 CI，留到 M1 gate 统一做 | M1 gate |
| RUN-004 | TC-006 | 跳过 | 同上 | M1 gate |
| RUN-004 | TC-007 | 跳过 | 阶段 2 进行中 | 阶段 2 gate |
| RUN-004 | TC-008 | 跳过 | 阶段 2 进行中 | 阶段 2 gate |
| RUN-004 | TC-009 | 跳过 | 阶段 2 进行中 | 阶段 2 gate |
| RUN-004 | TC-010 | 跳过 | 阶段 3 未开始 | 阶段 3 gate |
| RUN-004 | TC-011 | 跳过 | 阶段 3 未开始 | 阶段 3 gate |
| RUN-004 | TC-015 | 跳过 | 两个删除分支的 agent 正在并行跑测试套件，`$TMPDIR` 计数会被干扰，无法判定 | 阶段 2 gate 在无并行任务时执行 |
| RUN-004 | TC-012 | 待人工 | 需原版客户端；`smoke` 已就绪 | 阶段 3 gate 人工收口轮 |
| RUN-004 | TC-016 | 待人工 | 需一台未装 JDK 25 的 macOS | 阶段 3 gate 人工收口轮 |

| RUN-005 | TC-001 | 跳过 | 第 1～3 步实跑通过（证据 `docs/test-runs/RUN-005/tc-001.txt`），第 4 步离线 `check` 未重跑：代码版本与 RUN-004 相同 | 阶段 2 gate 全量轮重跑 |
| RUN-005 | TC-003 | 跳过 | 同上，代码版本与 RUN-004 相同 | 阶段 2 gate 全量轮 |
| RUN-005 | TC-007 | 跳过 | 阶段 2 进行中 | 阶段 2 gate |
| RUN-005 | TC-010 | 跳过 | 阶段 3 未开始 | 阶段 3 gate |
| RUN-005 | TC-012 | 待人工 | 需原版客户端 | 阶段 3 gate 人工收口轮 |

| RUN-006 | TC-005 | 跳过 | CI 探测需推送故意破坏的分支，留到 M1 gate 统一做 | M1 gate |
| RUN-006 | TC-006 | 跳过 | 同上 | M1 gate |
| RUN-006 | TC-010 | 跳过 | 阶段 3 未开始 | 阶段 3 gate |
| RUN-006 | TC-011 | 跳过 | 阶段 3 未开始 | 阶段 3 gate |
| RUN-006 | TC-012 | 待人工 | 需原版客户端 | 阶段 3 gate 人工收口轮 |
| RUN-006 | TC-016 | 待人工 | 需一台未装 JDK 25 的 macOS | 阶段 3 gate 人工收口轮 |

| RUN-007 | TC-005 | 失败 | 第 2 步预期 jcstress job 失败且含 `FORBIDDEN`，实际 `Concurrency` 工作流 success、`CI` 工作流 failure（`ThreadDispatcherTest > shutdownFromTickThread() FAILED`）；证据 `docs/test-runs/RUN-007/tc-00X-ci-jcstress-probe.txt` | 预期机制写错：废弃 TC-005，新增 TC-021，RUN-008 复测 |
| RUN-007 | TC-012 | 待人工 | 需原版客户端；品牌已显示 `game-engine`（smoke 日志） | 人工收口轮 |
| RUN-007 | TC-016 | 待人工 | 需一台未装 JDK 25 的 macOS | 人工收口轮 |

| RUN-008 | TC-001 | 跳过 | 代码版本与 RUN-007 相同 | M1.5 阶段 gate |
| RUN-008 | TC-002 | 跳过 | 同上 | M1.5 阶段 gate |
| RUN-008 | TC-003 | 跳过 | 同上 | M1.5 阶段 gate |
| RUN-008 | TC-010 | 跳过 | 同上 | M1.5 阶段 gate |
| RUN-008 | TC-020 | 跳过 | 同上 | M1.5 阶段 gate |
| RUN-008 | TC-012 | 待人工 | 需原版客户端 | 人工收口轮 |

| RUN-009 | TC-022 | 失败 | 第 1 步 grep 输出 `8`（全部是 `TICK_MS`/`PROTOCOL_VERSION`/`VERSION_NAME` 常量引用，无访问器）；第 2～4 步通过（串行 59 s、并行 38 s、单跑通过）；证据 `docs/test-runs/RUN-009/tc-022.txt` | 用例第 1 步写宽了：废弃 TC-022，新增 TC-023，RUN-010 复测 |
| RUN-009 | TC-001 | 跳过 | 仅冒烟：依赖与数据未变 | RUN-010 |
| RUN-009 | TC-002 | 跳过 | 同上 | RUN-010 |
| RUN-009 | TC-010 | 跳过 | 同上 | RUN-010 |
| RUN-009 | TC-020 | 跳过 | 本轮只执行第 1、2 步（注册表 9,063 字节与基线一致，`docs/test-runs/RUN-009/tc-020-registry.txt`），第 3～6 步未跑 | RUN-010 |
| RUN-009 | TC-012 | 待人工 | 需原版客户端 | 人工收口轮 |

| RUN-010 | TC-012 | 待人工 | 需原版客户端 | 人工收口轮 |

| RUN-011 | TC-012 | 待人工 | 需原版客户端 | 人工收口轮 |

| RUN-012 | TC-012 | 待人工 | 需原版客户端 | 人工收口轮 |

| RUN-013 | TC-003 | 失败 | 第 2 步 `tests` 之和 2,599 < 阈值 2,659，0 失败 0 错误（证据 `docs/test-runs/RUN-013/tc-003.txt`）；阈值是阶段 0 的数量，REQ-005 删除子系统后已不适用 | 预期过时：废弃 TC-003，改 TC-029（根模块基线 2,599），TC-029 本轮通过 |
| RUN-013 | TC-010 | 失败 | 第 4 步 5 行中第 2 行（空行）指向改名提交 `15f619f46d`：git 无法把该行映射到父提交，内容行全部穿透（证据 `docs/test-runs/RUN-013/static-checks.txt`） | 预期过严：废弃 TC-010，改 TC-028（非空行不指向改名提交），TC-028 本轮通过 |
| RUN-013 | TC-012 | 待人工 | 需原版客户端；品牌已改为 `synaxis`（smoke 日志），`synaxis:main` 探针往返见 `docs/test-runs/RUN-013/echo.txt` | 人工收口轮 |
| RUN-013 | TC-021 | 跳过 | 边缘级；本次只改了仓库名字面量，CI 门禁逻辑未动，`main` 上改名提交的 CI 结果作为旁证（见证据段） | 下次全量轮 |

| RUN-014 | TC-012 | 待人工 | 需原版客户端；品牌 `synaxis`、`synaxis:main` 探针往返见 `docs/test-runs/RUN-014/echo.txt` | 人工收口轮 |
| RUN-014 | TC-016 | 待人工 | 需一台未装 JDK 25 的 macOS | 人工收口轮 |
| RUN-014 | TC-006 | 跳过 | 边缘级；本轮改动不触及 CI 工作流、ArchUnit 规则与 jcstress 配置，门禁机制未变；`main` 上修复提交的 CI 结果作为旁证 | 下次门禁改动时 |
| RUN-014 | TC-021 | 跳过 | 同上 | 下次门禁改动时 |

| RUN-015 | TC-012 | 待人工 | 需原版客户端；`synaxis:main` 探针往返见 `docs/test-runs/RUN-015/echo.txt` | 人工收口轮 |

| RUN-016 | TC-GAME-004 | 阻塞 | 需推远端 PR 才能看到覆盖率门变红，本轮未推 | 下次有 PR 时 |
| RUN-016 | TC-GAME-005 | 阻塞 | 需 M2 第一个 tag 之后才有 ABI baseline；当前 ABI 门按引导规则显式 SKIPPED | 阶段 2 打 tag 后 |
| RUN-016 | TC-GAME-006 | 阻塞 | 同上，需 baseline 存在才能验证破坏性变更变红 | 阶段 2 打 tag 后 |
| RUN-016 | TC-GAME-017 | 阻塞 | `:protocol` 只有骨架，消息与向量随阶段 4 落 | 阶段 4 |
| RUN-016 | TC-GAME-018 | 阻塞 | 同上 | 阶段 4 |
| RUN-016 | TC-GAME-019 | 阻塞 | 握手随 REQ-M2-008 落在阶段 4；探针尚未实现 `C2S_HELLO` | 阶段 4 |
| RUN-016 | TC-GAME-021 | 阻塞 | 同上 | 阶段 4 |
| RUN-016 | TC-GAME-022 | 阻塞 | 同上 | 阶段 4 |
| RUN-016 | TC-GAME-033 | 阻塞 | 第 3～5 步需探针连入对局，随阶段 4 落；第 1、2 步（`SharedInstance` 全库零命中、有玩家的区块拒绝卸载）已由单测覆盖 | 阶段 4 |
| RUN-016 | TC-GAME-034 | 阻塞 | 同上：第 1、2 步已实跑，第 3～5 步需探针 | 阶段 4 |
| RUN-016 | TC-GAME-035 | 阻塞 | 同上 | 阶段 4 |
| RUN-016 | TC-GAME-020 | 待人工 | 需原版客户端看断线提示 | 人工收口轮 |
| RUN-016 | TC-GAME-016 | 待人工 | 示例插件按 `docs/PLUGINS.md` 跑通需人工照说明走一遍 | 人工收口轮 |

| RUN-023 | TC-GAME-068 | 失败 | 第 2 步 `S2C_UNIT_STATE` 三项判红：单条 2,023 B（判据 ≤ 256）、4,420 B/s/玩家（≤ 2,560）、220,866 B/s 50 人（≤ 128,000）；同步的 tick P99 8.617 ms、存活堆 43.0 MiB、寻路桶 P99 1 ms 达标（证据 `docs/research/soak-2026-09-22/runs/gate-1000/summary.json` 与 `report.md`）。根因是环形播种把 1,000 单位挤在出生点 ±17 格、每人兴趣集顶到 512 上限，量的是最坏布局而非 §2「视野 50 单位」画像；线上（压缩后）2,547 B/s/玩家、127,292 B/s 合计在线内 | 待拍板：播种改均匀铺开后重跑本用例，或改写 §15 单位快照三行的口径 |


RUN-023 证据：`docs/test-runs/RUN-023/acceptance.txt`（验收人侧：合并后全量 check、三组反证、端到端 50 连接实跑）、`docs/research/soak-2026-09-22/`（harness 用法、三档报告与 `runs/*/summary.json`）、`docs/research/attack-surface-2026-09/`（39 个面的盘点与实跑摘录）。要点：全量 check 真跑 3,140 个测试 0 失败；反证三组亲跑——`Entity#moveTo` 加 `@ApiStatus.Internal` → 聚合规则 5 条红 2 条；`closeOnceFlushed` 守卫反转 + `LoginListener` 改回采信对端 `profileId` → 两条新测试各红一条；`-Dsynaxis.app.units=3` → 拒启、端口未 bind。端到端：合并后的树第一次把攻击面修复与 harness 放在一起跑，50 连接 × 1,000 单位 × 60 s，50/50 登录、0 掉线、3,000 条下令 2,986 条回、0 ERROR 0 踢人。**正式测量的结论**（机器不安静，另一进程满占一核）：tick P99 8.617 / 9.698 / 11.096 ms（1,000 / 2,000 / 4,000 单位），存活堆 43.0 / 51.1 / 64.1 MiB，至 4,000 未见拐点；`S2C_UNIT_STATE` 三项按 v0.39 判据红，见上方明细行。**交付里的两处行为变化**：`S2C_UNIT_SPAWN` 改由下一个快照做差发出、比引擎实体包最多晚 2 tick（聚合规则抓到 `UnitEntity` 覆写内部 viewer 钩子后的处置，要不要给引擎开第二个公开回调登记为 §28.2 Open Question）；断开后 1 s 强制关 channel，慢读对端可能收不到断线原因（`docs/DESIGN.md` §16）。

RUN-022 证据：`docs/test-runs/RUN-022/acceptance.txt` 与 `docs/research/client-spikes-2026-09/stage6a-headless-vs-live.md`（逐行写清哪几行测不到、为什么、由谁判）。要点：客户端测试由 25 条增至 **92 条 0 失败**（握手 13、下令编排 23、拾取 15、绑定 13、向量 20、负载 5、结果码 3），**一条也没有启动 Minecraft**；碰 MC API 的只有两个接缝共四个方法（`ClientWorldView#renderPosition` / `#groundHit`、两个 `Sink#send`），生产实现各一到五行，是唯一测不到的部分，判据是人工用例。**§24 双端向量由验收人复验仍有齿**：把 `s2c_unit_spawn.bin` 的一个字节由 0 改成 255，客户端与服务端三条测试同时红，还原后同时绿。**范围自查**：服务端零改动（diff 只命中 `client-fabric/` 与 `docs/`），未越界写渲染或输入（`Mixin` / `RenderSystem` / `MouseHandler` / `KeyMapping` 全部零命中，属阶段 6b），根构建 3,122 个测试 0 失败。**本轮审查抓到一个真缺陷**：`ClientPlayConnectionEvents.DISCONNECT` 由 `ConnectionMixin` 在 `channelInactive` 的 HEAD 注入、**直接在 netty 线程上 invoke**，而 `rts:game` 的负载处理器跑在主线程的 `PacketProcessor` 队列上，两者同写绑定表的 `LinkedHashMap`、且拾取遍历时可能被另一线程清空；改用同线程同队列的 `INIT` 事件（由 `ClientPacketListener` 构造器点着，而该构造发生在已 `ensureRunningOnSameThread` 的 `handleConfigurationFinished` 里），顺序问题一并解决，证据以 `javap` 在真实 jar 上逐条取证。待人工（都要真实 Fabric 客户端与图形环境）：两个接缝的适配器、实际渲染、实际点击、端到端四环通，以及 spike ①②④ 的运行期验证。

RUN-021 证据：`docs/test-runs/RUN-021/acceptance.txt`、`docs/research/client-spikes-2026-09/`（四个 spike 的执行清单与 spike ④ 的源码结论）。要点：`client-fabric` 是独立的 Loom 构建、Minecraft 钉死 26.2、产出 33,880 字节的模组 jar，未被根构建吞进去；**§24 的双端向量保证由验收人亲自验有齿**——客户端读的是引擎那一份向量目录而非副本，把 `c2s_camera.bin` 的一个字节由 194 改成 61 后，客户端的 `FrozenVectorTest` 与服务端的 `ProtocolVectorTest`（含 `schema_version` 绑定那条）**同时变红**，还原后同时恢复绿，`TC-GAME-017` 第 4 步由此脱离阻塞；spike ③（1,024 区块载入墙钟）属服务端侧，本轮直接跑掉；**spike ④ 只给了带出处的源码结论**（取证对象 `fabric-networking-api-v1:6.3.4+2989c6a09e`，源码与字节码逐条对过），文档明写「桌面结论、非运行期验证」，`ADR-M2-010` **保持 Proposed**。服务端 3,120 个测试 0 失败、客户端 25 个 0 失败；核心模式起服 + 探针 login / echo / handshake / stop 全过，WARN+ERROR 0 行。磁盘：`~/.gradle` 4.0 GB（Loom 只缓存 26.2 一个版本），剩余 61 GB。待人工（都要真实 Fabric 客户端与图形环境）：spike ①（JiJ 运行期加载）、spike ②（相机与实体剔除，含 50% 实体距离档）、spike ④ 的运行期验证。

RUN-020 证据：`docs/test-runs/RUN-020/acceptance.txt`（验收人侧的退出条件自查、ABI 门、全量 check 与端到端实跑）与 `docs/research/soak-2026-09-18/report.md` 的「阶段 5 之后的复跑」一节。要点：`Entity#moveTo(Pos)` 单独成一个提交，等价性用**包序列**对照证明零行为变更（翻转 sendPackets 参数即红），三个 `refreshPosition` 重载仍是内部 API，游戏侧 grep 零命中；单位建模五条规则各有断言，文档点名的插值陷阱（`setTransformationInterpolationDuration`）全库零命中，`view_range` 取原型配置值 4.0；寻路为随地图烘一次的不可变网格 + 8 邻接 A\* + 拉绳平滑，三重预算实测——同 tick 64 条指令得 `ok` 36 / `busy` 28（和为 64、零 `rate-limited`）、每 tick 恰好处理 4 条、`path_ms` P99 0 ms、`path_nodes` 单次 ≤ 224；端到端（无客户端）下令后单位沿航点连续推进并在到达后转 idle，`unit_order_completed` 的 `elapsed_ms` 为正。**本轮审查抓到并修掉一个真缺陷**：空旷可达的远目标被答成 `no-path`（启发式未向目标偏置），修法是 octile 启发式加 1.15 的目标偏置，三重预算一个数未动，并新增 `TC-GAME-065` 钉住「远处空旷目标回 ok，`no-path` 只留给被围住的\」。**核心未退步**：PacketFlood 50 连接 × 100 Hz × 360 s 达成 5,015.0 包/秒 0 丢失（与 RUN-019 持平），窗口内 tick P99 1.367 ms（基线 1.350），存活集 32.0 MiB；三条客户端可控日志门禁各插探针均变红；ABI 门用 v0.1.0 真实资产真跑并绿。**阶段 5 新负载首测**：64 个单位全程移动时出站带宽由 idle 的 41.9 KB/s 升到 132.9 KB/s，按「单位数 × viewer 数」走；1,000 单位那一档仍未覆盖（见 §28）。待人工：TC-GAME-020。阻塞：TC-GAME-052 第 4～6 步（需 REQ-M2-018 的探针实体包解码）。

RUN-019 证据：`docs/test-runs/RUN-019/`（`acceptance.txt`：验收人侧的全量 check、ABI 门与核心模式实跑）与 `docs/research/soak-2026-09-18/report.md` 的「阶段 4 之后的复跑」一节。要点：完整 `clean check` BUILD SUCCESSFUL，引擎 2,868 + testing 6 + protocol 77 + server-app 90 + game-rts 55 共 3,096 个测试 0 失败（阶段 4 之前 2,967）；`:protocol` 由 20 行骨架变为 9 条消息 + 18 组冻结向量，逐字节对拍，改线格式不升 `schema_version` 会红；握手四条出口（成功 / schema 不等 / 超时 / 等待中断线）各有秒级测试，探针已实现 `C2S_HELLO`；限流按操作计的令牌桶、选中上限 8、洪水阈值各有测试；**核心未退步**——PacketFlood 50 连接 × 100 Hz × 360 s 达成 5,015.0 包/秒 0 丢失（RUN-018 为 5,014.9），窗口内 tick P99 1.350 ms（基线 1.631），churn 登录延迟 P99 102.8 ms（基线 114），六种攻击 + 64 连接负载 5 分钟 0 掉线，三条客户端可控日志门禁各插探针均变红；ABI 门由验收人用 v0.1.0 release 的真实资产复跑，两个任务真执行且通过。本轮修掉的缺陷：登录阶段会话表无界增长、以及游戏会话表「插入晚于回收」的永久泄漏（同形状在本仓库第三次出现，已照 `ConnectionManager#trackPlayer` 的同锁判活形状修，回归测试先红后绿）。待人工：TC-GAME-020（需原版客户端看断线提示）。阻塞：TC-GAME-017 第 4 步（客户端侧跑同一份向量，等阶段 6a）。

RUN-018 证据：`docs/test-runs/RUN-018/`（`nfr-5000.txt`：Java 发包端 `tools/loadtest/PacketFlood.java` 持续 360 秒打出 5,014.9 包/秒、1,805,402 个包、0 丢失 0 断线，窗口内 7,200 个 tick 的 p99 为 1.631 ms、日志增量 0 字节 —— `docs/DESIGN.md` §15 最后一条未证明的量化指标就此闭合，四条全部达标；`gates.txt`：ABI 门在本地用 v0.1.0 release 的真实资产当 baseline 执行并通过，删掉 `PluginContext#dataDirectory()` 后以 `ABI Check failed with 1 binary compatibility violations` 变红、复原后恢复绿，CI run 35332065219 里两个任务同样真跑而非 SKIPPED；引擎与宿主两条「客户端可控路径不得逐包写日志」门禁均由审查员插探针验过会红；全量 `clean check` BUILD SUCCESSFUL，引擎 2,867 + testing 6 + server-app 69 + game-rts 25 共 2,967 个测试 0 失败）。压测方法与全部数字见 `docs/research/soak-2026-09-18/` 的组 6 与组 7。附带产出：event 包测试密度由 0.05 升到 0.32（新测试炸出两个真缺陷：事件节点可挂成环导致 StackOverflowError、写队列最小写入长度算错，均已修）；network 包分母按「排除 219 个由字节对拍整体钉住的包定义 record」重新核定为 44 个逻辑文件对 48 个测试。通过项：TC-033、TC-035、TC-GAME-005、TC-GAME-006 及 §15 四条指标的实测核对。

RUN-017 证据：`docs/test-runs/RUN-017/`（`nfr-summary.txt` 与 `nfr-50conn-summary.json`：50 连接 / 10 分钟，tick p99 0.753 ms、登录延迟 p99 114 ms、存活集 31.7→28.1 MiB、0 掉线 0 登录失败，对照 `docs/DESIGN.md` §15 四条目标三条达标、入站包 5,000 每秒未触达（瓶颈在 harness，实测 2,880 单进程 / 3,990 双进程）；`duplicate-uuid-before.txt`：同名双进程压测在修复前触发 25 次 `register` 冲突异常与 662,803 行未注册移动 WARN、日志 87.6 MB；`duplicate-uuid-after.txt`：同场景修复后 `register` 异常 0、未注册移动 WARN 0、25 次 `reason=synaxis:duplicate-login` 的 kick、堆栈行 0、日志 26,129 字节）。完整 `clean check` BUILD SUCCESSFUL，引擎 2,695 + testing 6 + server-app 60 + game-rts 25 共 2,786 个测试 0 失败。压测方法与全部数字见 `docs/research/soak-2026-09-18/`。通过项：TC-001、TC-029 及核心模式起服 / 登录 / echo / stop 四项实跑（用例化随下一轮补）。

RUN-016 证据：`docs/test-runs/RUN-016/`（`static-checks.txt`：settings 含四个新模块且 `smoke` 已零跟踪文件、引擎 `game` 包已改名 `gameevent` 且旧目录不存在、ArchUnit 的引擎→游戏禁止规则写的是完整前缀、改名提交已登记 `.git-blame-ignore-revs`、`tools/pkgdeps.py` 对四个新模块各跑一条；`check.txt`：干净 `clean check` BUILD SUCCESSFUL，引擎 2,686 + testing 6 + server-app 43 + game-rts 25 共 2,760 个测试 0 失败；`tc-game-032-036.txt`：`:game-rts:test` 的对局生命周期与结构化 tag 禁止规则通过）。验收人另行核对：`Match#end` 在拆实例之前置 `ENDED`（新增回归测试先红后绿，红的证据是把状态翻转移回 `endWith` 后该用例失败）。通过项：TC-001、TC-029、TC-GAME-001、TC-GAME-002、TC-GAME-003、TC-GAME-007、TC-GAME-009、TC-GAME-010、TC-GAME-011、TC-GAME-014、TC-GAME-015、TC-GAME-029、TC-GAME-031、TC-GAME-032、TC-GAME-036、TC-GAME-048。

RUN-015 证据：`docs/test-runs/RUN-015/`（`static-checks.txt`：TC-001 第 1～3 步、TC-002 五步、TC-020 第 3～5 步（主源码 1,109 文件）、TC-028 五步；`tc-001-offline.txt`：离线 `check` 通过；`tc-029.txt`：`:test --rerun-tasks` 根模块 2,684 测试 0 失败；`tc-020-check.txt`：`check` 通过；`tc-020-registry.txt`：9,063 字节与基线一致；`tc-004.txt` / `tc-004-login.txt`：登录后 `stop` 1 秒退出码 0，末 20 行无 deadlock 与线程转储；`tc-027-*.txt`：零长帧断线、深递归退出码 0 且 2 次 KeepAlive、再登成功，无 `StackOverflowError`；`tc-026-*.txt`：两次 `Disconnect reason=synaxis:protocol-error`，再登成功；`tc-018*.txt`：3 个探针均收到 `synaxis:server-stopping`，进程 1 秒退出码 0；`echo.txt`：`synaxis:main` 往返 `ping`；`smoke-server.log`：3 行 `WARN` 均带 `connection_id`，堆栈行 0）。通过项：TC-001、TC-002、TC-004、TC-018、TC-020、TC-026、TC-027、TC-028、TC-029。

RUN-014 证据：`docs/test-runs/RUN-014/`（`static-checks.txt`：TC-001 第 1～3 步、TC-002 五步、TC-009 第 1～4 步、TC-011 四步、TC-020 第 3～5 步、TC-023 第 1 步、TC-024 第 1、2、4 步、TC-025 第 1、2 步、TC-028 五步；`tc-001-offline.txt`：离线 `check` 通过；`tc-004.txt` / `tc-004-login.txt`：登录后 `stop` 1 秒退出码 0，末 20 行无 deadlock 与线程转储；`tc-008.txt`：OpaqueRegistryFrozenTest 通过，注册表数据里 `minecraft:enchantment` 43 条；`tc-015.txt`：前后均 0；`tc-029.txt`：`:test --rerun-tasks` 根模块 2,673 测试 0 失败；`tc-023.txt`：串行 62 秒、并行 47 秒、单跑通过、属性文件已还原；`tc-024-arch.txt`：ArchitectureTest 通过，临时越层导入后失败于 `lowerLevelPackagesDoNotDependOnTheRestOfTheEngine`；`tc-025.txt`：两个测试类通过，启动日志含 `ServerConfig[` 与 `JvmFlags[`；`tc-020-registry.txt`：9,063 字节与基线一致；`tc-020-check.txt`：`check` 通过；`tc-014.txt`：775 / 777 均 `synaxis:unsupported-version`，776 `LoginSuccess`；`tc-019.txt` / `tc-019-holder.txt`：抢跑得 `synaxis:protocol-error`，holder 2 次 KeepAlive、退出码 0；`tc-027-*.txt`：零长帧断线、深递归退出码 0 且 2 次 KeepAlive、再登成功，无 `StackOverflowError`；`tc-026-*.txt`：两次 `Disconnect reason=synaxis:protocol-error`，再登成功；`tc-018*.txt`：3 个探针均收到 `synaxis:server-stopping`，进程 1 秒退出码 0；`echo.txt`：`synaxis:main` 往返 `ping`；`smoke-server.log`：3 行 `WARN` 均带 `connection_id`，堆栈行 0；探针用 `.venv/bin/python`）。另：审计复现测试（`docs/research/audit-2026-09-15/evidence/`）对修复后代码重跑，F23 与 F14 的 `env.tick()` 均在 5 秒内返回、tick 线程存活。通过项：TC-001、TC-002、TC-004、TC-008、TC-009、TC-011、TC-014、TC-015、TC-018、TC-019、TC-020、TC-023、TC-024、TC-025、TC-026、TC-027、TC-028、TC-029。

RUN-013 证据：`docs/test-runs/RUN-013/`（`static-checks.txt`：TC-001 第 1～3 步、TC-002 五步、TC-010 五步与 TC-028 第 4 步、TC-020 第 3～5 步、TC-024 第 1、2、4 步、TC-025 第 1、2 步；`tc-001-offline.txt`：离线 `check` 通过；`tc-003.txt`：`:test --rerun-tasks` 根模块 2,599 测试 0 失败；`tc-020-registry.txt`：注册表 9,063 字节与基线一致；`tc-020-check.txt`：`check` 通过；`tc-024-arch.txt`：ArchitectureTest 通过，临时加 `coordinate→entity` 导入后失败于 `lowerLevelPackagesDoNotDependOnTheRestOfTheEngine`，撤销后 `src` 干净；`tc-025.txt`：两个测试类通过，启动日志含 `ServerConfig[` 与 `JvmFlags[`；`tc-014.txt`：775 / 777 均 `synaxis:unsupported-version`，776 `LoginSuccess`；`tc-019.txt` / `tc-019-holder.txt`：抢跑得 `synaxis:protocol-error`，holder 2 次 KeepAlive 往返、退出码 0；`tc-027-*.txt`：零长帧 `synaxis:protocol-error`、深递归退出码 0 且 2 次 KeepAlive、再登成功，服务端无 `StackOverflowError`；`tc-026-*.txt`：两次 `Disconnect reason=synaxis:protocol-error`，再登成功；`tc-018*.txt`：3 个探针均收到 `synaxis:server-stopping`，进程 1 秒内退出码 0；`echo.txt`：`synaxis:main` 往返 `ping`；`smoke-server.log`：3 行 `WARN` 均带 `connection_id`，堆栈行 0；探针用 `.venv/bin/python`）。GitHub `Bearisbug/synaxis` 上 `15f619f46d` 的 `CI` 与 `Concurrency` 工作流均 success。通过项：TC-001、TC-002、TC-014、TC-018、TC-019、TC-020、TC-024、TC-025、TC-026、TC-027、TC-028、TC-029。

RUN-012 证据：`docs/test-runs/RUN-012/`（`tc-003.txt`：干净 `check --rerun-tasks` 2,602 测试 0 失败；`tc-026-alloc.txt` / `tc-026-nbt.txt`：两次 `Disconnect reason=engine:protocol-error`，`tc-026-login.txt` 再登成功；`tc-027-zero.txt`：`engine:protocol-error`，`tc-027-deep.txt`：退出码 0 且收到 4 次 KeepAlive，`tc-027-login.txt` 再登成功，服务端无 `StackOverflowError`；`smoke-server.log`：3 行 `WARN` 均带 `connection_id`，堆栈行 0；`static-checks.txt`：TC-001 四步含离线 `check`、TC-002、TC-010、TC-020 第 3～5 步、TC-023 第 1 步；`tc-020-registry.txt`：9,063 字节一致；`tc-014.txt`、`tc-019*.txt`、`tc-018-*.txt`、`echo.txt`、`tc-015.txt`）。通过项：TC-001、TC-002、TC-003、TC-004、TC-010、TC-014、TC-015、TC-018、TC-019、TC-020、TC-023、TC-026、TC-027。

RUN-011 证据：`docs/test-runs/RUN-011/`（`tc-003.txt`：干净 `check --rerun-tasks` 2,576 测试 0 失败；`tc-015.txt`：前后均 0；`static-checks.txt`：TC-023 第 1 步 0 处、TC-024 五步（`pkgdeps.py` 底层出向上层 0 边、无冻结存储、`utils/` 只剩通用工具、严格规则通过、临时违规后 `ArchitectureTest` 红且撤销后工作区干净）、TC-025 四步（`ServerFlag` 0 处、测试 JVM 无注入开关、两测试通过、启动日志各 1 行 `ServerConfig[...]` 与 `JvmFlags[...]`）、TC-001 四步含离线 `check`、TC-002、TC-010、TC-020 第 3～5 步（主源码 1,108）；`tc-020-registry.txt`：9,063 字节一致；`tc-014.txt`、`tc-019*.txt`、`tc-017.txt`、`tc-018-*.txt`、`echo.txt`、`smoke-server.log`）。通过项：TC-001、TC-002、TC-003、TC-004、TC-010、TC-014、TC-015、TC-017、TC-018、TC-019、TC-020、TC-023、TC-024、TC-025。

RUN-010 证据：`docs/test-runs/RUN-010/`（`tc-023.txt`：访问器 0 处、串行 50 s、并行 39 s、单跑通过、属性文件未改动；`static-checks.txt`：TC-001 四步（含离线 `check`）、TC-002、TC-010 五步、TC-020 第 3～5 步，主源码 1,115；`tc-020-registry.txt`：9,063 字节一致）。TC-003 以本轮 TC-023 第 2、3 步的两次 `:test --rerun-tasks` 全绿为据（`tc-003.txt` 只留下了最后一次单跑的 16 个用例结果，属证据采集顺序失误，测试数以 RUN-009 同版本干净 `check` 的 2,571 为准）。通过项：TC-023、TC-001、TC-002、TC-003、TC-010、TC-020。

RUN-009 证据：`docs/test-runs/RUN-009/`（`tc-003.txt`：干净 `check` 2,571 测试 0 失败；`tc-022.txt`；`tc-020-registry.txt`；`login.txt`、`echo.txt`、`smoke-server.log`）。通过项：TC-003。本轮与两个并行 agent 的 Gradle 任务共享 CPU，耗时含争用噪声。

RUN-008 证据：`docs/test-runs/RUN-008/tc-021.txt`（探测 PR 的 `CI` 工作流 failure，日志 `ThreadDispatcherTest > shutdownFromTickThread() FAILED`；`Concurrency` success；远端分支删除后 404）。通过项：TC-021。

RUN-007 证据：`docs/test-runs/RUN-007/`（`tc-003.txt`：干净 `check --rerun-tasks` 2,569 测试 0 失败；`tc-015.txt`；`static-checks.txt`：TC-010 五步、TC-011 四步、TC-020 第 3～5 步、TC-009、TC-001 第 1～3 步、TC-002；`tc-020-registry.txt`：9,063 字节与基线一致；`tc-008.txt`：43 条；`tc-014.txt`、`tc-019*.txt`、`tc-017.txt`、`tc-018-*.txt`、`echo.txt`、`smoke-server.log`；`tc-00X-ci-arch-probe.txt`：`ArchitectureTest > lowerLevelPackagesDoNotDependOnTheRestOfTheEngine() FAILED`）。通过项：TC-001（第 4 步离线 `check` 沿用 RUN-006 结论，代码版本仅改名，本轮以干净 `check` 覆盖）、TC-002、TC-003、TC-004、TC-006、TC-008、TC-009、TC-010、TC-011、TC-014、TC-015、TC-017、TC-018、TC-019、TC-020。GitHub Actions 在 `main` 上的 `CI` 与 `Concurrency` 均为 success。

RUN-006 证据：`docs/test-runs/RUN-006/`（`tc-003.txt`：干净 `check --rerun-tasks` 2,569 个测试 0 失败；`tc-015.txt`：测试前后 `$TMPDIR` 均为 0；`static-checks.txt`：TC-020 第 3～5 步、TC-009 第 1～4 步、TC-001 第 1～3 步；`tc-001-offline.txt`；`tc-020-registry.txt`：27 包 9,063 字节与基线 `cmp` 一致；`tc-008.txt`：`minecraft:enchantment` 43 条；`tc-014.txt`、`tc-019.txt` 与 `tc-019-holder.txt`、`tc-017.txt`、`tc-018-a/b/c.txt`、`echo.txt`、`smoke-server.log`）。通过项：TC-001、TC-002、TC-003、TC-004、TC-008、TC-009、TC-014、TC-015、TC-017、TC-018、TC-019、TC-020。TC-008 第 2 步由解析录制的注册表包得出（探针不打印各注册表条目数）。

RUN-005 证据：`docs/test-runs/RUN-005/`（`tc-019.txt`、`tc-019-holder.txt`：2 次 KeepAlive 往返、无意外断线、退出码 0；`smoke-server.log`：1 行 `engine:protocol-error`、无堆栈；`tc-002.txt`）。通过项：TC-019、TC-002。

RUN-004 证据：`docs/test-runs/RUN-004/`（`tc-001.txt`、`tc-002.txt`、`tc-003.txt`、`tc-014.txt`、`tc-013.txt` 与 `tc-013-holder.txt`、`tc-017.txt`、`tc-018-a/b/c.txt`、`echo.txt`、`smoke-server.log`）。通过项：TC-001（4 步，含离线 `check`）、TC-002、TC-003（干净 `check --rerun-tasks`，2,668 个测试 0 失败）、TC-004、TC-014、TC-017、TC-018。注册表基线样本于本轮录制：`src/test/resources/protocol/registry-data-26.2.bin`（9,063 字节，两次录制一致）。

RUN-003 证据：`docs/test-runs/RUN-003/tc-001.txt`、`docs/test-runs/RUN-003/tc-015.txt`；TC-003 本轮随 TC-015 第 2 步的强制全量重跑一并通过（2,659 个测试，0 失败，0 错误，17 跳过）。

## 7. 遗留问题

- `CommandPacketFilteringTest` 单独运行必挂，依赖其他 `@EnvTest` 先初始化静态 `serverProcess` — 接受理由：全量运行时通过，根因是 `MinecraftServer` 静态门面，阶段 4 门面改造时消失；关联：设计文档 §28 技术债，TC-003。
- 上游 `ObjectPoolTest` 在 jcstress pre-test 阶段 NPE，jcstress 插件与 isolated-projects 不兼容 — 接受理由：阶段 0 接入 CI 时单独 job 关闭这两项并修该测试；关联：TC-005。
- `testNamingCheck` 在 `main` 上有 10 个既有命名违规（`ArgumentItemStackTest`、`EntityTrackerLeakTest`、`ItemStackTemplateTest`、`UnsyncedComponentTest`、`PacketCompressionTest`、`PacketWriteQueueTest`、`PlayerConnectionKickTest`、`SocketWriteStateTransitionTest`、`RegistryTagCodecTest`、`PacketViewableUtilsConcurrencyTest`），任何一次非增量的 `./gradlew check`（`--rerun-tasks`、干净 checkout、CI 首跑，或 `build-src` 改动导致任务增量状态失效）都会红。该任务是 `@Incremental` 且 `outputs.upToDateWhen { true }`，日常增量构建只扫改动过的文件，所以这批违规平时不可见 — 跟进：随 `REQ-004` CI 门禁重命名这 10 个类；关联：TC-001、TC-003。
- ~~探针 `echo` 子命令归属未定~~ **已结案（2026-09-18，设计文档 v0.18）**：按原条目列的两个候选里的第一个落地——宿主在**核心模式**下自带 `synaxis:main` 诊断通道做 echo（`synaxis` 是宿主保留的通道命名空间，玩法插件的通道仍归插件所有，与 `REQ-M2-006` 不冲突）。`echo` 子命令因此只在核心模式的进程上适用，玩法模式的进程上没有这条通道。TC-012 的 echo 那一档仍然废弃（替代用例 `TC-030` 不含 echo）；关联：`TC-GAME-058` 第 3 步、TC-GAME-049 第 4 步、设计文档 `API-M2-010` 第 ⑤ 步与 §24。
