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

Gradle 不需要单独安装，仓库自带 wrapper（`./gradlew`，版本钉在 `gradle/wrapper/gradle-wrapper.properties`，当前 9.8.0）。

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

`server-app` 是唯一的启动入口：读 `synaxis.*` 配置、用 `ServiceLoader` 发现并启用插件、决定这次以**玩法模式**还是**核心模式**服务、再 bind 监听端口（`docs/DESIGN-M2.md` `API-M2-010` 启动序列第 ⑤ 步）。默认监听 `0.0.0.0:25565`，**在线模式**：每条连接加密并向 Mojang sessionserver 验证，只有正版账号进得来（`docs/DESIGN-PROD.md` `API-PROD-001`）。本机开发、探针、压测与无头客户端都没有正版账号，起服时加 `-Dsynaxis.app.auth=offline`（任意用户名可进）；`offline-encrypted` 是加密但不验证；`-Dsynaxis.app.auth.session-server=<URL>` 把验证改向 `tools/fake-sessionserver`，只给 CI 与压测用，启动日志会打 WARN。就绪日志是 `synaxis server-app listening on 0.0.0.0:25565`。

端口与插件选择走系统属性，`run` 任务会把 `synaxis.*` 转给 fork 出来的 JVM：

```bash
./gradlew :server-app:run --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts \
  -Dsynaxis.app.port=25570 -Dsynaxis.app.plugins=rts
```

同样的键可以写进工作目录下的 `synaxis.properties`（`:server-app:run` 的工作目录是仓库根），或用 `-Dsynaxis.config-file=<路径>` 指别的文件，这个键只从命令行读。文件是 `java.util.Properties` 格式、按 UTF-8 读，一行 `键=值`，键名与系统属性逐字相同。优先级是命令行 `-D` > 文件 > 启动器默认（`ShardMain` 的分片默认值、`Main` 的 RTS 视野默认值）> 引擎与宿主的默认值。文件里有键表外的键、同一个键写两次、或写了 `synaxis.config-file` 时拒绝起服，一次列出全部未知键并各给出最近的已知键，如 `Refusing to start: <文件绝对路径>: unknown key synaxis.max-player (nearest known key synaxis.max-players)`，退出码 1、端口不 bind。文件在 `Main` 类初始化的第一步就设成系统属性，早于 logback 读 `synaxis.app.log`、早于 `JvmFlags` 等 JVM 级开关类加载、早于进程读 `ServerConfig`，所以 JVM 级键（如 `synaxis.compression-level`）写在文件里同样生效。只在起服时读一次，改了要重启。起服日志的 `Effective config file: path=… keys=… overridden_by_command_line=[…]` 一行写明读的是哪个文件、几个键、哪些被命令行盖掉；105 个键的默认值与写法见 `docs/DESIGN-PROD.md` `API-PROD-023` 的键表。

另开一个终端探活：

```bash
nc -z 127.0.0.1 25565 && echo "server is up"
```

在服务端 stdin 输入 `stop` 回车即可停服：先关登录门，再保存在线玩家（≤ 30 s）、踢人（≤ 5 s）、禁用插件、台账落盘（≤ 2 s）后退出，阶段与预算见 `docs/DESIGN-PROD.md` §11.4；`stop <秒数>` 在关登录门之后先倒计时再保存。进程会在仓库根写两处运行时产物：插件数据目录 `plugins/<plugin_id>/` 与事件台账 `logs/events-YYYYMMDD.jsonl`，两者都已 gitignore，删掉即回到基线。

#### 核心模式（不带任何玩法插件跑服务端核心）

一个插件都不参与启动时（`-Dsynaxis.app.plugins` 指了一个不存在的 id，或 classpath 上根本没有插件），进程**不会**退出，而是以核心模式起服：宿主自建一个平坦世界（以原点为中心、边长 4,096 格的世界边界之内，`docs/DESIGN-PROD.md` v0.9.53）、任何连接一律放行进去、并注册一条 `synaxis:main` 诊断通道把收到的自定义负载原样回发。世界有光照（出生点一圈在绑定端口之前照亮）；宿主另挂默认玩法层（同实例聊天、丢弃与拾取、近战、掉出世界底部的伤害、新角色首次出生的 64 个石头与一把铁镐、出生点东侧 3 格的训练假人）与玩家持久化段（退出重进位置、背包、选中格与生命值都还在），见 `docs/DESIGN-PROD.md` `REQ-PROD-046`、`REQ-PROD-047`（v0.9.63）。它既是压测与冒烟服务端核心本身的入口，也是「服务端核心完全正常游玩」的验收世界（该分册 §19 可玩项矩阵）；不带插件玩法，也不补合成、怪物、饥饿、红石这些原版生存内容。

```bash
# 核心模式，classpath 上有没有玩法插件都一样（强制）；探针不会加密，所以显式离线
./gradlew :server-app:run --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts \
  -Dsynaxis.app.core-mode=on -Dsynaxis.app.auth=offline
```

启动日志里一眼能分清两种模式：核心模式打 `Core mode (synaxis.app.core-mode=…): …`，玩法模式打 `Gameplay mode: … plugin_ids=[rts]`。`synaxis.app.plugins` 里写了没有插件应答的 id 时另有一行 `No plugin answers to selected ids […]: discovered plugin_ids=[…].` 的 WARN——把 id 拼错（想写 `rts` 写成了 `rst`）与刻意排除全部插件在行为上都是「进核心模式」，起服后没人能进对局时先看这行。

`synaxis.app.core-mode` 三个值：`auto`（默认，没有插件参与启动就进核心模式）、`on`（强制核心模式，连插件发现都不做）、`off`（永不进核心模式，没有玩法就以非零码退出且端口不 bind，即核心模式落地之前的行为）。

核心模式起来之后可以用仓内探针打一次往返：

```bash
python3 tools/protocol-probe/probe.py login --hold 2
python3 tools/protocol-probe/probe.py echo --payload ping   # 期望打印 echo channel=synaxis:main payload=ping
```

**插件参与了启动却没留下玩法**（`onEnable` 抛异常、`-Dsynaxis.app.map` 指了未知预设……）仍然以非零码退出且端口不 bind——这是设计好的行为，不是故障，核心模式不会替一个坏掉的插件兜底。写插件见 `docs/PLUGINS.md`。

#### JVM 参数（生产默认）

`server-app/build.gradle.kts` 的 `applicationDefaultJvmArgs` 写死了下面七个参数（`docs/DESIGN-PROD.md` `REQ-PROD-028` 与 `REQ-PROD-015`）。`:server-app:run` 与 `installDist` / `distZip` 生成的启动脚本 `bin/server-app` 都带上它们，所以本机开发、CI 与部署机跑的是同一组。

