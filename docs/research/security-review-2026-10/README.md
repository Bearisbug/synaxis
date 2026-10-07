# 安全审查 · 2026-10-01

对象是生产就绪第 1～6 波加进来的攻击面：加密与正版验证、连接闸门 L0～L3 与入站速率闸（引擎 `network` / `listener` 包），转服票据与玩家数据持久化（PostgreSQL + Valkey），GM 命令与权限，管理接口与指标端点，`profile` 录制、崩溃报告与事件台账的落盘，配置文件、运行时机密与依赖构建链。上一轮是 2026-09-22 的攻击面盘点（`docs/research/attack-surface-2026-09/README.md`，基线 `c961f0492b`）。审查在 `main` `2bed5675f2` 上做；四个修复分支都从 `23982267e9` 拉出，两者之间只差 RUN-061 的文档与证据，代码相同；修复在 `prod/integrate-062` 集成，执行轮次 RUN-062。

方法：四个面（A 玩家入口、B 转服与持久化、C 管理与运维、D 配置、机密与供应链）各一个审查者、一个对抗复核者。审查者读代码报 finding，并列出核过、认为受控的面（A 11 个、B 10 个、C 17 个、D 16 个）；复核者逐条重读，改判严重度并给出 confirmed / plausible，驳回或改写了其中 7 个「受控」判断，另报审查者漏掉的 4 条（编号带 V）。最后一个完整性检查把 20 条串成跨面利用链，列出没人覆盖的面，分修复组，并列出待用户拍板的事项。审查与复核都只读代码、没有运行，每条修复的「先红」是它第一次实跑复现。修复分四组，每组先写复现测试拿到红，修复后转绿并逐条反证，再交独立评审；transfer、ops、secrets 三组首轮评审分别有 1、2、1 个阻塞项，返工后通过，login 组首轮通过。

结果一句话：20 条复核后 high 1、medium 3、low 14、info 2，没有 critical（confirmed 12、plausible 4、复核新增 4）；**修了 15 条**（代码加先红后绿的测试），**只改文档 2 条**（C-V01、D-05），**登记 3 条**（B-02、B-V01 按用户拍板不修，D-04 属 later 档、不在本轮范围），**交光照会话**的是 B-01 里 `ShardGates#moved` 的加固 1 项，以及完整性检查列出的 12 个未覆盖面里的 3 个；另有 `docs/DESIGN-M3.md` 与 TC-WORLD 用例的同步若干（§7）。

## 1. 总表

| 编号 | 面 | 标题 | 严重度（审查 → 复核） | 结论 | 处置 |
| --- | --- | --- | --- | --- | --- |
| A-01 | 玩家入口 | 握手 `serverAddress` 超过 255 字符时监听器在过闸之前抛异常，连接跳过 L2 三只桶；开 PROXY 时还跳过 L1 与 5 s 握手超时，照样进 LOGIN | high → high | confirmed | 已修：`sec/login-path`，TC-PROD-576～579 |
| A-02 | 玩家入口 | L3 席位在认证前的 `LoginStart` 就占、挂到 30 s 登录超时；按网段封禁在认证之后才判，挡不住占位 | medium → medium | confirmed | 已修：`sec/login-path`（席位挪到认证之后，`ADR-PROD-015`），TC-PROD-580～582 |
| A-03 | 玩家入口 | 未认证对端能持续驱动 `hasJoined`，没有速率上限，429 还重试一次 | low → low | plausible | 已修：`sec/login-path`（429 按 `Retry-After` 停发、不重试），TC-PROD-583 |
| A-04 | 玩家入口 | 即时包（ping、keep-alive、cookie）不经入站速率闸；复核改判只有 play 态成立，要正版账号 | low → low | plausible | 已修：`sec/login-path`，TC-PROD-584 |
| B-01 | 转服与持久化 | 源分片签票后从不断开：不照 `Transfer` 走的客户端留下 `in_transit` 幽灵，占席位、跨分片多开、`not_online` 台账能一直写 | medium → medium | confirmed | 已修：`sec/transfer-ghost`（10 s 后断开），TC-PROD-590～593；`ShardGates#moved` 加固由光照会话做（`docs/DESIGN-M3.md` v0.31） |
| B-02 | 转服与持久化 | 数据段大小的契约口径（紧凑编码 ≤ 65,536 B）与库约束口径（jsonb 文本）不一致；超限被当成数据库不可用，整名角色存不进且不冻结 | low → low | confirmed | 登记：DESIGN-PROD §28.2（用户拍板不修） |
| B-V01 | 转服与持久化 | 数据段含 U+0000 或 NaN / Infinity 时 jsonb 拒收，同样整名角色存不进、无限重试、不冻结 | — → low | 复核新增 | 登记：DESIGN-PROD §28.2（用户拍板不修） |
| C-01 | 管理与运维 | 管理接口握手不查 `Origin`：反代注入令牌的部署下，浏览器里的网页能拿到管理权限（CSWSH） | medium → low | plausible | 已修：`sec/ops-ports`（带 `Origin` 一律拒），TC-PROD-600 |
| C-02 | 管理与运维 | `gm.role` 的授予判定只看内存缓存：持有者能把限时授予改成永久，或在撤销生效之前授回 | low → low | confirmed | 已修：`sec/transfer-ghost`，TC-PROD-594、595、597 |
| C-03 | 管理与运维 | 游戏端口绑不上时进程不退出，两个运维端口照报健康（§28.2 原已登记，本条补全） | low → info | confirmed | 已修：`sec/ops-ports`，TC-PROD-604、605 |
| C-04 | 管理与运维 | `profile` 文件带全体玩家的聊天与命令原文、status 来源 IP，按 umask 写成 0644 | low → low | confirmed | 已修：`sec/secrets-files`，TC-PROD-614 |
| C-05 | 管理与运维 | 运维端口绑到非回环地址时没有护栏：指标端点不鉴权、不限连接数，管理接口握手前的连接也不限 | low → low | plausible | 已修：`sec/ops-ports`，TC-PROD-601～603 |
| C-V01 | 管理与运维 | 回环挡不住同机其他系统用户：分片不在监听时抢先占 `127.0.0.1:25585`，收下客户端先发出的 Bearer 令牌 | — → medium | 复核新增 | 只改文档：`sec/ops-ports`（§23 部署前提、每分片一个令牌），TC-PROD-606（人工，未执行） |
| D-01 | 配置、机密与供应链 | 连接串里的口令进日志：`StoreSettings#toString` 原样印 JDBC URL，`RedisLink` 解析失败的异常带原串 | low → low | confirmed | 已修：`sec/secrets-files`，TC-PROD-610、611 |
| D-02 | 配置、机密与供应链 | `SessionServer#isMojang` 只比主机名，`http://sessionserver.mojang.com` 被当成正版验证 | low → low | confirmed | 已修：`sec/secrets-files`，TC-PROD-612 |
| D-03 | 配置、机密与供应链 | 配置文件加载不查属主与权限，而文件里有决定鉴权与闸门的键 | low → low | confirmed | 已修：`sec/secrets-files`，TC-PROD-613、619 |
| D-04 | 配置、机密与供应链 | 构建链没有完整性锚点：先走第三方镜像，没有依赖校验元数据，wrapper 与 Actions 没钉哈希 | low → low | confirmed | 登记：DESIGN-PROD §28.2（later 档，不在本轮范围） |
| D-05 | 配置、机密与供应链 | Java-WebSocket 1.6.0 的 CVE-2026-90584 已被 `BoundedDraft` 挡住，但 §28.1 Q10 要写下判定 | info → info | confirmed | 只改文档：`sec/secrets-files`（§28.2 判为不影响本用法），TC-PROD-617 |
| D-V01 | 配置、机密与供应链 | `.env` 没有权限要求：照 `.env.example` 复制得到 0644，四类机密对同机用户可读 | — → low | 复核新增 | 已修：`sec/secrets-files`（代码侧 0600 / 0700，部署侧 `UMask=0077`），TC-PROD-616 |
| D-V02 | 配置、机密与供应链 | `profile` 文件仍带本机全部进程的命令行、`-D` 系统属性与 JVM 参数 | — → low | 复核新增 | 已修：`sec/secrets-files`，TC-PROD-615 |

