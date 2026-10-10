package io.github.bearisbug.synaxis.game.client.farview;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.bearisbug.synaxis.game.protocol.FarViewAnnouncement;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The far view cache of {@code docs/DESIGN-M3.md} {@code API-M3-015} (缓存条) under
 * {@code <gameDir>/synaxis/farview/}: {@code packs/<sha256>.hfp}, the objects downloaded and checked
 * (whole packs and coarse prefixes alike, named by their own digest, {@code .part} while they
 * download), and {@code dh/<view_key>/}, one Distant Horizons library per announcement content
 * ({@code ENT-FarViewLibrary}, §11.3) with its completion marker {@value #MARKER}. A {@code .part} lives
 * at most until the next announcement that does not name it, a day, or the next cleaning after a
 * bake ({@link #dropStaleParts}, {@link #clean}; M3-S {@code REQ-M3-032}); one that cannot be deleted
 * is logged and tried again the next time.
 */
public final class FarViewCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("synaxis");
    private static final String PART = ".hfp.part";

    /** The completion marker in a library directory. */
    public static final String MARKER = "synaxis-library.json";
    /** The layout of the marker. */
    public static final int MARKER_FORMAT = 1;
    /** What the whole of {@code synaxis/farview/} may hold before libraries are dropped: 1 GiB. */
    public static final long LIMIT_BYTES = 1L << 30;
    /** A {@code .part} modified longer ago than this is dropped, named by the announcement or not. */
    public static final Duration STALE_PART = Duration.ofHours(24);

    private final Path root;

    /**
     * @param root {@code <gameDir>/synaxis/farview}
     */
    public FarViewCache(Path root) {
        this.root = root;
    }

    /**
     * The cache's directory.
     *
     * @return {@code <gameDir>/synaxis/farview}
     */
    public Path root() {
        return root;
    }

    /**
     * Where a checked object is kept.
     *
     * @param sha256 the lower case hex of its digest
     * @return {@code packs/<sha256>.hfp}
     */
    public Path pack(String sha256) {
        return root.resolve("packs").resolve(sha256 + ".hfp");
    }

    /**
     * Where an object is written while it downloads.
     *
     * @param sha256 the lower case hex of its digest
     * @return {@code packs/<sha256>.hfp.part}
     */
    public Path part(String sha256) {
        return root.resolve("packs").resolve(sha256 + ".hfp.part");
    }

    /**
     * A library's directory, the one Distant Horizons is pinned to.
     *
     * @param viewKey the library's key
     * @return {@code dh/<view_key>}
     */
    public Path library(String viewKey) {
        return root.resolve("dh").resolve(viewKey);
    }

    /**
     * The key of the library an announcement bakes into: the first 16 hex digits of the SHA-256 of
     * {@code "synaxis-farview 1\n" + dhVersion + "\n" + bakeCodeVersion + "\n"} and a line
     * {@code "<tile_x> <tile_z> <layer> <sha256>\n"} per tile by x and then z. The build id is not
     * in it: two releases with the same tiles share a library.
     *
     * @param dhVersion       the Distant Horizons version
     * @param bakeCodeVersion {@link FarViewClient#BAKE_CODE_VERSION}
     * @param tiles           the tiles of the announcement
     * @return 16 lower case hex digits
     */
    public static String viewKey(String dhVersion, int bakeCodeVersion, List<FarViewAnnouncement.Tile> tiles) {
        final StringBuilder text = new StringBuilder("synaxis-farview 1\n").append(dhVersion).append('\n')
                .append(bakeCodeVersion).append('\n');
        final List<FarViewAnnouncement.Tile> ordered = new ArrayList<>(tiles);
        ordered.sort(Comparator.comparingInt(FarViewAnnouncement.Tile::tileX).thenComparingInt(FarViewAnnouncement.Tile::tileZ));
        for (FarViewAnnouncement.Tile tile : ordered) {
            text.append(tile.tileX()).append(' ').append(tile.tileZ()).append(' ').append(tile.layer()).append(' ')
                    .append(HexFormat.of().formatHex(tile.sha256())).append('\n');
        }
        return HexFormat.of().formatHex(sha256(text.toString().getBytes(StandardCharsets.UTF_8))).substring(0, 16);
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * One tile of a marker.
     *
     * @param tileX  the tile
     * @param tileZ  the tile
     * @param layer  0 whole, 1 coarse
     * @param sha256 the lower case hex of the object's digest
     */
    public record MarkerTile(int tileX, int tileZ, int layer, String sha256) {

        /**
         * The marker tile of an announced one.
         *
         * @param tile the announced tile
         * @return its marker form
         */
        public static MarkerTile of(FarViewAnnouncement.Tile tile) {
            return new MarkerTile(tile.tileX(), tile.tileZ(), tile.layer(), HexFormat.of().formatHex(tile.sha256()));
        }
    }

    /**
     * The completion marker {@value #MARKER}: the library holds every data source of these tiles.
     *
     * @param format          {@value #MARKER_FORMAT}
     * @param viewKey         the library's key
     * @param dhVersion       the Distant Horizons version it was baked with
     * @param bakeCodeVersion the {@link FarViewClient#BAKE_CODE_VERSION} it was baked with
     * @param tiles           the tiles, as announced
     * @param sources         the data sources handed to DH, which its rows at the base level equal
     * @param bakeMs          how long the bake took
     * @param bakedAt         when it was finished, epoch milliseconds
     * @param lastUsed        when a connection last took it, epoch milliseconds
     */
    public record Marker(int format, String viewKey, String dhVersion, int bakeCodeVersion, List<MarkerTile> tiles,
                         long sources, long bakeMs, long bakedAt, long lastUsed) {
    }

    /**
     * The marker of a library.
     *
     * @param viewKey the library's key
     * @return the marker, empty when there is none or it cannot be read
     */
    public Optional<Marker> marker(String viewKey) {
        final Path file = library(viewKey).resolve(MARKER);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            final JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            final List<MarkerTile> tiles = new ArrayList<>();
            for (JsonElement element : json.getAsJsonArray("tiles")) {
                final JsonObject tile = element.getAsJsonObject();
                tiles.add(new MarkerTile(tile.get("tile_x").getAsInt(), tile.get("tile_z").getAsInt(), tile.get("layer").getAsInt(),
                        tile.get("sha256").getAsString()));
            }
            return Optional.of(new Marker(json.get("format").getAsInt(), json.get("view_key").getAsString(),
                    json.get("dh_version").getAsString(), json.get("bake_code_version").getAsInt(), List.copyOf(tiles),
                    json.get("sources").getAsLong(), json.get("bake_ms").getAsLong(), json.get("baked_at").getAsLong(),
                    json.get("last_used").getAsLong()));
        } catch (IOException | RuntimeException _) {
            return Optional.empty();
        }
    }

    /**
     * Whether a marker says its library holds this announcement as this build bakes it.
     *
     * @param marker          the marker
     * @param viewKey         the key of the announcement
     * @param dhVersion       the Distant Horizons version
     * @param bakeCodeVersion the bake code version
     * @param tiles           the tiles of the announcement
     * @return true when every field agrees
     */
    public static boolean matches(Marker marker, String viewKey, String dhVersion, int bakeCodeVersion,
                                  List<FarViewAnnouncement.Tile> tiles) {
        return marker.format() == MARKER_FORMAT && marker.viewKey().equals(viewKey) && marker.dhVersion().equals(dhVersion)
                && marker.bakeCodeVersion() == bakeCodeVersion
                && marker.tiles().equals(tiles.stream().map(MarkerTile::of).toList());
    }

    /**
     * Writes a marker into its library: to {@code .tmp} first, then renamed over the old one.
     *
     * @param marker the marker
     * @throws IOException if it cannot be written
     */
    public void writeMarker(Marker marker) throws IOException {
        final JsonObject json = new JsonObject();
        json.addProperty("format", marker.format());
        json.addProperty("view_key", marker.viewKey());
        json.addProperty("dh_version", marker.dhVersion());
        json.addProperty("bake_code_version", marker.bakeCodeVersion());
        final JsonArray tiles = new JsonArray();
        for (MarkerTile tile : marker.tiles()) {
            final JsonObject entry = new JsonObject();
            entry.addProperty("tile_x", tile.tileX());
            entry.addProperty("tile_z", tile.tileZ());
            entry.addProperty("layer", tile.layer());
            entry.addProperty("sha256", tile.sha256());
            tiles.add(entry);
        }
        json.add("tiles", tiles);
        json.addProperty("sources", marker.sources());
        json.addProperty("bake_ms", marker.bakeMs());
        json.addProperty("baked_at", marker.bakedAt());
        json.addProperty("last_used", marker.lastUsed());
        final Path library = library(marker.viewKey());
        Files.createDirectories(library);
        final Path temporary = library.resolve(MARKER + ".tmp");
        Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(json) + "\n", StandardCharsets.UTF_8);
        Files.move(temporary, library.resolve(MARKER), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Removes a library's marker, which puts the library back to {@code absent}.
     *
     * @param viewKey the library's key
     * @throws IOException if it cannot be removed
     */
    public void deleteMarker(String viewKey) throws IOException {
        Files.deleteIfExists(library(viewKey).resolve(MARKER));
    }

    /**
     * Sets a marker's {@code last_used}; nothing without a marker.
     *
     * @param viewKey the library's key
     * @param now     epoch milliseconds
     * @throws IOException if the marker cannot be rewritten
     */
    public void touch(String viewKey, long now) throws IOException {
        final Optional<Marker> found = marker(viewKey);
        if (found.isEmpty()) return;
        final Marker marker = found.get();
        writeMarker(new Marker(marker.format(), marker.viewKey(), marker.dhVersion(), marker.bakeCodeVersion(), marker.tiles(),
                marker.sources(), marker.bakeMs(), marker.bakedAt(), now));
    }

    /**
     * The announced objects not in {@code packs/}.
     *
     * @param tiles the tiles of an announcement
     * @return those whose object is missing, in announcement order
     */
    public List<FarViewAnnouncement.Tile> missing(List<FarViewAnnouncement.Tile> tiles) {
        return tiles.stream().filter(tile -> !Files.isRegularFile(pack(HexFormat.of().formatHex(tile.sha256())))).toList();
    }

    /**
     * The bytes under the cache's directory.
     *
     * @return the sum of the sizes of its files
     * @throws IOException if it cannot be walked
     */
    public long size() throws IOException {
        if (!Files.exists(root)) return 0;
        try (Stream<Path> files = Files.walk(root)) {
            long bytes = 0;
            for (Path file : files.filter(Files::isRegularFile).toList()) bytes += Files.size(file);
            return bytes;
        }
    }

    /**
     * What a cleaning removed.
     *
     * @param libraries the keys of the library directories removed, oldest first
     * @param packs     the digests of the objects removed
     * @param parts     the digests of the {@code .part} files removed
     * @param size      the bytes left
     */
    public record Cleaned(List<String> libraries, List<String> packs, List<String> parts, long size) {
    }

    /**
     * Removes every {@code .part}, then holds the cache to {@code limit}: while it is over, removes the
     * library least recently used (one without a marker counts as the oldest) other than {@code inUse},
     * then every object no remaining marker names. It runs once a bake succeeded, when this connection
     * has nothing downloading.
     *
     * @param limit the bytes the cache may hold
     * @param inUse the key of the library this connection uses, never removed
     * @return what was removed
     * @throws IOException if the cache cannot be walked
     */
    public Cleaned clean(long limit, String inUse) throws IOException {
        final List<String> partsDropped = new ArrayList<>();
        for (Path part : parts()) {
            if (delete(part)) partsDropped.add(sha256Of(part));
        }
        final List<String> libraries = new ArrayList<>();
        final List<String> packs = new ArrayList<>();
        long size = size();
        while (size > limit) {
            // Every library but the one in use, least recently used first; one without a marker before any with one
            final List<String> candidates = new ArrayList<>();
            final Path dh = root.resolve("dh");
            if (Files.isDirectory(dh)) {
                try (Stream<Path> dirs = Files.list(dh)) {
                    for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                        final String key = dir.getFileName().toString();
                        if (!key.equals(inUse)) candidates.add(key);
                    }
                }
            }
            if (candidates.isEmpty()) break;
            candidates.sort(Comparator.comparingLong((String key) -> marker(key).map(Marker::lastUsed).orElse(Long.MIN_VALUE))
                    .thenComparing(Comparator.naturalOrder()));
            final String oldest = candidates.getFirst();
            deleteTree(library(oldest));
            libraries.add(oldest);
            packs.addAll(dropUnnamedPacks());
            size = size();
        }
        return new Cleaned(List.copyOf(libraries), List.copyOf(packs), List.copyOf(partsDropped), size);
    }

    /**
     * Drops the {@code .part} files left behind: at an announcement ({@code named} its objects' digests)
     * those it does not name and those not modified for {@link #STALE_PART}, a named one included, which
     * is then downloaded from the start; at the client's start ({@code named} null) only the stale ones.
     * One that cannot be deleted, a file another process holds open on Windows, is logged and skipped.
     *
     * @param named the lower case hex digests of the objects of the announcement, null at start
     * @param now   epoch milliseconds
     * @return the digests of the {@code .part} files dropped, in order
     */
    public List<String> dropStaleParts(@Nullable Set<String> named, long now) {
        final List<String> dropped = new ArrayList<>();
        final long staleBefore = now - STALE_PART.toMillis();
        for (Path part : parts()) {
            final String sha256 = sha256Of(part);
            boolean stale;
            try {
                stale = Files.getLastModifiedTime(part).toMillis() < staleBefore;
            } catch (IOException _) {
                stale = false;
            }
            if (!stale && (named == null || named.contains(sha256))) continue;
            if (delete(part)) dropped.add(sha256);
        }
        return dropped;
    }

    /** The {@code .part} files under {@code packs/}, in order; none when it cannot be listed. */
    private List<Path> parts() {
        final Path packs = root.resolve("packs");
        if (!Files.isDirectory(packs)) return List.of();
        try (Stream<Path> files = Files.list(packs)) {
            return files.filter(file -> file.getFileName().toString().endsWith(PART)).sorted().toList();
        } catch (IOException e) {
            LOGGER.warn("{}Far view cache {} cannot be listed: {}", FarViewClient.PREFIX, packs, e.toString());
            return List.of();
        }
    }

    private static String sha256Of(Path part) {
        final String name = part.getFileName().toString();
        return name.substring(0, name.length() - PART.length());
    }

    /** Deletes one file of the cache; true when it is gone. */
    private static boolean delete(Path file) {
        try {
            Files.deleteIfExists(file);
            return true;
        } catch (IOException e) {
            LOGGER.warn("{}Far view cache {} cannot be deleted: {}", FarViewClient.PREFIX, file.getFileName(), e.toString());
            return false;
        }
    }

    /** Removes every object no remaining marker names, and returns their digests. */
    private List<String> dropUnnamedPacks() throws IOException {
        final Set<String> named = new HashSet<>();
        final Path dh = root.resolve("dh");
        if (Files.isDirectory(dh)) {
            try (Stream<Path> dirs = Files.list(dh)) {
                for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                    marker(dir.getFileName().toString()).ifPresent(m -> m.tiles().forEach(tile -> named.add(tile.sha256())));
                }
            }
        }
        final List<String> dropped = new ArrayList<>();
        final Path packs = root.resolve("packs");
        if (!Files.isDirectory(packs)) return dropped;
        try (Stream<Path> files = Files.list(packs)) {
            for (Path file : files.sorted().toList()) {
                final String name = file.getFileName().toString();
                if (!name.endsWith(".hfp")) continue;
                final String sha256 = name.substring(0, name.length() - ".hfp".length());
                if (named.contains(sha256)) continue;
                Files.deleteIfExists(file);
                dropped.add(sha256);
            }
        }
        return dropped;
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