| 参数 | 理由 |
| --- | --- |
| `-XX:+UseG1GC` | JDK 25 的默认收集器本来就是 G1，但只在 JVM 认定为「服务器级机器」（≥ 2 核且 ≥ 1792 MiB 内存）时才选它；容器里 CPU 或内存配额卡得小，会静默退回 Serial。显式写上，换机器不变 |
| `-Xms4G -Xmx4G` | 初始堆等于最大堆，运行中不扩缩堆、不为扩堆触发 GC。50 连接三档（1,000 / 2,000 / 4,000 单位）强制 GC 后的存活堆是 43.0 / 51.1 / 64.1 MiB（`docs/research/soak-2026-09-22/report.md` 规模曲线表）；500 人档的存活堆未实测（`REQ-PROD-029` 压测后回填）。部署机按 16 GiB 规划（`docs/DESIGN-PROD.md` §2 容量表），4 GiB 堆之外留给堆外内存（网络缓冲、元空间、线程栈）与系统 |
| `-XX:+AlwaysPreTouch` | 启动时把整个堆逐页写一遍，缺页中断发生在 bind 之前，不落在 tick 里。代价是启动慢一点、进程一起来就占满堆的物理内存 |
| `-XX:MaxGCPauseMillis=50` | 一个 tick 的预算是 50 ms（20 TPS）。Paper 推荐的 Aikar 参数用 200 ms（docs.papermc.io/paper/aikars-flags），一次停顿就能吃掉 4 个 tick |
| `-XX:+HeapDumpOnOutOfMemoryError` | OOM 时在进程工作目录写 `java_pid<pid>.hprof`（`:server-app:run` 的工作目录是仓库根，`**.hprof` 已 gitignore）。一份转储最大约等于堆上限 4 GiB，不自动删除（`docs/DESIGN-PROD.md` §15 日志与磁盘行） |
| `-XX:+ExitOnOutOfMemoryError` | OOM 后堆里的状态已不可信，直接退出、不走 `stop` 的停机序列，由 systemd 或容器拉起（`REQ-PROD-015`）。部署单元要配自动重启，否则 OOM 后进程就停在那里 |

改用分代 ZGC 的条件（`docs/DESIGN-PROD.md` §2 堆行）：堆上限要超过 8 GiB，或压测中 GC 暂停 P99 > 10 ms。代价是吞吐与 RSS 变差，幅度未测。JDK 25 的 ZGC 只有分代模式，不需要再写 `-XX:+ZGenerational`。

**覆盖方式**：启动脚本把 `JAVA_OPTS` 与 `SERVER_APP_OPTS` 排在默认参数之后，HotSpot 对同一参数取最后一次，所以部署机改参数不用重新构建。换收集器时必须先关掉 G1，只加 `-XX:+UseZGC` 会报 `Multiple garbage collectors selected` 并拒绝启动：

```bash
./gradlew :server-app:installDist --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts
JAVA_OPTS="-XX:-UseG1GC -XX:+UseZGC -Xms8G -Xmx8G -Dsynaxis.app.port=25565" \
  server-app/build/install/server-app/bin/server-app
```

`:server-app:run` 不读 `JAVA_OPTS`，本机开发要换参数只能临时改 `build.gradle.kts`。

**确认参数生效**：服务端就绪后按端口找到 pid，读 `jcmd <pid> VM.flags`：

```bash
pid=$(lsof -nP -iTCP:25565 -sTCP:LISTEN -t)
jcmd "$pid" VM.flags | tr ' ' '\n' | grep -E 'UseG1GC|HeapSize|AlwaysPreTouch|MaxGCPauseMillis|OnOutOfMemoryError'
```

期望出现 `-XX:+UseG1GC`、`-XX:InitialHeapSize=4294967296`、`-XX:MaxHeapSize=4294967296`、`-XX:+AlwaysPreTouch`、`-XX:MaxGCPauseMillis=50`、`-XX:+HeapDumpOnOutOfMemoryError`、`-XX:+ExitOnOutOfMemoryError`。macOS 上 `ps -o rss` 在预触之后仍只有几十 MiB（2026-09-23 本机实测 47,920 KiB），这是 macOS 压缩了全零页，不说明预触没生效，以 `VM.flags` 为准；Linux 上预触后的 RSS 未实测。

### 3.5 离线构建（验证依赖已全部冻结在本地）

```bash
unset JAVA_TOOL_OPTIONS
./gradlew check --offline --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts
```

前提是依赖已经进过一次本地 Gradle 缓存（先按 §3.1 联网跑一遍）。注册表数据已经 vendoring 进仓库（`REQ-001`），不再有 `net.minestom:data` 这条外部数据链。

离线时仍然要带镜像 init 脚本：`--offline` 已经保证零网络访问，而 Gradle 的依赖缓存是**按来源仓库匹配**的——依赖当初是从阿里云镜像下下来的，仓库列表里没有这个镜像就会误报 `No cached version ... available for offline mode`。

### 3.6 包依赖矩阵（改动包结构之后）

**按模块分别跑**，不要一次扫全仓：七个模块的包名都在 `io.github.bearisbug.synaxis` 之下，一次扫完会把六个新模块折叠进引擎的两三个桶里，各自的内部结构反而看不见。

```bash
python3 tools/pkgdeps.py                                     # 引擎（含底层出向上层报告）
python3 tools/pkgdeps.py protocol/src/main/java   --root-package io.github.bearisbug.synaxis.game.protocol --skip-lower-layer
python3 tools/pkgdeps.py plugin-api/src/main/java --root-package io.github.bearisbug.synaxis.plugin        --skip-lower-layer
python3 tools/pkgdeps.py server-app/src/main/java --root-package io.github.bearisbug.synaxis.app           --skip-lower-layer
python3 tools/pkgdeps.py game-rts/src/main/java   --root-package io.github.bearisbug.synaxis.game.rts      --skip-lower-layer
python3 tools/pkgdeps.py play-kit/src/main/java   --root-package io.github.bearisbug.synaxis.game.playkit  --skip-lower-layer
python3 tools/pkgdeps.py game-rush/src/main/java  --root-package io.github.bearisbug.synaxis.game.rush     --skip-lower-layer
```

