package io.github.bearisbug.synaxis.app.command;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.app.shard.ShardService;
import io.github.bearisbug.synaxis.app.shard.ShardTransferResult;
import io.github.bearisbug.synaxis.command.ConsoleSender;
import io.github.bearisbug.synaxis.command.builder.CommandResult;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.packet.server.play.SystemChatPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.plugin.Permissions;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The GM command {@code transfer} ({@code docs/DESIGN-PROD.md} {@code API-PROD-011}, §10 node
 * {@code gm.transfer}, §19 the {@code gm.transfer} scenarios): it hands a player online here to the
 * shard service by region or by shard, answers the result once it is known, and writes one
 * {@code gm_command} event per run.
 */
@EnvTest
class TransferCommandIntegrationTest {

    /** A shard service recording whom it was asked to send where, each transfer completed by the test. */
    private static final class Service implements ShardService {
        final List<String> asked = new CopyOnWriteArrayList<>();
        final List<CompletableFuture<ShardTransferResult>> transfers = new CopyOnWriteArrayList<>();

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
            return asked("transfer " + player.getUsername() + " " + regionId);
        }

        @Override
        public CompletableFuture<ShardTransferResult> transferTo(Player player, String shardId) {
            return asked("transferTo " + player.getUsername() + " " + shardId);
        }

