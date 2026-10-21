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

`hello-plugin/src/main/java/com/example/helloplugin/HelloPlugin.java`：实现 `SynaxisPlugin` 的三个方法即可，完整代码见仓内 `example-plugin/src/main/java/com/example/helloplugin/HelloPlugin.java`。四条硬规则：

- **无参构造器里禁止注册任何东西**。宿主先 new 出实例只为读 `descriptor()`，一切注册都在 `onEnable`。
- `descriptor()` 的 `id` 必须匹配 `[a-z0-9][a-z0-9_-]{0,63}`、不得是 `synaxis`，且全进程唯一：两个插件声明同一个 id 时宿主拒启；`api_version` 必须等于 `PluginApi.API_VERSION`（当前 `2`）。任何一条不满足，宿主拒绝加载并以非零码退出，端口不会 bind。
- `onDisable()` 里只关自己开的东西（线程池、连接、文件）；宿主收回的那些（清单见第 7 节末列）不用管。`onDisable` 跑在宿主新起的 daemon 平台线程 `plugin-disable-<id>` 上，这条线程不拥有任何 tick 线程，改已分给 tick 线程的实体要经 `acquirable().sync(…)`。全部插件共用 5 s：用完时还在 `onDisable` 里的插件被留在它的线程上，宿主照样收回它的能力、接着停机。`onEnable` 抛异常时宿主直接收回能力、不调 `onDisable`，抛出之前自己开的线程池与连接要在 `onEnable` 的 catch 里关（`docs/DESIGN-PROD.md` `API-PROD-007`）。
- 自己起的线程设成 daemon，或在 `onDisable` 里关掉：停机序列走完，宿主就以退出码 0 结束进程（`docs/DESIGN-PROD.md` §11.4），不等插件留下的线程，上面做到一半的活就此中断。

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

只想启用一部分插件时用 `-Dsynaxis.app.plugins=hello,rts`（留空即全部）。注意 `installDist` 的 `lib/` 里只有 `rts` 一个玩法插件，去掉它就没有玩法了——它是 `lib/` 里唯一创建对局、唯一注册准入回调的插件，没有它没人知道玩家该进哪个对局。默认配置下进程不会因此退出，而是落进**核心模式**：宿主自建一张平坦世界、任何连接一律放行、只有 `synaxis:main` 这一条 echo 通道，端口照常 bind（日志第一眼就能认出来，见 `docs/ENGINE.md` 3.4）。要让它像以前那样拒启（两行 ERROR + 非零码退出 + 端口不 bind），得同时给 `-Dsynaxis.app.core-mode=off`。插件 id 写错（比如把 `rts` 写成 `rst`）同样不会退出，只会多一行点名没匹配上的 id 的 WARN 然后进核心模式——起服后没人能进对局时先看这行。

classpath 上有不止一个注册准入的玩法插件时，白名单必须给：一个进程只有一个准入槽，两个插件都启用时，后注册准入的那个在 `onEnable` 里抛异常（`docs/DESIGN-PLAY.md` §6.2）。服务端的 `lib/` 里恒有 `rts`，所以带样板玩法 `rush` 起服（`docs/DESIGN-PLAY.md` §23 的 R1 环）时给 `-Dsynaxis.app.plugins=rush`，并把 `:game-rush` 与 `:play-kit` 两个 jar 都追加上：

```bash
./gradlew :server-app:installDist :game-rush:jar :play-kit:jar --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts
java -Dsynaxis.app.plugins=rush \
  -cp "server-app/build/install/server-app/lib/*:game-rush/build/libs/game-rush-dev.jar:play-kit/build/libs/play-kit-dev.jar" \
  io.github.bearisbug.synaxis.app.Main
```

jar 名里的 `dev` 是版本号，设了环境变量 `GAME_ENGINE_VERSION` 时换成它的值。`:play-kit` 的 jar 不能省，白名单改成 `rts` 回滚时也不能：宿主先实例化 classpath 上的每个插件、读它的描述，再按白名单启用，而 `RushPlugin` 的构造就要用 `:play-kit` 的类，少了这个 jar 起服直接失败（`ServiceConfigurationError`，cause 是 `NoClassDefFoundError`）。本机开发用 `./gradlew :server-app:runRush` 更省事：它的 classpath 自带这两个模块，默认白名单就是 `rush`。

## 7. `PluginContext` 给了你什么

