# game-engine 测试文档 · 里程碑 M1「引擎独立化」

## 1. 元信息 / 变更记录

| 字段 | 值 |
| --- | --- |
| 状态 | 草稿 |
| Owner | Bearisbug |
| 关联设计文档 | `docs/DESIGN.md` |
| 被测系统 | 仓库 `Bearisbug/game-engine`，分支 `main` |
| 最后更新 | 2026-09-09 |

变更记录（登用例增改，不登执行轮次）：

| 日期 | 改动 | 作者 |
| --- | --- | --- |
| 2026-09-09 | 初稿，TC-001～TC-018，覆盖 REQ-001～REQ-012 | Bearisbug |
| 2026-09-09 | TC-007 第 4 步增加 `snapshot` 目录检查（设计文档 v0.2） | Bearisbug |
| 2026-09-09 | TC-001：前置与第 3 步按实际数据修正为 74 个 JSON、路径 `src/main/resources/data`、含子目录故改用 `find`（设计文档 v0.2.1）；第 4 步补上镜像 init 脚本 | Bearisbug |
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

- 覆盖：里程碑 M1 的 12 条需求的黑盒与半黑盒验收——构建产物与仓库元数据（命令、退出码、文件）、Minecraft 协议行为（裸 socket 与原版客户端可观察的包与断线原因）、文档与合规文件。
- 不测：

| 不测项 | 归属 |
| --- | --- |
| 单元 / 集成自动化（2,659 个 JUnit 用例、jcstress 场景、ArchUnit 规则本身） | 代码仓测试套件，`./gradlew check` 与 CI，策略见设计文档 §19 |
| 50 连接压测（设计文档 §15 tick 预算与入站包目标） | 阶段 3 结束前专项脚本，当前无归属——已知风险 |
| 安全渗透（畸形包模糊测试之外的攻击面） | 无归属——已知风险 |
| Fabric 客户端模组 | 里程碑 M2 |
| 设计文档 §19 中「CI 门禁拦截并发回归 / 分层违规」两个场景 | 由 TC-005、TC-006 以「故意提交坏改动到临时分支」方式可操作化，见 §4 |

## 3. 环境与前置

- 启动：`export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home && ./gradlew :smoke:run --no-daemon --console=plain --init-script gradle/init/aliyun-mirror.init.gradle.kts`（`smoke` 模块随 `REQ-008` 提前落地，`demo` 仍在仓内，阶段 2 才删除）。默认绑定 `0.0.0.0:25565`，端口用 `-Dsmoke.port=<port>` 覆盖。TC-004 与 TC-018 要往服务端 stdin 写 `stop`，因此运行期间必须保持 stdin 打开（`:smoke:run` 已把 Gradle 进程的 stdin 转给应用；后台运行时用 FIFO 喂入）。
- 就绪判定：stdout 出现 `game-engine smoke listening on 0.0.0.0:25565`，且 `nc -z 127.0.0.1 25565` 退出码为 0。
- 日志关联：与连接相关的服务端日志行统一带 `connection_id=<uuid>` —— `Login success: username=… connection_id=… remote_address=…`、`Kicking connection_id=… identifier=… reason=engine:…`、`Connection closed: connection_id=… identifier=…`，`smoke` 的 echo 行为 `Echo: connection_id=… channel=game:main bytes=…`。
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
| `premature-finish` | — | 在服务端发出 FinishConfiguration 之前抢跑应答 | 收到 `Disconnect reason=engine:protocol-error` |
| `bad-version` | `--protocol N`（必填） | 用非 776 的协议版本握手 | 收到 `LoginDisconnect reason=engine:unsupported-version` |
| `zero-frame` | — | 进入 play 后发单字节 `0x00` | 收到 `Disconnect reason=engine:protocol-error` |
| `deep-recursion` | `--depth N`（默认 200） | 创造模式设槽包携带 N 层自嵌套 `CustomPotionEffect.Settings` | 收到 `Disconnect reason=engine:protocol-error`，或连接被服务端关闭 |
| `echo` | `--payload TEXT`（必填） | 走 `game:main` 通道做一次自定义负载往返 | 回发负载与发出的完全一致 |
| `record-registry` | `--out FILE`（必填） | 按收到顺序写出全部 `RegistryData` 包的解压后字节（含包 ID varint） | 至少收到一个 `RegistryData` 包 |

  探针每次启动时从 `network/packet/PacketVanilla.java` 的注册表顺序推导包 ID、从 `component/DataComponents.java` 的注册顺序推导数据组件 ID，不写死协议常量；注册表顺序变化时探针自动跟随。stdout 逐条打印收到的包名、断线原因键与末行 `exit code: <n>`。