## 2. 修复组

全量一栏是各组在自己分支上报的数，都带数据库变量，默认与严格 `grid`（`-Dsynaxis.dispatcher-threads=4 -Dsynaxis.acquirable-strict=true -Dsynaxis.thread-provider=grid`）各一次。合并后的全量以 RUN-062 的登记为准。

| 组 | 分支（worktree，HEAD） | DESIGN-PROD | finding | 用例 | 证据 | 全量 | 评审 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| login | `sec/login-path`（`synaxis-wt/sec-login`，`68fd5d4fd8`） | v0.9.39，`ADR-PROD-015` | A-01～A-04 | TC-PROD-576～585 | `docs/test-runs/RUN-062/login/` | 根模块 3,158、`server-app` 524，0 失败 | 首轮通过 |
| transfer | `sec/transfer-ghost`（`synaxis-wt/sec-transfer`，`6d8ddb99d5`） | v0.9.40 | B-01、C-02；另只读核查 `grid` 竞态（§5 第 3 行） | TC-PROD-590～597 | `docs/test-runs/RUN-062/transfer/` | `server-app` 529，0 失败；引擎源码没改，根模块没跑全量 | 首轮阻塞 1 项（C-02 的 §8、§10 文字与代码不一致），返工后通过 |
| ops | `sec/ops-ports`（`synaxis-wt/sec-ops`，`1a6943c625`） | v0.9.41 | C-01、C-03、C-05、C-V01 | TC-PROD-600～607 | `docs/test-runs/RUN-062/ops/` | `server-app` 528，0 失败；引擎源码没改，根模块没跑全量 | 首轮阻塞 2 项（TC-PROD-533 第 2 步行号；§23 两行互相矛盾），返工后通过 |
| secrets | `sec/secrets-files`（`synaxis-wt/sec-secrets`，`89e9f87dc5`） | v0.9.42 | D-01、D-02、D-03、C-04、D-V02、D-V01、D-05 | TC-PROD-610～619 | `docs/test-runs/RUN-062/secrets/` | 根模块 3,148、`server-app` 536，0 失败；严格 `grid` 第一次红 1 条（§8 端口竞态），第二次绿 | 首轮阻塞 1 项（目录只查他人写位、不查组写位），返工后通过 |

## 3. 用户拍板（2026-10-01）

修复范围定为 now 与 next 两档；later 档只有 D-04。完整性检查列出的待拍板事项里，前四项的拍板与落地如下。

| 事项 | 拍板 | 落地 |
| --- | --- | --- |
| 认证前席位（A-02） | 席位挪到认证之后 | login 组：`LoginStart` 只调不占位的 `ConnectionGate#seatFree()`，满员在 RSA 与 `hasJoined` 之前回 `synaxis:shard-full`；席位在 `ConnectionManager#transitionLoginToConfig` 开头、`AsyncPlayerPreLoginEvent` 之前以一次 CAS 占，失败同样回 `shard-full`（已加密时加密发出）。认证前按网段封禁的 accept 阶段钩子不做，登记 §28.2 |
| 落盘与配置文件权限（D-03、D-V01、C-04 的权限部分） | 代码侧与部署侧两层都做 | secrets 组：`EventLedger`、`CrashReports`、`Profiler` 建文件 0600、建目录 0700；`ConfigFile` 遇到组或他人可写的文件、或组或他人可写且没有 sticky 位的所在目录即拒启；§23 检查单加落盘权限一行（`UMask=0077`、工作目录属服务用户 0700、`.env` 0600、上级目录只许 root 或服务用户写） |
| 管理接口的部署前提（C-V01） | 写明部署前提，令牌改为每分片一个，默认端口不变 | ops 组：§23 加一行前提「开管理接口的主机上没有不受信任的系统用户，或每个分片有独立的网络命名空间」；`.env.example` 把 `SYNAXIS_MANAGEMENT_TOKEN` 移到每分片的 `.env.<shard_id>`，变量名不变；`127.0.0.1:25585` 不变；代码不改 |
| 数据段（B-02、B-V01） | 不修，由集成轮登记 | DESIGN-PROD §28.2 登记。复核给的升级条件：插件把玩家输入的字符串、或玩家行为算出的浮点数存进 `DataSection` 时升为 medium |

待拍板事项的后三项不在上面四条里：D-05 的 Q10 判定由 secrets 组按完整性检查的建议只改文档；D-04「生产包在哪里构建」与 `item_ledger`「写不写」登记为 DESIGN-PROD §28.2 的两行 Open Question，待用户定。

## 4. 跨面利用链

**① A-01 + A-02：单个源地址占满席位。** 原链：开 PROXY 时，超长 `serverAddress` 的连接同时跳过 L1、L2 与 5 s 握手超时，单个源地址能开上千条连接，每条在 `LoginStart` 占一个 L3 席位、挂满 30 s，200～500 席的分片对所有新登录与转入回 `synaxis:shard-full`；占位连接不进拒绝计数，按 `RB-PROD-005` 聚合拒绝来源会指向受害者。修复后两环都断：A-01 让超长地址先过闸（扣令牌，PROXY 下计入 L1）再被关，登录前状态的监听器异常一律断开；A-02 让席位在认证之后才占。剩下的变体要正版账号：拿到 `LoginSuccess` 后不发 `LoginAcknowledged` 的连接能占席位到 30 s 登录超时，每 IP 受 L1 = 16 与 L2 约束（§6）。

**② A-01 + A-03：拖累同出口的全部分片。** 原链：默认不开 PROXY 时，单个 IP 的在线登录尝试不受 login 桶 0.5/s 约束，每次加密握手起一次 `hasJoined`；`SessionClient` 只限 32 个在途，遇 429 还重试；若 Mojang 按出口 IP 限速，未认证的攻击者能让共用出口的所有分片对正版玩家回 `auth-unavailable`。同一绕过还带来日志洪水：每连接一行带栈的 ERROR，约 2 KB，约 1 MB/s，约 3 小时把 10 GB 日志额度轮转掉。修复后：L2 恢复（A-01）；`LoginListener` 不再自写 ERROR，只交给 `LogBudget` 限流的异常处理器，日志洪水这一环断了；收到 429 按 `Retry-After`（缺省 10 s、上限 60 s）停发且不重试（A-03），配额耗尽时本分片不再加压。没断的一环：多个 IP 仍能叠加驱动 `hasJoined`，没有按来源的在途上限，停发窗口里本分片的在线登录全部 `auth-unavailable`。§28.2「认证前的连接」一行写了切换条件：同一分片 24 小时内因多来源驱动出现两次以上 429 停发，或 RSA 解密占到一个核的 10% 时，做 accept 阶段的地址判定钩子。

**③ B-01 + C-02 / C-V01：审计失明。** 原链：4～8 个正版小号在同一分片各留一个 `in_transit` 幽灵，在门框边来回走，每个约 20 行/s 的 `transfer_refused(not_online)`，10～20 小时撑满该分片当天 1 GiB 台账；之后 `gm_command`、`player_banned`、`save_fenced`、`lease_takeover` 只剩 `ledger_dropped` 计数，这时用 C-02 把限时授予改成永久，或用 C-V01 偷到的令牌调 `bans/add`、`server/stop`，都不留逐条审计。修复后：幽灵这一环断了，源分片在 `Transfer` 发出 10 s 后断开还连着的玩家，一张票据之后至多再写 5 s `cooldown` 加 5 s `not_online`；但每 5 s 转一次服的账号在每个经过的分片上仍能按 53 次/s 写，台账仍可撑满，要等光照会话的 `ShardGates#moved` 加固（§7）。C-02 这一环断了：玩家发起的 `role grant` 当场读库判定，授出的授予随发送者最早的到期。C-V01 这一环只靠部署前提与每分片一个令牌（§3）。