| 能力 | 用途 | 禁用时宿主怎么收回 |
| --- | --- | --- |
| `process()` | 引擎进程本体。**唯一不作兼容承诺的一项**，用它自担风险 | 不收回（不是宿主发的） |
| `events()` | `addListener(事件类, 监听器)`（`NORMAL`，已取消的事件照样收到）；`addListener(事件类, 优先级, ignoreCancelled, 监听器)`，六档 `LOWEST` → `MONITOR` 依次执行，所有插件同档的排在一起，`MONITOR` 只读不改（约定，不拦截）。监听器按事件的确切类派发，只有 `RecursiveEvent` 沿父类传：按接口或抽象类注册抛 `IllegalArgumentException`，要收一类事件就逐个注册具体类。`forInstance(实例)` 只收该实例的事件，监听器在派发事件的线程上执行：实体与玩家的事件在该实体所属 tick 线程上（grid 下同一个实例有多条），`InstanceTickEvent` 在 tick 调度线程，`InstanceChunkLoadEvent` 在处理该区块的虚拟线程（偶尔在请求线程，不要假定在 tick 线程），宿主派发的 `PlayInstanceStateEvent` 在执行转移的线程，监听器之间共享的状态要用并发结构；实例作用域的监听器先于全局作用域的全部插件监听器，也先于宿主节点自己的监听器（首次出生时应用存档、默认玩法层），要在宿主之后改出生结果就用全局作用域、按实例过滤。`PlayerPluginMessageEvent`、`PlayerCustomClickEvent`、`PlayerDisconnectEvent`、`PlayerPacketEvent` 及其超类型在玩家没有实例时也可能派发，不能按实例监听，注册即抛 `IllegalArgumentException`。冻结的玩家（`PlayerDataStore#isFrozen`）的 `PickupItemEvent`、`ItemDropEvent`、`PlayerBlockPlaceEvent`、`PlayerBlockInteractEvent` 与 `PlayerUseItemEvent`：宿主每个事件只判定一次冻结，读到冻结就取消两次：一次在根节点上、先于任何插件监听器，一次在宿主节点下的优先级 3、全局 `HIGHEST` 之后与 `MONITOR` 之前；冻结在一次派发中途落地时，这一次照判定放行到底。两参 `addListener` 收得到已取消的事件，凭这五类事件发放东西的监听器用 `ignoreCancelled=true` 或先查 `isCancelled()`，以是否已取消为准，不另查 `isFrozen`；撤销这次取消（`setCancelled(false)`）不受支持，撤销之后、第二次取消之前执行的监听器看到的是放行。冻结拦不住三条路径：冻结前已打开的容器里点格子、`PlayerUseItemOnBlockEvent`、冻结前开始而冻结中用完的物品（`docs/DESIGN-PROD.md` §28.2）。监听器挂在宿主按插件、按档建的子节点上 | 该插件的全部子节点（含实例下的）`removeChild` |
| `scheduler()` | `scheduleTask(任务, 延迟, 重复)` 在 tick 调度线程上跑，重复任务抛异常之后照原周期继续；`runOn(实体或实例, 任务, 延迟)` 投给目标：实体的任务在该实体所属 tick 线程上跑，实例的任务在 tick 调度线程的串行段跑，与实例自己的调度器同一处；`runAsync(任务)` 在自己的虚拟线程上跑阻塞的活。能在这些线程上做什么见第 10 节 | 取消它排的全部任务（含 `runOn` 还没跑的），`runAsync` 还在跑的任务体收到中断 |
| `channels()` | `register(名字, handler)` / `send(玩家, 名字, 字节)`；负载宜在 8 KiB 以内，超过 1,048,576 字节（客户端收的上限）`send` 在调用线程抛 `IllegalArgumentException`、什么都不发 | 注销该插件的全部通道 |
| `admission()` | `register(准入回调)`，决定连接进哪个对局实例；一个进程只有一个准入槽。`Accept` 带的队伍号宿主既不保存也不读，要按队伍做事的插件在回调里按 `connectionId` 自己记下、在 `PlayerDisconnectEvent` 里清掉（判活与回收照下面这条）；`Reject` 的原因键用 `DisconnectKeys` 的常量或本插件 `translationKeys()` 里声明的键（第 11 节），台账 `client_rejected` 的 `reason` 去掉 `synaxis:` 前缀，别的命名空间整串照记。回调不阻塞、不碰实体；抛异常、返回 null 或构造出不合法的答案，都按 `synaxis:match-unavailable` 拒绝这条连接；`admit` 超过 200 ms 记一行 WARN，答案照常生效。按 `connectionId` 建表时，用同一把锁先查 `process().connection().isConnected(connectionId)` 再建表，`PlayerDisconnectEvent` 里的回收也进这把锁，否则判活之后、建表之前断开的连接留下的表项没人回收（`docs/DESIGN-M2.md` `API-M2-011`）；返回 `Accept` 之后宿主仍可能断开这条连接（例如停机保存已经开始时以 `synaxis:state-unavailable` 拒绝），断开时照常派发 `PlayerDisconnectEvent` | 注销该回调 |
| `dataDirectory()` | `plugins/<plugin_id>/`，宿主保证存在 | 不删（是你的数据） |
| `analytics()` | `record(事件名, 属性)`，写进宿主的 `logs/events-<shard_id>-YYYYMMDD.jsonl`（`<shard_id>` 取 `synaxis.app.shard-id`，默认 `local`；前几天的文件压成 `.jsonl.gz`，保留 30 天，`docs/DESIGN-PROD.md` `API-PROD-014`） | 无状态，不需要收回 |
| `playerData()` | 玩家数据段（`docs/DESIGN-PROD.md` `API-PROD-005`）。`register` 只能在 `onEnable` 里调，id 为 `<plugin_id>:<名字>`，越界或在 `onEnable` 之外调用抛 `IllegalStateException`；`get` 在玩家所属 tick 线程上调；改完调 `markDirty`，宿主每 60 s、登出、转服与停机时写脏段；单段编码后 ≤ 65,536 B。交易、邮件、商店用 `commitTogether`，先查 `isFrozen`；暂存的值必须是新对象，不能是 `get` 返回之后原地改过的那个（可变模型先深拷贝再改拷贝），`apply` 执行之前 `get` 仍返回旧值，结果为 `FENCED` 或 `UNAVAILABLE` 时内存里的值不变、宿主也不重试；冻结期间 `saveNow` 与 `commitTogether` 直接得 `UNAVAILABLE` | `onEnable` 失败时收回它注册的段与停机保存钩子；正常启用之后不收回（数据是玩家的） |
| `lifecycle()` | `onShutdownSave(钩子)`，只能在 `onEnable` 里登记：停机保存之前，宿主在每名在线玩家所属 tick 线程上各调一次，在这里把内存里的状态写进段并标脏；已准入、还没进世界的玩家在保存期间离开时，由派发它的 `PlayerDisconnectEvent` 的线程调用。tick 被看门狗判死、或某名玩家所属的 tick 在保存阶段结束之后才轮到时，宿主不调钩子、直接读各段的当前值：只放在插件内存里的状态在这两种情况下会丢，要可靠就随变化写进段并 `markDirty`（`docs/DESIGN-PROD.md` `API-PROD-007`）。停机时不要在 `PlayerDisconnectEvent` 里保存：踢人之前数据已经写完 | 同 `playerData()` |
| `permissions()` | `has(玩家, 节点)` 与 `roles(玩家)`（`docs/DESIGN-PROD.md` `API-PROD-010`）：节点小写、点分，授予的节点可以 `*` 结尾；宿主在玩家登录时读角色、角色变化时重载；任意线程可调。命令的 condition 里查它，不要用 `Player#getPermissionLevel()`；condition 在每名玩家出生、每次重发补全时都会被问到，节点写成常量。没有数据库的进程（`synaxis.app.store=memory`，默认值）不载入角色：`has` 对谁都是 false、`roles` 为空，拿它做 condition 的命令所有玩家都看不到 | 无状态，不需要收回 |
| `moderation()` | 封禁与白名单（`docs/DESIGN-PROD.md` `API-PROD-009`）：`ban`、`unban`、`activeBan`、`whitelist`，任意线程可调，写库在虚拟线程上做，失败的 future 带 `IllegalStateException`；理由为空或超过 256 字符、到期时间不在将来或不早于 `+10000-01-01T00:00:00Z` 时当场抛 `IllegalArgumentException`；没有数据库的进程（`synaxis.app.store=memory`）谁都不封、什么也改不了 | 无状态，不需要收回；封禁与白名单在库里 |
| `commands()` | `register(命令)`（`docs/DESIGN-PROD.md` `API-PROD-011`）：注册成 `<plugin_id>:<名字>`，另以裸名注册，除非裸名已被别人占了（谁先注册归谁，别名同样）；名字与别名不能含 `:`。注册取的是命令当时的样子：之后再改它的结构，生效时机没有保证，要改先 `unregister` 再 `register`；宿主就地包装命令、语法与子命令的执行器、condition 与参数错误回调，此后这些 getter 返回宿主的包装。谁能跑由命令自己的 condition 决定，在里面查 `permissions()`。执行器在发命令的玩家所属 tick 线程上跑，控制台的在控制台读线程上；执行器、参数的错误回调与 condition 抛的异常怎么记见第 9 节，condition 抛异常按不放行 | 注销它注册的全部命令 |
| `tabList()` | `setVisibility((观看者, 被看者) -> 是否列出)`（`docs/DESIGN-PROD.md` `API-PROD-020`）：全进程只有一个可见性，另一个插件已经设过时抛 `IllegalStateException`；宿主每发一条列表条目就问一次，在发条目的那条线程上（玩家进服时是 tick 调度线程，`Player#setListed` 时是调用方）；不列出的玩家照样发给观看者、只是不显示，客户端要靠这条条目渲染皮肤 | 收回它设的可见性 |
| `entities()` | `setDefaultTrackingRange(实体类型, 水平, 垂直)`（`docs/DESIGN-PROD.md` `API-PROD-019`）：玩家在多远之内看得见这类实体，只收窄 `synaxis.entity-view-distance` 那一圈、不放宽；单个实体用 `Entity#setTrackingRange` 覆盖，`Entity#setAlwaysVisible` 让它不计入每名玩家的可见上限 | 不收回：设置是全进程的，插件禁用之后仍然生效 |
| `transfers()` | 把玩家原版转服到外部服务器、读写本插件命名空间的 Cookie，见第 12 节 | 不收回：已排上的转服在玩家下一个 tick 照常做完（还在载入出生区块的玩家至多 5 s） |
| `play()` | 场景、HUD 与动作（`docs/DESIGN-PLAY.md` `API-PLAY-005`）：`enter` / `leave` / `currentScene` 让玩家进出场景，只有本进程的场景所有者（占着准入槽的插件）能调；`hud(玩家)` 设本插件命名空间下的小部件；`onAction(动作 id, handler)` 只能在 `onEnable` 里注册。场景 id 与动作 id 的名字部分只能是 `[a-z0-9._/-]+`，补上前缀后至多 64 字节，不合规抛 `IllegalArgumentException`。插件只用 `io.github.bearisbug.synaxis.plugin.play` 的类型：`io.github.bearisbug.synaxis.game.protocol.play` 是宿主的线上编码层，经 `plugin-api` 传递可见，其中的 `Widget`、`PropValue.Row`、`PropValue.Button`、`C2SAction.Target` 与插件 API 的同名类型不能互换，IDE 自动导入时看清包名 | 收回它注册的动作 |
| `instances()` | 宿主托管的玩法实例（`docs/DESIGN-PLAY.md` `API-PLAY-006`）：`create(InstanceSpec)` 建大厅或一局，`owned()` 列出本插件建的、还没关闭的实例；实例的 `move`、`start`、`end`、`settled` 推进五态生命周期，宿主每次转移写一行台账 | 强制关闭它全部没关闭的实例（`onEnable` 失败时原因是 `plugin_disabled`，停机时是 `shutdown`），不调插件回调 |
| `logger()` | 名为 `plugin.<plugin_id>` 的 logger | — |