- 种子数据与重置：无持久数据；每条用例前重启进程即为基线。`RegistryDataPacket` 参考样本 `src/test/resources/protocol/registry-data-26.2.bin` 在阶段 2 开始前由 `tools/protocol-probe/probe.py record-registry --out <file>` 录制（同一进程连续两次录制字节完全一致，已实测）。
- 工具：`git`、`gh`（已登录 Bearisbug）、`nc`、Python 3.10+（探针）、原版 26.2 客户端、`grep`。
- 证据目录：`docs/test-runs/`（按 `RUN-*` 分子目录，文中引用以项目根为基准的相对路径）。

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

前置：本地仓库位于 `game-engine/`。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `git remote -v` | 只有 `origin`，两行均为 `https://github.com/Bearisbug/game-engine.git` |
| 2 | `gh repo view Bearisbug/game-engine --json isFork --jq .isFork` | 输出 `false` |
| 3 | `gh repo view Bearisbug/Minestom 2>&1` | stderr 含 `Could not resolve to a Repository`，退出码非 0 |
| 4 | `git log --format=%h \| grep -c 16842f1954` | 输出 `1` |
| 5 | `git branch --show-current` | 输出 `main` |

后置：无。

#### `TC-003` 止血基线全量测试 — 对应 `REQ-003` · 级别: 冒烟 · 执行者: AI