**④ B-01 + A-02：频道被判满。** 原链：K 个正版账号每走一次关卡门就在源分片留一个幽灵，占 L3 席位并计入心跳 `online`，经过 N 个分片就在每个分片各占 K 席；叠加 A-02 的认证前占位，`ShardServiceImpl` 按心跳人数把频道判为 `FULL`，整个大区的关卡门回 `transfer-failed`，直接登录的玩家收到 `shard-full`。修复后两环都断：幽灵 10 s 后被断开，席位随之释放；认证前的连接不占席位。心跳的 `online`（`gate.seats()`）也不再含认证中的连接。

**⑤ D-01 / D-V01 → 同机直连 PostgreSQL。** 原链：连接串口令进 0644 的 `logs/latest.log`（D-01），照 `.env.example` 复制出的 `.env` 是 0644（D-V01）；同机非特权用户拿到 `SYNAXIS_STATE_DB_PASSWORD` 后直连库，往 `account_role` 插一条 `*` 角色，或改 `character_section` 造物品，`item_ledger` 没有写入，事后查不出。修复后两个泄露源都断：起服自检行只印主机、端口与库名，`RedisLink` 解析失败只报变量名与写法；台账与崩溃报告 0600、目录 0700，`.env.example` 改为 `install -m 600`，§23 要求 `UMask=0077`、`.env` 0600、工作目录 0700。没断的：URL 格式坏掉时 pgjdbc 经 JUL 向 stderr 打完整 URL（systemd 下进 journald）；数据库账号的实际权限与 `item_ledger` 是 §5 第 12 行与第 7 行。

**⑥ C-V01 / D-V01 → 所有分片的管理权。** 原链：`.env.example` 只有一个 `SYNAXIS_MANAGEMENT_TOKEN`，各分片共用；同机用户读 0644 的 `.env`，或在分片重启的空档抢先 bind `127.0.0.1:25585` 收下运维客户端先发出的 Bearer，就能对每个分片调 `server/stop`、不可逆的 `shard/drain`、`bans/add`；抢端口还让真分片绑管理端口失败而拒启，编排反复重启期间端口一直在攻击者手里。修复后：读 `.env` 这一环断了（0600 加 `UMask`）；「一个令牌开所有分片」断了，令牌放在每分片的 `.env.<shard_id>`，拿到一个只开一个分片。抢端口这一环代码没改，靠 §23 的部署前提挡，前提的人工核对 TC-PROD-606 还没在 Linux 部署主机上执行。

**⑦ D-V02 / C-04 / D-01 → Valkey 口令 → 跨分片踢人。** 原链：同机用 `valkey-server --requirepass <口令>` 起 Valkey 时，`profile` 录出的 JFR 带这条命令行（`jdk.SystemProcess`），文件 0644；Redis URL 解析失败的日志也带口令；同机用户连上 Valkey 后向 `synaxis:kick:{shard_id}` 发顶号消息就能按 UUID 反复踢人，向 `synaxis:permission`、`synaxis:moderation` 发消息让各分片反复全量重读。修复后三个泄露源都断：`profile` 录制关掉 `jdk.SystemProcess` 等七个事件、写文件时按同一名单再滤一遍，文件 0600、目录 0700；`RedisLink` 的拒启信息不带原串。文件里仍有 `jdk.ProcessStart`（本 JVM 起的子进程命令行，宿主不起子进程，插件起的会进来），§28.2 堆转储一行写明插件起子进程且命令行带口令时不交出文件。

**⑧ D-03 → A-02 / 封禁失效。** 原链：能写工作目录的同机用户新建 `synaxis.properties`，写 `synaxis.proxy-protocol=true` 与可信网段 `0.0.0.0/0`，下次滚动重启后任何客户端都能用 PROXY 头自报源地址，A-02 的占位从 13～32 个 IP 降到一台机器，按 IP 与网段的封禁（`BanTarget.ByNetwork`）和 L1 / L2 一并失效。修复后起点断了：文件组或他人可写、或所在目录组或他人可写且没有 sticky 位时拒启；A-02 也已修。代码不查的两种（sticky 目录里抢先新建；上级目录与符号链接所指文件的目录）由 §23 检查单挡。

**⑨ D-03 + D-02 → 冒充任意正版账号。** 原链：在同一个文件里写 `synaxis.app.auth.session-server=http://sessionserver.mojang.com/session/minecraft/hasJoined`，`isMojang` 只比主机名，进程仍判为在线模式、能过 `store_meta.auth_mode` 校验、不打 WARN；在分片到 `sessionserver.mojang.com:80` 的出站路径上能应答的人可以对任意用户名回 200，以带 `*` 的 GM 身份登录。修复后两环都断：写文件一环同 ⑧；`isMojang` 改为规范化后逐段比对，http、别的端口、别的路径都算非 Mojang，正式库因此拒启。

**⑩ D-V01 → 伪造转服票据。** 原链：`.env` 里的 `SYNAXIS_TRANSFER_HMAC_KEYS` 可读时，持正版账号的人改掉自己票据的 `target_shard` / `source_shard` 再重签，能进任意频道、出生在任意关卡门的到达点；UUID 仍绑定 `hasJoined` 的结果，nonce 与 `version` 仍一次性，冒充不了别人、复制不了角色。修复后读 `.env` 这一环断了（同 ⑥）。按大区记位置接上之前，这条的额外收益本来就小：直接登录任意频道就能进任何大区（§28.2 待办）。

**⑪ B-V01 / B-02 + 缺失的 `item_ledger` → 回滚复制查不出。** 原链：插件把玩家输入存进数据段时，玩家写入一个 U+0000，或把段撑过 jsonb 口径的 64 KiB，角色就进入「存不进、又不冻结」的状态；经掉落与拾取把物品交给另一个角色后登出，30 s 后记录按 orphan 丢弃，角色回滚，接收方已落库；`item_ledger` 没有写入，`RB-PROD-006` 的抽查查不出。这条链没有断：B-02、B-V01 按用户拍板不修、登记 §28.2，`item_ledger` 是 §5 第 7 行。前提是接入这类插件，目前 `game-rts` 与 `example-plugin` 都不注册数据段，挂地图的分片不加载插件。

## 5. 没覆盖的面

完整性检查列出的、四位审查者都没覆盖的 12 个面。前三个由光照会话单开 M3 侧安全审查覆盖。