## 8. 通道命名

通道 id 一律是 `<plugin_id>:<name>`。`register("echo", ...)` 实际注册的是 `hello:echo`；写成别人的命名空间（`register("rts:game", ...)`）或宿主保留的 `synaxis:*` 一律抛 `IllegalArgumentException`。入站负载由宿主的单个监听器按前缀分发。handler 只在玩家进入 PLAY 之后、在发送者所属的 tick 线程上收到负载：configuration 阶段（握手与准入还没做完、玩家不在任何实例里）到的负载直接丢弃，handler 收不到，所以不用自己判连接状态。没人注册的通道直接丢弃并记一行带 `connection_id` 的 WARN，不断线也不回错；`minecraft:` 命名空间的不记，原版与 Fabric 客户端每次登录都发 `minecraft:brand`。通道名不是合法 identifier 的负载由引擎以 `synaxis:protocol-error` 断开。

在第 6 步起的服务端上给它发一条消息（不需要客户端，仓内探针就够）：

```bash
python3 tools/protocol-probe/probe.py echo --channel hello:echo --payload hi
```

原样收回 `hi` 时退出码为 0，服务端那边会打印 `echoing 2 bytes back to probe`。

## 9. 异常会算在谁头上

宿主在插件的每个回调外面包了一层：回调抛出的任何 `Throwable` 都被接住，记一行带 `plugin_id=<你的 id>` 的日志，就地终止那一次回调。异常不会外溢到引擎，进程继续跑，插件状态也不变——M2 没有熔断器（见 `docs/DESIGN-M2.md` §28.3）。

