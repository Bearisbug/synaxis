package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.light.LightEngine;
import io.github.bearisbug.synaxis.instance.light.LightResult;
import io.github.bearisbug.synaxis.instance.light.WorldView;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.server.BufferedPacket;
import io.github.bearisbug.synaxis.network.packet.server.CachedPacket;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.BlockChangePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.UpdateLightPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.data.LightData;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.world.DimensionType;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The world of the runtime light tests ({@code docs/DESIGN-M3.md} §19, the {@code :synx} integration
 * and multi-viewer entries of M3-D): the {@link RelightTestMap} on a real {@link OverlayStore} and a
 * {@link SynxChunkLoader} whose runtime light has started. A tick is {@code env.tick()}, then the
 * install and the write phase, in the order {@code ShardWorld} runs them at the end of the tick; the
 * real players the tests connect acknowledge every chunk batch at once.
 * <p>
 * The server's light of a chunk ({@link #truth}) is {@link LightEngine#computeArea} over the blocks
 * the instance holds right now, a chunk that is not a {@link SynxChunk} counting as missing, as the
 * recomputation counts it.
 */
final class RelightWorld {
    static final int SECTIONS = RelightTestMap.HEIGHT / 16;

    /** Holds the snapshots and computations of the recomputations until the test runs them. */
    static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public synchronized void execute(Runnable command) {
            tasks.add(command);
        }

        /** Runs the task handed over first; false if there is none. */
        boolean runNext() {
            final Runnable next;
            synchronized (this) {
                next = tasks.poll();
            }
            if (next == null) return false;
            next.run();
            return true;
        }

        /** Runs tasks, those they hand over included, until none is left. */
        void runAll() {
            while (runNext()) {
                Thread.onSpinWait();
            }
        }

