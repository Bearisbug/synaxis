# Synaxis 服务端核心压测 / 长稳报告（2026-09-18）

- 对象：`server-app` 核心模式（`-Dsynaxis.app.core-mode=on`，宿主自建平坦世界 + `synaxis:main`
  echo 通道，任何用户名放行）。协议 776 / MC 26.2。
- 机器：Apple Silicon，12 核，24 GiB，JDK 25.0.4.1，端口 25565。
- harness：`tools/loadtest/soak.py`（本目录 `README.md` 有一条命令的跑法与采集手段表）。
- head：三组长跑时引擎 head 为 `84b68cd75d`，harness 是当时**尚未提交的工作树版本**（`soak.py` 直到
  `30cec4073c` 才进历史，`git show 84b68cd75d:tools/loadtest/soak.py` 取不到），三组的产物也已按纪律删除，
  所以那三组数字不可回算复核；SOAK-1 / SOAK-2 的证据在 `30cec4073c`（首测）与 `14edb22d79`（回收时间轴重测）
  上采，期间只有 harness 与文档改动，引擎代码未变。分支 `worktree-wf_3e0dff43-904-1`。
- 三组都用 `stop` 停机，Gradle `GRADLE_EXIT=0`，停机后 `pgrep synaxis.app.Main` 为空。
- **上面三组那一轮只跑与记录，不修引擎缺陷。** 改动只在 harness、本报告与文档登记（`docs/DESIGN.md` §28 的
  两条技术债、`docs/TEST.md` §3 的工具登记、`docs/research/audit-2026-09-15/fixes.md` 里 F02 的
  状态更正），引擎代码一行未动。
- **复测（2026-09-18，验收人 NFR 级）**：同时起两个压测进程、各 25 连接会撞用户名，由此暴露 `SOAK-3`
  （见下方「发现」第三条）——本报告的三组长跑都只跑一个 harness 进程，一次都没触达它。该条已修复，
  修复前后的对照数字也记在同一条里。

## 压力是否到位

harness 用单 selector 循环驱动全部连接，只有登录握手在 8 个 worker 线程上跑。

- **稳态组（64 连接）是长稳，不是过载。** 目标 64×10×1200 = 768,000 个移动包，实发 730,656，达成
  95%，缺口来自开跑时 8 个 worker 逐条登录的爬坡（登录完成前那几秒还没有移动包）。达成的 608.9 包/秒
  在 20 TPS 下折合每 tick 约 32 个移动包，tick 中位数 0.26 ms 离 50 ms 预算差两个数量级——**核心在这个
  连接量级远未到瓶颈**，但这也意味着本轮没有把玩家侧负载推到拐点。
- **压力主要由 churn 与恶意两组提供。** 每秒 5 次完整登录（握手 + 登录 + configuration + 进世界）
  与六种攻击叠加 64 连接负载，走的是核心自己的连接生命周期与解码路径，不受 harness 单 selector 循环
  的限制。
- **CPU 不作为判据。** harness 的 `cpu_percent` / `harness_cpu_percent` 是每 10 秒一条的瞬时值，
  `samples.jsonl` 覆盖第一次登录之前与最后一次断开之后的整段，所以**它们的「整段最大值」不能归因到
  负载窗口**：实测一跑 `--connections 16` 的最大 CPU 样本是 `t=0.0, clients=0`（JVM 刚起来那一条），
  而负载窗口内全程只有个位数。本报告因此不拿 CPU 做任何推论。要按窗口判断瓶颈，读 `samples.jsonl`
  里 `clients > 0` 的那一段；harness 打印峰值时会连同它所在的 `t` 与 `clients` 一起打出来。
- 真正的资源问题不是 CPU，而是下面 SOAK-1 的连接生命周期。

## 组 1：稳态（64 连接 / 20 分钟）

`--connections 64 --duration 1200 --move-hz 10 --echo-hz 0.5 --gc-probe-interval 300`

- 登录 64/64 成功，0 失败，0 断线；730,656 个移动包（608.9/s），7,648 次 KeepAlive 应答。
- tick（`synaxis.ServerTickTime`，24,412 个）：整段 p50 0.257 / p99 0.553 / max 25.97 ms，
  **超过 50 ms 的 0 个**（DESIGN.md §15 的 SLO 是单 tick P99 < 50 ms）。max 25.97 ms 落在第 0 分钟，
  是玩家进场时核心世界按需生成区块（`synaxis.ChunkGeneration` 在 t=0 集中出现）；第 1 分钟起每分钟
  p99 稳定在 0.47–0.62 ms、max 1.5–4.4 ms，无漂移。
- 堆存活集（每 5 分钟一次 Full GC 后测）：32.2 → 31.9 → 31.8 → 28.6 MiB，20 分钟内不涨。
- 老年代 `OU` 在 6.8k→33k KiB 区间锯齿波动（正常 GC 行为，Full GC 后回落），非单调。
- 线程 66 恒定；FD 稳态 123，断开后回落 59；RSS 320–335 MiB。
- 客户端 RTT（echo 往返）p50 44.3 / p99 83.2 ms —— 20 TPS 下一次往返最多跨一个 50 ms tick，
  加上 harness 10 ms select 循环，44 ms 中位属预期，非发现。

判定：**稳态无单调增长、无 tick 尖刺（除进场一次性区块生成）、无异常日志。**

## 组 2：churn（16 常驻 + 5 登录/秒 / 10 分钟）

`--connections 16 --duration 600 --churn-hz 5 --churn-hold 8`

- 2,941 次登录，0 失败；churn 连接进世界后保持 8 秒再挂断。
- 线程 66 恒定（虚拟线程反复起落，JFR `jdk.VirtualThreadStart`=`VirtualThreadEnd`=15,715，成对）；
  FD 稳定 114（峰值），断开回落 59。
- 堆存活集：31.1 → 31.5 → 31.6 → 31.5 → 29.0 MiB，10 分钟无上涨；停机静置后 28.7 MiB。
- tick 12,609 个：整段 p50 0.31 / p99 0.87 / max 49.7 ms（max < 50 ms，故超 50 ms 的也是 0 个），
  第 1 分钟起每分钟 max ≤ 4 ms。max 49.7 ms 同样在第 0 分钟，与首批玩家进场的区块生成同时：
  `synaxis.ChunkGeneration` 在 t=0 的一秒内 529 次、单次最长 73 ms，但生成本身跑在虚拟线程上，
  不计入这一个 tick——tick 里看到的是它的下游代价（区块下发与实体可见性）。
- RSS 在 360–385 MiB 间波动，不随 churn 次数单调上行。

判定：**完成整个登录、进入 play 再挂断的连接会被及时回收**——线程 / FD / 堆均不随 churn 增长。

## 组 3：恶意 / 边界（六种攻击 + 64 连接负载 / 10 分钟）

`--connections 64 ... --attacks zero-frame,alloc-bomb,nbt-bomb,premature-finish,bad-version,deep-recursion --attack-interval 1.5`

