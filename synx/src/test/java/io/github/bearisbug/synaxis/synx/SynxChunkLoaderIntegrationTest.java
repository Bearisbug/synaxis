package io.github.bearisbug.synaxis.synx;

import ch.qos.logback.classic.Level;
import io.github.bearisbug.synaxis.coordinate.BlockFace;
import io.github.bearisbug.synaxis.coordinate.BlockVec;
import io.github.bearisbug.synaxis.coordinate.Direction;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.entity.GameMode;
import io.github.bearisbug.synaxis.entity.Metadata;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.entity.metadata.display.AbstractDisplayMeta;
import io.github.bearisbug.synaxis.entity.metadata.display.BlockDisplayMeta;
import io.github.bearisbug.synaxis.entity.metadata.display.ItemDisplayMeta;
import io.github.bearisbug.synaxis.entity.metadata.display.TextDisplayMeta;
import io.github.bearisbug.synaxis.entity.metadata.other.ArmorStandMeta;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.DynamicChunk;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerActionPacket;
import io.github.bearisbug.synaxis.network.packet.server.BufferedPacket;
import io.github.bearisbug.synaxis.network.packet.server.CachedPacket;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.AcknowledgeBlockChangePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.BlockChangePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.data.LightData;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.tag.Tag;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.world.biome.Biome;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/DESIGN-M3.md} §19 {@code REQ-M3-008}, the scenarios of track A whose entry is a
 * {@code :synx} integration test: a test process mounts the {@link SynxTestMap} with a
 * {@link SynxChunkLoader} and an overlay store in memory.
 */
@EnvTest
public class SynxChunkLoaderIntegrationTest {

    /** 场景「未改区块零编码发送」. */
    @Test
    public void untouchedChunksGoOutAsTheirSegmentOne() {
        final Env env = Env.create(builder -> builder.chunkViewDistance(12));
        try {
            final SynxTestMap map = SynxTestMap.get();
            final SynxChunkLoader loader = SynxWorlds.loader(SynxWorlds.WRITABLE, new MemoryOverlay());
            final Instance instance = SynxWorlds.instance(env.process(), loader);
            final RecordingConnection connection = new RecordingConnection(env.process());
            final Player player = RecordingConnection.connect(env.process(), connection, instance,
                    new Pos(16 * 16 + 8, 190, 16 * 16 + 8));
            SynxWorlds.tickUntil(env, () -> connection.buffered().size() >= 729, () -> player.onChunkBatchReceived(64));

            final List<BufferedPacket> packets = connection.buffered();
            assertEquals(729, packets.size(), "the ring of radius 13");
            final Set<Long> chunks = new HashSet<>();
            long bytes = 0;
            for (BufferedPacket packet : packets) {
                assertFalse(packet.sheddable(), "a chunk packet the backlog may not shed");
                final byte[] sent = RecordingConnection.bytes(packet);
                final int[] chunk = SynxWorlds.chunkOf(sent);
                assertTrue(Math.abs(chunk[0] - 16) <= 13 && Math.abs(chunk[1] - 16) <= 13, Arrays.toString(chunk));
                assertTrue(chunks.add(((long) chunk[0] << 32) | (chunk[1] & 0xFFFFFFFFL)), "sent once: " + Arrays.toString(chunk));
                assertArrayEquals(map.record(chunk[0], chunk[1]).packet(), sent, "segment ① of " + Arrays.toString(chunk));
                bytes += sent.length;
                final SynxChunk loaded = assertInstanceOf(SynxChunk.class, instance.getChunk(chunk[0], chunk[1]));
                assertEquals(SynxChunk.Mode.HANDLE, loaded.mode());
            }
            assertTrue(connection.packets(ChunkDataPacket.class).isEmpty(), "no chunk packet as an object");

            final SynxStreamStats stats = loader.stats();
            assertEquals(729, stats.handleSent());
            assertEquals(0, stats.overlaySent());
            assertEquals(bytes, stats.bytesSent());
            assertEquals(0, stats.failed());
            assertEquals(2, stats.regionsMapped(), "view_bounds ∩ the release reaches region 0,0 and 1,0");
        } finally {
            env.process().stop();
        }
    }

