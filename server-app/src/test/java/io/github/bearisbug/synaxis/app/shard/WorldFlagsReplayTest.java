package io.github.bearisbug.synaxis.app.shard;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import io.github.bearisbug.synaxis.ShutdownParticipant;
import io.github.bearisbug.synaxis.app.ShutdownSequence;
import io.github.bearisbug.synaxis.app.store.AuthMode;
import io.github.bearisbug.synaxis.app.store.Database;
import io.github.bearisbug.synaxis.app.store.Migrations;
import io.github.bearisbug.synaxis.app.store.RedisLink;
import io.github.bearisbug.synaxis.app.store.TestDatabase;
import io.github.bearisbug.synaxis.baker.Baker;
import io.github.bearisbug.synaxis.baker.GeneratorPreset;
import io.github.bearisbug.synaxis.baker.GeneratorSource;
import io.github.bearisbug.synaxis.baker.LightVersions;
import io.github.bearisbug.synaxis.coordinate.BlockFace;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.entity.PlayerHand;
import io.github.bearisbug.synaxis.event.player.PlayerBlockBreakEvent;
import io.github.bearisbug.synaxis.event.player.PlayerBlockPlaceEvent;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.listener.BlockPlacementListener;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerBlockPlacementPacket;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.synx.OverlayOptions;
import io.github.bearisbug.synaxis.synx.SynxChunk;
import io.github.bearisbug.synaxis.synx.SynxOptions;
import io.github.bearisbug.synaxis.synx.SynxRelight;
import io.github.bearisbug.synaxis.testing.Env;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The world state flags on a mounted map ({@code docs/DESIGN-M3.md} {@code API-M3-013}, §19 the
 * REQ-M3-023 scenarios whose entry is the server-app integration test): the replay at start, the
 * applications at run time on the tick scheduling thread, the governed cells kept from the players and
 * set back by the read every 60 s, and the refusal when the flags cannot be read.
 * <p>
 * The map is a baked fixture mounted the way {@code Main} mounts it, without a shard directory, so the
 * region is the manifest's bounds; the flags are rows of a database of its own on the shared
 * PostgreSQL, the appliers are registered here. The ticks are run by hand.
 */
@Timeout(120)
public class WorldFlagsReplayTest {

    private static final SynxOptions WRITABLE = new SynxOptions(true, 1 << 24, 1, false);
    private static final SynxOptions READ_ONLY = new SynxOptions(false, 1 << 24, 1, false);
    /** A change is written within a second, which the restart after a flush waits for. */
    private static final OverlayOptions OVERLAY = new OverlayOptions(16_384, 1);
    private static final String REGION = "r0";
    private static final String BRIDGE = "quest:bridge";
    private static final String GATE = "quest:gate";
    private static final String CHEST = "quest:chest";
    private static final String LAMP = "quest:lamp";
    /** The row of chests: z 3, on the floor. */
    private static final int CHEST_Z = 3;
    /** The bridge: 20 cells in the air at y 45, z 4, x 0 to 19, across chunks 0,0 and 1,0. */
    private static final int BRIDGE_Y = 45;
    private static final int BRIDGE_Z = 4;
    /** The doorway of the gate: two cells standing on the floor at x 10, z 10. */
    static final int GATE_X = 10;
    static final int GATE_Z = 10;
    private static final int FLOOR = MapFixture.FLOOR;

    @TempDir
    static Path baked;
    private static ShardSettings release;
    /** The same map with a deepslate floor: another build_id. */
    private static ShardSettings nextRelease;

    @TempDir
    Path overlayRoot;
    private TestDatabase database;
    private Database pool;
    private final List<Env> envs = new ArrayList<>();
    private final List<WorldFlagsImpl> opened = new ArrayList<>();
    private final ListAppender<ILoggingEvent> log = new ListAppender<>();

    @BeforeAll
    static void bake() {
        release = MapFixture.bake(baked);
        final GeneratorSource source = new GeneratorSource(new GeneratorPreset(
                unit -> unit.modifier().fillHeight(0, FLOOR, Block.DEEPSLATE), 0, 64, MapFixture.BOUNDS), Registries.vanilla());
        final Baker.Result result = Baker.bake(new Baker.Request(MapFixture.MAP_ID, source, baked, 1, baked.resolve("logs"),
                Registries.vanilla(), _ -> null, LightVersions.verified(), 1));
        if (result.exitCode() != Baker.EXIT_PUBLISHED || result.directory() == null)
            throw new AssertionError("the second release did not bake: " + result);
        nextRelease = new ShardSettings(release.mapRoot(), result.directory().getFileName().toString(), 4, 100);
        assertNotEquals(release.mapBuild(), nextRelease.mapBuild());
    }

    @BeforeEach
    void capture() {
        log.start();
        ((Logger) LoggerFactory.getLogger(WorldFlagsImpl.class)).addAppender(log);
    }

    @AfterEach
    void close() {
        ((Logger) LoggerFactory.getLogger(WorldFlagsImpl.class)).detachAppender(log);
        log.stop();
        for (WorldFlagsImpl flags : opened) flags.close();
        for (Env env : envs) env.close();
        if (pool != null) pool.close();
        if (database != null) database.close();
    }

    /** The database of this test, created on first use: the refusal needs none. */
    private DataSource pool() throws SQLException {
        if (pool == null) {
            database = TestDatabase.create();
            pool = new Database(database.settings());
            Migrations.migrate(pool.pool(), AuthMode.OFFLINE);
        }
        return pool.pool();
    }

