package io.github.bearisbug.synaxis.app.management;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.bearisbug.synaxis.app.command.HostCommands;
import io.github.bearisbug.synaxis.app.command.Profiler;
import io.github.bearisbug.synaxis.app.moderation.HostModeration;
import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.packet.server.play.SystemChatPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.plugin.Permissions;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.github.bearisbug.synaxis.app.management.ManagementSettingsTest.TOKEN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the management interface reads a message ({@code docs/DESIGN-PROD.md} {@code API-PROD-022},
 * §19 the scenario of strict JSON), over a real WebSocket: only RFC 8259 JSON is a request, and an
 * object that gives a name twice is not one. Every refused message below is a
 * {@code synaxis:broadcast} a lenient reader would run, so the player online is the witness.
 */
@EnvTest
class ManagementStrictJsonIntegrationTest {

    /** Each one runs as a broadcast of {@code lenient} when read leniently. */
    private static final List<String> LENIENT = List.of(
            "{'jsonrpc':'2.0','id':1,'method':'synaxis:broadcast','params':[{'literal':'lenient'}]}",
            "{jsonrpc:\"2.0\",id:2,method:\"synaxis:broadcast\",params:[{literal:\"lenient\"}]}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,/* note */\"method\":\"synaxis:broadcast\",\"params\":[{\"literal\":\"lenient\"}]}",
            "// note\n{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"synaxis:broadcast\",\"params\":[{\"literal\":\"lenient\"}]}",
            "# note\n{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"synaxis:broadcast\",\"params\":[{\"literal\":\"lenient\"}]}",
            "{\"jsonrpc\"=>\"2.0\",\"id\"=>6,\"method\"=>\"synaxis:broadcast\",\"params\"=>[{\"literal\"=>\"lenient\"}]}",
            "{\"jsonrpc\"=\"2.0\",\"id\"=7,\"method\"=\"synaxis:broadcast\",\"params\"=[{\"literal\"=\"lenient\"}]}",
            "{\"jsonrpc\":\"2.0\";\"id\":8;\"method\":\"synaxis:broadcast\";\"params\":[{\"literal\":\"lenient\"}]}",
            "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"synaxis:broadcast\",\"params\":[{\"literal\":\"lenient\"},]}",
            ")]}'\n{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"synaxis:broadcast\",\"params\":[{\"literal\":\"lenient\"}]}",
            "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"synaxis:broadcast\",\"params\":[{\"literal\":\"len\tient\"}]}",
            "{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"synaxis:broadcast\",\"params\":[{\"literal\":\"len\\'ient\"}]}",
            "{\"jsonrpc\":\"2.0\",\"id\":13,\"method\":\"synaxis:broadcast\",\"params\":[{\"literal\":\"lenient\"}]} x",
            "");

    @TempDir
    Path workingDirectory;

    private final List<Map<String, ?>> events = new CopyOnWriteArrayList<>();
    private final List<AutoCloseable> opened = new ArrayList<>();

    @AfterEach
    void closeEverything() throws Exception {
        for (AutoCloseable closeable : opened.reversed()) closeable.close();
    }

    private InetSocketAddress serve(Env env) {
        final PlayerStore store = PlayerStore.inMemory(env.process(), (_, _) -> { });
        final Permissions none = new Permissions() {
            @Override
            public boolean has(Player player, String node) {
                return false;
            }

            @Override
            public Set<String> roles(Player player) {
                return Set.of();
            }
        };
        final HostCommands commands = new HostCommands(env.process(), none, store, (_, _) -> { },
                new Profiler(workingDirectory.resolve("profiles"), "test", Clock.systemUTC()));
        final ManagementMethods methods = new ManagementMethods(env.process(), commands, store,
                HostModeration.withoutDatabase(env.process()), null, null, "test", (_, attributes) -> events.add(attributes));
        final ManagementServer server = ManagementServer.serve(ManagementSettings.parse("true", "127.0.0.1:0", TOKEN),
                env.process(), methods, Duration.ofSeconds(5));
        opened.add(server);
        return server.address();
    }

    private ManagementClient connect(InetSocketAddress address) throws Exception {
        final ManagementClient client = ManagementClient.connect(address, "Bearer " + TOKEN);
        opened.add(client);
        return client;
    }

