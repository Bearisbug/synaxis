package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.coordinate.ChunkRange;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.WorldBorder;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerPositionPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerRotationPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientTeleportConfirmPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.PlayerPositionAndLookPacket;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the server takes from a client's position packets ({@code docs/DESIGN-PROD.md}
 * {@code API-PROD-030}): a move out across the world border, or of more than 64 blocks in one
 * packet, is turned back where the player is, as a move into a chunk that is not loaded is, and
 * loads no chunk; a player the server put outside the border gets no farther out; the border ends
 * at the wall the vanilla client collides with; the moves the server makes itself are followed.
 * Security review M3, findings S1-V01 and S1-02.
 * <p>
 * The test thread hands the player its packets, and the chunks a move needs are loaded beforehand,
 * so that nothing waits on a chunk load; the player ticks only in the test of the chunks a move
 * loads.
 */
@EnvTest
public class PlayerMovementAuthorityIntegrationTest {

    private static final Pos SPAWN = new Pos(0.5, 40, 0.5);
    /** The view distance of the instances here: a chunk ring of radius 3 around the player's chunk. */
    private static final int VIEW = 2;

    /** A flat instance with the chunk ring of the spawn loaded. */
    private static Instance instance(Env env) {
        final Instance instance = env.createFlatInstance();
        instance.viewDistance(VIEW);
        ChunkRange.chunksInRange(0, 0, VIEW + 1, (x, z) -> instance.loadChunk(x, z).join());
        return instance;
    }

    /** What a client does once it has seen the server move it: confirms the last teleport it was sent. */
    private static void confirm(Player player) {
        player.addPacketToQueue(new ClientTeleportConfirmPacket(player.getLastSentTeleportId()));
        player.interpretPacketQueue();
    }

    private static void move(Player player, double x, double y, double z) {
        player.addPacketToQueue(new ClientPlayerPositionPacket(new Vec(x, y, z), true, false));
        player.interpretPacketQueue();
    }

    private static void look(Player player, float yaw, float pitch) {
        player.addPacketToQueue(new ClientPlayerRotationPacket(yaw, pitch, true, false));
        player.interpretPacketQueue();
    }

    /**
     * Ten ticks, then ticks until every chunk of the player's ring is loaded, 10 s at most: the chunks
     * a move asks for load off the tick threads, so the instance does not hold them right after it.
     */
    private static void settle(Env env, Instance instance, Player player) {
        for (int tick = 0; tick < 10; tick++) env.tick();
        final int radius = player.effectiveViewDistance();
        final boolean settled = env.tickWhile(() -> {
            final Pos at = player.getPosition();
            for (int x = at.chunkX() - radius; x <= at.chunkX() + radius; x++) {
                for (int z = at.chunkZ() - radius; z <= at.chunkZ() + radius; z++) {
                    if (instance.getChunk(x, z) == null) return true;
                }
            }
            return false;
        }, Duration.ofSeconds(10));
        assertTrue(settled, "the chunks of the ring around " + player.getPosition() + " loaded in 10 s");
    }

    /** S1-V01: a move out across the world border is turned back where the player is. */
    @Test
    public void aMoveOutAcrossTheWorldBorderIsTurnedBack(Env env) {
        final Instance instance = instance(env);
        // [-32, 32) on both axes; the chunk ring of the spawn reaches 16 blocks past it
        instance.setWorldBorder(new WorldBorder(64, 0, 0, 0, 0));
        final TestConnection connection = env.createConnection();
        final Player player = connection.connect(instance, SPAWN);
        confirm(player);
        final Collector<PlayerPositionAndLookPacket> turnedBack = connection.trackIncoming(PlayerPositionAndLookPacket.class);

        move(player, 40.5, 40, 0.5);

        assertEquals(SPAWN, player.getPosition(), "a move to x 40.5 across the border at x 32");
        turnedBack.assertSingle(packet -> assertEquals(SPAWN.asVec(), packet.position().asVec()));
    }

