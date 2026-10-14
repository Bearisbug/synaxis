package io.github.bearisbug.synaxis.baker.anvil;

import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.adventure.EngineAdventure;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.palette.Palette;
import io.github.bearisbug.synaxis.registry.DynamicRegistry;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.world.biome.Biome;
import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.ByteArrayBinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline, read-only reader of a vanilla 26.2 Anvil save, the baker's edit source ({@code docs/DESIGN-M3.md}
 * REQ-M3-020, API-M3-003). Revived from the engine's {@code AnvilLoader} and {@code RegionFile} as they
 * stood before {@code 74592d8fdf}, with the write path, the instance plumbing and the pre-26.1 layout left
 * behind.
 * <p>
 * A dimension lives in {@code <world>/dimensions/<namespace>/<path>/}; blocks come from its {@code region/},
 * static entities from its {@code entities/}. Files are only ever opened for reading and a missing region
 * file is never created. Every chunk handed out passed two gates, and a chunk that fails either is an
 * {@link AnvilSourceException} naming its coordinates: {@code DataVersion} must be
 * {@value MinecraftServer#DATA_VERSION} (the only version check there is: an older save is upgraded by
 * the vanilla server, not here), and the status, read from {@code Status} or from {@code status}, must be
 * {@code minecraft:full}.
 * <p>
 * Data markers (REQ-M3-022, API-M3-003) are applied while reading: a structure block in DATA mode whose
 * {@code metadata} is {@code <handler namespace> <SNBT>} turns into air, and the block directly below it
 * gets a block entity record carrying that handler, the marker's SNBT merged over whatever block entity
 * the source had there. The handler namespace must satisfy the {@code registeredHandlers} predicate, the
 * SNBT may not set {@code id}, {@code x}, {@code y} or {@code z}, and the block below may not be a marker. A
 * {@code synaxis:hidden <x1> <z1> <x2> <z2>} marker also turns into air and is reported as a
 * {@link HiddenArea}.
 * <p>
 * Thread safe: region files are opened once, under a lock, and read with positional reads.
 */
public final class AnvilWorld implements AutoCloseable {
    public static final String HIDDEN_MARKER = "synaxis:hidden";

    private static final String FULL = "minecraft:full";
    private static final String STRUCTURE_BLOCK = "minecraft:structure_block";
    private static final List<String> RECORD_KEYS = List.of("id", "x", "y", "z");
    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private final Path regionDir;
    private final Path entitiesDir;
    private final DynamicRegistry<Biome> biomes;
    private final Predicate<String> registeredHandlers;
    private final Map<Path, Optional<RegionFile>> regions = new HashMap<>();

    private AnvilWorld(Path dimensionDir, DynamicRegistry<Biome> biomes, Predicate<String> registeredHandlers) {
        this.regionDir = dimensionDir.resolve("region");
        this.entitiesDir = dimensionDir.resolve("entities");
        this.biomes = biomes;
        this.registeredHandlers = registeredHandlers;
    }

    /**
     * Opens one dimension of a save.
     *
     * @param world              the save directory, the one holding {@code level.dat}
     * @param dimension          the dimension, e.g. {@code minecraft:overworld}
     * @param biomes             the registry biome names are resolved in; its ids end up in the palettes
     * @param registeredHandlers accepts the handler namespaces a data marker may name
     * @throws IOException when the dimension has no {@code region/} directory
     */
    public static AnvilWorld open(Path world, Key dimension, DynamicRegistry<Biome> biomes,
                                  Predicate<String> registeredHandlers) throws IOException {
        final Path dimensionDir = world.resolve("dimensions").resolve(dimension.namespace()).resolve(dimension.value());
        final Path region = dimensionDir.resolve("region");
        if (!Files.isDirectory(region)) {
            throw new IOException("no " + region.toAbsolutePath() + (Files.isDirectory(world.resolve("region"))
                    ? ": a 26.2 save keeps each dimension under dimensions/<namespace>/<path>/, an older save must be"
                    + " upgraded by the vanilla server first"
                    : Files.isRegularFile(world.resolve("level.dat")) ? ": the save has not generated " + dimension.asString()
                    : ": " + world.toAbsolutePath() + " is not a save, give the directory that holds level.dat"));
        }
        return new AnvilWorld(dimensionDir, biomes, registeredHandlers);
    }