调用次数由客户端或时钟决定的回调按回调限流：一份预算每 10 s 至多写一行，这一行带当次的栈，行尾括号里是上一行以来的次数，形如 `(12 failures since the last report)`。一个回调次次都抛，日志也只随注册的回调数增长，不随收到的包数增长：

| 回调 | 预算 | 另外 |
| --- | --- | --- |
| 事件监听器 | 每个监听器一份 | 行首 `Plugin event listener failed: plugin_id=… event=…` |
| 调度任务 | `scheduleTask` 的周期任务每个一份；一次性任务、`runOn` 与 `runAsync` 共用一份 | 行首 `Plugin task failed: plugin_id=…`；周期任务抛异常之后照原周期继续 |
| 通道 handler | 每个通道一份 | — |
| 命令的执行器、参数的错误回调与 condition | 每个命令一份 | 发命令的人收到命令执行失败；condition 抛异常按不放行，命令不进这名玩家的补全 |
| 动作 handler（`play().onAction`） | 每个动作一份 | — |
| TabList 可见性 | 一份 | 记 WARN，抛异常按列出 |

次数有限的回调每次都记一行带栈的 ERROR：`onEnable`（宿主随后收回它的能力，见第 3 节）、`onDisable`、准入回调（每条连接至多一次，按 `synaxis:match-unavailable` 拒绝这条连接）、停机保存钩子（每次停机每名玩家至多一次）。

## 10. 回调跑在哪条线程上

引擎可以开多条 tick 线程：RTS 进程是 1 条，MMO 分片宿主（`bin/server-shard`）默认开「核数 − 2」条，按 16×16 区块一格分给各线程，同一格的区块与其中的实体归同一条线程，实体走到另一格就换线程。插件按下面的契约写，线程数是 1 还是 10 都不用改代码（`docs/DESIGN-PROD.md` `API-PROD-016`，`:plugin-api` 的 `package-info` 是同一份）：