    /** A player online, joined before the client connects so that no notification is among the answers. */
    private static Collector<SystemChatPacket> witness(Env env) {
        final TestConnection connection = env.createConnection(new GameProfile(UUID.randomUUID(), "Witness"));
        final Collector<SystemChatPacket> chat = connection.trackIncoming(SystemChatPacket.class);
        connection.connect(env.createFlatInstance(), new Pos(0, 41, 0));
        return chat;
    }

    private static List<String> texts(Collector<SystemChatPacket> chat) {
        return chat.collect().stream().map(SystemChatPacket::message)
                .map(message -> message instanceof TextComponent text ? text.content() : message.toString()).toList();
    }

    private List<String> managementCommands() {
        return events.stream().filter(event -> "management".equals(event.get("actor")))
                .map(event -> String.valueOf(event.get("command"))).toList();
    }

    /** The next message is an error with this code, a null id and a request id. */
    private static JsonObject assertRefused(ManagementClient client, String sent, int code) throws Exception {
        final JsonObject reply = client.poll(ManagementClient.WAIT);
        assertTrue(reply != null && reply.has("error"), "an error for " + sent + ": " + reply);
        assertEquals(code, reply.getAsJsonObject("error").get("code").getAsInt(), "for " + sent + ": " + reply);
        assertTrue(reply.get("id").isJsonNull(), "the id is null for " + sent + ": " + reply);
        assertFalse(reply.has("result"), reply.toString());
        UUID.fromString(reply.getAsJsonObject("error").getAsJsonObject("data").get("request_id").getAsString());
        return reply;
    }

    @Test
    void onlyStrictJsonIsARequest(Env env) throws Exception {
        final InetSocketAddress address = serve(env);
        final Collector<SystemChatPacket> chat = witness(env);
        final ManagementClient client = connect(address);

        for (String lenient : LENIENT) {
            client.send(lenient);
            final JsonObject reply = assertRefused(client, lenient, -32700);
            assertEquals("Parse error", reply.getAsJsonObject("error").get("message").getAsString());
        }
        assertEquals(List.of(), managementCommands(), "no refused message reached a method");

        // The same request written as RFC 8259 JSON runs; blanks around it are still JSON
        final JsonObject strict = client.call(20, "synaxis:broadcast", "[{\"literal\":\"strict\"}]");
        assertEquals(1, strict.get("result").getAsInt(), strict.toString());
        client.send(" \n{\"jsonrpc\":\"2.0\",\"id\":21,\"method\":\"minecraft:players\"}\t\n");
        assertEquals(1, client.answer(21, null).getAsJsonArray("result").size());
        assertEquals(List.of("synaxis:broadcast [{\"literal\":\"strict\"}]", "minecraft:players"), managementCommands());
        assertEquals(List.of("strict"), texts(chat), "the witness got the strict broadcast only");
    }

    @Test
    void aNameGivenTwiceInOneObjectIsAnInvalidRequest(Env env) throws Exception {
        final InetSocketAddress address = serve(env);
        final Collector<SystemChatPacket> chat = witness(env);
        final ManagementClient client = connect(address);

        // Last wins would broadcast, first wins would list the players: neither runs
        final String method = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"minecraft:players\","
                + "\"method\":\"synaxis:broadcast\",\"params\":[{\"literal\":\"twice\"}]}";
        client.send(method);
        assertEquals("The name method is given twice in one object",
                assertRefused(client, method, -32600).getAsJsonObject("error").get("message").getAsString());
        final String id = "{\"jsonrpc\":\"2.0\",\"id\":2,\"id\":3,\"method\":\"synaxis:broadcast\",\"params\":[{\"literal\":\"twice\"}]}";
        client.send(id);
        assertRefused(client, id, -32600);
        // Deeper down, inside the parameters
        final String nested = "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"synaxis:broadcast\","
                + "\"params\":{\"message\":{\"literal\":\"twice\",\"literal\":\"again\"}}}";
        client.send(nested);
        assertEquals("The name literal is given twice in one object",
                assertRefused(client, nested, -32600).getAsJsonObject("error").get("message").getAsString());
        assertEquals(List.of(), managementCommands(), "no refused message reached a method");
        assertEquals(List.of(), texts(chat));

        // The same name in two objects is two names
        final JsonObject kick = client.call(5, "minecraft:players/kick",
                "{\"kick\":[{\"player\":{\"name\":\"Nobody\"}},{\"player\":{\"name\":\"Nobody\"}}]}");
        assertEquals(new JsonArray(), kick.get("result"), kick.toString());
        assertEquals(1, managementCommands().size(), managementCommands().toString());
    }
}
