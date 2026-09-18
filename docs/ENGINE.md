# Synaxis 运行手册

面向一台**只装了 git 与 Homebrew 的 macOS 机器**：照本文从上到下执行，就能拿到一个可构建、可测试、可启动的引擎。每一步都是可直接粘贴的命令，不需要问任何人。

对应需求 `REQ-012`，验收用例 `docs/TEST.md` 的 `TC-016`。

---

## 1. 一次性环境准备

### 1.1 安装 JDK 25

引擎钉死 JDK 25（`build-src/src/main/kotlin/synaxis.java-library.gradle.kts` 里的 toolchain），低版本编译不过。

```bash
brew install openjdk@25
```

### 1.2 设置 `JAVA_HOME`

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
java -version   # 期望输出含 "openjdk version \"25"
```

Intel Mac 的 Homebrew 前缀是 `/usr/local` 而不是 `/opt/homebrew`，用下面这条替代上面第一行即可：

```bash
export JAVA_HOME="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home"
```

想每次开终端自动生效，把这两行 `export` 追加进 `~/.zshrc`：

```bash
cat >> ~/.zshrc <<'EOF'
export JAVA_HOME="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
EOF
```

Gradle 不需要单独安装，仓库自带 wrapper（`./gradlew`，版本钉在 `gradle/wrapper/gradle-wrapper.properties`，当前 9.7.1）。

### 1.3 克隆仓库

```bash
git clone https://github.com/Bearisbug/synaxis.git
cd synaxis
```

---

## 2. 网络：镜像 init 脚本与代理

### 2.1 每条 Gradle 命令都要带镜像 init 脚本

```
--init-script gradle/init/aliyun-mirror.init.gradle.kts
```

**为什么是必须的，不是可选优化**：

- 从国内直连 Maven Central 与 Gradle Plugin Portal 解析依赖会大概率超时或半途中断，而 `gradle.properties` 里开了 configuration cache / isolated projects，失败一次要重跑整轮配置。
- Gradle **不读** `http_proxy` / `https_proxy` 环境变量，所以就算 shell 里配了代理，依赖解析也不会走它。
- 这个 init 脚本把 pluginManagement 与 dependencyResolutionManagement 的仓库指向阿里云镜像，**只对本次构建生效**，不写进仓库配置也不写进 `~/.gradle`（见脚本首行注释），所以不会污染别的项目。

### 2.2 如有代理，再加 `JAVA_TOOL_OPTIONS`

镜像覆盖不到的少量制品（例如 Gradle 发行版本身、插件门户回退）仍会走公网。本机若在跑代理（默认 `127.0.0.1:7897`），执行 Gradle 前设置：

```bash
export JAVA_TOOL_OPTIONS="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttp.nonProxyHosts=maven.aliyun.com|*.aliyun.com"
```

- `nonProxyHosts` 把阿里云镜像排除在代理之外：镜像本来就是直连最快的一条路，绕代理反而更慢更容易断。
- 端口按自己的代理软件改。**没有代理就不要设这个变量**，否则所有 JVM 都会去连一个不存在的端口。
- 离线构建（见 §3.5）前必须 `unset JAVA_TOOL_OPTIONS`。

---

## 3. 常用命令

以下命令都在仓库根目录执行，且都假设 §1.2 的 `JAVA_HOME` 与 §2.2 的代理变量已经就绪。

### 3.1 构建 + 全部静态检查 + 测试（提交前的标准动作）

```bash
./gradlew check --no-daemon --max-workers=3 --init-script gradle/init/aliyun-mirror.init.gradle.kts
```

`check` 包含：Error Prone（96 项规则、`-Werror`，**禁止通配导入**，未用的 lambda 参数必须写 `_`）、禁止 import 检查、测试类命名检查、以及全量单元与集成测试。

### 3.2 只跑全量测试

```bash
./gradlew :test --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts
```

结果 XML 在 `build/test-results/test/`，HTML 报告在 `build/reports/tests/test/index.html`。

测试不会在 `$TMPDIR` 留下临时世界目录（`REQ-012`，用 JUnit 5 `@TempDir` 托管）。跑完可以自查：

```bash
ls -d "$TMPDIR"minestom-* 2>/dev/null | wc -l   # 期望 0
```

### 3.3 只跑单个测试类

```bash
./gradlew :test --tests 'io.github.bearisbug.synaxis.instance.UnloadChunkIntegrationTest' \
  --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts
