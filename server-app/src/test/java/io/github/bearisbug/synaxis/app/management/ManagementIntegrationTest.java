package io.github.bearisbug.synaxis.app.management;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.app.command.HostCommands;
import io.github.bearisbug.synaxis.app.command.Profiler;
import io.github.bearisbug.synaxis.app.moderation.HostModeration;
import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.command.ConsoleSender;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerDisconnectEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.monitoring.EventsJFR;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.PacketReading;
import io.github.bearisbug.synaxis.network.packet.PacketWriting;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginStartPacket;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.DisconnectPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginDisconnectPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SystemChatPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.plugin.Permissions;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.http.WebSocketHandshakeException;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;

import static io.github.bearisbug.synaxis.app.management.ManagementClient.await;
import static io.github.bearisbug.synaxis.app.management.ManagementSettingsTest.TOKEN;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The management interface over a real WebSocket ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-032},
 * {@code REQ-PROD-034}, {@code API-PROD-022}, §19 the management scenarios): a server bound on a
 * random loopback port, and the JDK's WebSocket client or a raw socket on the other end.
 */
@EnvTest
class ManagementIntegrationTest {

    private static final Duration HANDSHAKE_DEADLINE = Duration.ofMillis(300);

    @TempDir
    Path workingDirectory;

    private final List<Map<String, ?>> events = new CopyOnWriteArrayList<>();
    private final List<AutoCloseable> opened = new ArrayList<>();
    private @Nullable HostCommands commands;
    private @Nullable ManagementMethods methods;

    @AfterEach
    void closeEverything() throws Exception {
        Collections.reverse(opened);
        for (AutoCloseable closeable : opened) closeable.close();
    }

    private static final Permissions NO_NODES = new Permissions() {
        @Override
        public boolean has(Player player, String node) {
            return false;
        }

        @Override
        public Set<String> roles(Player player) {
            return Set.of();
        }
    };

    /** The methods of a process without a database, the built in commands registered once as in Main. */
    private ManagementMethods methods(Env env) {
        if (methods != null) return methods;
        final PlayerStore store = PlayerStore.inMemory(env.process(), (_, _) -> { });
        commands = new HostCommands(env.process(), NO_NODES, store, (_, attributes) -> events.add(attributes),
                new Profiler(workingDirectory.resolve("profiles"), "test", Clock.systemUTC()));
        commands.register();
        methods = new ManagementMethods(env.process(), commands, store, HostModeration.withoutDatabase(env.process()),
                null, null, "test", (_, attributes) -> events.add(attributes));
        return methods;
    }

    private InetSocketAddress serve(Env env, String bind) {
        final ManagementServer server = ManagementServer.serve(ManagementSettings.parse("true", bind, TOKEN),
                env.process(), methods(env), HANDSHAKE_DEADLINE);
        opened.add(server);
        final InetSocketAddress address = server.address();
        assertTrue(address != null && address.getPort() > 0, String.valueOf(address));
        return address;
    }

    private ManagementClient connect(InetSocketAddress address) throws Exception {
        final ManagementClient client = ManagementClient.connect(address, "Bearer " + TOKEN);
        opened.add(client);
        return client;
    }

    private List<String> managementCommands() {
        return events.stream().filter(event -> "management".equals(event.get("actor")))
                .map(event -> event.get("command") + " " + event.get("target") + " " + event.get("result")).toList();
    }