    /**
     * S1-V01: the move turned back at the border asks for no chunk. Once the chunks of the player's
     * ring are loaded, the instance holds as many as before it; the same ticks after a move the server
     * takes, into the next chunk inside the border, load the column of chunks its ring reaches past the
     * ring of the spawn.
     */
    @Test
    public void aMoveTurnedBackAtTheWorldBorderLoadsNoChunk(Env env) {
        final Instance instance = instance(env);
        instance.setWorldBorder(new WorldBorder(64, 0, 0, 0, 0));
        final Player player = env.createConnection().connect(instance, SPAWN);
        confirm(player);
        final int chunks = instance.getChunks().size();

        move(player, 40.5, 40, 0.5);
        settle(env, instance, player);
        assertEquals(chunks, instance.getChunks().size(), "the move to x 40.5 across the border asked for no chunk");

        // Ticked, the player has a tick thread: its packets are handed over under the lock of that thread
        player.acquirable().sync(ticked -> {
            confirm(ticked);
            move(ticked, 16.5, 40, 0.5);
        });
        settle(env, instance, player);
        assertEquals(chunks + 7, instance.getChunks().size(), "a move into chunk 1 had the 7 chunks of column 4 loaded");
    }

    /**
     * S1-V01 as reported: one jump after the other along x, each into a chunk the ring has loaded.
     * They stop at the border, and so does the chunk ring that loads chunks around the player.
     */
    @Test
    public void repeatedJumpsStopAtTheBorder(Env env) {
        final Instance instance = instance(env);
        // [-128, 128) on both axes
        instance.setWorldBorder(new WorldBorder(256, 0, 0, 0, 0));
        final Player player = env.createConnection().connect(instance, SPAWN);
        confirm(player);

        for (int jump = 0; jump < 12; jump++) {
            final double x = player.getPosition().x() + 40;
            // What the chunk ring around the player has loaded by the next jump
            instance.loadChunk((int) Math.floor(x) >> 4, 0).join();
            move(player, x, 40, 0.5);
            confirm(player);
        }

        assertEquals(new Pos(120.5, 40, 0.5), player.getPosition(), "the last jump inside the border, at x 120.5");
    }

    /** S1-02: one packet carries the player 64 blocks at most, in any direction. */
    @Test
    public void aMoveOfMoreThanSixtyFourBlocksIsTurnedBack(Env env) {
        final Instance instance = instance(env);
        // Past the ring of the spawn: a move 65 blocks east is turned back for its length, not for its chunk
        instance.loadChunk(4, 0).join();
        final TestConnection connection = env.createConnection();
        final Player player = connection.connect(instance, SPAWN);
        confirm(player);
        final Collector<PlayerPositionAndLookPacket> turnedBack = connection.trackIncoming(PlayerPositionAndLookPacket.class);

        move(player, 65.5, 40, 0.5);
        assertEquals(SPAWN, player.getPosition(), "65 blocks east in one packet");
        confirm(player);
        move(player, 0.5, 105, 0.5);
        assertEquals(SPAWN, player.getPosition(), "65 blocks up in one packet");
        turnedBack.assertCount(2);
        confirm(player);
        move(player, 64.5, 40, 0.5);
        assertEquals(new Pos(64.5, 40, 0.5), player.getPosition(), "64 blocks east in one packet");
    }

    /**
     * A player the server put outside the border, by a teleport or with a border that shrank past it,
     * gets no farther out on the x or the z axis: such a move is turned back, also one that gets nearer
     * on one axis. It turns its head, walks along the border and walks back in.
     */
    @Test
    public void aPlayerOutsideTheBorderGetsNoFartherOut(Env env) {
        final Instance instance = instance(env);
        // [-32, 32) on both axes
        instance.setWorldBorder(new WorldBorder(64, 0, 0, 0, 0));
        final Player player = env.createConnection().connect(instance, SPAWN);
        confirm(player);
        player.teleport(new Pos(42, 40, 0.5)).join();
        confirm(player);

        move(player, 47, 40, 0.5);
        assertEquals(new Pos(42, 40, 0.5), player.getPosition(), "5 blocks farther out, from 10 blocks outside the border");
        confirm(player);
        look(player, 90, 10);
        assertEquals(new Pos(42, 40, 0.5, 90, 10), player.getPosition(), "the head turned where the player stands");
        move(player, 42, 40, 5.5);
        assertEquals(new Pos(42, 40, 5.5, 90, 10), player.getPosition(), "5 blocks along the border");
        move(player, 37, 40, 33);
        assertEquals(new Pos(42, 40, 5.5, 90, 10), player.getPosition(), "5 blocks nearer on x, out across the border on z");
        confirm(player);
        move(player, 37, 40, 5.5);
        assertEquals(new Pos(37, 40, 5.5, 90, 10), player.getPosition(), "5 blocks back towards the border");
        move(player, 31.5, 40, 5.5);
        assertEquals(new Pos(31.5, 40, 5.5, 90, 10), player.getPosition(), "back inside");
        move(player, 32.5, 40, 5.5);
        assertEquals(new Pos(31.5, 40, 5.5, 90, 10), player.getPosition(), "out again across the border");
    }