引擎那一跑要看的是最后一段 `lower layer edges into the rest of the engine: 0`（`--detail` 会把违规文件逐个列出来）；`--skip-lower-layer` 把这段去掉，因为底层 9 包的分层只是引擎的，其余模块没有对应的层。各模块的读数（RUN-077 集成分支）：`:protocol` 2 个包、2 条边：`play` 引根包的消息公共类型，根包的 `Message` 又在 sealed `permits` 里列了 `play.PlayMessage`，两个包互相依赖是 sealed 层次决定的，不是违规；`:plugin-api` 3 个包、3 条边（根包的 `PluginContext` 引 `play`、`instance` 的两个入口，`instance` 引回根包）；`:server-app` 12 个包、28 条边；`:game-rts` 2 个包、0 条边；`:play-kit`、`:game-rush` 各 1 个包，矩阵为空。`architecture-tests/` 没有主源码，没有它的一条。

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
| `src/main/resources/data/` | **vendoring 的注册表数据**：39 个 JSON + `opaque/` 下 23 个黄金 NBT（REQ-007）；运行时由 `RegistryData.loadRegistryFile` 先按 `/data/<路径>` 从 classpath 读取，找不到再读工作目录下的 `<路径>`（可以用来补充没有 vendoring 的 tags），走到工作目录时打一行 INFO |
| `src/main/resources/META-INF/native-image/` | GraalVM 可达性元数据（资源 glob 指向 `data/*.json`） |
| `src/test/` | 单元与集成测试 |
| `testing/` | `@EnvTest` 测试夹具模块，被 `src/test` 依赖 |
| `protocol/` | M2 子项目：与 Fabric 客户端共享的消息定义，零第三方依赖（`docs/DESIGN-M2.md` `ADR-M2-008`）；`game.protocol.play` 包是宿主场景层通道 `synaxis:play` v1（`docs/DESIGN-PLAY.md` `API-PLAY-002`），向量在 `protocol/src/test/resources/play-vectors/` |
| `plugin-api/` | M2 子项目：唯一稳定面，引擎与 `protocol` 类型的受控再导出；接 ABI 门；子包 `plugin.play`、`plugin.instance` 是场景层与托管实例的插件面（`docs/DESIGN-PLAY.md` `API-PLAY-005`、`API-PLAY-006`） |
| `server-app/` | M2 子项目：插件宿主与装配层（唯一启动入口），跑 classpath，不出模块；包 `app.scene`、`app.instance` 是场景层与托管实例的宿主实现 |
| `game-rts/` | M2 子项目：RTS 玩法插件，由宿主用 `ServiceLoader` 发现 |
| `play-kit/` | 玩法辅助库 `Round`、`LocalQueue`、`Countdown`（`docs/DESIGN-PLAY.md` `API-PLAY-008`）：只依赖 `:plugin-api`，不进 ABI 门、不作兼容承诺 |
| `game-rush/` | 样板玩法插件 `rush`「信标速攻」（`docs/DESIGN-PLAY.md` §6.7）：不进 `server-app` 默认的运行期依赖，`./gradlew :server-app:runRush` 带着它起服（`ADR-PLAY-011`）；R1 环的起法见 `docs/PLUGINS.md` §6 |
| `loadtest/` | 压测 harness（`docs/DESIGN-PROD.md` `API-PROD-025`）：Java 机器人（真握手、可选加密、回 `ChunkBatchReceived`、walk / ride / fly）与 `sample`、`merge`、`flood` 三个子命令；不发布、没有模块依赖它。`./gradlew :loadtest:installDist` 后跑 `loadtest/build/install/loadtest/bin/loadtest run …`。`tools/loadtest/soak.py` 只留给 50 人以下的冒烟、RTS `play` 画像与攻击探针 |
| `synx/` | M3 子项目：`.synx` 区域文件与清单的编解码、起服校验函数 `SynxBuildVerifier` 与冻结的测试向量（`synx/src/test/resources/vectors/`），`docs/DESIGN-M3.md` `API-M3-001`、`API-M3-002` |
| `baker/` | M3 子项目：离线烘焙器（`docs/DESIGN-M3.md` `API-M3-003`），把原版 26.2 Anvil 存档或生成器预设烘成 `.synx` 地图版本；Anvil 读取器在 `baker.anvil` 包；`./gradlew :baker:run --args="bake …"`，工作目录是 `baker/`，路径参数写绝对路径 |
| `architecture-tests/` | M2 子项目，**纯测试**：没有主源码，把引擎与 `:plugin-api`、`:protocol`、`:game-rts`、`:play-kit`、`:game-rush`、`:server-app`、`:baker`、`:synx` 全部当 `testImplementation`（`architecture-tests/build.gradle.kts:17-27`），只住 `docs/DESIGN-M2.md` §24 规则 ⑥ 那两条任何单个模块都表达不了的反方向 ArchUnit 规则；不进 `pkgdeps`（§3.6），也不进 CI 的 `diff-cover --src-roots`（没有主源码行可覆盖） |
| `client-fabric/` | M2 客户端模组工程（`REQ-M2-020`）：Fabric Loom 的**独立 Gradle 构建**，自带 wrapper，不进 `settings.gradle.kts`（`ADR-M2-015`）；`:protocol` 从 `mavenLocal()` 按坐标消费并 JiJ 进模组 jar；`check` 跑 §24 的双端向量（读 `protocol/src/test/resources/vectors/` 与 `play-vectors/` 本身）与 `verifyModJar`，CI 见 §7 的 `client-mod.yml` |
| `build-src/` | Gradle 约定插件（toolchain、Error Prone、发布、ABI 检查） |
| `gradle/init/` | 构建用的临时 init 脚本，目前只有阿里云镜像脚本，见 §2.1 |
| `gradle/libs.versions.toml` | 版本目录：所有第三方版本与 `minecraft = "26.2"` 都钉在这里 |
| `example-plugin/` | `docs/PLUGINS.md` 的成品示例插件；独立 Gradle 构建，不进 `settings.gradle.kts` |
| `tools/` | 运维与验收脚本，协议探针在 `tools/protocol-probe/probe.py`；`tools/light-oracle/gen.sh` 用原版服务端的光照引擎生成光照形状表与对拍真值；`tools/anvil-fixture/gen.sh` 用原版 26.2 服务端生成 `:baker` 测试用的 Anvil 存档 |
| `docs/DESIGN.md` | 设计文档，单一事实源 |
| `docs/TEST.md` | 测试文档，验收执行脚本与轮次台账 |
| `docs/ENGINE.md` | 本文 |
| `docs/PLUGINS.md` | 一页插件开发说明（`REQ-M2-023`） |
| `docs/MAP-SOURCE.md` | 给地图美术的编辑源交付规范：存档要求、可用高度、处理顺序、数据标记与烘焙器自检命令 |
| `docs/research/` | 选型调研与对起点代码的六路审计、架构评审 |
| `docs/test-runs/` | 各轮测试的证据目录（`RUN-*`） |

版本钉死口径：Minecraft 26.2 / 协议 776（`src/main/resources/data/constants.json`）、JDK 25、Gradle 9.8.0（`client-fabric` 的独立 wrapper 是 9.7.1）。
**客户端侧另有一份且不在版本目录里**：`client-fabric/build.gradle.kts` 顶部的 `minecraftVersion` / `loaderVersion` / `fabricApiVersion`（26.2 / 0.19.5 / 0.161.0+26.2）——独立构建读不到 `gradle/libs.versions.toml`，升版本时这三个常量必须单独改，`fabric.mod.json` 的 minecraft 依赖由第一个常量展开。