| # | 面 | 完整性检查的结论 | 归属 | 去向 |
| --- | --- | --- | --- | --- |
| 1 | 分片模式的移动与方块权威（基线 #25 / G4 在 MMORPG 分片的现状） | 挂地图分片 `core-mode=on`，`server-app` 不设游戏模式，玩家是生存模式；`PlayerPositionListener` 自基线以来没改，只判有限值、坐标上限与目标区块已加载，不判速度与碰撞；`PlayerActionListener` 不校验挖掘时长；只有 `WorldFlagsImpl` 管辖的格子会取消破坏与放置。以下几件都没人看：其余格子的改动是否写进 overlay 并落盘，单个改过的客户端每秒写多少，overlay 有没有磁盘上限，能否逐区块瞬移进任意门框触发转服 | 光照会话 | 光照会话 M3 侧安全审查：已审（2026-10-02），结论见 §9：S1-V01 high、S1-01 medium、S1-02～S1-04 low、S1-05 info；修复设计在 `docs/DESIGN-M3.md` v0.38（M3-S 轨道 S）。其中引擎监听器的三条由生产就绪 authority 组修（`docs/DESIGN-PROD.md` v0.9.47 `API-PROD-030`，`docs/TEST.md` `TC-PROD-630`～`TC-PROD-637`）：S1-V01 跳出地图边界、S1-02 一包走 208 格、S1-01 挖掘不判距离；挖掘时长没修，登记在该分册 §28.2 |
| 2 | 地图根目录与 `shards.json` 的文件信任 | `ShardDirectory` 读 `<maps_root>/<map_id>/shards.json`，里面的 host:port 就是 `TicketIssuer` 发 `Transfer` 的目的地；`ShardWorld` 读地图 manifest，`SynxChunkLoader` 读区块文件。这些文件能被服务用户以外的人写时，可以把全部转服导向攻击者主机，或把畸形地图文件喂给解析器；属主、权限与解析器的畸形边界都没人查（D-03 只看了 `synaxis.properties`） | 光照会话 | 已审（2026-10-02），结论见 §9：S2-01、S2-02、S2-V01 low，S2-03、S2-04、S2-V02 info；修复设计在 `docs/DESIGN-M3.md` v0.38（M3-S 轨道 S 与 C） |
| 3 | `grid` 多 tick 线程下玩家能驱动的竞态（`REQ-PROD-020`，`ShardMain` 默认 `grid`） | 跨格拾取与掉落能否各执行一次、`PlayerStore` 冻结判定与保存状态切换是否在同一线程，没人从刷物品角度看过 | 生产就绪（引擎 `grid`、`PlayerStore`）与光照会话（`ShardGates`） | transfer 组只读核查的结论「没有刷物品路径」只在分片不加载插件时成立（挂地图的分片 `core-mode=on`），写进 §28.2「`grid` 下跨格拾取」一行时没有写出这一前提；查出的「拾取者线程直接移除别的 tick 线程的掉落物」已在集成轮修。评审补查：`ShardGates` 的 `inside` 与 `underWay` 是 `ConcurrentHashMap`，`HostPermissions#read` 在虚拟线程上调的 `refreshCommands()` 只发一个 `DeclareCommands` 包。`ShardGates` 与跨格拾取再由光照会话 M3 侧安全审查（2026-10-02，结论见 §9）覆盖，推翻了上面结论的一部分：冻结排在实例作用域任一档与全局 `LOWEST`～`HIGHEST` 的插件监听器之后，插件在那里按惯例发放能复制物品（S3-V01，low，潜伏）；跨格合并先读邻居的物品数、后占 claim，会吞物品（S3-01，info）。两条由 DESIGN-PROD v0.9.48 修（§28.2「M3 侧安全审查」已决行，`docs/TEST.md` TC-PROD-640～649）：冻结在根节点、先于一切插件监听器判定，每个拾取或丢弃只判一次，冻结在派发中途从别的线程落地时这一次放行到底，不会在插件发放之后再被取消；撤销冻结的取消不支持，登记在 §28.2 风险行。关卡门读心跳占连接池（S3-02 info）归 `docs/DESIGN-M3.md` v0.38 M3-S |
| 4 | 命令面（基线 #26 的「无攻击面」从第 4 波起不再成立） | `CommandParserImpl` 做补全时不查命令条件，`kick` 的补全回调把本分片全部在线用户名发给任何玩家，绕过插件用 Tab 列表设的可见集合（info）；参数在条件判定之前就已解析；命令包不经聊天限频，只受 750 / 7 s 约束 | 生产就绪 | 待排期 |
| 5 | 台账日上限被玩家事件撑满的成本（基线 #15 / #34 的后续） | 审计类事件（`gm_command`、`player_banned`、`save_fenced`、`lease_takeover`）与遥测类事件共用 1 GiB/天；认证后每连接写的几行、每张坏票一行、绕过 L2 之后的写入速率都没人算 | 生产就绪 | A-01 修后 L2 恢复，B-01 修后幽灵不再无限写；要不要给审计事件单独留额度待排期 |
| 6 | PROXY 头在信任判定之前就被解析 | 开 PROXY 时，任何公网对端的第一批字节都进 `ProxyProtocolDecoder`（结果为 PRESENT 之后才查可信网段）；v1 / v2 的截断、超长、TLV 边界没人逐字节审 | 生产就绪 | 待排期 |
| 7 | 物品台账 `item_ledger`（D5）没有实现 | `V2__item_ledger.sql` 建了表，主代码没有任何写入；`RB-PROD-006` 的处置与恢复验证都写着「抽查 `item_ledger`」；B 面「没有刷物品路径」只依赖租约与 `version`，回滚复制发生了也没有事后检测 | 生产就绪 | 待排期。完整性检查的建议：在第一个产生带 uid 物品、或注册 `DataSection` 的插件接入之前实现写入；在那之前把 `RB-PROD-006` 的抽查改为按 `character.version` 与 `EVT:save_fenced` 判断 |
| 8 | 看门狗与优雅停机（`REQ-PROD-014`、`REQ-PROD-008`） | `Watchdog` 发现 tick 卡住 60 s 就 `Runtime.halt`，任何能让 tick 卡住的玩家输入都会放大成分片重启，没人找过这类输入；玩家能否拖延停机、或借停机回滚存档没人看 | 生产就绪 | 待排期 |
| 9 | 服务器列表 ping（`REQ-PROD-037`） | `synaxis.hide-online-players` 默认 false，未认证对端每次 ping 拿到至多 12 个在线用户名；插件经 `ServerListPingEvent` 改写的 MOTD 与样本原样发出；`StatusResponse` 与 `Favicon` 的构造成本没人看；结论多半是 info | 生产就绪 | 待排期 |
| 10 | 日志内容与保留（`REQ-PROD-016`） | 每条连接的 `Login success`、`Kicking`、`Connection closed` 与 `Login timeout` 行带 IP 与用户名，保留 14 天；STDOUT 同步输出、没有上限，进容器日志管道后谁能读没人整理 | 生产就绪 | 文件权限由 §23 的 `UMask=0077` 覆盖（secrets 组）；个人信息与保留期待排期 |
| 11 | 第 5 波的逐玩家成本上限（`REQ-PROD-023`～`027`、`039`、`040`） | 可见实体上限、追踪范围、休眠、区块下发（4 块/tick）、视距上限、Tab 列表批量只有压测证据；反复改视距、伪造 `ChunkBatchReceived` 回执、快速跨区块移动能否绕过上限，没人看 | 生产就绪 | 已处理（2026-10-06）：区块下发由光照会话 M3 侧安全审查 ⑥ 查实，由生产就绪 chunks 组修复（`docs/DESIGN-PROD.md` v0.9.49，`docs/TEST.md` `TC-PROD-650`～`TC-PROD-654`、`TC-PROD-658`、`TC-PROD-659`）。不回执时服务端发完首批就停发，但区块环扫过的区块照样进待发队列、不出队，队列随移动一直增长，之后一次回执的发送在一个 tick 里逐条出队（8 万条 6,643 ms）；现在发送之前先修剪到区块环以内：不回执、按最低速率回执、一个 tick 多个移动包、反复改视距，每个 tick 发送之前的队列都不越过 (2r + 1)²（tick 之内可暂时越过，下一次发送之前修回）。可见实体上限、追踪范围、休眠、视距上限与 Tab 列表这次没有复核 |
| 12 | 数据库账号的实际权限（DESIGN-PROD D11：分片账号只有 DML） | 仓库与 `ops/postgres` 里都没有 GRANT 语句；只有 DML 时口令泄露的后果是改 `account_role`、`character_section`、`store_meta`，给了属主或超级用户时还能 `COPY ... PROGRAM` 在库主机上执行命令（链 ⑤） | 生产就绪 | 待排期 |

## 6. 残余风险

各组 `not_done`、`residual` 与评审非阻塞项里属于风险的条目。

