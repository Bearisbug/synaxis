package io.github.bearisbug.synaxis.loadtest;

import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerPacketEvent;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerPositionPacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bot whose moving thread stalled, against a real ticking server on loopback
 * ({@code docs/DESIGN-PROD.md} {@code API-PROD-025}, {@code API-PROD-030}): its profile went on
 * without it, and the server turns back a position more than 64 blocks from where it has the player.
 * The bot walks to where its profile got to at most 32 blocks a position packet, so that the server
 * takes every step.
 */
public class BotStallTest {

    /**
     * The stall is the clock of the bot's moves jumping 30 s ahead between two ticks: a walk bot is
     * then 129.5 blocks further along its circle. Every position the server judges after it (not
     * those it drops while a teleport waits for its confirmation) is at most 32 blocks from where the
     * server has the player, and 3 s on the server has the player more than 64 blocks from where the
     * stall left it. The server then teleports the player back there: the first position it judges
     * after that is at most 32 blocks from it, the bot walking on from where the teleport put it.
     */
    @Test
    @Timeout(60)
    public void afterAStallEveryPositionIsAtMost32BlocksFromWhereTheServerHasThePlayer() throws Exception {
        final AtomicLong stall = new AtomicLong();
        final List<Double> afterTheStall = new CopyOnWriteArrayList<>();
        final AtomicReference<Pos> held = new AtomicReference<>();
        final AtomicReference<Player> joined = new AtomicReference<>();
        // The last teleport id before the server takes the player back, -1 until then
        final AtomicInteger pullBack = new AtomicInteger(-1);
        final AtomicReference<Point> afterThePullBack = new AtomicReference<>();
        final ServerConfig jvm = ServerConfig.fromSystemProperties();
        try (BotSocketTest.Server server = new BotSocketTest.Server("server", ServerConfig.builder()
                .dispatcherThreads(jvm.dispatcherThreads()).threadProvider(jvm.threadProvider())
                .connectionGateTrustedNetworks("127.0.0.0/8").chunkViewDistance(4).build())) {
            server.process.eventHandler().addListener(PlayerPacketEvent.class, event -> {
                final Player player = event.getPlayer();
                if (!(event.getPacket() instanceof ClientPlayerPositionPacket packet)
                        || player.getLastSentTeleportId() != player.getLastReceivedTeleportId()) return;
                joined.set(player);
                final int before = pullBack.get();
                if (before >= 0 && player.getLastReceivedTeleportId() > before) {
                    afterThePullBack.compareAndSet(null, packet.position());
                    return;
                }
                if (stall.get() != 0) afterTheStall.add(packet.position().distance(player.getPosition()));
                held.set(player.getPosition());
            });
            final Bot bot = new Bot(0, new Bot.Settings("127.0.0.1", server.port(), Profile.WALK, false, 9f, false, 0,
                    30_000, List.of(), 0, Bot.NO_TRANSFER_NANOS), new BotStats(), () -> System.nanoTime() + stall.get());
            final Thread thread = Thread.ofVirtual().start(() -> bot.run(System.nanoTime() + TimeUnit.SECONDS.toNanos(30)));
            final long spawnBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!bot.inPlay() && System.nanoTime() < spawnBy) Thread.sleep(50);
            assertTrue(bot.inPlay(), "the bot was spawned");
            Thread.sleep(1_000);
            final Pos stalled = held.get();
            stall.set(TimeUnit.SECONDS.toNanos(30));
            Thread.sleep(3_000);
            final Pos reached = held.get();
            final Player player = joined.get();
            player.scheduleNextTick(_ -> {
                pullBack.set(player.getLastSentTeleportId());
                var _ = player.teleport(stalled);
            });
            Thread.sleep(1_000);
            bot.close();
            thread.join(10_000);

            assertNotNull(stalled, "the server judged positions before the stall");
            assertFalse(afterTheStall.isEmpty(), "the server judged positions after the stall");
            final double farthest = afterTheStall.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
            assertTrue(farthest <= 32 + 1e-9, "a position the server judged after the stall was " + farthest
                    + " blocks from where it had the player");
            assertTrue(reached.distance(stalled) > 64, "3 s after the stall the server has the player "
                    + reached.distance(stalled) + " blocks from where the stall left it");
            final Point first = afterThePullBack.get();
            assertNotNull(first, "the server judged a position after it took the player back");
            assertTrue(first.distance(stalled) <= 32 + 1e-9, "the first position the server judged after it took the "
                    + "player back was " + first.distance(stalled) + " blocks from where it put the player");
        }
    }
}
