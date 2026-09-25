# 写一个 Synaxis 插件

**插件不支持热加载与热卸载：改插件 = 重启进程**（`docs/DESIGN-M2.md` `ADR-M2-007`）。下面每一步都是可以直接粘进终端执行的命令，仓内 `example-plugin/` 就是这一页的成品。

前置：JDK 25（`export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`），以及一份本仓库的 clone。

## 1. 先出一份服务端发行版

插件要编译就得看得见稳定面 `:plugin-api`。在还没有发布到 Maven 之前，服务端发行版的 `lib/` 就是那份 classpath：

```bash
./gradlew :server-app:installDist --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts
```

产物在 `server-app/build/install/server-app/lib/`，运行时用的也是同一批 jar。

## 2. 建一个独立的 Gradle 构建

插件是独立工程，不进服务端的 `settings.gradle.kts`。两个文件：

`hello-plugin/settings.gradle.kts`

```kotlin
rootProject.name = "hello-plugin"
```

`hello-plugin/build.gradle.kts`

```kotlin
plugins {
    java
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

dependencies {
    // 只编译期可见：运行时这批 jar 已经在服务端的 classpath 上，不要打进插件 jar
    compileOnly(fileTree("<服务端仓库路径>/server-app/build/install/server-app/lib") { include("*.jar") })
}
```

## 3. 写插件类

`hello-plugin/src/main/java/com/example/helloplugin/HelloPlugin.java`：实现 `SynaxisPlugin` 的三个方法即可，完整代码见仓内 `example-plugin/src/main/java/com/example/helloplugin/HelloPlugin.java`。三条硬规则：

- **无参构造器里禁止注册任何东西**。宿主先 new 出实例只为读 `descriptor()`，一切注册都在 `onEnable`。
- `descriptor()` 的 `id` 必须匹配 `[a-z0-9][a-z0-9_-]{0,63}` 且不得是 `synaxis`；`api_version` 必须等于 `PluginApi.API_VERSION`（当前 `2`）。三者任何一条不满足，宿主拒绝加载并以非零码退出，端口不会 bind。
- `onDisable()` 里只管自己持有的东西。事件节点、调度任务、通道、准入回调由宿主统一摘掉。

## 4. 让 ServiceLoader 找得到它

`hello-plugin/src/main/resources/META-INF/services/io.github.bearisbug.synaxis.plugin.SynaxisPlugin`，内容是一行全限定类名：

```
com.example.helloplugin.HelloPlugin
```

## 5. 构建

```bash
./gradlew -p example-plugin build --no-daemon     # 仓内示例
# 自己的工程：cd hello-plugin && <服务端仓库路径>/gradlew build --no-daemon
```

自己的工程借用服务端仓库的 Gradle wrapper，不用另装 Gradle。产物是 `example-plugin/build/libs/hello-plugin.jar`（自己的工程是 `hello-plugin/build/libs/hello-plugin.jar`）。

## 6. 带着插件起服

把插件 jar 追加到服务端的 classpath：

```bash
java -Dsynaxis.app.auth=offline \
  -cp "server-app/build/install/server-app/lib/*:example-plugin/build/libs/hello-plugin.jar" \
  io.github.bearisbug.synaxis.app.Main
```

`-Dsynaxis.app.auth=offline` 让服务端不向 Mojang 验证账号。`server-app` 默认在线模式（`docs/DESIGN-PROD.md` `API-PROD-001`），第 8 节的探针没有正版账号，在线模式下它一收到加密请求就退出（`EncryptionRequest received: the probe needs an offline server`）。离线模式下谁都能用任何名字登录，只用于本机开发，对外开服不要带这个参数。

就绪日志是 `synaxis server-app listening on 0.0.0.0:25565`，它之前会出现 `Plugin enabled: plugin_id=hello ...` 与插件自己的打印。stdin 输入 `stop` 停服，停机日志里会出现 `hello-plugin is disabling`。

只想启用一部分插件时用 `-Dsynaxis.app.plugins=hello,rts`（留空即全部）。注意去掉 `rts` 就没有玩法了——它是唯一创建对局、唯一注册准入回调的插件，没有它没人知道玩家该进哪个对局。默认配置下进程不会因此退出，而是落进**核心模式**：宿主自建一张平坦世界、任何连接一律放行、只有 `synaxis:main` 这一条 echo 通道，端口照常 bind（日志第一眼就能认出来，见 `docs/ENGINE.md` 3.4）。要让它像以前那样拒启（两行 ERROR + 非零码退出 + 端口不 bind），得同时给 `-Dsynaxis.app.core-mode=off`。插件 id 写错（比如把 `rts` 写成 `rst`）同样不会退出，只会多一行点名没匹配上的 id 的 WARN 然后进核心模式——起服后没人能进对局时先看这行。

## 7. `PluginContext` 给了你什么