```


### 3.4 启动服务端

```bash
./gradlew :server-app:run --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts
```

`server-app` 是唯一的启动入口：读 `synaxis.*` 配置、用 `ServiceLoader` 发现并启用插件、决定这次以**玩法模式**还是**核心模式**服务、再 bind 监听端口（`docs/DESIGN-M2.md` `API-M2-010` 启动序列第 ⑤ 步）。默认监听 `0.0.0.0:25565`，离线模式，任意用户名可进；就绪日志是 `synaxis server-app listening on 0.0.0.0:25565`。

端口与插件选择走系统属性，`run` 任务会把 `synaxis.*` 转给 fork 出来的 JVM：

```bash
./gradlew :server-app:run --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts \
  -Dsynaxis.app.port=25570 -Dsynaxis.app.plugins=rts
```

另开一个终端探活：

```bash
nc -z 127.0.0.1 25565 && echo "server is up"
```

在服务端 stdin 输入 `stop` 回车即可停服（15 秒内退出：插件 5 秒 + 引擎 10 秒）。进程会在仓库根写两处运行时产物：插件数据目录 `plugins/<plugin_id>/` 与事件台账 `logs/events-YYYYMMDD.jsonl`，两者都已 gitignore，删掉即回到基线。

#### 核心模式（不带任何玩法插件跑服务端核心）

一个插件都不参与启动时（`-Dsynaxis.app.plugins` 指了一个不存在的 id，或 classpath 上根本没有插件），进程**不会**退出，而是以核心模式起服：宿主自建一个平坦世界、任何连接一律放行进去、并注册一条 `synaxis:main` 诊断通道把收到的自定义负载原样回发。这是压测与冒烟服务端核心本身的入口——它不带任何玩法。

```bash
# 核心模式，classpath 上有没有玩法插件都一样（强制）
./gradlew :server-app:run --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts \
  -Dsynaxis.app.core-mode=on
```

启动日志里一眼能分清两种模式：核心模式打 `Core mode (synaxis.app.core-mode=…): …`，玩法模式打 `Gameplay mode: … plugin_ids=[rts]`。`synaxis.app.plugins` 里写了没有插件应答的 id 时另有一行 `No plugin answers to selected ids […]: discovered plugin_ids=[…].` 的 WARN——把 id 拼错（想写 `rts` 写成了 `rst`）与刻意排除全部插件在行为上都是「进核心模式」，起服后没人能进对局时先看这行。

`synaxis.app.core-mode` 三个值：`auto`（默认，没有插件参与启动就进核心模式）、`on`（强制核心模式，连插件发现都不做）、`off`（永不进核心模式，没有玩法就以非零码退出且端口不 bind，即核心模式落地之前的行为）。

核心模式起来之后可以用仓内探针打一次往返：

```bash
python3 tools/protocol-probe/probe.py login --hold 2
python3 tools/protocol-probe/probe.py echo --payload ping   # 期望打印 echo channel=synaxis:main payload=ping
```

**插件参与了启动却没留下玩法**（`onEnable` 抛异常、`-Dsynaxis.app.map` 指了未知预设……）仍然以非零码退出且端口不 bind——这是设计好的行为，不是故障，核心模式不会替一个坏掉的插件兜底。写插件见 `docs/PLUGINS.md`。

### 3.5 离线构建（验证依赖已全部冻结在本地）

```bash
unset JAVA_TOOL_OPTIONS
./gradlew check --offline --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts
```

前提是依赖已经进过一次本地 Gradle 缓存（先按 §3.1 联网跑一遍）。注册表数据已经 vendoring 进仓库（`REQ-001`），不再有 `net.minestom:data` 这条外部数据链。

离线时仍然要带镜像 init 脚本：`--offline` 已经保证零网络访问，而 Gradle 的依赖缓存是**按来源仓库匹配**的——依赖当初是从阿里云镜像下下来的，仓库列表里没有这个镜像就会误报 `No cached version ... available for offline mode`。

### 3.6 包依赖矩阵（改动包结构之后）

**按模块分别跑**，不要一次扫全仓：五个模块的包名都在 `io.github.bearisbug.synaxis` 之下，一次扫完会把四个新模块折叠进引擎的两三个桶里，各自的内部结构反而看不见。

```bash
python3 tools/pkgdeps.py                                     # 引擎（含底层出向上层报告）
python3 tools/pkgdeps.py protocol/src/main/java   --root-package io.github.bearisbug.synaxis.game.protocol --skip-lower-layer
python3 tools/pkgdeps.py plugin-api/src/main/java --root-package io.github.bearisbug.synaxis.plugin        --skip-lower-layer
python3 tools/pkgdeps.py server-app/src/main/java --root-package io.github.bearisbug.synaxis.app           --skip-lower-layer
python3 tools/pkgdeps.py game-rts/src/main/java   --root-package io.github.bearisbug.synaxis.game.rts      --skip-lower-layer
```

引擎那一跑要看的是最后一段 `lower layer edges into the rest of the engine: 0`（`--detail` 会把违规文件逐个列出来）；`--skip-lower-layer` 把这段去掉，因为底层 9 包的分层只是引擎的，四个新模块没有对应的层。四个新模块当前各自只有一个扁平包，矩阵为空是正常输出。

---

## 4. 分支策略

- **`main` 是唯一主干**，永远可构建、可测试。
- 本仓库是 Minestom 的**硬 fork**，已经和上游断开：没有 `upstream` 远程，不 rebase 上游，不接受上游 PR。`git remote -v` 只应该看到 `origin`（`https://github.com/Bearisbug/synaxis.git`）。
- 干活开功能分支（例如 `freeze-data`、`fix/entity`），完成后合回 `main`。
- 合并前必须本地 `./gradlew check` 全绿（§3.1）。
- commit message 只描述代码变更本身，**禁止任何 AI 署名 / co-author trailer**（见根目录 `CLAUDE.md`）。
- 功能改动必须先动 `docs/DESIGN.md`，再改代码，再同步 `docs/TEST.md` 的用例与执行记录。

