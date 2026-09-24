package io.github.bearisbug.synaxis.baker;

import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.baker.anvil.AnvilBlockEntity;
import io.github.bearisbug.synaxis.baker.anvil.AnvilChunk;
import io.github.bearisbug.synaxis.baker.anvil.AnvilSection;
import io.github.bearisbug.synaxis.baker.anvil.AnvilSourceException;
import io.github.bearisbug.synaxis.baker.anvil.AnvilWorld;
import io.github.bearisbug.synaxis.instance.palette.Palette;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.synx.SynxBlockEntity;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import io.github.bearisbug.synaxis.world.DimensionType;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;

/**
 * An edit source save (REQ-M3-020) as a bake source, read through {@link AnvilWorld}. The world is the
 * dimension type of the same key: {@code minecraft:overworld} is 384 blocks from y -64. Every chunk inside
 * the bounds must be in the save and generated to full; the bounds default to the rectangle around every
 * chunk the save has, full or not, so a save whose edge was left half generated needs explicit bounds.
 */
public final class AnvilSource implements BakeSource {
    private final AnvilWorld world;
    private final SynxManifest.Bounds bounds;
    private final int minY;
    private final int height;
    private volatile @Nullable Double lightCoverage;

    private AnvilSource(AnvilWorld world, SynxManifest.Bounds bounds, int minY, int height) {
        this.world = world;
        this.bounds = bounds;
        this.minY = minY;
        this.height = height;
    }

    /**
     * Opens a save.
     *
     * @param bounds             the chunks to bake, or null for every chunk of the save
     * @param registeredHandlers the handler namespaces a data marker may name
     * @throws IOException when the save or the dimension cannot be opened, or has no chunk at all
     */
    public static AnvilSource open(Path save, Key dimension, @Nullable SynxManifest.Bounds bounds,
                                   Registries registries, Predicate<String> registeredHandlers) throws IOException {
        if (!Files.isDirectory(save)) throw new IOException("no source save at " + save);
        final DimensionType type = registries.dimensionType().get(dimension);
        if (type == null) throw new IOException("no dimension type " + dimension.asString() + " to take the world height from");
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
            return new AnvilSource(world, effective, type.minY(), type.height());
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
            if (i < 0 || i >= sections) {
                throw new AnvilSourceException(chunkX, chunkZ, "section " + section.sectionY() + " holds blocks outside"
                        + " of the world, y " + minY + " to " + (minY + height - 1));
            }
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
        return new SourceChunk(chunkX, chunkZ, blocks, biomes, blockEntities, chunk.entities(), chunk.lightOn(), sky, block);
    }

    private AnvilChunk chunk(int chunkX, int chunkZ) throws IOException {
        final AnvilChunk chunk = world.readChunk(chunkX, chunkZ);
        if (chunk == null) {
            throw new AnvilSourceException(chunkX, chunkZ, "missing from the source, every chunk inside the bounds "
                    + bounds.minX() + ", " + bounds.minZ() + " .. " + bounds.maxX() + ", " + bounds.maxZ()
                    + " must be generated to full");
        }
        return chunk;
    }

    @Override
    public void close() throws IOException {
        world.close();
    }
}