| 能力 | 用途 | 禁用时宿主怎么收回 |
| --- | --- | --- |
| `process()` | 引擎进程本体。**唯一不作兼容承诺的一项**，用它自担风险 | 不收回（不是宿主发的） |
| `events()` | `addListener(事件类, 监听器)`（`NORMAL`，已取消的事件照样收到）；`addListener(事件类, 优先级, ignoreCancelled, 监听器)`，六档 `LOWEST` → `MONITOR` 依次执行，所有插件同档的排在一起，`MONITOR` 只读不改（约定，不拦截）；`forInstance(实例)` 只收该实例的事件，在实例所属 tick 线程上执行，且先于全局作用域的全部插件监听器；`PlayerPluginMessageEvent`、`PlayerCustomClickEvent`、`PlayerDisconnectEvent`、`PlayerPacketEvent` 及其超类型在玩家没有实例时也可能派发，不能按实例监听，注册即抛 `IllegalArgumentException`。监听器挂在宿主按插件、按档建的子节点上 | 该插件的全部子节点（含实例下的）`removeChild` |
| `scheduler()` | `scheduleTask(任务, 延迟, 重复)` | 逐个 `cancel()` |
| `channels()` | `register(名字, handler)` / `send(玩家, 名字, 字节)` | 注销该插件的全部通道 |
| `admission()` | `register(准入回调)`，决定连接进哪个对局实例、属哪个队伍 | 注销该回调 |
| `dataDirectory()` | `plugins/<plugin_id>/`，宿主保证存在 | 不删（是你的数据） |
| `analytics()` | `record(事件名, 属性)`，写进宿主的 `logs/events-YYYYMMDD.jsonl` | 无状态，不需要收回 |
| `logger()` | 名为 `plugin.<plugin_id>` 的 logger | — |

## 8. 通道命名

通道 id 一律是 `<plugin_id>:<name>`。`register("echo", ...)` 实际注册的是 `hello:echo`；写成别人的命名空间（`register("rts:game", ...)`）或宿主保留的 `synaxis:*` 一律抛 `IllegalArgumentException`。入站负载由宿主的单个监听器按前缀分发，没人注册的通道直接丢弃并记一行带 `connection_id` 的 WARN，不断线也不回错。

在第 6 步起的服务端上给它发一条消息（不需要客户端，仓内探针就够）：

```bash
python3 tools/protocol-probe/probe.py echo --channel hello:echo --payload hi
```

原样收回 `hi` 时退出码为 0，服务端那边会打印 `echoing 2 bytes back to probe`。

## 9. 异常会算在谁头上

事件监听器、调度任务、通道 handler 三处各有一层包装：抛出来的异常各被 catch 一次、各打一行带 `plugin_id=<你的 id>` 的 ERROR，然后就地终止那一次回调。异常不会外溢到引擎，进程继续跑，插件状态也不变——M2 没有熔断器（见 `docs/DESIGN-M2.md` §28.3）。

## 10. 回调跑在哪条线程上

引擎可以开多条 tick 线程：RTS 进程是 1 条，MMO 分片宿主（`bin/server-shard`）默认开「核数 − 2」条，按 16×16 区块一格分给各线程，同一格的区块与其中的实体归同一条线程，实体走到另一格就换线程。插件按下面的契约写，线程数是 1 还是 10 都不用改代码（`docs/DESIGN-PROD.md` `API-PROD-016`，`:plugin-api` 的 `package-info` 是同一份）：

| 回调 | 线程 | 在这里可以做什么 |
| --- | --- | --- |
| 实体与玩家的事件、玩家发来的通道负载、`scheduler().runOn(实体, …)` | 该实体所属的 tick 线程 | 读写这个实体 |
| `scheduler().scheduleTask(…)` | tick 调度线程，所有插件的任务一个接一个跑 | 每 tick 的全局逻辑；它是整个 tick 的串行段，要扩到几百人就按格或按实体拆开 |
| `scheduler().runAsync(…)` | 自己的虚拟线程 | 阻塞的活（读写库、HTTP）；不碰实体与实例，结果用 `runOn` 送回去 |
| 服务器列表事件 `ServerListPingEvent` | 发请求那条连接的读线程（虚拟线程） | 只改事件本身（MOTD、人数、样本、图标，`docs/DESIGN-PROD.md` `API-PROD-024`）；不碰实体与实例 |

对另一个实体的影响（攻击、交易）不要在自己的线程上直接改对方：对方可能在另一条线程上，用 `scheduler().runOn(对方, 任务, TaskSchedule.immediate())` 投递，任务在对方下一次 tick 时在对方的线程上执行。只有必须在同一 tick 读到对方状态时才用 `对方.acquirable().sync(…)`，所有跨线程的 `sync` 都排同一把进程级的锁，用多了就退化成单线程。