| 回调 | 线程 | 在这里可以做什么 |
| --- | --- | --- |
| 实体与玩家的事件（含 `transfers().transfer(…)` 让宿主派发的 `OutgoingTransferEvent`，不论从哪条线程调用；例外见下一张表）、实体的休眠策略（`Entity#setDormancyPolicy`）、玩家发来的通道负载（handler 只在玩家处于 PLAY 态时收到，§8）、`scheduler().runOn(实体, …)` | 该实体所属的 tick 线程 | 读写这个实体 |
| `events().forInstance(实例)` 的监听器 | 派发这个事件的线程：实体与玩家的事件在该实体所属的 tick 线程上（`grid` 下同一个实例同时有几条），`InstanceTickEvent` 在 tick 调度线程上，`InstanceChunkLoadEvent` 由处理该区块的虚拟线程派发，偶尔由请求线程在载入表的映射之外派发（不要假定在 tick 线程上），宿主派发的 `PlayInstanceStateEvent` 在执行这次转移的线程上 | 读写派发它的那个实体；同一个实例的几个监听器共用的每局状态用并发容器或锁。这些监听器排在全局作用域的插件监听器之前，也排在宿主节点的监听器之前：首次出生时宿主在自己的节点里用存档覆盖背包、手持格与生命值，要在那之后改出生结果，用全局作用域、按实例过滤（§7） |
| `scheduler().scheduleTask(…)` | tick 调度线程，所有插件的任务一个接一个跑 | 每 tick 的全局逻辑；它是整个 tick 的串行段，要扩到几百人就按格或按实体拆开。可以读任何实体，可以调写明能在串行段调用的宿主接口（场景层的写方法，`docs/DESIGN-PLAY.md` `API-PLAY-005`）；已分到 tick 线程的实体不在这里直接改，用 `runOn(实体, …)`（下一 tick 生效）或 `实体.acquirable().sync(…)`（当场生效）：严格模式下直接改抛 `AcquirableOwnershipException`，不开严格模式也会与别的线程的 `sync` 同时写 |
| `scheduler().runOn(实例, …)` | tick 调度线程的串行段，与实例自己的调度器在同一处 | 同上一行 |
| `playerData().commitTogether(…, apply)` 的 `apply` | tick 调度线程，提交写进库之后下一个 tick 的串行段，只跑一次 | 改每个参与者，不论他们分在哪条 tick 线程；参与者这时可能已经离开，先查 `isRemoved()`（`docs/DESIGN-PROD.md` `API-PROD-005`） |
| `playerData().saveNow(…)` 与 `commitTogether(…)` 返回的 future 的回调 | 玩家冻结、不在线或没有载入时 future 拿到手就已完成，回调在调用线程上；数据库返回得快时也可能在调用返回前就已完成，同样在调用线程上；从不拥有玩家的线程调 `saveNow`、到玩家下一个 tick 时玩家已离开或已冻结，在玩家所属的 tick 线程上；10 s 内没有这个 tick，在 JDK 的 `CompletableFuture` 定时线程上；其余在宿主的虚拟线程上 | 不碰实体也不阻塞：碰实体用 `runOn` 送回，阻塞用 `runAsync`；`saveNow` 本身哪条线程都能调，快照总在玩家所属的 tick 线程上取（`docs/DESIGN-PROD.md` `API-PROD-005`） |
| `scheduler().runAsync(…)` | 自己的虚拟线程 | 阻塞的活（读写库、HTTP）；不碰实体与实例，结果用 `runOn` 送回去 |
| 服务器列表事件 `ServerListPingEvent` | 发请求那条连接的读线程（虚拟线程） | 只改事件本身（MOTD、人数、样本、图标，`docs/DESIGN-PROD.md` `API-PROD-024`）；不碰实体与实例 |
| 插件的 `onDisable()` | 宿主为每个插件新起的平台线程 `plugin-disable-<id>`，不属于任何 tick 线程；全部插件共用 5 s，超时之后宿主照样收回能力，`onDisable` 留在自己的线程上继续跑 | 收尾插件自己持有的东西（线程池、连接）；已分到 tick 线程的实体经 `实体.acquirable().sync(…)` 改，还没分到的直接改（`docs/DESIGN-PROD.md` `API-PROD-007`） |
| 可见规则的谓词（`Entity#updateViewableRule`、`Entity#updateViewerRule`） | 改变可见性的那条线程（跨区块、玩家每 5 tick 的可见性重算、改规则），持着两边实体的视图锁 | 只读谓词自己持有的线程安全状态；不阻塞，不调任何实体的 `isViewer`、`getViewers`、`addViewer`、`removeViewer` 与规则方法，否则与另一条 tick 线程互锁；状态变了调无参重载重新应用（见这几个方法的 Javadoc） |

例外：下面这些玩家事件不在该玩家所属的 tick 线程上派发，或只为一部分包派发。它们的监听器与 tick 线程上的代码共用状态时，用并发容器或锁。

