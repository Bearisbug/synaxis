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
| `events()` | `addListener(事件类, 监听器)`（`NORMAL`，已取消的事件照样收到）；`addListener(事件类, 优先级, ignoreCancelled, 监听器)`，六档 `LOWEST` → `MONITOR` 依次执行，所有插件同档的排在一起，`MONITOR` 只读不改（约定，不拦截）；`forInstance(实例)` 只收该实例的事件，在实例所属 tick 线程上执行，且先于全局作用域的全部插件监听器；`PlayerPluginMessageEvent`、`PlayerCustomClickEvent`、`PlayerDisconnectEvent`、`PlayerPacketEvent` 及其超类型在玩家没有实例时也可能派发，不能按实例监听，注册即抛 `IllegalArgumentException`。冻结的玩家（`PlayerDataStore#isFrozen`）的 `PickupItemEvent` 与 `ItemDropEvent`：宿主每个事件只判定一次冻结，读到冻结就取消两次：一次在根节点上、先于任何插件监听器，一次在宿主节点下的优先级 3、全局 `HIGHEST` 之后与 `MONITOR` 之前；冻结在一次派发中途落地时，这一次照判定放行到底。发放物品的监听器跳过已取消的事件，以是否已取消为准，不另查 `isFrozen`；撤销这次取消（`setCancelled(false)`）不受支持，撤销之后、第二次取消之前执行的监听器看到的是放行。监听器挂在宿主按插件、按档建的子节点上 | 该插件的全部子节点（含实例下的）`removeChild` |
| `scheduler()` | `scheduleTask(任务, 延迟, 重复)` | 逐个 `cancel()` |
| `channels()` | `register(名字, handler)` / `send(玩家, 名字, 字节)` | 注销该插件的全部通道 |
| `admission()` | `register(准入回调)`，决定连接进哪个对局实例、属哪个队伍 | 注销该回调 |
| `dataDirectory()` | `plugins/<plugin_id>/`，宿主保证存在 | 不删（是你的数据） |
| `analytics()` | `record(事件名, 属性)`，写进宿主的 `logs/events-YYYYMMDD.jsonl` | 无状态，不需要收回 |
| `transfers()` | 把玩家原版转服到外部服务器、读写本插件命名空间的 Cookie，见第 12 节 | 不收回：已排上的转服在玩家下一个 tick 照常做完（还在载入出生区块的玩家至多 5 s） |
| `play()` | 场景、HUD 与动作（`docs/DESIGN-PLAY.md` `API-PLAY-005`）：`enter` / `leave` / `currentScene` 让玩家进出场景，只有本进程的场景所有者（占着准入槽的插件）能调；`hud(玩家)` 设本插件命名空间下的小部件；`onAction(动作 id, handler)` 只能在 `onEnable` 里注册 | 收回它注册的动作 |
| `instances()` | 宿主托管的玩法实例（`docs/DESIGN-PLAY.md` `API-PLAY-006`）：`create(InstanceSpec)` 建大厅或一局，`owned()` 列出本插件建的、还没关闭的实例；实例的 `move`、`start`、`end`、`settled` 推进五态生命周期，宿主每次转移写一行台账 | 强制关闭它全部没关闭的实例（`onEnable` 失败时原因是 `plugin_disabled`，停机时是 `shutdown`），不调插件回调 |
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
| 实体与玩家的事件（含 `transfers().transfer(…)` 让宿主派发的 `OutgoingTransferEvent`，不论从哪条线程调用）、实体的休眠策略（`Entity#setDormancyPolicy`）、玩家发来的通道负载、`scheduler().runOn(实体, …)` | 该实体所属的 tick 线程 | 读写这个实体 |
| `scheduler().scheduleTask(…)` | tick 调度线程，所有插件的任务一个接一个跑 | 每 tick 的全局逻辑；它是整个 tick 的串行段，要扩到几百人就按格或按实体拆开 |
| `playerData().commitTogether(…, apply)` 的 `apply` | tick 调度线程，提交写进库之后下一个 tick 的串行段，只跑一次 | 改每个参与者，不论他们分在哪条 tick 线程；参与者这时可能已经离开，先查 `isRemoved()`（`docs/DESIGN-PROD.md` `API-PROD-005`） |
| `scheduler().runAsync(…)` | 自己的虚拟线程 | 阻塞的活（读写库、HTTP）；不碰实体与实例，结果用 `runOn` 送回去 |
| 服务器列表事件 `ServerListPingEvent` | 发请求那条连接的读线程（虚拟线程） | 只改事件本身（MOTD、人数、样本、图标，`docs/DESIGN-PROD.md` `API-PROD-024`）；不碰实体与实例 |

对另一个实体的影响（攻击、交易）不要在自己的线程上直接改对方：对方可能在另一条线程上，用 `scheduler().runOn(对方, 任务, TaskSchedule.immediate())` 投递，任务在对方下一次 tick 时在对方的线程上执行。只有必须在同一 tick 读到对方状态时才用 `对方.acquirable().sync(…)`，所有跨线程的 `sync` 都排同一把进程级的锁，用多了就退化成单线程。`sync` 持有对方期间，引擎不会把对方交给别的 tick 线程；从 `runAsync` 这类非 tick 线程持有时，持着它跨过 tick 边界会推迟下一个 tick 的开始，所有 tick 线程一起等，所以回调里不要阻塞（`docs/DESIGN-PROD.md` `API-PROD-016` 元素换线程一条）。