---

## 5. 目录地图

| 路径 | 内容 |
| --- | --- |
| `src/main/java/` | 引擎主源码，包名 `io.github.bearisbug.synaxis`（2026-09-15 定名；两次改名提交都登记在 `.git-blame-ignore-revs`） |
| `src/main/java/.../registry`、`EntityType`、`Block` 等常量表 | 原为生成物，自 REQ-008 起作为普通源码维护；文件头有说明 |
| `src/main/resources/data/` | **vendoring 的注册表数据**：39 个 JSON + `opaque/` 下 23 个黄金 NBT（REQ-007）；运行时由 `RegistryData.loadRegistryFile` 按 `/data/<路径>` 从 classpath 读取 |
| `src/main/resources/META-INF/native-image/` | GraalVM 可达性元数据（资源 glob 指向 `data/*.json`） |
| `src/test/` | 单元与集成测试 |
| `testing/` | `@EnvTest` 测试夹具模块，被 `src/test` 依赖 |
| `protocol/` | M2 子项目：与 Fabric 客户端共享的消息定义，零第三方依赖（`docs/DESIGN-M2.md` `ADR-M2-008`） |
| `plugin-api/` | M2 子项目：唯一稳定面，引擎与 `protocol` 类型的受控再导出；接 ABI 门 |
| `server-app/` | M2 子项目：插件宿主与装配层（唯一启动入口），跑 classpath，不出模块 |
| `game-rts/` | M2 子项目：RTS 玩法插件，由宿主用 `ServiceLoader` 发现 |
| `build-src/` | Gradle 约定插件（toolchain、Error Prone、发布、ABI 检查） |
| `gradle/init/` | 构建用的临时 init 脚本，目前只有阿里云镜像脚本，见 §2.1 |
| `gradle/libs.versions.toml` | 版本目录：所有第三方版本与 `minecraft = "26.2"` 都钉在这里 |
| `example-plugin/` | `docs/PLUGINS.md` 的成品示例插件；独立 Gradle 构建，不进 `settings.gradle.kts` |
| `tools/` | 运维与验收脚本，协议探针在 `tools/protocol-probe/probe.py` |
| `docs/DESIGN.md` | 设计文档，单一事实源 |
| `docs/TEST.md` | 测试文档，验收执行脚本与轮次台账 |
| `docs/ENGINE.md` | 本文 |
| `docs/PLUGINS.md` | 一页插件开发说明（`REQ-M2-023`） |
| `docs/research/` | 选型调研与对起点代码的六路审计、架构评审 |
| `docs/test-runs/` | 各轮测试的证据目录（`RUN-*`） |

版本钉死口径：Minecraft 26.2 / 协议 776（`src/main/resources/data/constants.json`）、JDK 25、Gradle 9.7.1。

---

## 6. 升 Minecraft 版本 checklist

硬 fork 之后升版本是一次**主动的、有预算的项目**，不是季度例行动作。按 `docs/research/minestom-audit/07-architecture-review.md` §8.5 执行：

