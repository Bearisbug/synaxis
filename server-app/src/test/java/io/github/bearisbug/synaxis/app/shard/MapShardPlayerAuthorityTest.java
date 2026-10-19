package io.github.bearisbug.synaxis.app.shard;

import io.github.bearisbug.synaxis.ShutdownParticipant;
import io.github.bearisbug.synaxis.app.ShutdownSequence;
import io.github.bearisbug.synaxis.coordinate.BlockFace;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.GameMode;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerActionPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerPositionPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientTeleportConfirmPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.AcknowledgeBlockChangePacket;
import io.github.bearisbug.synaxis.synx.OverlayOptions;
import io.github.bearisbug.synaxis.synx.SynxChunk;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import io.github.bearisbug.synaxis.synx.SynxOptions;
import io.github.bearisbug.synaxis.synx.SynxSettings;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.TestConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Security review 2026-10, surface 1 (movement and block authority on a map shard), as regression tests
 * ({@code docs/DESIGN-M3.md} {@code REQ-M3-029}, M3-S): what one modified survival client gets past the
 * engine listeners on a mounted map. The packets go through the player's packet queue and a tick, as the
 * engine takes them; the map is the baked fixture mounted as {@code Main} mounts a map shard without a
 * directory, view distance 12 as {@code ShardMain} sets. The tests assert outcomes only: the position, the
 * block, the overlay and the transfers. The moves are judged by the engine (DESIGN-PROD v0.9.47, the handoffs
 * ①② of the M3 v0.38 row of §28.2): until those are in, the two tests that move are red.
 */
@Timeout(300)
public class MapShardPlayerAuthorityTest {

    /** What {@code ShardMain} writes for a map shard ({@code synaxis.world-writable=false}). */
    private static final SynxOptions READ_ONLY = new SynxOptions(false, 1 << 24, 1, false);
    private static final SynxOptions WRITABLE = new SynxOptions(true, 1 << 24, 1, false);
    private static final OverlayOptions OVERLAY = new OverlayOptions(16_384, 1);
    private static final int VIEW = 12;
    private static final int RING = VIEW + 1;

    @TempDir
    static Path baked;
    private static ShardSettings release;

    @TempDir
    Path overlayRoot;
    private final List<Env> envs = new ArrayList<>();

    @BeforeAll
    static void bake() {
        release = MapFixture.bake(baked);
    }

    @AfterEach
    void stop() {
        for (Env env : envs) env.close();
    }

    private static final class NoPlayers implements ShutdownParticipant {
        @Override
        public void onGateClosed(Duration countdown) {
        }

        @Override
        public CompletableFuture<?> saveAll(Duration budget, boolean tickAlive) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onSaveEnded(boolean timedOut) {
        }
    }

    private record Mounted(Env env, ShardWorld world, InstanceContainer instance, Pos spawn) {
    }

    private Mounted mount(SynxOptions options) {
        final Env env = Env.create(config -> config.chunkViewDistance(VIEW));
        envs.add(env);
        final ShardWorld world = ShardWorld.prepare(env.process(), release, options, OVERLAY, null);
        final ShardWorld.Started started = world.start(new ShutdownSequence(env.process(), new NoPlayers()),
                (_, _) -> {
                }, null, overlayRoot);
        return new Mounted(env, world, started.instance(), started.spawn());
    }