    /** What the server answers a raw HTTP request, read until it closes the socket or 2 s pass. */
    private static byte[] rawRequest(InetSocketAddress address, String request) throws IOException {
        try (Socket socket = new Socket(Proxy.NO_PROXY)) {
            socket.connect(address, 5_000);
            socket.setSoTimeout(2_000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            final ByteArrayOutputStream answer = new ByteArrayOutputStream();
            try {
                socket.getInputStream().transferTo(answer);
            } catch (SocketTimeoutException _) {
                // Still open: what came so far is the whole answer
            }
            return answer.toByteArray();
        }
    }

    private static String upgrade(InetSocketAddress address, @Nullable String authorization) {
        return "GET / HTTP/1.1\r\nHost: " + address.getHostString() + ":" + address.getPort() + "\r\n"
                + "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                + "Sec-WebSocket-Version: 13\r\n" + (authorization == null ? "" : "Authorization: " + authorization + "\r\n")
                + "\r\n";
    }

    private static List<ILoggingEvent> capture(Runnable body) {
        final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            body.run();
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
        return appender.list;
    }

    private static String text(Component component) {
        if (component instanceof TranslatableComponent translatable) return translatable.key();
        return ((TextComponent) component).content();
    }

    @Test
    void aHandshakeWithoutTheTokenIsRefusedBeforeAnythingRuns(Env env) throws Exception {
        final List<ILoggingEvent> lines = capture(() -> {
            try {
                final InetSocketAddress address = serve(env, "127.0.0.1:0");
                final byte[] none = rawRequest(address, upgrade(address, null));
                final String answer = new String(none, StandardCharsets.US_ASCII);
                assertTrue(answer.startsWith("HTTP/1.1 404 WebSocket Upgrade Failure\r\n"), answer);
                // Wrong the same length, wrong another length, the right token under another scheme,
                // and a request that is no upgrade at all: one answer, byte for byte
                final String sameLength = "Bearer " + "f".repeat(TOKEN.length());
                for (String refused : List.of(upgrade(address, sameLength), upgrade(address, "Bearer short"),
                        upgrade(address, "Basic " + TOKEN), "GET /minecraft:players HTTP/1.1\r\nHost: x\r\n\r\n"))
                    assertArrayEquals(none, rawRequest(address, refused), refused);

                final ExecutionException wrong = assertThrows(ExecutionException.class,
                        () -> ManagementClient.connect(address, sameLength));
                assertEquals(404, assertInstanceOf(WebSocketHandshakeException.class, wrong.getCause())
                        .getResponse().statusCode());
                assertTrue(managementCommands().isEmpty(), "no refused handshake ran a method: " + events);

                final String accepted = new String(rawRequest(new InetSocketAddress(address.getAddress(),
                        address.getPort()), upgrade(address, "Bearer " + TOKEN) + "\u0088\u0080\0\0\0\0"),
                        StandardCharsets.ISO_8859_1);
                assertTrue(accepted.startsWith("HTTP/1.1 101"), accepted);
                final ManagementClient client = connect(address);
                assertEquals(new JsonArray(), client.call(1, "minecraft:players", null).get("result"));
                assertEquals(List.of("minecraft:players null ok"), managementCommands());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        assertTrue(lines.stream().anyMatch(line -> line.getFormattedMessage().startsWith("Management interface listening on")),
                "the ready line");
        for (ILoggingEvent line : lines)
            assertFalse(line.getFormattedMessage().contains(TOKEN), "the token is in the log: " + line.getFormattedMessage());
    }

    @Test
    void everyMethodAnswersOverARealWebSocket(Env env) throws Exception {
        final ManagementClient client = connect(serve(env, "127.0.0.1:0"));
        final Instance instance = env.createFlatInstance();
        final TestConnection aliceConnection = env.createConnection(new GameProfile(UUID.randomUUID(), "Alice"));
        final Collector<SystemChatPacket> aliceChat = aliceConnection.trackIncoming(SystemChatPacket.class);
        final Player alice = aliceConnection.connect(instance, new Pos(0, 41, 0));
        final TestConnection bobConnection = env.createConnection(new GameProfile(UUID.randomUUID(), "Bob"));
        final Collector<DisconnectPacket> bobKicked = bobConnection.trackIncoming(DisconnectPacket.class);
        final Player bob = bobConnection.connect(instance, new Pos(0, 41, 0));

        final JsonArray players = client.call(1, "minecraft:players", null).getAsJsonArray("result");
        assertEquals(2, players.size(), players.toString());
        assertTrue(players.contains(JsonParser.parseString("{\"id\":\"" + alice.getUuid() + "\",\"name\":\"Alice\"}")),
                players.toString());

        final JsonObject status = client.call(2, "minecraft:server/status", null).getAsJsonObject("result");
        assertEquals(env.process().isAlive(), status.get("started").getAsBoolean());
        assertEquals(2, status.getAsJsonArray("players").size());
        assertEquals(MinecraftServer.VERSION_NAME, status.getAsJsonObject("version").get("name").getAsString());
        assertEquals(776, status.getAsJsonObject("version").get("protocol").getAsInt());

        final JsonObject broadcast = client.call(3, "synaxis:broadcast",
                "[{\"translatable\":\"synaxis:server-stopping-in\",\"translatableParams\":[\"30\"],\"literal\":\"soon\"}]");
        assertEquals(2, broadcast.get("result").getAsInt());
        final TranslatableComponent announced = assertInstanceOf(TranslatableComponent.class,
                aliceChat.collect().getLast().message());
        assertEquals("synaxis:server-stopping-in", announced.key());
        assertEquals("soon", announced.fallback());
        assertEquals("30", text(announced.arguments().getFirst().asComponent()));

        assertTrue(client.call(4, "minecraft:server/save", "{\"flush\":true}").get("result").getAsBoolean());

        // By name here and by id there; Nobody is not online, so only Bob is kicked
        final JsonObject kicked = client.call(5, "minecraft:players/kick",
                "{\"kick\":[{\"player\":{\"name\":\"Bob\"},\"message\":{\"literal\":\"bye\"}},{\"player\":{\"name\":\"Nobody\"}}]}");
        assertEquals(JsonParser.parseString("[{\"id\":\"" + bob.getUuid() + "\",\"name\":\"Bob\"}]"), kicked.get("result"));
        assertEquals("bye", text(bobKicked.collect().getFirst().message()));
        final UUID stranger = UUID.randomUUID();
        final JsonObject none = client.call(6, "minecraft:players/kick", "[[{\"player\":{\"id\":\"" + stranger + "\"}}]]");
        assertEquals(new JsonArray(), none.get("result"));

        final JsonObject shard = client.call(7, "synaxis:shard/status", null).getAsJsonObject("result");
        assertEquals("test", shard.get("shard_id").getAsString());
        assertTrue(shard.get("session").isJsonNull(), "no shard session in memory");
        assertEquals(env.process().connection().getOnlinePlayerCount(), shard.get("online").getAsInt());
        assertFalse(shard.get("draining").getAsBoolean());
        assertEquals("running", shard.get("shutdown_phase").getAsString());
        assertTrue(shard.has("tps") && shard.getAsJsonObject("mspt").has("p99"), shard.toString());

        assertEquals(List.of("minecraft:players null ok", "minecraft:server/status null ok",
                "synaxis:broadcast [{\"translatable\":\"synaxis:server-stopping-in\",\"translatableParams\":[\"30\"],"
                        + "\"literal\":\"soon\"}] null ok",
                "minecraft:server/save {\"flush\":true} null ok",
                "minecraft:players/kick {\"kick\":[{\"player\":{\"name\":\"Bob\"},\"message\":{\"literal\":\"bye\"}},"
                        + "{\"player\":{\"name\":\"Nobody\"}}]} " + bob.getUuid() + " ok",
                "minecraft:players/kick [[{\"player\":{\"id\":\"" + stranger + "\"}}]] null no_target",
                "synaxis:shard/status null ok"), managementCommands());
    }

    @Test
    void drainClosesTheLoginGateOnlyAndOnce(Env env) throws Exception {
        final ManagementClient client = connect(serve(env, "127.0.0.1:0"));
        final Player online = env.createPlayer(env.createFlatInstance(), new Pos(0, 41, 0));
        env.process().start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));

        assertTrue(client.call(1, "synaxis:shard/drain", null).get("result").getAsBoolean());

        // A new login is turned away the way a shutdown turns it away
        try (SocketChannel channel = SocketChannel.open(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                env.process().server().getPort()))) {
            channel.socket().setSoTimeout(5_000);
            send(channel, ConnectionState.HANDSHAKE, new ClientHandshakePacket(MinecraftServer.PROTOCOL_VERSION,
                    "localhost", env.process().server().getPort(), ClientHandshakePacket.Intent.LOGIN));
            send(channel, ConnectionState.LOGIN, new ClientLoginStartPacket("Latecomer", UUID.randomUUID()));
            final LoginDisconnectPacket refused = assertInstanceOf(LoginDisconnectPacket.class, read(channel, env.process()));
            assertEquals("synaxis:server-stopping", text(refused.kickMessage()));
        }
        assertTrue(env.process().connection().gate().isClosed());
        assertFalse(client.call(2, "synaxis:shard/drain", null).get("result").getAsBoolean(), "already closed");
        env.tick();
        assertTrue(online.isOnline(), "a drain kicks nobody");
        assertEquals(ServerProcess.ShutdownPhase.RUNNING, env.process().shutdownPhase(), "a drain is no shutdown");
        assertTrue(client.call(3, "synaxis:shard/status", null).getAsJsonObject("result").get("draining").getAsBoolean());
        assertEquals(List.of("synaxis:shard/drain null ok", "synaxis:shard/drain null invalid", "synaxis:shard/status null ok"),
                managementCommands());
    }

    /**
     * Two drains at once, as from two operators or from one connection that does not wait for the first
     * answer, each call on its own virtual thread: one of them closed the gate, so exactly one answers true
     * and is recorded {@code ok}, the other {@code invalid} ({@code docs/DESIGN-PROD.md} {@code API-PROD-022}).
     */
    @Test
    void ofTwoDrainsAtOnceExactlyOneClosesTheGate(Env env) throws Exception {
        final ManagementMethods methods = methods(env);
        final CyclicBarrier together = new CyclicBarrier(2);
        final List<Boolean> answers = new CopyOnWriteArrayList<>();
        final List<Thread> calls = new ArrayList<>();
        for (int id = 1; id <= 2; id++) {
            final String request = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"synaxis:shard/drain\"}";
            calls.add(Thread.ofVirtual().start(() -> {
                try {
                    together.await();
                } catch (InterruptedException | BrokenBarrierException e) {
                    throw new IllegalStateException(e);
                }
                final String reply = methods.handle(request).text();
                answers.add(JsonParser.parseString(String.valueOf(reply)).getAsJsonObject().get("result").getAsBoolean());
            }));
        }
        for (Thread call : calls) call.join();

        assertEquals(1, answers.stream().filter(Boolean::booleanValue).count(), answers.toString());
        assertEquals(List.of("synaxis:shard/drain null invalid", "synaxis:shard/drain null ok"),
                managementCommands().stream().sorted().toList());
        assertTrue(env.process().connection().gate().isClosed());
    }

    private static void send(SocketChannel channel, ConnectionState state, ClientPacket packet) throws IOException {
        final NetworkBuffer framed = PacketWriting.allocateTrimmedPacket(state, packet, null, 0);
        while (!framed.writeChannel(channel)) {
            // A blocking channel writes everything, the loop only documents the contract
        }
    }

    /** One uncompressed, unencrypted login packet from the server. */
    private static ServerPacket read(SocketChannel channel, ServerProcess process) throws Exception {
        final InputStream in = channel.socket().getInputStream();
        final ByteArrayOutputStream frame = new ByteArrayOutputStream();
        int length = 0;
        for (int shift = 0; ; shift += 7) {
            final int b = in.read();
            if (b < 0) throw new AssertionError("closed before an answer");
            frame.write(b);
            length |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
        }
        frame.write(in.readNBytes(length));
        final byte[] bytes = frame.toByteArray();
        final PacketReading.Result<ServerPacket> result = PacketReading.readServers(
                NetworkBuffer.wrap(bytes, 0, bytes.length, process.registries()), ConnectionState.LOGIN, false);
        if (!(result instanceof PacketReading.Result.Success<ServerPacket> success))
            throw new AssertionError("not a packet: " + bytes.length + " bytes");
        return success.packets().getFirst().packet();
    }

    @Test
    void errorsAreJsonRpcErrorsAndNotificationsAreNotAnswered(Env env) throws Exception {
        final InetSocketAddress address = serve(env, "127.0.0.1:0");
        // In before the client connects, so that no joined notification is among the answers below
        final TestConnection connection = env.createConnection(new GameProfile(UUID.randomUUID(), "Listener"));
        final Collector<SystemChatPacket> chat = connection.trackIncoming(SystemChatPacket.class);
        connection.connect(env.createFlatInstance(), new Pos(0, 41, 0));
        final ManagementClient client = connect(address);

        client.send("{not json");
        final JsonObject parse = client.poll(ManagementClient.WAIT);
        assertError(parse, -32700);
        assertTrue(parse.get("id").isJsonNull());
        UUID.fromString(parse.getAsJsonObject("error").getAsJsonObject("data").get("request_id").getAsString());

        client.send("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"minecraft:players\"}]");
        assertError(client.poll(ManagementClient.WAIT), -32600);
        client.send("{\"id\":2,\"method\":\"minecraft:players\"}");
        assertError(client.answer(2, null), -32600);
        client.send("{\"jsonrpc\":2.0,\"id\":3,\"method\":\"minecraft:players\"}");
        assertError(client.answer(3, null), -32600);
        client.send("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":7}");
        assertError(client.answer(4, null), -32600);
        client.send("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"minecraft:players\",\"params\":\"x\"}");
        assertError(client.answer(5, null), -32600);
        client.send("{\"jsonrpc\":\"2.0\",\"id\":true,\"method\":\"minecraft:players\"}");
        assertError(client.poll(ManagementClient.WAIT), -32600);

        final JsonObject unknown = client.call(6, "synaxis:teleport", "[]");
        assertError(unknown, -32601);
        assertEquals("Method not found", unknown.getAsJsonObject("error").get("message").getAsString());
        assertError(client.call(7, "minecraft:players/kick", null), -32602);
        assertError(client.call(8, "minecraft:players/kick", "{\"kick\":[{\"player\":{}}]}"), -32602);
        assertError(client.call(9, "minecraft:players/kick", "{\"kick\":[{\"player\":{\"id\":\"not-a-uuid\"}}]}"), -32602);
        assertError(client.call(10, "synaxis:broadcast", "[{}]"), -32602);
        assertError(client.call(11, "synaxis:broadcast", "[{\"literal\":1}]"), -32602);
        assertError(client.call(12, "synaxis:broadcast", "[{\"translatable\":\"k\",\"translatableParams\":[{}]}]"), -32602);
        assertError(client.call(13, "synaxis:profile", "{\"seconds\":0}"), -32602);
        assertError(client.call(14, "synaxis:profile", "{\"seconds\":1.5}"), -32602);
        assertError(client.call(15, "synaxis:profile", "[\"soon\"]"), -32602);
        // Without a database there is nothing to ban into
        assertError(client.call(16, "minecraft:bans/add", "[[{\"player\":{\"id\":\"" + UUID.randomUUID() + "\"}}]]"), -32000);
        assertEquals(new JsonArray(), client.call(17, "minecraft:bans", null).get("result"));

        // A notification runs and is not answered: of the three, only 18 gets an answer
        client.send("{\"jsonrpc\":\"2.0\",\"method\":\"synaxis:broadcast\",\"params\":{\"message\":{\"literal\":\"quiet\"}}}");
        client.send("{\"jsonrpc\":\"2.0\",\"method\":\"no:such-method\"}");
        client.send("{\"jsonrpc\":\"2.0\",\"id\":18,\"method\":\"minecraft:players\"}");
        final List<JsonObject> answers = new ArrayList<>();
        for (JsonObject answer; (answer = client.poll(Duration.ofSeconds(1))) != null; ) answers.add(answer);
        assertEquals(1, answers.size(), "one answer for three messages: " + answers);
        assertEquals(18, answers.getFirst().get("id").getAsInt());
        await(() -> managementCommands().stream().anyMatch(command -> command.startsWith("synaxis:broadcast")), "the broadcast");
        assertTrue(chat.collect().stream().anyMatch(packet -> "quiet".equals(text(packet.message()))), "the broadcast ran");
    }

    private static void assertError(@Nullable JsonObject reply, int code) {
        assertTrue(reply != null && reply.has("error"), String.valueOf(reply));
        final JsonObject error = reply.getAsJsonObject("error");
        assertEquals(code, error.get("code").getAsInt(), reply.toString());
        assertFalse(reply.has("result"), reply.toString());
        assertTrue(error.getAsJsonObject("data").has("request_id"), reply.toString());
        assertFalse(error.get("message").getAsString().contains("Exception"), reply.toString());
    }

    @Test
    void anUnforeseenFailureIsAnsweredWithoutItsStackAndLoggedOnceWithTheRequestId(Env env) {
        final HostCommands hostCommands = new HostCommands(env.process(), NO_NODES,
                PlayerStore.inMemory(env.process(), (_, _) -> { }), (_, _) -> { },
                new Profiler(workingDirectory.resolve("profiles"), "test", Clock.systemUTC()));
        // No store: saving fails in a way no method foresees
        final ManagementMethods broken = new ManagementMethods(env.process(), hostCommands, null,
                HostModeration.withoutDatabase(env.process()), null, null, "test", (_, attributes) -> events.add(attributes));
        final List<ILoggingEvent> lines = capture(() -> {
            final ManagementMethods.Reply reply = broken.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"minecraft:server/save\"}");
            final JsonObject answer = JsonParser.parseString(reply.text()).getAsJsonObject();
            assertError(answer, -32603);
            assertEquals("Internal error", answer.getAsJsonObject("error").get("message").getAsString());
            assertFalse(reply.text().contains("NullPointer"), reply.text());
            final String requestId = answer.getAsJsonObject("error").getAsJsonObject("data").get("request_id").getAsString();
            // A second failure within 10 s is counted, not written
            broken.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"minecraft:server/save\"}");
            assertTrue(requestId.length() == 36, requestId);
        });
        final List<ILoggingEvent> warnings = lines.stream().filter(line -> line.getLevel() == Level.WARN).toList();
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().getFormattedMessage().startsWith("Management call failed: method=minecraft:server/save request_id="),
                warnings.getFirst().getFormattedMessage());
        assertInstanceOf(NullPointerException.class,
                ((ThrowableProxy) warnings.getFirst().getThrowableProxy()).getThrowable());
        assertEquals(List.of("minecraft:server/save null unavailable", "minecraft:server/save null unavailable"),
                managementCommands());
    }

    @Test
    void playersEnteringAndLeavingAreAnnounced(Env env) throws Exception {
        final ManagementClient client = connect(serve(env, "127.0.0.1:0"));
        final Player player = env.createPlayer(env.createFlatInstance(), new Pos(0, 41, 0));

        final JsonObject joined = client.notification(ManagementServer.JOINED, null);
        assertEquals("2.0", joined.get("jsonrpc").getAsString());
        assertEquals(JsonParser.parseString("[{\"id\":\"" + player.getUuid() + "\",\"name\":\"" + player.getUsername()
                + "\"}]"), joined.get("params"));

        // One who leaves without ever getting into the world was never announced, and is not now
        final Player neverSpawned = new Player(new PlayerConnection(env.process()) {
            @Override
            public void sendPacket(SendablePacket packet) {
                // Nothing to write to
            }

            @Override
            public SocketAddress getRemoteAddress() {
                return new InetSocketAddress(InetAddress.getLoopbackAddress(), 1);
            }
        }, new GameProfile(UUID.randomUUID(), "Ghost"));
        env.process().eventHandler().call(new PlayerDisconnectEvent(neverSpawned));

        player.remove();
        final JsonObject left = client.notification(ManagementServer.LEFT, null);
        assertEquals(joined.get("params"), left.get("params"), "the next left is the one who joined");
        assertTrue(managementCommands().isEmpty(), "a notification the server sends is no call");
    }

    @Test
    void aMessageOverTheLimitClosesTheConnectionAndDoesNotRun(Env env) throws Exception {
        final InetSocketAddress address = serve(env, "127.0.0.1:0");
        final TestConnection connection = env.createConnection(new GameProfile(UUID.randomUUID(), "Reader"));
        final Collector<SystemChatPacket> chat = connection.trackIncoming(SystemChatPacket.class);
        connection.connect(env.createFlatInstance(), new Pos(0, 41, 0));

        final ManagementClient fits = connect(address);
        fits.send(broadcastOf(ManagementServer.MAX_MESSAGE_BYTES, 1));
        assertEquals(1, fits.answer(1, null).get("result").getAsInt(), "65,536 bytes run");

        final ManagementClient single = connect(address);
        single.send(broadcastOf(ManagementServer.MAX_MESSAGE_BYTES + 1, 2));
        assertEquals(1009, single.awaitClose());

        final ManagementClient fragmented = connect(address);
        final String message = broadcastOf(ManagementServer.MAX_MESSAGE_BYTES + 1, 3);
        fragmented.socket().sendText(message.substring(0, 40_000), false).get();
        fragmented.socket().sendText(message.substring(40_000), true).get();
        assertEquals(1009, fragmented.awaitClose());

        assertEquals(1, managementCommands().size(), "only the message that fits ran: " + managementCommands());
        assertEquals(1, chat.collect().size());
    }

    /** A broadcast request of exactly this many bytes. */
    private static String broadcastOf(int bytes, int id) {
        final String head = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"synaxis:broadcast\",\"params\":[{\"literal\":\"";
        final String tail = "\"}]}";
        return head + "x".repeat(bytes - head.length() - tail.length()) + tail;
    }

    @Test
    void beforeTheHandshakeTheRequestIsBoundedInSizeAndTime(Env env) throws Exception {
        final InetSocketAddress address = serve(env, "127.0.0.1:0");

        // A header that never ends: closed past 8 KiB, with nothing answered
        try (Socket socket = new Socket(Proxy.NO_PROXY)) {
            socket.connect(address, 5_000);
            socket.setSoTimeout(5_000);
            final OutputStream out = socket.getOutputStream();
            out.write(("GET / HTTP/1.1\r\nX-Filler: " + "a".repeat(ManagementServer.MAX_HANDSHAKE_BYTES)).getBytes(StandardCharsets.US_ASCII));
            out.flush();
            assertClosedWithoutAnswer(socket.getInputStream());
        }
        // A connection that says nothing: closed once the deadline is over
        try (Socket socket = new Socket(Proxy.NO_PROXY)) {
            socket.connect(address, 5_000);
            socket.setSoTimeout(5_000);
            final long start = System.nanoTime();
            assertClosedWithoutAnswer(socket.getInputStream());
            assertTrue(System.nanoTime() - start >= HANDSHAKE_DEADLINE.toNanos() / 2, "not before the deadline");
        }
        // The one who does finish in time is not affected
        final ManagementClient client = connect(address);
        Thread.sleep(HANDSHAKE_DEADLINE.toMillis() * 2);
        assertEquals(new JsonArray(), client.call(1, "minecraft:players", null).get("result"));
    }

    private static void assertClosedWithoutAnswer(InputStream in) throws IOException {
        final ByteArrayOutputStream answered = new ByteArrayOutputStream();
        try {
            for (int b; (b = in.read()) >= 0; ) answered.write(b);
        } catch (SocketTimeoutException e) {
            throw new AssertionError("still open after 5 s", e);
        } catch (SocketException _) {
            // Reset: the server closed with the request still unread, which is closed all the same
        }
        assertEquals(0, answered.size(), answered.toString(StandardCharsets.US_ASCII));
    }

    @Test
    void stopIsAnsweredFirstAndSharesTheConsoleGuard(Env env) throws Exception {
        final ManagementClient client = connect(serve(env, "127.0.0.1:0"));
        methods(env).serverStarted();

        assertTrue(client.call(1, "minecraft:server/stop", null).get("result").getAsBoolean());
        assertFalse(client.call(2, "minecraft:server/stop", null).get("result").getAsBoolean(), "already stopping");
        final List<String> console = new CopyOnWriteArrayList<>();
        final ConsoleSender sender = new ConsoleSender(env.process()) {
            @Override
            public void sendMessage(Component message) {
                console.add(text(message));
            }
        };
        env.process().command().execute(sender, "stop");
        assertEquals(List.of("synaxis:command-already-stopping"), console);
        await(() -> env.process().shutdownPhase() == ServerProcess.ShutdownPhase.STOPPED, "the process stops");
        assertEquals(List.of("minecraft:server/stop null ok", "minecraft:server/stop null invalid"), managementCommands());
    }

    @Test
    void profileWritesOneReadableRecordingAtATime(Env env) throws Exception {
        final ManagementClient client = connect(serve(env, "127.0.0.1:0"));

        client.send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"synaxis:profile\",\"params\":{\"seconds\":1}}");
        // The directory is made as the recording starts, and a second start waits for the first to return
        await(() -> Files.isDirectory(workingDirectory.resolve("profiles")), "the recording started");
        final JsonObject busy = client.call(2, "synaxis:profile", "[1]");
        assertError(busy, -32000);
        assertTrue(busy.getAsJsonObject("error").get("message").getAsString().startsWith("A recording to "), busy.toString());

        final JsonObject done = client.answer(1, env::tick).getAsJsonObject("result");
        assertEquals(1, done.get("seconds").getAsInt());
        final Path path = Path.of(done.get("path").getAsString());
        assertTrue(path.isAbsolute() && path.startsWith(workingDirectory.resolve("profiles")), path.toString());
        assertTrue(path.getFileName().toString().matches("profile-test-\\d{8}-\\d{6}-\\d{3}\\.jfr"), path.toString());
        final List<RecordedEvent> recorded = RecordingFile.readAllEvents(path);
        assertTrue(recorded.stream().anyMatch(event -> EventsJFR.SERVER_TICK.equals(event.getEventType().getName())),
                "the ticks of the recording are in it");
        assertEquals(List.of("synaxis:profile [1] null invalid", "synaxis:profile {\"seconds\":1} null ok"),
                managementCommands());
        Files.delete(path);
    }

    @Test
    void boundOnLoopbackItCannotBeReachedFromAnotherAddress(Env env) throws Exception {
        final InetAddress other = nonLoopbackAddress();
        Assumptions.assumeTrue(other != null, "this machine has no non-loopback IPv4 address");
        final InetSocketAddress address = serve(env, "127.0.0.1:0");

        try (Socket socket = new Socket(Proxy.NO_PROXY)) {
            assertThrows(ConnectException.class, () -> socket.connect(new InetSocketAddress(other, address.getPort()), 2_000));
        }
        final ManagementClient client = connect(address);
        assertEquals(new JsonArray(), client.call(1, "minecraft:players", null).get("result"));
    }

    private static @Nullable InetAddress nonLoopbackAddress() throws SocketException {
        for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            // Tunnels and virtual interfaces are left out: a VPN or a proxy in TUN mode answers any
            // connection to its own address, which says nothing about where this port listens
            if (!network.isUp() || network.isLoopback() || network.isPointToPoint() || network.isVirtual()) continue;
            for (InetAddress address : Collections.list(network.getInetAddresses()))
                if (address instanceof Inet4Address && !address.isLoopbackAddress()) return address;
        }
        return null;
    }

    @Test
    void disabledItBindsNothingAndAPortTakenRefusesTheStart(Env env) {
        final ManagementServer disabled = ManagementServer.serve(ManagementSettings.parse("false", "127.0.0.1:0", null),
                env.process(), methods(env));
        opened.add(disabled);
        assertNull(disabled.address());

        final InetSocketAddress address = serve(env, "127.0.0.1:0");
        final IllegalArgumentException taken = assertThrows(IllegalArgumentException.class,
                () -> ManagementServer.serve(ManagementSettings.parse("true", "127.0.0.1:" + address.getPort(), TOKEN),
                        env.process(), methods(env)));
        assertTrue(taken.getMessage().startsWith("synaxis.app.management.bind=127.0.0.1:" + address.getPort()
                + " cannot be bound"), taken.getMessage());
    }

    @Test
    void aRecordingThatCannotStartIsUnavailableAndABinaryFrameCloses(Env env) throws Exception {
        final ManagementClient client = connect(serve(env, "127.0.0.1:0"));

        // Where the recordings go is taken by a file: the server cannot, the call itself is fine
        Files.writeString(workingDirectory.resolve("profiles"), "in the way");
        final JsonObject cannot = client.call(1, "synaxis:profile", "{\"seconds\":1}");
        assertError(cannot, -32000);
        assertTrue(cannot.getAsJsonObject("error").get("message").getAsString().startsWith("The recording cannot start: "),
                cannot.toString());
        assertEquals(List.of("synaxis:profile {\"seconds\":1} null unavailable"), managementCommands());

        // JSON-RPC is text: a binary frame closes the connection with 1003
        client.socket().sendBinary(StandardCharsets.US_ASCII.encode("binary"), true).get();
        assertEquals(1003, client.awaitClose());
    }
}