| 来源 | 残余 | 指向 |
| --- | --- | --- |
| A-01 | configuration 与 play 态的监听器异常仍只在 `rejectMisusedPacket` 开时断开（登录前状态已一律断开） | `docs/DESIGN.md` §28 既有一行；DESIGN-PROD §28.3 交主会话一行 |
| A-02 | 认证前的连接仍各耗一次 RSA 解密（103.1 µs）与一次 `hasJoined`，每 IP 受 L2，多 IP 叠加没有进程级上限；满员临界时连接能先做完 RSA 与 `hasJoined` 才被拒；认证前按网段封禁不做，生产处置以机房防火墙为准 | §28.2「认证前的连接仍能消耗 RSA 解密与 `hasJoined`」一行、「按 IP 网段封禁」一行 |
| A-02（评审补） | 已认证对端拿到 `LoginSuccess` 后不发 `LoginAcknowledged`，能占席位到 30 s 登录超时；重复 UUID 在 `LoginAcknowledged` 才判，同一正版账号能并行占多个席位，每 IP 受 L1 = 16 与 L2 约束；GM 可按账号封禁 | §28.2「认证前的连接仍能消耗 RSA 解密与 `hasJoined`」一行 |
| A-03 | 停发窗口按进程计，没有按来源的在途上限；`Retry-After: 0` 的 429 照常退避重试一次；HTTP 日期格式的 `Retry-After` 按 10 s 处理 | §28.2「Mojang 对 `hasJoined` 的服务端限速未公开」一行；`API-PROD-002` |
| A-04 | RUN-057 定 750 包 / 7 s 的基数是否已含 keep-alive 与 ping 没核实 | `API-PROD-012` |
| B-01 | `ShardGates#moved` 加固之前，每 5 s 转一次服的账号在每个分片上仍能按 53 次/s 写 `cooldown` / `not_online` 台账；断开按墙钟 10 s 计，tick 卡住时推到下一个 tick | §28.2「`EVT:transfer_refused` 的 `cooldown` 与 `not_online`」一行（切换条件：出现 `reason=daily_cap` 的 `EVT:ledger_dropped`，或某个 `player_uuid` 一天超过 1,000 行）；「签票后不走的客户端」已决行 |
| B-01（评审补） | `presence:{uuid}` 按账号存、不分分片，断开之前源分片的刷新会把目标分片写的 `{B, online}` 覆盖成 `{A, in_transit}`；修复前就有，主代码不读 presence，目标分片 10 s 内改回 | 只记录 |
| C-02 | 读库与写入不在一个事务里，别处的撤销落在这一次查询往返之内时授予照写；有效授予全都不到期的玩家仍写得出不到期的授予（设计行为，授出的节点由它自己不到期的授予覆盖）；被撤权或到期的 GM 在 30 s 内仍能执行按缓存判定的命令 | §16；§28.2「`gm.role` 的授予判定只看本分片内存」已决行、「`account_role.expires_at` 到期不发通知」Open Question |
| `grid` 跨格拾取 | 拾取者线程直接移除别的 tick 线程的掉落物，与 `API-PROD-016` 不符；开断言或 strict 时掉落物留在世界里、再也拾不起来，claim 挡住了复制 | §28.2「`grid` 下跨格拾取」一行；集成轮已修 |
| C-01（评审补） | 防线依赖反向代理把 `Origin` 原样转给后端；代理配置成删掉 `Origin`（`proxy_set_header Origin "";`）时失效 | `API-PROD-022` 鉴权一条（写明前置反向代理不得删改 `Origin`） |
| C-05 | 上限拿可用性换资源上界：不受信的对端占满 64 条时，Prometheus 抓取与持令牌的运维连接也被关；指标端点到上限时静默关连接、不写日志；`jdk.httpserver.maxConnections` 是 JVM 级属性，插件自建的 `HttpServer` 同样被限到 64，`HostMetrics#serve` 之前若已有代码建过 `HttpServer`，上限不生效 | `API-PROD-015`、`API-PROD-022` |
| C-03 | 只接住原因为 `IOException` 的失败；`server.start` 里别的失败仍以未捕获异常离开 `main`，不跑停机任务，台账丢至多 1 s 的行 | §28.2「游戏端口绑不上时 `server-app` 不退出」已决行 |
| C-V01 | 代码不变：部署前提做不到（裸机或 systemd、同机有别的用户）又开管理接口时，抢端口仍能拿到该分片的令牌 | §23 检查单；§28.2「管理接口不做 TLS」一行（切换做法：绑 1024 以下的回环端口并加 `AmbientCapabilities=CAP_NET_BIND_SERVICE`，前提是 `net.ipv4.ip_unprivileged_port_start` 仍为 1024） |
| D-01 | URL 格式坏掉时 pgjdbc 经 JUL 向 stderr 打完整 URL（systemd 下进 journald）。拒启信息原先只要求编码保留字符，集成轮已改为「除字母、数字和 `-._~` 之外一律百分号编码」 | §28.2「机密与落盘权限修复之后的残留」风险行 ① |
| D-03 | sticky 目录里抢先新建、上级目录、符号链接所指文件的目录不查；Kubernetes 按目录挂载 ConfigMap 时卷根 0777 会被拒，只承诺 subPath | §28.2「机密进日志与落盘权限六处」已决行 ③；§23 落盘权限一行；`API-PROD-023` |
| C-04、D-V02 | `profile` 文件仍有 `jdk.ProcessStart`、`jdk.FileRead` / `jdk.FileWrite`、`jdk.ThreadDump`、玩家 UUID 与其余系统属性；机密 `-D` 只按键名里的 password / secret / token / credential 判，javaagent 参数与 jvmFlags 文件里的值不判；`profile` 显式关掉三个引擎事件期间，别的只靠注解默认开启的录制也录不到它们 | §28.2「堆转储带着进程环境变量的明文」一行；`API-PROD-011` |
| D-V01 | `logs/` 由 logback 先建时，`latest.log` 与滚动 `.gz` 只靠部署单元的 `UMask`；非 POSIX 文件系统上带 POSIX 权限属性建文件抛 `UnsupportedOperationException`，`EventLedger` 写线程只接 `IOException`，会因此结束 | §23 落盘权限一行；§28.2「机密与落盘权限修复之后的残留」风险行 |
| D-05 | Q10 的 30 天窗口 2026-10-13 到期，到期前要再查一次上游是否发版；PR #1509 只修 #1507，升级后 `BoundedDraft` 的两道上限也不能删 | §28.2「Java-WebSocket 1.6.0 的 CVE-2026-90584」已决行 |
| B-02、B-V01 | 没修：插件把玩家输入存进数据段时，玩家能让自己的角色进入「存不进、又不冻结」的状态，配合掉落与拾取做回滚复制（链 ⑪） | §28.2「数据段写不进库时整名角色都存不进」待办行 |
| D-04 | 没修：被投毒的镜像能同时给出改过的 jar 与配套的校验和；`client-fabric` 的构建产出发给玩家的模组 jar，受害者包括玩家机器 | §28.2「依赖与构建链没有完整性锚点」Open Question |
| 经验球（集成评审补） | `Player#update` 对附近经验球派发 `PickupExperienceEvent` 后直接调 `experienceOrb.remove()`，跨格时同样在别的 tick 线程上移除，前面也没有 claim；目前主代码不生成经验球 | §28.2「经验球的拾取同样跨格直接移除」待办行（触发条件：插件或宿主开始生成经验球之前） |

## 7. 交光照会话

| 事项 | 来源 | 内容 |
| --- | --- | --- |
| `ShardGates#moved` 加固（已做：`docs/DESIGN-M3.md` v0.31，`072d45b790`，RUN-065） | B-01（transfer 组） | 合入后在 `underWay.add(player)` 之前判 `PlayerStore#isOnline(Player)`，不在 online 就返回，不读心跳、不写台账；建议构造器加 `Predicate<Player>`，在 `Main` 建 `ShardGates` 处传 `store::isOnline`；补一条 `ShardGatesTest`：非 online 的玩家进出门框 100 次，`ShardService#transfer` 0 次、`transfer_refused` 0 行（修前各 100）。做完把 §28.2 `transfer_refused` 风险行里「由光照会话在 v0.9.40 合入后做」改成已做，按 `docs/TEST.md` §5 加跑 TC-PROD-593 与 TC-PROD-570～573；改用别的判定时告知生产就绪会话，删掉 `isOnline` |
| M3 侧安全审查 | §5 第 1～3 行 | 分片移动与方块权威；地图与 `shards.json` 的文件信任；`grid` 下 `ShardGates` 与跨格拾取 |
| `docs/DESIGN-M3.md` 同步 `ADR-PROD-015` | login 组 | `LoginStart` 只判有没有空位，席位在认证之后、读 Cookie 与用 nonce 之前占，两处拒绝都是 `shard-full`。涉及 `REQ-M3-016`、`REQ-M3-017`，`API-M3-010` 的选频道条（v0.30 第 812 行，原文引 `ConnectionGate#takeSeat` 处理 `LoginStart`）与其后的顺序行（第 876 行），§12 时序图里「LoginStart 时闸门 L3 判席位」一行（第 1319 行），§9 `shard_heartbeat.online`，§12 转服时序，§14 首段，§16「先到 `LoginStart` 先占席位」一行。`ShardHeartbeat` 写的 `gate.seats()` 不再含认证中的连接，代码不用改 |
| `API-M3-010` 签发 ④ 的线程一条（可选） | transfer 组 | v0.30 第 867 行只写了 `in_transit` 冻结，可补一句 v0.9.40 的 10 s 断开 |
| TC-WORLD 用例 | login、ops 组 | TC-WORLD-085 第 1、2 步已由 login 组改写（持席位的连接要答密钥，红 2 → 1）并在分支上重跑；TC-WORLD-100 第 2 步的 `new ShardGates(` 随 ops 组的改动由 `Main.java:504` 移到 `:518`，集成轮已改；TC-WORLD-089 已废弃，没动 |
| `CLAUDE.md` 第 17 行 | ops 组评审 | 「运行时密钥只进 `.env`」「Bitwarden item 名 = 键名」与新的 `.env.<shard_id>`、`<shard_id>/SYNAXIS_MANAGEMENT_TOKEN` 写法不一致，交 `CLAUDE.md` 的负责方 |