    /** 场景「世界不可写时改方块不生效」. */
    @Test
    public void aReadOnlyWorldIgnoresEveryChange(Env env) {
        final SynxChunkLoader loader = SynxWorlds.loader(SynxWorlds.READ_ONLY, new MemoryOverlay());
        final Instance instance = SynxWorlds.instance(env.process(), loader);
        final RecordingConnection connection = new RecordingConnection(env.process());
        final Player player = RecordingConnection.connect(env.process(), connection, instance,
                new Pos(16 * 16 + 8, 190, 16 * 16 + 8));
        player.setGameMode(GameMode.CREATIVE);
        final SynxChunk chunk = (SynxChunk) instance.loadChunk(17, 16).join();
        final int x = 17 * 16 + 3, z = 16 * 16 + 3;
        final int y = surface(instance, x, z);
        final Block before = instance.getBlock(x, y, z);
        final RegistryKey<Biome> biomeBefore = instance.getBiome(x, y, z);
        final RegistryKey<Biome> otherBiome = biomeBefore.equals(Biome.DESERT) ? Biome.PLAINS : Biome.DESERT;
        connection.sent.clear();

        instance.setBlock(x, y, z, Block.GOLD_BLOCK);
        instance.setBiome(x, y, z, otherBiome);
        chunk.lockWriteLock();
        try {
            chunk.setBlock(x, y, z, Block.DIAMOND_BLOCK);
        } finally {
            chunk.unlockWriteLock();
        }
        assertEquals(before, instance.getBlock(x, y, z));
        assertEquals(biomeBefore, instance.getBiome(x, y, z));
        assertEquals(SynxChunk.Mode.HANDLE, chunk.mode());
        assertTrue(chunk.isReadOnly());

        // The player digs another block of the chunk
        final int digX = x + 5, digZ = z + 5, digY = surface(instance, digX, digZ);
        final Block dug = instance.getBlock(digX, digY, digZ);
        player.addPacketToQueue(new ClientPlayerActionPacket(ClientPlayerActionPacket.Status.STARTED_DIGGING,
                new BlockVec(digX, digY, digZ), BlockFace.TOP, 42));
        SynxWorlds.tickUntil(env, () -> !connection.packets(AcknowledgeBlockChangePacket.class).isEmpty(), () -> {
        });
        assertEquals(dug, instance.getBlock(digX, digY, digZ), "the dug block is still there");
        assertEquals(42, connection.packets(AcknowledgeBlockChangePacket.class).getFirst().sequence(),
                "the client is told to roll its prediction back to the block it had");
        assertTrue(connection.packets(BlockChangePacket.class).isEmpty(), "no block update from any of the calls");
        assertEquals(SynxChunk.Mode.HANDLE, chunk.mode());
    }

    /** 场景「邻区边缘区块只读」. */
    @Test
    public void neighbourRegionChunksAreReadOnly() {
        final Env env = Env.create(builder -> builder.chunkViewDistance(12));
        try {
            final SynxWorlds.Ticker ticker = new SynxWorlds.Ticker(SynxTestMap.TICKER_HANDLER);
            env.process().block().registerHandler(SynxTestMap.TICKER_HANDLER, () -> ticker);
            final MemoryOverlay overlay = new MemoryOverlay();
            final SynxChunkLoader loader = SynxWorlds.loader(SynxWorlds.WRITABLE, overlay);
            final Instance instance = SynxWorlds.instance(env.process(), loader);
            final RecordingConnection connection = new RecordingConnection(env.process());
            final Player player = RecordingConnection.connect(env.process(), connection, instance,
                    new Pos(31 * 16 + 8, 190, 5 * 16 + 8));
            final int ax = SynxTestMap.NEIGHBOUR_CHUNK_X, az = SynxTestMap.NEIGHBOUR_CHUNK_Z;
            SynxWorlds.tickUntil(env, () -> connection.buffered().stream()
                    .anyMatch(packet -> Arrays.equals(SynxWorlds.chunkOf(RecordingConnection.bytes(packet)), new int[]{ax, az})),
                    () -> player.onChunkBatchReceived(64));
            final int findsBefore = overlay.finds.get();

            final SynxChunk a = (SynxChunk) instance.getChunk(ax, az);
            assertNotNull(a);
            assertEquals(SynxChunk.Mode.HANDLE, a.mode());
            assertTrue(a.isReadOnly());
            final int x = SynxTestMap.NEIGHBOUR_TICKER_X, z = SynxTestMap.NEIGHBOUR_TICKER_Z;
            instance.setBlock(x + 1, SynxTestMap.FEATURE_Y, z, Block.GOLD_BLOCK);
            for (int i = 0; i < 100; i++) env.tick();

            assertEquals(Block.AIR, instance.getBlock(x + 1, SynxTestMap.FEATURE_Y, z));
            assertEquals(SynxChunk.Mode.HANDLE, a.mode());
            assertTrue(instance.getChunkEntities(a).stream().allMatch(Player.class::isInstance),
                    "no static entity outside the region");
            assertEquals(0, ticker.ticksAt(x, z), "the handler of a read only neighbour chunk does not tick");
            assertTrue(overlay.events.isEmpty());
            assertEquals(findsBefore, overlay.finds.get(), "chunks outside the region never ask the overlay");
        } finally {
            env.process().stop();
        }
    }

