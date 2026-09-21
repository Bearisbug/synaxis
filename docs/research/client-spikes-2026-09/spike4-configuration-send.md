# spike ④ 的源码结论：Fabric 客户端在 configuration 阶段发包，是否依赖对端的 `minecraft:register`

**这是桌面结论，不是运行期验证。** 下面每一条都能在本机 jar 上复核，但**没有在真实 Minecraft 客户端上跑过**。
`ADR-M2-010` 因此**保持 Proposed**，直到验收人按本目录 `README.md` 的 S1 跑出结果。

## 取证对象

| 项 | 值 |
| --- | --- |
| 坐标 | `net.fabricmc.fabric-api:fabric-networking-api-v1:6.3.4+2989c6a09e` |
| 来自 | `net.fabricmc.fabric-api:fabric-api:0.161.0+26.2` 的 `<dependency>`（即 `client-fabric/build.gradle.kts` 里钉的那一版） |
| 字节码 jar | `~/.gradle/caches/modules-2/files-2.1/net.fabricmc.fabric-api/fabric-networking-api-v1/6.3.4+2989c6a09e/608860809ec66fd65ca764c4157e96030f330537/fabric-networking-api-v1-6.3.4+2989c6a09e.jar`，sha256 `10b88a11d0c534810bee2ce10b3e70482200f20ba92f1fa5457ce81b6649b923` |
| 源码 jar | `https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-networking-api-v1/6.3.4+2989c6a09e/fabric-networking-api-v1-6.3.4+2989c6a09e-sources.jar`，sha256 `7015b67a0bb211393aaa3f1b8cfe812c7f7a198236789a8b4114f3bfe46e3ed5` |

下文的行号是该 sources jar 内的行号；每条都用 `javap -c` 在上面那个字节码 jar 上对过，源码与字节码一致。
**GitHub 上没有对应提交**：版本尾缀 `+2989c6a09e` 是 FabricMC/fabric 的提交短 hash，但 `api.github.com` 解析不到它
（该分支未公开），所以出处只能给 Maven 坐标 + 摘要，不能给 GitHub URL。

复核命令：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home

# 字节码：Loom 早就把它拉进 ~/.gradle 了，不用重下
J=$(find ~/.gradle/caches/modules-2/files-2.1/net.fabricmc.fabric-api/fabric-networking-api-v1 \
      -name 'fabric-networking-api-v1-6.3.4+2989c6a09e.jar' | head -1)
$JAVA_HOME/bin/javap -c -p -cp "$J" net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking \
  | awk '/public static void send/,/athrow/'

# 源码：Gradle 不会自动取 sources，要自己下一次（走代理）
curl --proxy http://127.0.0.1:7897 -o /tmp/fabric-net-sources.jar \
  https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-networking-api-v1/6.3.4+2989c6a09e/fabric-networking-api-v1-6.3.4+2989c6a09e-sources.jar
