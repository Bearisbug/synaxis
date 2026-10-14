package io.github.bearisbug.synaxis.game.client.farview;

import io.github.bearisbug.synaxis.game.protocol.FarViewAnnouncement;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
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
        /**
         * Data sources are being handed to DH, or their rows counted and the parent propagation waited for. A ready
         * library's load check runs in {@code READY}.
         */
        BAKING,
        /** The library holds the whole announcement. */
        READY
    }

    /**
     * The DH side's bake. {@link #start} is called holding this connection's lock, the one
     * {@link FarViewConnection#cancel} takes, so it only hands the work to a thread of its own and returns.
     */
    @FunctionalInterface
    public interface Baker {
        /**
         * Starts the bake or, with a matching marker, the load check, which goes on to download and bake
         * when the library fails it ({@code API-M3-015} 写库条第 8 步).
         *
         * @param connection this connection
         * @param level      DH's level wrapper, an {@code IDhApiLevelWrapper}
         * @param loadCheck  whether the marker matched, so the library's rows are counted before anything is baked
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
    private final FarViewCache cache;
    private final FarViewDownloader downloader;
    private final @Nullable InetAddress server;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<State> state = new AtomicReference<>(State.ABSENT);
    private final AtomicLong done = new AtomicLong();
    private final AtomicLong total = new AtomicLong();
    private volatile FarViewCache.@Nullable Marker marker;
    private volatile @Nullable Timings timings;
    private volatile long downloadMillis = -1;
    private volatile long levelLoadedNanos;
    private volatile @Nullable String failure;
    private volatile long settledNanos;
    private volatile long cancelledNanos;

    // guarded by this
    private boolean packsReady;
    private boolean started;
    private @Nullable Object level;
    private @Nullable Baker baker;

    /**
     * @param announcement   the announcement, null for none or one this client does not take
     * @param announcedNanos {@link System#nanoTime()} when it was handled
     * @param settings       the player's setting
     * @param available      whether this connection draws a far view
     * @param cache          the cache
     * @param downloader     the downloads
     * @param server         the address of the server of this connection, null when unknown
     */
    FarViewConnection(@Nullable FarViewAnnouncement announcement, long announcedNanos, FarViewSettings settings,
                      boolean available, FarViewCache cache, FarViewDownloader downloader,
                      @Nullable InetAddress server) {
        this.announcement = announcement;
        this.announcedNanos = announcedNanos;
        this.settings = settings;
        this.available = available && announcement != null;
        this.viewKey = this.available ? FarViewCache.viewKey(FarViewClient.DH_VERSION, FarViewClient.BAKE_CODE_VERSION,
                announcement.tiles()) : "";
        this.cache = cache;
        this.downloader = downloader;
        this.server = server;
    }

    /**
     * Picks the library and starts what it needs: nothing for a matching marker, the downloads of
     * the missing objects on a thread of their own otherwise.
     */
    void begin() {
        begin(download -> Thread.ofPlatform().daemon().name("synaxis-farview-download").start(download));
    }

    /**
     * {@link #begin()} with the downloads handed to {@code downloads}.
     *
     * @param downloads runs the download of the missing objects
     */
    void begin(Executor downloads) {
        if (!available) return;
        final FarViewAnnouncement announced = announcement();
        final Optional<FarViewCache.Marker> found = cache.marker(viewKey);
        if (found.isPresent() && FarViewCache.matches(found.get(), viewKey, FarViewClient.DH_VERSION,
                FarViewClient.BAKE_CODE_VERSION, announced.tiles())) {
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
        downloads.execute(() -> {
            if (download(missing)) packsReady();
        });
    }

    /**
     * Downloads objects on the calling thread, with the HUD line and the log lines of the design; none
     * when their host resolves to a local address and the server is not local ({@code API-M3-015}
     * 下载条 ⑥).
     *
     * @param missing the objects, at least one
     * @return whether all are in the cache now
     */
    public boolean download(List<FarViewAnnouncement.Tile> missing) {
        state.set(State.DOWNLOADING);
        final String refusal = localRefusal(missing);
        if (refusal != null) {
            failure = "local address";
            LOGGER.warn("{}Far view off: {}", FarViewClient.PREFIX, refusal);
            state.compareAndSet(State.DOWNLOADING, State.ABSENT);
            return false;
        }
        done.set(0);
        total.set(missing.stream().mapToLong(FarViewAnnouncement.Tile::bytes).sum());
        // Where the objects come from, so a failure in a player's log points at a host: admitted() holds them to one
        LOGGER.info("{}view {} downloading {} object(s), {} B from {}", FarViewClient.PREFIX, viewKey, missing.size(),
                total.get(), FarViewClient.origin(missing.getFirst().url()));
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
                    result.failed().substring(0, 12), result.attempts(), result.reason());
        } else if (result.reason().equals(FarViewDownloader.CANCELLED)) {
            LOGGER.info("{}view {} download stopped: {}", FarViewClient.PREFIX, viewKey, result.reason());
        } else {
            failure = result.reason();
            LOGGER.warn("{}Far view off: view {} download stopped: {}", FarViewClient.PREFIX, viewKey, result.reason());
        }
        state.compareAndSet(State.DOWNLOADING, State.ABSENT);
        return false;
    }

    /**
     * Why the host of these objects may not be asked on this connection, null when it may: the local address
     * rule ({@code API-M3-015} 下载条 ⑥). A host that resolves to a {@link #local} address is asked only when
     * the server is at one too, so a server cannot point the client at the player's own network. The first URL
     * stands for all: {@link FarViewClient#admitted} takes an announcement only when its URLs share one scheme,
     * host and port.
     *
     * @param tiles the objects, at least one, of an admitted announcement
     * @return why the host may not be asked, null when it may
     */
    private @Nullable String localRefusal(List<FarViewAnnouncement.Tile> tiles) {
        final String host = URI.create(tiles.getFirst().url()).getHost();
        final InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(host);
        } catch (UnknownHostException _) {
            // The downloads fail on their own
            return null;
        }
        if (server != null && local(server)) return null;
        for (InetAddress address : resolved) {
            if (local(address)) {
                return host + " resolves to the local address " + address.getHostAddress() + ", the server "
                        + (server == null ? "(unknown)" : server.getHostAddress()) + " is not local";
            }
        }
        return null;
    }

    /**
     * Whether an address is loopback (127/8, ::1), private (10/8, 172.16/12, 192.168/16, fc00::/7), shared
     * (100.64/10, RFC 6598: carrier grade NAT, and Tailscale's tailnet), link local (169.254/16, fe80::/10) or
     * unspecified.
     *
     * @param address the address
     * @return whether it is
     */
    static boolean local(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()) return true;
        final byte[] bytes = address.getAddress();
        // fc00::/7, unique local: isSiteLocalAddress covers only the old IPv6 site local fec0::/10
        if (bytes.length == 16) return (bytes[0] & 0xFE) == 0xFC;
        // 100.64.0.0/10, shared: no public address, and not site local to the JDK either
        return (bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 64;
    }

    private synchronized void packsReady() {
        packsReady = true;
        maybeStart();
    }

    /**
     * DH has loaded the level: a matching marker's load check starts now; otherwise the bake starts now if
     * the objects are there, else once they are. Only the connection's first level load counts: once its
     * level is unloaded within the connection (a dimension change, a new configuration phase), which cancels
     * it, the connection draws no far view, the known limitation of {@code docs/DESIGN-M3.md} §28.2.
     *
     * @param levelWrapper DH's level wrapper
     * @param baker        the DH side's bake
     */
    public synchronized void levelLoaded(Object levelWrapper, Baker baker) {
        if (!available || level != null || cancelled.get()) return;
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
     * keeps the bake from starting; the state never stays {@code baking} after a cancel. The HUD line
     * starts the bake from nothing handed over, not from the download's bytes.
     *
     * @return whether it did
     */
    public synchronized boolean baking() {
        if (cancelled.get()) return false;
        done.set(0);
        state.set(State.BAKING);
        return true;
    }

    /**
     * Runs the load check of a ready library ({@code API-M3-015} 写库条第 8 步) on what DH's library holds;
     * true when the library is to be baked again. A library holding the marker's sources stays ready with
     * its {@code last_used} updated; a marker that cannot be rewritten costs only a WARN line, since
     * {@code last_used} only orders the cleaning. Any other library loses its marker, which puts it back
     * to {@code absent}.
     *
     * @param rows the library's rows at the base level, null when DH's connection gave no answer
     * @return whether the library is to be baked again
     * @throws IOException if the marker cannot be removed
     */
    public boolean rebakeAfterLoadCheck(@Nullable Long rows) throws IOException {
        final FarViewCache.Marker matched = marker;
        if (matched != null && rows != null && rows == matched.sources()) {
            state.set(State.READY);
            LOGGER.info("{}view {} ready ({} tiles)", FarViewClient.PREFIX, viewKey, announcement().tiles().size());
            try {
                cache.touch(viewKey, System.currentTimeMillis());
            } catch (IOException e) {
                // The library checked out: last_used only orders the cleaning
                LOGGER.warn("{}view {} last_used not updated: {}", FarViewClient.PREFIX, viewKey, e.toString());
            }
            return false;
        }
        // DH closes the library as the connection or the level ends: no answer then says nothing about the library
        if (rows == null && cancelled.get()) return false;
        LOGGER.warn("{}view {} library does not match its marker: {} of {}", FarViewClient.PREFIX, viewKey,
                rows == null ? "no answer" : rows, matched == null ? "?" : matched.sources());
        cache.deleteMarker(viewKey);
        state.set(State.ABSENT);
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
        // Nothing starts after a cancel: DH's level wrapper, and the client level it holds, need not outlive the connection
        level = null;
        baker = null;
        final State was = state.get();
        if (was == State.DOWNLOADING || was == State.BAKING) {
            state.compareAndSet(was, State.ABSENT);
            LOGGER.info("{}view {} {} stopped: {}", FarViewClient.PREFIX, viewKey,
                    was == State.DOWNLOADING ? "download" : "bake", why);
        }
    }

    /**
     * The HUD line of {@code API-M3-015} (进度与日志): download progress, then bake progress, then the
     * finishing line once every source is handed over, then nothing.
     *
     * @return the line, null when there is none
     */
    public @Nullable Component hudLine() {
        final long all = total.get();
        if (all <= 0) return null;
        final long handed = done.get();
        final long percent = Math.min(100, handed * 100 / all);
        return switch (state.get()) {
            case DOWNLOADING -> Component.translatable("synaxis.farview.downloading", percent);
            // Every source handed over: the row check and the parent propagation left are DH's, with nothing to count
            case BAKING -> handed >= all ? Component.translatable("synaxis.farview.finishing")
                    : Component.translatable("synaxis.farview.preparing", percent);
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

    /** @return the cache */
    public FarViewCache cache() {
        return cache;
    }

    /** @return whether the connection or the level ended */
    public boolean cancelled() {
        return cancelled.get();
    }

    /** @return the state */
    public State state() {
        return state.get();
    }

    /**
     * Moves the state from the bake thread, to {@code ready} or {@code absent}. Entering {@code baking}
     * goes through {@link #baking()} only, under the lock {@link #cancel} takes (§11.3).
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
        return settledNanos != 0;
    }

    /** Marks the bake thread of this connection finished. */
    public void settle() {
        settledNanos = System.nanoTime();
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
     * Milliseconds since a {@link System#nanoTime()} reading.
     *
     * @param since the reading
     * @return the milliseconds
     */
    public static long millisSince(long since) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - since);
    }
}
