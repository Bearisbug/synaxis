# 服务端核心压测 / 长稳 harness 使用说明（2026-09-18）

`tools/loadtest/soak.py` —— 只用标准库，`python3` 跑（Python 3.10+，与 docs/TEST.md §3 的探针同口径）。
握手 / 登录 / configuration 三个阶段复用 `tools/protocol-probe/probe.py`（包 ID 仍从仓库推导），
harness 在其上加：一次维持 N 条连接、每条走圈发移动包并应答 KeepAlive / TeleportConfirm、
可配的连接 churn、把 §3 探针表的恶意子命令在负载中打进去，并用现成工具（`jcmd` / `jstat` /
`lsof` / `ps` + 引擎自带 JFR 事件 `synaxis.ServerTickTime`）采样。**不改引擎**。

## 前置

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"   # harness 要能调用 jcmd / jstat / jfr / lsof
```

## 起核心模式服务端（固定 25565）

在 **worktree 根**执行；stdin 要保持打开，停机用 `stop`：

```bash
export JAVA_TOOL_OPTIONS="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttp.nonProxyHosts=maven.aliyun.com|*.aliyun.com"
mkfifo /tmp/synaxis-soak/stdin.fifo
( sleep 86400 > /tmp/synaxis-soak/stdin.fifo & )   # 占住 fifo 写端
./gradlew :server-app:run --no-daemon --console=plain \
  --init-script gradle/init/aliyun-mirror.init.gradle.kts \
  -Dsynaxis.app.core-mode=on < /tmp/synaxis-soak/stdin.fifo > /tmp/synaxis-soak/server.log 2>&1 &
# 就绪：server.log 出现 "server-app listening"，nc -z 127.0.0.1 25565 通
# 停机：echo stop > /tmp/synaxis-soak/stdin.fifo
```

## 一条命令跑一组

harness 自己 `pgrep` 找服务端 pid，起 JFR、每 10 秒采样、每 N 秒强制一次 Full GC 量存活集
（live set），结束后 dump JFR 并算 tick 分布，产物写 `--out` 目录：
`summary.json`（全部数字）、`samples.jsonl`（每次采样一行）、`attacks.jsonl`、`soak.jfr`。
没测到的分布（`--connections 0` 没有登录、`--echo-hz 0` 没有 echo 往返）在 `summary.json` 里是
`{"count": 0}`，不影响其余数字落盘。找不到服务端进程、或 `JFR.start` 失败（`jcmd` 不在 PATH）时
harness **直接以退出码 2 拒绝跑**——采不到东西却跑完，比报错更容易被当成一次干净的长跑。

```bash
# 稳态：64 连接、20 分钟
python3 tools/loadtest/soak.py run --connections 64 --duration 1200 \
  --move-hz 10 --echo-hz 0.5 --gc-probe-interval 300 --out /tmp/synaxis-soak/steady

# churn：16 常驻 + 每秒 5 次登录/断开、10 分钟
python3 tools/loadtest/soak.py run --connections 16 --duration 600 \
  --churn-hz 5 --churn-hold 8 --gc-probe-interval 120 --out /tmp/synaxis-soak/churn

# 恶意/边界：64 连接负载下，六种攻击轮流打
python3 tools/loadtest/soak.py run --connections 64 --duration 600 \
  --attacks zero-frame,alloc-bomb,nbt-bomb,premature-finish,bad-version,deep-recursion \
  --attack-interval 1.5 --out /tmp/synaxis-soak/attack
```

从已有 JFR 单独算 tick 分布：`python3 tools/loadtest/soak.py ticks --jfr /tmp/synaxis-soak/steady/soak.jfr`。

## 采集手段

| 指标 | 来源 |
| --- | --- |
| tick p50/p99/max（整段 + 每分钟） | JFR 事件 `synaxis.ServerTickTime`，`jfr print --stack-depth 0` |
| 堆存活集 | `jcmd <pid> GC.run` 后 `GC.heap_info` 的 `used`（Full GC 后才是存活集） |
| 分代/GC 次数 | `jstat -gc <pid>`（OU/EU/MU/YGC/FGC…） |
| 线程数 | `ps -M <pid>` 行数；虚拟线程泄漏另看 JFR `jdk.VirtualThreadStart/End` |
| 文件描述符 | `lsof -p <pid>` 行数 |
| CPU / RSS / load | `ps -o %cpu,rss`、`sysctl vm.loadavg`；harness 自身 CPU 一并采，用于判断瓶颈在谁 |
| 客户端 RTT / 断线 | harness 侧 `synaxis:main` echo 往返、DisconnectPacket 计数 |

## 复现本轮的资源滞留发现（SOAK-1）

```bash
# 起服（同上，core-mode=on），然后只打 premature-finish、高频、不带常驻连接：
python3 tools/loadtest/soak.py run --connections 0 --duration 360 \
  --attacks premature-finish --attack-interval 0.02 --out /tmp/synaxis-soak/knownpacks
# 期间反复取线程 dump，数卡在 doConfiguration 的线程：
jcmd $(pgrep -f synaxis.app.Main) Thread.dump_to_file -format=json -overwrite /tmp/td.json
python3 -c "import json;d=json.load(open('/tmp/td.json'))['threadDump'];print(sum(1 for c in d['threadContainers'] for t in (c.get('threads') or []) if any('doConfiguration' in f for f in (t.get('stack') or []))))"
jcmd $(pgrep -f synaxis.app.Main) GC.class_histogram | grep 'synaxis.entity.Player$'
```
会看到卡住的线程数与存活 `Player` 数随攻击次数单调上升。判读要点：**零点是最后一次攻击，不是
洪泛开始**——每条连接的 5 分钟超时从它自己进入等待的那一刻起算，所以停止攻击后头 3 分钟一条线程
都不会释放，回收全部挤在「最后一次攻击 + 300 s」之前的最后一两分钟里。按 30–60 秒一次采到
「最后一次攻击 + 360 s」才看得到完整曲线；线程归 0 后 `Player` 不归零，留下约 1% 的残留
（SOAK-2，再打一轮攻击残留继续累积），残留的 `PlayerSocketConnection` 与 `Player` 同数：

```bash
jcmd $(pgrep -f synaxis.app.Main) GC.class_histogram \
  | grep -E 'synaxis\.entity\.Player$|PlayerSocketConnection$'
```

两条发现的数据见本目录 `report.md`。