---

## 6. 升 Minecraft 版本 checklist

硬 fork 之后升版本是一次**主动的、有预算的项目**，不是季度例行动作。按 `docs/research/minestom-audit/07-architecture-review.md` §8.5 执行：

1. **决策**：先写清为什么升（新客户端特性 / 玩家被迫升级 / 安全）。没有明确理由就不升——钉死版本的全部价值就在这里。
2. **拿数据**：从上游 `MinestomDataGenerator` 跑出新版 JSON（或直接取新版 `net.minestom:data` 解包），整体替换 `src/main/resources/data/`，然后 `git diff` 逐个注册表看增删。注册表数据不带 `useShapeForLightOcclusion`，光照靠 `instance/light/light_shape_states.txt` 按状态 ID 补上：把 `tools/light-oracle/gen.sh` 里的 `SERVER_SHA1` 与 Loom 缓存路径改成新版，重跑它，新表与 `parity/*.light.gz` 对拍真值一起提交（`LightBlocksTest` 会拦下 ID 已错位的旧表）。
3. **协议 diff**：对照 wiki.vg / Mojang 的版本差异，列出新增、删除、改字段的包。这是工作量主体。
4. **改包定义**：按 diff 改 `network/packet/**`；每个改动过的包都必须有往返测试。
5. **改常量表**：从 git 历史复活 `code-generators`（REQ-008 删除前的最后版本在 commit `b162f394e8`），用新数据跑一次（入参 `<注册表数据目录> <输出目录>`），把输出与我们手工裁剪过的 `src/main/java`（原生成物已并入，REQ-008） 做三方合并——生成器会覆盖裁剪，必须人工合。
6. **注册表**：新增 / 删除的注册表先在 `BuiltinRegistries` 加减常量；类型化注册表在 `Registries` 与 `VanillaRegistries` 加减访问器，不透明注册表进 `RegistriesImpl.OPAQUE_KEYS` 并放 `data/opaque/<name>.nbt`；再按客户端的顺序插进 `RegistriesImpl#configurationRegistries()`，两张表漏改一张由 `RegistryDataPacket` 字节对比那道门拦下（`docs/TEST.md` TC-007）。
7. **物品提示行**：`ItemStackImpl.Builder` 的 `EXTRA_TOOLTIP_HIDE`（`ItemStack#withoutExtraTooltip` 隐藏的组件）按新版客户端重核：`javap -c` 新版客户端 jar 的 `ItemStack#addDetailsToTooltip` 与属性提示行，它读到的组件除 `minecraft:lore` 外都要在这张表里，新版删掉的组件从表里删掉。
8. **客户端**：Fabric 模组同步升 Loader / Yarn / API——改的是 `client-fabric/build.gradle.kts` 的 `minecraftVersion` / `loaderVersion` / `fabricApiVersion` 三个常量（版本目录管不到它），自定义 payload 的编解码用共享测试向量在两端各跑一遍。
9. **验收**：原版客户端加我们的 Fabric 模组走完整「登录 → 进世界 → 自定义协议往返」；`./gradlew check` 全绿；ArchUnit 绿。同时更新 §5 里的版本钉死口径、`gradle/libs.versions.toml` 的 `minecraft` 与 `client-fabric/build.gradle.kts` 的三个版本常量（漏掉最后一处，服务端升了而客户端会静默停在旧版本）。

---

## 7. CI

`.github/workflows/` 下的工作流：`ci.yml`、`concurrency.yml`、`client-mod.yml` 在 push 到 `main` 与每个 PR 上触发，`client-gametest.yml` 的触发见它那一条；GitHub 运行器直连 Maven Central，**不用**镜像 init 脚本。

- `ci.yml`：每个 job 都是 temurin JDK 25、`gradle/actions/setup-gradle` 缓存（非 `main` 只读），都带 PostgreSQL 16 与 Valkey 8 服务容器并设 `SYNAXIS_TEST_PG_URL` 等四个变量（设了变量而连不上库时数据库用例失败，不跳过）。
  - job `check`（「Check」）：跑 `./gradlew check jacocoTestReport -Dsynaxis.synx.require-frozen-zlib=true`，再查 store 包 7 个数据库测试类的结果都是 `skipped="0"`，上传测试报告与 JaCoCo XML（保留 14 天），然后跑 ABI 门（§7.1）；PR 上另跑 `diff-cover` 对新增行做 80% 覆盖率门（需要 `fetch-depth: 0`）。本地等价：`./gradlew check jacocoTestReport -Dsynaxis.synx.require-frozen-zlib=true --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts`，再 `diff-cover $(find . -path '*/build/reports/jacoco/test/jacocoTestReport.xml' | sort) --compare-branch=origin/main --src-roots src/main/java testing/src/main/java protocol/src/main/java plugin-api/src/main/java server-app/src/main/java game-rts/src/main/java play-kit/src/main/java game-rush/src/main/java synx/src/main/java baker/src/main/java --fail-under=80`（`pip install diff-cover`）——覆盖率门读**全部模块**的报告，少一个 `--src-roots` 就会让那个模块的新增行在本地隐形、与 CI 得出不同的绿。
  - job `multithreaded`（「Test with 4 tick threads (strict)」，`docs/DESIGN-PROD.md` `REQ-PROD-019`）：`./gradlew test -Dsynaxis.dispatcher-threads=4 -Dsynaxis.acquirable-strict=true`，全部测试在 4 条 tick 线程（`counter` 分配）与所有权断言下再跑一遍；随后在 `:testing` 的 `EnvDispatcherThreadsIntegrationTest` 结果里查 `env dispatcher threads: 4`，构建不再把这两个属性转给测试 JVM 时这一步红，不会在 1 条线程上照绿。
  - job `multithreaded-grid`（「Test with 4 tick threads (strict, grid)」，`REQ-PROD-020`）：同一条命令再加 `-Dsynaxis.thread-provider=grid`（分片用的 16×16 区块分格），结果里另查 `env thread provider: grid`。

  这些 job 的本地等价都要数据库：四个变量与本机共用容器 `synaxis-pg` / `synaxis-valkey` 见 `docs/TEST.md` §4「第 3 波 3A」一节的数据库前置，不设变量时数据库用例跳过，与 CI 得出不同的绿。严格 job 的本地等价是各自的命令加 `--no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts`。
