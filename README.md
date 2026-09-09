# game-engine

> **本项目派生自 Minestom**（[Minestom/Minestom](https://github.com/Minestom/Minestom)，Apache-2.0），
> 起点 commit `16842f1954`，并**已做大量修改**：除另有注明外，所有承自上游的文件均已被修改。
> 这是一个**硬 fork**：独立维护、**不跟随上游**，不 rebase 上游、不接受上游 PR。
> Minecraft 版本**钉死 26.2（协议 776）**，其他版本握手即拒。
> 详见根目录 `NOTICE` 与 `LICENSE`。

自研 Minecraft Java 版服务端引擎。不含任何原版玩法：原版玩法子系统已整体删除，
游戏规则属于引擎之上的游戏模块，引擎不依赖游戏。客户端强制安装配套 Fabric 模组。

包名 `dev.gameengine`，产物坐标 `dev.gameengine:game-engine`，与上游的 Maven 坐标不兼容，
也不互为替代。

## 构建

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
./gradlew check --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts
```

JDK 25 / Gradle 9.7.1。冷启动、常用命令、目录地图、升 Minecraft 版本 checklist
全部在 [`docs/ENGINE.md`](docs/ENGINE.md)。

## 仓库结构

| 路径 | 内容 |
| --- | --- |
| `src/` | 引擎主源码（`dev.gameengine`）与测试 |
| `testing/` | `@EnvTest` 测试夹具模块 |
| `smoke/` | 冒烟启动器，协议验收用的最小服务端 |
| `jmh-benchmarks/`、`jcstress-tests/` | 基准与并发压力测试 |
| `build-src/` | Gradle 约定插件（toolchain、Error Prone、发布、ABI 检查） |
| `gradle/` | 版本目录与构建 init 脚本 |
| `tools/` | 运维与验收脚本，协议探针在 `tools/protocol-probe/probe.py` |
| `docs/` | 设计文档、测试文档、引擎手册、调研与审计 |

`docs/DESIGN.md` 是单一事实源，`docs/TEST.md` 是验收执行脚本与轮次台账。

## 许可

Apache License, Version 2.0，见 [`LICENSE`](LICENSE)。上游署名与修改声明见 [`NOTICE`](NOTICE)。