        private CompletableFuture<ShardTransferResult> asked(String call) {
            asked.add(call);
            final CompletableFuture<ShardTransferResult> transfer = new CompletableFuture<>();
            transfers.add(transfer);
            return transfer;
        }
    }

    /** The console, recording every answer as its translate key followed by its arguments. */
    private static final class RecordingConsole extends ConsoleSender {
        final List<String> answers = new CopyOnWriteArrayList<>();

        RecordingConsole(ServerProcess process) {
            super(process);
        }

        @Override
        public void sendMessage(Component message) {
            answers.add(text(message));
        }
    }

    private static String text(Component message) {
        final TranslatableComponent translatable = assertInstanceOf(TranslatableComponent.class, message);
        final String arguments = translatable.arguments().stream()
                .map(argument -> ((TextComponent) argument.asComponent()).content())
                .collect(Collectors.joining("|"));
        return arguments.isEmpty() ? translatable.key() : translatable.key() + " " + arguments;
    }

    /** Players holding {@code gm.transfer}, by name. */
    private static Permissions holding(Set<String> names) {
        return new Permissions() {
            @Override
            public boolean has(Player player, String node) {
                return TransferCommand.NODE.equals(node) && names.contains(player.getUsername());
            }

            @Override
            public Set<String> roles(Player player) {
                return Set.of();
            }
        };
    }

    private final List<String> events = new CopyOnWriteArrayList<>();

    private void register(Env env, Permissions permissions, @Nullable ShardService service) {
        env.process().command().register(new TransferCommand(env.process(), permissions, service,
                (event, attributes) -> events.add(event + " " + attributes)));
    }

    private static String event(@Nullable UUID actor, String command, @Nullable UUID target, String result) {
        final Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("actor", actor == null ? "console" : "player");
        attributes.put("actor_uuid", actor);
        attributes.put("command", command);
        attributes.put("target", target);
        attributes.put("result", result);
        return "gm_command " + attributes;
    }

    private static Player join(Env env, Instance instance, String name) {
        return env.createConnection(new GameProfile(UUID.randomUUID(), name)).connect(instance, new Pos(0, 41, 0));
    }

    @Test
    void theConsoleSendsAPlayerByNameOrUuidAndIsAnsweredOnceTheTransferIsKnown(Env env) {
        final Service service = new Service();
        register(env, holding(Set.of()), service);
        final Player walker = join(env, env.createFlatInstance(), "Walker");
        final RecordingConsole console = new RecordingConsole(env.process());

        env.process().command().execute(console, "transfer shard Walker r0-c2");
        assertEquals(List.of("transferTo Walker r0-c2"), service.asked);
        assertEquals(List.of(), console.answers, "no answer before the transfer is known");
        assertEquals(List.of(), events);
        service.transfers.getFirst().complete(ShardTransferResult.SENT);
        assertEquals(List.of("synaxis:command-transfer-result Walker|r0-c2|sent"), console.answers);
        assertEquals(List.of(event(null, "transfer shard Walker r0-c2", walker.getUuid(), "sent")), events);

        env.process().command().execute(console, "transfer region " + walker.getUuid() + " r1");
        assertEquals("transfer Walker r1", service.asked.getLast(), "a uuid names the same player");
        service.transfers.getLast().complete(ShardTransferResult.FULL);
        assertEquals("synaxis:command-transfer-result Walker|r1|full", console.answers.getLast());
        assertEquals(event(null, "transfer region " + walker.getUuid() + " r1", walker.getUuid(), "full"), events.getLast());
    }

    @Test
    void everyResultIsAnsweredAndRecordedByItsLowerCaseName(Env env) {
        final Service service = new Service();
        register(env, holding(Set.of()), service);
        final Player walker = join(env, env.createFlatInstance(), "Walker");
        final RecordingConsole console = new RecordingConsole(env.process());
        for (ShardTransferResult result : ShardTransferResult.values()) {
            env.process().command().execute(console, "transfer region Walker r1");
            service.transfers.getLast().complete(result);
            final String name = result.name().toLowerCase(Locale.ROOT);
            assertEquals("synaxis:command-transfer-result Walker|r1|" + name, console.answers.getLast(), result.name());
            assertEquals(event(null, "transfer region Walker r1", walker.getUuid(), name), events.getLast(), result.name());
        }
        assertEquals(ShardTransferResult.values().length, events.size());
    }

    @Test
    void onlyAPlayerHoldingTheNodeRunsIt(Env env) {
        final Service service = new Service();
        register(env, holding(Set.of("Gamemaster")), service);
        final Instance instance = env.createFlatInstance();
        final TestConnection gmConnection = env.createConnection(new GameProfile(UUID.randomUUID(), "Gamemaster"));
        final Collector<SystemChatPacket> told = gmConnection.trackIncoming(SystemChatPacket.class);
        final Player gm = gmConnection.connect(instance, new Pos(0, 41, 0));
        final Player nobody = join(env, instance, "Nobody");
        final Player walker = join(env, instance, "Walker");

        assertEquals(CommandResult.Type.CANCELLED,
                env.process().command().execute(nobody, "transfer shard Walker r0-c2").getType());
        assertEquals(List.of(), service.asked, "a player without the node sends nobody");
        assertEquals(List.of(), events, "and writes no event: the engine refused the command");

        assertEquals(CommandResult.Type.SUCCESS,
                env.process().command().execute(gm, "transfer shard Walker r0-c2").getType());
        service.transfers.getFirst().complete(ShardTransferResult.COOLDOWN);
        assertEquals(List.of("transferTo Walker r0-c2"), service.asked);
        assertEquals(List.of("synaxis:command-transfer-result Walker|r0-c2|cooldown"),
                told.collect().stream().map(packet -> text(packet.message())).toList());
        assertEquals(List.of(event(gm.getUuid(), "transfer shard Walker r0-c2", walker.getUuid(), "cooldown")), events);
    }

    @Test
    void withoutAShardServiceThisShardCannotTransfer(Env env) {
        register(env, holding(Set.of()), null);
        join(env, env.createFlatInstance(), "Walker");
        final RecordingConsole console = new RecordingConsole(env.process());
        env.process().command().execute(console, "transfer shard Walker r0-c2");
        assertEquals(List.of("synaxis:command-transfer-unavailable"), console.answers);
        assertEquals(List.of(event(null, "transfer shard Walker r0-c2", null, "unavailable")), events);
    }

    @Test
    void aPlayerNotOnlineHereIsNoTarget(Env env) {
        final Service service = new Service();
        register(env, holding(Set.of()), service);
        final RecordingConsole console = new RecordingConsole(env.process());
        final UUID elsewhere = UUID.randomUUID();
        env.process().command().execute(console, "transfer region Nobody r1");
        env.process().command().execute(console, "transfer shard " + elsewhere + " r0-c2");
        assertEquals(List.of("synaxis:command-transfer-no-target Nobody",
                "synaxis:command-transfer-no-target " + elsewhere), console.answers);
        assertEquals(List.of(), service.asked, "nobody to send");
        assertEquals(List.of(event(null, "transfer region Nobody r1", null, "no_target"),
                event(null, "transfer shard " + elsewhere + " r0-c2", null, "no_target")), events);
    }

    @Test
    void aTransferThatThrewIsAnErrorWithItsOwnException(Env env) {
        final Service service = new Service();
        register(env, holding(Set.of()), service);
        final Player walker = join(env, env.createFlatInstance(), "Walker");
        final RecordingConsole console = new RecordingConsole(env.process());
        final ListAppender<ILoggingEvent> log = new ListAppender<>();
        log.start();
        final Logger logger = (Logger) LoggerFactory.getLogger(TransferCommand.class);
        logger.addAppender(log);
        try {
            env.process().command().execute(console, "transfer shard Walker r0-c2");
            // As the stages of the shard service hand on what TransferTickets threw
            service.transfers.getFirst().completeExceptionally(
                    new CompletionException(new IllegalStateException("the ticket could not be encoded")));
        } finally {
            logger.detachAppender(log);
        }
        final List<ILoggingEvent> errors = log.list.stream().filter(line -> line.getLevel() == Level.ERROR).toList();
        assertEquals(1, errors.size(), log.list.toString());
        final ThrowableProxy thrown = (ThrowableProxy) errors.getFirst().getThrowableProxy();
        assertInstanceOf(IllegalStateException.class, thrown.getThrowable(), "the exception of the transfer, unwrapped");
        assertEquals(List.of("synaxis:command-transfer-error Walker"), console.answers);
        assertEquals(List.of(event(null, "transfer shard Walker r0-c2", walker.getUuid(), "unavailable")), events);
    }
}