- `concurrency.yml`：job `jcstress`，`./gradlew :jcstress-tests:jcstress -Dorg.gradle.isolated-projects=false -Dorg.gradle.configuration-cache=false`，该插件不支持这两项；报告在 `jcstress-tests/build/reports/jcstress/`。本地快速模式把 `jcstress-tests/build.gradle.kts` 的 `mode` 临时设为 `quick`。
- `client-mod.yml`：客户端 Fabric 模组（`REQ-M2-020b`），job `mod`。三步——`env -u CI ./gradlew :protocol:publishToMavenLocal` → `client-fabric` 里 `./gradlew build` → 上传模组 jar 与测试报告。三处与别的工作流不同：① **`env -u CI`**，发布约定在 `CI` 存在时强制签名，而这个 job 没有 GPG 私钥也不需要（工件不出 runner），排掉 `signMavenPublication` 不管用——`.asc` 是配置期挂进发布的；② 它**单独一个工作流**，因为 Loom 第一次要下载 Minecraft，不该让引擎的 `check` 跟着等、也不该让 Fabric 的仓库抖动把引擎门禁染红（Loom 的缓存在 `~/.gradle/caches/fabric-loom`，正落在 `setup-gradle` 默认缓存的 `caches` 里）；③ 客户端构建自己声明 `maven.fabricmc.net`，本机跑它要带代理（镜像 init 脚本里没有这个仓库）。本地等价：`./gradlew :protocol:publishToMavenLocal --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts`，再 `cd client-fabric && ./gradlew build --no-daemon`。客户端 `check` 里的两道门：§24 的双端向量（`FrozenVectorTest` 读 `protocol/src/test/resources/vectors/` 本身，`PlayFrozenVectorTest` 读系统属性 `synaxis.play-vectors.dir` 指向的 `play-vectors/`）与 `verifyModJar`（模组 jar 的版本钉死、资源、JiJ 的 `:protocol`、不含引擎类）。
- `client-gametest.yml`：真客户端（`docs/DESIGN-PROD.md` `REQ-PROD-030`、`REQ-PROD-031`，报告与判定见 `API-PROD-026`）。触发：PR 只在改了 `client-fabric/**`、`protocol/**`、`server-app/**`、`game-rts/**`、`game-rush/**`、`play-kit/**`、`plugin-api/**`、引擎 `network` / `listener` 包或这个工作流时跑；push `main`；每日 UTC 18:00；每周日 UTC 19:00（只跑 P10）；手动 `workflow_dispatch`（可选握手阶段，`p10: true` 时只跑 P10）。
  - job `gametest`（「Run the client gametests」）：Xvfb 与 Mesa llvmpipe 里起真 26.2 客户端，连游戏模式的 `server-app`（在线模式，会话服务是本 job 起的假 sessionserver，`record` 模式），跑回归断言 R1～R8，再跑可玩项剧本（`PlayableClientGameTest`，`docs/DESIGN-PROD.md` §19 可玩项矩阵与 `API-PROD-026`）：平坦核心十步连本 job 另起的一台平坦核心 `server-app`（`installDist` 的 `bin/server-app`，工作目录 `$RUNNER_TEMP/playable`，端口 25566，同一个假 sessionserver），RTS 五步连游戏模式那台，每步一行写进 `synaxis-gametest/playable.txt`；PR 只用生产模组 jar 起一次，其余触发 dev 与生产 jar 各一次，每日那次另跑 spike。判定读报告、台账与服务端日志（`client-fabric/gametest-env/judge.sh`，设了 `CORE_CONNECTIONS` 时连 `playable.txt` 一起判），不看 Gradle 的退出码；runner 钉在 `ubuntu-24.04`，时限 40 分钟。每周 P10 那次与手动 `p10: true` 不跑它。
  - job `rush`（「Run the rush script」，样板玩法 `rush` 的真客户端剧本，`docs/DESIGN-PLAY.md` §6.7.6）：触发同 job `gametest`，与它并行；PR 也用 dev 与生产模组 jar 各起一次（§26 S1 的退出条件是两次启动）。先一次构建夹具的 classpath、`installDist` 与 `:game-rush`、`:play-kit` 两个 jar，再起三个进程：假 sessionserver（`record` 模式）、服务端测试源码集里的夹具 `PlayHudFixture`（`$RUNNER_TEMP/fixture`，离线，端口 25568）、样板服务端 `./gradlew :server-app:runRush`（仓库根，在线模式经假 sessionserver，白名单 `rush`，端口 25567，五个 `synaxis.rush.*` 取 CI 值）。客户端设 `SYNAXIS_PLAY_FIXTURE_SERVER` 与 `SYNAXIS_RUSH_SERVER` 跑 `RushClientGameTest`：Q0a～Q0c 连夹具（组件解码、换实例的逐 tick 读数、客户端小部件），Q1～Q9 连样板服务端独自打一局，从大厅排队、进竞技场、占信标、结算回到大厅。判定（`client-fabric/gametest-env/rush_judge.py`，只用标准库，单测 `test_rush_judge.py` 在同一 job 里先跑）读两份 `synaxis-gametest/rush.txt`、样板服务端的台账 `logs/events-*.jsonl` 与日志 `rush-server.log`、sessionserver 日志和模组主源码：每步都要 PASS，竞技场五个状态按序且 `prepare_ms` ≤ 1,000，每次移动 5 s 内就绪，两局都以 `time_limit` 结束，日志没有 ERROR，模组主源码里没有 rush。之后在 `$RUNNER_TEMP/rollback` 演练 `docs/DESIGN-PLAY.md` §23：同一个 classpath（`lib/*` 加两个 jar）先以 `rush`、再以 `rts` 起服（离线，端口 25569）。截图、报告、客户端日志、样板服务端的日志与台账、夹具日志、演练日志与 sessionserver 日志作为 artifact `client-gametest-rush` 上传；runner 钉在 `ubuntu-24.04`，时限 30 分钟，整个 job 约 7 分钟（RUN-077 组 G 实测 6 min 56 s）。
  - job `p10`（「Two shards, 100 round trips (P10)」）：只在每周日定时与手动 `p10: true` 时跑。两个目录模式分片（PostgreSQL 16 与 Valkey 8 服务容器）之间经管理接口 `synaxis:transfer` 把客户端来回送 100 次，`client-fabric/gametest-env/p10.py` 驱动并判定（客户端首次进服之前先往库里给它的角色写好存档，判每个世界的背包都等于这份存档、第 1 个世界的位置与存档位置相距 ≤ 0.5 格）；时限 60 分钟。
  - job `p11-smoke`（「Two shards, 2 round trips (P11 smoke)」，可玩项 P11）：PR 与 push `main` 时跑，与 job `gametest` 并行。步骤、服务容器与判定照抄 job `p10`，驱动与判定都加 `--round-trips 2`（4 次转服）；时限 40 分钟。`p10` 与它都钉在 `ubuntu-24.04`。

  本地没有一条命令的等价：客户端在 `client-fabric/gametest-env/` 的无头容器里跑，R1～R8 按 `docs/TEST.md` `TC-PROD-130`（CI 跑的完整正版路径档），P10 按 `TC-PROD-550`（P11 冒烟同一套，驱动与判定加 `--round-trips 2`）。在 CI 上跑某个分支：`gh workflow run client-gametest.yml --ref <分支>`，只跑 P10 再加 `-f p10=true`。