    private void row(String key, String value, long version) throws SQLException {
        pool();
        database.execute("INSERT INTO world_flag (map_id, region_id, flag_key, value, version) VALUES ('"
                + MapFixture.MAP_ID + "', '" + REGION + "', '" + key + "', '\"" + value + "\"', " + version + ")");
    }

    private WorldFlagsImpl flags() throws SQLException {
        return track(new WorldFlagsImpl(pool(), RedisLink.disabled(REGION + "-c1"), MapFixture.MAP_ID, REGION,
                MapFixture.BOUNDS));
    }

    private WorldFlagsImpl track(WorldFlagsImpl flags) {
        opened.add(flags);
        return flags;
    }

    static List<FlagCell> bridge(JsonElement value) {
        final Block block = "repaired".equals(value.getAsString()) ? Block.OAK_PLANKS : Block.AIR;
        return IntStream.range(0, 20).mapToObj(x -> new FlagCell(x, BRIDGE_Y, BRIDGE_Z, block)).toList();
    }

    static List<FlagCell> gate(JsonElement value) {
        final Block block = "open".equals(value.getAsString()) ? Block.AIR : Block.IRON_BARS;
        return List.of(new FlagCell(GATE_X, FLOOR, GATE_Z, block), new FlagCell(GATE_X, FLOOR + 1, GATE_Z, block));
    }

    /**
     * Three chests on the floor of chunk 0,0, each unlike what an overlay record gives back in one way
     * only: no NBT, the record keys in its NBT, and a handler of its own where the registration makes
     * a new one on every call.
     */
    static List<FlagCell> chests(JsonElement ignored) {
        final CompoundBinaryTag withRecordKeys = CompoundBinaryTag.builder().putString("id", "minecraft:chest")
                .putInt("x", 5).putInt("y", FLOOR).putInt("z", CHEST_Z).putBoolean("keepPacked", false)
                .putString("synaxis_note", "kept").build();
        return List.of(new FlagCell(3, FLOOR, CHEST_Z, Block.CHEST),
                new FlagCell(5, FLOOR, CHEST_Z, Block.CHEST.withNbt(withRecordKeys)),
                new FlagCell(7, FLOOR, CHEST_Z, Block.CHEST.withNbt(CompoundBinaryTag.empty()).withHandler(new ChestHandler())));
    }

    /** A handler the process registers with a supplier that makes a new one on every call. */
    private static final class ChestHandler implements BlockHandler {
        static final Key KEY = Key.key("synaxis_test", "flag_chest");

        @Override
        public Key getKey() {
            return KEY;
        }
    }

    private static JsonElement json(String value) {
        return new JsonPrimitive(value);
    }

    /** The player store of a shard nobody joined. */
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

    private record Mounted(Env env, ShardWorld world, InstanceContainer instance, SynxRelight relight) {
    }

    /** Steps (3) and (5) of {@code Main} on a map, in a process of its own. */
    private Mounted mount(ShardSettings settings, SynxOptions synx, WorldFlagsImpl flags) {
        return mount(settings, synx, flags, null);
    }

    /** As {@link #mount(ShardSettings, SynxOptions, WorldFlagsImpl)}, the light recomputations on {@code relightExecutor}. */
    private Mounted mount(ShardSettings settings, SynxOptions synx, WorldFlagsImpl flags, @Nullable Executor relightExecutor) {
        final Env env = Env.create(config -> config.chunkViewDistance(4));
        envs.add(env);
        env.process().block().registerHandler(ChestHandler.KEY, ChestHandler::new);
        final ShardWorld world = ShardWorld.prepare(env.process(), settings, synx, OVERLAY, null);
        final ShardWorld.Started started = world.start(new ShutdownSequence(env.process(), new NoPlayers()),
                (_, _) -> {
                }, flags, overlayRoot, relightExecutor);
        return new Mounted(env, world, started.instance(), started.relight());
    }

    private void stop(Mounted mounted) {
        envs.remove(mounted.env());
        mounted.env().close();
    }

