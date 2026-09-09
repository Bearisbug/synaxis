# game-engine 运行手册

面向一台**只装了 git 与 Homebrew 的 macOS 机器**：照本文从上到下执行，就能拿到一个可构建、可测试、可启动的引擎。每一步都是可直接粘贴的命令，不需要问任何人。

对应需求 `REQ-012`，验收用例 `docs/TEST.md` 的 `TC-016`。

---

## 1. 一次性环境准备

### 1.1 安装 JDK 25

引擎钉死 JDK 25（`build-src/src/main/kotlin/minestom.java-library.gradle.kts` 里的 toolchain），低版本编译不过。

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
git clone https://github.com/Bearisbug/game-engine.git
cd game-engine
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
./gradlew :test --tests 'dev.gameengine.instance.UnloadChunkIntegrationTest' \
  --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts
```

`--tests` 支持通配，例如 `--tests '*AnvilLoader*'`。注意：`CommandPacketFilteringTest` 单跑必挂（依赖别的 `@EnvTest` 先初始化静态 `serverProcess`），只在全量运行下有效，见 `docs/TEST.md` §7。

### 3.4 启动服务端

```bash
./gradlew :smoke:run --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts
```

`smoke` 是最小启动器：起进程、开一个平坦世界、注册 `game:main` 自定义负载通道的 echo 处理器。监听 `127.0.0.1:25565`，离线模式，任意用户名可进。

另开一个终端探活：

```bash
nc -z 127.0.0.1 25565 && echo "server is up"
```

在服务端 stdin 输入 `stop` 回车即可停服（10 秒内退出）。

> `smoke` 模块由 `REQ-008` 引入。在它合入之前，等价的启动入口是 `./gradlew :demo:run --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts`。

### 3.5 离线构建（验证依赖已全部冻结在本地）

```bash
unset JAVA_TOOL_OPTIONS
./gradlew check --offline --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts
```

前提是依赖已经进过一次本地 Gradle 缓存（先按 §3.1 联网跑一遍）。注册表数据已经 vendoring 进仓库（`REQ-001`），不再有 `net.minestom:data` 这条外部数据链。

离线时仍然要带镜像 init 脚本：`--offline` 已经保证零网络访问，而 Gradle 的依赖缓存是**按来源仓库匹配**的——依赖当初是从阿里云镜像下下来的，仓库列表里没有这个镜像就会误报 `No cached version ... available for offline mode`。

---

## 4. 分支策略

- **`main` 是唯一主干**，永远可构建、可测试。
- 本仓库是 Minestom 的**硬 fork**，已经和上游断开：没有 `upstream` 远程，不 rebase 上游，不接受上游 PR。`git remote -v` 只应该看到 `origin`（`https://github.com/Bearisbug/game-engine.git`）。
- 干活开功能分支（例如 `freeze-data`、`fix/entity`），完成后合回 `main`。
- 合并前必须本地 `./gradlew check` 全绿（§3.1）。
- commit message 只描述代码变更本身，**禁止任何 AI 署名 / co-author trailer**（见根目录 `CLAUDE.md`）。
- 功能改动必须先动 `docs/DESIGN.md`，再改代码，再同步 `docs/TEST.md` 的用例与执行记录。

---

## 5. 目录地图

| 路径 | 内容 |
| --- | --- |
| `src/main/java/` | 引擎主源码，包名 `dev.gameengine`（占位名，REQ-009 已改；再改一次同样是一次性脚本操作） |
| `src/main/java/.../registry`、`EntityType`、`Block` 等常量表 | 原为生成物，自 REQ-008 起作为普通源码维护；文件头有说明 |
| `src/main/resources/data/` | **vendoring 的注册表数据**：39 个 JSON + `opaque/` 下 23 个黄金 NBT（REQ-007）；运行时由 `RegistryData.loadRegistryFile` 按 `/data/<路径>` 从 classpath 读取 |
| `src/main/resources/META-INF/native-image/` | GraalVM 可达性元数据（资源 glob 指向 `data/*.json`） |
| `src/test/` | 单元与集成测试 |
| `testing/` | `@EnvTest` 测试夹具模块，被 `src/test` 依赖 |
| `demo/` | 上游遗留的示例启动器（`REQ-008` 会被 `smoke` 取代） |
| `build-src/` | Gradle 约定插件（toolchain、Error Prone、发布、ABI 检查） |
| `gradle/init/` | 构建用的临时 init 脚本，目前只有阿里云镜像脚本，见 §2.1 |
| `gradle/libs.versions.toml` | 版本目录：所有第三方版本与 `minecraft = "26.2"` 都钉在这里 |
| `tools/` | 运维与验收脚本，协议探针在 `tools/protocol-probe/probe.py` |
| `docs/DESIGN.md` | 设计文档，单一事实源 |
| `docs/TEST.md` | 测试文档，验收执行脚本与轮次台账 |
| `docs/ENGINE.md` | 本文 |
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

- `ci.yml`：temurin JDK 25，`gradle/actions/setup-gradle` 缓存（非 `main` 只读），跑 `./gradlew check jacocoTestReport`，上传测试报告与 JaCoCo XML（保留 14 天）；PR 上另跑 `diff-cover` 对新增行做 80% 覆盖率门（需要 `fetch-depth: 0`）。本地等价：`./gradlew check jacocoTestReport --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts`，再 `diff-cover build/reports/jacoco/test/jacocoTestReport.xml --compare-branch=origin/main --src-roots src/main/java --fail-under=80`（`pip install diff-cover`）。
- `concurrency.yml`：`./gradlew :jcstress-tests:jcstress -Dorg.gradle.isolated-projects=false -Dorg.gradle.configuration-cache=false`，该插件不支持这两项；报告在 `jcstress-tests/build/reports/jcstress/`。本地快速模式把 `jcstress-tests/build.gradle.kts` 的 `mode` 临时设为 `quick`。

`check` 里的三道门：Error Prone（约 96 项，`-Werror`）、`forbiddenImportsCheck`、`testNamingCheck`（测试类名必须与其夹具一致：`@EnvTest` 的类以 `IntegrationTest` 结尾，注册表夹具以 `RegistriesTest` 结尾；该任务是增量任务，只有干净构建目录或 `--rerun-tasks` 才全量扫），外加 `ArchitectureTest`：ArchUnit **冻结**规则——底层 15 包对上层的既有违规记录在 `src/test/resources/archunit_store/`，`allowStoreUpdate=false` 意味着任何**新增**反向边都会让构建红，CI 永不改写存储；要重新生成存储是有意为之的本地动作（临时把 `allowStoreCreation`/`allowStoreUpdate` 设为 `true`，跑一次，再改回）。`checkBinaryCompatibility` 任务保留但不在 CI 里，等我们打出第一个 tag 作为基线再启用。