- 攻击 185 次，全部探针退出码 0；64 条正常连接**零断线、零失败**。这一组的移动包总数**没有记进
  本报告**，产物已删除、无法回算：它是 64 连接 × 10 Hz × 600 秒，上限 384,000（同段 tick 计数
  12,409 ≈ 620 s × 20 TPS 也印证它只跑了 10 分钟）。所以「攻击下吞吐没掉」这一条本报告拿不出
  数字，实测站得住的只有零断线、零失败。
- tick 12,409 个：整段 p50 0.265 / p99 0.692 / max 67.8 ms（第 0 分钟区块生成），
  第 1 分钟起每分钟 max ≤ 2 ms —— 攻击没有在正常负载上制造尖刺。这一组的 max 超过 50 ms，
  但 SLO 口径是 P99 < 50 ms（DESIGN.md §15），三组 p99 均 ≤ 0.87 ms，不构成违约；本组超 50 ms 的
  tick 计数未记入本报告（harness 的 `over_50ms` 字段有，产物已按要求删除）。
- 服务端日志：每次解码类攻击一行 WARN，带 `connection_id` / `state` / `reason`，**无堆栈**
  （全日志 `at `/`Caused by` 行数为 0）。三类可观察 WARN：
  - zero-frame → `DataFormatException: Packet length must be positive: 0`
  - alloc-bomb → `IllegalArgumentException: Collection size (65,535) is out of the allowed range (0..100)`
  - nbt-bomb → `IllegalArgumentException: NBT is too large (max: 2097152 bytes)`
  bad-version 走 LoginDisconnect、deep-recursion 非创造玩家整包跳过，均不崩不挂。
- **「所有 WARN 都带 `connection_id`」只对上面三类解码断线成立**，另有两行 WARN 不带任何连接标识，
  docs/TEST.md §3 的日志关联口径在它们上面有真实反例：
  - `ConnectionManager.java:280` 的 `Player <username> failed to respond to known packs query`
    ——只有用户名。**已于 2026-09-18 改成 `Player failed to respond to known packs query:
    username=… connection_id=…`**（`docs/TEST.md` §3 的关联口径同批补上这一行）；同一轮的 SOAK-1
    修复还让它在「客户端先走了」这一档彻底不再出现，剩下的命中都是真的连着但不应答。
    这是 SOAK-1 超时到点时打的那一行，每轮 premature-finish 洪泛的最后一次攻击
    之后 5 分钟成批出现。**行数与攻击次数的比例是赛跑结果，不是性质**：攻击造成的 protocol-error
    断线与 `ConnectionManager.java:264` 的 `if (!player.isOnline()) return;` 谁先到，决定这条连接
    会不会走到 known-packs 等待。实测三次分别是 684/3,792 = 18%、151/718 = 21%、复核者的
    720 次洪泛 34%，越堵比例越低。
  - `PacketReading.java:296` 的 `WARNING: Packet (...) 0x.. not fully read (NetworkBuffer{...})`
    （由默认开启的 `synaxis.warn-unread-bytes-packet` 控制）——打在包解码点，拿不到连接上下文。
    复核者在 premature-finish 洪泛里以约 1/540 的频率抓到过 `ClientChangeDifficultyPacket 0x4`
    这一形状；本轮累计 4,510 次同样的攻击（3,792 + 718）一次都没命中（`grep -c 'not fully read'`
    两次重跑都是 0），所以只能记下代码事实与他人观察，命中条件未定位。**2026-09-18 判为不修**：
    它报的是「服务端的序列化器没读完这个包」这类引擎自身缺陷，不是连接事件，`docs/TEST.md` §3
    列举的关联行里本来就没有它；要带上 `connection_id` 得把连接标识穿过 `readPackets` →
    `readFramedPacket` → `readPayload` 三层静态方法，代价与这条从不命中的诊断行不相称。
- 堆存活集 33.1 → 32.4 → 29.1 MiB，不涨。

判定：**攻击不影响正常连接、服务端不崩不挂；解码断线的日志规范（一行 WARN + connection_id +
无堆栈），上面两行 WARN 当时是这条规范的例外——第一行已于 2026-09-18 补上 `connection_id`，
第二行判为不修（理由见上）。**

## 组 4：NFR 级（50 连接 / 100 Hz / 10 分钟，验收人 2026-09-18 补跑）

前三组由压测员跑，稳态那组只到 609 包/秒，没有触达 `docs/DESIGN.md` §15 的量化目标。本组由验收人
在 `e074732388` 上补跑，专门对照 §15 的四条：

| §15 目标 | 本组实测 | 结论 |
| --- | --- | --- |
| 单 tick P99 < 50 ms（50 玩家） | p50 0.353 ms、p99 0.753 ms、max 22.6 ms（max 落在第 1 分钟的 JIT 预热窗口），超 50 ms 的 tick 0 个 | 达标，余量约 66 倍 |
| 登录延迟 < 2 s | 50 次登录 p99 114 ms、max 114 ms | 达标 |
| 堆 < 2 GB | 存活集 31.7 → 28.1 MiB（两次强制 Full GC 取样），RSS 214 → 299 MiB | 达标 |
| 入站包 5,000 包/秒无丢包 | **未触达**：单进程 2,880 包/秒，两进程并行 3,990 包/秒 | 见下 |

入站包那条没打满，瓶颈在 harness 不在引擎：`soak.py` 单 selector 循环驱动全部连接，Python 侧先到
上限（并行两个进程只提升到 3,990）。可以说的是「在 2,880 包/秒下 tick p99 是 0.75 ms、离 50 ms 预算
还有 66 倍」，不能说「5,000 包/秒达标」——要证明那一条，得先把 harness 换成多进程或非 Python 的
发包端。这条写进「局限」。

其余数字：50 条连接全程 0 掉线、0 登录失败、0 断线原因；echo 往返 p99 102.7 ms；停机后线程 66、
FD 59、存活集 28.1 MiB。服务端日志零 WARN 零 ERROR 零堆栈行。

产物：`docs/test-runs/RUN-017/nfr-50conn-summary.json`（全量数字）与 `nfr-summary.txt`（摘要）。

## 组 5：同名并发登录（验收人 2026-09-18，先炸后修）

组 4 之后验收人把两个 harness 进程并排跑（各 25 连接），两边的用户名都是 `soak0000..soak0024`。
引擎默认离线模式，UUID 由用户名派生，于是后一组的 25 个玩家带着**已经在线的 UUID**登录，炸出了
一条此前两轮审计与前三组压测都没打到的链：

1. 第二个同 UUID 的玩家照常走完登录与 configuration，进入 play。
2. tick 线程上 `Entity#setInstance` 的续体调 `EntityTrackerImpl#register`，抛
   `IllegalStateException: There is already an entity registered with uuid …`。