    private static void tickUntil(Env env, BooleanSupplier done, Duration within, String what) {
        final long deadline = System.nanoTime() + within.toNanos();
        while (!done.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) fail(what + ", not within " + within);
            env.tick();
            try {
                TimeUnit.MILLISECONDS.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private static List<SynxChunk> bridgeChunks(InstanceContainer instance) {
        return List.of((SynxChunk) instance.getChunk(0, 0), (SynxChunk) instance.getChunk(1, 0));
    }

    private static void assertBridge(InstanceContainer instance, Block block) {
        for (int x = 0; x < 20; x++) assertEquals(block, instance.getBlock(x, BRIDGE_Y, BRIDGE_Z), "the bridge at x " + x);
    }

    private static void assertDoorway(InstanceContainer instance, Block block) {
        assertEquals(block, instance.getBlock(GATE_X, FLOOR, GATE_Z));
        assertEquals(block, instance.getBlock(GATE_X, FLOOR + 1, GATE_Z));
    }

    private List<String> warnings() {
        return log.list.stream().filter(event -> event.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** §19 「世界状态标志起服重放」. */
    @Test
    public void theStartReplaysTheFlagsAndARestartOnTheSameReleaseChangesNothing() throws Exception {
        row(BRIDGE, "repaired", 3);
        final WorldFlagsImpl flags = flags();
        flags.register(BRIDGE, WorldFlagsReplayTest::bridge);

        final Mounted first = mount(release, WRITABLE, flags);

        assertBridge(first.instance(), Block.OAK_PLANKS);
        for (SynxChunk chunk : bridgeChunks(first.instance())) assertTrue(chunk.dirty(), "materialized into the overlay");
        assertEquals(1, flags.stats().replayed());
        assertEquals(new FlagValue(json("repaired"), 3), flags.get(BRIDGE));
        assertEquals(1, first.world().startedAttributes().get("flags_replayed"));

        first.instance().setChunkUnloadGraceTicks(-1);
        tickUntil(first.env(), () -> bridgeChunks(first.instance()).stream().noneMatch(SynxChunk::dirty),
                Duration.ofSeconds(20), "the bridge chunks are written");
        stop(first);

        final WorldFlagsImpl again = flags();
        again.register(BRIDGE, WorldFlagsReplayTest::bridge);
        final Mounted second = mount(release, WRITABLE, again);

        assertBridge(second.instance(), Block.OAK_PLANKS);
        for (SynxChunk chunk : bridgeChunks(second.instance()))
            assertFalse(chunk.dirty(), "loaded from its overlay record, no cell set again");
        assertEquals(1, again.stats().replayed());
        assertTrue(((Number) second.world().startedAttributes().get("overlay_records")).longValue() >= 2);
        second.env().tick();
        for (SynxChunk chunk : bridgeChunks(second.instance()))
            assertFalse(chunk.dirty(), "nothing for the write phase at the end of the first tick");
    }

    /** §19 「换地图版本后标志重放到新版本」. */
    @Test
    public void aNewReleaseGetsTheFlagsReplayedOntoIt() throws Exception {
        row(BRIDGE, "repaired", 3);
        final WorldFlagsImpl flags = flags();
        flags.register(BRIDGE, WorldFlagsReplayTest::bridge);
        final Mounted first = mount(release, WRITABLE, flags);
        first.instance().setChunkUnloadGraceTicks(-1);
        tickUntil(first.env(), () -> bridgeChunks(first.instance()).stream().noneMatch(SynxChunk::dirty),
                Duration.ofSeconds(20), "the bridge chunks are written");
        stop(first);
        final Path previous = overlayRoot.resolve(release.mapBuild());
        try (var files = Files.list(previous)) {
            assertTrue(files.findAny().isPresent(), "the old release has overlay records");
        }

        final WorldFlagsImpl next = flags();
        next.register(BRIDGE, WorldFlagsReplayTest::bridge);
        final Mounted second = mount(nextRelease, WRITABLE, next);

        assertFalse(Files.exists(previous), "F1: the overlay of the old build_id is gone");
        assertEquals(Block.DEEPSLATE, second.instance().getBlock(0, FLOOR - 1, 0), "the new release is mounted");
        assertBridge(second.instance(), Block.OAK_PLANKS);
        for (SynxChunk chunk : bridgeChunks(second.instance())) assertTrue(chunk.dirty());
        assertEquals(1, second.world().startedAttributes().get("flags_replayed"));
    }

    /** §19 「世界状态标志起服重放」 with block entities, which the overlay record gives back in another form. */
    @Test
    public void aRestartOnTheSameReleaseLeavesTheBlockEntitiesOfAFlagAlone() throws Exception {
        row(CHEST, "placed", 1);
        final WorldFlagsImpl flags = flags();
        flags.register(CHEST, WorldFlagsReplayTest::chests);
        final Mounted first = mount(release, WRITABLE, flags);
        final SynxChunk placed = (SynxChunk) first.instance().getChunk(0, 0);
        assertTrue(placed.dirty(), "the replay placed the chests");
        first.instance().setChunkUnloadGraceTicks(-1);
        tickUntil(first.env(), () -> !placed.dirty(), Duration.ofSeconds(20), "the chests are written");
        stop(first);

        final WorldFlagsImpl again = flags();
        again.register(CHEST, WorldFlagsReplayTest::chests);
        final Mounted second = mount(release, WRITABLE, again);

        assertFalse(((SynxChunk) second.instance().getChunk(0, 0)).dirty(), "the replay set no chest again");
        assertEquals(1, again.stats().replayed());
        for (int x = 3; x <= 7; x += 2) assertEquals(Block.CHEST.id(), second.instance().getBlock(x, FLOOR, CHEST_Z).id());
    }

    /** §19 「没登记应用方式的键在重放时跳过」. */
    @Test
    public void aKeyWithoutAnApplierIsSkippedWithAWarning() throws Exception {
        row(BRIDGE, "repaired", 3);
        row(GATE, "closed", 2);
        final WorldFlagsImpl flags = flags();
        flags.register(BRIDGE, WorldFlagsReplayTest::bridge);

        final Mounted mounted = mount(release, WRITABLE, flags);

        assertTrue(warnings().stream().anyMatch(line -> line.contains(GATE)), warnings().toString());
        assertDoorway(mounted.instance(), Block.AIR);
        assertNull(flags.get(GATE));
        assertEquals(1, flags.stats().replayed(), "only quest:bridge");
        assertBridge(mounted.instance(), Block.OAK_PLANKS);
        assertThrows(IllegalStateException.class, () -> flags.register(GATE, WorldFlagsReplayTest::gate),
                "no applier after the replay");
    }

    /** §19 「玩家不能改标志管辖的格子」. */
    @Test
    public void thePlayersCannotChangeTheGovernedCells() throws Exception {
        row(BRIDGE, "repaired", 3);
        row(GATE, "open", 2);
        final WorldFlagsImpl flags = flags();
        flags.register(BRIDGE, WorldFlagsReplayTest::bridge);
        flags.register(GATE, WorldFlagsReplayTest::gate);
        final Mounted mounted = mount(release, WRITABLE, flags);
        final InstanceContainer instance = mounted.instance();
        flags.start(instance);
        final List<PlayerBlockBreakEvent> breaks = new CopyOnWriteArrayList<>();
        final List<PlayerBlockPlaceEvent> places = new CopyOnWriteArrayList<>();
        mounted.env().process().eventHandler().addListener(PlayerBlockBreakEvent.class, breaks::add)
                .addListener(PlayerBlockPlaceEvent.class, places::add);
        final Player player = mounted.env().createPlayer(instance, new Pos(6.5, FLOOR, 13.5));
        player.setItemInHand(PlayerHand.MAIN, ItemStack.of(Material.STONE, 5));

        assertFalse(instance.breakBlock(player, new Vec(5, BRIDGE_Y, BRIDGE_Z), BlockFace.TOP));
        BlockPlacementListener.listener(new ClientPlayerBlockPlacementPacket(PlayerHand.MAIN,
                new Pos(GATE_X, FLOOR - 1, GATE_Z), BlockFace.TOP, 0.5f, 0.5f, 0.5f, false, false, 1), player);

        assertEquals(1, breaks.size());
        assertTrue(breaks.getFirst().isCancelled());
        assertEquals(1, places.size());
        assertTrue(places.getFirst().isCancelled());
        assertEquals(Block.OAK_PLANKS, instance.getBlock(5, BRIDGE_Y, BRIDGE_Z));
        assertDoorway(instance, Block.AIR);
        assertEquals(0, flags.stats().conflicts());

        assertTrue(instance.breakBlock(player, new Vec(12, FLOOR - 1, 14), BlockFace.TOP), "outside the table");
        assertEquals(Block.AIR, instance.getBlock(12, FLOOR - 1, 14));
        BlockPlacementListener.listener(new ClientPlayerBlockPlacementPacket(PlayerHand.MAIN,
                new Pos(3, FLOOR - 1, 14), BlockFace.TOP, 0.5f, 0.5f, 0.5f, false, false, 2), player);
        assertEquals(Block.STONE, instance.getBlock(3, FLOOR, 14));
        assertFalse(breaks.get(1).isCancelled());
        assertFalse(places.get(1).isCancelled());
    }

    /** §19 「标志与覆盖层冲突以标志为准」: the read every 60 s sets a cell back, a new value counts one too. */
    @Test
    public void theFlagWinsOverAChangeThatCameAroundTheProtection() throws Exception {
        row(BRIDGE, "repaired", 3);
        final WorldFlagsImpl flags = flags();
        flags.register(BRIDGE, WorldFlagsReplayTest::bridge);
        final Mounted mounted = mount(release, WRITABLE, flags);
        final InstanceContainer instance = mounted.instance();
        instance.setChunkUnloadGraceTicks(-1);
        flags.start(instance);
        final long before = flags.stats().conflicts();

        instance.setBlock(5, BRIDGE_Y, BRIDGE_Z, Block.AIR);
        flags.syncNow();
        tickUntil(mounted.env(), () -> instance.getBlock(5, BRIDGE_Y, BRIDGE_Z).equals(Block.OAK_PLANKS),
                Duration.ofSeconds(10), "the cell is set back");

        assertEquals(1, flags.stats().conflicts() - before, "the flag_conflicts of the next EVT:chunk_stream_stats");
        assertTrue(warnings().stream().anyMatch(line -> line.contains(BRIDGE) && line.contains("5 45 4")),
                warnings().toString());
        assertBridge(instance, Block.OAK_PLANKS);

        instance.setBlock(6, BRIDGE_Y, BRIDGE_Z, Block.STONE);
        flags.schedule(BRIDGE, json("broken"), 4);
        mounted.env().tick();

        assertBridge(instance, Block.AIR);
        assertEquals(2, flags.stats().conflicts() - before,
                "stone was neither the planks applied last nor the air of the new value");
        assertEquals(new FlagValue(json("broken"), 4), flags.get(BRIDGE));
    }

    /**
     * §19 「标志与覆盖层冲突以标志为准」 from the other side: a block entity the overlay record gives back
     * with an empty NBT, without the record keys or with another handler object of the same namespace
     * holds its target, not a conflict.
     */
    @Test
    public void aBlockEntityLoadedFromItsOverlayRecordIsNoConflict() throws Exception {
        row(CHEST, "placed", 1);
        final WorldFlagsImpl flags = flags();
        flags.register(CHEST, WorldFlagsReplayTest::chests);
        final Mounted mounted = mount(release, WRITABLE, flags);
        final InstanceContainer instance = mounted.instance();
        instance.setChunkUnloadGraceTicks(-1);
        flags.start(instance);
        final SynxChunk placed = (SynxChunk) instance.getChunk(0, 0);
        tickUntil(mounted.env(), () -> !placed.dirty(), Duration.ofSeconds(20), "the chests are written");
        instance.unloadChunk(placed);
        instance.loadChunk(0, 0).join();
        assertNotEquals(Block.CHEST, instance.getBlock(3, FLOOR, CHEST_Z),
                "precondition: loaded from its overlay record, the chest has the empty NBT the record gives it");
        final long before = flags.stats().conflicts();

        flags.syncNow();
        mounted.env().tick();
        mounted.env().tick();

        assertEquals(0, flags.stats().conflicts() - before, warnings().toString());
        assertFalse(((SynxChunk) instance.getChunk(0, 0)).dirty(), "no chest set again");
        assertTrue(warnings().stream().noneMatch(line -> line.contains(CHEST)), warnings().toString());
    }

    /** The warning of a conflict tells what the cell held from what it is set to, down to the properties. */
    @Test
    public void aConflictWarningShowsTheStateFoundAndTheStateSet() throws Exception {
        row(GATE, "closed", 2);
        final WorldFlagsImpl flags = flags();
        flags.register(GATE, WorldFlagsReplayTest::gate);
        final Mounted mounted = mount(release, WRITABLE, flags);
        final InstanceContainer instance = mounted.instance();
        instance.setChunkUnloadGraceTicks(-1);
        flags.start(instance);

        instance.setBlock(GATE_X, FLOOR, GATE_Z, Block.IRON_BARS.withProperty("waterlogged", "true"));
        flags.syncNow();
        tickUntil(mounted.env(), () -> instance.getBlock(GATE_X, FLOOR, GATE_Z).equals(Block.IRON_BARS),
                Duration.ofSeconds(10), "the cell is set back");

        assertTrue(warnings().stream().anyMatch(line -> line.contains(GATE) && line.contains("waterlogged=true")
                && line.contains("waterlogged=false")), warnings().toString());
    }

    /** §19 「起服读不到标志即拒绝」. */
    @Test
    public void flagsThatCannotBeReadRefuseTheStartBeforeTheOverlayIsOpened() throws IOException {
        final Path old = Files.createDirectories(overlayRoot.resolve("fixture-000000000000-p776"));
        Files.writeString(old.resolve("00000001.seg"), "an old release");
        final WorldFlagStoreDbTest.Unreachable down = new WorldFlagStoreDbTest.Unreachable();
        final WorldFlagsImpl flags = track(new WorldFlagsImpl(down, RedisLink.disabled(REGION + "-c1"), MapFixture.MAP_ID,
                REGION, MapFixture.BOUNDS, List.of(Duration.ZERO, Duration.ZERO, Duration.ZERO)));

        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> mount(release, WRITABLE, flags));

        assertTrue(refused.getMessage().startsWith("synx-world-flags-unavailable: "), refused.getMessage());
        assertEquals(4, down.connections.get(), "1 read and 3 retries");
        assertTrue(Files.isRegularFile(old.resolve("00000001.seg")), "the old release's overlay is still there");
        assertFalse(Files.exists(overlayRoot.resolve(release.mapBuild())), "the overlay store was never opened");
    }

    /** §19 「只读世界里标志照常重放与应用」. */
    @Test
    public void aReadOnlyWorldStillTakesItsFlags() throws Exception {
        row(BRIDGE, "repaired", 3);
        final WorldFlagsImpl flags = flags();
        // One cell of the applier is past the bounds, in chunk -1,0, and one above the world height
        flags.register(BRIDGE, value -> {
            final List<FlagCell> cells = new ArrayList<>(bridge(value));
            cells.add(new FlagCell(-5, BRIDGE_Y, BRIDGE_Z, cells.getFirst().block()));
            cells.add(new FlagCell(1, 4_096, BRIDGE_Z, cells.getFirst().block()));
            return cells;
        });
        // The light recomputations never run: only a flag application could load a chunk past the bounds
        final Mounted mounted = mount(release, READ_ONLY, flags, _ -> {
        });
        final InstanceContainer instance = mounted.instance();
        instance.setChunkUnloadGraceTicks(-1);
        flags.start(instance);

        assertBridge(instance, Block.OAK_PLANKS);
        final WorldFlagsImpl other = flags();
        assertEquals(new FlagWrite.Written(4), other.set(BRIDGE, json("broken"), 3).join());
        flags.syncNow();
        tickUntil(mounted.env(), () -> new FlagValue(json("broken"), 4).equals(flags.get(BRIDGE)),
                Duration.ofSeconds(10), "the new value is applied");

        assertBridge(instance, Block.AIR);
        assertEquals(0, flags.stats().conflicts());
        instance.setBlock(3, BRIDGE_Y, BRIDGE_Z, Block.STONE);
        assertEquals(Block.AIR, instance.getBlock(3, BRIDGE_Y, BRIDGE_Z), "not in a flag application: read only");
        assertTrue(instance.getChunk(0, 0).isReadOnly());
        assertNull(instance.getChunk(-1, 0), "no application loaded the chunk past the bounds");
        instance.loadChunk(-1, 0).join();
        assertEquals(Block.AIR, instance.getBlock(-5, BRIDGE_Y, BRIDGE_Z), "past the bounds");
        assertTrue(warnings().stream().anyMatch(line -> line.contains(BRIDGE) && line.contains("2 cells outside")),
                warnings().toString());
    }

    /**
     * §19 「只读世界里标志改方块后光照照样装回」: the flag sets a glowstone in the middle of chunk 8,8 of
     * a world nothing else may change; the recomputation installs the light of its 3×3, and the four
     * neighbours the glowstone lights are materialized for it, which {@code world_writable} does not stop.
     */
    @Test
    public void aReadOnlyWorldTakesTheLightOfAFlagChange() throws Exception {
        row(LAMP, "lit", 1);
        final WorldFlagsImpl flags = flags();
        flags.register(LAMP, _ -> List.of(new FlagCell(8 * 16 + 8, FLOOR, 8 * 16 + 8, Block.GLOWSTONE)));
        final Mounted mounted = mount(release, READ_ONLY, flags);
        final InstanceContainer instance = mounted.instance();
        instance.setChunkUnloadGraceTicks(-1);
        assertEquals(Block.GLOWSTONE, instance.getBlock(8 * 16 + 8, FLOOR, 8 * 16 + 8));

        tickUntil(mounted.env(), () -> mounted.relight().stats().pending() == 0 && relit(instance, 9, 8),
                Duration.ofSeconds(20), "the light of the glowstone is installed");

        for (int x = 7; x <= 9; x++) {
            for (int z = 7; z <= 9; z++) {
                final SynxChunk chunk = (SynxChunk) instance.getChunk(x, z);
                assertNull(LightTruth.firstDifference(LightTruth.sent(chunk, Registries.vanilla()),
                        LightTruth.of(instance, x, z), x, z, 0, 4), "the light of " + x + "," + z);
            }
        }
        for (int[] side : new int[][]{{7, 8}, {9, 8}, {8, 7}, {8, 9}}) {
            final SynxChunk chunk = (SynxChunk) instance.getChunk(side[0], side[1]);
            assertTrue(chunk.dirty() && chunk.generation() >= 1,
                    "materialized for its light in a read only world: " + side[0] + "," + side[1]);
        }
    }

    private static boolean relit(InstanceContainer instance, int chunkX, int chunkZ) {
        return instance.getChunk(chunkX, chunkZ) instanceof SynxChunk chunk && LightTruth.firstDifference(
                LightTruth.sent(chunk, Registries.vanilla()), LightTruth.of(instance, chunkX, chunkZ), chunkX, chunkZ, 0, 4) == null;
    }

    /** §19 「同一个键的两次应用乱序时不回退」. */
    @Test
    public void anOlderApplicationScheduledAfterANewerOneChangesNothing() throws Exception {
        row(GATE, "closed", 2);
        final WorldFlagsImpl flags = flags();
        flags.register(GATE, WorldFlagsReplayTest::gate);
        final Mounted mounted = mount(release, WRITABLE, flags);
        final InstanceContainer instance = mounted.instance();
        instance.setChunkUnloadGraceTicks(-1);
        assertDoorway(instance, Block.IRON_BARS);
        // The light recomputations of a change hold the 5×5 around it until they are installed
        tickUntil(mounted.env(), () -> mounted.relight().stats().pending() == 0, Duration.ofSeconds(20),
                "the light of the replay is installed");

        flags.schedule(GATE, json("open"), 4);
        flags.schedule(GATE, json("closed"), 3);
        assertEquals(2, instance.chunkTicketCount(0, 0), "each application holds the chunk of the doorway");
        mounted.env().tick();
        tickUntil(mounted.env(), () -> mounted.relight().stats().pending() == 0, Duration.ofSeconds(20),
                "the light of the application is installed");

        assertDoorway(instance, Block.AIR);
        assertEquals(new FlagValue(json("open"), 4), flags.get(GATE));
        assertEquals(0, instance.chunkTicketCount(0, 0), "the dropped application gave its ticket back too");
        assertEquals(0, flags.stats().conflicts());
    }

    /**
     * §19 「跨频道同步与 Redis 不可用时收敛」 at start: a value another channel wrote after the read at start
     * and before the subscription is in neither the replay nor a message; the read of every flag on the
     * first tick after {@code start} applies it.
     */
    @Test
    public void aValueWrittenBeforeTheSubscriptionIsAppliedOnTheFirstTick() throws Exception {
        row(GATE, "closed", 2);
        final WorldFlagsImpl flags = flags();
        flags.register(GATE, WorldFlagsReplayTest::gate);
        final Mounted mounted = mount(release, WRITABLE, flags);
        final InstanceContainer instance = mounted.instance();
        instance.setChunkUnloadGraceTicks(-1);
        assertDoorway(instance, Block.IRON_BARS);
        database.execute("UPDATE world_flag SET value = '\"open\"', version = 3 WHERE flag_key = '" + GATE + "'");

        flags.start(instance);
        tickUntil(mounted.env(), () -> new FlagValue(json("open"), 3).equals(flags.get(GATE)), Duration.ofSeconds(10),
                "the first tick reads every flag");

        assertDoorway(instance, Block.AIR);
        assertTrue(flags.syncScheduled(), "and again every 60 s");
    }

    /**
     * Two keys must not give the same cell: one warning names both, and the key applied last holds the
     * cell, the same one on every channel because the read orders the rows by key.
     */
    @Test
    public void twoFlagsGivingTheSameCellAreAWarningAndTheLastKeyHoldsIt() throws Exception {
        // Written b before a: the read orders the rows by key
        row("quest:wall_b", "up", 1);
        row("quest:wall_a", "up", 1);
        final WorldFlagsImpl flags = flags();
        flags.register("quest:wall_a", _ -> List.of(new FlagCell(5, BRIDGE_Y, BRIDGE_Z, Block.OAK_PLANKS),
                new FlagCell(6, BRIDGE_Y, BRIDGE_Z, Block.OAK_PLANKS)));
        flags.register("quest:wall_b", _ -> List.of(new FlagCell(6, BRIDGE_Y, BRIDGE_Z, Block.STONE),
                new FlagCell(7, BRIDGE_Y, BRIDGE_Z, Block.STONE)));

        final Mounted mounted = mount(release, WRITABLE, flags);

        assertEquals(Block.STONE, mounted.instance().getBlock(6, BRIDGE_Y, BRIDGE_Z), "quest:wall_b is applied last");
        assertEquals(Block.OAK_PLANKS, mounted.instance().getBlock(5, BRIDGE_Y, BRIDGE_Z));
        assertEquals(2, flags.stats().replayed());
        assertEquals(1, warnings().stream().filter(line -> line.startsWith(
                "World flags give the same cells, the one applied last holds them: [quest:wall_b over quest:wall_a 1 cells]"))
                .count(), warnings().toString());
    }

    /**
     * §28.2 「标志的测试缺口」 an applier that throws at run time, here because it builds a cell of the null
     * {@code Block.fromKey} gives for a name it does not know: one ERROR names the key and the version, the
     * flag stays where it was and the ticks go on; another value that throws changes nothing either.
     */
    @Test
    public void anApplierThatThrowsAtRunTimeLeavesTheFlagWhereItWas() throws Exception {
        row(GATE, "closed", 2);
        final WorldFlagsImpl flags = flags();
        flags.register(GATE, value -> switch (value.getAsString()) {
            case "rusted" -> List.of(new FlagCell(GATE_X, FLOOR, GATE_Z, Block.fromKey("minecraft:iron_bar")));
            case "jammed" -> throw new IllegalStateException("the test applier throws on jammed");
            default -> gate(value);
        });
        final Mounted mounted = mount(release, WRITABLE, flags);
        final InstanceContainer instance = mounted.instance();
        instance.setChunkUnloadGraceTicks(-1);
        final WorldFlagsImpl other = flags();
        final FlagValue closed = new FlagValue(json("closed"), 2);

        assertEquals(new FlagWrite.Written(3), other.set(GATE, json("rusted"), 2).join());
        flags.syncNow();
        mounted.env().tick();
        mounted.env().tick();

        assertEquals(closed, flags.get(GATE));
        assertDoorway(instance, Block.IRON_BARS);
        final List<ILoggingEvent> errors = errors();
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.getFirst().getFormattedMessage().startsWith(
                "The applier of world flag quest:gate failed on version 3, the flag stays at version 2"), errors.toString());
        assertEquals(NullPointerException.class.getName(), errors.getFirst().getThrowableProxy().getClassName());

        assertEquals(new FlagWrite.Written(4), other.set(GATE, json("jammed"), 3).join());
        flags.syncNow();
        mounted.env().tick();
        mounted.env().tick();

        assertEquals(closed, flags.get(GATE));
        assertDoorway(instance, Block.IRON_BARS);
    }

    /** A block handler that throws while the replay sets its cell: one ERROR names the key, the start goes on. */
    @Test
    public void aHandlerThatThrowsInTheReplaySkipsItsFlagAndTheStartGoesOn() throws Exception {
        row(GATE, "closed", 2);
        row(LAMP, "lit", 1);
        final WorldFlagsImpl flags = flags();
        flags.register(GATE, WorldFlagsReplayTest::gate);
        flags.register(LAMP, _ -> List.of(new FlagCell(3, FLOOR, 14, Block.STONE.withHandler(new FaultyHandler()))));

        final Mounted mounted = mount(release, WRITABLE, flags);

        assertDoorway(mounted.instance(), Block.IRON_BARS);
        assertEquals(1, flags.stats().replayed(), "quest:gate only");
        assertNull(flags.get(LAMP));
        final List<ILoggingEvent> errors = errors();
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.getFirst().getFormattedMessage().startsWith("The world flags [" + LAMP + "] failed in the replay"),
                errors.toString());
    }

    /** A handler whose {@code onPlace} throws. */
    private static final class FaultyHandler implements BlockHandler {
        @Override
        public Key getKey() {
            return Key.key("synaxis_test", "faulty");
        }

        @Override
        public void onPlace(BlockHandler.Placement placement) {
            throw new IllegalStateException("the test handler throws on place");
        }
    }

    /**
     * §28.2 「标志的测试缺口」 the check after a read takes 1,024 governed cells a tick, chunk by chunk: 1,280
     * cells of chunks 0,0 to 4,0 changed around the protection are set back over two ticks, the four
     * chunks 0,0 to 3,0 on the first.
     */
    @Test
    public void theCheckSetsBack1024CellsATickChunkByChunk() throws Exception {
        final String deck = "quest:deck";
        row(deck, "laid", 1);
        final WorldFlagsImpl flags = flags();
        flags.register(deck, _ -> IntStream.range(0, 80 * 16)
                .mapToObj(i -> new FlagCell(i / 16, BRIDGE_Y, i % 16, Block.OAK_PLANKS)).toList());
        final Mounted mounted = mount(release, WRITABLE, flags);
        final InstanceContainer instance = mounted.instance();
        instance.setChunkUnloadGraceTicks(-1);
        for (int x = 0; x < 80; x++) {
            for (int z = 0; z < 16; z++) instance.setBlock(x, BRIDGE_Y, z, Block.AIR);
        }
        final long before = flags.stats().conflicts();

        flags.syncNow();
        mounted.env().tick();

        assertEquals(WorldFlagsImpl.CHECKED_PER_TICK, flags.stats().conflicts() - before);
        for (int x = 0; x < 80; x++) {
            for (int z = 0; z < 16; z++) {
                assertEquals(x < 64 ? Block.OAK_PLANKS : Block.AIR, instance.getBlock(x, BRIDGE_Y, z),
                        "the cell " + x + " " + z);
            }
        }

        mounted.env().tick();

        assertEquals(80 * 16, flags.stats().conflicts() - before);
        for (int x = 64; x < 80; x++) {
            for (int z = 0; z < 16; z++) assertEquals(Block.OAK_PLANKS, instance.getBlock(x, BRIDGE_Y, z));
        }
    }

    /** §28.2 「标志的测试缺口」 the 5th failed read in a row is the first warning. */
    @Test
    public void theFifthFailedReadInARowIsTheFirstWarning() {
        final WorldFlagStoreDbTest.Unreachable down = new WorldFlagStoreDbTest.Unreachable();
        final WorldFlagsImpl flags = track(new WorldFlagsImpl(down, RedisLink.disabled(REGION + "-c1"), MapFixture.MAP_ID,
                REGION, MapFixture.BOUNDS));
        for (int read = 1; read < WorldFlagsImpl.READ_FAILURES_WARNED; read++) flags.syncNow();
        assertEquals(List.of(), warnings(), "4 failed reads");

        flags.syncNow();

        assertEquals(1, warnings().size(), warnings().toString());
        assertTrue(warnings().getFirst().startsWith("The world flags of map fixture region r0 were not read 5 times in a "
                + "row: java.sql.SQLException: the test cut the database off"), warnings().toString());
        assertEquals(5, down.connections.get());
    }

    /**
     * A value PostgreSQL takes and Gson cannot read back, nested past its 255 levels, fails the read at
     * start like a database that does not answer, naming its key.
     */
    @Test
    public void aValueTooDeepToParseRefusesTheStartNamingItsKey() throws Exception {
        pool();
        database.execute("INSERT INTO world_flag (map_id, region_id, flag_key, value, version) VALUES ('"
                + MapFixture.MAP_ID + "', '" + REGION + "', 'quest:deep', '" + "[".repeat(300) + "]".repeat(300) + "', 1)");
        final WorldFlagsImpl flags = track(new WorldFlagsImpl(pool(), RedisLink.disabled(REGION + "-c1"), MapFixture.MAP_ID,
                REGION, MapFixture.BOUNDS, List.of(Duration.ZERO, Duration.ZERO, Duration.ZERO)));

        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, flags::load);

        assertTrue(refused.getMessage().startsWith("synx-world-flags-unavailable: "), refused.getMessage());
        assertTrue(refused.getMessage().contains("the value of world flag quest:deep does not parse"), refused.getMessage());
    }