- `farview-gametest.yml`：远景的容器用例（`docs/DESIGN-M3.md` §26 M3-G「容器验收」与 M3-S「轨道 C 的四项容器规格」，`docs/TEST.md` `TC-WORLD-117`～`TC-WORLD-120`、`TC-WORLD-140`～`TC-WORLD-143` 与 `light` 组的 `TC-WORLD-095`），job `farview`：在运行器上起 `FarViewShardFixture`（服务端测试源码集，经 `:server-app:farviewFixtureArgs`）与 `bin/server-shard`，再用装了 Distant Horizons 的真客户端跑 `farview/FarViewClientGameTest`，Xvfb 与 Mesa llvmpipe 同 `client-gametest.yml`；每个用例按 `farview-report.txt` 的 F 行判定，报告、截图、客户端与夹具日志作为 artifact 上传；矩阵按 `synthetic`、`baked`、`hidden`、`light` 分组，`light` 组起 `LightShardFixture`（经 `:server-app:lightFixtureArgs`），用不装 Distant Horizons 的真客户端跑 `light/LightClientGameTest` 的 `default` 模式（`docs/TEST.md` `TC-WORLD-095` 第 5～11 步），按 `light-report.txt` 的 L 行判定；并发组 `farview-gametest-<ref>`；不用 secrets。只手动触发，不挂 push，也不挂自动触发的 PR：给 PR 打标签 `farview-gametest`（`pull_request: types: [labeled]`，打别的标签只到 job 的 `if` 就跳过），或这个文件进了 `main` 之后 `workflow_dispatch`（输入夹具模式、gametest 模式、`dayTime` 与期望的太阳高度角，跑一个用例）。重跑用 `gh run rerun`，或摘下标签再打上。本地等价是 `client-fabric/gametest-env/` 的无头容器，按 `docs/TEST.md` 那几条用例的命令。

`check` 里的三道门：Error Prone（约 96 项，`-Werror`）、`forbiddenImportsCheck`、`testNamingCheck`（测试类名必须与其夹具一致：`@EnvTest` 的类以 `IntegrationTest` 结尾，注册表夹具以 `RegistriesTest` 结尾；该任务是增量任务，只有干净构建目录或 `--rerun-tasks` 才全量扫），外加 `ArchitectureTest`：ArchUnit **严格**分层规则——底层集合 `codec、condition、coordinate、exception、gamedata、monitoring、thread、timer、utils` 不得依赖集合之外的引擎包，唯一豁免是根包 `JvmFlags`（零引擎依赖、只读系统属性）；任何新增反向边直接让构建红，没有冻结存储。同一个 `ArchitectureTest` 还有第二条规则「引擎不得依赖游戏侧、稳定面与宿主」——禁止 `io.github.bearisbug.synaxis.game..`、`io.github.bearisbug.synaxis.plugin..`、`io.github.bearisbug.synaxis.app..` 三个完整前缀（`REQ-M2-002`；写完整前缀是因为 `..plugin..` 会误命中引擎自己的 `network.plugin`）。第三条规则「客户端可驱动的路径不得逐包写日志」见 §8。`python3 tools/pkgdeps.py` 打印顶层包依赖矩阵与「底层出向上层边数」，改动包结构后先跑它，且**按模块分别跑**（每个模块一条命令，见 §3.6）。`:plugin-api` / `:protocol` 已接 ABI 门（`ci.yml` 的 `ABI gate of the stable surface` 步骤跑 `:plugin-api:checkBinaryCompatibility :protocol:checkBinaryCompatibility`）：打出第一个 tag 之前没有 baseline，不传 `-PbaselineJarDir` 时两个任务以 Gradle 的 `SKIPPED` 结束并打印「无 baseline，ABI 门未生效」、构建照常绿，传了而 baseline jar 缺失则失败（引导规则见 `docs/DESIGN-M2.md` §24）；引擎根模块不接这道门。**这道门在有 baseline 之前一次都没真跑过，绿灯不代表它有齿**——打第一个 tag、归档 baseline jar、让 CI 开始传参的完整流程，以及本地不靠 tag 就能跑同一道门的口径，见 §7.1。`check` 里**唯一一条挂墙钟的断言**是 `:game-rts` 的 `MatchIntegrationTest#theFullSizeMapGenerates`（512×512 / 1,024 区块的整图生成，`load_ms < 3000`，`docs/DESIGN-M2.md` §15 的预算、§26 阶段 3 的退出判据）。本机实测 0.7～1.2 s，余量三四倍；CI 机器被压满时它是最先红的那条——**先怀疑机器负载，别当功能回归查**。


### 7.1 ABI baseline：打第一个 tag 与让门禁真的跑起来

`checkBinaryCompatibility` 的 baseline 是**我们自己上一个 tag 的 jar**。仓库里那 50 个 tag 全部继承自上游 Minestom，一个都不能当 baseline——拿它们比对等于拿别人的 ABI 约束我们自己。

**CI 的开关只有一个文件**：`gradle/abi-baseline.tag`，内容是一行 tag 名。文件不存在 = 还没有 baseline，`ci.yml` 不传 `-PbaselineJarDir`，两个任务显式 `SKIPPED`、工作流仍绿；文件存在 = CI 先用 `gh release download` 把该 tag 的 `plugin-api.jar` 与 `protocol.jar` 取到 `.abi-baseline/`，再带 `-PbaselineJarDir=.abi-baseline` 跑门禁。传参之后「缺失即失败」自动生效：资产没上传、名字不对或下载失败，都是红，不会退回 `SKIPPED`。

**打第一个 tag 的动作**（验收人做）：

1. 在要打 tag 的提交上构建稳定面的 jar：`./gradlew :plugin-api:jar :protocol:jar --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts`，产物是 `plugin-api/build/libs/plugin-api-<版本>.jar` 与 `protocol/build/libs/protocol-<版本>.jar`。
2. `git tag -a <tag> -m "<一句话>" && git push origin <tag>`。
3. 归档 baseline jar：两个 jar **必须重命名成 `plugin-api.jar` 与 `protocol.jar`**（门禁按 `<baselineJarDir>/<模块名>.jar` 找文件，名字不对等于 baseline 缺失），作为该 tag 的 release 资产上传——`gh release create <tag> --title <tag> --notes "<一句话>" plugin-api.jar protocol.jar`（release 已存在就用 `gh release upload <tag> plugin-api.jar protocol.jar`）。
4. 新建 `gradle/abi-baseline.tag`，写入那个 tag 名并提交。**这一步是 CI 从 `SKIPPED` 切到真检查的那一刻**，合入前先按下面的本地口径自查一遍。
5. 之后每打一个 tag 重复 1～3，并把 `gradle/abi-baseline.tag` 的内容改成新 tag。