`teleport` 与 `setInstance` 要先载入区块时，返回的 future 在区块载入、实体所属线程放开它之后才完成。在这个实体所属的 tick 线程上（它的事件监听器、`runOn` 给它的任务），以及任何 `sync` 或 `lock` 回调里，都不要 `join()` 或 `get()` 这个 future。在所属线程上（`sync` 持有的就是这个实体时也算）等的正是当前线程，引擎对方法直接返回的 future 当场抛 `IllegalStateException`；`sync` 或 `lock` 从别的线程取到元素时，当前线程持着所有跨线程 `sync` 共用的那把锁，续延要先拿它，引擎拦不住，等下去整个进程停止 tick；在它上面 `thenRun` 得到的 future 引擎同样拦不住。要接后续，用 `thenRun`。

实体的属性（`LivingEntity#getAttribute` 取到的 `AttributeInstance`）任意线程可读，只在实体所属的线程上改：每次修改当场回调实体，发属性包，降最大生命值时还压低当前生命值。不拥有实体的线程降最大生命值，压生命值排到实体下一个 tick、在它的线程上做，在那之前读到的生命值还是旧的；实体还没分到 tick 线程时（例如首次出生的 `PlayerSpawnEvent`），tick 线程以外的线程降最大生命值照常当场压。

区块的观察者（`Chunk#getViewers()`，即 `sendPacketToViewers` 发给谁）是区块环里有这个区块的玩家（引擎给他发区块的那一圈），不是位置在一定半径内的玩家：区块更新限流器留住区块环中心时两者不同。直接改区块内容（`Chunk#setBlock` 这类）要在这个区块的写锁里改，改完再发更新包。读锁不能升级为写锁：只持读锁时调 `lockWriteLock()`，或调会取写锁的 `Instance#setBlock`、`Instance#setBiome`，抛 `IllegalStateException`（`tryLockWriteLock` 等满时限后返回 false），先查后改要从一开始就拿写锁（`docs/DESIGN-PROD.md` `API-PROD-020` 区块读锁一条）；引擎发区块时在同一把写锁里取包并交给连接，所以一次改动要么在区块包里，要么排在区块包后面（`docs/DESIGN-PROD.md` `API-PROD-020` 区块包与方块更新的先后一条）。持着区块的锁给玩家排区块（`Chunk#sendChunk`）不会卡住发送：发送一批最多等区块锁 1 ms，等不到的区块在锁空出来的那个 tick 发，可能不止晚一个 tick，同一批的其他区块照发。例外是 `PlayerChunkLoadEvent`：它在这名玩家的发送队列锁里派发，监听器里改方块（`Instance#setBlock`）会一直等那个区块的写锁；这时另一条线程若正持着那个区块的锁、又给这名玩家排区块，两边互等。监听器里要改方块，用 `scheduler().runOn(玩家, 任务, TaskSchedule.immediate())` 投递到这名玩家的下一次 tick 再改。

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
| `transfer(玩家, host, port)` | 发原版 `Transfer` 包（host、port 按监听器改过的），把客户端送去外部服务器，不带票据；玩家断开后宿主按普通登出保存。future 以 `true` 完成 | 玩家不在 PLAY（还在 configuration 或已断开）、离上一次转服不满 `synaxis.app.transfer-cooldown-ms`（默认 5 s）、或监听器取消了 `OutgoingTransferEvent` 时以 `false` 完成，什么都不发、冷却不记；5 s 内玩家没有 tick（已离开）同样以 `false` 完成；`host` 空白或 `port` 不在 1～65535 在调用线程上抛 `IllegalArgumentException` |
| `wasTransferred(玩家)` | 客户端握手时是否自称由别的服务器转来 | 这是客户端自报的，不能当来源证明 |
| `storeCookie(玩家, 键, 值)` | 把至多 5,120 B 存在客户端上，客户端转服时带去下一台服务器 | 键不在本插件命名空间或值超过 5,120 B 时抛 `IllegalArgumentException`，不发包 |
| `fetchCookie(玩家, 键)` | 向客户端要回一个 Cookie；future 以值完成，客户端没有这个 Cookie 时以 `null` 完成 | 5 s 内没回答（玩家断开，或对同一玩家同一个键在回答前又要了一次）以 `TimeoutException` 完成；键不在本插件命名空间时抛 `IllegalArgumentException` |

Cookie 键必须是 `<plugin_id>:<路径>`，如 `Key.key("hello", "lobby-token")`。别的命名空间一律拒绝，包括宿主的 `synaxis:`：宿主把转服票据存在 `synaxis:transfer`，插件写它会覆盖票据，读它会抢走验票在等的那次应答，两种都会让玩家的转服被拒。

冷却是整个进程一张表，M3-E 起宿主的分片间转服也记在这张表里：玩家刚以票据转入或刚被宿主转出，5 s 内 `transfer` 以 `false` 完成；插件转走的玩家 5 s 内宿主也不再签票据。要把玩家送去另一个 Synaxis 分片不能用 `transfer`：分片只收带宿主票据的转入，没有票据的会被拒。M3-E 起跨分片转服由宿主的关卡门、GM 命令 `gm.transfer` 与管理接口 `synaxis:transfer` 发起，在那之前没有发起跨分片转服的入口。

`transfer` 的 future 在玩家所属的 tick 线程上完成；调用时玩家就不在 PLAY 的，拿到手时已经完成，5 s 没等到 tick 的在 JDK 的计时线程上完成。`fetchCookie` 的 future 在读到回答的连接线程上完成，超时则在 JDK 的计时线程上完成。拿到值之后要改实体，用 `scheduler().runOn(玩家, …)` 送回玩家所属的线程（第 10 节）。

**不要在 tick 线程上 `join()` 或 `get()` 这个 future**（例如在玩家事件监听器里写 `transfer(p, h, port).join()`）：转服要等玩家的下一个 tick 才做，下一个 tick 要等当前 tick 结束，阻塞会让这条 tick 线程上所有实体停 5 s，最后以 `false` 完成、转服不发出。用 `thenAccept` 接结果。