    /**
     * 场景「view_bounds 外是引擎建的空气区块」, and the chunks of view_bounds without a region file. The
     * client asks for 20 chunks; the map instance starts at 12, as a map shard runs, and the test turns
     * it up to 20 at run time.
     */
    @Test
    public void outsideTheViewBoundsTheEngineBuildsAir() {
        final Env env = Env.create(builder -> builder.chunkViewDistance(20));
        try {
            final SynxChunkLoader loader = SynxWorlds.loader(SynxWorlds.WRITABLE, new MemoryOverlay());
            final Instance instance = SynxWorlds.instance(env.process(), loader);
            instance.viewDistance(12);
            final RecordingConnection connection = new RecordingConnection(env.process());
            final Player player = RecordingConnection.connect(env.process(), connection, instance,
                    new Pos(31 * 16 + 8, 190, 16 * 16 + 8));
            final int bx = 31 + 18;
            SynxWorlds.tickUntil(env, () -> instance.getChunk(31 + 13, 16) != null, () -> player.onChunkBatchReceived(64));
            assertNull(instance.getChunk(bx, 16), "the ring of radius 13 does not reach 18 chunks out");

            instance.viewDistance(20);
            SynxWorlds.tickUntil(env, () -> instance.getChunk(bx, 16) != null && instance.getChunk(45, 16) != null
                    && instance.getChunk(40, 35) != null, () -> player.onChunkBatchReceived(64));

            final Chunk b = instance.getChunk(bx, 16);
            assertInstanceOf(DynamicChunk.class, b);
            assertFalse(b instanceof SynxChunk, "18 chunks out is past view_bounds, 17 chunks out");
            assertEquals(Block.AIR, instance.getBlock(bx * 16, 64, 16 * 16));
            final Chunk noRegionFile = instance.getChunk(40, 35);
            assertFalse(noRegionFile instanceof SynxChunk, "inside view_bounds, but the release has no region 1,1");
            final SynxChunk inside = assertInstanceOf(SynxChunk.class, instance.getChunk(45, 16));
            assertEquals(SynxChunk.Mode.HANDLE, inside.mode());
            assertTrue(inside.isReadOnly());
            assertEquals(0, loader.stats().failed());
            assertEquals(2, loader.stats().regionsMapped());
        } finally {
            env.process().stop();
        }
    }

