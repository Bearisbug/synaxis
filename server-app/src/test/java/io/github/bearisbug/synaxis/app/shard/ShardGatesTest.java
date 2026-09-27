package io.github.bearisbug.synaxis.app.shard;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerMoveEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.packet.server.play.SystemChatPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.TestConnection;
import net.kyori.adventure.text.TranslatableComponent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The gates of a region ({@code docs/DESIGN-M3.md} {@code API-M3-010}, {@code REQ-M3-017}): a walk
 * into a frame sends the player to the gate's target region once, on the block the new position is
 * in; nothing more while that transfer is under way; and only a choice that failed is told to the
 * player.
 */
class ShardGatesTest {

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
            asked.add(player.getUsername() + " " + regionId);
            final CompletableFuture<ShardTransferResult> transfer = new CompletableFuture<>();
            transfers.add(transfer);
            return transfer;
        }

        @Override
        public CompletableFuture<ShardTransferResult> transferTo(Player player, String shardId) {
            throw new AssertionError("a gate sends a player to a region");
        }
    }

    private record Walker(Player player, Collector<SystemChatPacket> chat) {
        /** The translate keys of the system messages the player got. */
        List<String> messages() {
            return chat.collect().stream()
                    .map(packet -> packet.message() instanceof TranslatableComponent key ? key.key() : packet.message().toString())
                    .toList();
        }
    }

    private final Service service = new Service();
    private Env env;
    private Instance instance;

    @BeforeEach
    void start() {
        env = Env.create(UnaryOperator.identity());
        instance = env.createFlatInstance();
    }

    @AfterEach
    void stop() {
        env.process().stop();
    }

    private void attach(ShardDirectory directory) {
        new ShardGates(directory, service).attach(instance);
    }

    private Walker walker(String name) {
        return walker(name, instance);
    }

    private Walker walker(String name, Instance in) {
        final TestConnection connection = env.createConnection(new GameProfile(UUID.randomUUID(), name));
        final Collector<SystemChatPacket> chat = connection.trackIncoming(SystemChatPacket.class);
        return new Walker(connection.connect(in, new Pos(2024.5, 41, 1024.5)), chat);
    }

    /** What the engine calls once it took a position packet of the player, before it moves it. */
    private void walk(Walker walker, double x, double z) {
        env.process().eventHandler().call(new PlayerMoveEvent(walker.player(), new Pos(x, 41, z), true));
    }

    private void walk(Walker walker, double x) {
        walk(walker, x, 1024.5);
    }

    @Test
    void aGateTriggersOnceForEveryWalkIntoItsFrame() {
        attach(ExampleDirectory.as("r0-c1"));
        final Walker walker = walker("Walker");
        walk(walker, 2030);
        assertEquals(List.of(), service.asked);
        walk(walker, 2040);
        assertEquals(List.of("Walker r1"), service.asked, "walking into r0-east");
        service.transfers.getFirst().complete(ShardTransferResult.COOLDOWN);
        walk(walker, 2045);
        assertEquals(1, service.asked.size(), "moving inside the frame");
        walk(walker, 2030);
        walk(walker, 2046);
        assertEquals(List.of("Walker r1", "Walker r1"), service.asked, "out and in again");
    }

    @Test
    void aWalkInWhileATransferIsUnderWayDoesNotTrigger() {
        attach(ExampleDirectory.as("r0-c1"));
        final Walker walker = walker("Walker");
        walk(walker, 2040);
        walk(walker, 2030);
        walk(walker, 2040);
        assertEquals(List.of("Walker r1"), service.asked, "the first transfer is still under way");

        service.transfers.getFirst().complete(ShardTransferResult.FULL);
        walk(walker, 2041);
        assertEquals(1, service.asked.size(), "still inside the frame it walked into while waiting");
        walk(walker, 2030);
        walk(walker, 2040);
        assertEquals(2, service.asked.size(), "out and in once the transfer is over");
    }

    @Test
    void theFrameIsJudgedOnTheBlockOfTheNewPosition() {
        attach(ExampleDirectory.as("r0-c1"));
        final Walker walker = walker("Walker");
        walk(walker, 2031.99);
        assertEquals(List.of(), service.asked, "block 2031 is outside the frame");
        walk(walker, 2032.0);
        assertEquals(List.of("Walker r1"), service.asked, "block 2032 is its first column");
    }

    @Test
    void aWalkFromOneFrameStraightIntoAnotherTriggersTheOther() {
        final ShardDirectory.Gate south = new ShardDirectory.Gate("r0-south",
                new ShardDirectory.Frame(2032, 0, 1032, 2047, 255, 1047), "r2", new Pos(2024.5, 200.0, 1040.5));
        final ShardDirectory.Region r0 = new ShardDirectory.Region("r0", ExampleDirectory.R0.bounds(),
                List.of(ExampleDirectory.R0_EAST, south));
        final ShardDirectory.Region r2 = new ShardDirectory.Region("r2", new SynxManifest.Bounds(0, 128, 127, 255), List.of());
        attach(new ShardDirectory("synth", List.of(r0, ExampleDirectory.R1, r2), ExampleDirectory.CHANNELS, "r0-c1"));
        final Walker walker = walker("Walker");
        walk(walker, 2040, 1024.5);
        service.transfers.getFirst().complete(ShardTransferResult.COOLDOWN);
        walk(walker, 2040, 1040.5);
        assertEquals(List.of("Walker r1", "Walker r2"), service.asked);
    }

    @Test
    void onlyAChoiceThatFailedIsToldToThePlayer() {
        attach(ExampleDirectory.as("r0-c1"));
        for (ShardTransferResult result : ShardTransferResult.values()) {
            final Walker walker = walker("Walker" + result.ordinal());
            walk(walker, 2040);
            service.transfers.getLast().complete(result);
            final List<String> told = switch (result) {
                case FULL, OFFLINE, DIRECTORY_UNAVAILABLE -> List.of("synaxis:transfer-failed");
                // SAVE_FAILED: the source side of the transfer already told the player
                case SENT, COOLDOWN, NOT_ONLINE, SAVE_FAILED, UNKNOWN_TARGET, SAME_TARGET -> List.of();
            };
            assertEquals(told, walker.messages(), result.name());
        }
    }

    @Test
    void aResultTheDirectoryRulesOutAndATransferThatThrewAreErrors() {
        final ListAppender<ILoggingEvent> log = new ListAppender<>();
        log.start();
        final Logger logger = (Logger) LoggerFactory.getLogger(ShardGates.class);
        logger.addAppender(log);
        try {
            // Gates of their own on an instance of their own for each: a budget writes one ERROR per 10 s
            for (ShardTransferResult result : List.of(ShardTransferResult.UNKNOWN_TARGET, ShardTransferResult.SAME_TARGET)) {
                final Instance own = env.createFlatInstance();
                new ShardGates(ExampleDirectory.as("r0-c1"), service).attach(own);
                walk(walker("Walker" + result.ordinal(), own), 2040);
                service.transfers.getLast().complete(result);
            }
            final Instance own = env.createFlatInstance();
            new ShardGates(ExampleDirectory.as("r0-c1"), service).attach(own);
            final Walker walker = walker("Thrown", own);
            walk(walker, 2040);
            // As ShardServiceImpl passes it on: the failure of TransferTickets, wrapped by thenCompose
            service.transfers.getLast().completeExceptionally(
                    new CompletionException(new IllegalStateException("the ticket does not encode")));
            assertEquals(List.of(), walker.messages());

            assertEquals(List.of("java.lang.IllegalStateException: the shard service answers UNKNOWN_TARGET",
                    "java.lang.IllegalStateException: the shard service answers SAME_TARGET",
                    "java.lang.IllegalStateException: the ticket does not encode"), log.list.stream()
                    .filter(event -> event.getLevel() == Level.ERROR)
                    .map(event -> event.getThrowableProxy().getClassName() + ": " + event.getThrowableProxy().getMessage())
                    .toList());
            walk(walker, 2030);
            walk(walker, 2040);
            assertEquals(4, service.asked.size(), "the transfer that threw is over");
        } finally {
            logger.detachAppender(log);
        }
    }
}
