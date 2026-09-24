package io.github.bearisbug.synaxis.baker;

import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.instance.light.LightEngine;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.data.ChunkData;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.synx.SynxChunkPacket;
import io.github.bearisbug.synaxis.synx.SynxChunkRecord;
import io.github.bearisbug.synaxis.synx.SynxFormat;
import io.github.bearisbug.synaxis.synx.SynxRegion;
import io.github.bearisbug.synaxis.synx.SynxSlot;
import io.github.bearisbug.synaxis.tag.Tag;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.TagStringIO;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/** Builds bake requests for the tests and reads what a bake wrote. */
public final class BakeTesting {
    public static final Registries REGISTRIES = Registries.vanilla();

    private BakeTesting() {
    }

    /** A handler that shows the client only {@code tags}. */
    public record TestHandler(Key getKey, Collection<Tag<?>> getBlockEntityTags)
            implements BlockHandler {
    }

    public static Baker.Request request(String mapId, BakeSource source, Path root, int threads,
                                        Function<String, @Nullable BlockHandler> handlers) {
        return new Baker.Request(mapId, source, root.resolve("maps"), threads, root.resolve("logs"), REGISTRIES,
                handlers, Set.of(LightEngine.VERSION), 4);
    }

    public static Baker.Request request(String mapId, BakeSource source, Path root, int threads) {
        return request(mapId, source, root, threads, Map.<String, BlockHandler>of()::get);
    }

    /** The record of one chunk of a published build. */
    public static SynxChunkRecord record(Path build, int chunkX, int chunkZ) throws Exception {
        final Path file = build.resolve("regions").resolve(SynxFormat.regionFileName(SynxFormat.region(chunkX),
                SynxFormat.region(chunkZ)));
        try (Arena arena = Arena.ofConfined()) {
            final SynxSlot slot = SynxRegion.open(file, arena).slot(chunkX, chunkZ);
            if (!(slot instanceof SynxSlot.Present present)) throw new AssertionError(chunkX + "," + chunkZ + ": " + slot);
            return present.record();
        }
    }

    public static ChunkDataPacket packet(SynxChunkRecord record) {
        return SynxChunkPacket.decode(record.packet(), REGISTRIES);
    }

    /** The sections of a segment ①, bottom up, palettes in the form they were sent. */
    public static List<ChunkData.Section> sections(SynxChunkRecord record) {
        final byte[] data = packet(record).chunkData().data();
        final NetworkBuffer buffer = NetworkBuffer.wrap(data, 0, data.length, REGISTRIES);
        final NetworkBuffer.Type<ChunkData.Section> type = ChunkData.Section.networkType(REGISTRIES.biome().size());
        final List<ChunkData.Section> sections = new ArrayList<>();
        while (buffer.readableBytes() > 0) sections.add(buffer.read(type));
        return sections;
    }

    public static List<String> events(Path root) throws IOException {
        final List<String> lines = new ArrayList<>();
        try (Stream<Path> files = Files.list(root.resolve("logs"))) {
            for (Path file : files.sorted().toList()) lines.addAll(Files.readAllLines(file));
        }
        return lines;
    }

    public static List<Path> list(Path dir) throws IOException {
        if (!Files.exists(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.sorted().toList();
        }
    }

    public static CompoundBinaryTag nbt(String snbt) throws IOException {
        return TagStringIO.tagStringIO().asCompound(snbt);
    }
}
