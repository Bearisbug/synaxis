package io.github.bearisbug.synaxis.app.management;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The number parameter of the management methods, {@code seconds} of {@code synaxis:profile}
 * ({@code docs/DESIGN-PROD.md} {@code API-PROD-022}, §19 the scenario of the seconds): it is read
 * from the text of the JSON number, exactly, so a number that only a double rounds into the range
 * is invalid params rather than an internal error once it is cut to an int.
 */
@EnvTest
class ManagementWholeNumbersIntegrationTest {

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

    private static JsonObject profile(ManagementMethods methods, int id, String seconds) {
        final String reply = methods.handle("{\"jsonrpc\":\"2.0\",\"id\":" + id
                + ",\"method\":\"synaxis:profile\",\"params\":{\"seconds\":" + seconds + "}}").text();
        return JsonParser.parseString(String.valueOf(reply)).getAsJsonObject();
    }

    @Test
    void secondsThatAreNotExactlyAWholeNumberInRangeAreInvalidParams(Env env) {
        final ManagementMethods methods = methods(env);
        final Logger logger = (Logger) LoggerFactory.getLogger(ManagementMethods.class);
        final ListAppender<ILoggingEvent> lines = new ListAppender<>();
        lines.start();
        logger.addAppender(lines);
        // First the one a double reads as 1.0 and getAsInt cuts to 0
        final List<String> wrong = List.of("0.99999999999999999999", "600.00000000000000000001", "1.5", "0", "601", "-1",
                "4294967297", "1e999999999", "1e-999999999", "\"60\"", "true", "[60]", "{}");
        try {
            int id = 0;
            for (String seconds : wrong) {
                final JsonObject answer = profile(methods, ++id, seconds);
                assertTrue(answer.has("error"), seconds + " -> " + answer);
                final JsonObject error = answer.getAsJsonObject("error");
                assertEquals(-32602, error.get("code").getAsInt(), seconds + " -> " + answer);
                assertEquals("seconds is a whole number from 1 to 600", error.get("message").getAsString());
            }
        } finally {
            logger.detachAppender(lines);
            lines.stop();
        }
        assertEquals(List.of(), lines.list.stream().filter(line -> line.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList(), "no call failed unforeseen");
        assertEquals(wrong.size(), events.size(), events.toString());
        for (Map<String, ?> event : events) assertEquals("invalid", event.get("result"), event.toString());
        assertFalse(Files.exists(workingDirectory.resolve("profiles")), "no recording started");
        // The one other number a request carries, its id, goes back as written
        assertTrue(String.valueOf(methods.handle("{\"jsonrpc\":\"2.0\",\"id\":0.99999999999999999999,\"method\":\"synaxis:none\"}")
                .text()).startsWith("{\"jsonrpc\":\"2.0\",\"id\":0.99999999999999999999,\"error\":{\"code\":-32601,"));
    }

    @Test
    void aWholeNumberWrittenWithAFractionOrAnExponentIsThatNumber(Env env) {
        final ManagementMethods methods = methods(env);
        int id = 0;
        for (String seconds : List.of("1.0", "0.1e1")) {
            final JsonObject answer = profile(methods, ++id, seconds);
            assertTrue(answer.has("result"), seconds + " -> " + answer);
            assertEquals(1, answer.getAsJsonObject("result").get("seconds").getAsInt(), answer.toString());
        }
        assertEquals(2, events.stream().filter(event -> "ok".equals(event.get("result"))).count(), events.toString());
    }
}
