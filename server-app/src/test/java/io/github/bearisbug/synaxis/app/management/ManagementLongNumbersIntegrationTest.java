package io.github.bearisbug.synaxis.app.management;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.bearisbug.synaxis.app.command.HostCommands;
import io.github.bearisbug.synaxis.app.command.Profiler;
import io.github.bearisbug.synaxis.app.moderation.HostModeration;
import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.plugin.Permissions;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The longest JSON number a management message may hold ({@code docs/DESIGN-PROD.md}
 * {@code API-PROD-022} the message item, §19 the scenario of long numbers, §28.2 the open question
 * on them): Gson's reader holds a number in its buffer of 1,024 characters and gives up on a longer
 * one, so a number of 1,023 characters is read, exactly, and one of 1,024 makes the whole message a
 * parse error with a null id. This pins the limit the design writes down; a reader without it turns
 * the second half red.
 */
@EnvTest
class ManagementLongNumbersIntegrationTest {

    /** The longest number Gson reads, and exactly 1: "1." and 1,021 zeros. */
    private static final String LONGEST_ONE = "1." + "0".repeat(1_021);

    @TempDir
    Path workingDirectory;

    private final List<Map<String, ?>> events = new CopyOnWriteArrayList<>();

    private ManagementMethods methods(Env env) {
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
        final PlayerStore store = PlayerStore.inMemory(env.process(), (_, _) -> { });
        final HostCommands commands = new HostCommands(env.process(), none, store, (_, _) -> { },
                new Profiler(workingDirectory.resolve("profiles"), "test", Clock.systemUTC()));
        return new ManagementMethods(env.process(), commands, store, HostModeration.withoutDatabase(env.process()),
                null, null, "test", (_, attributes) -> events.add(attributes));
    }

    private static String reply(ManagementMethods methods, String request) {
        return String.valueOf(methods.handle(request).text());
    }

    private static void assertParseError(String reply) {
        final JsonObject answer = JsonParser.parseString(reply).getAsJsonObject();
        assertTrue(answer.has("error"), reply);
        assertEquals(-32700, answer.getAsJsonObject("error").get("code").getAsInt(), reply);
        assertTrue(answer.get("id").isJsonNull(), reply);
    }

    @Test
    void aNumberOfOneThousandAndTwentyFourCharactersIsNotReadAsJson(Env env) {
        final ManagementMethods methods = methods(env);
        assertEquals(1_023, LONGEST_ONE.length());

        final String recorded = reply(methods, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"synaxis:profile\","
                + "\"params\":{\"seconds\":" + LONGEST_ONE + "}}");
        final JsonObject result = JsonParser.parseString(recorded).getAsJsonObject().getAsJsonObject("result");
        assertTrue(result != null && result.get("seconds").getAsInt() == 1, "1,023 characters, exactly 1: " + recorded);

        final String tooLong = LONGEST_ONE + "0";
        assertParseError(reply(methods, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"synaxis:profile\","
                + "\"params\":{\"seconds\":" + tooLong + "}}"));

        final String longestId = "9".repeat(1_023);
        final String echoed = reply(methods, "{\"jsonrpc\":\"2.0\",\"id\":" + longestId + ",\"method\":\"minecraft:players\"}");
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":" + longestId + ",\"result\":[]}", echoed);
        assertParseError(reply(methods, "{\"jsonrpc\":\"2.0\",\"id\":" + longestId + "9,\"method\":\"minecraft:players\"}"));

        // Only the two that were read ran
        assertEquals(List.of("synaxis:profile ok", "minecraft:players ok"), events.stream()
                .map(event -> String.valueOf(event.get("command")).replaceFirst(" .*", "") + " " + event.get("result"))
                .toList());
    }
}