| 事件 | 线程 | 在这里可以做什么 |
| --- | --- | --- |
| `PlayerPacketOutEvent`（每个出站包一次） | 该连接的写线程（虚拟线程） | 只看或取消这个包（取消就不写出；切换连接状态的 `FinishConfigurationPacket`、`StartConfigurationPacket` 取消无效，照常写出并记一行 WARN）；不读写实体与实例，要碰玩家用 `runOn`；不阻塞，阻塞卡住这名玩家的全部出站包。句柄区块、批量广播这类 `BufferedPacket` 不派发，`CachedPacket`、`FramedPacket` 派发其中的包 |
| `PlayerPacketEvent` | 只为排进玩家队列的 PLAY 包派发，在玩家所属的 tick 线程上；玩家不在实例里时（configuration 结束后等待加入，或被送回 configuration、客户端还没确认）在 tick 调度线程的串行段上；keep-alive、ping 与 Cookie 的应答在读线程上处理，不派发；会切换解码状态的包（回到 configuration 的确认）也不派发，插件取消不了它 | 读写这名玩家；取消就不处理这个包 |
| `AsyncPlayerPreLoginEvent`、`AsyncPlayerConfigurationEvent` | 连接的虚拟线程（在线模式下 `AsyncPlayerPreLoginEvent` 在验证线程上，离线模式下在登录线程上） | 可以阻塞（读库、HTTP；远程取皮肤在这里取，存下来到 `PlayerSkinInitEvent` 再设）；只碰事件、连接与这名还没进世界的玩家。`sendPluginRequest` 的应答带超时等：连接断开或离开 LOGIN 时 future 以 `IllegalStateException` 异常完成，之后再调抛同一异常 |
| `PlayerSkinInitEvent`、首次出生的 `PlayerSpawnEvent` | tick 调度线程的串行段，玩家这时还没分到 tick 线程（从 configuration 回来的玩家由这条线程持着） | 改这名玩家；不阻塞，整个进程的 tick 在等它；碰别的实体用 `runOn` |
| `PlayerDisconnectEvent` | 已出生的玩家在他所属的 tick 线程上；进了 PLAY 还没出生的在 tick 调度线程上；PLAY 之前断开的在断开连接的那条线程上（读线程、configuration 线程或 tick 调度线程） | 清理按玩家记的状态；后两种与 tick 线程上的代码同时跑 |

对另一个实体的影响（攻击、交易）不要在自己的线程上直接改对方：对方可能在另一条线程上，用 `scheduler().runOn(对方, 任务, TaskSchedule.immediate())` 投递，任务在对方下一次 tick 时在对方的线程上执行。只有必须在同一 tick 读到对方状态时才用 `对方.acquirable().sync(…)`，所有跨线程的 `sync` 都排同一把进程级的锁，用多了就退化成单线程。在 tick 线程上跨线程 `sync`（`lock`、`applySync` 也一样）时，引擎在等这把锁期间放开本线程自己的锁，别的线程可能趁这时改了本线程名下的实体，正在 tick 的这一个也算，所以拿到对方之后要重新读自己的状态。`sync` 持有对方期间，引擎不会把对方交给别的 tick 线程；从 `runAsync` 这类非 tick 线程持有时，持着它跨过 tick 边界会推迟下一个 tick 的开始，所有 tick 线程一起等，所以回调里不要阻塞（`docs/DESIGN-PROD.md` `API-PROD-016` 元素换线程一条）。

`teleport` 与 `setInstance` 要先载入区块时，返回的 future 在区块载入、实体所属线程放开它之后才完成。在这个实体所属的 tick 线程上（它的事件监听器、`runOn` 给它的任务），以及任何 `sync` 或 `lock` 回调里，都不要 `join()` 或 `get()` 这个 future。在所属线程上（`sync` 持有的就是这个实体时也算）等的正是当前线程，引擎对方法直接返回的 future 当场抛 `IllegalStateException`；`sync` 或 `lock` 从别的线程取到元素时，当前线程持着所有跨线程 `sync` 共用的那把锁，续延要先拿它，引擎拦不住，等下去整个进程停止 tick；在它上面 `thenRun` 得到的 future 引擎同样拦不住。要接后续，用 `thenRun`。玩家的 `setInstance` 是例外：在调用它的线程上 `join` 不抛，等区块载完、只跑这次换实例的续延，这段时间这条 tick 线程停着，所以同样用 `thenRun`；等区块期间再调一次 `setInstance` 或玩家被移除，前一次的 future 以 `CancellationException` 失败。

实体的属性（`LivingEntity#getAttribute` 取到的 `AttributeInstance`）任意线程可读，只在实体所属的线程上改：每次修改当场回调实体，发属性包，降最大生命值时还压低当前生命值。不拥有实体的线程降最大生命值，压生命值排到实体下一个 tick、在它的线程上做，在那之前读到的生命值还是旧的；实体还没分到 tick 线程时（例如首次出生的 `PlayerSpawnEvent`），tick 线程以外的线程降最大生命值照常当场压。

区块的观察者（`Chunk#getViewers()`，即 `sendPacketToViewers` 发给谁）是区块环里有这个区块的玩家（引擎给他发区块的那一圈），不是位置在一定半径内的玩家：区块更新限流器留住区块环中心时两者不同。直接改区块内容（`Chunk#setBlock` 这类）要在这个区块的写锁里改，改完再发更新包；不持写锁调 `Chunk#setBlock`、`Chunk#setBiome` 抛 `IllegalStateException`。读锁不能升级为写锁：只持读锁时调 `lockWriteLock()`，或调会取写锁的 `Instance#setBlock`、`Instance#setBiome`，抛 `IllegalStateException`（`tryLockWriteLock` 等满时限后返回 false），先查后改要从一开始就拿写锁（`docs/DESIGN-PROD.md` `API-PROD-020` 区块读锁一条）；引擎发区块时在同一把写锁里取包并交给连接，所以一次改动要么在区块包里，要么排在区块包后面（`docs/DESIGN-PROD.md` `API-PROD-020` 区块包与方块更新的先后一条）。持着区块的锁给玩家排区块（`Chunk#sendChunk`）不会卡住发送：发送一批最多等区块锁 1 ms，等不到的区块在锁空出来的那个 tick 发，可能不止晚一个 tick，同一批的其他区块照发。例外是 `PlayerChunkLoadEvent`：它在这名玩家的发送队列锁里派发，监听器里改方块（`Instance#setBlock`）会一直等那个区块的写锁；这时另一条线程若正持着那个区块的锁、又给这名玩家排区块，两边互等。监听器里要改方块，用 `scheduler().runOn(玩家, 任务, TaskSchedule.immediate())` 投递到这名玩家的下一次 tick 再改。