1. **决策**：先写清为什么升（新客户端特性 / 玩家被迫升级 / 安全）。没有明确理由就不升——钉死版本的全部价值就在这里。
2. **拿数据**：从上游 `MinestomDataGenerator` 跑出新版 JSON（或直接取新版 `net.minestom:data` 解包），整体替换 `src/main/resources/data/`，然后 `git diff` 逐个注册表看增删。
3. **协议 diff**：对照 wiki.vg / Mojang 的版本差异，列出新增、删除、改字段的包。这是工作量主体。
4. **改包定义**：按 diff 改 `network/packet/**`；每个改动过的包都必须有往返测试。
5. **改常量表**：从 git 历史复活 `code-generators`（REQ-008 删除前的最后版本在 commit `b162f394e8`），用新数据跑一次（入参 `<注册表数据目录> <输出目录>`），把输出与我们手工裁剪过的 `src/main/java`（原生成物已并入，REQ-008） 做三方合并——生成器会覆盖裁剪，必须人工合。
6. **注册表**：新增 / 删除的注册表在 `Registries.java` 与 `OpaqueRegistry` 里加减，并跑 `RegistryDataPacket` 字节对比那道门（`docs/TEST.md` TC-007）。
7. **客户端**：Fabric 模组同步升 Loader / Yarn / API，自定义 payload 的编解码用共享测试向量在两端各跑一遍。
8. **验收**：原版客户端加我们的 Fabric 模组走完整「登录 → 进世界 → 自定义协议往返」；`./gradlew check` 全绿；ArchUnit 绿。同时更新 §5 里的版本钉死口径与 `gradle/libs.versions.toml` 的 `minecraft`。

---

## 7. CI

两个工作流，都在 push 到 `main` 与每个 PR 上触发；GitHub 运行器直连 Maven Central，**不用**镜像 init 脚本。

- `ci.yml`：temurin JDK 25，`gradle/actions/setup-gradle` 缓存（非 `main` 只读），跑 `./gradlew check jacocoTestReport`，上传测试报告与 JaCoCo XML（保留 14 天）；PR 上另跑 `diff-cover` 对新增行做 80% 覆盖率门（需要 `fetch-depth: 0`）。本地等价：`./gradlew check jacocoTestReport --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts`，再 `diff-cover $(find . -path '*/build/reports/jacoco/test/jacocoTestReport.xml' | sort) --compare-branch=origin/main --src-roots src/main/java testing/src/main/java protocol/src/main/java plugin-api/src/main/java server-app/src/main/java game-rts/src/main/java --fail-under=80`（`pip install diff-cover`）——覆盖率门读**全部模块**的报告，少一个 `--src-roots` 就会让那个模块的新增行在本地隐形、与 CI 得出不同的绿。
- `concurrency.yml`：`./gradlew :jcstress-tests:jcstress -Dorg.gradle.isolated-projects=false -Dorg.gradle.configuration-cache=false`，该插件不支持这两项；报告在 `jcstress-tests/build/reports/jcstress/`。本地快速模式把 `jcstress-tests/build.gradle.kts` 的 `mode` 临时设为 `quick`。

`check` 里的三道门：Error Prone（约 96 项，`-Werror`）、`forbiddenImportsCheck`、`testNamingCheck`（测试类名必须与其夹具一致：`@EnvTest` 的类以 `IntegrationTest` 结尾，注册表夹具以 `RegistriesTest` 结尾；该任务是增量任务，只有干净构建目录或 `--rerun-tasks` 才全量扫），外加 `ArchitectureTest`：ArchUnit **严格**分层规则——底层集合 `codec、condition、coordinate、exception、gamedata、monitoring、thread、timer、utils` 不得依赖集合之外的引擎包，唯一豁免是根包 `JvmFlags`（零引擎依赖、只读系统属性）；任何新增反向边直接让构建红，没有冻结存储。同一个 `ArchitectureTest` 还有第二条规则「引擎不得依赖游戏侧、稳定面与宿主」——禁止 `io.github.bearisbug.synaxis.game..`、`io.github.bearisbug.synaxis.plugin..`、`io.github.bearisbug.synaxis.app..` 三个完整前缀（`REQ-M2-002`；写完整前缀是因为 `..plugin..` 会误命中引擎自己的 `network.plugin`）。第三条规则「客户端可驱动的路径不得逐包写日志」见 §8。`python3 tools/pkgdeps.py` 打印顶层包依赖矩阵与「底层出向上层边数」，改动包结构后先跑它，且**按模块分别跑**（每个模块一条命令，见 §3.6）。`:plugin-api` / `:protocol` 已接 ABI 门（`ci.yml` 的 `ABI gate of the stable surface` 步骤跑 `:plugin-api:checkBinaryCompatibility :protocol:checkBinaryCompatibility`）：打出第一个 tag 之前没有 baseline，不传 `-PbaselineJarDir` 时两个任务以 Gradle 的 `SKIPPED` 结束并打印「无 baseline，ABI 门未生效」、构建照常绿，传了而 baseline jar 缺失则失败（引导规则见 `docs/DESIGN-M2.md` §24）；引擎根模块不接这道门。`check` 里**唯一一条挂墙钟的断言**是 `:game-rts` 的 `MatchIntegrationTest#theFullSizeMapGenerates`（512×512 / 1,024 区块的整图生成，`load_ms < 3000`，`docs/DESIGN-M2.md` §15 的预算、§26 阶段 3 的退出判据）。本机实测 0.7～1.2 s，余量三四倍；CI 机器被压满时它是最先红的那条——**先怀疑机器负载，别当功能回归查**。