    /** {@code get} hands out a copy and {@code set} keeps one: changing either object afterwards changes nothing applied. */
    @Test
    public void getAndSetHandOutAndKeepCopiesOfTheValue() throws Exception {
        pool();
        database.execute("INSERT INTO world_flag (map_id, region_id, flag_key, value, version) VALUES ('"
                + MapFixture.MAP_ID + "', '" + REGION + "', '" + GATE + "', '{\"state\": \"closed\"}', 1)");
        final WorldFlagsImpl flags = flags();
        flags.register(GATE, value -> gate(value.getAsJsonObject().get("state")));
        final Mounted mounted = mount(release, WRITABLE, flags);
        mounted.instance().setChunkUnloadGraceTicks(-1);

        final JsonElement read = flags.get(GATE).value();
        read.getAsJsonObject().addProperty("state", "open");
        assertEquals("{\"state\":\"closed\"}", flags.get(GATE).value().toString(), "the object get gave is changed");

        assertEquals(new FlagWrite.Written(2), flags.set(GATE, read, 1).join());
        read.getAsJsonObject().addProperty("state", "jammed");
        tickUntil(mounted.env(), () -> flags.get(GATE).version() == 2, Duration.ofSeconds(10), "the write is applied");

        assertEquals("{\"state\":\"open\"}", flags.get(GATE).value().toString(), "the object set took is changed");
        assertDoorway(mounted.instance(), Block.AIR);
    }

    /** The warning of a conflict writes a non-empty NBT as SNBT, and an empty one not at all. */
    @Test
    public void aConflictWarningWritesTheNbtAsSnbtAndNoEmptyOne() throws Exception {
        row(CHEST, "placed", 1);
        final WorldFlagsImpl flags = flags();
        flags.register(CHEST, WorldFlagsReplayTest::chests);
        final Mounted mounted = mount(release, WRITABLE, flags);
        final InstanceContainer instance = mounted.instance();
        instance.setChunkUnloadGraceTicks(-1);

        instance.setBlock(5, FLOOR, CHEST_Z, Block.CHEST.withNbt(CompoundBinaryTag.empty()));
        flags.syncNow();
        mounted.env().tick();

        final List<String> lines = warnings().stream().filter(line -> line.contains(CHEST)).toList();
        assertEquals(1, lines.size(), warnings().toString());
        final String line = lines.getFirst();
        assertTrue(line.contains("synaxis_note:") && line.contains("kept"), line);
        assertFalse(line.contains("BinaryTag") || line.contains("{}"), line);
    }

    private List<ILoggingEvent> errors() {
        return log.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
    }
}