方块回调与实例事件在锁里执行：`BlockHandler#onPlace`、`BlockHandler#onDestroy`、`BlockPlacementRule#blockPlace`、`BlockPlacementRule#blockUpdate` 在设方块的那次调用里同步执行，持着这个区块的写锁，经 `Instance#setBlock`、放置与破坏设方块时还持着实例监视器；`InstanceBlockUpdateEvent` 在同一处派发，`InstanceChunkUnloadEvent` 在卸载区块时持实例监视器派发，`InstanceUnregisterEvent` 不在实例监视器里派发。另一条 tick 线程这时可能持着它自己的锁、等着这个实例的监视器（它也在改这个实例的方块），回调里对别的 tick 线程上的实体调 `acquirable().sync` 或 `lock`，两边就互等。回调里可以在同一条线程上再调 `Instance#setBlock`；不阻塞，不 `join()` 还没完成的 future（含 `loadChunk(…).join()`）；要影响别的实体，用 `scheduler().runOn(目标, 任务, TaskSchedule.immediate())` 投递。

消费掉落物（吸取、自动拾取这类插件自己交出 `ItemEntity` 的物品再移除它）的约定还没定。引擎的拾取与合并都先经 `ItemEntity#claim` 的 CAS 才消费，同一个掉落物至多被消费一次；`claim()` 与 `releaseClaim()` 标着 `@ApiStatus.Internal`，不在插件的稳定面里。插件只按上表用 `runOn` 在掉落物自己的线程上处理、不经 claim 时，邻格线程上的合并或拾取照样能占到它，同一个掉落物会被消费两次，物品复制。这一条登记在 `docs/DESIGN-PROD.md` §28.2 待办「插件消费掉落物没有 claim 约定」，属于插件 API 的决定，第一个生成或消费掉落物的插件接入之前定下：默认做法是去掉这两个方法的 Internal 标注，约定先 `claim()`，拿不到就不消费，在掉落物自己的线程上消费完再 `remove()`。

## 11. 发给玩家的文字

断线原因、准入拒绝与系统消息一律发翻译键（`Component.translatable(键, 参数…)`），不发拼好的句子。客户端只从 Synaxis 模组的两份语言文件取译文（`client-fabric/src/main/resources/assets/synaxis/lang/en_us.json`、`zh_cn.json`），没有译文的键会原样显示成键名。

- `DisconnectKeys` 里的键已经有译文，直接用。
- 插件自己的键：在 `SynaxisPlugin#translationKeys()` 里列出来，再把中英两行译文加进模组的两份语言文件。仓内插件由 `:architecture-tests` 的翻译键门禁核对，缺一行 `./gradlew check` 就红（`docs/DESIGN-PROD.md` `API-PROD-029`）；仓外插件门禁看不到，译文要提交到本仓的模组里。
- 键写成完整的字符串常量，不在运行时拼（`"synaxis:command-" + name` 门禁看不见）。

## 12. 转服与 Cookie

`context.transfers()` 给的 `Transfers` 有四个方法，任意线程可调（`docs/DESIGN-PROD.md` `API-PROD-021`）。`transfer` 不在调用线程上转服：它把转服排到玩家的下一个 tick，由玩家所属的 tick 线程查 PLAY 与冷却、派发 `OutgoingTransferEvent`（监听器在这里可以读写这名玩家，第 10 节），事件没被取消才发包、记冷却，结果以 `CompletableFuture<Boolean>` 返回：

| 方法 | 做什么 | 什么时候不做 |
| --- | --- | --- |
| `transfer(玩家, host, port)` | 发原版 `Transfer` 包（host、port 按监听器改过的），把客户端送去外部服务器，不带票据；玩家断开后宿主按普通登出保存。future 以 `true` 完成 | 玩家不在 PLAY（还在 configuration 或已断开）、离上一次转服不满 `synaxis.app.transfer-cooldown-ms`（默认 5 s）、或监听器取消了 `OutgoingTransferEvent` 时以 `false` 完成，什么都不发、冷却不记；5 s 内玩家没有 tick（已离开）同样以 `false` 完成；`host` 空白或 `port` 不在 1～65535 在调用线程上抛 `IllegalArgumentException`。监听器把 host 设为空白或把 port 设到 1～65535 之外时，setter 抛 `IllegalArgumentException`，记日志后按最后一次被接受的 host 与 port 发 |
| `wasTransferred(玩家)` | 客户端握手时是否自称由别的服务器转来 | 这是客户端自报的，不能当来源证明 |
| `storeCookie(玩家, 键, 值)` | 把至多 5,120 B 存在客户端上，客户端转服时带去下一台服务器 | 键不在本插件命名空间或值超过 5,120 B 时抛 `IllegalArgumentException`，不发包 |
| `fetchCookie(玩家, 键)` | 向客户端要回一个 Cookie；future 以值完成，客户端没有这个 Cookie 时以 `null` 完成 | 5 s 内没回答（玩家断开，或对同一玩家同一个键在回答前又要了一次）以 `TimeoutException` 完成；键不在本插件命名空间时抛 `IllegalArgumentException` |