## 8. 没验证到的

- TC-PROD-606（C-V01 部署前提的人工核对，含用 `nobody` 抢端口的反证）要在 Linux 部署主机上做，本轮没执行；第 1 步的循环只在本机假目录上试过。
- B-01 不误踢按 `Transfer` 走的客户端：审查时只按读代码判断（它们收到 `Transfer` 当场断开），之后两处都实跑过。TC-PROD-548 由 RUN-062 在集成分支 `45f4133080`（含 B-01 的修复）上照字面在真进程里跑过，八步都与用例相同：`:loadtest` 机器人随 `Transfer` 断开，`minecraft:players` 随后为空，分片日志 WARN / ERROR 0 行。合入 `main` 之后 job `p10` 跑了两次、都通过：2026-10-01 在 `c69548be70` 上手动触发的 run 36836899026，与 2026-10-04 每周定时的 run 37238375047（`14437d0b78`），判定 8 行全是 ok、末行 `P10: all checks passed`。P10 里真 26.2 客户端（生产模组 jar，`Transfer` 由原版代码处理）在两个分片之间来回 100 次（转服 200 次）：run 36836899026 的 `p10.txt` 里相邻两次进世界相隔 5.1～5.8 s（首个间隔除外），`drive.json` 里每次转服从发出到出现在目标分片 0.6～1.1 s，所以客户端回到源分片时离源分片签票不到 7 s，早于 10 s 的断开任务；200 次都到达，客户端交替进了 201 个世界，没有被踢。
- C-03 在存储模式下进程能否以 1 退出没验证；核心模式实测 0.9 s 退出、退出码 1。
- D-03 的 ConfigMap 卷根权限没在 Kubernetes 上核实；只读挂载跳过检查没做，本机造不出带 unix 视图的只读挂载，写不出反证。
- Mojang 对 `hasJoined` 的按 IP 限速没核实，A-03 的危害取决于它；§23 检查单的上线前 1,000 次探测仍待做。
- secrets 组第二轮有一段没拿住重活锁（zsh 函数里设的 EXIT trap 在函数返回时触发）：07、08 两批与 09 默认全量的前约 2 分钟，其间是否有别的会话同时跑重活没核实。评审在锁下复跑了每条 finding 的反证，红行与 `docs/TEST.md` 一致。
- secrets 组严格 `grid` 第一次全量红 1 条：`ConfigFileLaunchTest` 的 `freePort()` 先挑端口、关掉，再交给子 JVM 去绑 `0.0.0.0`，其间端口被占，子 JVM 绑不上，测试等满 120 s。端口竞态是既有问题，本轮没修。
- 审查者自报没审的项：
  - HikariCP 7.1.0、Flyway 13.7.0、Gson 2.14.0 的 CVE 没逐个查（pgjdbc 42.7.13 与 Jedis 8.0.1 查过）；
  - `client-fabric` 侧由 Loom 与 Minecraft 带来的库（Netty、log4j2 等）的 CVE；
  - JitPack 构建与 nmcp 向 Maven Central 的发布；
  - `LoginPluginMessageProcessor` 在线模式下的 `awaitReplies` 超时路径；
  - Java-WebSocket 1.6.0 的 `doBroadcast`、`sendPing` 没逐行读原文。

## 9. M3 侧审查（2026-10-02）

对象是 §5 第 1～3 行的三个面：① 分片模式的移动与方块权威；② 地图根目录、`shards.json` 与地图文件的信任，以及客户端远景包；③ `grid` 多 tick 线程下玩家能驱动的竞态（关卡门与跨格拾取）。做法照本报告：每个面一个审查者、一个对抗复核者，复核者重读代码、补反证测试；严重度与结论以复核为准。审查在 `main` `14437d0b78` 上做，复现测试只在本机分支上，没推到 origin（推远端备份要用户批准）：

| 面 | 分支（worktree） | 提交 | 复现测试 |
| --- | --- | --- | --- |
| ① | `m3/sec-r1`（`synaxis-wt/m3-sec-1`） | `de82c5720a` | `server-app/src/test/java/io/github/bearisbug/synaxis/app/shard/MapShardPlayerAuthorityTest.java`，4 条，在锁下 4 过（过 = 测试描述的现状成立） |
| ② | `m3/sec-r2`（`synaxis-wt/m3-sec-2`） | `a61412e212` | `synx/.../SynxParserBoundaryPocTest.java`（6 条）、`server-app/.../app/shard/ShardDirectoryFileTrustPocTest.java`（3 条）、`protocol/.../FarViewDecoderBoundaryPocTest.java`（3 条）、`client-fabric/.../farview/FarViewDownloaderSizePocTest.java`（1 条），13 条全过 |
| ③ | `m3/sec-r3`（`synaxis-wt/m3-sec-3`） | `7219ce342d`、`7062cfecd2` | 根模块 `entity/GridItemRaceProbeTest.java`；`server-app` 的 `app/store/ShardGatesRaceProbeTest.java`、`PickupFreezeRaceProbeTest.java`、`GateFreezeEarlyGrantProbeTest.java` 与 `app/FreezeBeforePluginListenersProbeTest.java`；`synx/.../OverlayCrossCellWritersProbeTest.java`。严格 `grid` 4 线程各跑 20 次；故意为红的是 S3-01 的一个方法与 S3-V01 的两个探针类，其余绿 |

结果一句话：15 条，复核后 high 1、medium 1、low 7、info 6，没有 critical（confirmed 9、plausible 2、复核新增 4）。用户 2026-10-06 的回复是「全部都修复」，分叉按推荐项：挂地图分片默认不能改地形，S1-V01 定 high，引擎监听器、`PlayerStore`、`ItemEntity` 的修复交生产就绪会话，地图制作者与运维同等可信、不钉清单 sha256，实例作用域撤销冻结取消写明不支持。修复设计在 `docs/DESIGN-M3.md` v0.38（M3-S：`REQ-M3-029`～`REQ-M3-033`，第 0 步与轨道 S、C、G），交给生产就绪会话的在该分册 §28.2 交接行 M3 v0.38。复现测试的去向：断言漏洞行为的 10 条（`m3/sec-r1` 3 条、`m3/sec-r2` 7 条）在修复时翻转为断言修复后的行为、进门禁，`m3/sec-r1` 那 3 条只断言结果：移动的两条靠交给生产就绪会话的引擎判定，挖掘那条有默认不可改地形或引擎的距离判定任一即绿；`m3/sec-r3` 的关卡门竞态（`ShardGatesRaceProbeTest`）与覆盖层跨格写者（`OverlayCrossCellWritersProbeTest`）由 M3-S 改成回归测试，其余四个在生产就绪会话的包里，随它的修复处置（`GridItemRaceProbeTest`、`FreezeBeforePluginListenersProbeTest`、`GateFreezeEarlyGrantProbeTest` 以该会话分支里的版本为准）；三条分支本身不合并。

