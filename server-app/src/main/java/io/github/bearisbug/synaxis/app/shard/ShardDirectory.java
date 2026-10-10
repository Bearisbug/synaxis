package io.github.bearisbug.synaxis.app.shard;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The shard directory {@code <maps_root>/<map_id>/shards.json} as a shard in the directory mode read
 * it ({@code docs/DESIGN-M3.md} {@code API-M3-010}): the regions of the map with their bounds and
 * gates, the channels with their address and capacity, and which of them this shard is.
 * <p>
 * The public constructor checks nothing, so that a test can build any directory; the file is only
 * checked by {@link #load} and against the map release by {@link #checkAgainst}.
 *
 * @param mapId    the {@code map_id} of the map
 * @param regions  the regions, in the order of the file
 * @param channels the channels of every region, in the order of the file
 * @param shardId  this shard, {@code synaxis.app.shard-id}
 */
public record ShardDirectory(String mapId, List<Region> regions, List<Channel> channels, String shardId) {
    /** The name of the file under {@code synaxis.map.root}, next to the release directories. */
    public static final String FILE_NAME = "shards.json";
    /** What separates the region from the channel number in a shard id, {@code <region_id>-c<channel>}. */
    private static final String CHANNEL_SEPARATOR = "-c";
    private static final int FORMAT_VERSION = 1;
    /** §9: how a {@code region_id} and a {@code gate_id} are written. */
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
    private static final int MAX_CHANNEL = 999;
    private static final int MAX_HOST_LENGTH = 253;
    private static final int MAX_PORT = 65_535;
    private static final int MAX_CAPACITY = 500;
    /** The largest file read: a directory of the production tier, 64 regions of 3 channels, is about 50 KB. */
    static final int MAX_BYTES = 1 << 20;
    private static final TypeAdapter<JsonElement> JSON = new Gson().getAdapter(JsonElement.class);

    public ShardDirectory {
        regions = List.copyOf(regions);
        channels = List.copyOf(channels);
    }

    /**
     * A region of the map.
     *
     * @param regionId its id
     * @param bounds   the chunks it covers, inclusive
     * @param gates    its gates to other regions
     */
    public record Region(String regionId, SynxManifest.Bounds bounds, List<Gate> gates) {
        public Region {
            gates = List.copyOf(gates);
        }
    }

    /**
     * A gate of a region: walking into its frame sends a player to the target region.
     *
     * @param gateId       its id
     * @param frame        the blocks it covers
     * @param targetRegion where it sends a player
     * @param arrival      where a player coming from the target region into this region spawns
     */
    public record Gate(String gateId, Frame frame, String targetRegion, Pos arrival) {
    }

    /**
     * The frame of a gate, inclusive block coordinates.
     *
     * @param minX the smallest x
     * @param minY the smallest y
     * @param minZ the smallest z
     * @param maxX the largest x
     * @param maxY the largest y
     * @param maxZ the largest z
     */
    public record Frame(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        /**
         * Whether a block is inside the frame.
         *
         * @param x the block x
         * @param y the block y
         * @param z the block z
         * @return true if it is, bounds included
         */
        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    /**
     * A channel of a region, one shard process.
     *
     * @param shardId  its shard id, {@code <region_id>-c<channel>}
     * @param regionId its region
     * @param channel  its number in the region
     * @param host     the address clients connect to
     * @param port     the port clients connect to
     * @param capacity how many players it seats, its {@code synaxis.max-players}
     */
    public record Channel(String shardId, String regionId, int channel, String host, int port, int capacity) {
    }

    /**
     * Reads and checks the directory ({@code API-M3-010}): the whole file, then the channel of
     * {@code shardId}. Writes nothing. The file is read as strict JSON, where a name given twice in one
     * object and a field the format does not have are refused too, so a misspelt key is never ignored.
     *
     * @param file    {@code <map_root>/shards.json}
     * @param shardId {@code synaxis.app.shard-id}
     * @return the directory
     * @throws IllegalArgumentException for a refused start: {@code the shard directory <path> is not
     *                                  valid: } and the array index or id at fault, or {@code shardId}
     *                                  not a channel of the file
     */
    public static ShardDirectory load(Path file, String shardId) {
        final Path absolute = file.toAbsolutePath();
        try (FileChannel channel = FileChannel.open(absolute, StandardOpenOption.READ)) {
            return load(channel, absolute, shardId);
        } catch (IOException e) {
            throw new IllegalArgumentException(refusal(absolute) + "cannot be read: " + e, e);
        }
    }

    /**
     * {@link #load(Path, String)} on a channel of the file: its size is compared with {@value #MAX_BYTES}
     * before a byte is read, and no more is read than one byte past it (M3-S).
     *
     * @param absolute the file, for the messages
     * @throws IOException if the channel cannot be read, or the file is not UTF-8
     */
    static ShardDirectory load(SeekableByteChannel channel, Path absolute, String shardId) throws IOException {
        final String refusal = refusal(absolute);
        final byte[] bytes = BoundedRead.readAtMost(channel, MAX_BYTES);
        if (bytes == null) {
            throw new IllegalArgumentException(refusal + channel.size() + " bytes, over " + MAX_BYTES);
        }
        final String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        final ShardDirectory directory;
        try {
            directory = read(parse(text), shardId);
        } catch (Invalid e) {
            throw new IllegalArgumentException(refusal + e.getMessage(), e);
        }
        if (directory.channels().stream().noneMatch(entry -> entry.shardId().equals(shardId))) {
            throw new IllegalArgumentException("synaxis.app.shard-id=" + shardId
                    + " is not a channel of the shard directory " + absolute);
        }
        return directory;
    }

    private static String refusal(Path absolute) {
        return "the shard directory " + absolute + " is not valid: ";
    }

    /**
     * Checks the directory against the map release it serves ({@code API-M3-010}): the same
     * {@code map_id}, every region within the release's bounds, the gate frames and arrivals within
     * the height of its world. The directory does not know its file: the message is the detail
     * alone, and {@link ShardWorld} puts {@code the shard directory <path> is not valid: } in front.
     *
     * @param manifest the manifest of the release
     * @throws IllegalArgumentException for a refused start, naming the array index or id at fault
     */
    public void checkAgainst(SynxManifest manifest) {
        if (!mapId.equals(manifest.mapId())) {
            throw new IllegalArgumentException("map_id " + mapId + " is not " + manifest.mapId()
                    + ", the map_id of the map release " + manifest.buildId());
        }
        final SynxManifest.Bounds release = manifest.bounds();
        final int minY = manifest.world().minY();
        final int maxY = minY + manifest.world().height() - 1;
        final String world = " is not within the world y " + minY + ".." + maxY + " of the map release";
        for (int i = 0; i < regions.size(); i++) {
            final Region region = regions.get(i);
            final String where = "regions[" + i + "] " + region.regionId();
            final SynxManifest.Bounds bounds = region.bounds();
            if (bounds.minX() < release.minX() || bounds.minZ() < release.minZ() || bounds.maxX() > release.maxX()
                    || bounds.maxZ() > release.maxZ()) {
                throw new IllegalArgumentException(where + ": bounds " + text(bounds) + " are not within the bounds "
                        + text(release) + " of the map release");
            }
            for (int j = 0; j < region.gates().size(); j++) {
                final Gate gate = region.gates().get(j);
                final String at = where + ": gates[" + j + "] " + gate.gateId();
                final Frame frame = gate.frame();
                if (frame.minY() < minY || frame.maxY() > maxY) {
                    throw new IllegalArgumentException(at + ": the frame y " + frame.minY() + ".." + frame.maxY() + world);
                }
                final int arrivalY = (int) Math.floor(gate.arrival().y());
                if (arrivalY < minY || arrivalY > maxY) {
                    throw new IllegalArgumentException(at + ": arrival y " + gate.arrival().y() + world);
                }
            }
        }
    }

    /** The bounds as the design writes them, {@code min_x,min_z,max_x,max_z}. */
    private static String text(SynxManifest.Bounds bounds) {
        return bounds.minX() + "," + bounds.minZ() + "," + bounds.maxX() + "," + bounds.maxZ();
    }

    /** What is wrong in the file, the detail after the prefix of the refusal. */
    private static final class Invalid extends Exception {
        private static final long serialVersionUID = 1L;

        Invalid(String detail) {
            super(detail, null, false, false);
        }
    }

    /** A strict reader that fails on the second occurrence of a name in one object. */
    private static final class UniqueNamesReader extends JsonReader {
        private final Deque<Set<String>> objects = new ArrayDeque<>();

        UniqueNamesReader(String text) {
            super(new StringReader(text));
            setStrictness(Strictness.STRICT);
        }

        @Override
        public void beginObject() throws IOException {
            super.beginObject();
            objects.push(new HashSet<>());
        }

        @Override
        public void endObject() throws IOException {
            super.endObject();
            objects.pop();
        }

        @Override
        public String nextName() throws IOException {
            final String name = super.nextName();
            if (!objects.element().add(name)) throw new NameGivenTwice(getPath());
            return name;
        }
    }

    /** A name an object of the file gives a second time, at its JSONPath. */
    private static final class NameGivenTwice extends IOException {
        private static final long serialVersionUID = 1L;

        NameGivenTwice(String path) {
            super(path, null);
        }
    }

    /**
     * Reads the file as RFC 8259 JSON in Gson's {@link Strictness#STRICT}: no comments, single quotes or
     * unquoted names, one document and nothing after it, no name twice in one object.
     */
    private static JsonObject parse(String text) throws Invalid {
        final UniqueNamesReader reader = new UniqueNamesReader(text);
        final JsonElement root;
        try {
            root = JSON.read(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new Invalid("not JSON: more after the document at "
                    + reader.getPath());
        } catch (NameGivenTwice e) {
            throw new Invalid(e.getMessage() + " is given twice");
        } catch (IOException | JsonParseException e) {
            throw new Invalid("not JSON: " + e.getMessage());
        }
        if (!root.isJsonObject()) throw new Invalid("not a JSON object");
        return root.getAsJsonObject();
    }

    /**
     * The fields of one object of the file: exactly the names given, and typed reads that refuse with
     * where the object is. {@code where} grows the id once it is read, {@code channels[0]} becoming
     * {@code channels[0] r0-c1}.
     */
    private static final class Fields {
        private final JsonObject object;
        private String where;

        Fields(JsonObject object, String where, String... names) throws Invalid {
            this.object = object;
            this.where = where;
            final List<String> known = List.of(names);
            for (String name : object.keySet()) {
                if (!known.contains(name)) throw invalid("unknown field " + name);
            }
            for (String name : names) {
                if (!object.has(name)) throw invalid(name + " is missing");
            }
        }

        Invalid invalid(String detail) {
            return new Invalid(where.isEmpty() ? detail : where + ": " + detail);
        }

        void named(String id) {
            this.where = where + " " + id;
        }

        String where() {
            return where;
        }

        String string(String name) throws Invalid {
            final JsonElement value = object.get(name);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid(name + " is not a string");
            return value.getAsString();
        }

        /** A whole number, read from its text: 1 and 1.0 are 1, 1.5 and a number past int are refused. */
        int integer(String name) throws Invalid {
            final JsonElement value = object.get(name);
            final Integer number = wholeNumber(value);
            if (number == null) throw invalid(name + " is not an integer: " + value);
            return number;
        }

        int[] integers(String name, int count) throws Invalid {
            final JsonElement value = object.get(name);
            final Invalid invalid = invalid(name + " is not " + count + " integers");
            if (!value.isJsonArray() || value.getAsJsonArray().size() != count) throw invalid;
            final int[] numbers = new int[count];
            for (int i = 0; i < count; i++) {
                final Integer number = wholeNumber(value.getAsJsonArray().get(i));
                if (number == null) throw invalid;
                numbers[i] = number;
            }
            return numbers;
        }

        double[] finiteNumbers(String name, int count) throws Invalid {
            final JsonElement value = object.get(name);
            final Invalid invalid = invalid(name + " is not " + count + " finite numbers");
            if (!value.isJsonArray() || value.getAsJsonArray().size() != count) throw invalid;
            final double[] numbers = new double[count];
            for (int i = 0; i < count; i++) {
                final JsonElement element = value.getAsJsonArray().get(i);
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) throw invalid;
                numbers[i] = element.getAsDouble();
                if (!Double.isFinite(numbers[i])) throw invalid;
            }
            return numbers;
        }

        JsonObject object(String name) throws Invalid {
            final JsonElement value = object.get(name);
            if (!value.isJsonObject()) throw invalid(name + " is not an object");
            return value.getAsJsonObject();
        }

        List<JsonObject> objects(String name) throws Invalid {
            final JsonElement value = object.get(name);
            if (!value.isJsonArray()) throw invalid(name + " is not an array");
            final List<JsonObject> objects = new ArrayList<>();
            for (JsonElement element : value.getAsJsonArray()) {
                if (!element.isJsonObject()) throw invalid(name + "[" + objects.size() + "] is not an object");
                objects.add(element.getAsJsonObject());
            }
            return objects;
        }

        private static @Nullable Integer wholeNumber(JsonElement value) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
            try {
                return value.getAsBigDecimal().intValueExact();
            } catch (NumberFormatException | ArithmeticException _) {
                return null;
            }
        }
    }

    /** Every rule of the format but the ones against the release, in the order of the file. */
    private static ShardDirectory read(JsonObject root, String shardId) throws Invalid {
        final Fields file = new Fields(root, "", "format_version", "map_id", "regions", "channels");
        final int formatVersion = file.integer("format_version");
        if (formatVersion != FORMAT_VERSION) throw file.invalid("format_version " + formatVersion + " is not " + FORMAT_VERSION);
        final String mapId = file.string("map_id");

        final List<Region> regions = new ArrayList<>();
        final Map<String, String> gateIds = new HashMap<>();
        final List<JsonObject> regionObjects = file.objects("regions");
        for (int i = 0; i < regionObjects.size(); i++) {
            regions.add(region(regionObjects.get(i), "regions[" + i + "]", regions, gateIds));
        }
        for (int i = 0; i < regions.size(); i++) {
            final Region region = regions.get(i);
            for (int j = 0; j < region.gates().size(); j++) {
                final Gate gate = region.gates().get(j);
                final String where = "regions[" + i + "] " + region.regionId() + ": gates[" + j + "] " + gate.gateId();
                if (gate.targetRegion().equals(region.regionId()))
                    throw new Invalid(where + ": target_region " + gate.targetRegion() + " is its own region");
                if (regions.stream().noneMatch(other -> other.regionId().equals(gate.targetRegion())))
                    throw new Invalid(where + ": target_region " + gate.targetRegion() + " is not a region of the directory");
            }
        }

        final List<Channel> channels = new ArrayList<>();
        final List<JsonObject> channelObjects = file.objects("channels");
        for (int i = 0; i < channelObjects.size(); i++) {
            channels.add(channel(channelObjects.get(i), "channels[" + i + "]", regions, channels));
        }
        return new ShardDirectory(mapId, regions, channels, shardId);
    }

    private static Region region(JsonObject object, String where, List<Region> before, Map<String, String> gateIds)
            throws Invalid {
        final Fields fields = new Fields(object, where, "region_id", "bounds", "gates");
        final String regionId = fields.string("region_id");
        if (!ID.matcher(regionId).matches()) throw fields.invalid("region_id " + regionId + " is not written " + ID);
        if (before.stream().anyMatch(region -> region.regionId().equals(regionId)))
            throw fields.invalid("region_id " + regionId + " is given twice");
        fields.named(regionId);

        final Fields bounds = new Fields(fields.object("bounds"), fields.where() + ": bounds", "min_x", "min_z", "max_x", "max_z");
        final int minX = bounds.integer("min_x"), minZ = bounds.integer("min_z");
        final int maxX = bounds.integer("max_x"), maxZ = bounds.integer("max_z");
        if (minX > maxX || minZ > maxZ)
            throw fields.invalid("bounds " + minX + "," + minZ + "," + maxX + "," + maxZ + " are empty");
        final SynxManifest.Bounds box = new SynxManifest.Bounds(minX, minZ, maxX, maxZ);
        for (int i = 0; i < before.size(); i++) {
            final SynxManifest.Bounds other = before.get(i).bounds();
            if (box.minX() <= other.maxX() && other.minX() <= box.maxX() && box.minZ() <= other.maxZ()
                    && other.minZ() <= box.maxZ()) {
                throw fields.invalid("bounds " + text(box) + " overlap the bounds " + text(other) + " of regions[" + i
                        + "] " + before.get(i).regionId());
            }
        }
        // The blocks the region covers, inclusive
        final int blockMinX = minX * 16, blockMaxX = maxX * 16 + 15;
        final int blockMinZ = minZ * 16, blockMaxZ = maxZ * 16 + 15;
        final String blocks = "the blocks x " + blockMinX + ".." + blockMaxX + ", z " + blockMinZ + ".." + blockMaxZ
                + " of the region";

        final List<Gate> gates = new ArrayList<>();
        final List<JsonObject> gateObjects = fields.objects("gates");
        for (int j = 0; j < gateObjects.size(); j++) {
            final Fields gate = new Fields(gateObjects.get(j), fields.where() + ": gates[" + j + "]",
                    "gate_id", "min", "max", "target_region", "arrival");
            final String gateId = gate.string("gate_id");
            if (!ID.matcher(gateId).matches()) throw gate.invalid("gate_id " + gateId + " is not written " + ID);
            final String first = gateIds.putIfAbsent(gateId, fields.where());
            if (first != null) throw gate.invalid("gate_id " + gateId + " is given twice, see " + first);
            gate.named(gateId);
            final int[] min = gate.integers("min", 3);
            final int[] max = gate.integers("max", 3);
            if (min[0] > max[0] || min[1] > max[1] || min[2] > max[2]) {
                throw gate.invalid("min " + min[0] + "," + min[1] + "," + min[2] + " is past max " + max[0] + ","
                        + max[1] + "," + max[2]);
            }
            final Frame frame = new Frame(min[0], min[1], min[2], max[0], max[1], max[2]);
            if (frame.minX() < blockMinX || frame.maxX() > blockMaxX || frame.minZ() < blockMinZ || frame.maxZ() > blockMaxZ) {
                throw gate.invalid("the frame x " + frame.minX() + ".." + frame.maxX() + ", z " + frame.minZ() + ".."
                        + frame.maxZ() + " is not within " + blocks);
            }
            final String targetRegion = gate.string("target_region");
            for (int k = 0; k < gates.size(); k++) {
                if (gates.get(k).targetRegion().equals(targetRegion)) {
                    throw gate.invalid("a second gate to " + targetRegion + " after gates[" + k + "] "
                            + gates.get(k).gateId() + ": the arrival of a player from " + targetRegion
                            + " would be ambiguous");
                }
            }
            final double[] arrival = gate.finiteNumbers("arrival", 4);
            final String at = arrival[0] + ", " + arrival[1] + ", " + arrival[2];
            final int arrivalX = (int) Math.floor(arrival[0]), arrivalZ = (int) Math.floor(arrival[2]);
            if (arrivalX < blockMinX || arrivalX > blockMaxX || arrivalZ < blockMinZ || arrivalZ > blockMaxZ)
                throw gate.invalid("arrival " + at + " is not within " + blocks);
            gates.add(new Gate(gateId, frame, targetRegion,
                    new Pos(arrival[0], arrival[1], arrival[2], (float) arrival[3], 0f)));
        }
        // Every frame of the region is known now: no arrival may stand in one of them
        for (int j = 0; j < gates.size(); j++) {
            final Pos arrival = gates.get(j).arrival();
            final int x = (int) Math.floor(arrival.x()), y = (int) Math.floor(arrival.y()), z = (int) Math.floor(arrival.z());
            for (Gate gate : gates) {
                if (gate.frame().contains(x, y, z)) {
                    throw fields.invalid("gates[" + j + "] " + gates.get(j).gateId() + ": arrival " + arrival.x() + ", "
                            + arrival.y() + ", " + arrival.z() + " is inside the gate " + gate.gateId());
                }
            }
        }
        return new Region(regionId, box, gates);
    }

    private static Channel channel(JsonObject object, String where, List<Region> regions, List<Channel> before)
            throws Invalid {
        final Fields fields = new Fields(object, where, "shard_id", "region_id", "channel", "host", "port", "capacity");
        final String shardId = fields.string("shard_id");
        fields.named(shardId);
        final String regionId = fields.string("region_id");
        if (regions.stream().noneMatch(region -> region.regionId().equals(regionId)))
            throw fields.invalid("region_id " + regionId + " is not a region of the directory");
        final int channel = fields.integer("channel");
        final String expected = regionId + CHANNEL_SEPARATOR + channel;
        if (!shardId.equals(expected))
            throw fields.invalid("shard_id is not " + expected + ", <region_id>" + CHANNEL_SEPARATOR + "<channel>");
        if (channel < 1 || channel > MAX_CHANNEL)
            throw fields.invalid("channel " + channel + " is not from 1 to " + MAX_CHANNEL);
        for (int i = 0; i < before.size(); i++) {
            if (before.get(i).regionId().equals(regionId) && before.get(i).channel() == channel)
                throw fields.invalid("channel " + channel + " of " + regionId + " is given twice, see channels[" + i + "]");
        }
        final String host = fields.string("host");
        if (host.isEmpty() || host.length() > MAX_HOST_LENGTH)
            throw fields.invalid("host is not 1 to " + MAX_HOST_LENGTH + " characters");
        final int port = fields.integer("port");
        if (port < 1 || port > MAX_PORT) throw fields.invalid("port " + port + " is not from 1 to " + MAX_PORT);
        final int capacity = fields.integer("capacity");
        if (capacity < 1 || capacity > MAX_CAPACITY)
            throw fields.invalid("capacity " + capacity + " is not from 1 to " + MAX_CAPACITY);
        return new Channel(shardId, regionId, channel, host, port, capacity);
    }

    /**
     * The channel of this shard.
     *
     * @return the channel whose shard id is {@link #shardId()}
     * @throws IllegalStateException if there is none, which {@link #load} refuses
     */
    public Channel ownChannel() {
        for (Channel channel : channels) {
            if (channel.shardId().equals(shardId)) return channel;
        }
        throw new IllegalStateException(shardId + " is not a channel of the shard directory");
    }

    /**
     * The region of this shard.
     *
     * @return the region of {@link #ownChannel()}
     * @throws IllegalStateException if the directory has no such region, which {@link #load} refuses
     */
    public Region ownRegion() {
        final String regionId = ownChannel().regionId();
        final Region region = region(regionId);
        if (region == null) throw new IllegalStateException(regionId + " is not a region of the shard directory");
        return region;
    }

    /**
     * A region by id.
     *
     * @param regionId the region id
     * @return the region, null if the directory has none of that id
     */
    public @Nullable Region region(String regionId) {
        for (Region region : regions) {
            if (region.regionId().equals(regionId)) return region;
        }
        return null;
    }

    /**
     * The gate of this shard's region that leads to a region; there is at most one.
     *
     * @param targetRegion the region it leads to
     * @return the gate, null if this shard's region has none leading there
     */
    public @Nullable Gate gateTo(String targetRegion) {
        for (Gate gate : ownRegion().gates()) {
            if (gate.targetRegion().equals(targetRegion)) return gate;
        }
        return null;
    }

    /**
     * The region a shard id names: what comes before its last {@code -c}.
     *
     * @param shardId a shard id, {@code <region_id>-c<channel>}
     * @return the region id, null if the shard id is not written that way
     */
    public static @Nullable String regionOfShard(String shardId) {
        final int separator = shardId.lastIndexOf(CHANNEL_SEPARATOR);
        return separator <= 0 ? null : shardId.substring(0, separator);
    }
}