3. 续体的 `catch (Exception)` 把它交给默认异常处理器，只打一行栈。
4. 玩家留在世界里、连接活着、能收发包，但**不在 EntityTracker 里**。
5. 此后它每发一个移动包就写一行 `Attempted to move unregistered entity N`。

实测 15 分钟写了 662,803 行、日志 87.6 MB —— 一个未认证成本极低的填盘手段。2026-09-15 审计的
F59 在 notes 里点过名（「离线模式下 `LoginListener` 直接采信客户端 profileId、`ConnectionManager`
无重复 UUID 拒绝」），当时不在那一批范围内。

修法是三层，都在 `91902d12af`：

| 层 | 改动 | 作用 |
| --- | --- | --- |
| 门口 | `ConnectionManager#createPlayer` 在 login-ack 时用 `synchronized` + `putIfAbsent` 抢 UUID，抢不到的那条连接以新原因键 `synaxis:duplicate-login` 断开 | 两条同 UUID 的连接恰好一条成功；断线后 `removePlayer` 用两参 `remove` 归还 UUID，合法重连不受影响 |
| 中间 | `Entity#setInstance` 的续体不再吞异常，改抛 `CompletionException`，由 `ConnectionManager` 的 `spawnFuture.exceptionally` 撤销这次 spawn | 任何原因导致的注册失败都不再留下僵尸玩家 |
| 兜底 | `EntityTrackerImpl` 的未注册移动 WARN 改为按时间限流，并在每次输出里带上被抑制的次数 | 日志量不再由客户端频率决定 |

同一场景在修复后复跑（各 25 连接 / 60 Hz / 180 秒）：

| 判据 | 修复前 | 修复后 |
| --- | --- | --- |
| `register` 冲突异常 | 25 | 0 |
| `Attempted to move unregistered entity` | 662,803 | 0 |
| `reason=synaxis:duplicate-login` 的 kick | 0 | 25 |
| 堆栈行 / ERROR | 25 / 0 | 0 / 0 |
| 服务端日志字节 | 87,614,417 | 26,129 |

证据：`docs/test-runs/RUN-017/duplicate-uuid-before.txt` 与 `duplicate-uuid-after.txt`。

## 组 6：入站包 5,000 每秒（2026-09-18，压测员补跑，Java 发包端）

组 4 留下的那条「入站包 5,000 包/秒无丢包」是 §15 四条量化目标里唯一没被证明的一条，瓶颈在发包端不在
引擎。本组换掉发包端重跑，把推测换成实测。

- 引擎版本：分支 `worktree-wf_9a22d33c-85c-1`，`6bc248cc8e` 之上只加本轮的一处修复（见下方 `SOAK-4`）；
  组 6 的数字是在**带该修复**的构建上采的，那处修复只改客户端填满包队列时的断线原因，5,000 包/秒下
  这条路一次都没走到。
- 发包端：`tools/loadtest/PacketFlood.java`，JDK 单文件源码启动器直接跑，不进任何 source set、不上模块路径。
  每条连接一个发送线程 + 一个读取线程，按 `nanoTime` 截止时间定速；包 ID 与 `probe.py` 同口径，
  从 `PacketVanilla.java` 的注册表顺序推导。**它报的是自己实际写进 socket 的包数**（窗口内计数 ÷ 窗口时长），
  不是「打算发多少」；登录爬坡不计入窗口。
- 服务端采样仍用 `soak.py run --connections 0`（只采样、不发包），JFR 事件 `synaxis.ServerTickTime`。
- **本组没有走到的两条入站解码路径**（与下面「都挤在 spawn 周围」同性质的未覆盖面）：全程离线模式，
  `EncryptionRequestPacket` 一次没发，解密那一段零覆盖；压缩虽然协商了（`ServerProcessImpl` 默认
  阈值 256 字节，`transitionLoginToConfig` 据此发 `SetCompressionPacket`），但位置包体只有 34 字节
  （每帧 36 字节 = 1 字节长度 varint + 1 字节值为 0 的 data-length varint + 34 字节包体），低于阈值
  就按未压缩发，于是引擎的 inflate 分支同样一次没走到。
- 机器与上面几组相同：Apple Silicon，12 核，24 GiB，JDK 25.0.4.1，端口 25565，loopback。

一条命令（服务端先按本目录 `README.md` 起在核心模式）：

```bash
java tools/loadtest/PacketFlood.java --connections 50 --hz 100 --seconds 360 --out /tmp/synaxis-flood/nfr
```

### 达成的速率

| 项 | 数字 |
| --- | --- |
| 连接 | 50 条，登录 50/50 成功，耗时 0.1 s |
| 目标速率 | 50 × 100 = 5,000 包/秒 |
| **实际达成** | **5,014.9 包/秒**（窗口 360.0 s，1,805,500 个入站包） |
| 每 60 秒区间 | 5,014.4 / 5,015.3 / 5,014.7 / 5,015.2 / 5,015.1 / 5,014.6，全程无塌陷 |
| 掉线 | 0；`disconnect_reasons` 为空 |
| 应答 | KeepAlive 1,750 次，TeleportConfirm 100 次，echo 3,550 次 |
| echo 往返 | p50 34.5 / p99 67.4 / max 71.8 ms（20 TPS 下一次往返至少跨一个 50 ms tick） |

实发略高于 5,000 是因为 KeepAlive 应答、TeleportConfirm 与 echo 也是入站包，一并计数。

### 服务端侧（按发包窗口裁齐）

窗口口径先说死，否则服务端侧的数字很容易混进空闲时段：发包端的窗口是 **360.0 s**（登录爬坡不计入），
而服务端采样进程是 `soak.py run --connections 0 --duration 400`，比发包窗口长、且 JFR 由它起停。
下表因此按同一条命令原样复跑一次（同机、同参数，`achieved 5014.9 packets/s over 360.0 s`，与上表
一致到 0.1 包/秒，窗口内包数 1,805,402 对 1,805,500），并把 tick 与日志两行裁到发包窗口内：tick 用
`jfr print --events synaxis.ServerTickTime` 的事件时间戳裁到 `[窗口起, 窗口止)`，正好 7,200 个
（360 s × 20 TPS，不多不少）；日志取窗口两端 `server.log` 的字节数之差。采样进程整段的数字另附在
表下，两个口径不混用。

| §15 目标 | 本组实测（发包窗口内） | 结论 |
| --- | --- | --- |
| 入站包 50 玩家峰值 5,000 包/秒无丢包 | 5,014.9 包/秒持续 360.0 s（1,805,402 个入站包），0 掉线、0 kick、0 丢包 | **达标** |
| 单 tick P99 < 50 ms | 7,200 个 tick：p50 0.402 / p99 1.631 ms（余量 30 倍）；max 114.4 ms 是窗口的**第一个** tick，见下 | 达标 |
| 登录延迟 < 2 s | 50 条连接 0.1 s 内全部进 play | 达标 |
| 堆 < 2 GB | 存活集（强制 Full GC 后）31.6 → 31.7 → 31.8 MiB，断开后 28.3 MiB；RSS 采样区间 109–295 MiB | 达标 |
| 日志量不由客户端决定 | 1,805,402 个入站包让服务端日志长了 **0 字节**（13,388 → 13,388），0 WARN、0 ERROR、0 堆栈行 | 达标 |

