package io.github.bearisbug.synaxis.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.MainHand;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.server.ServerListPingEvent;
import io.github.bearisbug.synaxis.event.server.ServerListPingEvent.SamplePlayer;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.message.ChatMessageType;
import io.github.bearisbug.synaxis.network.packet.PacketReading;
import io.github.bearisbug.synaxis.network.packet.PacketWriting;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket.Intent;
import io.github.bearisbug.synaxis.network.packet.client.status.StatusRequestPacket;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.status.ResponsePacket;
import io.github.bearisbug.synaxis.network.player.ClientSettings;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.testing.Env;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.zip.DataFormatException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server list of {@code docs/DESIGN-PROD.md} {@code REQ-PROD-037} ({@code API-PROD-024}), asked
 * for the way a client does: a status handshake and a status request to a server listening on
 * loopback, the answer read back off the socket.
 */
public class ServerListPingSocketTest {

    @TempDir
    Path directory;

    /** The first half of the §19 scenario: what is configured is what the list shows. */
    @Test
    public void theAnswerCarriesTheConfiguredMotdIconAndTheRealLimit() throws IOException {
        final byte[] png = Pngs.solid(64, 64);
        final Path icon = Files.write(directory.resolve("icon.png"), png);
        final String motd = "Synaxis 内测\n第二行";
        final Env env = listening(config -> config.maxPlayers(300).motd(motd).serverIcon(icon.toString()));
        try {
            final JsonObject status = ping(env.process());
            final JsonObject version = status.getAsJsonObject("version");
            assertEquals(MinecraftServer.VERSION_NAME, version.get("name").getAsString());
            assertEquals(MinecraftServer.PROTOCOL_VERSION, version.get("protocol").getAsInt());
            final JsonObject players = status.getAsJsonObject("players");
            assertEquals(300, players.get("max").getAsInt());
            assertEquals(0, players.get("online").getAsInt());
            assertFalse(players.has("sample"), "nobody in play, no sample");
            assertEquals(Component.text(motd), description(status));
            assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(png),
                    status.get("favicon").getAsString());
        } finally {
            env.process().stop();
        }
    }

    /** Nothing configured: the engine defaults, which are the MOTD {@code synaxis} and no icon. */
    @Test
    public void withNothingConfiguredTheListShowsTheEngineDefaults() throws IOException {
        final Env env = listening(UnaryOperator.identity());
        try {
            final JsonObject status = ping(env.process());
            // No seat limit: the socket limit of the gate is the real one
            assertEquals(1024, status.getAsJsonObject("players").get("max").getAsInt());
            assertEquals(Component.text("synaxis"), description(status));
            assertFalse(status.has("favicon"), status.toString());
        } finally {
            env.process().stop();
        }
    }

    /**
     * A listener sees the connection that asked and may change every field; the limit it may only
     * raise, and a lower one is refused without changing what is sent.
     */
    @Test
    public void aListenerChangesTheAnswerButCannotShowALowerLimit() throws IOException {
        final Path icon = Files.write(directory.resolve("icon.png"), Pngs.solid(64, 64));
        final Env env = listening(config -> config.maxPlayers(300).serverIcon(icon.toString()));
        final UUID gm = UUID.randomUUID();
        final AtomicReference<String> refusal = new AtomicReference<>();
        final AtomicInteger limitAfterRefusal = new AtomicInteger();
        final AtomicReference<String> asker = new AtomicReference<>();
        env.process().eventHandler().addListener(ServerListPingEvent.class, event -> {
            asker.set(event.getConnection().getRemoteAddress().toString());
            event.setDescription(Component.translatable("synaxis:server-list.motd"));
            event.setOnlinePlayers(42);
            event.setSample(List.of(new SamplePlayer("GM on duty", gm)));
            event.setFavicon(null);
            try {
                event.setMaxPlayers(100);
            } catch (IllegalArgumentException e) {
                refusal.set(e.getMessage());
            }
            limitAfterRefusal.set(event.getMaxPlayers());
            event.setMaxPlayers(450);
        });
        try {
            final JsonObject status = ping(env.process());
            assertEquals("the server list must not show a player limit below the real one, 300, but was given 100",
                    refusal.get());
            assertEquals(300, limitAfterRefusal.get(), "a refused limit leaves the real one in place");
            final JsonObject players = status.getAsJsonObject("players");
            assertEquals(450, players.get("max").getAsInt(), "a higher limit is allowed");
            assertEquals(42, players.get("online").getAsInt());
            assertEquals(List.of(List.of("GM on duty", gm.toString())), sample(status));
            assertEquals(Component.translatable("synaxis:server-list.motd"), description(status));
            assertFalse(status.has("favicon"), "the listener took the configured icon away");
            assertTrue(asker.get().contains("127.0.0.1"), "the connection that asked: " + asker.get());
        } finally {
            env.process().stop();
        }
    }

    /**
     * The sample lists the first 12 players in play, in the order they entered it; one who asked
     * not to be listed is an anonymous line in its place, as in vanilla.
     */
    @Test
    public void theSampleListsTwelvePlayersAndHidesThoseWhoAskedNotToBeListed() throws IOException {
        final Env env = listening(UnaryOperator.identity());
        try {
            final List<Player> players = joinPlayers(env, 13);
            doNotList(players.get(1));

            final JsonObject status = ping(env.process());
            assertEquals(13, status.getAsJsonObject("players").get("online").getAsInt());
            final List<List<String>> expected = new ArrayList<>();
            for (Player player : players.subList(0, 12)) {
                expected.add(player == players.get(1)
                        ? List.of("Anonymous Player", "00000000-0000-0000-0000-000000000000")
                        : List.of(player.getUsername(), player.getUuid().toString()));
            }
            assertEquals(expected, sample(status), "the 13th player is left out");
        } finally {
            env.process().stop();
        }
    }

    /** {@code synaxis.hide-online-players} means what it means in vanilla: no sample, the count stays. */
    @Test
    public void hidingTheOnlinePlayersLeavesTheCountAndDropsTheSample() throws IOException {
        final Env env = listening(config -> config.hideOnlinePlayers(true));
        try {
            joinPlayers(env, 2);
            final JsonObject players = ping(env.process()).getAsJsonObject("players");
            assertEquals(2, players.get("online").getAsInt());
            assertFalse(players.has("sample"), players.toString());
        } finally {
            env.process().stop();
        }
    }

    private static Env listening(UnaryOperator<ServerConfig.Builder> settings)
            throws IOException {
        final Env env = Env.create(settings);
        env.process().server().init(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        env.process().server().start();
        return env;
    }

    private static List<Player> joinPlayers(Env env, int count) {
        final Instance instance = env.process().instance().createInstanceContainer();
        final List<Player> players = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            players.add(env.createConnection(new GameProfile(UUID.randomUUID(), "player" + i))
                    .connect(instance, new Pos(0, 40, 0)));
        }
        return players;
    }

    private static void doNotList(Player player) {
        final ClientSettings settings = player.getSettings();
        player.refreshSettings(new ClientSettings(Locale.US, settings.viewDistance(), ChatMessageType.FULL, true,
                settings.displayedSkinParts(), MainHand.RIGHT, true, false, settings.particleSetting()));
    }

    private static Component description(JsonObject status) {
        return GsonComponentSerializer.gson().deserializeFromTree(status.get("description"));
    }

    private static List<List<String>> sample(JsonObject status) {
        final JsonArray entries = status.getAsJsonObject("players").getAsJsonArray("sample");
        final List<List<String>> sample = new ArrayList<>();
        for (var entry : entries) {
            sample.add(List.of(entry.getAsJsonObject().get("name").getAsString(),
                    entry.getAsJsonObject().get("id").getAsString()));
        }
        return sample;
    }

    /** A status handshake and a status request on a new connection, and the JSON the server answers. */
    private static JsonObject ping(ServerProcess process) throws IOException {
        try (SocketChannel channel = SocketChannel.open(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), process.server().getPort()))) {
            channel.socket().setSoTimeout(5_000);
            send(channel, ConnectionState.HANDSHAKE, new ClientHandshakePacket(MinecraftServer.PROTOCOL_VERSION,
                    "localhost", process.server().getPort(), Intent.STATUS));
            send(channel, ConnectionState.STATUS, new StatusRequestPacket());
            final ResponsePacket response = assertInstanceOf(ResponsePacket.class, read(channel, process));
            return JsonParser.parseString(response.jsonResponse()).getAsJsonObject();
        }
    }

    private static void send(SocketChannel channel, ConnectionState state, ClientPacket packet) throws IOException {
        final NetworkBuffer framed = PacketWriting.allocateTrimmedPacket(state, packet, null, 0);
        while (!framed.writeChannel(channel)) {
            // A blocking channel writes everything, the loop only documents the contract
        }
    }

    private static ServerPacket read(SocketChannel channel, ServerProcess process) throws IOException {
        final InputStream in = channel.socket().getInputStream();
        final ByteArrayOutputStream frame = new ByteArrayOutputStream();
        int length = 0;
        for (int shift = 0; ; shift += 7) {
            final int b = in.read();
            if (b < 0) throw new AssertionError("closed before the length of the answer");
            frame.write(b);
            length |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
        }
        frame.write(in.readNBytes(length));
        final byte[] bytes = frame.toByteArray();
        final NetworkBuffer buffer = NetworkBuffer.wrap(bytes, 0, bytes.length, process.registries());
        final PacketReading.Result<ServerPacket> result;
        try {
            result = PacketReading.readServers(buffer, ConnectionState.STATUS, false);
        } catch (DataFormatException e) {
            throw new AssertionError("not a packet: " + bytes.length + " bytes", e);
        }
        if (!(result instanceof PacketReading.Result.Success<ServerPacket> success))
            throw new AssertionError("not a whole packet: " + bytes.length + " bytes");
        return success.packets().getFirst().packet();
    }
}
