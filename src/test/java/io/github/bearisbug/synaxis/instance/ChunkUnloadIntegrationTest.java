package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientSettingsPacket;
import io.github.bearisbug.synaxis.network.player.ClientSettings;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chunk tickets and the automatic unload of an instance ({@code docs/DESIGN-M3.md}
 * {@code REQ-M3-010}, {@code API-M3-006}, §11.1 the unload transitions): a loaded chunk without a
 * ticket for the grace period is unloaded at the end of the tick, the oldest first and at most 256
 * a tick; the view tickets of a player follow its chunk ring.
 */
@EnvTest
public class ChunkUnloadIntegrationTest {

    private static final Pos SPAWN = new Pos(0.5, 42, 0.5);
    /** 40 chunks away along x. */
    private static final Pos AWAY = new Pos(40 * 16 + 8.5, 42, 0.5);

    private static void ticks(Env env, int count) {
        for (int i = 0; i < count; i++) env.tick();
    }

    /**
     * Teleports on the test thread. The target chunk is loaded first: a teleport into an unloaded chunk
     * finishes on the loading thread, which waits for the player the test thread is holding.
     */
    private static void teleport(Player player, Pos to) {
        player.getInstance().loadChunk(to).join();
        player.acquirable().sync(owned -> owned.teleport(to).join());
    }

    /** The §19 scenario: the player leaves chunk A, whose last ticket goes on that tick. */
    @Test
    public void aChunkNobodyLooksAtIsUnloadedOnTheHundredthTick(Env env) {
        final Instance instance = env.createFlatInstance();
        instance.setChunkUnloadGraceTicks(100);
        final Player player = env.createPlayer(instance, SPAWN);
        env.tick();
        assertEquals(1, instance.chunkTicketCount(0, 0));

        teleport(player, AWAY);
        assertEquals(0, instance.chunkTicketCount(0, 0), "tick 0: the ring left A");
        ticks(env, 99);
        assertNotNull(instance.getChunk(0, 0), "tick 99: A is still loaded");
        env.tick();
        assertNull(instance.getChunk(0, 0), "tick 100: A is unloaded");
        assertNotNull(instance.getChunk(40, 0), "the chunk of the player stays");
    }

    @Test
    public void withoutAGracePeriodNothingIsUnloaded(Env env) {
        final Instance instance = env.createFlatInstance();
        assertEquals(-1, instance.chunkUnloadGraceTicks());
        final Player player = env.createPlayer(instance, SPAWN);
        teleport(player, AWAY);

        ticks(env, 1_000);

        assertNotNull(instance.getChunk(0, 0));
    }

    @Test
    public void aGracePeriodBelowOneOtherThanNeverIsRefused(Env env) {
        final Instance instance = env.createFlatInstance();
        instance.setChunkUnloadGraceTicks(100);
        assertThrows(IllegalArgumentException.class, () -> instance.setChunkUnloadGraceTicks(0));
        assertThrows(IllegalArgumentException.class, () -> instance.setChunkUnloadGraceTicks(-2));
        assertEquals(100, instance.chunkUnloadGraceTicks());
        instance.setChunkUnloadGraceTicks(-1);
        assertEquals(-1, instance.chunkUnloadGraceTicks());
    }

    /** The §19 scenario: a plugin ticket taken before the player leaves. */
    @Test
    public void aPluginTicketKeepsTheChunk(Env env) {
        final Instance instance = env.createFlatInstance();
        instance.setChunkUnloadGraceTicks(100);
        final Player player = env.createPlayer(instance, SPAWN);
        final Object owner = new Object();
        assertTrue(instance.addChunkTicket(0, 0, owner));
        assertFalse(instance.addChunkTicket(0, 0, owner), "one ticket per chunk and owner");
        assertEquals(2, instance.chunkTicketCount(0, 0), "the view ticket and the plugin ticket");

        teleport(player, AWAY);
        ticks(env, 300);
        assertNotNull(instance.getChunk(0, 0));
        assertEquals(1, instance.chunkTicketCount(0, 0));

        assertTrue(instance.removeChunkTicket(0, 0, owner));
        assertFalse(instance.removeChunkTicket(0, 0, owner));
        ticks(env, 99);
        assertNotNull(instance.getChunk(0, 0));
        env.tick();
        assertNull(instance.getChunk(0, 0), "the 100th tick after the ticket went");
    }