    /** 场景「记录损坏的区块发空气占位」: what the player's connection is sent for it, framed as the write thread frames it. */
    @Test
    public void aCorruptRecordLoadsAsLitAir(Env env, @TempDir Path dir) throws Exception {
        final Path build = SynxTestMap.get().copyTo(dir);
        final int cx = 20, cz = 20;
        corrupt(SynxTestMap.regionFile(build, cx, cz), cx, cz);
        final SynxChunkLoader loader = SynxWorlds.loader(build, SynxTestMap.REGION, SynxWorlds.WRITABLE, new MemoryOverlay());
        final Instance instance = SynxWorlds.instance(env.process(), loader);
        final ChunkDataPacket packet;
        try (LogCapture logs = LogCapture.of(SynxChunkLoader.class)) {
            final RecordingConnection connection = new RecordingConnection(env.process());
            final Player player = RecordingConnection.connect(env.process(), connection, instance,
                    new Pos(cx * 16 + 8, 190, cz * 16 + 8));
            SynxWorlds.tickUntil(env, () -> sentFor(connection, cx, cz) != null, () -> player.onChunkBatchReceived(64));
            packet = SynxChunkPacket.decode(RecordingConnection.framed(Objects.requireNonNull(sentFor(connection, cx, cz))),
                    SynxTestMap.REGISTRIES);
            final List<String> errors = logs.messagesAt(Level.ERROR);
            assertEquals(1, errors.size(), errors.toString());
            assertTrue(errors.getFirst().contains(cx + "," + cz) && errors.getFirst().contains("crc32c"), errors.getFirst());
        }
        final SynxChunk c = assertInstanceOf(SynxChunk.class, instance.getChunk(cx, cz));
        assertEquals(SynxChunk.Mode.FAILED, c.mode());
        assertTrue(c.isReadOnly());
        final LightData light = packet.lightData();
        final BitSet sections = new BitSet();
        sections.set(1, SynxTestMap.HEIGHT / 16 + 1);
        assertEquals(sections, light.skyMask(), "sky light in every section");
        assertTrue(light.emptySkyMask().isEmpty());
        for (byte[] array : light.skyLight()) {
            final byte[] full = new byte[2048];
            Arrays.fill(full, (byte) 0xFF);
            assertArrayEquals(full, array, "sky light 15 everywhere");
        }
        assertEquals(Block.AIR, instance.getBlock(cx * 16, 64, cz * 16));
        instance.setBlock(cx * 16, 64, cz * 16, Block.STONE);
        assertEquals(Block.AIR, instance.getBlock(cx * 16, 64, cz * 16), "read only");

        final SynxChunk neighbour = (SynxChunk) instance.loadChunk(cx + 1, cz).join();
        assertEquals(SynxChunk.Mode.HANDLE, neighbour.mode(), "the rest of the file reads");
        assertEquals(1, loader.stats().failed());
    }

    /** A chunk of the region with no record fails too; the test map has one for every chunk it bakes. */
    @Test
    public void aRegionChunkWithoutARecordFails(Env env) {
        final SynxManifest.Bounds larger = new SynxManifest.Bounds(0, 0, 31, 40);
        final SynxChunkLoader loader = SynxWorlds.loader(SynxTestMap.get().buildDir, larger, SynxWorlds.WRITABLE,
                new MemoryOverlay());
        final Instance instance = SynxWorlds.instance(env.process(), loader);
        final SynxChunk chunk = assertInstanceOf(SynxChunk.class, instance.loadChunk(5, 35).join());
        assertEquals(SynxChunk.Mode.FAILED, chunk.mode());
        assertEquals(1, loader.stats().failed());
    }

    /** 场景「会 tick 的 handler 在句柄区块里照常 tick」. */
    @Test
    public void tickingHandlersTickInHandleMode(Env env) {
        final SynxWorlds.Ticker ticker = new SynxWorlds.Ticker(SynxTestMap.TICKER_HANDLER);
        env.process().block().registerHandler(SynxTestMap.TICKER_HANDLER, () -> ticker);
        final Instance instance = SynxWorlds.instance(env.process(), SynxWorlds.loader(SynxWorlds.WRITABLE, new MemoryOverlay()));
        final SynxChunk chunk = (SynxChunk) instance.loadChunk(SynxTestMap.TICKER_CHUNK_X, SynxTestMap.TICKER_CHUNK_Z).join();
        for (int i = 0; i < 20; i++) env.tick();
        assertEquals(20, ticker.ticksAt(SynxTestMap.TICKER_X, SynxTestMap.TICKER_Z));
        assertEquals(SynxChunk.Mode.HANDLE, chunk.mode());
        final Block block = instance.getBlock(SynxTestMap.TICKER_X, SynxTestMap.FEATURE_Y, SynxTestMap.TICKER_Z);
        assertEquals(Block.STONE.stateId(), block.stateId());
        assertEquals(ticker, block.handler());
    }

