package io.github.bearisbug.synaxis.synx;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * {@code manifest.json} of a map release, API-M3-002. Field names on disk are the snake case ones of
 * the design; digests are lower case hex. {@link #toJson()} writes the fields in a fixed order, so
 * a manifest read and written back is byte for byte the same file.
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
            if (minX > maxX || minZ > maxZ) throw new IllegalArgumentException("empty bounds");
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
     * @throws SynxException {@link SynxError#MANIFEST} if the text is not JSON, a required field is
     *                       missing or has the wrong type, or a digest is not 32 bytes of hex
     */
    public static SynxManifest fromJson(String text) throws SynxException {
        try {
            final JsonObject json = JsonParser.parseString(text).getAsJsonObject();
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
        } catch (JsonParseException | IllegalStateException | IllegalArgumentException | ArithmeticException e) {
            throw new SynxException(SynxError.MANIFEST, FILE_NAME, String.valueOf(e.getMessage()));
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

    /** @throws ArithmeticException if the number has a fraction or does not fit an int */
    private static int integer(JsonObject json, String name) {
        return new BigDecimal(number(json, name).toString()).intValueExact();
    }

    /** @throws ArithmeticException if the number has a fraction or does not fit a long */
    private static long longInteger(JsonObject json, String name) {
        return new BigDecimal(number(json, name).toString()).longValueExact();
    }

    private static byte[] digest(JsonObject json, String name) {
        final byte[] bytes = SynxDigests.unhex(string(json, name));
        if (bytes.length != 32) throw new IllegalArgumentException(name + " is not 32 bytes");
        return bytes;
    }

    private static <T> List<T> list(JsonObject json, String name, Function<JsonObject, T> reader) {
        final List<T> list = new ArrayList<>();
        for (JsonElement element : field(json, name).getAsJsonArray()) list.add(reader.apply(element.getAsJsonObject()));
        return list;
    }
}