    @Test
    public void aTicketHeldWhileTheChunkIsNotLoadedProtectsItOnceLoaded(Env env) {
        final Instance instance = env.createFlatInstance();
        instance.setChunkUnloadGraceTicks(1);
        final Object owner = "owner";
        assertTrue(instance.addChunkTicket(5, 5, owner));
        assertNull(instance.getChunk(5, 5), "a ticket does not load");
        assertEquals(1, instance.chunkTicketCount(5, 5));

        instance.loadChunk(5, 5).join();
        ticks(env, 10);
        assertNotNull(instance.getChunk(5, 5));

        instance.removeChunkTicket(5, 5, owner);
        ticks(env, 2);
        assertNull(instance.getChunk(5, 5));
    }

    /** Idle from the tick it loaded when it loads without a ticket. */
    @Test
    public void aChunkLoadedWithoutATicketIsIdleFromItsLoad(Env env) {
        final Instance instance = env.createFlatInstance();
        instance.setChunkUnloadGraceTicks(5);
        instance.loadChunk(30, 30).join();
        ticks(env, 4);
        assertNotNull(instance.getChunk(30, 30));
        env.tick();
        assertNull(instance.getChunk(30, 30));
    }

    @Test
    public void anExplicitUnloadIgnoresTheTickets(Env env) {
        final Instance instance = env.createFlatInstance();
        final Chunk chunk = instance.loadChunk(30, 30).join();
        instance.addChunkTicket(30, 30, "owner");
        instance.unloadChunk(chunk);
        assertNull(instance.getChunk(30, 30));
        assertEquals(1, instance.chunkTicketCount(30, 30), "the ticket stays on the coordinates");
    }

    /** The §19 scenario: a respawn, a cap that shrinks the ring, a disconnect. */
    @Test
    public void theViewTicketsFollowTheChunkRingNotTheCallsThatSendIt(Env env) {
        final Instance instance = env.createFlatInstance();
        instance.viewDistance(12);
        final Player player = env.createPlayer(instance, SPAWN);
        player.addPacketToQueue(new ClientSettingsPacket(ClientSettings.defaultSettings(12)));
        env.tick();
        assertEquals(13, player.effectiveViewDistance());
        assertEquals(1, instance.chunkTicketCount(0, 13), "the ring reaches 13 chunks");
        assertEquals(0, instance.chunkTicketCount(0, 14));

        player.acquirable().sync(owned -> {
            owned.kill();
            owned.respawn();
        });
        env.tick();
        assertEquals(1, instance.chunkTicketCount(0, 13), "a respawn on the spot sends the ring again, one ticket");
        assertEquals(1, instance.chunkTicketCount(0, 0));

        player.setMaxViewDistance(4);
        env.tick();
        assertEquals(0, instance.chunkTicketCount(0, 13));
        assertEquals(1, instance.chunkTicketCount(0, 5), "the capped ring reaches 5 chunks");
        assertEquals(0, instance.chunkTicketCount(0, 6));

        player.acquirable().sync(Player::remove);
        for (int x = -13; x <= 13; x++) {
            for (int z = -13; z <= 13; z++) assertEquals(0, instance.chunkTicketCount(x, z), x + "," + z);
        }
    }

    @Test
    public void aPlayerChangingInstanceTakesItsTicketsAlong(Env env) {
        final Instance first = env.createFlatInstance();
        final Instance second = env.createFlatInstance();
        final Player player = env.createPlayer(first, SPAWN);
        assertEquals(1, first.chunkTicketCount(0, 0));

        player.acquirable().sync(owned -> owned.setInstance(second, SPAWN).join());

        assertEquals(0, first.chunkTicketCount(0, 0));
        assertEquals(0, first.chunkTicketCount(9, 9));
        assertEquals(1, second.chunkTicketCount(0, 0));
        assertEquals(1, second.chunkTicketCount(9, 9));
    }

