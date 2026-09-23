# jmh-benchmarks

两类基准：

| source set | 内容 | 怎么跑 |
| --- | --- | --- |
| `src/jmh` | JMH 微基准（palette、光照 BFS、网络编码等） | `./gradlew :jmh-benchmarks:jmh` |
| `src/worldbench` | `LargeWorldBench`：整图级测量（区块包大小、首次序列化、存活堆、整图 relight、单处改动后的 3×3 重算） | `./gradlew :jmh-benchmarks:worldBench`，见下 |

`check` 只编译这两个 source set（Error Prone 要过），不运行它们。

## LargeWorldBench

用来重测 `docs/research/large-world-2026-09/README.md` §8.1 列为「未核实」的数字。结果与对照写在
`docs/research/large-world-2026-09/bench-2026-09.md`，原始 JSON 在同目录 `bench-2026-09-results.json`。

### 复跑

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
# 全套（2026-09 在 M4 Pro 12 核、有并行负载时约 8 分钟），结果写到 jmh-benchmarks/build/worldbench/full.json
./gradlew :jmh-benchmarks:worldBench --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts \
    -PworldBenchArgs="--out build/worldbench/full.json"
# 冒烟（约 15 秒）：三个小作业，验证程序能跑通
./gradlew :jmh-benchmarks:worldBench --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts \
    -PworldBenchArgs="--quick --heap 3g"
# 只跑指定作业：作业之间用分号，作业内参数用逗号
./gradlew :jmh-benchmarks:worldBench --no-daemon --init-script gradle/init/aliyun-mirror.init.gradle.kts \
    -PworldBenchArgs="--jobs relight,h=256,t=natural,n=4096;local,h=256,t=natural"
```

`JAVA_TOOL_OPTIONS` 里的代理参数只为 Gradle 解析依赖，会原样传到子 JVM 并记进 `jvm.inputArguments`，不影响测量。

### 驱动参数

| 参数 | 默认 | 含义 |
| --- | --- | --- |
| `--out <path>` | `build/worldbench/worldbench-<时间>.json` | 结果文件，相对 `jmh-benchmarks/` |
| `--heap <size>` | `6g` | 每个子 JVM 的 `-Xms` 与 `-Xmx` |
| `--jobs <list>` | 源码里的 `DEFAULT_JOBS` | 作业列表 |
| `--quick` | —— | 改跑 `QUICK_JOBS` |

### 作业与参数

每个作业在独立子 JVM 里跑：`-Xms6g -Xmx6g -XX:+UseG1GC -XX:+AlwaysPreTouch`，堆固定大小，避免扩容干扰堆读数；
一个作业的垃圾、JIT profile 不会带进下一个作业。

| 参数 | 默认 | 含义 |
| --- | --- | --- |
| `h` | 384 | 世界高度。384 用原版布局 minY=-64；256 与 64 从 y=0 起（与 M2 对局世界一致） |
| `t` | natural | 地形：`natural`（噪声高度场 + 基岩/深板岩/石头/泥土/草/沙/水 + 约 1.2% 矿石 + 噪声隧道 + 约每区块一棵橡树）、`solid`（同一地表，地下无矿无洞）、`flat40`（M2 RTS 地图形状：石头到 y=40，每 64 列一根 3 格柱子） |
| `p` | opt | `opt`：生成后对每个 section 做 `Palette#optimize(SIZE)`，等于从网络格式（.synx / Anvil）解码出来的间接调色板；`setall`：保持 `setAllRelative` 生成的直接调色板（15 bit/方块） |
| `grid` / `n` | 32 / —— | `packet`、`local` 用 grid×grid 区块；`relight` 用 n 个区块（必须是平方数） |
| `rounds` / `warmup` | 3 / 1 | 测量轮数与丢弃的预热轮数；每轮新建世界 |
| `samples` / `warmupSamples` | 300 / 60 | `local` 的方块改动次数与丢弃的预热次数 |

三类作业：

- `packet`：每轮先建 DynamicChunk 世界，逐区块首次序列化（`CachedPacket#body`，含成帧与 zlib，阈值 256）；再建 LightingChunk 世界，逐区块首次序列化（光照在这一步懒算，等同玩家进服时的路径），然后 `invalidate()` 丢掉包缓存、再序列化一遍（光照已算好，只剩编码）。耗时与字节数只统计内圈 (grid-2)² 个区块，边上一圈缺邻居。
- `relight`：先用一张 32×32 世界跑一次 `LightingChunk.relight` 预热，然后每轮新建 n 区块世界，量 `relight(instance, 全部区块)` 的墙钟时间、进程 CPU 时间和堆增量。
- `local`：32×32 世界整图 relight 并全部序列化一次后，在内圈随机列的地表轮流做三种改动（放萤石、放石头、挖掉地表方块），每次改动后量两种 3×3 重算：A「懒重算」= 对 3×3 区块调 `createLightData(true)`，只重算被改动作废的 section（新观察者拿区块包时走的就是这条）；B「整块重算」= `LightingChunk.relight(instance, 3×3 区块)`。

### 测堆方法

`System.gc()` 两次后读 `totalMemory - freeMemory`，与 `Match#sampleLiveHeap` 同法；若两次调用都没触发回收（例如加了 `-XX:+DisableExplicitGC`）直接报错，不产出假数字。
每个堆读数都是同一 JVM 内「建世界前」与「建世界后」的差，除以区块数。预热轮吸收类加载和注册表懒初始化的一次性分配，
不预热时这部分会摊进第一张世界（64 区块世界实测多出约 1 MiB，KiB/区块偏大约 60%）。包缓存由 `SoftReference` 持有，内存紧张时会被回收，所以「带包缓存」一项是上界。

`breakdown` 字段是直接数出来的构成：单值 / 间接 / 直接调色板 section 数、调色板数据 KiB（按 bitsPerEntry 算 long[] 大小）、
各 section 光照对象里保留的非共享 `byte[2048]` 个数（反射读 `content` / `contentPropagation` / `contentPropagationSwap`，排除 `EMPTY_CONTENT` 与 `CONTENT_FULLY_LIT` 两个共享哨兵）。

### 统计口径

分布字段都是 `{n, median, p95, mean, min, max}`，p95 取最近秩。`*Ms` 是墙钟毫秒；relight 与懒光照会用到
`LightingChunk` 的 work-stealing 线程池，所以另记进程 CPU 时间（`*CpuMs`）。`wholePass` 是整遍序列化的总耗时除以区块数，
比逐区块分布更能代表平均成本：懒光照下第一个碰到某片区域的区块会顺带算邻居，逐区块数字偏斜。
`loadAverageBefore` / `loadAverageAfter` 是作业前后的 1 分钟系统负载，高于核数一半时墙钟数字只能当上界。
`summarize_worldbench.py <json>`（在 `.venv` 里跑，只用标准库）把结果打印成 bench-2026-09.md 用的表格。