- **那个 114.4 ms 的 tick 是进场，不是负载**：它在 14:49:01.099，即 50 条连接同一秒进 play 之后的
  第一个 tick（区块生成 + 首批区块下发），紧跟的一个 44.0 ms，**此后整段最大 17.6 ms**。与组 1 记的
  「第 0 分钟一次性 ≤ 68 ms」是同一件事，量级随进场并发度走（组 1 是渐进登录，本组 50 条同时进）。
  §15 的判据是 P99，而 P99 离 50 ms 预算有 30 倍余量。
- **日志那一行的 0 字节不等于「一条都不写」**：每条连接固定两行，只是都落在窗口外——窗口开始前
  30 ms 的 50 行 `Login success`（9,300 字节）与窗口结束时的 50 行 `Connection closed`（8,441 字节）。
  这正是 §15 那条约束要的形状：日志量由连接数决定，与包速率无关。
- **采样进程整段**（416 s，含发包窗口前后约 56 s 的空闲 tick）：8,309 个 tick，p50 0.392 /
  p99 1.570 / max 114.4 ms。比窗口内略好，因为空闲 tick 把分位数拉低了——这就是上表不用它的原因。

线程负载中 66、断开后 54，FD 负载中 109、断开后回落 59，均无单调增长。

「无丢包」的判据是三条同时成立，不是只看发包端：① 发包端的实际速率与目标只差 0.3%，服务端一旦
停止读取，TCP 背压会让写阻塞、速率立刻塌下去，没有塌；② 引擎对超量入站的处置是**踢人不是丢包**
（`Player#addPacketToQueue` 队列满即断开），全程 0 kick；③ echo 往返 3,550 次全部回来且 p99 稳定在
67 ms，队列若有积压这条曲线会先抬头。

### 复核：不带采样进程的一轮

上面那组进场之后最慢的一个 tick 是 17.6 ms，落在负载窗口内，为排除它是 harness 自己每 10 秒
`fork` 一次 `lsof` / `ps` / `jstat` 造成的，另跑一轮只开 JFR、不跑 `soak.py` 采样的 330 秒：
5,015.1 包/秒、0 掉线，6,695 个 tick p50 0.436 / p99 2.272 / max 34.7 ms、超 50 ms 的 0 个。
结论不变，max 的量级也相当。

## 组 7：余量与拐点（同一发包端）

把每条连接的速率往上抬，找引擎在这台机器上的拐点。每档 50 连接 / 90 秒：

| 总速率 | 实际达成 | 掉线 | tick（该档所在分钟） | 服务端 CPU（`ps %cpu`，单核为 100%） |
| --- | --- | --- | --- | --- |
| 10,000 包/秒 | 10,015.3 | 0 | p50 0.43 / p99 1.42 / max 8.4 ms | ~25% |
| 20,000 包/秒 | 20,014.6 | 0 | p50 0.49 / p99 1.42 / max 5.1 ms | ~50% |
| 40,000 包/秒 | 40,015.2 | 0 | p50 0.63–0.67 / p99 1.43–1.51 / max 4.9 ms | ~105% |

整段 8,309 个 tick 超 50 ms 的 0 个，存活集 32.7 → 27.9 MiB，线程 66 恒定。
CPU 与速率成正比、tick p99 基本不动 —— **5,000 包/秒这一档对引擎是轻载，实测余量至少 8 倍**，
到 40,000 包/秒才吃掉约一个核。

发包端自己在 40,000 包/秒时仍只用到个位数核心占用，没有成为瓶颈；`soak.py` 单进程 2,880 包/秒的
上限确实是 Python 侧的，与引擎无关。

### 负控：确认发包端真的能看见拒绝

速率再往上（50 × 1,200 = 60,000 包/秒）时引擎**不是丢包，是踢人**：约 6 秒后 50 条连接全部被断开，
发包端 `connections_lost` 记满 50 条。这既证明限流真的会触发，也证明上面那几组「0 掉线」不是
发包端看不见断线。原因键见 `SOAK-4`。

## 发现

### SOAK-1（P1）：configuration 阶段中断的连接，会把虚拟线程 + 整个 Player 对象图滞留最长 5 分钟

- 位置：`src/main/java/io/github/bearisbug/synaxis/network/ConnectionManager.java:278`
  （`knownPacksFuture.get(knownPacksResponseTimeout, MILLISECONDS)`），默认值
  `src/main/java/io/github/bearisbug/synaxis/ServerConfig.java:189`
  （`knownPacksResponseTimeout = 5 * 60_000`）。
- **来历：这是 2026-09-15 审计 F02 没修完的那一半，不是新缺陷。** F02 的正文
  （`docs/research/audit-2026-09-15/00-总结.md:395`）当时就点了同一行：「同一方法第 278 行
  `knownPacksFuture.get(knownPacksResponseTimeout)` 是同一类问题的弱化版：默认 5 分钟超时期间
  同样不检查在线状态，被踢的玩家要被滞留 5 分钟」。而 `fixes.md` 把 F02 记成 fixed，修的只是
  `packFuture` 那一半——`ConnectionManager#removePlayer`（:318-325）只调
  `player.releaseResourcePackWait()`，全仓没有任何地方 complete 或 cancel `knownPacksFuture`
  （`grep -rn knownPacksFuture src/` 共 7 处，完成点只有客户端应答走的
  `PlayerConnection.java:370`）。所以 SOAK-1 是 F02 的**残余**，本轮只是把它的代价量到了。
- 现象：`doConfiguration` 在一条**虚拟线程**里阻塞等待 known-packs 应答。客户端若在此之前就
  终止（premature-finish 触发协议错误、或登录后直接静默 / 断开），服务端向客户端发出
  `synaxis:protocol-error` 断线、TCP 也关了，**但这个虚拟线程不会被唤醒或取消**，它一直
  `park` 到 5 分钟超时才走 `catch (TimeoutException)` 打 `failed to respond to known packs query`
  并 `disconnect()`。在这 5 分钟里，与它绑定的 `Player`、`PlayerSocketConnection`、
  `ClientSettings`、`GameProfile`、`PlayerInputs` 全部存活、无法 GC。