shasum -a 256 /tmp/fabric-net-sources.jar   # 期望 7015b67a…e3ed5
unzip -o -q /tmp/fabric-net-sources.jar -d /tmp/fabricsrc
```

## 问题一：发送路径上有没有基于对端 `minecraft:register` 声明的检查

**没有。** 对端声明的通道集合叫 `sendableChannels`，它只被 `getSendable()` / `canSend()` 读，
**`send()` 这条路上一次都不读它**。

`ClientConfigurationNetworking.java:232`：

```java
public static void send(CustomPacketPayload payload) {
    Objects.requireNonNull(payload, "Payload cannot be null");
    Objects.requireNonNull(payload.type(), "CustomPacketPayload#type() cannot return null for payload class: " + payload.getClass());

    final ClientConfigurationNetworkAddon addon = ClientNetworkingImpl.getClientConfigurationAddon();

    if (addon != null) {
        addon.sendPacket(payload);
        return;
    }

    throw new IllegalStateException("Cannot send packet while not configuring!");
}
```

`addon.sendPacket(payload)` 走 `PacketSender.java:53` 的默认实现 `sendPacket(createPacket(payload))`，
再落到 `AbstractChanneledNetworkAddon.java:163`：

```java
@Override
public void sendPacket(Packet<?> packet, ChannelFutureListener callback) {
    Objects.requireNonNull(packet, "Packet cannot be null");

    this.connection.send(packet, callback);
}
```

整条链只有两个 `requireNonNull` 和一次 `connection.send`。`sendableChannels` 这个字段在
`AbstractChanneledNetworkAddon` 里只由 `registerChannel` / `unregister` 写、由 `getSendableChannels()` 读，
而 `getSendableChannels()` 在整个 jar 里的调用点是：四个门面（client / server × configuration / play）各自的
`getSendable()` 与 `canSend()` 这一对，外加 `ServerConfigurationPacketListenerImplMixin`（服务端拿它填 channel info）。
**客户端的发送路径一处也不读它。**

## 问题二：`canSend` 与 `send` 是什么关系

**没有关系。`canSend` 是一句对「对端说过自己能收什么」的查询，不是 `send` 的前置条件。**

- `canSend(Identifier)`（`ClientConfigurationNetworking.java:187`）返回 `addon.getSendableChannels().contains(channelName)`，
  而那个集合只有在对端发来 `minecraft:register`（或 `minecraft:common/register`）时才会被填。
- `send()` 不查这个集合，所以对一个从不发 `minecraft:register` 的服务端：**`canSend()` 返回 `false`，
  而 `send()` 照样把包写进 socket，不抛也不静默丢弃。**

唯一能让 `send()` 抛的是 `addon == null`（不在 configuration 阶段），文案 `Cannot send packet while not configuring!`。

另有一条与 `minecraft:register` 无关、但会让发送失败的前提：负载类型必须注册进
`PayloadTypeRegistry.serverboundConfiguration()`，否则编码器找不到 codec —— 这是**我们自己模组的注册**，
与服务端声明什么无关。

## 问题三：configuration 阶段与 play 阶段的实现是否一致

**一致，而且 play 阶段更直白。** `ClientPlayNetworking.java:240`：

```java
public static void send(CustomPacketPayload payload) {
    Objects.requireNonNull(payload, "Payload cannot be null");
    Objects.requireNonNull(payload.type(), "CustomPacketPayload#type() cannot return null for payload class: " + payload.getClass());

    // You cant send without a client player, so this is fine
    if (Minecraft.getInstance().getConnection() != null) {
        Minecraft.getInstance().getConnection().send(createServerboundPacket(payload));
        return;
    }

    throw new IllegalStateException("Cannot send packets when not in game!");
}
```

play 阶段连 addon 都不经过，直接 `connection.send(...)`；两个阶段的 `canSend` 语义也相同
（查 `sendableChannels`，与 `send` 无关）。两处的 `createPacket` 最终都是
`ClientNetworkingImpl.createServerboundPacket`，返回 `new ServerboundCustomPayloadPacket(payload)`。

这与仓库现状相符：`client-fabric` 的骨架在 play 阶段走 `rts:game`，而本引擎从不发 `minecraft:register`，
那条通道至今收发正常。

## 真正的坑不在 `send`，在「什么时候能发」

`ClientConfigurationConnectionEvents` 有四个事件，Javadoc 里只有 **`START`** 写着「Packets may be sent
during this event」；`INIT` / `COMPLETE` / `DISCONNECT` 都写着「No packets should be sent」。
而 `READY` 已经 `@Deprecated`，实现上它与 `COMPLETE` 在同一句里一起触发
（`ClientConfigurationNetworkAddon.handleComplete()`），也就是**收到 `ClientboundFinishConfigurationPacket`
之后**——那时服务端的 3 s 有界等待早就超时了。**握手必须挂 `START`，不能挂 `READY`。**

`START` 的触发条件是这一段（`ClientConfigurationNetworkAddon.java:64`）：

```java
@Override
protected void receiveRegistration(boolean register, RegistrationPayload payload) {
    super.receiveRegistration(register, payload);

    if (register && !this.sentInitialRegisterPacket) {
        this.sendInitialChannelRegistrationPacket();
        this.sentInitialRegisterPacket = true;

        this.onServerReady();          // → invokeStartEvent()
    }
}