Cookie 键必须是 `<plugin_id>:<路径>`，如 `Key.key("hello", "lobby-token")`。别的命名空间一律拒绝，包括宿主的 `synaxis:`：宿主把转服票据存在 `synaxis:transfer`，插件写它会覆盖票据，读它会抢走验票在等的那次应答，两种都会让玩家的转服被拒。

冷却是整个进程一张表，M3-E 起宿主的分片间转服也记在这张表里：玩家刚以票据转入或刚被宿主转出，5 s 内 `transfer` 以 `false` 完成；插件转走的玩家 5 s 内宿主也不再签票据。要把玩家送去另一个 Synaxis 分片不能用 `transfer`：分片只收带宿主票据的转入，没有票据的会被拒。M3-E 起跨分片转服由宿主的关卡门、GM 命令 `gm.transfer` 与管理接口 `synaxis:transfer` 发起，在那之前没有发起跨分片转服的入口。

`transfer` 的 future 在玩家所属的 tick 线程上完成；调用时玩家就不在 PLAY 的，拿到手时已经完成，5 s 没等到 tick 的在 JDK 的计时线程上完成。`fetchCookie` 的 future 在读到回答的连接线程上完成，超时则在 JDK 的计时线程上完成。拿到值之后要改实体，用 `scheduler().runOn(玩家, …)` 送回玩家所属的线程（第 10 节）。

**不要在 tick 线程上 `join()` 或 `get()` 这个 future**（例如在玩家事件监听器里写 `transfer(p, h, port).join()`）：转服要等玩家的下一个 tick 才做，下一个 tick 要等当前 tick 结束，阻塞会让这条 tick 线程上所有实体停 5 s，最后以 `false` 完成、转服不发出。用 `thenAccept` 接结果。

## 13. 给插件写测试

`:testing` 给测试一个进程内的服务端：不开端口，tick 线程照常起，但不起按 20 TPS 推进 tick 的调度线程，由测试自己推进 tick，用假连接造玩家。它还没发布到 Maven，先从本仓库装进本机的 Maven 仓库；它依赖引擎本体与 `:plugin-api`，三个一起装：

```bash
./gradlew :publishToMavenLocal :plugin-api:publishToMavenLocal :testing:publishToMavenLocal --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts
```

坐标是 `io.github.bearisbug.synaxis:testing:dev`（设了环境变量 `GAME_ENGINE_VERSION` 时版本号换成它的值），引擎与 `:plugin-api` 随它传递进来。插件工程的 `build.gradle.kts` 在第 2 节之外再加：

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    testImplementation("io.github.bearisbug.synaxis:testing:dev")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}

tasks.test {
    useJUnitPlatform()
}
```

测试类标 `@EnvTest`，测试方法声明一个 `Env` 参数就拿到一个环境，方法结束时自动收尾：

- `env.createFlatInstance()` 建一个平坦实例，`env.createPlayer(实例, 位置)` 让一条假连接以玩家身份进去；`env.tick()` 推进一个 tick。服务端在这个 tick 里抛的异常让测试当场失败，不抛回引擎。
- `env.tickWhile(条件, 超时)` 一直 tick 到条件变为 false 时返回 true，超时返回 false，超时给 `null` 表示不设上限；每一轮都经 `tick()`，服务端抛的异常同样当场失败，不会空转到超时。
- `env.trackEvent(事件类, 过滤器, 对象)` 收集派发给这个对象（实体、实例）的事件，`assertSingle()`、`assertCount(n)` 这类断言读出收集到的事件，读过之后不再收集；它只摘自己的监听器，不影响这个对象上的其他监听器。
- 要的配置与 `@EnvTest` 不同时用 `Env.create(设置)` 建一个独立进程的环境。它用完要 `close()`：用 try-with-resources，或放在 `@AfterEach` 里。`close()` 先报服务端在最后一次检查之后抛的异常（第一个为 cause，其余挂在它的 suppressed 里），再停进程，检查失败也停；只调 `process().stop()` 会跳过这项检查，测试照样绿。

跑插件自己的 `onEnable` 要一个 `PluginContext`，测试里自己实现：八个抽象方法 `process()`、`events()`、`scheduler()`、`channels()`、`admission()`、`dataDirectory()`、`analytics()`、`logger()` 必须写，其余十个是默认方法，调用即抛 `UnsupportedOperationException`，用到哪个就覆盖哪个。`playerData()` 返回 `:testing` 的 `InMemoryPlayerDataStore`：不连库，没冻住的玩家每次保存都成功，`commitTogether` 在调用线程上当场执行 `apply`，宿主则排到下一个 tick 的串行段；`freeze(玩家, true)` 把玩家冻住、`freeze(玩家, false)` 解冻，冻住期间 `isFrozen` 为 true，`saveNow` 与 `commitTogether` 回 `UNAVAILABLE`，走冻结时插件该走的那条路。