- 证据一（滞留随攻击次数累积）：专门复现，
  `--connections 0 --duration 180 --attacks premature-finish --attack-interval 0.02`，
  实测攻击速率 9.05 次/秒 = 1,362 次 / 151 秒。单条 premature-finish 客户端 0.09 秒就收到
  protocol-error 断线退出，服务端侧却按攻击次数堆积。下表每一行的四个数取自同一时刻（一次
  `Thread.dump_to_file` + 一次 `GC.class_histogram`，后者自带 Full GC）：

  | 攻击开始后 | 累计攻击 | 卡在 doConfiguration 的虚拟线程 | dump 里的全部线程 | 存活 Player |
  | --- | --- | --- | --- | --- |
  | 30 s | 271 | 145 | 172 | 154 |
  | 60 s | 544 | 165 | 193 | 181 |
  | 90 s | 816 | 244 | 272 | 260 |
  | 120 s | 1,089 | 315 | 340 | 335 |
  | 151 s | 1,362 | 356 | 381 | 377 |

  末行这一刻，滞留线程占 dump 里全部线程的 93%（356 / 381）。
- 证据二（回收地平线）：**另一次独立的洪泛**，零点定义为该次运行的最后一次攻击——90 秒洪泛，
  718 次 premature-finish（7.98 次/秒），attacker 在 duration 到点那一刻置停止位，末次攻击
  （单次约 0.1 秒）横跨零点；洪泛开始前该 JVM 是干净的（15 个线程、0 个 `Player`）：

  | 相对最后一次攻击 | 卡在 doConfiguration 的虚拟线程 | dump 里的全部线程 | 存活 Player | 存活 PlayerSocketConnection | 累计 known-packs WARN |
  | --- | --- | --- | --- | --- | --- |
  | −60 s（洪泛中） | 129 | 156 | 130 | 130 | 0 |
  | −30 s（洪泛中） | 139 | 166 | 143 | 144 | 0 |
  | −5 s（洪泛中） | 151 | 176 | 158 | 158 | 0 |
  | +15 s | 151 | 176 | 158 | 158 | 0 |
  | +60 s | 151 | 169 | 158 | 158 | 0 |
  | +120 s | 151 | 169 | 158 | 158 | 0 |
  | +180 s | 151 | 169 | 158 | 158 | 0 |
  | +240 s | 22 | 42 | 29 | 29 | 129 |
  | +300 s | **0** | 20 | 7 | 7 | 151 |
  | +330 / +360 / +390 / +420 s | 0 | 18 | 7 | 7 | 151 |

  这一跑的滞留数在 30 秒后基本饱和（129 → 151），与证据一的近线性增长不同——滞留/攻击比随负载
  下滑（见「组 3」里 LOG-1 那条的解释），两次跑的绝对增速不可直接比较，可比的是下面的时间轴。

  三点读数：① 停止攻击后**整整 180 秒一条线程都不放**，全部回收挤在 +180 s 与 +300 s 之间，
  地平线就是「最后一次攻击 + `knownPacksResponseTimeout`（默认 5 分钟）」——每条连接的超时从它
  自己进入等待的那一刻起算，与断开无关，所以滞留有上界、不是无界泄漏，但上界要按**最后一次
  连接**算；② 151 行 `failed to respond to known packs query` 与 151 条滞留线程一一对应，
  说明这些线程全部是超时唤醒的，没有别的出口；③ `Player` 不归零（7 个），见下面的 SOAK-2。

- 影响：这是放大型资源占用——离线模式下任意用户名、一次 0.09 秒的握手+登录+早退，就换来服务端
  5 分钟的一条虚拟线程 + 一整个 Player 对象图。低速持续（实测 9 次/秒即累积数百上千条）即可把
  内存与线程数抬到攻击速率 ×300 秒的水平（而且这 300 秒从**最后一次**攻击起算，持续洪泛期间
  一条都不会提前释放）；正常玩家高延迟掉线也会踩到同一路径。稳态与 churn 两组
  没有触发它，正因为那两组的连接都**完整进了 play 之后**才断开，走的是另一条能即时回收的路径。
- 复现步骤：见本目录 `README.md` 末节。
- 归属：引擎缺陷。**已于 2026-09-18 修复**：`ConnectionManager#removePlayer` 在
  `releaseResourcePackWait()` 之外再调 `PlayerConnection#releaseKnownPacksWait()`，用空清单完成
  这个 future，`doConfiguration` 拿到结果后按 `isOnline()` 直接退出；默认 5 分钟超时不动（它现在
  只覆盖「连着但不应答」这一档）。回归用例
  `ConfigurationKnownPacksIntegrationTest#configurationStopsWaitingForKnownPacksWhenTheClientLeaves`，
  去向登记在 `docs/DESIGN.md` §28 与 `fixes.md` 的 F02 行。

### SOAK-2（P2）：约 1% 的早退连接留下永不回收的 `Player`

- 现象：SOAK-1 证据一那一跑的线程归 0 之后，`GC.class_histogram`（自带 Full GC）连续三次都还
  数到 23 个存活 `Player`，对应此前累计 2,165 次 premature-finish。再打一轮 1,627 次同样的攻击、等到线程再次
  归 0，残留从 23 升到 40（+17，同样连过三次 Full GC 不降）——**残留随攻击次数累积，不会自愈**，
  比例约 1%。
- 第三个数据点（基线更干净）：**新起的 JVM**，洪泛前 0 个 `Player`，718 次 premature-finish 之后
  等到滞留线程归 0，连续四次 `GC.class_histogram` 都稳定数到 7 个 `Player` + 7 个
  `PlayerSocketConnection`（0.97%，与前两轮的约 1% 一致）。两个类同数残留，是查持有者时的线索。
- 对照：同一进程上做 5 次完整的 `probe.py login --hold 2` 再断开，残留计数 23 → 23 不变。所以
  留下残留的是 configuration 阶段早退这条路径，正常连接的回收是干净的（也与组 1 / 组 2 的堆存活集
  不涨一致：40 个 `Player` 的浅堆只有 18.5 KiB，连带保留的对象图更大但未测，20 分钟量级的长跑
  都看不出来）。
- ~~未定位~~ **持有者已定位并修复（2026-09-18）**：是 `ConnectionManager` 的
  `configurationPlayers` 与 `keepAlivePlayers`。`doConfiguration` 进门先把玩家放进这两个集合，
  而断线处理（`removePlayer`）若抢在这之前跑完，它摘的是一个空集合，之后那次插入再没有人来撤销
  ——两个集合都是 `ConnectionManager` 的字段，于是 `Player` 连同 `PlayerSocketConnection` 永久
  可达，这正是「两个类同数残留」的原因。本轮的猜测「与 SOAK-1 同源」成立：同一条早退路径上，
  断线与 `doConfiguration` 的赛跑赢在哪一侧决定了是滞留线程（SOAK-1）还是永久残留（SOAK-2）。
  修复是把三处插入统一走与 `removePlayer` 同锁的 `ConnectionManager#trackPlayer`，插入前复核连接
  仍在 `connectionPlayerMap` 里。回归用例
  `ConfigurationKnownPacksIntegrationTest#aClientGoneBeforeConfigurationStartsLeavesNothingBehind`。
- 复现：SOAK-1 的复现命令跑两轮，每轮结束后静置到线程归 0，再
  `jcmd <pid> GC.class_histogram | grep -E 'synaxis.entity.Player$|PlayerSocketConnection$'`
  对比两轮的残留数。