@Override
public boolean handle(CustomPacketPayload payload) {
    boolean result = super.handle(payload);

    if (payload instanceof BrandPayload) {
        // If we have received this without first receiving the registration packet, its likely a vanilla server.
        invokeStartEvent();
    }

    return result;
}
```

两条路：对端发 `minecraft:register`，**或者**对端发 `minecraft:brand`。本引擎走第二条，而且顺序正好：
`ConnectionManager#doConfiguration` 的**第一句**就是

```java
player.sendPacket(PluginMessagePacket.brandPacket(process.brandName()));
```

（`src/main/java/io/github/bearisbug/synaxis/network/ConnectionManager.java:288`，`brandPacket` 的通道是
`minecraft:brand`），**之后**才 `call(AsyncPlayerConfigurationEvent)`，而 `HandshakeGate.await` 的 3 s
等待就挂在那个事件上。所以客户端拿到 brand → `START` → 发 `C2S_HELLO`，全程在服务端开始计时之后、
预算之内。`ClientConfigurationNetworkAddon.isOnReceiveThread()` 对 configuration 返回 `true`
（「Configuration packets are handled on the network thread」），`START` 直接在 netty 线程上跑，不排队等主线程。

## 结论

**源码显示：能发。** Fabric 的 configuration 发送路径不看对端的 `minecraft:register`，`canSend()` 只是一句
与 `send()` 无关的查询；本引擎发的 `minecraft:brand` 又正好能把 `ClientConfigurationConnectionEvents.START`
点着，且它早于服务端的 3 s 等待。**但这一条没有在真实客户端上跑过**，它是读 jar 得出的，
`ADR-M2-010` 保持 Proposed。

跑出来若与此不符，退路仍是设计文档写死的那两条，不要临场发明第三条：

1. **服务端在 configuration 阶段补发一次 `minecraft:register`**（成本最小，但要改引擎）；
2. **切到 login 阶段**：`-Dsynaxis.app.handshake-phase=login`，见下一节。

## 退路二的现状复核（本轮已核）

`ADR-M2-010` 的「落地形态与退路」说 login 阶段是改一个属性的事。核过了，确实是：

- 开关：`HandshakeGate.PHASE_PROPERTY = "synaxis.app.handshake-phase"`，取值 `configuration`（默认）/ `login`，
  非这两个值即按非法启动参数拒启（判断在同文件 `phase()` 的
  `server-app/src/main/java/io/github/bearisbug/synaxis/app/HandshakeGate.java:163-171`，属性名声明在 `:80`）。
- login 形态的实现是同一个类里的 `negotiateDuringLogin(AsyncPlayerPreLoginEvent)`，用同一对
  `C2S_HELLO` / `S2C_HELLO` record，判据从「3 s 静默」换成「空负载应答」。
- 验收脚本已经就位：`docs/TEST.md` 的 `TC-GAME-061`（三次起停，含第 7 步确认没有落进引擎那条
  `INVALID_PROXY_RESPONSE` 文案）。
- 切换步骤：给 `:server-app:run` 加 `-Dsynaxis.app.handshake-phase=login`，就绪前那行 `Gameplay mode: …`
  会以 `in the login phase (schema_version=1)` 结尾。**探针与客户端模组都已经两种形态都支持，切换时一行不用改**：
  模组同时在 `ClientLoginNetworking` 上注册了 `synaxis:handshake` 的 query 应答
  （`registerGlobalReceiver(Identifier, LoginQueryRequestHandler)`，返回 `CompletableFuture<FriendlyByteBuf>`）
  与 `ClientConfigurationConnectionEvents.START` 上的主动发送，由服务端的启动参数决定哪条真正用上
  （2026-09-22 随阶段 6a 落地）。

真走这条时别忘了 §28.2 那条技术债：**赢的那条留下、输的那条连同 `synaxis.app.handshake-phase` 一起删掉**。
