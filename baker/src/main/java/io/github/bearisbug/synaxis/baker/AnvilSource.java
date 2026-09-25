package io.github.bearisbug.synaxis.baker;

import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.baker.anvil.AnvilBlockEntity;
import io.github.bearisbug.synaxis.baker.anvil.AnvilChunk;
import io.github.bearisbug.synaxis.baker.anvil.AnvilSection;
import io.github.bearisbug.synaxis.baker.anvil.AnvilSourceException;
import io.github.bearisbug.synaxis.baker.anvil.AnvilWorld;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.palette.Palette;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.synx.SynxBlockEntity;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;

/**
 * An edit source save (REQ-M3-020) as a bake source, read through {@link AnvilWorld}. The world is a window of
 * {@value #HEIGHT} blocks from a given y, {@value #DEFAULT_MIN_Y} by default (API-M3-003): a chunk with a
 * block other than air, a block entity or an entity outside of the window is refused. Every chunk inside
 * the bounds must be in the save and generated to full; the bounds default to the rectangle around every
 * chunk the save has, full or not, so a save whose edge was left half generated needs explicit bounds.
 */
public final class AnvilSource implements BakeSource {
    /** The world height a map has (Q7 of {@code docs/DESIGN-M3.md}). */
    public static final int HEIGHT = 256;
    /** The lowest y of the vanilla overworld, where bedrock and generated terrain start. */
    public static final int DEFAULT_MIN_Y = -64;

    private final AnvilWorld world;
    private final SynxManifest.Bounds bounds;
    private final int minY;
    private final int height;
    private volatile @Nullable Double lightCoverage;

    private AnvilSource(AnvilWorld world, SynxManifest.Bounds bounds, int minY) {
        this.world = world;
        this.bounds = bounds;
        this.minY = minY;
        this.height = HEIGHT;
    }

    /**
     * Opens a save.
     *
     * @param bounds             the chunks to bake, or null for every chunk of the save
     * @param minY               the lowest y of the window baked, a multiple of 16
     * @param registeredHandlers the handler namespaces a data marker may name
     * @throws IOException when the save or the dimension cannot be opened, or has no chunk at all
     * @throws IllegalArgumentException when {@code minY} is not a multiple of 16
     */
    public static AnvilSource open(Path save, Key dimension, @Nullable SynxManifest.Bounds bounds, int minY,
                                   Registries registries, Predicate<String> registeredHandlers) throws IOException {
        if (minY % 16 != 0) throw new IllegalArgumentException("--min-y must be a multiple of 16, got " + minY);
        if (!Files.isDirectory(save)) throw new IOException("no source save at " + save);
        final AnvilWorld world = AnvilWorld.open(save, dimension, registries.biome(), registeredHandlers);
        try {
            SynxManifest.Bounds effective = bounds;
            if (effective == null) {
                final List<AnvilWorld.ChunkPos> chunks = world.chunks();
                if (chunks.isEmpty()) throw new IOException("the save has no chunk in " + dimension.asString());
                int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
                for (AnvilWorld.ChunkPos chunk : chunks) {
                    minX = Math.min(minX, chunk.x());
                    minZ = Math.min(minZ, chunk.z());
                    maxX = Math.max(maxX, chunk.x());
                    maxZ = Math.max(maxZ, chunk.z());
                }
                effective = new SynxManifest.Bounds(minX, minZ, maxX, maxZ);
            }
            return new AnvilSource(world, effective, minY);
        } catch (IOException | RuntimeException e) {
            world.close();
            throw e;
        }
    }

    @Override
    public String kind() {
        return "anvil";
    }