    /**
     * The border ends at the wall the vanilla client collides with, its edges rounded outwards to whole
     * blocks: with a border 63 blocks across, [-31.5, 31.5) on both axes, the wall stands at x -32 and
     * x 32, and a client against it is not turned back. A move past the wall is.
     */
    @Test
    public void theBorderEndsAtTheWallOfTheVanillaClient(Env env) {
        final Instance instance = instance(env);
        instance.setWorldBorder(new WorldBorder(63, 0, 0, 0, 0));
        final Player player = env.createConnection().connect(instance, new Pos(31, 40, 0.5));
        confirm(player);

        move(player, 31.6, 40, 0.5);
        assertEquals(new Pos(31.6, 40, 0.5), player.getPosition(), "x 31.6, past the border at 31.5, short of the wall at 32");
        move(player, 32.1, 40, 0.5);
        assertEquals(new Pos(31.6, 40, 0.5), player.getPosition(), "x 32.1, past the wall at 32");

        player.teleport(new Pos(-31, 40, 0.5)).join();
        confirm(player);
        move(player, -31.6, 40, 0.5);
        assertEquals(new Pos(-31.6, 40, 0.5), player.getPosition(), "x -31.6, past the border at -31.5, short of the wall at -32");
        move(player, -32.1, 40, 0.5);
        assertEquals(new Pos(-31.6, 40, 0.5), player.getPosition(), "x -32.1, past the wall at -32");
    }

    /**
     * The server's own moves are not judged: after a spawn, a teleport, a respawn and an instance
     * change, each hundreds of blocks from where the player was, the client's next packet is taken.
     * The packets a client sent before it saw a teleport are dropped, as they were.
     */
    @Test
    public void theServersOwnMovesAreFollowed(Env env) {
        final Instance instance = instance(env);
        final Pos spawn = new Pos(300.5, 40, 300.5);
        final Player player = env.createConnection().connect(instance, spawn);
        confirm(player);
        move(player, 301.5, 40, 300.5);
        assertEquals(new Pos(301.5, 40, 300.5), player.getPosition(), "the first move after a spawn 424 blocks from the origin");

        final Pos teleported = new Pos(-200.5, 40, 300.5);
        player.teleport(teleported).join();
        move(player, 302.5, 40, 300.5);
        assertEquals(teleported, player.getPosition(), "a packet the client sent before it saw the teleport");
        confirm(player);
        move(player, -201.5, 40, 300.5);
        assertEquals(new Pos(-201.5, 40, 300.5), player.getPosition(), "the first move after a teleport of 502 blocks");

        final Pos respawn = new Pos(-200.5, 40, -400.5);
        instance.loadChunk(respawn.chunkX(), respawn.chunkZ()).join();
        player.setRespawnPoint(respawn);
        player.kill();
        player.respawn();
        assertEquals(respawn, player.getPosition());
        confirm(player);
        move(player, -201.5, 40, -400.5);
        assertEquals(new Pos(-201.5, 40, -400.5), player.getPosition(), "the first move after a respawn 701 blocks away");

        final Instance other = instance(env);
        final Pos elsewhere = new Pos(1000.5, 40, 0.5);
        player.setInstance(other, elsewhere).join();
        assertSame(other, player.getInstance());
        confirm(player);
        move(player, 1001.5, 40, 0.5);
        assertEquals(new Pos(1001.5, 40, 0.5), player.getPosition(), "the first move in another instance, 1,000 blocks away");
    }
}