| 编号 | 面 | 标题 | 严重度（审查 → 复核） | 结论 | 处置 | 归属 | 复现测试 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| S1-V01 | ① | 玩家移动不受世界边界约束，越过地图边界后实例不断新建空气区块，载入快过卸载，单个客户端能让分片堆一直涨 | 判为不成立 → high | 复核新增，测试复现：200 tick 内玩家到 x = 41,720（边界 256），载入 70,929 个、卸载 25,856 个，常驻区块 729 → 45,073，堆 +142 MB（夹具 4 个 section，约 3.2 KB/区块；4 GiB 堆约 5 分钟耗尽是推算，OOM 没跑到） | 修：引擎的位置判定（由界内走到界外、人在界外时离边界更远与单包三维距离超过 64 格的位置包拉回，对所有实例生效，挂地图分片按 `bounds` 设世界边界，也由它覆盖）、核心模式平坦实例的有限世界边界、载具同判交生产就绪会话；地图分片对 `view_bounds` 外新建的区块计数告警（`REQ-M3-029`）。残余：`bounds` 内每包 64 格仍合法，单个客户端能让常驻区块涨满 `view_bounds`（4k 大区 84,100 个），对玩家开放的前提是它乘每区块常驻字节不超过堆的 25%，M3-S 实测 | 生产就绪会话（引擎）；光照会话（计数与残余测量） | `MapShardPlayerAuthorityTest#jumpsPastTheBoundsAreTurnedBackAndLoadNoAirChunk`（翻转为越界被拉回、`void_chunks` 为 0）、`ShardInstanceTest`、测量程序 `ShardSweepProbe`；`docs/TEST.md` `TC-WORLD-125`、`TC-WORLD-127`、`TC-WORLD-128` |
| S1-01 | ① | 生存玩家能远程、瞬时破坏任意已载入可写区块里的方块，改动进覆盖层、跨重启保留，没有审计 | medium → medium | confirmed，测试复现：一个 `FINISHED_DIGGING` 打掉 293.45 格外的石头，重启后仍是空气。更正：放置侧现在不成立（生存放置要消耗物品）；管辖格保护只在目录模式里装上 | 修：`ShardMain` 挂地图时默认 `synaxis.world-writable=false`（`REQ-M3-029`）；挖掘与放置的距离判定交生产就绪会话，挖掘时长校验在打开改地形之前另交 | 光照会话（默认值）；生产就绪会话（距离判定） | `#aDigFarAwayIsTurnedBackAndTheOverlayStaysClean`（翻转为默认被回正、不进覆盖层）、`ShardMainTest` 的改地形默认值；`TC-WORLD-125`、`TC-WORLD-126` |
| S1-02 | ① | 改过的客户端能逐区块瞬移，包括一包跨进关卡门框触发跨大区转服 | medium（可能 high）→ low | confirmed，测试复现：一个位置包移动 208 格落进门框、发起 `transfer("r1")`。改判理由：门框是公开地点、走路也能到，没有任务锁。更正：载入在虚拟线程上当场完成，实际是每 tick 跳 13 个区块，约 4,160 格/s | 修：引擎的单包位移上界交生产就绪会话，超过 64 格拉回，拉回不派发 `PlayerMoveEvent`，关卡门只看放行的移动（`REQ-M3-029`）。残余：不判速度与碰撞，登记为接受的风险，地形或关卡路线开始承载进度门槛之前再判 | 生产就绪会话 | `#onePositionPacketThirteenChunksIntoAGateIsTurnedBack`（翻转为被拉回、`transfer` 0 次）；`TC-WORLD-125` |
| S1-03 | ① | 覆盖层没有磁盘上限 | low → low | plausible，数字改过：记录随改动变大（4 个 section 的区块打掉 0～10,000 格，673～4,512 B）；4k 大区每区块改一次约 0.27 GB、改到饱和约 3.5 GB（按 24 个 section 外推）；漏掉的一档：非目录模式挂整张正式档地图约 17.6 GB | 修：`synaxis.overlay.max-bytes`，默认 4 GiB，满了按不可改地形处理（`REQ-M3-030`） | 光照会话 | `#theOverlayRecordOfAChunkGrowsWithEveryCellBroken`，原样保留（量记录大小）；磁盘上限是 `OverlayStoreTest`、`SynxOverlayCombinedIntegrationTest`、`OverlayOptionsTest` 的新用例，`TC-WORLD-129`、`TC-WORLD-130` |
| S1-04 | ① | 单个客户端能让光照重算池持续满载，光照装回延迟拉长 | low → low | plausible：重算合并、装回队列 256 个目标的背压、停机按时限完成都成立；积压期间 3×3 不卸载、不淘汰，覆盖层常驻可越过 16,384；重算吞吐没测 | 不改代码；M3-S 实测重算池在 107 个中心/s 下的吞吐，写进 `docs/DESIGN-M3.md` §15 | 光照会话 | `SynxRelightBacklogTest` 的测量模式（不进门禁）；`TC-WORLD-139` |
| S1-05 | ① | 实体与物品在挂地图分片里不会堆积 | info → info | confirmed：主代码没有 `new ItemEntity` 与 `new ExperienceOrb`，`Player#dropItem` 只派发事件 | 只登记；失效条件：生成掉落物的插件接入且分片打开改地形 | 光照会话 | — |
| S2-01 | ② | 地图根目录下的文件不查属主与权限；区域文件只在起服时校验一次 | low → low | confirmed，测试复现读取一环：0666 的 `shards.json` 放在 0777 目录里照常读，地址 `192.0.2.66:25999`。补充：`shards.json` 同机共用，滚动重启之后同机全部频道被导向；校验与载入分两次按路径打开，整体换文件也能绕过；被导向的目的地还能下发远景宣告，经它触达 S2-02 | 修：起服按真实路径查属主与权限位，覆盖目录以 0700 建并查同一条权限规则，清单只读一次，校验、载入与出生点用同一份映射（`REQ-M3-031`）；部署规范：属 root 或部署用户、0755 / 0644、已挂载的版本目录不原地覆盖 | 光照会话 | `ShardDirectoryFileTrustTest#aShardsJsonAnyoneMayWriteIsRefused`（翻转为拒启）、`ShardWorldFileTrustTest`、`MapFileTrustTest`、`SynxBuildVerifierTest` 的同一份映射；`TC-WORLD-131`～`TC-WORLD-133` |
| S2-02 | ② | 客户端远景下载对宣告的对象大小没有上限；超过 2 GiB 的 `.part` 触发 OOM、绕过异常处理、永不清理 | low → low | confirmed，测试复现：3 GiB 稀疏 `.part`，`success=false`、`failed=null`、`reason=OutOfMemoryError`、`.part` 留下、`clean()` 不清。补前提：只影响装了 DH 3.3.2 且远景打开的玩家，经 S2-01 也能触达；补影响：残留超过 1 GiB 之后每次烘焙成功都删掉当前视图之外的全部库 | 修：单个对象 ≤ 40 MiB（两端共用 `:protocol`）、宣告合计 ≤ 1 GiB、边写边算摘要、`object()` 接住 `RuntimeException`（`REQ-M3-032`） | 光照会话 | `FarViewDownloaderLimitsTest`（原 `FarViewDownloaderSizePocTest`）与 `FarViewDecoderBoundaryTest#anAnnouncedObjectSizeIsHeldTo40MiB`，都翻转；`TC-WORLD-136`、`TC-WORLD-137` |
| S2-V01 | ② | 下载被取消时特意留下 `.part`，清理只删 `*.hfp` | — → low | 复核新增，只按代码（`FarViewDownloader.java:113`、`:202`、`:255`、`:264`，`FarViewCache.java:349`） | 修：收到宣告时删宣告没点名的与存在超过 24 h 的 `.part`，客户端启动时删存在超过 24 h 的，`clean()` 也删（`REQ-M3-032`） | 光照会话 | `FarViewClientTest#partsNotNamedOrOlderThanADayGoWhenAnAnnouncementArrives`、`FarViewCacheTest` 的三条；`TC-WORLD-137` |
| S2-03 | ② | `.synx` 解析器的长度与分配没有上限（纵深防御） | info → info | confirmed，测试复现：14 B 的帧声明 64 MiB 时先分配 67,241,640 B；段③ 16,316 B 解出 16,777,216 B；负长度抛 `NegativeArraySizeException`；清单同名键取后一个。更正：`ShardWorld.java:216` 读超过 2 GiB 的包是 Java 代码主动抛的 OOM，不触发 `ExitOnOutOfMemoryError` | 修：段① 帧长 ≤ 2,097,151 B、未压缩 0～8,388,608 B，段③ 解压 ≤ 16 MiB、每条 NBT 计量 ≤ 16 MiB，清单 ≤ 16 MiB 且严格 JSON 拒同名键，`shards.json` ≤ 1 MiB，读远景包前比大小（`REQ-M3-031`） | 光照会话 | `SynxParserBoundaryTest`（原 `SynxParserBoundaryPocTest`，4 条翻转、2 条原样、另加 1 条）、`SynxBlockEntityLimitsTest`、`SynxTruncatedMappingTest`；`TC-WORLD-133`、`TC-WORLD-134` |
| S2-04 | ② | CDN 被换包时完整性守得住，剩可用性与重定向 | info → info | confirmed，改一处：`URI.create` 在 `try` 之外，非 http(s) 的 URL 走 `failed = null` 的分支、日志是 INFO；`Redirect.NORMAL` 不会由 https 跳到 http；其余属实（`url-root` 接受 `http://`，只有 30 s 空闲超时、没有总时限） | 修：URL 解析进 `try`、当场失败；3xx 当场失败、不跟随重定向；总时限 10 min；宣告各项 URL 同主机，主机解析出本地地址而服务器不在本地时不下载；非回环的 http `url-root` 起服记 WARN，部署规范要求 https（`REQ-M3-032`） | 光照会话 | `FarViewDownloaderTest` 的四条、`FarViewClientTest` 的主机两条、`ShardWorldFarViewTest` 的 url-root WARN；`TC-WORLD-135`、`TC-WORLD-137` |
| S2-V02 | ② | 起服校验不覆盖 16,384 B 的区域索引 | — → info | 复核新增，只按代码：`payload_sha256` 从 16,640 起算；`SynxBuildVerifier.java:15` 的「byte for byte」不准 | 修类注释；不钉清单 sha256 时校验不改，改钉时清单加 `index_sha256` | 光照会话 | — |
| S3-01 | ③ | `grid` 下跨格合并掉落物读到邻居的旧数量，第三堆被吞 | low → info | confirmed，测试复现 20/20（70 颗变 50 颗）；只吞不复制，当前部署走不到 | 交生产就绪会话，随它的 RUN-070：先占两把 claim 再读 | 生产就绪会话 | `GridItemRaceTest#aMergeAcrossACellBorderKeepsEveryItem`，修后转绿 |
| S3-02 | ③ | 每走进一次门框读一次心跳，约 75 个账号来回走能占满存储的 4 连接池 | info → info | confirmed，只按代码：每人至多 53 次/s；单次读 1 ms 是假设；会话续期用独立连接，不会连带进 DEGRADED | 修：同一目标大区的心跳读 1 s 内复用（`REQ-M3-033`），台账写法照旧（复用之后拒绝当场完成，每人至多 53 行/s），不改 `TransferTickets` | 光照会话 | `ShardServiceImplTest` 的心跳读复用三条，`ShardGatesRaceTest`（原 `ShardGatesRaceProbeTest`）；`TC-WORLD-138` |
| S3-V01 | ③ | 冻结只在宿主节点下优先级 3 取消拾取与丢弃；实例作用域的插件与全局 `LOWEST`～`HIGHEST` 先看到未取消的事件，在那里发放物品就复制 | — → low（潜伏） | 复核新增，测试复现 20/20（5 颗变 10 颗）；全局 `MONITOR` 的对照看到 `cancelled=true` | 交生产就绪会话，随它的 RUN-070：根节点自身的监听器再挂一份冻结取消，改 `PluginEvents`、`PlayerDataStore` 两处 javadoc 与 `API-PROD-005`；实例作用域撤销冻结取消写明不支持 | 生产就绪会话 | `FreezeBeforePluginListenersIntegrationTest`、`GateFreezeEarlyGrantTest`，修后转绿 |

