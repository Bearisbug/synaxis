package io.github.bearisbug.synaxis.game.client.farview;

import io.github.bearisbug.synaxis.game.protocol.FarViewAnnouncement;
import java.io.IOException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The far view of one connection: its announcement, the library it selects and where that library
 * is in the state machine of {@code ENT-FarViewLibrary} ({@code docs/DESIGN-M3.md} §11.3). It lives
 * from the announcement to the end of the connection; {@code downloading} and {@code baking} exist
 * only here, {@code ready} is the completion marker on disk.
 * <p>
 * Two things start the bake and the later one does: the last object arriving in the cache (or it
 * being there already) and Distant Horizons loading the level ({@link #levelLoaded}). A library whose
 * marker matches downloads nothing and is checked against DH's rows once the level loads. A cancel
 * (disconnect, level or world unload) stops a download or a bake at its next check.
 */
public final class FarViewConnection {

    private static final Logger LOGGER = LoggerFactory.getLogger("synaxis");

    /** Where the library of this connection is, as far as this connection knows. */
    public enum State {
        /** Not ready, and nothing under way. */
        ABSENT,
        /** Objects are being downloaded. */
        DOWNLOADING,
        /** Data sources are being handed to DH, or the library is being checked. */
        BAKING,
        /** The library holds the whole announcement. */
        READY
    }

    /** The DH side's bake, started on a thread of its own once objects and level are both there. */
    @FunctionalInterface
    public interface Baker {
        /**
         * Starts the bake or, with a matching marker, the load check.
         *
         * @param connection this connection
         * @param level      DH's level wrapper, an {@code IDhApiLevelWrapper}
         * @param loadCheck  whether the marker matched and only the library's rows are to be checked
         */
        void start(FarViewConnection connection, Object level, boolean loadCheck);
    }

    /**
     * The readings of a finished bake (the bake log line, §15 远景准备行).
     *
     * @param ms           from the bake thread starting to the marker
     * @param writeMs      from the bake thread starting to the row count check passing
     * @param visibleMs    from the announcement to the marker
     * @param dbBytes      the three SQLite files at that moment
     * @param sources      the data sources handed to DH
     * @param fromLevelMs  from DH loading the level to the marker
     */
    public record Timings(long ms, long writeMs, long visibleMs, long dbBytes, long sources, long fromLevelMs) {
    }

    private final @Nullable FarViewAnnouncement announcement;
    private final long announcedNanos;
    private final FarViewSettings settings;
    private final boolean available;
    private final String viewKey;
    private final String dhVersion;
    private final FarViewCache cache;
    private final FarViewDownloader downloader;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<State> state = new AtomicReference<>(State.ABSENT);
    private final AtomicLong done = new AtomicLong();
    private final AtomicLong total = new AtomicLong();
    private volatile FarViewCache.@Nullable Marker marker;
    private volatile @Nullable Timings timings;
    private volatile long downloadMillis = -1;
    private volatile long levelLoadedNanos;
    private volatile @Nullable String failure;
    private volatile boolean settled;
    private volatile long settledNanos;
    private volatile long cancelledNanos;

    // guarded by this
    private boolean packsReady;
    private boolean started;
    private @Nullable Object level;
    private @Nullable Baker baker;

    FarViewConnection(@Nullable FarViewAnnouncement announcement, long announcedNanos, FarViewSettings settings,
                      boolean available, String dhVersion, FarViewCache cache, FarViewDownloader downloader) {
        this.announcement = announcement;
        this.announcedNanos = announcedNanos;
        this.settings = settings;
        this.available = available && announcement != null;
        this.dhVersion = dhVersion;
        this.viewKey = this.available ? FarViewCache.viewKey(dhVersion, FarViewClient.BAKE_CODE_VERSION, announcement.tiles()) : "";
        this.cache = cache;
        this.downloader = downloader;
    }

    /**
     * Picks the library and starts what it needs: nothing for a matching marker, the downloads of
     * the missing objects otherwise.
     */
    void begin() {
        if (!available) return;
        final FarViewAnnouncement announced = announcement();
        final Optional<FarViewCache.Marker> found = cache.marker(viewKey);
        if (found.isPresent() && FarViewCache.matches(found.get(), viewKey, dhVersion, FarViewClient.BAKE_CODE_VERSION,
                announced.tiles())) {
            marker = found.get();
            state.set(State.READY);
            return;
        }
        final List<FarViewAnnouncement.Tile> missing = cache.missing(announced.tiles());
        if (missing.isEmpty()) {
            packsReady();
            return;
        }
        state.set(State.DOWNLOADING);
        Thread.ofPlatform().daemon().name("synaxis-farview-download").start(() -> {
            if (download(missing)) packsReady();
        });
    }

    /**
     * Downloads objects on the calling thread, with the HUD line and the log lines of the design.
     *
     * @param missing the objects
     * @return whether all are in the cache now
     */
    public boolean download(List<FarViewAnnouncement.Tile> missing) {
        state.set(State.DOWNLOADING);
        done.set(0);
        total.set(missing.stream().mapToLong(FarViewAnnouncement.Tile::bytes).sum());
        LOGGER.info("{}view {} downloading {} object(s), {} B", FarViewClient.PREFIX, viewKey, missing.size(), total.get());
        final FarViewDownloader.Result result = downloader.download(missing, cancelled, done::set);
        downloadMillis = result.millis();
        if (result.success()) {
            LOGGER.info("{}view {} downloaded {} object(s), {} B in {} ms", FarViewClient.PREFIX, viewKey, result.objects(),
                    result.bytes(), result.millis());
            return true;
        }
        if (result.failed() != null) {
            failure = "pack " + result.failed().substring(0, 12) + " failed";
            LOGGER.warn("{}Far view pack {} failed after {} attempts: {}", FarViewClient.PREFIX,
                    result.failed().substring(0, 12), FarViewDownloader.ATTEMPTS, result.reason());
        } else {
            LOGGER.info("{}view {} download stopped: {}", FarViewClient.PREFIX, viewKey, result.reason());
        }
        state.compareAndSet(State.DOWNLOADING, State.ABSENT);
        return false;
    }

    private synchronized void packsReady() {
        packsReady = true;
        maybeStart();
    }

    /**
     * DH has loaded the level: the bake starts now if the objects are there, else once they are.
     *
     * @param levelWrapper DH's level wrapper
     * @param baker        the DH side's bake
     */
    public synchronized void levelLoaded(Object levelWrapper, Baker baker) {
        if (!available || level != null) return;
        this.level = levelWrapper;
        this.baker = baker;
        levelLoadedNanos = System.nanoTime();
        maybeStart();
    }

    private void maybeStart() {
        if (started || cancelled.get() || level == null || baker == null) return;
        if (marker != null) {
            started = true;
            baker.start(this, level, true);
        } else if (packsReady && baking()) {
            started = true;
            baker.start(this, level, false);
        }
    }

    /**
     * Moves to {@code baking} unless the connection or the level has ended. It holds the lock
     * {@link #cancel} takes, so a cancel either finds {@code baking} and ends it or comes first and
     * keeps the bake from starting; the state never stays {@code baking} after a cancel.
     *
     * @return whether it did
     */
    public synchronized boolean baking() {
        if (cancelled.get()) return false;
        state.set(State.BAKING);
        return true;
    }

    /**
     * The load check of a ready library ({@code API-M3-015} 写库条第 8 步), given what DH's library
     * holds: a library holding the marker's sources stays ready, any other is baked again.
     *
     * @param rows the library's rows at the base level, null when DH's connection gave no answer
     * @return whether the library is to be baked again
     * @throws IOException if the marker cannot be rewritten or removed
     */
    public boolean loadChecked(@Nullable Long rows) throws IOException {
        final FarViewCache.Marker matched = marker;
        if (matched != null && rows != null && rows == matched.sources()) {
            cache.touch(viewKey, System.currentTimeMillis());
            state.set(State.READY);
            LOGGER.info("{}view {} ready ({} tiles)", FarViewClient.PREFIX, viewKey, announcement().tiles().size());
            return false;
        }
        // DH closes the library as the connection or the level ends: no answer then says nothing about the library
        if (rows == null && cancelled.get()) return false;
        cache.deleteMarker(viewKey);
        LOGGER.warn("{}view {} library does not match its marker: {} of {}", FarViewClient.PREFIX, viewKey,
                rows == null ? "no answer" : rows, matched == null ? "?" : matched.sources());
        return true;
    }

    /**
     * Stops whatever is under way at its next check; a ready library stays ready.
     *
     * @param why for the log
     */
    public synchronized void cancel(String why) {
        if (!cancelled.compareAndSet(false, true)) return;
        cancelledNanos = System.nanoTime();
        final State was = state.get();
        if (was == State.DOWNLOADING || was == State.BAKING) {
            state.compareAndSet(was, State.ABSENT);
            LOGGER.info("{}view {} {} stopped: {}", FarViewClient.PREFIX, viewKey,
                    was == State.DOWNLOADING ? "download" : "bake", why);
        }
    }

    /**
     * The HUD line of {@code API-M3-015} (进度与日志): download progress, then bake progress, then nothing.
     *
     * @return the line, null when there is none
     */
    public @Nullable Component hudLine() {
        final long all = total.get();
        if (all <= 0) return null;
        final long percent = Math.min(100, done.get() * 100 / all);
        return switch (state.get()) {
            case DOWNLOADING -> Component.translatable("synaxis.farview.downloading", percent);
            case BAKING -> Component.translatable("synaxis.farview.preparing", percent);
            default -> null;
        };
    }

    // ---- What the bake and the tests read and set

    /**
     * The announcement; only called on a connection whose far view is available.
     *
     * @return the announcement
     */
    public FarViewAnnouncement announcement() {
        if (announcement == null) throw new IllegalStateException("this connection has no announcement");
        return announcement;
    }

    /**
     * Whether this connection draws a far view: DH supported, a radius, an announcement.
     *
     * @return the availability of {@code API-M3-015}
     */
    public boolean available() {
        return available;
    }

    /** @return the library's key, empty when the far view is not available */
    public String viewKey() {
        return viewKey;
    }

    /** @return the settings read at the announcement */
    public FarViewSettings settings() {
        return settings;
    }

    /** @return the DH version the key was made with */
    public String dhVersion() {
        return dhVersion;
    }

    /** @return the cache */
    public FarViewCache cache() {
        return cache;
    }

    /** @return set once the connection or the level ended */
    public AtomicBoolean cancelled() {
        return cancelled;
    }

    /** @return the state */
    public State state() {
        return state.get();
    }

    /**
     * Moves the state.
     *
     * @param next the new state
     */
    public void state(State next) {
        state.set(next);
    }

    /**
     * The bake's progress for the HUD line.
     *
     * @param handed  data sources handed over
     * @param sources data sources in all
     */
    public void progress(long handed, long sources) {
        total.set(sources);
        done.set(handed);
    }

    /** @return the bytes downloaded, or the data sources handed over while baking */
    public long progressDone() {
        return done.get();
    }

    /** @return the bytes to download, or the data sources to hand over while baking */
    public long progressTotal() {
        return total.get();
    }

    /** @return the marker that matched at the announcement, null when none did */
    public FarViewCache.@Nullable Marker marker() {
        return marker;
    }

    /** @return {@link System#nanoTime()} when the announcement was handled */
    public long announcedNanos() {
        return announcedNanos;
    }

    /** @return {@link System#nanoTime()} when DH loaded the level, 0 before */
    public long levelLoadedNanos() {
        return levelLoadedNanos;
    }

    /** @return the wall clock of the download, -1 without one */
    public long downloadMillis() {
        return downloadMillis;
    }

    /** @return the readings of the bake, null until one finished */
    public @Nullable Timings timings() {
        return timings;
    }

    /**
     * Records the readings of a finished bake.
     *
     * @param readings the readings
     */
    public void timings(Timings readings) {
        timings = readings;
    }

    /** @return whether the bake thread of this connection has finished, whatever its outcome */
    public boolean settled() {
        return settled;
    }

    /** Marks the bake thread of this connection finished. */
    public void settle() {
        settledNanos = System.nanoTime();
        settled = true;
    }

    /** @return {@link System#nanoTime()} when the bake thread finished, 0 before */
    public long settledNanos() {
        return settledNanos;
    }

    /** @return {@link System#nanoTime()} when the cancel flag was set, 0 before */
    public long cancelledNanos() {
        return cancelledNanos;
    }

    /** @return why the far view went off on this connection, null while it has not */
    public @Nullable String failure() {
        return failure;
    }

    /**
     * Records why the far view went off.
     *
     * @param reason a short reason
     */
    public void failure(String reason) {
        failure = reason;
    }

    /**
     * The lower case hex of an announced object's digest.
     *
     * @param tile the object
     * @return 64 hex digits
     */
    public static String hex(FarViewAnnouncement.Tile tile) {
        return HexFormat.of().formatHex(tile.sha256());
    }

    /**
     * Milliseconds since a {@link System#nanoTime()} reading.
     *
     * @param since the reading
     * @return the milliseconds
     */
    public static long millisSince(long since) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - since);
    }
}