    /**
     * A block entity's handler is taken from the {@code BlockManager} once per record: with a
     * registration that makes a new handler on every call, {@code getBlock}, the tick and the overlay
     * entries still share one instance.
     */
    @Test
    public void aBlockEntityKeepsOneHandlerInstance(Env env) {
        final List<SynxWorlds.Ticker> made = new CopyOnWriteArrayList<>();
        env.process().block().registerHandler(SynxTestMap.TICKER_HANDLER, () -> {
            final SynxWorlds.Ticker ticker = new SynxWorlds.Ticker(SynxTestMap.TICKER_HANDLER);
            made.add(ticker);
            return ticker;
        });
        final Instance instance = SynxWorlds.instance(env.process(), SynxWorlds.loader(SynxWorlds.WRITABLE, new MemoryOverlay()));
        final SynxChunk chunk = (SynxChunk) instance.loadChunk(SynxTestMap.TICKER_CHUNK_X, SynxTestMap.TICKER_CHUNK_Z).join();
        final int x = SynxTestMap.TICKER_X, y = SynxTestMap.FEATURE_Y, z = SynxTestMap.TICKER_Z;
        final SynxWorlds.Ticker handler = assertInstanceOf(SynxWorlds.Ticker.class, instance.getBlock(x, y, z).handler());
        assertSame(handler, instance.getBlock(x, y, z).handler(), "read twice");
        for (int i = 0; i < 5; i++) env.tick();
        assertEquals(5, handler.ticksAt(x, z), "the instance getBlock returns is the one that ticks");

        instance.setBlock(x + 2, y, z, Block.STONE);
        assertEquals(SynxChunk.Mode.OVERLAY, chunk.mode());
        assertSame(handler, instance.getBlock(x, y, z).handler(), "materialized");
        env.tick();
        assertEquals(6, handler.ticksAt(x, z));
        assertEquals(1, made.size(), made.toString());
    }