复核确认安全的四项（审查者判为受控、复核抽查成立）：

| 面 | 项 | 依据 |
| --- | --- | --- |
| ① | 停机预算拖不垮：玩家造出再多脏区块，停机也按时限结束，超时的改动丢弃、记 ERROR | `OverlayStore#finalFlush` 上限 min(2 s, 剩余预算)、到点由 `timedOut` 完成；`SynxRelight#drain` 上限 500 ms |
| ② | 解析器与缓存路径受控：`shards.json` 严格 JSON、同名键与未知字段都拒、端口、容量与频道号有范围；`FarViewPack.Reader` 20,000 次变异里 19,375 次拒为 `Invalid`、0 次未受控；远景宣告解码 20,000 次里 7,881 次拒、0 次未受控；缓存路径只用本地算出的十六进制名，宣告字段不进路径；`build_id` 不越出目录（措辞更正：只拒含 `/`、`\` 或整个等于 `.`、`..` 的值，`a.b` 照样放行） | `ShardDirectory.java:208-264`、`:275-285`、`:492-505`；`FarViewPack.java:494-497`、`:524-539`、`:547-575`、`:660`；`MsgBuf.java:202-208`、`:244-251`；`FarViewCache.java:65-87`、`:100-110`；`ShardSettings.java:63` |
| ③ | 关卡门：同时触发不会签两张票（重跑 60/60，`PlayerStore#transferOut` 在 `synchronized` 里只放行一次 `ONLINE→SAVING`）；`underWay` 不会永久卡住（心跳读 2 s 超时、断开时回 `NOT_ONLINE`、登出由 `loggedOut` 补完；隐患：`fence()` 不完成 `transferAsked`，靠调度器先进先出让快照任务先跑）；两条 tick 线程边界上来回进出门框安全 | `PlayerStore.java:737-741`、`:945-959`、`:1015-1024`；`TicketIssuer.java:98-104`；`ShardServiceImpl.java:97`；`PlayerConnection.java:273-274` |
| ③ | 拾取与覆盖层：两名拾取者在不同 tick 线程同时拾取只拾一次（20/20，claim 的 CAS）；覆盖层与 relight 的跨格写者 20 次比对差异为 0（`UNSAFE_setBlock` 持实例监视器与区块写锁，装回与写入阶段都在 `TICK_END`） | `LivingEntity.java:237`；`ItemEntity.java:193-196`；`InstanceContainer.java:151-218`；`ShardWorld.java:470-477`、`:500-507` |

复核推翻或更正的受控判断：「把自己移进未加载区块不构成载入放大」被推翻，改记为 S1-V01；「非 http(s) 的 URL 按 Failed 处理」不成立，并进 S2-04；「拾取与冻结交错不丢不复制」只在插件用全局 `MONITOR` 发放时成立，改记为 S3-V01；「`ShardGates` 不看 `isCancelled`」要更正：关卡门挂在实例节点上、早于全部插件监听器执行，加一句 `isCancelled()` 也看不到之后的取消。交给生产就绪会话的引擎位置判定拉回越界与单包超过 64 格的移动时不派发 `PlayerMoveEvent`，关卡门收不到这些移动（`docs/DESIGN-M3.md` `REQ-M3-029`）。

没验证到的：S1-V01 的 OOM 本身没跑到，3.2 KB/区块是 4 个 section 的夹具量的；S1-03 的饱和量是外推；S1-04 的重算吞吐与 S3-02 的单次读耗时没测；S2-01 的截断只在 macOS 的 JDK 25 上测过（M3-S 设计的安全审查 2026-10-06：读截断之后的映射抛 `InternalError`、进程不退出，原先「JVM 中止」的推断不成立），Linux 没测；客户端 1～2 GiB 的堆 OOM 只按代码推断；750 包 / 7 s 超限时丢包还是踢人没实测；`m3/sec-r3` 的转服链路用 `MemoryBackend`，PostgreSQL 的租约与 fencing 没在竞态下实测。用例号在 M3-S 合入时补进本节（`docs/TEST.md`，RUN-069）。