    @Override
    public int minY() {
        return minY;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public SynxManifest.Bounds bounds() {
        return bounds;
    }

    @Override
    public Integer dataVersion() {
        return MinecraftServer.DATA_VERSION;
    }

    @Override
    public @Nullable Double lightCoverage() {
        return lightCoverage;
    }

    /**
     * Reads every chunk inside the bounds once. The first failure in (x, z) order is thrown, so the same save
     * always names the same chunk.
     */
    @Override
    public void checkGate(int threads) throws IOException {
        final ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            final List<Future<Boolean>> reads = new ArrayList<>();
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    final int chunkX = x, chunkZ = z;
                    reads.add(executor.submit(() -> chunk(chunkX, chunkZ).lightOn()));
                }
            }
            int lit = 0;
            for (Future<Boolean> read : reads) {
                try {
                    if (read.get()) lit++;
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof IOException io) throw io;
                    throw new IOException(e.getCause());
                }
            }
            lightCoverage = (double) lit / reads.size();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } finally {
            executor.shutdownNow();
        }
    }

    @Override
    public SourceChunk read(int chunkX, int chunkZ) throws IOException {
        final AnvilChunk chunk = chunk(chunkX, chunkZ);
        final int sections = height / 16, minSection = minY >> 4;
        final Palette[] blocks = new Palette[sections];
        final Palette[] biomes = new Palette[sections];
        final byte[][] sky = new byte[sections][];
        final byte[][] block = new byte[sections][];
        for (AnvilSection section : chunk.sections()) {
            final int i = section.sectionY() - minSection;
            if (i < 0 || i >= sections) continue; // all air, checked by chunk()
            blocks[i] = section.blocks();
            biomes[i] = section.biomes();
            sky[i] = section.skyLight();
            block[i] = section.blockLight();
        }
        for (int i = 0; i < sections; i++) {
            if (blocks[i] == null) {
                blocks[i] = Palette.blocks();
                biomes[i] = Palette.biomes();
            }
        }
        final List<SynxBlockEntity> blockEntities = new ArrayList<>(chunk.blockEntities().size());
        for (AnvilBlockEntity blockEntity : chunk.blockEntities()) {
            blockEntities.add(new SynxBlockEntity(blockEntity.sectionY(), blockEntity.index(),
                    blockEntity.handler() == null ? "" : blockEntity.handler(), blockEntity.nbt()));
        }
        return new SourceChunk(chunkX, chunkZ, blocks, biomes, blockEntities, chunk.entities(), chunk.lightOn(), sky, block,
                chunk.markerCells());
    }

    private AnvilChunk chunk(int chunkX, int chunkZ) throws IOException {
        final AnvilChunk chunk = world.readChunk(chunkX, chunkZ);
        if (chunk == null) {
            throw new AnvilSourceException(chunkX, chunkZ, "missing from the source, every chunk inside the bounds "
                    + bounds.minX() + ", " + bounds.minZ() + " .. " + bounds.maxX() + ", " + bounds.maxZ()
                    + " must be generated to full");
        }
        checkWindow(chunk);
        return chunk;
    }

    /** Refuses a chunk with anything outside of the window: its lowest block, block entity or entity out there. */
    private void checkWindow(AnvilChunk chunk) throws AnvilSourceException {
        String outside = null;
        int outsideY = Integer.MAX_VALUE;
        for (AnvilSection section : chunk.sections()) {
            final int sectionMinY = section.sectionY() * 16;
            if (sectionMinY >= minY && sectionMinY < minY + height) continue;
            final int[] lowest = {Integer.MAX_VALUE, 0};
            section.blocks().getAllPresent((_, y, _, state) -> {
                if (y < lowest[0] && !Objects.requireNonNull(Block.fromStateId(state)).air()) {
                    lowest[0] = y;
                    lowest[1] = state;
                }
            });
            if (lowest[0] != Integer.MAX_VALUE && sectionMinY + lowest[0] < outsideY) {
                outsideY = sectionMinY + lowest[0];
                outside = "block " + Objects.requireNonNull(Block.fromStateId(lowest[1])).name();
            }
        }
        for (AnvilBlockEntity blockEntity : chunk.blockEntities()) {
            final int y = blockEntity.y();
            if ((y < minY || y >= minY + height) && y < outsideY) {
                outsideY = y;
                outside = "block entity " + blockEntity.nbt().getString("id");
            }
        }
        for (CompoundBinaryTag entity : chunk.entities()) {
            final int y = (int) Math.floor(entity.getList("Pos").getDouble(1));
            if ((y < minY || y >= minY + height) && y < outsideY) {
                outsideY = y;
                outside = "entity " + entity.getString("id");
            }
        }
        if (outside != null) {
            throw new AnvilSourceException(chunk.chunkX(), chunk.chunkZ(), outside + " at y " + outsideY
                    + " lies outside of the baked window, y " + minY + " to " + (minY + height - 1)
                    + "; clear it from the source or move the window with --min-y");
        }
    }

    @Override
    public void close() throws IOException {
        world.close();
    }
}