    /** 场景「名单外的静态实体被跳过」. */
    @Test
    public void staticEntitiesOutsideTheListAreSkipped(Env env) {
        final Instance instance = SynxWorlds.instance(env.process(), SynxWorlds.loader(SynxWorlds.WRITABLE, new MemoryOverlay()));
        final SynxChunk chunk;
        try (LogCapture logs = LogCapture.of(SynxStaticEntities.class)) {
            chunk = (SynxChunk) instance.loadChunk(SynxTestMap.STATICS_CHUNK_X, SynxTestMap.STATICS_CHUNK_Z).join();
            final List<String> warnings = logs.messagesAt(Level.WARN);
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.getFirst().contains("minecraft:minecart"), warnings.getFirst());
        }
        assertEquals(SynxChunk.Mode.HANDLE, chunk.mode());
        final Set<Entity> entities = instance.getChunkEntities(chunk);
        assertEquals(1, entities.size(), entities.toString());
        final Entity frame = entities.iterator().next();
        assertEquals(EntityType.ITEM_FRAME, frame.getEntityType());
        final CompoundBinaryTag source = SynxTestMap.itemFrame(SynxTestMap.STATICS_CHUNK_X * 16 + 2.5,
                SynxTestMap.FEATURE_Y, SynxTestMap.STATICS_CHUNK_Z * 16 + 2.5);
        assertEquals(source, frame.getTag(Tag.NBT("synx_source")));
        assertEquals(new Pos(SynxTestMap.STATICS_CHUNK_X * 16 + 2.5, SynxTestMap.FEATURE_Y,
                SynxTestMap.STATICS_CHUNK_Z * 16 + 2.5, 90f, 0f), frame.getPosition());
    }

    /** The mapped fields of the six covered types, {@code docs/DESIGN-M3.md} §28.1 静态实体还原范围. */
    @Test
    public void staticEntitiesKeepTheMappedFields(Env env) {
        final Instance instance = SynxWorlds.instance(env.process(), SynxWorlds.loader(SynxWorlds.WRITABLE, new MemoryOverlay()));
        final Chunk chunk = instance.loadChunk(SynxTestMap.MAPPED_CHUNK_X, SynxTestMap.MAPPED_CHUNK_Z).join();
        final Set<Entity> entities = instance.getChunkEntities(chunk);
        assertEquals(5, entities.size(), entities.toString());
        for (Entity entity : entities) {
            final CompoundBinaryTag source = (CompoundBinaryTag) entity.getTag(Tag.NBT("synx_source"));
            assertNotNull(source);
            assertMapped(source, entity);
        }
    }

    /** API-M3-005 加载结果表, first row: the overlay comes first and {@code generation} goes on from it. */
    @Test
    public void theOverlayComesFirstAndItsGenerationGoesOn(Env env) {
        final SynxTestMap map = SynxTestMap.get();
        final int cx = 3, cz = 3, x = cx * 16 + 1, z = cz * 16 + 1;
        // An overlay record of chunk 3,3 with a gold block on it, at generation 5
        final MemoryOverlay first = new MemoryOverlay();
        final Instance writer = SynxWorlds.instance(env.process(), SynxWorlds.loader(SynxWorlds.WRITABLE, first));
        final SynxChunk original = (SynxChunk) writer.loadChunk(cx, cz).join();
        writer.setBlock(x, SynxTestMap.FEATURE_Y, z, Block.GOLD_BLOCK);
        final OverlayEncoding encoding = original.encodeOverlay(map.settings(), System.nanoTime() + 1_000_000_000L);
        assertNotNull(encoding);

        final MemoryOverlay overlay = new MemoryOverlay();
        overlay.put(MemoryOverlay.hit(encoding.record(), 5, 3, 4096));
        final Instance instance = SynxWorlds.instance(env.process(), SynxWorlds.loader(SynxWorlds.WRITABLE, overlay));
        final SynxChunk chunk = (SynxChunk) instance.loadChunk(cx, cz).join();
        assertEquals(SynxChunk.Mode.HANDLE, chunk.mode());
        assertEquals(5, chunk.generation());
        assertFalse(chunk.dirty());
        assertEquals(Block.GOLD_BLOCK, instance.getBlock(x, SynxTestMap.FEATURE_Y, z), "read from the overlay record");
        final byte[] sent = RecordingConnection.bytes((BufferedPacket) chunk.getFullDataPacket());
        assertArrayEquals(encoding.record().packet(), sent);

        instance.setBlock(x + 1, SynxTestMap.FEATURE_Y, z, Block.DIAMOND_BLOCK);
        assertEquals(SynxChunk.Mode.OVERLAY, chunk.mode());
        assertEquals(6, chunk.generation());
        assertTrue(chunk.dirty());
        assertEquals(Block.GOLD_BLOCK, instance.getBlock(x, SynxTestMap.FEATURE_Y, z), "materialized from the overlay record");
        assertEquals(List.of(new MemoryOverlay.Event("materialized", cx, cz, 5, false),
                new MemoryOverlay.Event("modified", cx, cz, 6, true)), overlay.events);
    }

    /** Handle mode reads the same blocks and biomes as the materialized chunk, block entities included. */
    @Test
    public void handleModeReadsWhatTheChunkHolds(Env env) {
        final Instance instance = SynxWorlds.instance(env.process(), SynxWorlds.loader(SynxWorlds.WRITABLE, new MemoryOverlay()));
        for (int[] at : new int[][]{{SynxTestMap.SIGN_CHUNK_X, SynxTestMap.SIGN_CHUNK_Z}, {12, 20}, {30, 1}}) {
            final SynxChunk chunk = (SynxChunk) instance.loadChunk(at[0], at[1]).join();
            final Block[] handle = new Block[16 * 16 * SynxTestMap.HEIGHT];
            final Object[] handleBiomes = new Object[handle.length];
            readAll(chunk, handle, handleBiomes);
            chunk.lockWriteLock();
            try {
                chunk.setBlock(chunk.getChunkX() * 16, 0, chunk.getChunkZ() * 16,
                        chunk.getBlock(chunk.getChunkX() * 16, 0, chunk.getChunkZ() * 16));
            } finally {
                chunk.unlockWriteLock();
            }
            assertEquals(SynxChunk.Mode.OVERLAY, chunk.mode());
            final Block[] overlay = new Block[handle.length];
            final Object[] overlayBiomes = new Object[handle.length];
            readAll(chunk, overlay, overlayBiomes);
            assertArrayEquals(overlay, handle, "chunk " + Arrays.toString(at));
            assertArrayEquals(overlayBiomes, handleBiomes, "chunk " + Arrays.toString(at));
        }
    }

    /** The fields §28.1 lists for the type of {@code source} are on {@code entity}. */
    private static void assertMapped(CompoundBinaryTag source, Entity entity) {
        final ListBinaryTag pos = source.getList("Pos");
        final ListBinaryTag rotation = source.getList("Rotation");
        assertEquals(new Pos(pos.getDouble(0), pos.getDouble(1), pos.getDouble(2), rotation.getFloat(0),
                rotation.getFloat(1)), entity.getPosition());
        final Map<Integer, Metadata.Entry<?>> metadata = entity.getMetadataPacket().entries();
        final ItemStack diamond = ItemStack.of(Material.DIAMOND);
        switch (source.getString("id")) {
            case "minecraft:glow_item_frame" -> {
                assertEquals(EntityType.GLOW_ITEM_FRAME, entity.getEntityType());
                assertEquals(5, ((SynxStaticEntity) entity).objectData(), "facing east, the spawn data");
                assertEquals(Direction.EAST, metadata.get(8).value());
                assertEquals(diamond, metadata.get(9).value());
                assertEquals(7, metadata.get(10).value());
                assertTrue(entity.isInvisible());
            }
            case "minecraft:text_display" -> {
                final TextDisplayMeta meta = (TextDisplayMeta) entity.getEntityMeta();
                assertEquals(Component.text("Welcome"), meta.getText());
                assertEquals(AbstractDisplayMeta.BillboardConstraints.CENTER, meta.getBillboardRenderConstraints());
                assertEquals(0x7F00FF00, meta.getBackgroundColor());
            }
            case "minecraft:item_display" -> assertEquals(diamond, ((ItemDisplayMeta) entity.getEntityMeta()).getItemStack());
            case "minecraft:block_display" -> assertEquals(Block.OAK_STAIRS.withProperty("facing", "east"),
                    ((BlockDisplayMeta) entity.getEntityMeta()).getBlockStateId());
            case "minecraft:armor_stand" -> {
                final ArmorStandMeta meta = (ArmorStandMeta) entity.getEntityMeta();
                assertTrue(meta.isSmall());
                assertTrue(meta.isHasArms());
                assertTrue(meta.isHasNoBasePlate());
                assertTrue(meta.isMarker());
                assertTrue(entity.isInvisible());
            }
            default -> throw new AssertionError("not a mapped type: " + source);
        }
    }

    private static void readAll(SynxChunk chunk, Block[] blocks, Object[] biomes) {
        chunk.lockReadLock();
        try {
            int i = 0;
            for (int y = 0; y < SynxTestMap.HEIGHT; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++, i++) {
                        final int bx = chunk.getChunkX() * 16 + x, bz = chunk.getChunkZ() * 16 + z;
                        blocks[i] = chunk.getBlock(bx, y, bz);
                        biomes[i] = chunk.getBiome(bx, y, bz);
                    }
                }
            }
        } finally {
            chunk.unlockReadLock();
        }
    }

    /**
     * The cached packet {@code connection} was sent for chunk {@code chunkX, chunkZ}, if any: the form a
     * chunk in failed or overlay mode sends, where handle mode sends a buffered one.
     */
    private static @Nullable CachedPacket sentFor(RecordingConnection connection, int chunkX, int chunkZ) {
        for (SendablePacket packet : connection.sent) {
            if (packet instanceof CachedPacket cached && cached.packet() instanceof ChunkDataPacket chunk
                    && chunk.chunkX() == chunkX && chunk.chunkZ() == chunkZ) {
                return cached;
            }
        }
        return null;
    }

    /** The y of the highest non air block of a column. */
    static int surface(Instance instance, int x, int z) {
        for (int y = SynxTestMap.HEIGHT - 1; y >= 0; y--) {
            if (!instance.getBlock(x, y, z).air()) return y;
        }
        throw new AssertionError("an empty column at " + x + "," + z);
    }

    /** Flips a byte in the segment ① of a chunk, which the {@code crc32c} of its index entry covers. */
    private static void corrupt(Path region, int chunkX, int chunkZ) throws Exception {
        try (FileChannel channel = FileChannel.open(region, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            final ByteBuffer entry = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            channel.read(entry, SynxFormat.HEADER_BYTES + (long) SynxFormat.index(chunkX, chunkZ) * SynxFormat.INDEX_ENTRY_BYTES);
            final long offset = entry.getLong(0);
            final ByteBuffer one = ByteBuffer.allocate(1);
            final long at = offset + SynxFormat.SEGMENT_TABLE_BYTES + 20;
            channel.read(one, at);
            one.put(0, (byte) (one.get(0) ^ 0x5A)).rewind();
            channel.write(one, at);
        }
    }
}
