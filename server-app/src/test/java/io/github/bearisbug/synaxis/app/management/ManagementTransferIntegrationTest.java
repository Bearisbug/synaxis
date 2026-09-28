package io.github.bearisbug.synaxis.app.management;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.bearisbug.synaxis.app.command.HostCommands;
import io.github.bearisbug.synaxis.app.command.Profiler;
import io.github.bearisbug.synaxis.app.moderation.HostModeration;
import io.github.bearisbug.synaxis.app.shard.ShardService;
import io.github.bearisbug.synaxis.app.shard.ShardTransferResult;
import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.plugin.Permissions;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The management method {@code synaxis:transfer} ({@code docs/DESIGN-PROD.md} {@code API-PROD-022},
 * §19 the {@code synaxis:transfer} scenarios): a player online here and exactly one of
 * {@code region} and {@code shard}, handed to the shard service, answered with the result in lower
 * case once it is known; {@code -32000} in a process without a shard service.
 */
@EnvTest
class ManagementTransferIntegrationTest {

    @TempDir
    Path workingDirectory;

    private final List<Map<String, ?>> events = new CopyOnWriteArrayList<>();

    /** A shard service recording whom it was asked to send where, and answering what the test set. */
    private static final class Service implements ShardService {
        final List<String> asked = new CopyOnWriteArrayList<>();
        volatile CompletableFuture<ShardTransferResult> answer = CompletableFuture.completedFuture(ShardTransferResult.SENT);

        @Override
        public String shardId() {
            return "r0-c1";
        }

        @Override
        public String regionId() {
            return "r0";
        }

        @Override
        public CompletableFuture<ShardTransferResult> transfer(Player player, String regionId) {
            asked.add("transfer " + player.getUsername() + " " + regionId);
            return answer;
        }

        @Override
        public CompletableFuture<ShardTransferResult> transferTo(Player player, String shardId) {
            asked.add("transferTo " + player.getUsername() + " " + shardId);
            return answer;
        }
    }

