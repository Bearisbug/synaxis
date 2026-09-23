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
- `descriptor()` 的 `id` 必须匹配 `[a-z0-9][a-z0-9_-]{0,63}` 且不得是 `synaxis`；`api_version` 必须等于 `PluginApi.API_VERSION`（当前 `1`）。三者任何一条不满足，宿主拒绝加载并以非零码退出，端口不会 bind。
- `onDisable()` 里只管自己持有的东西。事件节点、调度任务、通道、准入回调由宿主统一摘掉。

## 4. 让 ServiceLoader 找得到它

`hello-plugin/src/main/resources/META-INF/services/io.github.bearisbug.synaxis.plugin.SynaxisPlugin`，内容是一行全限定类名：

```
com.example.helloplugin.HelloPlugin
```

## 5. 构建

```bash
./gradlew -p example-plugin build --no-daemon     # 仓内示例
# 自己的工程：cd hello-plugin && gradle build
```

产物是 `example-plugin/build/libs/hello-plugin.jar`。

## 6. 带着插件起服

把插件 jar 追加到服务端的 classpath：

```bash
java -cp "server-app/build/install/server-app/lib/*:example-plugin/build/libs/hello-plugin.jar" \
  io.github.bearisbug.synaxis.app.Main
```

就绪日志是 `synaxis server-app listening on 0.0.0.0:25565`，它之前会出现 `Plugin enabled: plugin_id=hello ...` 与插件自己的打印。stdin 输入 `stop` 停服，停机日志里会出现 `hello-plugin is disabling`。

只想启用一部分插件时用 `-Dsynaxis.app.plugins=hello,rts`（留空即全部）。注意去掉 `rts` 就没有玩法了——它是唯一创建对局、唯一注册准入回调的插件，没有它没人知道玩家该进哪个对局。默认配置下进程不会因此退出，而是落进**核心模式**：宿主自建一张平坦世界、任何连接一律放行、只有 `synaxis:main` 这一条 echo 通道，端口照常 bind（日志第一眼就能认出来，见 `docs/ENGINE.md` 3.4）。要让它像以前那样拒启（两行 ERROR + 非零码退出 + 端口不 bind），得同时给 `-Dsynaxis.app.core-mode=off`。插件 id 写错（比如把 `rts` 写成 `rst`）同样不会退出，只会多一行点名没匹配上的 id 的 WARN 然后进核心模式——起服后没人能进对局时先看这行。

## 7. `PluginContext` 给了你什么

| 能力 | 用途 | 禁用时宿主怎么收回 |
| --- | --- | --- |
| `process()` | 引擎进程本体。**唯一不作兼容承诺的一项**，用它自担风险 | 不收回（不是宿主发的） |
| `events()` | `addListener(事件类, 监听器)`（`NORMAL`，已取消的事件照样收到）；`addListener(事件类, 优先级, ignoreCancelled, 监听器)`，六档 `LOWEST` → `MONITOR` 依次执行，所有插件同档的排在一起，`MONITOR` 只读不改（约定，不拦截）；`forInstance(实例)` 只收该实例的事件，在实例所属 tick 线程上执行，且先于全部全局监听器。监听器挂在宿主按插件、按档建的子节点上 | 该插件的全部子节点（含实例下的）`removeChild` |
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