### SOAK-3（P1）：两个压测进程撞用户名 ⇒ 重复 UUID 进 play ⇒ 每个移动包一行 WARN（15 分钟 84 MiB）

- **发现者与场景**：验收人在 NFR 级复测里同时起两个压测进程、各 25 条连接。`soak.py` 的用户名固定为
  `soak0000..soak0024`（不可配），引擎默认离线模式、UUID 由用户名派生，于是第二批的 25 个玩家
  带着**已经在线的 UUID** 登录。上面三组之所以一次都没踩到，是因为它们都只跑一个 harness 进程。
- 触发链（已查清，证据在验收人保留的片段里：异常全栈 + WARN 样本 + 行数与日志字节数）：
  1. 第二个同 UUID 的玩家照常通过登录与 configuration，进入 play；
  2. tick 线程上 `ConnectionManager.updateWaitingPlayers` → `Entity.setInstance` 的续体 →
     `EntityTrackerImpl.register` 抛 `IllegalStateException: There is already an entity registered
     with uuid <uuid>`；
  3. 这个异常被续体里的 `catch (Exception e) -> handleException` 吞掉，默认处理器只打一行栈；
  4. 结果：玩家在世界里、连接活着、能收发包，但**不在 `EntityTracker` 里**；
  5. 此后它每发一个移动包，`EntityTrackerImpl` 就写一行
     `Attempted to move unregistered entity N in the entity tracker`。实测 15 分钟 662,803 行 / 84 MiB。
- **来历**：2026-09-15 审计 F59 的 notes 里点过名——「离线模式下 `LoginListener` 直接采信客户端
  `profileId`、`ConnectionManager` 无重复 UUID 拒绝」当时不在那一批的文件范围里，**未处理**。
  F59 本身（register 的原子性）已经修过，正因为它修了，第 2 步才是干净地抛出而不是留下半张表。
- **处置（2026-09-18，三层防线，都已合入）**：
  1. **门口拦住**：`ConnectionManager` 新增一张 profile UUID → `Player` 的索引，与
     `connectionPlayerMap` 在同一个 `synchronized` 里写，占用期严格等于「连接被跟踪」的时长。
     `createPlayer` 占不到就返回 `null`，`LoginListener#loginAckListener` 以新原因键
     **`synaxis:duplicate-login`** 拒掉**后来的**那条连接（`docs/DESIGN.md` §11.1 新增一条
     login→disconnected 转移、§14 新增该原因键）。拒新不踢旧：离线模式下 UUID 由用户名派生，
     踢老的等于给了任何人一个「顶掉别人」的手段；僵死会话由 keep-alive 在 `keepAliveKick` 内清掉，
     占用窗口有界，所以合法重连的行为是**被拒 + 客户端重试**，而不是等待。
     判定点选在 login-ack（`createPlayer`）而不是进 play 之前：那是 profile 最终确定后的第一个时刻
     （`AsyncPlayerPreLoginEvent` 可以改 UUID，改完才发 `LoginSuccess`），也是 `Player` 对象诞生、
     第一次进入 `connectionPlayerMap` 的时刻——把占用与这次插入放进同一把锁，占用与释放就天然
     跟 `removePlayer` 对齐，不会出现「占了没人放」的孤儿。
  2. **失败不留僵尸**：`Entity#setInstance` 的续体不再吞异常，抛 `CompletionException`；
     `Player#spawnPlayer` 读回 `super.setInstance` 的 future（原来整个丢掉）、`Player#setInstance`
     的两条完成路径把它转成失败的 future，`ConnectionManager#updateWaitingPlayers` 既有的
     `exceptionally` 于是真的会跑，以 `synaxis:internal-error` 断开该连接，连接的 `disconnect()`
     再摘掉玩家并在下一 tick `remove()` 掉实体。这一层对**任何**注册失败成立，不只是 UUID 冲突。
     被取消的 `AddEntityToInstanceEvent` 不受影响：`spawnPlayer` 仍在读 future 之前按
     `this.instance != instance` 提前返回。
  3. **日志有界**：`EntityTrackerImpl` 的「移动未注册实体」改成每 10 秒最多一行，行内带
     「距上次报告以来的累计次数」。选按时间限流而不是「每个实体一次」：后者要按 entity id 记一张
     只增不减的表，而 entity id 单调递增，那张表本身就是新的无界增长。这一层无论前两层是否生效
     都成立。
- **修复前后实测（同一台机、同一条命令、各跑 200 秒）**：两个 `soak.py` 进程各 25 连接、
  `--move-hz 10 --echo-hz 0 --no-jfr`，第二个晚 12 秒起（确保第一批已进 play）。

  | 指标 | 修复前 | 修复后 |
  | --- | --- | --- |
  | `There is already an entity registered with uuid` | 25 | **0** |
  | `Attempted to move unregistered entity` | 47,350 | **0** |
  | 服务端日志字节数（整跑，含启动的 6.8 KB） | 6,329,554 | **29,384** |
  | 服务端日志行数 | 48,137 | **212** |
  | 异常栈行（`^\tat `） | 575 | **0** |
  | `reason=synaxis:duplicate-login` 的 kick | 0（原因键不存在） | 25 |
  | 先起那组的连接 / 移动包 | 25 / 47,497 | 25 / 47,423（**在线会话不受影响**） |
  | 后起那组 | 25 条全部进 play（即缺陷本身） | 25 条全部 `login_failures = disconnect:synaxis:duplicate-login` |

  复现命令与判据已登记为 `docs/TEST.md` 的 `TC-032`；两次跑的 `--out` 目录与服务端日志已按纪律删除。
- 归属：引擎缺陷。**已于 2026-09-18 修复**，去向登记在 `docs/DESIGN.md` §28 与
  `docs/research/audit-2026-09-15/fixes.md` 的 F59 行。回归用例
  `DuplicateUuidLoginIntegrationTest`（三条：拒绝、并发恰好一条赢、旧连接摘掉后 UUID 立刻可用）、
  `FailedSpawnRegistrationIntegrationTest`（两条：注册被拒让 future 异常完成、玩家收到
  `synaxis:internal-error` 并在下一 tick 离开世界）、`EntityTrackerUnregisteredMoveTest`（一条：
  一万次移动尝试不是一万行日志）。

### SOAK-4（P2，已修）：填满包队列的断线不带错误码

- **现象**：50 × 1,200 包/秒的负控里，50 条连接全部被断开，但断线原因是纯文本组件
  `Component.text("Too Many Packets", RED)`，不是 §14 要求的 `translate` 键。发包端读出来是
  `{'<no translate or text key>': 50}`（补上 `text` 解析后是 `{'Too Many Packets': 50}`）。
