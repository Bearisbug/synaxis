package io.github.bearisbug.synaxis.synx;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * {@code manifest.json} of a map release, API-M3-002. Field names on disk are the snake case ones of
 * the design; digests are lower case hex. {@link #toJson()} writes the fields in a fixed order, so
 * a manifest toJson wrote reads back and writes again byte for byte the same.
 *
 * @param contentHash    the map wide content hash, {@link SynxDigests#contentHash}
 * @param registryDigest {@link SynxDigests#registryDigest}
 * @param bounds         the chunks the release covers; every chunk inside has a record
 */
public record SynxManifest(String mapId, String buildId, byte[] contentHash, String mcVersion, int protocolVersion,
                           byte[] registryDigest, int lightEngineVersion, int bakerVersion, World world,
                           Bounds bounds, List<Region> regions, List<FarView> farview, Source source, Bake bake,
                           Verify verify) {
    public static final String FILE_NAME = "manifest.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final TypeAdapter<JsonElement> JSON = new Gson().getAdapter(JsonElement.class);

    public SynxManifest {
        contentHash = contentHash.clone();
        registryDigest = registryDigest.clone();
        regions = List.copyOf(regions);
        farview = List.copyOf(farview);
    }

    @Override
    public byte[] contentHash() {
        return contentHash.clone();
    }

    @Override
    public byte[] registryDigest() {
        return registryDigest.clone();
    }

    public record World(int minY, int height) {
    }

    /** Inclusive chunk coordinates. */
    public record Bounds(int minX, int minZ, int maxX, int maxZ) {
        public Bounds {
            if (minX > maxX || minZ > maxZ) {
                throw new IllegalArgumentException("bounds " + minX + "," + minZ + "," + maxX + "," + maxZ + " are empty");
            }
        }

        /** Whether a chunk is inside, both ends included. */
        public boolean contains(int chunkX, int chunkZ) {
            return chunkX >= minX && chunkX <= maxX && chunkZ >= minZ && chunkZ <= maxZ;
        }

        /** These bounds grown by {@code chunks} on every side. */
        public Bounds grow(int chunks) {
            return new Bounds(minX - chunks, minZ - chunks, maxX + chunks, maxZ + chunks);
        }
    }

    /** One {@code regions/r.<rx>.<rz>.synx}: its size, {@code payload_sha256} and number of chunk records. */
    public record Region(int regionX, int regionZ, long bytes, byte[] payloadSha256, int chunks) {
        public Region {
            payloadSha256 = payloadSha256.clone();
        }

        @Override
        public byte[] payloadSha256() {
            return payloadSha256.clone();
        }
    }

    /**
     * One {@code farview/t.<tile_x>.<tile_z>.hfp}, API-M3-014: the size and SHA-256 of the whole pack and of
     * its coarse prefix, the first {@code coarse_bytes} bytes, which is distributed as an object of its own.
     */
    public record FarView(int tileX, int tileZ, int baseLevel, int coarseLevel, long bytes, byte[] sha256,
                          long coarseBytes, byte[] coarseSha256) {
        public FarView {
            sha256 = sha256.clone();
            coarseSha256 = coarseSha256.clone();
        }

        @Override
        public byte[] sha256() {
            return sha256.clone();
        }

        @Override
        public byte[] coarseSha256() {
            return coarseSha256.clone();
        }
    }

    /**
     * Where the release was baked from.
     *
     * @param kind          {@code anvil} or {@code generator}
     * @param dataVersion   the source DataVersion, Anvil sources only (4903)
     * @param lightCoverage the share of source chunks with {@code isLightOn}, Anvil sources only
     */
    public record Source(String kind, @Nullable Integer dataVersion, @Nullable Double lightCoverage) {
    }

    public record Bake(String startedAt, long wallMs, long cpuMs, int threads, String jdk) {
    }

    /**
     * The result of the baker's {@code verify}.
     *
     * @param sourceLightDiff nibbles that differ from the source's own light, Anvil sources only
     */
    public record Verify(int samples, int diffs, @Nullable Long sourceLightDiff) {
    }

    /** {@code <map_id>-<first 12 hex digits of content_hash>-p<protocol_version>}. */
    public static String buildId(String mapId, byte[] contentHash, int protocolVersion) {
        return mapId + "-" + SynxDigests.hex(contentHash).substring(0, 12) + "-p" + protocolVersion;
    }

    public String toJson() {
        final JsonObject json = new JsonObject();
        json.addProperty("map_id", mapId);
        json.addProperty("build_id", buildId);
        json.addProperty("content_hash", SynxDigests.hex(contentHash));
        json.addProperty("mc_version", mcVersion);
        json.addProperty("protocol_version", protocolVersion);
        json.addProperty("registry_digest", SynxDigests.hex(registryDigest));
        json.addProperty("light_engine_version", lightEngineVersion);
        json.addProperty("baker_version", bakerVersion);
        final JsonObject worldJson = new JsonObject();
        worldJson.addProperty("min_y", world.minY());
        worldJson.addProperty("height", world.height());
        json.add("world", worldJson);
        final JsonObject boundsJson = new JsonObject();
        boundsJson.addProperty("min_x", bounds.minX());
        boundsJson.addProperty("min_z", bounds.minZ());
        boundsJson.addProperty("max_x", bounds.maxX());
        boundsJson.addProperty("max_z", bounds.maxZ());
        json.add("bounds", boundsJson);
        final JsonArray regionsJson = new JsonArray();
        for (Region region : regions) {
            final JsonObject entry = new JsonObject();
            entry.addProperty("rx", region.regionX());
            entry.addProperty("rz", region.regionZ());
            entry.addProperty("bytes", region.bytes());
            entry.addProperty("payload_sha256", SynxDigests.hex(region.payloadSha256));
            entry.addProperty("chunks", region.chunks());
            regionsJson.add(entry);
        }
        json.add("regions", regionsJson);
        final JsonArray farviewJson = new JsonArray();
        for (FarView pack : farview) {
            final JsonObject entry = new JsonObject();
            entry.addProperty("tile_x", pack.tileX());
            entry.addProperty("tile_z", pack.tileZ());
            entry.addProperty("base_level", pack.baseLevel());
            entry.addProperty("coarse_level", pack.coarseLevel());
            entry.addProperty("bytes", pack.bytes());
            entry.addProperty("sha256", SynxDigests.hex(pack.sha256));
            entry.addProperty("coarse_bytes", pack.coarseBytes());
            entry.addProperty("coarse_sha256", SynxDigests.hex(pack.coarseSha256));
            farviewJson.add(entry);
        }
        json.add("farview", farviewJson);
        final JsonObject sourceJson = new JsonObject();
        sourceJson.addProperty("kind", source.kind());
        if (source.dataVersion() != null) sourceJson.addProperty("data_version", source.dataVersion());
        if (source.lightCoverage() != null) sourceJson.addProperty("light_coverage", source.lightCoverage());
        json.add("source", sourceJson);
        final JsonObject bakeJson = new JsonObject();
        bakeJson.addProperty("started_at", bake.startedAt());
        bakeJson.addProperty("wall_ms", bake.wallMs());
        bakeJson.addProperty("cpu_ms", bake.cpuMs());
        bakeJson.addProperty("threads", bake.threads());
        bakeJson.addProperty("jdk", bake.jdk());
        json.add("bake", bakeJson);
        final JsonObject verifyJson = new JsonObject();
        verifyJson.addProperty("samples", verify.samples());
        verifyJson.addProperty("diffs", verify.diffs());
        if (verify.sourceLightDiff() != null) verifyJson.addProperty("source_light_diff", verify.sourceLightDiff());
        json.add("verify", verifyJson);
        return GSON.toJson(json) + "\n";
    }

    /**
     * Parses a manifest.
     *
     * @throws SynxException {@link SynxError#MANIFEST} if the text is not strict JSON, an object of it gives
     *                       a name twice, a required field is missing or has the wrong type, a digest is not 32
     *                       bytes of hex, an integer field has a fraction or does not fit, or the bounds are empty
     */
    public static SynxManifest fromJson(String text) throws SynxException {
        try {
            final JsonObject json = parse(text);
            final JsonObject worldJson = object(json, "world");
            final JsonObject boundsJson = object(json, "bounds");
            final JsonObject sourceJson = object(json, "source");
            final JsonObject bakeJson = object(json, "bake");
            final JsonObject verifyJson = object(json, "verify");
            return new SynxManifest(string(json, "map_id"), string(json, "build_id"), digest(json, "content_hash"),
                    string(json, "mc_version"), integer(json, "protocol_version"), digest(json, "registry_digest"),
                    integer(json, "light_engine_version"), integer(json, "baker_version"),
                    new World(integer(worldJson, "min_y"), integer(worldJson, "height")),
                    new Bounds(integer(boundsJson, "min_x"), integer(boundsJson, "min_z"),
                            integer(boundsJson, "max_x"), integer(boundsJson, "max_z")),
                    list(json, "regions", entry -> new Region(integer(entry, "rx"), integer(entry, "rz"),
                            longInteger(entry, "bytes"), digest(entry, "payload_sha256"), integer(entry, "chunks"))),
                    list(json, "farview", entry -> new FarView(integer(entry, "tile_x"), integer(entry, "tile_z"),
                            integer(entry, "base_level"), integer(entry, "coarse_level"), longInteger(entry, "bytes"),
                            digest(entry, "sha256"), longInteger(entry, "coarse_bytes"), digest(entry, "coarse_sha256"))),
                    new Source(string(sourceJson, "kind"),
                            sourceJson.has("data_version") ? integer(sourceJson, "data_version") : null,
                            sourceJson.has("light_coverage") ? number(sourceJson, "light_coverage").doubleValue() : null),
                    new Bake(string(bakeJson, "started_at"), longInteger(bakeJson, "wall_ms"),
                            longInteger(bakeJson, "cpu_ms"), integer(bakeJson, "threads"), string(bakeJson, "jdk")),
                    new Verify(integer(verifyJson, "samples"), integer(verifyJson, "diffs"),
                            verifyJson.has("source_light_diff") ? longInteger(verifyJson, "source_light_diff") : null));
        } catch (IOException | JsonParseException | IllegalStateException | IllegalArgumentException e) {
            throw new SynxException(SynxError.MANIFEST, FILE_NAME, String.valueOf(e.getMessage()));
        }
    }

    /**
     * RFC 8259 JSON in Gson's {@link Strictness#STRICT}: no comments, single quotes or unquoted names, one
     * document and nothing after it, and no name twice in one object (M3-S): a lenient reader takes the
     * later of two, which the shard directory refuses as well.
     */
    private static JsonObject parse(String text) throws IOException {
        final UniqueNamesReader reader = new UniqueNamesReader(text);
        final JsonElement root = JSON.read(reader);
        // In Strictness.STRICT anything after the document throws a MalformedJsonException
        var _ = reader.peek();
        if (!root.isJsonObject()) throw new IllegalArgumentException("not a JSON object");
        return root.getAsJsonObject();
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
            if (!objects.element().add(name)) throw new IOException(getPath() + " is given twice");
            return name;
        }
    }

    private static JsonElement field(JsonObject json, String name) {
        final JsonElement element = json.get(name);
        if (element == null || element.isJsonNull()) throw new IllegalArgumentException("missing field " + name);
        return element;
    }

    private static JsonObject object(JsonObject json, String name) {
        return field(json, name).getAsJsonObject();
    }

    private static JsonPrimitive primitive(JsonObject json, String name) {
        final JsonElement element = field(json, name);
        if (!element.isJsonPrimitive()) throw new IllegalArgumentException(name + " is not a value");
        return element.getAsJsonPrimitive();
    }

    private static String string(JsonObject json, String name) {
        final JsonPrimitive value = primitive(json, name);
        if (!value.isString()) throw new IllegalArgumentException(name + " is not a string");
        return value.getAsString();
    }

    private static Number number(JsonObject json, String name) {
        final JsonPrimitive value = primitive(json, name);
        if (!value.isNumber()) throw new IllegalArgumentException(name + " is not a number");
        return value.getAsNumber();
    }

    /** A whole number that fits an int, read from its text: 4.0 is 4, 4.5 is refused naming the field. */
    private static int integer(JsonObject json, String name) {
        final Number number = number(json, name);
        try {
            return new BigDecimal(number.toString()).intValueExact();
        } catch (ArithmeticException _) {
            throw new IllegalArgumentException(name + " is not an integer: " + number);
        }
    }

    /** {@link #integer} for a long. */
    private static long longInteger(JsonObject json, String name) {
        final Number number = number(json, name);
        try {
            return new BigDecimal(number.toString()).longValueExact();
        } catch (ArithmeticException _) {
            throw new IllegalArgumentException(name + " is not an integer: " + number);
        }
    }

    private static byte[] digest(JsonObject json, String name) {
        final String text = string(json, name);
        final byte[] bytes;
        try {
            bytes = SynxDigests.unhex(text);
        } catch (IllegalArgumentException _) {
            throw new IllegalArgumentException(name + " is not hex: " + text);
        }
        if (bytes.length != 32) throw new IllegalArgumentException(name + " is not 32 bytes");
        return bytes;
    }

    private static <T> List<T> list(JsonObject json, String name, Function<JsonObject, T> reader) {
        final List<T> list = new ArrayList<>();
        for (JsonElement element : field(json, name).getAsJsonArray()) list.add(reader.apply(element.getAsJsonObject()));
        return list;
    }
}
