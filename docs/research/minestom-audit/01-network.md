# Minestom 网络与协议层只读审计报告

- **仓库**：`/Users/bug/Documents/Projects/Minecraft/Minestom`（upstream master fork，HEAD `16842f1954`，2026-09-04）
- **范围**：`net.minestom.server.network.**`（303 文件）+ `net.minestom.server.listener.**` + 与之直接耦合的 `ConnectionManager` / `Player#UNSAFE_init` / `PacketViewableUtils` / `EntityTrackerImpl`
- **方法**：全量阅读 + grep + `git blame` / `git show` + 对照 [Java Edition protocol](https://minecraft.wiki/w/Java_Edition_protocol)；**未执行任何构建或测试**（按约束禁止 Gradle），所有判定均为静态分析
- **只读**：未修改仓库任何文件

---

## ① TL;DR

这一层的**写路径**（`NetworkBufferTemplate` 字节码生成、`PacketWriting` 帧格式、`ViewableStorage` 批量写、`CachedPacket` 的 acquire/release 缓存）设计扎实，读写对称性在**实际被使用的**类型上基本正确；`RawBytes`、`BinaryTagTypeImpl`、字符串等历史 OOM 高危点都已补上校验（`#3168`、`#3198` 的修复痕迹清晰可见）。真正的问题集中在**错误路径**：解析失败、缓冲区不足、状态机被客户端提前推进这三类"异常分支"几乎没有被认真设计过，普遍表现为"抛一个语义不对的 RuntimeException，让最外层 `catch (Throwable)` 兜底"。

最严重的三个问题：

1. **P0-1**：客户端在 CONFIGURATION 阶段抢跑发 `ClientFinishConfigurationPacket`（服务端还在 `doConfiguration` 里等 known-packs，默认窗口 **5 分钟**），会让 `Player#UNSAFE_init()` 在 `pendingInstance == null` 上抛 NPE；而 `ServerProcessImpl.TickerImpl#tick` 对 `connection().tick()` **没有 try/catch**，NPE 会把当前这一 tick 的实例更新、实体更新、`PacketViewableUtils.flush()` 全部跳过。单个恶意连接即可稳定丢 tick，可重复。
2. **P0-2**：开启 `minestom.proxy-protocol` 时，一个声明了 `payloadLength = 0xFFFF` 的 PROXY v2 头会让 `ProxyProtocolDecoder` 永远返回 `NEED_MORE`（读缓冲固定 16383 字节，永远凑不满 65551），读循环退化成 `readChannel` 返回 0 的**忙等死循环**，一个连接吃满一个核。
3. **P1-1/P1-2**：`packetLength == 0`（1 个字节 `0x00`）和超长 VarInt 包长（5 个 `0xFF`）分别会触发 `IndexOutOfBoundsException`（零长 MemorySegment 切片）和 `IllegalArgumentException`（`readBuffer.resize(5)`）。前者与 issue **#3320** 的第三段堆栈**逐字符吻合**，后者是同一处设计缺陷的另一半，且 open PR #3347 **没有覆盖**。

统计：**P0 × 2，P1 × 8，P2 × 11，P3 × 4**。

---

## ② 发现清单

### P0-1 客户端抢跑 `ClientFinishConfigurationPacket` → `UNSAFE_init` NPE → 整个 tick 被跳过

- **严重度**：P0（远程可触发的服务端拒绝服务）
- **位置**：
  - `src/main/java/net/minestom/server/entity/Player.java:339-344`
  - `src/main/java/net/minestom/server/network/ConnectionManager.java:359-375`
  - `src/main/java/net/minestom/server/ServerProcessImpl.java:271-300`
  - `src/main/java/net/minestom/server/listener/preplay/LoginListener.java:236-238`

**问题描述**

`Player#UNSAFE_init()` 无条件解引用 `pendingInstance`：

```java
// Player.java:339-344
public CompletableFuture<Void> UNSAFE_init() {
    final Instance spawnInstance = this.pendingInstance;
    this.pendingInstance = null;
    this.removed = false;
    this.dimensionTypeId = DIMENSION_TYPE_REGISTRY.getId(spawnInstance.getDimensionType()); // NPE
```

`pendingInstance` 只在 `ConnectionManager#doConfiguration` 的**最后一步**被赋值（`ConnectionManager.java:294` 的 `player.setPendingOptions(...)`，紧接着才 `sendPacket(new FinishConfigurationPacket())`）。而 `LoginListener#finishConfigListener` 对客户端发来的 `ClientFinishConfigurationPacket` **没有任何前置条件检查**，直接 `transitionConfigToPlay(player)` 入队；下一 tick 的 `updateWaitingPlayers` 就会调 `UNSAFE_init()`。

关键放大器在 tick 循环：

```java
// ServerProcessImpl.java:273-282
public void tick(long nanoTime) {
    ...
    connection().tick(nanoTime);   // 无 try/catch
    serverTick(nanoTime);          // ← NPE 后这些全部被跳过
```

异常一路冒到 `TickSchedulerThread.run` 的 `catch (Throwable)`（`TickSchedulerThread.java:32-34`），只被记录，但 `serverTick()`（实例/区块/实体 tick）、`scheduler().processTickEnd()`、`PacketViewableUtils.flush()` 都不会执行——**这一 tick 全服的实体更新和已排队的 viewable 包全部丢失**。

**触发场景**

1. 正常握手 → LoginStart → 收到 LoginSuccess → 发 `ClientLoginAcknowledged`。
2. 服务端起虚拟线程跑 `doConfiguration`，发出 brand + `SelectKnownPacksPacket` 后阻塞在 `knownPacksFuture.get(ServerFlag.KNOWN_PACKS_RESPONSE_TIMEOUT, ...)`——默认 **5 分钟**（`ServerFlag.java:23`）。
3. 客户端**不回**known-packs，直接发 `ClientFinishConfigurationPacket`（CONFIGURATION 阶段 id）。
4. 下一 tick：`playWaitingPlayers.drain` → `player.isOnline()` 为 true → `playPlayers.add` → `UNSAFE_init()` → NPE。

窗口极大，无需精确竞态。断开重连即可重复。

**影响**

单连接即可周期性丢 tick；N 个并发恶意连接可使服务端几乎不再推进世界状态。同时 `configurationPlayers.remove` / `playPlayers.add` / `keepAlivePlayers.add` 已经执行过，玩家被留在不一致的集合状态里。

**建议修复**

1. `finishConfigListener` 增加前置条件：仅当服务端已发出 `FinishConfigurationPacket`（即 `connection.getServerState() == PLAY`）时才接受，否则 `disconnect()`。
2. `UNSAFE_init()` 对 `pendingInstance == null` 显式抛带上下文的 `IllegalStateException` 并 `kick`，不要让它变成 NPE。
3. `ServerProcessImpl.TickerImpl#tick` 给 `connection().tick(nanoTime)` 套 `try/catch` + `exception().handleException(e)`（与 `serverTick` 里对 `instance.tick` 的处理一致，`ServerProcessImpl.java:306-310`）。

**建议的复现测试**

```java
@EnvTest
public class ConfigAckAbortsTickIntegrationTest {
    @Test
    public void finishConfigurationBeforeReadyMustNotBreakTick(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 40, 0));
        // 模拟客户端抢跑：pendingInstance 已被首次 UNSAFE_init 消费掉，现在为 null
        MinecraftServer.getConnectionManager().transitionConfigToPlay(player);
        // tick 必须整体成功，且 instance 仍被 tick 到
        assertDoesNotThrow(env::tick);
        assertTrue(player.isOnline());
    }
}
```

**判定**：【读代码确认】（NPE 路径与 tick 未捕获均为纯代码事实；"每 tick 可重复"部分标【疑似】，取决于客户端能多快回到 CONFIGURATION）
**关联**：无直接 issue；与 open PR #3347 无重叠（#3347 只加固了读线程侧）

---

### P0-2 PROXY protocol v2 声明的 payloadLength 超过读缓冲容量 → 读线程 100% CPU 忙等

- **严重度**：P0（远程 CPU 拒绝服务，需 `minestom.proxy-protocol=true`）
- **位置**：
  - `src/main/java/net/minestom/server/network/player/ProxyProtocolDecoder.java:102-109`
  - `src/main/java/net/minestom/server/network/player/PlayerSocketConnection.java:91-92, 115-138`
  - `src/main/java/net/minestom/server/network/NetworkBufferImpl.java:256-267`

**问题描述**

```java
// ProxyProtocolDecoder.java:107-109
final int payloadLength = fixedHeader.payloadLength();   // UNSIGNED_SHORT，最大 65535
final int headerLength = V2_HEADER_LENGTH + payloadLength;
if (buffer.readableBytes() < headerLength) return new Result(Status.NEED_MORE, originalAddress);
```

```java
// PlayerSocketConnection.java:120-123
if (ServerFlag.PROXY_PROTOCOL && !attemptedProxyProtocolDetection) {
    final ProxyProtocolDecoder.Result result = ProxyProtocolDecoder.parse(remoteAddress, readBuffer);
    if (result.status() == ProxyProtocolDecoder.Status.NEED_MORE) return;   // ← 不处理任何包，也不扩容
```

`readBuffer` 初始容量是 `ServerFlag.POOLED_BUFFER_SIZE = 16383`（`PlayerSocketConnection.java:91-92`），而扩容只发生在 `processPackets` 的 `Failure` 分支——`NEED_MORE` 时直接 `return`，永远走不到。于是 `readableBytes()` 上限就是 16383，永远 `< 65551`。

缓冲写满后，`readChannel` 的 `bufferSlice(segment, writeIndex, writableBytes)` 长度为 0，阻塞 `SocketChannel.read` 对零剩余的 ByteBuffer **立即返回 0**（不是 -1，所以不会抛 `EOFException`），`advanceWrite(0)`，回到 `parse` 又是 `NEED_MORE`。`Server#playerReadLoop` 无条件重来。

**触发场景**

`\r\n\r\n\0\r\nQUIT\n` + `0x21` + `0x11` + `0xFF 0xFF`（payloadLength=65535），再灌 16367 字节垃圾把缓冲填满，然后停止发送。

**影响**

一条 TCP 连接吃满一个 CPU 核（虚拟线程绑在载体线程上空转），且不会超时（`SO_TIMEOUT` 对 `SocketChannel` 的阻塞读不生效）。攻击成本 ~16 KB。

**建议修复**

1. `parse` 在 `headerLength > buffer.capacity()` 时抛 `IOException("PROXY v2 header too large")`（v2 头合法上限是 16+65535，但对 Minecraft 场景可直接限制到几百字节）。
2. `read()` 里给 proxy 探测阶段设一个总字节上限（如 1 KB），超出即 `throw new IOException`。
3. 兜底：`readChannel` 在 `writableBytes() == 0` 时抛异常而不是返回 0。

**建议的复现测试**

```java
@Test
public void proxyV2OversizedPayloadMustNotHang() throws Exception {
    var buffer = NetworkBuffer.resizableBuffer(16383, MinecraftServer.getRegistries());
    buffer.write(NetworkBuffer.RAW_BYTES, new byte[]{0x0D,0x0A,0x0D,0x0A,0x00,0x0D,0x0A,0x51,0x55,0x49,0x54,0x0A});
    buffer.write(NetworkBuffer.BYTE, (byte) 0x21);   // v2 / PROXY
    buffer.write(NetworkBuffer.BYTE, (byte) 0x11);   // TCP over IPv4
    buffer.write(NetworkBuffer.UNSIGNED_SHORT, 0xFFFF);
    // 期望：抛 IOException，而不是无限 NEED_MORE
    assertThrows(IOException.class, () ->
        ProxyProtocolDecoder.parse(new InetSocketAddress("127.0.0.1", 1), buffer));
}
```

**判定**：【读代码确认】（忙等成立的前提是 `channel.read` 对零剩余 buffer 返回 0，这是 NIO 契约）
**关联**：无

---

### P1-1 `packetLength == 0` 未被拒绝 → 零长 MemorySegment 切片 → IndexOutOfBoundsException（issue #3320）

- **严重度**：P1（远程可触发的未处理异常 + 日志洪水）
- **位置**：`src/main/java/net/minestom/server/network/packet/PacketReading.java:163-179`；`src/main/java/net/minestom/server/network/NetworkBufferTypeImpl.java:253-267`

**问题描述**

```java
// PacketReading.java:162-179
final int maxPacketSize = maxPacketSize(state);
if (packetLength < 0) throw new DataFormatException("Packet length negative: " + packetLength);
if (packetLength > maxPacketSize) throw new DataFormatException("Packet too large: " + packetLength);
if (buffer.readableBytes() < packetLength) { ... }
final PacketRegistry<? extends T> registry = parser.stateRegistry(state);
final long offset = buffer.advanceRead(packetLength);
final NetworkBuffer slice = buffer.slice(offset, packetLength, 0, packetLength).readOnly();
final T packet = readFramedPacket(slice, registry, compressed, maxPacketSize);
```

`packetLength == 0` 通过全部三道检查：不是负数、不超上限、`readableBytes() >= 0`。于是 `buffer.slice(offset, 0, 0, 0)` 产生一个 **byteSize 为 0 的 MemorySegment**，`readPayload` 在其上读包 id：

```java
// NetworkBufferTypeImpl.java:254-259
long index = buffer.readIndex();          // 0
for (int shift = 0; shift <= 28; shift += 7) {
    byte b = impl(buffer)._getByte(index++);   // segment.get(JAVA_BYTE, 0) on byteSize=0
```

抛出 `IndexOutOfBoundsException: Out of bound access on segment MemorySegment{... byteSize: 0}; new offset = 0; new length = 1`。

这**逐字符对应** issue #3320 中第三段堆栈（`byteSize: 0` / `new offset = 0` / `new length = 1`，帧为 `NetworkBufferImpl._getByte` ← `VarIntType.read` ← `PacketReading.readPayload:219`）。`git show d4d3698fa0`（"fix: Prevent negative packet length (#3198)"）当时只补了 `< 0`，漏了 `== 0`。

**触发场景**

任何连接、任何阶段，发送**一个字节 `0x00`**。

**影响**

`processPackets` 只 catch `DataFormatException`，IOOBE 一路冒到 `Server#playerReadLoop:130` 的 `catch (Throwable)` → `handleException`（默认实现是 `Throwable::printStackTrace`，`ExceptionManager.java:43`）+ 断开。攻击者循环建连即可产生无限量完整堆栈日志（磁盘/日志 IO DoS），且掩盖真实错误。issue #3320 中另外两段 `Packet id 0x# isn't registered!`（`PacketRegistryImpl.java:73-75` 抛 `IllegalStateException`）是同一类问题：**协议层错误被表达成内部错误**。

**建议修复**

```java
if (packetLength <= 0) throw new DataFormatException("Packet length must be positive: " + packetLength);
```

同时把 `PacketRegistryImpl#packetInfo(int)` 的 `IllegalStateException` 改成 `DataFormatException`（或新增一个受检的 `ProtocolException`），让 `processPackets` 能走"安静断开"路径。

**建议的复现测试**

```java
@EnvTest
public class ZeroLengthPacketTest {
    @BeforeAll static void setup(Env env) { assertNotNull(env.process().registries()); }

    @Test
    public void zeroLengthFrameIsRejectedAsProtocolError() {
        var buffer = PacketVanilla.PACKET_POOL.get();
        buffer.write(NetworkBuffer.VAR_INT, 0); // 单字节 0x00
        // 现状：抛 IndexOutOfBoundsException(byteSize: 0)
        // 期望：DataFormatException
        assertThrows(DataFormatException.class,
                () -> PacketReading.readClients(buffer, ConnectionState.HANDSHAKE, false));
    }
}
```

**判定**：【读代码确认】
**关联**：issue **#3320**；open PR **#3347** 把 `processPackets` 的 catch 放宽到 `Throwable` 并按状态分级静默，**缓解了症状但没有修根因**（0 长度包依然产生零长切片）

---

### P1-2 畸形 VarInt 包长被误判成"缓冲区太小" → `readBuffer.resize(5)` → IllegalArgumentException

- **严重度**：P1（远程可触发的未处理异常，5 字节成本）
- **位置**：`src/main/java/net/minestom/server/network/packet/PacketReading.java:149-155`；`src/main/java/net/minestom/server/network/player/PlayerSocketConnection.java:185-191`；`src/main/java/net/minestom/server/network/NetworkBufferImpl.java:191-201`

**问题描述**

`VarIntType.read` 用**同一种异常**表达两件语义完全不同的事：读越过 segment 边界（FFM 抛 IOOBE），和"VarInt 超过 5 字节，协议非法"（`NetworkBufferTypeImpl.java:266` 显式 `throw new IndexOutOfBoundsException("VarInt too long")`）。`PacketReading` 把两者都翻译成"请把缓冲扩到 5 字节"：

```java
// PacketReading.java:149-155
try {
    packetLength = buffer.read(VAR_INT);
} catch (IndexOutOfBoundsException _) {
    return new Result.Failure<>(MAX_VAR_INT_SIZE);   // = 5
}
```

```java
// PlayerSocketConnection.java:185-191
case PacketReading.Result.Failure<ClientPacket> failure -> {
    final long requiredCapacity = failure.requiredCapacity();     // 5
    assert requiredCapacity > readBuffer.capacity() : ...;        // -ea 下先 AssertionError
    readBuffer.resize(requiredCapacity);                          // resize(5)
}
```

`readBuffer` 容量至少 16383，于是 `NetworkBufferImpl.resize:196` 的 `if (newSize < capacity) throw new IllegalArgumentException("New size is smaller than the current size")` 必然触发。

注意：即使是"合法"的那一条语义（缓冲装不下 5 字节的长度前缀），`resize(5)` 同样是错的——这个分支**在任何情况下都不可能正确执行**。

**触发场景**

发送 `FF FF FF FF FF`（5 个带 continuation 位的字节）。任意连接阶段。

**影响**

同 P1-1：完整堆栈 + 断开；日志信息还具有误导性（报的是"容量"问题）。**open PR #3347 不覆盖此路径**——它的 `try/catch (Throwable)` 只包住 `PacketReading.readPackets(...)`，`switch (result)` 里的 `resize` 在 try 之外。

**建议修复**

区分两种异常。最小改动：让 `VarIntType.read` 在超长时抛 `DataFormatException`（或专门的 `MalformedVarIntException`），`PacketReading` 只把真正的边界 IOOBE 翻译成 `Failure`；同时 `Failure` 分支改成 `readBuffer.resize(Math.max(requiredCapacity, readBuffer.capacity() * 2))` 并加保护。

**建议的复现测试**

```java
@Test
public void overlongVarIntLengthIsAProtocolErrorNotACapacityFailure() {
    var buffer = NetworkBuffer.resizableBuffer(16383, MinecraftServer.getRegistries());
    for (int i = 0; i < 5; i++) buffer.write(NetworkBuffer.BYTE, (byte) 0xFF);
    // 现状：返回 Result.Failure(5)，调用方 resize(5) 抛 IllegalArgumentException
    var result = assertDoesNotThrow(() -> PacketReading.readClients(buffer, ConnectionState.HANDSHAKE, false));
    assertFalse(result instanceof PacketReading.Result.Failure<?> f && f.requiredCapacity() < buffer.capacity(),
            "Failure.requiredCapacity must always exceed current capacity");
}
```

**判定**：【读代码确认】
**关联**：issue #3320（同一处错误处理设计）；PR #3347（**未覆盖**）

---

### P1-3 `NetworkBufferImpl#compress` 不检查 `deflater.finished()` → 压缩帧被静默截断

- **严重度**：P1（数据损坏，客户端被踢"Network Protocol Error"）
- **位置**：`src/main/java/net/minestom/server/network/NetworkBufferImpl.java:305-327`（对照 `330-353`）；`src/main/java/net/minestom/server/network/packet/PacketWriting.java:88-116, 154-177`

**问题描述**

```java
// NetworkBufferImpl.java:313-322
ByteBuffer outputBuffer = bufferSlice(outputSegment, output.writeIndex(), output.writableBytes());
Deflater deflater = CompressionHolder.DEFLATER_POOL.get();
try {
    deflater.setInput(input);
    deflater.finish();
    final int bytes = deflater.deflate(outputBuffer);
    output.advanceWrite(bytes);      // ← 缺 if (!deflater.finished()) throw
    return bytes;
```

同一个文件的 `decompress` 就有对称保护（`NetworkBufferImpl.java:344-346`：`if (!inflater.finished()) throw new DataFormatException(...)`），压缩侧没有。`Deflater.deflate(ByteBuffer)` 在输出写满时只写下能写的部分并返回该长度、`finished()` 保持 false、**不抛异常**。于是 `writeCompressedFormat` 用截断后的 `writeIndex` 回填两个长度前缀（`PacketWriting.java:114-115`），产出一个**长度字段自洽、zlib 流不完整**的帧；因为没有 IOOBE，`writeQueue` / `allocateTrimmedPacket` 的"扩容重试"也不会被触发，包被当作写成功而 `poll()` 出队。

配套缺陷在 `PacketWriting.java:170-174`：

```java
// Add 15 bytes to account for the 3 potential varints in the packet header
tmpBuffer.resize(sizeOf + 15);
```

`writeCompressedFormat` 的 `contentStart` 已经占掉 6 字节，压缩输出可用空间只有 `sizeOf + 9`。deflate 对不可压缩数据的最坏膨胀约为 `n + 6 + 5·ceil(n/65535)`（zlib `compressBound`），100 KB 载荷就需要额外 ~16 字节、1 MB 需要 ~86 字节 —— 都超过 9。

**触发场景**

开启压缩（默认 `compressionThreshold = 256`，`MinecraftServer.java:89`）+ 载荷不可压缩（插件通道里转发已压缩/加密数据、随机贴图、大 map 数据）+ 单包超过 `POOLED_BUFFER_SIZE = 16383` 走到扩容分支。

**影响**

客户端收到损坏的 zlib 流 → 解压失败 → 断线（表现为经典的 "Network Protocol Error"，与 issue #2169 的症状一致）。服务端侧完全静默，无任何日志。

**建议修复**

```java
final int bytes = deflater.deflate(outputBuffer);
if (!deflater.finished()) throw new IndexOutOfBoundsException("Compressed payload exceeds output capacity");
output.advanceWrite(bytes);
```

抛 IOOBE 可直接复用 `PacketWriting` 已有的扩容重试路径。同时把 `sizeOf + 15` 改成留足 `compressBound` 余量，例如 `sizeOf + 15 + (sizeOf >> 12) + (sizeOf >> 14) + 13`。

**建议的复现测试**

```java
@Test
public void compressMustNotSilentlyTruncate() {
    var input = NetworkBuffer.staticBuffer(1 << 16);
    byte[] random = new byte[1 << 16];
    new java.util.Random(42).nextBytes(random);   // 不可压缩
    input.write(NetworkBuffer.RAW_BYTES, random);
    var output = NetworkBuffer.staticBuffer(random.length);  // 刚好等于输入大小
    assertThrows(RuntimeException.class, () -> input.compress(0, random.length, output),
            "deflate 输出放不下时必须报错，不能静默截断");
}
```

**判定**：截断行为【读代码确认】；线上触发频率取决于负载可压缩性，【疑似】
**关联**：疑似 issue **#2169**（"Kick with reason: Network Protocol Error"）的成因之一

---

### P1-4 单包超过 `MAX_PACKET_SIZE` 会把写线程卡进热循环（每轮分配 ~4 MB）

- **严重度**：P1（服务端自触发的 DoS + 无界队列增长）
- **位置**：`src/main/java/net/minestom/server/network/packet/PacketWriting.java:179-215`；`src/main/java/net/minestom/server/network/player/PlayerSocketConnection.java:438-471`；`src/main/java/net/minestom/server/network/socket/Server.java:139-170`

**问题描述**

```java
// PacketWriting.java:201-212
} else {
    buffer.writeIndex(index);
    if (written < minWrite) {
        final long newSize = Math.min(buffer.capacity() * 2, ServerFlag.MAX_PACKET_SIZE);
        if (newSize == buffer.capacity()) break;   // ← 到顶了，直接 break
        buffer.resize(newSize);
    } else { break; }
}
```

包留在队列头（`peek` 不 `poll`）。回到 `flushSync`：队列非空 → 不 sleep / 不 park → `writeChannel` 对 0 可读字节直接返回 true → 缓冲入池 → `playerWriteLoop` 立刻重来。每一轮都会从 16383 一路 `resize` 到 2097151（16 KB+32 KB+…+2 MB ≈ 4 MB 分配），**永远不会前进**。

注意 `writeCompressedFormat` 是"先把未压缩内容写进缓冲，再原地压缩"（`PacketWriting.java:93-108`），所以判据是**未压缩**序列化长度是否超过 2 MB —— 即使它能压到 100 KB 也没用。

同时 `packetQueue` 是 `mpscUnboundedArrayQueue`（`PlayerSocketConnection.java:93`），后续包无限堆积。

**触发场景**

任何单个出站包序列化后 > `ServerFlag.MAX_PACKET_SIZE = 2_097_151`：区块方块实体（大量告示牌/箱子 NBT）、超大 `DeclareCommandsPacket`、插件自行构造的巨型 `PluginMessagePacket`。

**影响**

该玩家的写线程 100% CPU + 每秒数百 MB 的分配压力（GC 死亡螺旋），连接永久静默但不断开。

**建议修复**

在 `writeQueue` 无法容纳时（`written == 0 && newSize == capacity`）把包 `poll()` 丢弃并抛/记录 `IllegalStateException("Packet too large")`，或直接 `disconnect()`，绝不能保持原地重试。

**建议的复现测试**

```java
@Test
public void oversizedPacketMustNotStallTheQueue() {
    var queue = ConcurrentMessageQueues.<ServerPacket>mpscUnboundedArrayQueue(16);
    queue.relaxedOffer(new PluginMessagePacket("t", new byte[ServerFlag.MAX_PACKET_SIZE + 1]));
    var buffer = NetworkBuffer.staticBuffer(ServerFlag.POOLED_BUFFER_SIZE, MinecraftServer.getRegistries());
    PacketWriting.writeQueue(buffer, queue, 1,
            (b, p) -> { PacketWriting.writeFramedPacket(b, ConnectionState.PLAY, p, 0); return true; });
    assertTrue(queue.isEmpty(), "无法写入的包必须被丢弃，否则写线程会永远重试");
}
```

**判定**：【读代码确认】（"客户端能否远程造出这么大的回包"标【疑似】）
**关联**：无

---

### P1-5 未被守卫的 `ClientConfigurationAckPacket` → 服务端在 PLAY 状态发 CONFIGURATION-only 包 → 写线程异常

- **严重度**：P1（远程可触发的连接终止 + 状态机错乱）
- **位置**：`src/main/java/net/minestom/server/listener/preplay/LoginListener.java:228-230`；`src/main/java/net/minestom/server/network/ConnectionManager.java:246-296`；`src/main/java/net/minestom/server/listener/manager/PacketListenerManager.java:155, 211-216`

**问题描述**

```java
// LoginListener.java:228-230
public static void configAckListener(ClientConfigurationAckPacket packet, Player player) {
    executeConfig(player, false);      // 没有任何前置条件
}
```

`ClientConfigurationAckPacket` 是普通的 PLAY 客户端包，客户端可以在**服务端从未发送 `StartConfigurationPacket`** 的情况下主动发送。`PacketListenerManager#processClientPacket:214-216` 会先把 `clientState` 推到 CONFIGURATION，然后调用监听器。`doConfiguration(player, false)` 随即在新虚拟线程上执行，但 `connection.getServerState()` **仍然是 PLAY**（`ConnectionManager#updateWaitingPlayers` 的 play→config 分支从未执行，`StartConfigurationPacket` 也从未被写出）。于是 `ConnectionManager.java:254` 的 `requestKnownPacks(...)` 要发 `SelectKnownPacksPacket`——它只存在于 `SERVER_CONFIGURATION` 注册表（`PacketVanilla.java` 中 CONFIGURATION/PLAY 是两张独立表）——`PacketRegistryImpl#packetInfo(Class)` 抛：

```java
// PacketRegistryImpl.java:63-66
if (packetInfo == null)
    throw new IllegalStateException("Packet type " + packetClass + " cannot be sent in state " + side.name() + "_" + state.name() + "!");
```

这个异常发生在**写线程**（`flushSync` → `writeQueue` → `writePacketSync`），`writeQueue` 只 catch `IndexOutOfBoundsException`，于是冒到 `Server#playerWriteLoop:147` → `handleException` + `disconnect()`。

同时玩家仍在 `playPlayers` 里（`transitionPlayToConfig` 从未被调用），却已经在跑配置流程；重复发送则命中 `Check.stateCondition(knownPacksFuture != null, "Known packs already pending")`（`PlayerConnection.java:274`），走 `executeConfig` 的 catch → `player.kick`。

**触发场景**

进入 PLAY 后随时发一个 `ClientConfigurationAckPacket`。

**影响**

自我踢出 + 一条完整堆栈日志 + 每次都新起一个虚拟线程跑 `doConfiguration`（含 `AsyncPlayerConfigurationEvent` 广播，插件逻辑会被意外重入执行）。对插件而言 `AsyncPlayerConfigurationEvent` 可以被客户端任意时刻触发是一个真实的语义漏洞。

**建议修复**

`configAckListener` 首行加：`if (player.getPlayerConnection().getServerState() != ConnectionState.CONFIGURATION) { player.getPlayerConnection().disconnect(); return; }`。这与 vanilla 的行为一致（vanilla 只在服务端主动发起 `StartConfiguration` 后才接受 Ack）。

**建议的复现测试**

```java
@EnvTest
public class UnsolicitedConfigAckTest {
    @Test
    public void unsolicitedConfigurationAckMustBeRejected(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 40, 0));
        var conn = player.getPlayerConnection();
        assertEquals(ConnectionState.PLAY, conn.getServerState());
        MinecraftServer.getPacketListenerManager()
                .processClientPacket(new ClientConfigurationAckPacket(), conn);
        env.tick();
        // 期望：连接被干净断开，且服务端状态没有被客户端单方面改写
        assertFalse(conn.isOnline());
    }
}
```

**判定**：【读代码确认】
**关联**：无

---

### P1-6 `NetworkBufferTypeImpl.LazyType#read` 恒返回 null 且不消耗任何字节

- **严重度**：P1（彻底的读写不对称，public API）
- **位置**：`src/main/java/net/minestom/server/network/NetworkBufferTypeImpl.java:933-952`（工厂在 `NetworkBuffer.java:187-189`）

```java
@Override
public void write(NetworkBuffer buffer, T value) {
    if (type == null) type = supplier.get();
    type.write(buffer, value);          // 正常委托
}

@Override
public T read(NetworkBuffer buffer) {
    if (type == null) type = supplier.get();
    return null;                        // ← 应为 return type.read(buffer);
}
```

**影响**：不仅返回值错，`readIndex` 一个字节都不推进 → 同一个包里 `Lazy` 之后的所有字段全部错位解析。`sizeOf` 走 `write` 路径所以是对的，掩盖不了问题。

**触发场景**：`NetworkBuffer.Lazy(...)` 构造的类型被读取。`src/` 内 grep 只命中定义、无调用点，故**对内当前不可触发**；但它是 public API，任何扩展/插件一用就必错。

**建议修复**：`return type.read(buffer);`

**建议的复现测试**

```java
@Test
public void lazyTypeRoundTrips() {
    var lazy = NetworkBuffer.Lazy(() -> NetworkBuffer.INT);
    var buffer = NetworkBuffer.resizableBuffer(64);
    buffer.write(lazy, 1234);
    assertEquals(1234, buffer.read(lazy));      // 现状：返回 null 并抛 NPE 拆箱
    assertEquals(0, buffer.readableBytes());
}
```

**判定**：【读代码确认】

---

### P1-7 `MaxLength#write` 丢掉 `buffer.registries()`

- **严重度**：P1（依赖 registries 的类型套上 `.maxLength()` 后写入必失败）
- **位置**：`src/main/java/net/minestom/server/network/NetworkBufferTypeImpl.java:915-921`

```java
public void write(NetworkBuffer buffer, T value) {
    final long length = parent.sizeOf(value);     // ← 应为 parent.sizeOf(value, buffer.registries())
    Check.argCondition(length > maxLength, ...);
    buffer.write(parent, value);
}
```

`Type#sizeOf(T)`（`NetworkBuffer.java:320-322`）转发到 `sizeOf(value, null)`，构造出的 dummy buffer 没有 registries。parent 一旦是 `TypedNbtType`（`NetworkBufferTypeImpl.java:973-983` 有 `Check.stateCondition(registries == null, "Buffer does not have registries")`）、Component 或 `ItemStack.NETWORK_TYPE`，`write` 就抛 `IllegalStateException`——即使外层 buffer 明明带着 registries。同文件的 `LengthPrefixedType#write:897` 就是正确写法（`NetworkBuffer.makeArray(..., buffer.registries())`）。

**触发场景**：`SomeRegistryDependentType.maxLength(N)`。`src/main` 内无调用点，属 public API 陷阱。
**建议修复**：改为 `parent.sizeOf(value, buffer.registries())`。
**建议的复现测试**：`assertDoesNotThrow(() -> registryAwareBuffer.write(ItemStack.NETWORK_TYPE.maxLength(1 << 20), someItem));`
**判定**：【读代码确认】

---

### P1-8 `EntityTrackerImpl` 双重内存泄漏（`viewers` 永不移除 + `chunkEntities` 读路径 `computeIfAbsent`）

- **严重度**：P1（长期运行内存泄漏）
- **位置**：`src/main/java/net/minestom/server/instance/EntityTrackerImpl.java:216-219, 262-271, 285-296`；`src/main/java/net/minestom/server/instance/Chunk.java:73-75`；`src/main/java/net/minestom/server/instance/InstanceContainer.java:279`

**泄漏 A（更严重）**：每次 `new Chunk(...)` 都会调 `viewable(...)`：

```java
// Chunk.java:73-75
final List<SharedInstance> shared = instance instanceof InstanceContainer ic ? ic.getSharedInstances() : List.of();
this.viewable = instance.getEntityTracker().viewable(shared, chunkX, chunkZ);
```

```java
// EntityTrackerImpl.java:216-219
return entry.viewers.computeIfAbsent(new ChunkViewKey(sharedInstances, chunkX, chunkZ), ChunkView::new);
```

`ChunkViewKey#equals`（`EntityTrackerImpl.java:263-270`）用的是 **`sharedInstances == instances` 引用相等**，而 `InstanceContainer#getSharedInstances`（`InstanceContainer.java:585-586`）每次调用都返回一个**新的** `Collections.unmodifiableList(...)` 包装对象。于是**对同一个 InstanceContainer，任意两次 `new Chunk` 的 key 永远不相等**——每加载一个区块就在 `viewers` 里留下一个永不回收的条目，区块卸载（`InstanceContainer.java:275-285`）也不会移除。同一区块反复加载/卸载 1000 次 = 1000 个残留 `ChunkView`。

**泄漏 B**：`chunkEntities(long)` 是读路径却用了 `computeIfAbsent`：

```java
// EntityTrackerImpl.java:285-296
List<T> chunkEntities(long index) {
    return chunkEntities.computeIfAbsent(index, _ -> new CopyOnWriteArrayList<>());
}
...
void removeFromChunk(long index, T entity) {
    List<T> entities = chunkEntities.get(index);
    if (entities != null) entities.remove(entity);      // 空列表不被移除
}
```

公开 API `chunkEntities(chunkX, chunkZ, target)`（`EntityTrackerImpl.java:148-154`）每被查询一个新坐标就永久建一个空 `CopyOnWriteArrayList`，×3 个 Target。

**影响**：随"历史加载过的区块数"单调增长，而非"当前加载的区块数"。探索型/随机传送型服务器上每个泄漏条目约 150–200 B，百万次区块加载 ≈ 150–200 MB，且永不释放。

**上游状态**：`upstream/fix/viewable-leak`（单提交 `b73ca0ccac`，2026-01-19）**正是修这两处**：`chunkEntities(long)` 改成 `get()` 返回可空、`removeFromChunk` 在空时 `chunkEntities.remove(index)`、新增 `EntityTracker#unregisterViewable(Viewable)` 并在 `InstanceContainer#unloadChunk` 调用。该分支基于 `fb5342901c`（2026-01 的 master），**距今已落后 7 个多月**，`git diff upstream/master upstream/fix/viewable-leak` 是 5.5 MB 的噪音，无法直接 rebase。我逐行核对了 master 上的对应代码：**两处泄漏均仍然存在**。

**建议修复**：直接采纳该分支的三处改动；额外建议把 `ChunkViewKey#equals` 的 `sharedInstances == instances` 改成 `Objects.equals(...)`（引用相等 + record 默认的内容哈希虽然没违反 hashCode 契约，但让 key 事实上不可复用）。

**建议的复现测试**

```java
@EnvTest
public class EntityTrackerLeakTest {
    @Test
    public void reloadingChunksMustNotGrowViewerMap(Env env) {
        var instance = env.createFlatInstance();
        for (int i = 0; i < 200; i++) {
            instance.loadChunk(0, 0).join();
            instance.unloadChunk(0, 0);
            env.tick();
        }
        // 期望：viewers 中最多 1 个 (0,0) 条目；现状：200 个
        // （需要通过反射或新增的包级可见 accessor 读取 targetEntries[PLAYERS].viewers）
    }
}
```

**判定**：【读代码确认】
**关联**：issue **#2649**（"EntityTracker leaking memory"）；分支 `upstream/fix/viewable-leak`

---

### P2 级别（边界与健壮性）

| # | 标题 | 位置 | 说明 | 判定 |
|---|---|---|---|---|
| P2-1 | `writePacketSync` 先改 `serverState` 再写包，写失败重试时用错状态 | `PlayerSocketConnection.java:379-386, 407-410` + `PacketWriting.java:187-207` | `setServerState(nextState)` 在 `writeFramedPacket` **之前**。若写抛 IOOBE，包被回退重试（`writeQueue` 的 resize 分支会用同一个包再调一次 writer），但状态已推进 → `FinishConfigurationPacket` 会被拿去 `SERVER_PLAY` 注册表查找 → `IllegalStateException` → 断线。触发概率约 `7/16383` 每次状态切换（缓冲剩余不足 7 字节时）。修复：把 `setServerState` 挪到写成功之后 | 【读代码确认】 |
| P2-2 | `unlockWriteThread` 的漏唤醒竞态 | `PlayerSocketConnection.java:232-237, 438-456` | `writeSignaled` 只在写线程 park 前 `set(false)`（第 451 行），而 `packetQueue.isEmpty()` 检查在第 440 行。若在这两行之间有第三方线程 offer 且 `writeSignaled` 仍为 true（上一轮唤醒遗留），`compareAndExchange` 返回 true → **不 unpark** → 写线程带着队列里的包 park。自愈依赖下一个包（keep-alive 最长 10 s）。仅在 `minestom.new-socket-write-lock=true` 时生效（默认关） | 【读代码确认】 |
| P2-3 | `compressionStart` 用"已写出包数"作为分界，但入队顺序才是真实顺序 | `PlayerSocketConnection.java:96-99, 211-217, 460-465` | `startCompression()` 取 `sentPacketCounter.get()`（已**写出**数量）。若调用时队列里还有 ≥1 个未写出的包，则 `SetCompressionPacket` 自身会被判为 `compressed = true` 而压缩发出 → 客户端此时还没启用压缩 → 协议错位。正常登录流程下队列为空，但插件/调度器并发 `sendPacket` 可打破该前提 | 【疑似】 |
| P2-4 | 集合/映射反序列化：负数 size 绕过上限 → `NegativeArraySizeException`；`Set.of`/`Map.copyOf` 对重复元素抛 IAE | `NetworkBufferTypeImpl.java:1050-1053, 1076-1078, 1098-1100` | `Check.argCondition(size > maxSize, ...)` 对负数恒 false，随后 `new Object[size]`。客户端可达：`ClientClickWindowPacket.changedSlots`（`.mapValue(..., MAX_CHANGED_SLOTS)`）、`ClientSetGameRulesPacket.entries`、`ClientDebugSubscriptionRequestPacket.subscriptions`。**正向超大值已被 maxSize 正确拦截**，缺口只在负数 + 重复元素 | 【读代码确认】 |
| P2-5 | `NetworkBuffer.Enum(...)` 无边界检查 | `NetworkBuffer.java:100-103` | `VAR_INT.transform(integer -> values[integer], ...)`，负数与超界 ordinal 直接 AIOOBE。影响面很广：`ClientSettingsPacket.chatMessageType/particleSetting`、`ClientClickWindowPacket.clickType`、`ClientEntityActionPacket.action`、`DIRECTION`、`POSE` 等。同理 `ClientPlayerActionPacket.java:20` 的 `BlockFace.values()[aByte]`（byte 取值 -128..127，枚举只有 6 个常量） | 【读代码确认】 |
| P2-6 | `StringTerminatedType#read` 返回底层容量数组 | `NetworkBufferTypeImpl.java:529-537` | `new String(bytes.elements(), UTF_8)` —— fastutil `elements()` 返回**容量**数组而非有效前缀，`"abc"` 读回来会带一串 `\0`。应为 `bytes.toByteArray()`。同时该 read 没有任何长度上限。`src/` 内无调用点 | 【读代码确认】 |
| P2-7 | 定长类型的 `read()` 全部不调用 `ensureReadable`，边界只由 segment capacity 兜底 | `NetworkBufferTypeImpl.java:82-232, 253-267, 324-340` | 写侧有 `ensureWritable(N)`，读侧完全没有对称保护。在包体解析路径上因为 `PacketReading.java:178` 切成 `capacity == packetLength` 的 slice 而侥幸安全；但任何直接持大缓冲读的调用方会**静默读到 `writeIndex` 之后的陈旧字节**并把 `readIndex` 推过 `writeIndex`（`readableBytes()` 变负）。`PacketReading.java:156-161` 的 `readerStart > writeIndex` 后置检查正是为此打的补丁 | 【读代码确认】 |
| P2-8 | `VarLongType#read` 抛 `RuntimeException` 而非 `IndexOutOfBoundsException` | `NetworkBufferTypeImpl.java:336` | 长度上限本身正确（≤10 字节，符合协议），但 `NetworkBuffer#read` 签名声明 `throws IndexOutOfBoundsException`（`NetworkBuffer.java:216`），`PacketReading`/`PacketWriting` 的恢复路径全部按 IOOBE 过滤，`RuntimeException` 会穿透所有重试逻辑。测试里已有 `//todo: convert from runtime to index out of bounds`（`NetworkBufferRegistriesTest.java:669`） | 【读代码确认】 |
| P2-9 | 已注册但无监听器的 PLAY 包 → 每包一行 WARN，可远程放大 | `PacketListenerManager.java:223-226` + `PacketVanilla.java:320, 324, 347, 370` | `ClientChatAckPacket`、`ClientQueryBlockNbtPacket`、`ClientQueryEntityNbtPacket`、`ClientLockDifficultyPacket`、`ClientChangeDifficultyPacket`、`ClientSetBeaconEffectPacket`、`ClientSelectTradePacket`、`ClientWindowSlotStatePacket` 等已在 `CLIENT_PLAY` 注册但无监听器。对比：开发者对"有意忽略"的包会挂空 lambda（`PacketListenerManager.java:179, 189`）。恶意客户端以 `PLAYER_PACKET_PER_TICK = 50`/tick 的速率狂发 → 1000 行 WARN/秒/玩家，无限流。原版客户端也会周期性发 Chat Ack | 【读代码确认】 |
| P2-10 | 状态请求无"每连接一次"守卫 → 带宽/CPU 放大 | `StatusListener.java:17-23` | vanilla 的 `ServerStatusPacketListenerImpl` 有 `hasRequestedStatus` 标志，重复请求直接断开。Minestom 没有：客户端可在同一 TCP 连接上反复发 2 字节的 `StatusRequestPacket`，每次都触发 `Status.builder().build()` + `ServerListPingEvent` 广播 + 完整 JSON 序列化（含 base64 favicon，常见 8–30 KB）。放大比可达数千倍 | 【读代码确认】 |
| P2-11 | 未加载区块 / null instance 上的 `getBlock` → NPE | `EditSignListener.java:12-16`、`PlayerPickListener.java:15-21`、`UseEntityListener.java:19, 26`、`PlayerSpectatorListener.java:26, 44` | `Instance#getBlock` 对未加载区块 `throw new NullPointerException("Unloaded chunk at ...")`（`Instance.java:746-749`）。`ClientUpdateSignPacket.blockPosition` / `ClientPickItemFromBlockPacket.pos` 完全由客户端控制，可指向任意远坐标。对比 `PlayerActionListener.java:39, 42, 45` 每个分支都先 `instance.isChunkLoaded(...)`。异常被 `processClientPacket` 的 catch 吞掉，不崩服，但制造噪音与开销 | 【读代码确认】 |
| P2-12 | 移动包只校验 x/y/z 的有限性，yaw/pitch 的 NaN/Inf 无校验 | `PlayerPositionListener.java:53-62`（配合 `:37-39`） | `processMovement` 的 `Double.isFinite` 只覆盖 x/y/z；`ClientPlayerRotationPacket.yaw/pitch` 是裸 `FLOAT`，NaN/±Inf 会经 `withView(...)` 写入实体并广播给观察者，污染后续角度/朝向计算 | 【读代码确认】 |
| P2-13 | `PacketVanilla.PACKET_POOL` 只 `clear()` 不缩容 | `PacketVanilla.java:257-259` + `ObjectPool.java:55-58` + `PacketWriting.java:170-176` | 任何 >16383 字节的包都会把池化缓冲 `resize` 到最大 2 MB，随后原样归还（sanitizer 是 `NetworkBuffer::clear`）。池容量 32768 条，靠 `SoftReference` 兜底。副作用之一见下：`allocateTrimmedPacket` 的 `catch (IndexOutOfBoundsException)` 在缓冲恰好装得下超规格包时不会触发，导致 `writeAt(..., VAR_INT_3, ...)` 抛 `IllegalArgumentException: VarInt3 out of bounds`（`NetworkBufferTypeImpl.java:288`）穿透所有重试逻辑，设计好的 `"Packet too large"` 诊断被绕过 | 【读代码确认】 |

---

### P3 级别（代码异味 / API）

- **P3-1**（关联 PR **#3346**）`PlayerConnection#sendPackets(Collection<SendablePacket>)` 的泛型边界不变（`PlayerConnection.java:86`、`PlayerSocketConnection.java:226`、`Player.java:1662`）。这是编译期的 API 人体工学问题，不是运行时 bug；`ConnectionManager.java:283` 内部调用能编译说明内部返回的就是宽类型。建议按 PR 改成 `Collection<? extends SendablePacket>`。
- **P3-2**（关联 PR **#3342**）`Status.Builder`（`ping/Status.java:193-197`）无条件调 `PlayerInfo.onlineCount()` → `MinecraftServer.getConnectionManager()`，在 `MinecraftServer.process()` 为 null（服务端未初始化）时 NPE。只影响单元测试/初始化前构造，运行中的服务器不受影响。PR 里的 `== null ? null : ...` 是权宜之计；更干净的做法是让 `PlayerInfo` 延迟求值。
- **P3-3** `ConnectionManager#shutdown`（`ConnectionManager.java:323-333`）第一个循环已经踢掉了 `connectionPlayerMap` 里的**全部**连接（`disconnect()` 会反过来 `removePlayer`），第二个 `for (playPlayers)` 循环因此是死代码。与 issue #2907/#1636 的"服务器停不下来"可能相关，但我没有验证。
- **P3-4** `PlayerConnection#kick`（`PlayerConnection.java:139-149`）按 `serverState == LOGIN` 二选一发 `LoginDisconnectPacket`/`DisconnectPacket`；在 HANDSHAKE / STATUS 状态调用会因为 `DisconnectPacket` 不在对应注册表里而在写线程抛 `IllegalStateException`。同时 `disconnect()` 不关闭 channel、不打断读线程，读循环会继续处理客户端包直到写线程关闭 socket（最长 ~25 ms 或一次 park 唤醒）。

---

## ③ 上游 open bug issue 复核表（本范围内）

| Issue | 判定 | 依据 |
|---|---|---|
| **#3320** Packet id 0x# isn't recognized / MemorySegment IOOBE | **仍存在**（协议层解析缺陷，非用户误用） | 第三段堆栈的 `byteSize: 0 / new offset = 0 / new length = 1` 与 `PacketReading.java:178` 在 `packetLength == 0` 时产生的零长切片逐字符吻合（见 P1-1）。前两段 `Packet id ... isn't registered!` 是 `PacketRegistryImpl.java:73-75` 把协议错误表达成 `IllegalStateException`。两者都是"扫描器/畸形包 → 内部异常堆栈"，而不是用户误用。PR #3347 缓解噪音但未修根因，且完全不覆盖 P1-2 的 `resize(5)` 路径 |
| **#2649** EntityTracker leaking memory | **仍存在** | `EntityTrackerImpl.java:218` 的 `viewers.computeIfAbsent` 无对应移除；`:285-296` 读路径 `computeIfAbsent` + `removeFromChunk` 不清空列表。`upstream/fix/viewable-leak` 的修复未合入 master（见 P1-8） |
| **#2169** Kick with reason: Network Protocol Error | **无法判断（有一条可信线索）** | P1-3 的压缩静默截断会精确产生这种症状；但 issue 本身没有可复现步骤，也可能是别的原因 |
| **#2241** Bug: Handshake packet reading? | **已修复/非 bug** | 当前 `ClientHandshakePacket.java:14-24` 用 `NetworkBufferTemplate` 严格按 VarInt/String/UnsignedShort/VarInt 顺序解析，并在紧凑构造器里限制地址长度；`HandshakeListener.java:44-54` 按 auth 模式区分 255 / 2500 / `Short.MAX_VALUE` 上限。原 issue 描述的读取问题在当前代码中不成立 |
| **#3050** Client times out in this scenario | **无法判断** | 缺少复现细节。P2-2（写线程漏唤醒）与 P1-4（写线程卡死）都能产生"客户端超时"，但无法确认对应关系 |
| **#2907** / **#1636** Server cannot be stopped | **无法判断（本范围内只看到线索）** | `Server#stop()`（`socket/Server.java:176-190`）只置 `stop` 标志并关 ServerSocket，**不主动关闭已建立的 client channel**；`playerReadLoop` 阻塞在 `channel.read` 上不会看到 `stop`。`ConnectionManager#shutdown` 的第二个循环是死代码（P3-3）。是否构成"停不下来"需要运行验证 |
| **#3057** Network Protocol Error when setting entity meta after switchEntityType / **#2352** EntityCreature non-living → Protocol Error | **非本范围** | 属实体元数据序列化，不在 network 包 |
| 其余（#3231 PlayerSkin、#3224/#2813 寻路、#3158 lighting、#3141 EntityMeta codec、#3118 ArgumentItemStack、#3083 chunk unload、#3054 GroundNodeGenerator、#3049 collision、#3027/#2847/#2830/#2546/#2514/#1932 inventory、#3021/#2527/#2526/#2486 chunk/batch、#3004 命名、#2900 instance、#2831 tracker NPE、#2797/#2081/#1750 viewer、#2766 batch callback、#2702/#2693/#1107 physics、#2682/#1617/#1520/#1326/#466 命令、#2668 palette、#2639 tags、#2638 InstanceContainer 竞态、#2598 block properties、#2586 particles、#2547/#2540/#2507 setBlock、#2353 BlockHandler、#2334 teleport ACK、#2314 tablist、#2252/#2131 collision、#2152 switchEntityType、#2141 Team、#2061 damage、#1918 EntityFinder、#1622 ItemEntity、#1618 projectile、#1526 CME、#1212 CME、#1071 item frame、#950 events、#246 components、#221 javadoc） | **非本范围** | 均不属于网络与协议层 |

---

## ④ 未覆盖的区域与原因

1. **`network/debug/*`**：仅粗读，未逐行审计。这是 1.21.6+ 的 debug subscription 通道，攻击面小且默认不启用。
2. **`network/packet/server/**` 的 ~180 个包定义**：只抽查了参与状态切换、被 `CachedPacket` 缓存、以及体积可能超限的那几个。逐个核对 180 个 record 的字段顺序需要与协议文档做交叉比对，成本远超本轮预算。
3. **`ComponentNetworkBufferTypeImpl` 的手写 NBT 编码器**（1000+ 行）：委派子代理做了分支级核对，未发现字节层面不对称；但没有做穷尽的 round-trip 差分测试，**结论强度为"未发现问题"而非"确认无问题"**。
4. **`NetworkBufferTemplate` 的 2142 行模板重载**：只核对了 `NetworkBufferTemplateImpl` 的字节码生成逻辑（栈序、`ClassDesc`、构造器 arity、字段读取顺序）与非编译降级路径，未逐个核对 20+ 个 arity 重载。
5. **加密路径的实测**：`MojangCrypt` / AES-CFB8 的正确性没有跑过真实客户端，只核对了 `cipher(cipher, start, length)` 的调用顺序与写入顺序一致（`PlayerSocketConnection.java:343-354`）。
6. **并发正确性未做压力验证**：P2-2（漏唤醒）、P2-3（压缩分界）、`CachedPacket` 的 acquire/release 语义都是纯推理结论，需要 jcstress 或高并发实测才能定性。
7. **所有"建议的复现测试"均未运行**（按约束禁止 Gradle）。它们是按 `src/test/java/net/minestom/server/network/SocketReadIntegrationTest.java` 与 `testing/src/main/java/net/minestom/testing/` 的既有风格写的，但**没有编译验证过**，可能需要调整导入与 API 细节。
8. **`upstream/fix/viewable-leak` 未做 rebase 验证**：该分支基于 `fb5342901c`（7 个多月前），与 master 的 diff 有 5.5 MB 噪音。我的判断来自逐行核对 master 上的对应代码，而非把补丁打到 master 上。

---

## ⑤ 参考链接

- Minecraft Java Edition 协议（wiki.vg 已并入 minecraft.wiki）：https://minecraft.wiki/w/Java_Edition_protocol
- 包格式（含压缩格式）：https://minecraft.wiki/w/Minecraft_Wiki:Projects/wiki.vg_merge/Protocol#Packet_format
- HAProxy PROXY protocol 规范：https://www.haproxy.org/download/1.8/doc/proxy-protocol.txt
- issue #3320：https://github.com/Minestom/Minestom/issues/3320
- issue #2649：https://github.com/Minestom/Minestom/issues/2649
- issue #2169：https://github.com/Minestom/Minestom/issues/2169
- PR #3347（network error handling behind server flags）：https://github.com/Minestom/Minestom/pull/3347
- PR #3346（sendPackets 泛型协变）：https://github.com/Minestom/Minestom/pull/3346
- PR #3342（Scratch status fix）：https://github.com/Minestom/Minestom/pull/3342
- 分支 `upstream/fix/viewable-leak`，提交 `b73ca0ccac`（"fix: Memory leak in EntityTracker"）
- 相关历史提交：`d4d3698fa0`（#3198 Prevent negative packet length）、`c4dc9e89e8`（#3168 Potential security issues）、`689e6bdd21`（#3277 Add NetworkBuffer#slice）