    private static void tickUntil(Env env, BooleanSupplier done, Duration within, String what) {
        final long deadline = System.nanoTime() + within.toNanos();
        while (!done.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) fail(what + ", not within " + within);
            env.tick();
            sleep(5);
        }
    }

    private static void sleep(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** A player of {@code connection} in the instance, once the entity tracker of the instance has it. */
    private static Player join(Env env, TestConnection connection, Instance instance, Pos at) {
        final Player player = connection.connect(instance, at);
        tickUntil(env, () -> instance.getEntityTracker().getEntityByUuid(player.getUuid()) != null, Duration.ofSeconds(10),
                "the player is in the entity tracker");
        return player;
    }

    /**
     * What a modified client sends, through the packet queue and one tick: it confirms the last teleport it
     * was sent, then one position packet.
     */
    private static void moveTo(Env env, Player player, double x, double y, double z) {
        player.addPacketToQueue(new ClientTeleportConfirmPacket(player.getLastSentTeleportId()));
        player.addPacketToQueue(new ClientPlayerPositionPacket(new Vec(x, y, z), (byte) 1));
        env.tick();
    }

    /**
     * §19 「不可改地形时远处的挖掘被回正、不进覆盖层」 (S1-01): one FINISHED_DIGGING, no STARTED_DIGGING, bare
     * hand, for a stone block 13 chunks away on both axes (about 294 blocks) on a map shard as
     * {@code ShardMain} mounts it: the block stays, the client is told to roll its prediction back, the chunk
     * is not dirty, and a restart on the same overlay has no record. A world state flag still changes a block.
     */
    @Test
    public void aDigFarAwayIsTurnedBackAndTheOverlayStaysClean() {
        final Mounted first = mount(READ_ONLY);
        final TestConnection connection = first.env().createConnection();
        final Collector<AcknowledgeBlockChangePacket> acks = connection.trackIncoming(AcknowledgeBlockChangePacket.class);
        final Player player = join(first.env(), connection, first.instance(), new Pos(8.5, MapFixture.FLOOR, 8.5));
        assertEquals(GameMode.SURVIVAL, player.getGameMode());
        final int chunk = RING;
        final Vec target = new Vec(chunk * 16 + 8, MapFixture.FLOOR - 1, chunk * 16 + 8);
        tickUntil(first.env(), () -> first.instance().isChunkLoaded(chunk, chunk), Duration.ofSeconds(30),
                "the chunk at the edge of the view ring loads");
        assertEquals(Block.STONE, first.instance().getBlock(target));

        player.addPacketToQueue(new ClientPlayerActionPacket(ClientPlayerActionPacket.Status.FINISHED_DIGGING, target,
                BlockFace.TOP, 1));
        // The queue is read on the next tick; collect() stops the tracking, so it is read once, after
        for (int i = 0; i < 5; i++) first.env().tick();
        final List<AcknowledgeBlockChangePacket> answered = acks.collect();

        assertEquals(Block.STONE, first.instance().getBlock(target), "dug from " + player.getPosition().distance(target)
                + " blocks");
        assertEquals(List.of(1), answered.stream().map(AcknowledgeBlockChangePacket::sequence).toList(),
                "the client rolls its prediction back");
        final SynxChunk unchanged = (SynxChunk) Objects.requireNonNull(first.instance().getChunk(chunk, chunk));
        assertFalse(unchanged.dirty(), "nothing for the overlay to write");
        envs.remove(first.env());
        first.env().close();

        final Mounted second = mount(READ_ONLY);
        assertEquals(0L, ((Number) second.world().startedAttributes().get("overlay_records")).longValue());
        second.instance().loadChunk(chunk, chunk).join();
        assertEquals(Block.STONE, second.instance().getBlock(target));
        final Vec flagged = new Vec(chunk * 16 + 2, MapFixture.FLOOR - 1, chunk * 16 + 2);
        SynxChunk.asWorldFlag(() -> second.instance().setBlock(flagged, Block.GOLD_BLOCK));
        assertEquals(Block.GOLD_BLOCK, second.instance().getBlock(flagged), "a world state flag still applies");
    }

    /**
     * S1-03 quantity: the overlay record of one chunk as random cells of it are broken, in bytes; the
     * record a chunk is written as is its whole content, so this is what the disk keeps per chunk.
     */
    @Test
    public void theOverlayRecordOfAChunkGrowsWithEveryCellBroken() throws Exception {
        final Mounted mounted = mount(WRITABLE);
        final Player player = mounted.env().createPlayer(mounted.instance(), mounted.spawn());
        final int chunkX = mounted.spawn().chunkX(), chunkZ = mounted.spawn().chunkZ();
        final SynxChunk chunk = (SynxChunk) Objects.requireNonNull(mounted.instance().getChunk(chunkX, chunkZ));
        final SynxSettings settings = ShardWorld.overlaySettings(SynxManifest.fromJson(
                Files.readString(release.buildDir().resolve(SynxManifest.FILE_NAME))), mounted.env().process().compressionThreshold());
        final Random random = new Random(7);
        final int[] checkpoints = {0, 1, 10, 100, 1_000, 4_000, 10_000};
        final List<Integer> sizes = new ArrayList<>();
        int done = 0;
        for (int checkpoint : checkpoints) {
            for (; done < checkpoint; done++) {
                final Vec cell = new Vec(chunkX * 16 + random.nextInt(16), random.nextInt(MapFixture.FLOOR), chunkZ * 16 + random.nextInt(16));
                boolean _ = mounted.instance().breakBlock(player, cell, BlockFace.TOP);
            }
            tickUntil(mounted.env(), () -> !chunk.relightPending(), Duration.ofSeconds(30), "the light settles");
            final int size = Objects.requireNonNull(chunk.encodeOverlay(settings, System.nanoTime() + TimeUnit.SECONDS.toNanos(1)))
                    .record().encode(settings).length;
            sizes.add(size);
        }
        System.out.println("S1-03 record_bytes_by_breaks checkpoints=" + Arrays.toString(checkpoints) + " sizes=" + sizes);
        assertTrue(sizes.get(5) > sizes.get(1), "a chunk with more cells broken has a larger record: " + sizes);
    }

    /**
     * §19 「一个位置包跨 13 个区块进不了关卡门框」 (S1-02): one position packet of 208 blocks (13 chunks) into the
     * frame of a gate is turned back, nothing asks for a transfer; walking there 16 blocks a packet, the packet
     * into the frame asks once.
     */
    @Test
    public void onePositionPacketThirteenChunksIntoAGateIsTurnedBack() {
        final Env env = Env.create(config -> config.chunkViewDistance(VIEW));
        envs.add(env);
        final Instance instance = env.createFlatInstance();
        final List<String> asked = new CopyOnWriteArrayList<>();
        new ShardGates(ExampleDirectory.as("r0-c1"), new ShardService() {
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
                asked.add(regionId);
                return new CompletableFuture<>();
            }

            @Override
            public CompletableFuture<ShardTransferResult> transferTo(Player player, String shardId) {
                throw new AssertionError();
            }
        }, (_, _) -> {
        }, _ -> true).attach(instance);
        final Pos start = new Pos(2040.5 - RING * 16, 41, 1024.5);
        final Player player = join(env, env.createConnection(), instance, start);
        tickUntil(env, () -> instance.isChunkLoaded(2040 >> 4, 1024 >> 4), Duration.ofSeconds(30), "the gate's chunk loads");

        moveTo(env, player, 2040.5, 41, 1024.5);
        assertEquals(start.x(), player.getPosition().x(), "a packet of " + (2040.5 - start.x()) + " blocks is turned back");
        assertEquals(List.of(), asked, "nothing asks for a transfer");

        double x = start.x();
        while (x < 2032) {
            x += 16;
            moveTo(env, player, x, 41, 1024.5);
            assertEquals(x, player.getPosition().x(), "16 blocks a packet are taken");
        }
        assertEquals(List.of("r1"), asked, "the packet into the frame asks once");
    }

    /**
     * §19 「连续越界跳跃不再新建空气区块」 (S1-V01): 200 ticks of one position packet 64 blocks east, within the
     * cap of one packet: those past the world border, x 256, are turned back, no chunk is asked for outside
     * {@code view_bounds}, and the instance never holds more than the 2,500 chunks of {@code view_bounds}.
     */
    @Test
    public void jumpsPastTheBoundsAreTurnedBackAndLoadNoAirChunk() {
        final Mounted mounted = mount(READ_ONLY);
        final InstanceContainer instance = mounted.instance();
        final Player player = join(mounted.env(), mounted.env().createConnection(), instance, mounted.spawn());
        final int z = mounted.spawn().chunkZ();
        tickUntil(mounted.env(), () -> instance.isChunkLoaded(mounted.spawn().chunkX() + RING, z), Duration.ofSeconds(30),
                "the view ring loads");
        final double border = instance.getWorldBorder().centerX() + instance.getWorldBorder().diameter() / 2;
        int held = instance.getChunks().size();
        double farthest = player.getPosition().x();
        for (int jump = 0; jump < 200; jump++) {
            moveTo(mounted.env(), player, player.getPosition().x() + 64, mounted.spawn().y(), mounted.spawn().z());
            farthest = Math.max(farthest, player.getPosition().x());
            held = Math.max(held, instance.getChunks().size());
        }
        final long voidChunks = ((ShardInstance) instance).voidChunks();
        System.out.println("S1-V01 jumps=200 x=" + player.getPosition().x() + " farthest=" + farthest + " border_x=" + border
                + " chunks_held_max=" + held + " void_chunks=" + voidChunks);
        assertTrue(farthest < border, "the player stays inside the world border at x " + border + ": " + farthest);
        assertEquals(0, voidChunks, "no chunk asked for outside view_bounds");
        assertTrue(held <= 50 * 50, "at most the 2,500 chunks of view_bounds: " + held);
    }
}