- **为什么算缺陷**：§14 写明「原因文本为 Adventure `Component`，其 `translate` 键即稳定错误码」，
  §15 的限流那一行写明超限断线用 `synaxis:protocol-error`。这条路掉在错误码信封外面，排障时既没有
  错误码可匹配，也和其他六个原因键对不上。**本轮修的是其中一条**——客户端可达的纯文本断线不止这一处，
  清点见 `SOAK-6`。
- **修法**：`Player#addPacketToQueue` 队列满时改 `kick(DisconnectReasons.PROTOCOL_ERROR)`。
  设计文档不动——§14 / §15 本来就是这么写的，是实现没追齐。
- **回归**：`DisconnectReasonsIntegrationTest#aFloodedPacketQueueDisconnectsWithAProtocolError`
  （先写会失败的那一版：`Unexpected type, expected: <TranslatableComponent> but was: <TextComponentImpl>`）。
- **端到端复核**：修复后同一负控，服务端日志 50 行 `Kicking connection_id=… reason=synaxis:protocol-error`，
  发包端读到 `{'synaxis:protocol-error': 40}`（另外 10 条的 socket 先被关掉、没读到断线包，属正常竞争）。

### SOAK-5（P2，未修）：§15 写的「单连接每秒 > 500 包断线」没有实现

- **现象**：一条连接以 600 包/秒连发 60 秒，`0 lost`、服务端 0 kick、0 WARN；50 条连接各 800 包/秒
  （远超 500）连发 90 秒同样一条都不掉。真正会断开的阈值是**队列排空速度**：
  `ServerConfig#playerPacketPerTick` 默认 50，20 TPS 下即 1,000 包/秒，超过它队列
  （`playerPacketQueueSize` 默认 1000，数组队列实际容量向上取到 1024）才会满、才踢人。
- **也就是说**：文档写的是 500 包/秒的速率闸，实现是 ~1,000 包/秒的排空能力 + 队列深度，二者
  既不是同一个数，也不是同一种语义（速率闸能挡住持续超速，队列只在**超过排空速度**时才满——
  一条连接完全可以用 999 包/秒长期跑满，而那已经是 §15 文本的两倍）。
- **没有在本轮改**：定 500 还是 1,000、要不要真的加一个每秒计数器（热路径上每包一次计数）、
  超限该踢还是该丢，都是设计决定，不是实现补齐。留给验收人。

### SOAK-6（P2，未修）：§14 的错误码信封外，客户端可达的纯文本断线有七处

- **怎么数的（口径写死，不是「扫了一遍」）**：`grep -rn 'kick(' src/main/java` 在本轮 HEAD 上得到
  31 行，其中 1 行是 `AsyncPlayerConfigurationEvent` 的 Javadoc 引用，剩下 **30 个调用点**。逐个判
  两件事——「谁能触发它」与「原因组件有没有 `translate` 键」——落进下面六档，分档计数
  7 + 2 + 5 + 3 + 2 + 11 = 30，没有留「等等」这种收尾。`SOAK-4` 修的那一条现在在第 ⑥ 档。

- **① 默认配置下客户端的包就能触发、原因却是 `Component.text(...)` 的七处**，全部掉在 §14 的
  错误码信封外面：

  | 位置 | 文本 | 客户端怎么触发 |
  | --- | --- | --- |
  | `listener/common/KeepAliveListener.java:11+16` | `Bad Keep Alive packet` | 一个 id 对不上的 `ClientKeepAlivePacket`，比填满 1024 深的队列容易得多 |
  | `listener/PlayerPositionListener.java:21+61` | `You moved too far away!` | 位置包带 NaN / 无穷，或 \|坐标\| > `Entity.MAX_COORDINATE` |
  | `listener/PlayerVehicleListener.java:22` | 同上（共用 `PlayerPositionListener.KICK_MESSAGE`） | 载具移动包按同一判据 |
  | `listener/preplay/LoginListener.java:36+48` | `Malformed username!` | 登录包里的用户名由客户端决定 |
  | `listener/preplay/LoginListener.java:35+124` | `Error during login!` | 发一个服务端从没请求过的 `ClientLoginPluginResponsePacket`：`LoginPluginMessageProcessor#handleResponse` 对陌生 messageId 直接抛。仓库自己的 `PreAuthConnectionIntegrationTest#aKickStopsTheRestOfTheBatchFromBeingHandled`（TC-033）每轮都在走这条路 |
  | `network/ConnectionManager.java:59+484` | `Timeout` | 停止应答 KeepAlive 超过 `keepAliveKick`（默认 15 s）——只要不回包即可，是真实环境里最常见的一种断线 |
  | `entity/Player.java:162+1566` | `Required resource pack was not loaded.` | 服务端下发过必需资源包时，客户端回一个非 `SUCCESSFULLY_LOADED` 的状态 |

- **② 入口是客户端的包、但要先有一次服务端内部失败的两处**：`LoginListener.java:149`
  （`loginAckListener` 的 `catch`，`createPlayer` 抛）与 `LoginListener.java:198`
  （`executeConfig` 起的虚拟线程里 `doConfiguration` 抛），共用 ① 的 `ERROR_DURING_LOGIN` 常量。
  触发条件是引擎自己的异常而不是包的内容，所以单列一档。

- **③ 只在代理转发配置（或宿主自己发过登录插件请求）下够得着的五处**，同样是纯文本：
  `LoginListener.java:38+100/110` 与 `ConnectionManager.java:237` 的 `Invalid proxy response!`
  —— 前两处是 Velocity 应答本身不合法，第三处是 `awaitReplies` 超时，引擎里只有 Velocity 模式会留下
  待答请求，宿主在 `AsyncPlayerPreLoginEvent` 里自己 `request(...)` 也到得了；
  `HandshakeListener.java:33+152` 的 `Invalid connection, please connect through the BungeeCord proxy...`；
  以及 `LoginListener.java:83` 的 `Login plugin response is not supported in this auth mode` —— 这一处挂在
  Velocity 请求的回调上，而 `ServerProcessImpl#auth` 是 `final`，它那个 `!(auth instanceof Auth.Velocity)`
  判断不可能为真，实际是条不可达分支（本轮不动它，一并留给拍板）。

- **④ 由服务端代码决定、客户端够不着的三处**：`Player.java:161+654` 的
  `You have been removed from the server without reason.`（`remove(true)` 时连接还在），以及
  `Player.java:1928` / `:1937` —— 后两处是 `kick(Component)` / `kick(String)` 这两个公开 API 本身，
  理由由调用方给，不属于引擎自己的错误码面。

- **⑤ 带 `translate` 键但不是 §14 错误码的两处**，用的是原版键：`Player.java:2362` 的
  `multiplayer.disconnect.invalid_packet`（`rejectMisusedPacket` 打开时）与 `HandshakeListener.java:38+57`
  的 `multiplayer.disconnect.transfers_disabled`。它们至少能被客户端本地化，性质与 ① 不同。