前置：`main` 分支，JDK 25。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `./gradlew :test --continue --rerun-tasks --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | stdout 含 `BUILD SUCCESSFUL` |
| 2 | 统计 `build/test-results/test/*.xml` 的 `failures` 与 `errors` 属性之和 | 为 `0`，`tests` 之和 ≥ 2,659 |

后置：`rm -rf $TMPDIR/minestom-test-world-*`（阶段 0 完成 REQ-012 后不再需要）。

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
| 3 | 删除远端与本地临时分支 | `gh api repos/Bearisbug/game-engine/branches/tmp/ci-jcstress-probe` 返回 404 |

后置：确认 `main` 未被改动。

#### `TC-021` CI 门禁拦截并发回归 — 对应 `REQ-004` · 级别: 边缘 · 执行者: AI

前置：CI 含 `check` 与 jcstress 两个工作流，工作流在 PR 上触发；本地有推送权限。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 新建分支 `tmp/ci-jcstress-probe`，把 `ThreadDispatcherImpl.shutdown()` 改回 `synchronized`，提交推送，并为该分支开一个草稿 PR | PR 创建成功，两个工作流开始运行 |
| 2 | 等待该分支的两个工作流完成 | 至少一个工作流结论为 `failure`；失败日志含并发回归测试名（如 `ThreadDispatcherTest > shutdownFromTickThread() FAILED`）或 jcstress 的 `FORBIDDEN` |
| 3 | 关闭 PR 并删除远端与本地临时分支 | `gh api repos/Bearisbug/game-engine/branches/tmp/ci-jcstress-probe` 返回 404 |

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
| 4 | `ls src/main/java/dev/gameengine/entity/pathfinding src/main/java/dev/gameengine/entity/ai src/main/java/dev/gameengine/snapshot 2>&1` | 三个目录均 `No such file or directory` |
| 5 | `ls src/main/java/dev/gameengine/entity/metadata/` 与 `ls src/main/java/dev/gameengine/entity/metadata/other/` | 前者只含根基类文件、`package-info.java` 与 `avatar`、`display`、`item`、`projectile`、`other` 五个子目录；后者只含 `ArmorStandMeta.java`、`InteractionMeta.java`、`package-info.java` |
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
| 4 | `ls smoke/src/main/java` | 存在至少一个含 `main` 方法的类 |
| 5 | `./gradlew check --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | `BUILD SUCCESSFUL` |

后置：无。

### 命名与合规

#### `TC-010` 命名空间改名完整 — 对应 `REQ-009` · 级别: 冒烟 · 执行者: AI

前置：阶段 3 改名提交已合入。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `grep -rn "net\.minestom" --include='*.java' --include='*.kts' --include='*.toml' . \| grep -v "net\.minestom\.data" \| wc -l` | 输出 `0` |
| 2 | `cat .git-blame-ignore-revs` | 含改名提交的完整 SHA |
| 3 | `git config blame.ignoreRevsFile` | 输出 `.git-blame-ignore-revs` |
| 4 | `git blame -L 1,5 src/main/java/dev/gameengine/ServerProcess.java`（包名以设计文档 §28 拍板为准） | 5 行中无一行指向改名提交 |
| 5 | `ls src/main/resources/META-INF/native-image/` | 目录名已改为新坐标，不含 `net.minestom` |

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

前置：进程已就绪；原版 26.2 客户端已安装；客户端开启 F3 调试界面。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 多人游戏直连 `127.0.0.1` | 5 秒内进入世界，看到平坦地面，F3 显示服务端品牌为我们的名称 |
| 2 | 在聊天框输入 `/echo ping`（`smoke` 启动器把该命令转成 `game:main` echo 负载） | 1 秒内聊天框出现 `echo: ping` |
| 3 | 查看服务端 stdout | 含一行带 `connection_id` 的登录日志和一行 echo 日志，两者 `connection_id` 相同 |
| 4 | 客户端断开 | 服务端 stdout 出现同一 `connection_id` 的断开日志，进程继续运行 |

后置：无。

#### `TC-013` 抢跑 FinishConfiguration 被拒绝 — 对应 `REQ-011` · 级别: 回归 · 执行者: AI

**已废弃，被 `TC-019` 取代**（RUN-004 暴露第 2 步预期「每秒收到 KeepAlive」与引擎事实不符：`KEEP_ALIVE_DELAY` 为 10 秒，设计文档未规定间隔；预期改为「往返不中断」属语义变更，故换 ID）。


前置：进程已就绪；同时用探针 `login` 保持一个正常玩家在线。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `python3 tools/protocol-probe/probe.py premature-finish` | stdout 含 `Disconnect` 且原因键为 `engine:protocol-error` |
| 2 | 观察正常玩家的探针连续 3 秒的 KeepAlive 往返 | 每秒收到 KeepAlive，无中断 |
| 3 | 查看服务端 stdout | 含该连接 `connection_id` 的 `protocol-error` 日志，无异常堆栈 |

后置：无。

#### `TC-019` 抢跑 FinishConfiguration 被拒绝，其他玩家不受影响 — 对应 `REQ-011` · 级别: 回归 · 执行者: AI

前置：进程已就绪；同时用探针 `--username holder login --hold 14` 保持一个正常玩家在线。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `python3 tools/protocol-probe/probe.py premature-finish` | stdout 含 `Disconnect reason=engine:protocol-error`，退出码 0 |
| 2 | 等 holder 探针的 14 秒保持期结束，查看其 stdout | 至少一次 `recv KeepAlivePacket` 与对应的 `sent KeepAlive`，无 `unexpected disconnect`，`exit code: 0` |
| 3 | 查看服务端 stdout | 含抢跑连接 `connection_id` 的 `reason=engine:protocol-error` 日志，无异常堆栈 |

后置：无。

#### `TC-014` 非法协议版本被拒绝 — 对应 `REQ-011` · 级别: 回归 · 执行者: AI

前置：进程已就绪。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `python3 tools/protocol-probe/probe.py bad-version --protocol 775` | stdout 含 `LoginDisconnect` 且原因键为 `engine:unsupported-version` |
| 2 | `python3 tools/protocol-probe/probe.py bad-version --protocol 777` | 同上 |
| 3 | `python3 tools/protocol-probe/probe.py login --protocol 776` | stdout 含 `LoginSuccess` |

后置：无。

#### `TC-017` 零长帧与超深递归被断线 — 对应 `REQ-011` · 级别: 边缘 · 执行者: AI

前置：进程已就绪。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `python3 tools/protocol-probe/probe.py zero-frame`（登录后发送单字节 `0x00`） | 连接被服务端关闭，stdout 含 `engine:protocol-error` |
| 2 | `python3 tools/protocol-probe/probe.py deep-recursion --depth 200`（创造模式设槽包携带 200 层自嵌套药水效果） | 连接被关闭，服务端 stdout 无 `StackOverflowError` |
| 3 | 探针 `login` 再连一次 | `LoginSuccess`，进程仍在运行 |

后置：无。

#### `TC-018` stop 在 10 秒内退出 — 对应 `REQ-011` · 级别: 回归 · 执行者: AI

前置：进程已就绪，探针 `login` 保持 3 个连接。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | 记录时间，向服务端 stdin 输入 `stop` | 三个探针均收到原因键 `engine:server-stopping` 的 Disconnect |
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
| 4 | `ls src/main/java/dev/gameengine/utils/` | 只剩通用工具类，不含 `chunk`、`block`、`entity`、`inventory`、`time`、`identity`、`location`、`position`、`callback`、`crypto` 子目录 |
| 5 | 在 `coordinate` 包任一类临时加 `import dev.gameengine.entity.Entity;` 并引用，重跑第 3 步，然后撤销 | 第 3 步失败，日志含 `ArchitectureTest`；撤销后 `git status` 干净 |

后置：无。

#### `TC-025` 配置对象取代全局开关 — 对应 `REQ-014` · 级别: 回归 · 执行者: AI

前置：`main` 分支，REQ-014 已合入。

| # | 操作 | 预期 |
| --- | --- | --- |
| 1 | `grep -rn "ServerFlag" src testing smoke jmh-benchmarks jcstress-tests --include='*.java' --include='*.kts' \| wc -l` | 输出 `0` |
| 2 | `grep -n "minestom\." build-src/src/main/kotlin/*.gradle.kts \| grep -i "jvmArgs\|systemProperty"` | 退出码 1，无输出（测试 JVM 不再注入与生产不同的开关） |
| 3 | `./gradlew :test --tests '*ServerConfigTest*' --tests '*ServerProcessDispatcherTest*' --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts` | `BUILD SUCCESSFUL`：默认值与系统属性读取一致；两个进程分别按 1 与 2 个 tick 线程分区 |
| 4 | 启动 `smoke`，查看启动日志 | 含一行生效配置（`ServerConfig[...]`）与一行 `JvmFlags[...]` |

后置：`stop` 关闭 `smoke`。

## 5. 回归策略

- **全量轮**（全部用例）：首次成稿、每个阶段 gate（0、2、3）、跨功能域改动、地基变更（JDK、Gradle、镜像、协议版本）。
- **局部轮**（受影响用例 + 全部冒烟级）：单一需求改动。受影响用例 = 本次改动触及的 `REQ-*` 所对应的 TC；无 REQ 变更的改动（纯 bug 修、重构）按触及的功能域圈定：构建与仓库 → TC-001、TC-002、TC-003、TC-004、TC-006、TC-021，删除与注册表 → TC-008、TC-009、TC-020，命名与合规 → TC-010～011，协议 → TC-012、TC-014、TC-017、TC-018、TC-019，卫生 → TC-015～016，门面与配置 → TC-023、TC-025，分层 → TC-024；影响面不确定时升级为全量轮。
- **复测轮**（上轮失败用例 + 全部冒烟级）：修复失败项之后。
- **人工收口轮**（仅上轮「待人工」用例）：AI 轮之后由人工补测 TC-012、TC-016，不要求冒烟级。

## 6. 执行记录

轮次汇总：

| 轮次 | 日期 | 被测版本 | 执行者 | 范围 | 结果 |
| --- | --- | --- | --- | --- | --- |
| RUN-001 | 2026-09-09 | `c77b723235` | AI（Claude Code，Gradle 9.7.1 / JDK 25.0.4.1） | 局部（REQ-003→TC-003；冒烟级 TC-001、TC-002、TC-007、TC-010、TC-012 因阶段 0～3 未开始而跳过） | 通过 1/6 · 跳过 5 |
| RUN-002 | 2026-09-09 | `7d7e956ada` | AI（Claude Code，gh 2.x / git） | 局部（REQ-002→TC-002；冒烟级 TC-001、TC-003、TC-007、TC-010、TC-012 按 RUN-001 同理跳过） | 通过 1/6 · 跳过 5 |
| RUN-003 | 2026-09-09 | `6f7c627da8` + 分支 `freeze-data` 的 REQ-001 / REQ-012 改动（Gradle 9.7.1 / JDK 25） | AI（Claude Code） | 局部（REQ-001→TC-001，REQ-012→TC-015、TC-016；冒烟级 TC-002、TC-003、TC-007、TC-010、TC-012） | 通过 2/8 · 未通过 1 · 跳过 4 · 待人工 1 |
| RUN-004 | 2026-09-09 | `e6b8dcaa36`（阶段 1 全部合入 `main`） | AI（Claude Code，Gradle 9.7.1 / JDK 25 / 探针） | 全量（阶段 1 gate） | 通过 7/18 · 失败 1 · 待人工 2 · 跳过 8 |
| RUN-005 | 2026-09-09 | `e6b8dcaa36`（代码与 RUN-004 相同，仅文档变更） | AI（Claude Code，探针） | 复测（RUN-004：TC-019 + 冒烟级 TC-001、TC-002、TC-003、TC-007、TC-010、TC-012） | 通过 2/7 · 跳过 4 · 待人工 1 |
| RUN-006 | 2026-09-09 | `2cbd57ae85`（阶段 2 全部合入） | AI（Claude Code，Gradle 9.7.1 / JDK 25 / 探针） | 全量（阶段 2 gate） | 通过 12/18 · 待人工 2 · 跳过 4 |
| RUN-007 | 2026-09-09 | `a16b4bb928`（阶段 3 合入，M1 代码完成） | AI（Claude Code，Gradle / 探针 / GitHub Actions） | 全量（M1 gate） | 通过 15/18 · 失败 1 · 待人工 2 |
| RUN-008 | 2026-09-09 | `15616dede0`（代码与 RUN-007 相同，仅文档变更） | AI（Claude Code，GitHub Actions） | 复测（RUN-007：TC-021 + 冒烟级 TC-001、TC-002、TC-003、TC-010、TC-012、TC-020） | 通过 1/7 · 跳过 5 · 待人工 1 |
| RUN-009 | 2026-09-09 | `6dc0003213`（REQ-013 合入） | AI（Claude Code，Gradle / 探针） | 局部（REQ-013→TC-022 + 冒烟级 TC-001、TC-002、TC-003、TC-010、TC-012、TC-020） | 通过 1/7 · 失败 1 · 跳过 4 · 待人工 1 |
| RUN-010 | 2026-09-09 | `6dc0003213`（代码与 RUN-009 相同） | AI（Claude Code，Gradle / 探针） | 复测（RUN-009：TC-023 + 冒烟级 TC-001、TC-002、TC-003、TC-010、TC-012、TC-020） | 通过 6/7 · 待人工 1 |

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

| RUN-003 | TC-001 | 未通过 | 第 1～3 步通过（两条 grep 均无命中、`find` 输出 74）。第 4 步离线 `check` 在 `testNamingCheck` 上失败：10 个测试类命名违规，全部是本分支未改动的既有文件；排除该任务后离线全量构建 `BUILD SUCCESSFUL`，2,659 测试 0 失败 0 错误 | 见 §7 遗留问题最后一条，随 `REQ-004`（CI 门禁）一并修 |
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