**本地跑同一道门**（不需要 tag，改稳定面签名之前先跑它；`docs/TEST.md` TC-GAME-006 就是这个形态）：

```bash
./gradlew :plugin-api:jar :protocol:jar --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts
mkdir -p .abi-baseline
cp plugin-api/build/libs/plugin-api-*.jar .abi-baseline/plugin-api.jar
cp protocol/build/libs/protocol-*.jar .abi-baseline/protocol.jar
# 改代码，然后：
./gradlew :plugin-api:checkBinaryCompatibility :protocol:checkBinaryCompatibility -PbaselineJarDir=.abi-baseline --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts
```

`-PbaselineJarDir` 的相对路径按**仓库根**解析，绝对路径照原样用；`.abi-baseline/` 不进仓库（`.gitignore` 的 `*.jar` 已经盖住它），验完删掉。

三种结局，照着认：

- **不传** `-PbaselineJarDir`：任务 `SKIPPED` + 「无 baseline，ABI 门未生效」，构建绿——这是 tag 之前的常态，不是门禁通过。
- **传了且 baseline jar 在**：任务真的执行（输出里是 `> Task :plugin-api:checkBinaryCompatibility` 而**没有** `SKIPPED`，尾部计数含 `2 executed`）。破坏性变更打 `Public/protected method removed or signature changed: <类>#<方法><描述符>` 并以 `ABI Check failed with N binary compatibility violations.` 让构建红；只加 `default` 方法这类兼容变更不报（给接口加**抽象**方法会报 `New abstract method added to interface`，那对自己实现它的插件是破坏；带 `@ApiStatus.NonExtendable` 的接口不查这一项，见下）。
- **传了但 baseline jar 不在**：任务失败，`Baseline JAR does not exist: <路径>`。这是设计要的结果，不是配置出错。

改稳定面签名踩到红灯时，走 `docs/DESIGN-M2.md` §8「演进与弃用」的破坏性变更流程（新方法并行 + 旧方法 `@Deprecated`），不要去放宽门禁。

**给已有的类或接口加 `@ApiStatus.NonExtendable`（或改成 sealed）本身就是一条违例**：`Class made non-extendable (sealed or @NonExtendable): <类>`，对自己实现了它的插件这确实是破坏。门禁没有豁免清单，这类改动只能随一次破坏性升级走：`PluginApi.API_VERSION` 加 1（常量值变化同样报 `Constant field value changed`），按上面第 1～3 步打新 tag、上传两个 jar，再把 `gradle/abi-baseline.tag` 改成新 tag，旧 baseline 下的这批违例由新 baseline 吸收（`v0.1.0` → `v0.2.0` 就是这样，`docs/DESIGN-PROD.md` §1）。已标注的接口之后加抽象方法不报；`:plugin-api` 哪些接口标了、依据是什么，见 `docs/DESIGN-PROD.md` §8。

**每次合入 `main` 之后都要看 `main` 上这一步的结果**，PR 上的绿不够：PR 只证明该分支对当时的 baseline 不破坏，几组改动各自在本地拿中间提交当 baseline 比对、各自都绿，合进 `main` 之后对 `gradle/abi-baseline.tag` 指的 tag 照样可能红。`3914650305` 给 `PluginEvents` 加了两个抽象方法，第 4 波的本地门禁都拿 `3914650305` 或更晚的提交当 baseline，所以都没看到，`main` 的 ABI 门从那个提交起一直红，直到 `v0.2.0`。

---

## 8. 写日志的纪律：日志量不由客户端决定

2026-09-18 的压测实测：一条「每个客户端包写一行」的 WARN 在 15 分钟里写了 662,803 行 / 84 MiB。这不是日志级别的问题，是**这条日志写多少次由对端说了算**。所以加日志之前先问一句「这一行的频率由谁决定」：

- **由启动、注册表数据、配置或停机决定** —— 进程生命周期内次数固定，随便写。
- **由连接数决定** —— 每连接至多一行，且必须与一次断开在同一分支上（`PlayerSocketConnection` 的协议错误就是这个形状；`LoginListener` 收到陌生的登录插件应答时不写自己的行，每连接一次的报告只交 `handleException`、与踢人在同一分支，宿主的异常处理器再按异常类经 `LogBudget` 限量）。**「断开」要真的把活停下来才算数**：一次读上来的是一整批包，`PlayerSocketConnection#processPackets` 每派发一个包前先看连接还在不在，不在就 `break`，否则排在这次 kick 后面的几千个帧照样一个个交给监听器，「每连接一行」就变成「每包一行」（实测一批 4,000 帧，见 `docs/TEST.md` TC-035）。
- **由客户端发了什么、发了多少决定** —— 必须有界，三选一：
  1. **按时间限流**，样板是 `EntityTrackerImpl#reportUnregisteredMove` 与 `PacketReading#reportUnreadBytes`：一个 `AtomicLong` 记区间内累计次数、一个 `AtomicLong` 记上次输出时刻，CAS 抢到的那一次才写，并把累计次数带进那一行（**量被压掉了，但量还看得见**）。计数器只在「本来就要写日志」的那条分支上碰，解码热路径不为它付钱。宿主与游戏侧同一形状打包成了 `plugin-api` 的 `LogBudget`（标 `@ApiStatus.Internal`，不进 ABI 门；住在稳定面模块是因为宿主与插件坐在同一条客户端可控路径上，而插件不能依赖宿主）（同样是 10 秒 + 行内计数），带栈的报告也走它：一个区间里只有第一条留栈，其余次数进计数、由下一次触发写出的那一行报出来（洪水停在区间中途时积压的计数就等下一次触发），**预算按宿主注册过什么切**（每个频道注册一份、每个事件监听器一份），客户端能相乘的那一维（它自己编的 channel id）共用一份，事件台账那处整份文件共用一份。
  2. **按类型去重**，样板是 `PacketListenerManager` 的 `missingListenerWarnings`：客户端加不了包类型，所以一种一次就封顶了。
  3. **紧跟一次断开**，见上一档。