        synchronized int size() {
            return tasks.size();
        }
    }

    /** A player on a connection that keeps every packet, see {@link RecordingConnection}. */
    record Viewer(Player player, RecordingConnection connection) {
        List<UpdateLightPacket> lightPackets(int chunkX, int chunkZ) {
            return connection.packets(UpdateLightPacket.class).stream()
                    .filter(packet -> packet.chunkX() == chunkX && packet.chunkZ() == chunkZ).toList();
        }

        long blockChanges(int x, int y, int z) {
            return connection.packets(BlockChangePacket.class).stream()
                    .filter(packet -> packet.blockPosition().blockX() == x && packet.blockPosition().blockY() == y
                            && packet.blockPosition().blockZ() == z).count();
        }

        /** The chunks the player was sent, by {@code CoordConversion#chunkIndex}. */
        Set<Long> chunksReceived() {
            final Set<Long> received = new HashSet<>();
            for (SendablePacket packet : connection.sent) {
                final int[] at = switch (packet) {
                    case BufferedPacket buffered -> SynxWorlds.chunkOf(RecordingConnection.bytes(buffered));
                    case CachedPacket cached when cached.packet() instanceof ChunkDataPacket data ->
                            new int[]{data.chunkX(), data.chunkZ()};
                    case ChunkDataPacket data -> new int[]{data.chunkX(), data.chunkZ()};
                    default -> null;
                };
                if (at != null) received.add(key(at[0], at[1]));
            }
            return received;
        }

        void forget() {
            connection.sent.clear();
        }
    }

    final Env env;
    final OverlayStore store;
    final SynxChunkLoader loader;
    final InstanceContainer instance;
    final SynxRelight relight;
    final @Nullable ManualExecutor executor;
    private final List<Viewer> viewers = new ArrayList<>();

    private RelightWorld(Env env, OverlayStore store, SynxChunkLoader loader, InstanceContainer instance,
                         SynxRelight relight, @Nullable ManualExecutor executor) {
        this.env = env;
        this.store = store;
        this.loader = loader;
        this.instance = instance;
        this.relight = relight;
        this.executor = executor;
    }

    /**
     * @param bounds   the region the loader serves
     * @param manual   whether the recomputations wait for the test ({@link #executor}) instead of a pool
     */
    static RelightWorld create(Env env, Path overlayRoot, SynxManifest.Bounds bounds, SynxOptions options,
                               OverlayOptions overlayOptions, boolean manual) {
        final RelightTestMap map = RelightTestMap.get();
        try {
            final OverlayStore store = OverlayStore.open(overlayRoot, map.manifest, map.settings(), overlayOptions);
            final SynxChunkLoader loader = new SynxChunkLoader(map.buildDir, map.manifest, bounds, options, store, store);
            final InstanceContainer instance = env.process().instance().createInstanceContainer(dimension(env.process()), loader);
            final ManualExecutor executor = manual ? new ManualExecutor() : null;
            final SynxRelight relight = executor != null ? loader.startRelight(instance, executor) : loader.startRelight(instance);
            return new RelightWorld(env, store, loader, instance, relight, executor);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    static RelightWorld create(Env env, Path overlayRoot, boolean manual) {
        return create(env, overlayRoot, RelightTestMap.BOUNDS, options(false), new OverlayOptions(16_384, 10), manual);
    }

    static SynxOptions options(boolean resendLight) {
        return new SynxOptions(true, 64L << 20, 2, resendLight);
    }

    /** The dimension type of the relight test map, y 0 to 64. */
    static RegistryKey<DimensionType> dimension(ServerProcess process) {
        final Key key = Key.key("synaxis:relight_test");
        final RegistryKey<DimensionType> existing = process.dimensionType().getKey(key);
        if (existing != null) return existing;
        return process.dimensionType().register(key, DimensionType.builder()
                .minY(0).height(RelightTestMap.HEIGHT).logicalHeight(RelightTestMap.HEIGHT).build());
    }

    static long key(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | (chunkZ & 0xFFFFFFFFL);
    }

    SynxChunk load(int chunkX, int chunkZ) {
        return (SynxChunk) instance.loadChunk(chunkX, chunkZ).join();
    }

    /** Loads the 5×5 around a chunk. */
    void loadAround(int chunkX, int chunkZ, int radius) {
        for (int x = chunkX - radius; x <= chunkX + radius; x++) {
            for (int z = chunkZ - radius; z <= chunkZ + radius; z++) instance.loadChunk(x, z).join();
        }
    }

    SynxChunk chunk(int chunkX, int chunkZ) {
        final Chunk chunk = instance.getChunk(chunkX, chunkZ);
        if (!(chunk instanceof SynxChunk synx)) throw new AssertionError("no SynxChunk at " + chunkX + "," + chunkZ + ": " + chunk);
        return synx;
    }

    void tick() {
        for (Viewer viewer : viewers) viewer.player().onChunkBatchReceived(64);
        env.tick();
        relight.installPhase();
        store.tickEnd();
    }

    void ticks(int count) {
        for (int i = 0; i < count; i++) tick();
    }

    /** Ticks until {@code done}, 30 s at most. */
    void tickUntil(BooleanSupplier done) {
        final long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out");
            tick();
            Thread.onSpinWait();
        }
    }

    /** Runs the held recomputations, if any, and ticks until nothing is pending. */
    void settle() {
        tickUntil(() -> {
            if (executor != null) executor.runAll();
            return relight.pendingCenters() == 0;
        });
    }

    /**
     * Logs a real player in at {@code spawn}, its chunk ring {@code maxViewDistance} + 1 chunks around
     * it, and ticks until it holds every chunk of the ring the map has.
     */
    Viewer connect(Pos spawn, int maxViewDistance) {
        final ServerProcess process = env.process();
        final RecordingConnection connection = new RecordingConnection(process);
        process.connection().setPlayerProvider(Player::new);
        final Player player = process.connection().createPlayer(connection, new GameProfile(UUID.randomUUID(), "Viewer"));
        if (player == null) throw new IllegalStateException("no player");
        player.eventNode().addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(instance);
            event.getPlayer().setRespawnPoint(spawn);
            event.getPlayer().setMaxViewDistance(maxViewDistance);
        });
        final CompletableFuture<Player> future = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try {
                process.connection().doConfiguration(player, false);
                process.connection().transitionConfigToPlay(player);
                future.complete(player);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        future.join();
        connection.setClientState(ConnectionState.PLAY);
        connection.setServerState(ConnectionState.PLAY);
        process.connection().updateWaitingPlayers();
        final Viewer viewer = new Viewer(player, connection);
        viewers.add(viewer);
        awaitRing(viewer, spawn.chunkX(), spawn.chunkZ(), maxViewDistance + 1);
        return viewer;
    }

    /** Ticks until the viewer holds every chunk of the map in the ring of {@code radius} around the chunk. */
    void awaitRing(Viewer viewer, int chunkX, int chunkZ, int radius) {
        final Set<Long> ring = new HashSet<>();
        for (int x = chunkX - radius; x <= chunkX + radius; x++) {
            for (int z = chunkZ - radius; z <= chunkZ + radius; z++) {
                if (x >= RelightTestMap.BOUNDS.minX() && x <= RelightTestMap.BOUNDS.maxX()
                        && z >= RelightTestMap.BOUNDS.minZ() && z <= RelightTestMap.BOUNDS.maxZ()) ring.add(key(x, z));
            }
        }
        tickUntil(() -> viewer.chunksReceived().containsAll(ring));
    }

    // ---- The server's light

    /** The server's light of a chunk: {@link LightEngine#computeArea} over what the instance holds now. */
    LightResult truth(int chunkX, int chunkZ) {
        return truth(instance, chunkX, chunkZ);
    }

    static LightResult truth(Instance instance, int chunkX, int chunkZ) {
        final Map<Long, char[][]> blocks = new HashMap<>();
        for (int x = chunkX - 1; x <= chunkX + 1; x++) {
            for (int z = chunkZ - 1; z <= chunkZ + 1; z++) {
                if (!(instance.getChunk(x, z) instanceof SynxChunk chunk)) continue;
                final char[][] states = new char[SECTIONS][4096];
                chunk.lockReadLock();
                try {
                    for (int i = 0; i < SECTIONS * 4096; i++) {
                        final int y = i >> 8, bx = x * 16 + (i & 15), bz = z * 16 + ((i >> 4) & 15);
                        states[y >> 4][i & 4095] = (char) chunk.getBlock(bx, y, bz, Block.Getter.Condition.TYPE).stateId();
                    }
                } finally {
                    chunk.unlockReadLock();
                }
                blocks.put(key(x, z), states);
            }
        }
        return LightEngine.computeArea(new WorldView() {
            @Override
            public int minY() {
                return 0;
            }

            @Override
            public int height() {
                return RelightTestMap.HEIGHT;
            }

            @Override
            public boolean hasChunk(int x, int z) {
                return blocks.containsKey(key(x, z));
            }

            @Override
            public Block getBlock(int x, int y, int z) {
                final char[][] states = blocks.get(key(x >> 4, z >> 4));
                return Block.fromStateId(states[y >> 4][(x & 15) | (z & 15) << 4 | (y & 15) << 8]);
            }
        }, chunkX, chunkZ, chunkX, chunkZ);
    }

    /** The light a new viewer of the chunk gets: that of its full data packet. */
    static LightData sentLight(SynxChunk chunk) {
        final SendablePacket packet = chunk.getFullDataPacket();
        return switch (packet) {
            case BufferedPacket buffered -> SynxChunkPacket.decode(RecordingConnection.bytes(buffered),
                    RelightTestMap.REGISTRIES).lightData();
            case CachedPacket cached -> ((ChunkDataPacket) cached.packet()).lightData();
            default -> throw new AssertionError("not a chunk packet: " + packet);
        };
    }

    /** The light a new viewer of a chunk gets, against the server's. */
    void assertLight(int chunkX, int chunkZ) {
        assertLight("chunk " + chunkX + "," + chunkZ, sentLight(chunk(chunkX, chunkZ)), truth(chunkX, chunkZ), chunkX, chunkZ);
    }

    void assertLight(int minX, int minZ, int maxX, int maxZ) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) assertLight(x, z);
        }
    }

    /** Every section of {@code light}, sky and block, nibble for nibble against the server's light. */
    static void assertLight(String what, LightData light, LightResult truth, int chunkX, int chunkZ) {
        for (int section = 0; section < SECTIONS; section++) {
            assertSection(what + " sky section " + section, truth.sky(chunkX, section, chunkZ),
                    array(light.skyMask(), light.emptySkyMask(), light.skyLight(), section));
            assertSection(what + " block section " + section, truth.block(chunkX, section, chunkZ),
                    array(light.blockMask(), light.emptyBlockMask(), light.blockLight(), section));
        }
    }

    /** The array of a section in a light payload, all 0 for an empty one; null if the payload leaves it out. */
    static byte @Nullable [] array(BitSet mask, BitSet empty, List<byte[]> arrays, int section) {
        final int bit = section + 1;
        if (empty.get(bit)) return new byte[2048];
        if (!mask.get(bit)) return null;
        return arrays.get(mask.get(0, bit).cardinality());
    }

    /** Whether two light payloads hold the same light, section for section; an empty section is all 0. */
    static boolean sameLight(LightData a, LightData b) {
        for (int section = 0; section < SECTIONS; section++) {
            if (!sameArray(array(a.skyMask(), a.emptySkyMask(), a.skyLight(), section),
                    array(b.skyMask(), b.emptySkyMask(), b.skyLight(), section))) return false;
            if (!sameArray(array(a.blockMask(), a.emptyBlockMask(), a.blockLight(), section),
                    array(b.blockMask(), b.emptyBlockMask(), b.blockLight(), section))) return false;
        }
        return true;
    }

    private static boolean sameArray(byte @Nullable [] a, byte @Nullable [] b) {
        if (a == null || b == null) return a == b;
        return Arrays.equals(a.length == 0 ? new byte[2048] : a, b.length == 0 ? new byte[2048] : b);
    }

    /** Whether a light payload covers every section of the world, a full light packet. */
    static boolean full(LightData light) {
        for (int section = 0; section < SECTIONS; section++) {
            final int bit = section + 1;
            if (!(light.skyMask().get(bit) || light.emptySkyMask().get(bit))) return false;
            if (!(light.blockMask().get(bit) || light.emptyBlockMask().get(bit))) return false;
        }
        return true;
    }

    private static void assertSection(String what, byte[] expected, byte @Nullable [] actual) {
        if (actual == null) fail(what + " is not in the light data");
        final byte[] sent = actual.length == 0 ? new byte[2048] : actual;
        if (Arrays.equals(expected, sent)) return;
        for (int i = 0; i < 4096; i++) {
            final int e = (expected[i >> 1] >> ((i & 1) << 2)) & 15, a = (sent[i >> 1] >> ((i & 1) << 2)) & 15;
            if (e != a) {
                assertEquals(e, a, what + ": the nibble at x " + (i & 15) + " z " + ((i >> 4) & 15) + " y " + (i >> 8));
            }
        }
        assertTrue(Arrays.equals(expected, sent), what);
    }

    /** Whether the light of a chunk as a new viewer gets it equals the server's. */
    boolean lightMatches(int chunkX, int chunkZ) {
        try {
            assertLight(chunkX, chunkZ);
            return true;
        } catch (AssertionError _) {
            return false;
        }
    }
}