- **⑥ 已经用 §14 原因键（`DisconnectReasons`）的十一处**：`Player.java:2346`、`CookieListener:15`、
  `HandshakeListener:63`、`LoginListener:44 / 143 / 158 / 173`、`ConnectionManager:384 / 389 / 455`、
  `PlayerSocketConnection:167`。`Player.java:2346` 是 `SOAK-4` 本轮从 ① 挪进来的。

- **顺带的一条**：`PlayerConnection#kick`（`network/player/PlayerConnection.java:219-222`）只对
  `TranslatableComponent` 写 `Kicking connection_id=… identifier=… reason=…`，所以 ①～④ 这些断线在
  服务端一行日志都不留——`Timeout` 这种最常见的断线，排障时连一个计数都拿不到。

- **为什么本轮不一并改**：§14 现在只有六个原因键，① 里这些要么得复用 `synaxis:protocol-error`
  （`Bad Keep Alive packet`、位置越界属于协议违例，尚可）、要么得新开原因键（资源包、用户名格式、
  keep-alive 超时、代理握手），那是往 §14 的错误码表里加行，属于设计决定。本轮只把压测直接打到的
  那一条追齐，其余登记为待办（`docs/DESIGN.md` §28）。

### 观察（不是缺陷）：5,000 包/秒下最慢的那一个 tick 在 23–47 ms 之间

三轮 5,000 包/秒的长跑，max tick 分别是 33.3 / 46.8 / 34.7 ms，**超 50 ms 的 tick 一个都没有**，
p99 只有 1.9–2.3 ms。（口径对齐的那次复跑见组 6，它窗口首 tick 的 114.4 ms 是 50 条连接同时进场，
不属这里说的稳态尖刺；进场之后它整段最大 17.6 ms。）也就是 p99 离预算有 25 倍余量，但**最慢的那
一个 tick 只剩 1.1–1.5 倍**。
逐条对时的结论：

- 10–15 ms 那一档大多能与 G1 young GC 对上（如 13:38:00.035 的 14.9 ms tick 对 14.2 ms 的
  `G1 Evacuation Pause`）；组 6 里 49.5 ms 的 `G1Full` 是 harness 自己的 `jcmd GC.run` 存活集探针，
  不算引擎。
- 最大的那几个（34.7 / 27.1 / 23.4 ms）**没有**对应的 GC 事件。JFR 默认 20 ms 一次的执行采样在
  这些 tick 里只落到一两个样本，其中 23.4 ms 那个采到的是
  `ThreadDispatcherImpl#processUpdatedElement → ArrayList.remove` —— 分区元素表是 `ArrayList`，
  换分区时的移除是线性扫描。一两个样本不足以定性，只作为下一轮的排查起点记在这里。

### 未发现的项（如实记录）

- 稳态 20 分钟、churn 10 分钟内，tick 时间、堆存活集、线程数、FD 均**无单调增长**（SOAK-2 的
  残留量级太小，这两组的堆存活集看不出来）；
  唯一的 tick 尖刺是玩家进场时的一次性区块生成（第 0 分钟，≤ 68 ms），此后每分钟 max ≤ 4 ms。
- 三组停机都干净：退出码 0，无残留 java 进程 / 线程。
- 六种攻击均未使正常连接掉线、未使服务端崩溃或挂起、日志无堆栈。

## 局限

- **只跑了一条 tick 线程。** 三组都是默认起服，`ServerConfig.java:185` 的 `dispatcherThreads`
  默认 1，于是审计里最大的一簇跨 TickThread 缺陷（F27 / F17 / F58 / F22 / F37 那类 ABBA 锁序与
  tick 线程间竞态）在这个配置下根本不可能显现，DESIGN.md:417 写的「各区块分区并行」也没有被验证。
  要覆盖它必须另起一组 `dispatcherThreads > 1` 的长跑。
- **客户端包面只有 4 种。** harness 发 `ClientKeepAlive` / `ClientTeleportConfirm` /
  `ClientPlayerPositionAndRotation` / `ClientPluginMessage`，复用的 `probe.py` 在 configuration
  阶段也从不发 `ClientSettings`。因此视距变更（F11 在 2026-09-16 刚改成按 tick 节流的 `chunkQueue`
  路径，恰是只有长跑才显形的那类）、方块放置 / 破坏与光照、容器点击、聊天与命令补全全程零覆盖。
  另外每条连接只在半径 16–72 格的圆上走，64 条连接始终挤在 spawn 周围约 145 格的圆盘里，而
  DESIGN.md:375 的 tick 预算场景要求「各自站在不同区块」——区块与分区铺开的那一面没测到。
- **审计里那批只在别的形态下显形的缺陷，这一轮一条都没触达**：F30 `InstanceContainer#copy()` 的
  dispatcher 分区、F44 `DynamicRegistryImpl` 重复注册、F16 同 JVM 多 `ServerProcess` 的 shutdown
  hook、F60 `BossBarManager` 的 viewer 生命周期——单进程、单 instance、无插件的核心模式一条都到
  不了；F49 的 `AutoIncrementMap` 增长同理（客户端喂键名那条路已在 R02 关掉，`AutoIncrementMap`
  本身仍无界，而 harness 的 nbt-bomb 键名固定，喂不出增长）。这些项的状态只能靠单测判断，长跑
  给不出任何信息。
- **churn 组「连接被及时回收」的判据分辨率不够。** 那一组只看了 ~30 MiB 量级的堆存活集，而 SOAK-2
  本身证明了这个刻度看不见每连接级别的残留（40 个 `Player` 的浅堆只有 18.5 KiB）。要坐实它，
  churn 组得补一条 `GC.class_histogram` 的 `Player` / `PlayerSocketConnection` 计数；本报告没有
  这条证据。
- 单机 loopback，无真实网络抖动；RTT 含 harness 自身 10 ms select 循环。
- 核心模式是平坦世界、无实体 AI / 无玩法 tick 负载，测的是网络 + 连接生命周期 + 世界 tick 底座，
  不代表 game-rts 满载。稳态连接数（64）对核心是轻载，压力主要由 churn 与攻击两组提供。
- JFR 只开了 `synaxis.ServerTickTime` 与虚拟线程起止；未开分配采样（`jdk.ObjectAllocationSample`）。

- 入站包 5,000 包/秒这条 §15 目标**已被证明达标**（2026-09-18，组 6）：换成 Java 发包端`tools/loadtest/PacketFlood.java` 后实测 5,014.9 包/秒持续 6 分钟、0 掉线，发包窗口内 7,200 个 tick p99 1.631 ms，超 50 ms 的只有 50 条连接同时进场的那个窗口首 tick；组 7 把速率抬到 40,015 包/秒仍然 0 掉线、p99 1.5 ms，余量至少 8 倍。`soak.py` 的 2,880 / 3,990 包/秒确实是 Python 发包端自己的上限，与引擎无关。**仍未测到的是「各自站在不同区块」那一面**：两个发包端的连接都挤在 spawn 周围的圆盘里，区块与分区铺开的场景另需一轮。