---

## 8. 写日志的纪律：日志量不由客户端决定

2026-09-18 的压测实测：一条「每个客户端包写一行」的 WARN 在 15 分钟里写了 662,803 行 / 84 MiB。这不是日志级别的问题，是**这条日志写多少次由对端说了算**。所以加日志之前先问一句「这一行的频率由谁决定」：

- **由启动、注册表数据、配置或停机决定** —— 进程生命周期内次数固定，随便写。
- **由连接数决定** —— 每连接至多一行，且必须与一次断开在同一分支上（`PlayerSocketConnection` 的协议错误、`HandshakeListener` 的 BungeeGuard 拒绝、`LoginListener` 的登录插件应答就是这个形状）。**「断开」要真的把活停下来才算数**：一次读上来的是一整批包，`PlayerSocketConnection#processPackets` 每派发一个包前先看连接还在不在，不在就 `break`，否则排在这次 kick 后面的几千个帧照样一个个交给监听器，「每连接一行」就变成「每包一行」（实测一批 4,000 帧，见 `docs/TEST.md` TC-033）。
- **由客户端发了什么、发了多少决定** —— 必须有界，三选一：
  1. **按时间限流**，样板是 `EntityTrackerImpl#reportUnregisteredMove` 与 `PacketReading#reportUnreadBytes`：一个 `AtomicLong` 记区间内累计次数、一个 `AtomicLong` 记上次输出时刻，CAS 抢到的那一次才写，并把累计次数带进那一行（**量被压掉了，但量还看得见**）。计数器只在「本来就要写日志」的那条分支上碰，解码热路径不为它付钱。
  2. **按类型去重**，样板是 `PacketListenerManager` 的 `missingListenerWarnings`：客户端加不了包类型，所以一种一次就封顶了。
  3. **紧跟一次断开**，见上一档。

这条纪律有门禁：`ArchitectureTest#clientDrivenPathsDoNotWriteALinePerPacket` 禁止 `network..` 与 `listener..` 两个包树调用 `Logger#warn` / `Logger#error` / `Throwable#printStackTrace` / `ExceptionManager#handleException`。**`handleException` 也在禁止之列**，因为引擎默认的处理器就是 `Throwable::printStackTrace`：宿主决定这份报告落到哪里，调用点决定它写多少次。豁免名单是上面点名的七个类（`PacketReading`、`PlayerSocketConnection`、`Server`、`ConnectionManager`、`PacketListenerManager`、`HandshakeListener`、`LoginListener`），每一条都在 `boundedReporters()` 的 Javadoc 里写明自己的上界。新加一个类要写 WARN 就会红——**红了先想怎么让它有界，别直接进豁免名单**。`theClientDrivenLogRuleRejectsALinePerPacket` 与 `theClientDrivenLogRuleRejectsAReportPerPacket` 用测试源集里的两个 fixture 反证这条规则真的会红（一个写 WARN，一个把异常交给宿主）。

盲点三个，写在这里免得被门禁的绿骗了：豁免是按类不按调用点（七个类里再加一条无界的行不会红）；`command` 包不在清单里（`CommandParserImpl` 执行命令抛异常时每次一行带栈的 ERROR）；以及豁免里 `PlayerSocketConnection` 对「监听器抛了异常」每包交一次 `handleException`，只有 `rejectMisusedPacket`（默认关）才会断开，这一处仍是敞的。后两条登记在 `docs/DESIGN.md` §28。