这条纪律有门禁，**每个模块一份**：引擎的 `ArchitectureTest#clientDrivenPathsDoNotWriteALinePerPacket` 禁止 `network..` 与 `listener..` 两个包树、宿主的 `ClientDrivenLogArchitectureTest#hostPathsDoNotWriteALinePerPacket`（在 `:server-app` 自己的测试源集里）禁止 `io.github.bearisbug.synaxis.app..`、游戏侧的 `ArchitectureTest#gameSidePathsDoNotWriteALinePerPacket`（在 `:game-rts` 自己的测试源集里，随 `REQ-M2-009` 的 `rts:game` 通道一起加）禁止 `io.github.bearisbug.synaxis.game..`，三条规则的禁止目标相同：`Logger#warn` / `Logger#error` / `Throwable#printStackTrace` / `ExceptionManager#handleException`。**一条规则守不了两个模块**：ArchUnit 扫的是自己模块 classpath 上的类，引擎的测试源集里没有宿主的类。**`handleException` 也在禁止之列**，因为引擎默认的处理器就是 `Throwable::printStackTrace`：宿主决定这份报告落到哪里，调用点决定它写多少次。豁免名单是 `PacketReading`、`PlayerSocketConnection`、`Server`、`ConnectionGate`、`SessionClient`、`ConnectionManager`、`PacketListenerManager`、`LoginListener`，每一条都在 `boundedReporters()` 的 Javadoc 里写明自己的上界。**规则守的是这两棵树里的类，不是「客户端可达的路径」**——差在哪见下面的盲点 2。新加一个类要写 WARN 就会红——**红了先想怎么让它有界，别直接进豁免名单**。`theClientDrivenLogRuleRejectsALinePerPacket` 与 `theClientDrivenLogRuleRejectsAReportPerPacket` 用测试源集里的两个 fixture 反证这条规则真的会红（一个写 WARN，一个把异常交给宿主）。宿主那条的豁免名单是 `Main`、`PluginHost`、`AdmissionGate`、`CrashReports`、`PlayerStore`、`ShardSession`、`TicketVerifier`、`ShutdownSequence`、`ShardWorld`、`ShardHeartbeat`，各自的上界同样写在它那份 `boundedReporters()` 的 Javadoc 里；`PluginChannelRouter`、`PluginEventsImpl`、`PluginSchedulerImpl`（插件按包投递 `runOn`，任务数由客户端决定）与 `EventLedger` **不在**名单里——它们经 `LogBudget` 写，所以盲点 1 对它们不成立；**名单里的类照样是按类豁免的**，往它们里面再加一行客户端可驱动的 WARN / ERROR 门禁仍然是绿的（实测：在 `AdmissionGate` 里挂一个 PLAY 期事件的监听器写一行 `LOGGER.warn`，`hostPathsDoNotWriteALinePerPacket` 不红）。它的反证 fixture 是 `UnboundedHostLogFixture`。游戏侧那条的豁免名单只有 `Match`（一局至多两行，且发生在 bind 之前），`GameChannel` **不在**名单里——它的三条线（未知 `msg_id`、协议错误、洪水）都经 `LogBudget` 输出，因此在它旁边加一行直写的 WARN 会让门禁变红（实测），反证 fixture 是 `UnboundedGameLogFixture`。台账那处容易被算成「每次插件启用一行」而放进豁免名单：`PluginAnalytics` 把 `record` 交给了每个插件，`DESIGN-M2` §20 又要求逐条指令记事件，所以它的频率是客户端定的——**判频率看的是谁能触发它，不是宿主自己调了几次**。

盲点七个，写在这里免得被门禁的绿骗了：

1. **三条规则的豁免都是按类不按调用点** —— 往三份名单里任何一个类再加一条无界的行都不会红。不在名单里的类躲开了这一条：它们直写一行 WARN / ERROR 就红，要报只能经 `LogBudget`（宿主的 `PluginChannelRouter`、`PluginEventsImpl`、`EventLedger`，游戏侧的 `GameChannel`，见上）。
2. **包清单只到 `network..` / `listener..`，PLAY 态的包不在这两棵树里派发** —— `PlayerSocketConnection` 只内联派发 `IMMEDIATE_PROCESS_PACKETS` 那 12 个即时包，其中 PLAY 态收得到 keep-alive、ping、cookie 应答与 configuration ack 四个，都在读线程处理，不派发 `PlayerPacketEvent`；其余一律进 `Player#addPacketToQueue`，由 `entity` 包的 `Player#interpretPacketQueue` 每 tick 取 `playerPacketPerTick` 个（默认 50）交给监听器。`entity..` 不在清单里，实测在 `Player#addPacketToQueue`（每个客户端包调用一次）插一行 `LOGGER.warn`，`clientDrivenPathsDoNotWriteALinePerPacket` 仍然是绿的。**在 `Player`、`Entity` 里加逐包日志，门禁看不见，靠评审。**
3. **`command` 包不在清单里** —— `CommandParserImpl` 执行命令抛异常时每次一行带栈的 ERROR。
4. **`:plugin-api` 没有门禁** —— 三条规则守的是引擎根模块、`:server-app` 与 `:game-rts`，而 `LogBudget` 自己住在 `:plugin-api`（宿主与插件要用同一个上界，插件又不能依赖宿主）。该模块今天只有它这一个写日志的类，而它本身就是那个上界，所以没有可守的对象；往这个模块里再加一个写 WARN 的类，三条规则都看不见。真要给它加一份，只能靠 `docs/DESIGN-M2.md` §28 登记的那个「把所有模块当 `testImplementation` 的聚合项目」，M2 不做。
5. **「监听器抛了异常」每包一次 `handleException` 有两处，登录前已封，configuration 与 play 态仍敞着** —— `PlayerSocketConnection#processBatch`（豁免名单里的那一个）在一批包的起始状态早于 configuration 时，监听器一抛异常就断开，不看 `rejectMisusedPacket`；从 configuration 起，它与 `Player#interpretPacketQueue`（包清单外的那一个，管 play 态的主路径）一样，只有 `rejectMisusedPacket`（默认关）才会在同一分支断开。
6. **三条规则都只认 `Logger#warn` / `Logger#error` 这两个方法名** —— SLF4J 的流式写法 `LOGGER.atWarn().log(…)` 绕得过去：`atWarn` 名字不匹配，`log` 的宿主类型是 `LoggingEventBuilder` 而不是 `Logger`（实测：在不在豁免名单里的 `PluginChannelRouter#route` 插一行 `LOGGER.atWarn().log(…)`，宿主门禁仍然是绿的）。
7. **宿主那条规则的 import 与包谓词都只到 `io.github.bearisbug.synaxis.app..`** —— 今天 `:server-app` 的主源码全在这棵树里，所以没有实际漏扫；将来在该模块下新开一个平级顶层包（例如 `io.github.bearisbug.synaxis.hosting`），既不会被 import 也不在谓词里，会静默失明。

④ 登记在 `docs/DESIGN-M2.md` §24 的规则⑤，其余六条登记在 `docs/DESIGN.md` §28。