    /**
     * Every chunk that has an entry in the dimension's region files, generated to full or not, in
     * ascending (x, z) order.
     */
    public List<ChunkPos> chunks() throws IOException {
        final List<ChunkPos> chunks = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(regionDir, "r.*.mca")) {
            for (Path file : files) {
                final Matcher name = REGION_NAME.matcher(file.getFileName().toString());
                if (!name.matches()) continue;
                final int regionX = Integer.parseInt(name.group(1)), regionZ = Integer.parseInt(name.group(2));
                final RegionFile region = region(file);
                if (region == null) continue;
                for (int z = 0; z < 32; z++) {
                    for (int x = 0; x < 32; x++) {
                        if (region.hasChunk(x, z)) chunks.add(new ChunkPos(regionX * 32 + x, regionZ * 32 + z));
                    }
                }
            }
        }
        chunks.sort(Comparator.comparingInt(ChunkPos::x).thenComparingInt(ChunkPos::z));
        return chunks;
    }

    /**
     * Reads one chunk with its static entities, markers applied.
     *
     * @return the chunk, or null when the save has no such chunk
     * @throws AnvilSourceException when the chunk or its entities are refused, see {@link AnvilSourceException}
     * @throws IOException          when a region file or one of its entries cannot be read
     */
    public @Nullable AnvilChunk readChunk(int chunkX, int chunkZ) throws IOException {
        final String fileName = RegionFile.fileName(chunkX >> 5, chunkZ >> 5);
        final RegionFile region = region(regionDir.resolve(fileName));
        final CompoundBinaryTag data = region != null ? region.readChunk(chunkX, chunkZ) : null;
        if (data == null) return null;

        checkDataVersion(chunkX, chunkZ, data, "");
        final String status = data.get("Status") instanceof StringBinaryTag upper ? upper.value()
                : data.get("status") instanceof StringBinaryTag lower ? lower.value() : null;
        if (!FULL.equals(status)) {
            throw new AnvilSourceException(chunkX, chunkZ, "status " + (status == null ? "missing" : status)
                    + ", not " + FULL + "; pregenerate the source with the vanilla 26.2 server until every chunk inside the"
                    + " bounds is full, or give --bounds that leave this chunk out (without --bounds every chunk of the save"
                    + " is inside)");
        }
        if (data.getInt("xPos", chunkX) != chunkX || data.getInt("zPos", chunkZ) != chunkZ) {
            throw new AnvilSourceException(chunkX, chunkZ, "the region entry holds chunk "
                    + data.getInt("xPos") + ", " + data.getInt("zPos"));
        }

        final Map<Integer, AnvilSection> sections = readSections(chunkX, chunkZ, data);
        final Map<Long, AnvilBlockEntity> blockEntities = readBlockEntities(chunkX, chunkZ, data);
        final Set<Long> markerCells = new HashSet<>();
        final List<HiddenArea> hiddenAreas = applyMarkers(chunkX, chunkZ, sections, blockEntities, markerCells);
        return new AnvilChunk(chunkX, chunkZ, data.getBoolean("isLightOn"),
                List.copyOf(sections.values()), List.copyOf(blockEntities.values()),
                readEntities(chunkX, chunkZ, fileName), List.copyOf(hiddenAreas), markerCells);
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        synchronized (regions) {
            for (Optional<RegionFile> region : regions.values()) {
                if (region.isEmpty()) continue;
                try {
                    region.get().close();
                } catch (IOException e) {
                    if (failure == null) failure = e;
                    else failure.addSuppressed(e);
                }
            }
            regions.clear();
        }
        if (failure != null) throw failure;
    }

    /**
     * The region file at {@code path}, opened on first use; null when there is no such file.
     */
    private @Nullable RegionFile region(Path path) throws IOException {
        synchronized (regions) {
            Optional<RegionFile> region = regions.get(path);
            if (region == null) {
                region = Files.isRegularFile(path) ? Optional.of(new RegionFile(path)) : Optional.empty();
                regions.put(path, region);
            }
            return region.orElse(null);
        }
    }

    private static void checkDataVersion(int chunkX, int chunkZ, CompoundBinaryTag data, String what)
            throws AnvilSourceException {
        final int dataVersion = data.getInt("DataVersion", -1);
        if (dataVersion != MinecraftServer.DATA_VERSION) {
            throw new AnvilSourceException(chunkX, chunkZ, what + "DataVersion " + dataVersion + ", not "
                    + MinecraftServer.DATA_VERSION + " (26.2); " + (dataVersion > MinecraftServer.DATA_VERSION
                    ? "a later Minecraft saved it and the vanilla server does not downgrade, use a copy last opened with 26.2"
                    : "upgrade the source with the vanilla 26.2 dedicated server --forceUpgrade --eraseCache, then"
                    + " pregenerate it again"));
        }
    }

    private Map<Integer, AnvilSection> readSections(int chunkX, int chunkZ, CompoundBinaryTag data)
            throws AnvilSourceException {
        final Map<Integer, AnvilSection> sections = new TreeMap<>();
        for (BinaryTag tag : data.getList("sections", BinaryTagTypes.COMPOUND)) {
            final CompoundBinaryTag section = (CompoundBinaryTag) tag;
            // Vanilla keeps a light only section below and above the world; it has no block states
            if (!(section.get("block_states") instanceof CompoundBinaryTag blockStates)) continue;
            final int sectionY = section.getByte("Y");

            final Palette blockPalette = Palette.blocks();
            final ListBinaryTag blockEntries = blockStates.getList("palette", BinaryTagTypes.COMPOUND);
            final int[] blockIds = new int[blockEntries.size()];
            for (int i = 0; i < blockIds.length; i++) {
                blockIds[i] = blockState(chunkX, chunkZ, sectionY, blockEntries.getCompound(i));
            }
            load(chunkX, chunkZ, sectionY, "block_states", blockPalette, Palette.BLOCK_PALETTE_MIN_BITS, blockIds, blockStates);

            final Palette biomePalette = Palette.biomes();
            final CompoundBinaryTag biomeTag = section.getCompound("biomes");
            final ListBinaryTag biomeNames = biomeTag.getList("palette", BinaryTagTypes.STRING);
            final int[] biomeIds = new int[biomeNames.size()];
            for (int i = 0; i < biomeIds.length; i++) {
                biomeIds[i] = biome(chunkX, chunkZ, sectionY, biomeNames.getString(i));
            }
            load(chunkX, chunkZ, sectionY, "biomes", biomePalette, Palette.BIOME_PALETTE_MIN_BITS, biomeIds, biomeTag);

            sections.put(sectionY, new AnvilSection(sectionY, blockPalette, biomePalette,
                    light(section, "SkyLight"), light(section, "BlockLight")));
        }
        return sections;
    }

    private static void load(int chunkX, int chunkZ, int sectionY, String what, Palette palette, int minBits,
                             int[] ids, CompoundBinaryTag container) throws AnvilSourceException {
        if (ids.length == 0) {
            throw new AnvilSourceException(chunkX, chunkZ, "section " + sectionY + " has an empty " + what + " palette");
        }
        if (ids.length == 1) {
            palette.fill(ids[0]);
            return;
        }
        // Palette.load does not check its input: with a palette (up to 256 block states or 8 biomes) it pads or cuts
        // the packed array silently and an index past the palette throws on a later read; beyond that a short array
        // throws and an index past the palette reads as 0
        final long[] packed = container.getLongArray("data");
        final int bits = Math.max(minBits, 32 - Integer.numberOfLeadingZeros(ids.length - 1));
        final int perLong = 64 / bits, entries = palette.maxSize();
        final int expected = (entries + perLong - 1) / perLong;
        if (packed.length != expected) {
            throw new AnvilSourceException(chunkX, chunkZ, "section " + sectionY + " packs " + what + " in "
                    + packed.length + " longs, not " + expected);
        }
        final long mask = (1L << bits) - 1;
        for (int i = 0; i < entries; i++) {
            final long index = (packed[i / perLong] >>> (i % perLong * bits)) & mask;
            if (index >= ids.length) {
                throw new AnvilSourceException(chunkX, chunkZ, "section " + sectionY + " packs " + what + " index "
                        + index + ", past its palette of " + ids.length);
            }
        }
        palette.load(ids, packed);
    }

    private static int blockState(int chunkX, int chunkZ, int sectionY, CompoundBinaryTag entry)
            throws AnvilSourceException {
        final String name = entry.getString("Name");
        Block block = Block.fromKey(name);
        if (block == null) {
            throw new AnvilSourceException(chunkX, chunkZ, "section " + sectionY + " names unknown block " + name);
        }
        final CompoundBinaryTag properties = entry.getCompound("Properties");
        if (!properties.isEmpty()) {
            final Map<String, String> values = HashMap.newHashMap(properties.size());
            for (Map.Entry<String, ? extends BinaryTag> property : properties) {
                if (!(property.getValue() instanceof StringBinaryTag value)) {
                    throw new AnvilSourceException(chunkX, chunkZ, "section " + sectionY + ": property "
                            + property.getKey() + " of " + name + " is not a string");
                }
                values.put(property.getKey(), value.value());
            }
            try {
                block = block.withProperties(values);
            } catch (IllegalArgumentException e) {
                throw new AnvilSourceException(chunkX, chunkZ, "section " + sectionY + ": " + name + values
                        + " is not a 26.2 block state (" + e.getMessage() + ")");
            }
        }
        return block.stateId();
    }

    private int biome(int chunkX, int chunkZ, int sectionY, String name) throws AnvilSourceException {
        final RegistryKey<Biome> key;
        try {
            key = biomes.getKey(Key.key(name));
        } catch (InvalidKeyException _) {
            throw new AnvilSourceException(chunkX, chunkZ, "section " + sectionY + " names biome " + name
                    + ", not a key");
        }
        final int id = key == null ? -1 : biomes.getId(key);
        if (id == -1) {
            throw new AnvilSourceException(chunkX, chunkZ, "section " + sectionY + " names biome " + name
                    + ", which is not registered");
        }
        return id;
    }

    /**
     * The section's light array {@code name}, or null when it is absent or not 2,048 bytes long: the source light
     * parity indexes the computed 2,048 byte array by the source's.
     */
    private static byte @Nullable [] light(CompoundBinaryTag section, String name) {
        return section.get(name) instanceof ByteArrayBinaryTag light && light.size() == 2048 ? light.value() : null;
    }

    /**
     * Keyed by {@link AnvilChunk#cellKey}, which orders by section then index.
     */
    private static Map<Long, AnvilBlockEntity> readBlockEntities(int chunkX, int chunkZ, CompoundBinaryTag data)
            throws AnvilSourceException {
        final Map<Long, AnvilBlockEntity> blockEntities = new TreeMap<>();
        for (BinaryTag tag : data.getList("block_entities", BinaryTagTypes.COMPOUND)) {
            final CompoundBinaryTag nbt = (CompoundBinaryTag) tag;
            final int x = nbt.getInt("x"), y = nbt.getInt("y"), z = nbt.getInt("z");
            if (x >> 4 != chunkX || z >> 4 != chunkZ) {
                throw new AnvilSourceException(chunkX, chunkZ, "block entity " + nbt.getString("id") + " at "
                        + x + ", " + y + ", " + z + " lies outside of the chunk");
            }
            final AnvilBlockEntity blockEntity = new AnvilBlockEntity(y >> 4, AnvilBlockEntity.index(x, y, z), null, nbt);
            blockEntities.put(AnvilChunk.cellKey(blockEntity.sectionY(), blockEntity.index()), blockEntity);
        }
        return blockEntities;
    }

    private List<HiddenArea> applyMarkers(int chunkX, int chunkZ, Map<Integer, AnvilSection> sections,
                                          Map<Long, AnvilBlockEntity> blockEntities, Set<Long> markerCells) throws AnvilSourceException {
        final List<AnvilBlockEntity> markers = new ArrayList<>();
        final Set<Long> markerKeys = new HashSet<>();
        for (AnvilBlockEntity blockEntity : blockEntities.values()) {
            final CompoundBinaryTag nbt = blockEntity.nbt();
            if (nbt.getString("id").equals(STRUCTURE_BLOCK) && nbt.getString("mode").equals("DATA")) {
                markers.add(blockEntity);
                markerKeys.add(AnvilChunk.cellKey(blockEntity.sectionY(), blockEntity.index()));
            }
        }

        final List<HiddenArea> hiddenAreas = new ArrayList<>();
        for (AnvilBlockEntity marker : markers) {
            final int x = marker.nbt().getInt("x"), y = marker.nbt().getInt("y"), z = marker.nbt().getInt("z");
            final String where = "data marker at " + x + ", " + y + ", " + z;
            final String metadata = marker.nbt().getString("metadata").strip();
            final int split = metadata.indexOf(' ');
            final String namespace = split < 0 ? metadata : metadata.substring(0, split);
            final String argument = split < 0 ? "" : metadata.substring(split + 1).strip();

            final long markerKey = AnvilChunk.cellKey(marker.sectionY(), marker.index());
            final int belowSection = (y - 1) >> 4, belowIndex = AnvilBlockEntity.index(x, y - 1, z);
            final long below = AnvilChunk.cellKey(belowSection, belowIndex);

            blockEntities.remove(markerKey);
            section(chunkX, chunkZ, sections, y, where).blocks().set(x & 15, y & 15, z & 15, Block.AIR.stateId());
            markerCells.add(markerKey);
            markerCells.add(below);

            if (namespace.equals(HIDDEN_MARKER)) {
                hiddenAreas.add(hiddenArea(chunkX, chunkZ, where, argument));
                continue;
            }
            if (namespace.isEmpty() || !registeredHandlers.test(namespace)) {
                throw new AnvilSourceException(chunkX, chunkZ, where + " names handler namespace '" + namespace
                        + "', which is not registered");
            }
            final CompoundBinaryTag markerNbt;
            try {
                markerNbt = argument.isEmpty() ? CompoundBinaryTag.empty()
                        : EngineAdventure.tagStringIO().asCompound(argument);
            } catch (IOException | RuntimeException e) {
                // Adventure's parser throws StringIndexOutOfBoundsException, not an IOException, on truncated SNBT
                throw new AnvilSourceException(chunkX, chunkZ, where + " carries malformed SNBT '" + argument
                        + "': " + e);
            }
            for (String key : RECORD_KEYS) {
                if (markerNbt.get(key) != null) {
                    throw new AnvilSourceException(chunkX, chunkZ, where + " sets '" + key
                            + "', which belongs to the block entity record");
                }
            }

            // Called for its check only: refuses a marker whose block below lies outside of every section
            section(chunkX, chunkZ, sections, y - 1, where + " marks the block below it, which");
            if (markerKeys.contains(below)) {
                throw new AnvilSourceException(chunkX, chunkZ, where + " stands on another data marker");
            }
            final AnvilBlockEntity existing = blockEntities.get(below);
            final CompoundBinaryTag merged = existing == null ? markerNbt
                    : CompoundBinaryTag.builder().put(existing.nbt()).put(markerNbt).build();
            blockEntities.put(below, new AnvilBlockEntity(belowSection, belowIndex, namespace, merged));
        }
        return hiddenAreas;
    }

    private static AnvilSection section(int chunkX, int chunkZ, Map<Integer, AnvilSection> sections, int y,
                                        String where) throws AnvilSourceException {
        final AnvilSection section = sections.get(y >> 4);
        if (section == null) throw new AnvilSourceException(chunkX, chunkZ, where + " lies outside of every section");
        return section;
    }

    private static HiddenArea hiddenArea(int chunkX, int chunkZ, String where, String argument)
            throws AnvilSourceException {
        final String[] corners = argument.split("\\s+", -1);
        if (corners.length != 4) {
            throw new AnvilSourceException(chunkX, chunkZ, where + ": " + HIDDEN_MARKER + " takes <x1> <z1> <x2> <z2>, got '"
                    + argument + "'");
        }
        try {
            return new HiddenArea(Integer.parseInt(corners[0]), Integer.parseInt(corners[1]),
                    Integer.parseInt(corners[2]), Integer.parseInt(corners[3]));
        } catch (NumberFormatException _) {
            throw new AnvilSourceException(chunkX, chunkZ, where + ": " + HIDDEN_MARKER + " takes four integers, got '"
                    + argument + "'");
        }
    }

    private List<CompoundBinaryTag> readEntities(int chunkX, int chunkZ, String fileName) throws IOException {
        final RegionFile region = region(entitiesDir.resolve(fileName));
        final CompoundBinaryTag data = region != null ? region.readChunk(chunkX, chunkZ) : null;
        if (data == null) return List.of();
        checkDataVersion(chunkX, chunkZ, data, "entities: ");
        final int[] position = data.getIntArray("Position");
        if (position.length != 2 || position[0] != chunkX || position[1] != chunkZ) {
            throw new AnvilSourceException(chunkX, chunkZ, position.length == 2
                    ? "entities: the entry holds chunk " + position[0] + ", " + position[1]
                    : "entities: the entry has no Position");
        }
        final List<CompoundBinaryTag> entities = new ArrayList<>();
        for (BinaryTag tag : data.getList("Entities", BinaryTagTypes.COMPOUND)) {
            final CompoundBinaryTag entity = (CompoundBinaryTag) tag;
            // The window check reads the y of Pos, a shard places the entity at Pos turned by Rotation
            final ListBinaryTag pos = entity.getList("Pos", BinaryTagTypes.DOUBLE);
            if (pos.size() != 3 || !Double.isFinite(pos.getDouble(0)) || !Double.isFinite(pos.getDouble(1))
                    || !Double.isFinite(pos.getDouble(2)) || entity.getList("Rotation", BinaryTagTypes.FLOAT).size() != 2) {
                throw new AnvilSourceException(chunkX, chunkZ, "entities: " + entity.getString("id")
                        + " has no Pos of three finite doubles and Rotation of two floats");
            }
            entities.add(entity);
        }
        return List.copyOf(entities);
    }

    /**
     * A chunk coordinate pair.
     */
    public record ChunkPos(int x, int z) {
    }
}