    private ManagementMethods methods(Env env, @Nullable ShardService service) {
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
                null, service, "test", (_, attributes) -> events.add(attributes));
    }

    private static JsonObject call(ManagementMethods methods, String params) {
        final String reply = methods.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"synaxis:transfer\",\"params\":"
                + params + "}").text();
        return JsonParser.parseString(String.valueOf(reply)).getAsJsonObject();
    }

    private static Player join(Env env, String name) {
        return env.createConnection(new GameProfile(UUID.randomUUID(), name)).connect(env.createFlatInstance(),
                new Pos(0, 41, 0));
    }

    @Test
    void aPlayerNamedByNameOrIdIsSentByRegionOrByShardWithParamsByNameOrPosition(Env env) {
        final Service service = new Service();
        final ManagementMethods methods = methods(env, service);
        final Player walker = join(env, "Walker");
        final String id = walker.getUuid().toString();

        assertEquals("sent", call(methods, "{\"player\":{\"name\":\"Walker\"},\"shard\":\"r0-c2\"}").get("result")
                .getAsString());
        service.answer = CompletableFuture.completedFuture(ShardTransferResult.FULL);
        assertEquals("full", call(methods, "[{\"id\":\"" + id + "\"},\"r1\"]").get("result").getAsString());
        service.answer = CompletableFuture.completedFuture(ShardTransferResult.OFFLINE);
        assertEquals("offline", call(methods, "[{\"id\":\"" + id + "\"},null,\"r0-c3\"]").get("result").getAsString());
        assertEquals(List.of("transferTo Walker r0-c2", "transfer Walker r1", "transferTo Walker r0-c3"), service.asked);

        assertEquals(List.of("sent", "full", "offline"), events.stream().map(event -> event.get("result")).toList());
        final Map<String, ?> first = events.getFirst();
        assertEquals("management", first.get("actor"));
        assertTrue(first.containsKey("actor_uuid") && first.get("actor_uuid") == null, first.toString());
        assertEquals("synaxis:transfer {\"player\":{\"name\":\"Walker\"},\"shard\":\"r0-c2\"}", first.get("command"));
        for (Map<String, ?> event : events) assertEquals(walker.getUuid(), event.get("target"), event.toString());
    }

    @Test
    void theAnswerWaitsForTheTransferToBeKnown(Env env) throws Exception {
        final Service service = new Service();
        final ManagementMethods methods = methods(env, service);
        join(env, "Walker");
        final CompletableFuture<ShardTransferResult> pending = new CompletableFuture<>();
        service.answer = pending;
        final CompletableFuture<JsonObject> answer = CompletableFuture.supplyAsync(
                () -> call(methods, "{\"player\":{\"name\":\"Walker\"},\"region\":\"r1\"}"));
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (service.asked.isEmpty() && System.nanoTime() - deadline < 0) Thread.sleep(10);
        assertEquals(List.of("transfer Walker r1"), service.asked);
        Thread.sleep(200);
        assertFalse(answer.isDone(), "no answer before the transfer is known");
        pending.complete(ShardTransferResult.COOLDOWN);
        assertEquals("cooldown", answer.get(5, TimeUnit.SECONDS).get("result").getAsString());
    }

    @Test
    void everyResultIsAnsweredAndRecordedByItsLowerCaseName(Env env) {
        final Service service = new Service();
        final ManagementMethods methods = methods(env, service);
        join(env, "Walker");
        for (ShardTransferResult result : ShardTransferResult.values()) {
            service.answer = CompletableFuture.completedFuture(result);
            final String name = result.name().toLowerCase(Locale.ROOT);
            assertEquals(name, call(methods, "{\"player\":{\"name\":\"Walker\"},\"region\":\"r1\"}").get("result")
                    .getAsString());
            assertEquals(name, events.getLast().get("result"));
        }
        assertEquals(ShardTransferResult.values().length, service.asked.size());
    }

    @Test
    void notExactlyOneTargetOrNobodyOnlineHereIsInvalidParams(Env env) {
        final Service service = new Service();
        final ManagementMethods methods = methods(env, service);
        join(env, "Walker");
        final List<String> wrong = List.of(
                "{\"player\":{\"name\":\"Walker\"},\"region\":\"r1\",\"shard\":\"r0-c2\"}",
                "{\"player\":{\"name\":\"Walker\"}}",
                "{\"player\":{\"name\":\"Walker\"},\"region\":1}",
                "{\"region\":\"r1\"}",
                "{\"player\":{},\"region\":\"r1\"}",
                "{\"player\":{\"name\":\"Nobody\"},\"region\":\"r1\"}",
                "{\"player\":{\"id\":\"" + UUID.randomUUID() + "\"},\"shard\":\"r0-c2\"}");
        for (String params : wrong) {
            final JsonObject answer = call(methods, params);
            assertTrue(answer.has("error"), params + " -> " + answer);
            assertEquals(-32602, answer.getAsJsonObject("error").get("code").getAsInt(), params + " -> " + answer);
        }
        assertEquals(List.of(), service.asked, "nobody was sent anywhere");
        assertEquals(wrong.size(), events.size(), events.toString());
        for (Map<String, ?> event : events) assertEquals("invalid", event.get("result"), event.toString());
    }

    @Test
    void aProcessWithoutAShardServiceAnswersUnavailable(Env env) {
        final ManagementMethods methods = methods(env, null);
        join(env, "Walker");
        final JsonObject answer = call(methods, "{\"player\":{\"name\":\"Walker\"},\"shard\":\"r0-c2\"}");
        assertEquals(-32000, answer.getAsJsonObject("error").get("code").getAsInt(), answer.toString());
        assertEquals("This shard cannot send players to other shards: it is not in the directory mode",
                answer.getAsJsonObject("error").get("message").getAsString());
        assertEquals(1, events.size());
        assertEquals("unavailable", events.getFirst().get("result"));
        assertNull(events.getFirst().get("target"));
    }

    @Test
    void aTransferThatThrewIsAnInternalErrorLoggedWithItsOwnException(Env env) {
        final Service service = new Service();
        final ManagementMethods methods = methods(env, service);
        join(env, "Walker");
        // As the stages of the shard service hand on what TransferTickets threw
        service.answer = CompletableFuture.failedFuture(
                new CompletionException(new IllegalStateException("the ticket could not be encoded")));
        final Logger logger = (Logger) LoggerFactory.getLogger(ManagementMethods.class);
        final ListAppender<ILoggingEvent> lines = new ListAppender<>();
        lines.start();
        logger.addAppender(lines);
        final JsonObject answer;
        try {
            answer = call(methods, "{\"player\":{\"name\":\"Walker\"},\"shard\":\"r0-c2\"}");
        } finally {
            logger.detachAppender(lines);
            lines.stop();
        }
        final JsonObject error = answer.getAsJsonObject("error");
        assertEquals(-32603, error.get("code").getAsInt(), answer.toString());
        assertEquals("Internal error", error.get("message").getAsString());
        final String requestId = error.getAsJsonObject("data").get("request_id").getAsString();
        final List<ILoggingEvent> errors = lines.list.stream().filter(line -> line.getLevel() == Level.ERROR).toList();
        assertEquals(1, errors.size(), lines.list.toString());
        assertTrue(errors.getFirst().getFormattedMessage().contains("request_id=" + requestId),
                errors.getFirst().getFormattedMessage());
        final ThrowableProxy thrown = (ThrowableProxy) errors.getFirst().getThrowableProxy();
        assertInstanceOf(IllegalStateException.class, thrown.getThrowable(), "the exception of the transfer, unwrapped");
        assertEquals(List.of(), lines.list.stream().filter(line -> line.getLevel() == Level.WARN).toList(),
                "not the WARN of a call that failed unforeseen");
        assertEquals(List.of("unavailable"), events.stream().map(event -> event.get("result")).toList());
    }
}