    @Test
    public void twoPlayersHoldTwoTicketsOnTheChunksTheyShare(Env env) {
        final Instance instance = env.createFlatInstance();
        env.createPlayer(instance, SPAWN);
        final Player second = env.createPlayer(instance, new Pos(3 * 16 + 0.5, 42, 0.5));
        assertEquals(2, instance.chunkTicketCount(0, 0));
        assertEquals(1, instance.chunkTicketCount(-9, 0));
        assertEquals(1, instance.chunkTicketCount(12, 0));

        teleport(second, new Pos(4 * 16 + 0.5, 42, 0.5));
        assertEquals(1, instance.chunkTicketCount(13, 0), "the ring moved one column");
        assertEquals(1, instance.chunkTicketCount(-6, 0), "the column left behind has the first player's ticket only");
        assertEquals(2, instance.chunkTicketCount(-5, 0));
    }

    /**
     * The §19 scenario: an online player stands in a chunk without tickets, a state only the test hook
     * of this package makes (a player's chunk is always in its own ring).
     */
    @Test
    public void aChunkWithAnOnlinePlayerIsSkipped(Env env) {
        final Instance instance = env.createFlatInstance();
        instance.setChunkUnloadGraceTicks(1);
        env.createPlayer(instance, SPAWN);
        instance.clearViewTickets(0, 0);
        assertEquals(0, instance.chunkTicketCount(0, 0));

        ticks(env, 10);

        assertNotNull(instance.getChunk(0, 0));
    }

    /** The §19 scenario: 600 idle chunks, 256 unloaded a tick, the longest idle first. */
    @Test
    public void atMost256ChunksAreUnloadedATickTheLongestIdleFirst(Env env) {
        final Instance instance = env.createFlatInstance();
        final List<Chunk> older = new ArrayList<>();
        for (int x = 0; x < 300; x++) older.add(instance.loadChunk(x, 100).join());
        env.tick();
        final List<Chunk> newer = new ArrayList<>();
        for (int x = 0; x < 300; x++) newer.add(instance.loadChunk(x, 101).join());
        env.tick();
        instance.setChunkUnloadGraceTicks(1);

        env.tick();

        final long olderLeft = older.stream().filter(Chunk::isLoaded).count();
        final long newerLeft = newer.stream().filter(Chunk::isLoaded).count();
        assertEquals(600 - 256, olderLeft + newerLeft, "256 unloaded in one tick");
        assertEquals(300, newerLeft, "the longest idle go first");
        for (int i = 0; i < 256; i++) assertFalse(older.get(i).isLoaded(), "in the order they became idle: " + i);

        ticks(env, 2);
        assertEquals(0, older.stream().filter(Chunk::isLoaded).count() + newer.stream().filter(Chunk::isLoaded).count());
    }

    /** {@link Chunk#readyToUnload()} holds a chunk back, one more round at a time. */
    @Test
    public void aChunkNotReadyToUnloadStays(Env env) {
        final Instance instance = env.createFlatInstance();
        final AtomicBoolean ready = new AtomicBoolean(false);
        final AtomicInteger asked = new AtomicInteger();
        instance.setChunkSupplier((owner, chunkX, chunkZ) -> new DynamicChunk(owner, chunkX, chunkZ) {
            @Override
            protected boolean readyToUnload() {
                asked.incrementAndGet();
                return ready.get();
            }
        });
        instance.loadChunk(7, 7).join();
        instance.setChunkUnloadGraceTicks(1);

        ticks(env, 5);
        assertNotNull(instance.getChunk(7, 7));
        assertTrue(asked.get() >= 4, "asked on every round it was a candidate: " + asked.get());

        ready.set(true);
        env.tick();
        assertNull(instance.getChunk(7, 7));
    }
}
