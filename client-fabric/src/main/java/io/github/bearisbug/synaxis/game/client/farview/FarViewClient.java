package io.github.bearisbug.synaxis.game.client.farview;

import io.github.bearisbug.synaxis.game.protocol.FarViewAnnouncement;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client far view of {@code docs/DESIGN-M3.md} {@code REQ-M3-025} / {@code API-M3-015}: takes the
 * announcement a map shard sends in the configuration phase, decides whether this connection has a
 * far view, keeps the connection's {@link FarViewConnection} and draws its HUD line.
 * <p>
 * Distant Horizons is reached only through {@link FarViewDh}, loaded by name once Fabric reports DH
 * and never otherwise, so nothing here loads a DH class. Availability is decided in two steps: once
 * at start ({@link #gate}: DH there, the pinned version, its surface), then at every announcement
 * ({@link #available}: a radius and an announcement that could be read).
 */
public final class FarViewClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("synaxis");

    /** The prefix of every far view log line, which the tests and the real machine runs read. */
    public static final String PREFIX = "[synaxis/farview] ";
    /** Raised with any change to the bake: the point rule, the coarse expansion, what is written. It is in the view key. */
    public static final int BAKE_CODE_VERSION = 1;
    /** The Distant Horizons version the far view is written against. */
    public static final String DH_VERSION = "3.3.2";
    /** Its API major version. */
    public static final int DH_API_MAJOR = 7;
    /** Fabric's id of Distant Horizons. */
    public static final String DH_MOD_ID = "distanthorizons";

    /** The namespace of the dimension types of Synaxis' own levels. */
    static final String SYNAXIS_NAMESPACE = "synaxis";
    /** The library of Synaxis connections without a far view: never written, so always empty. */
    public static final String NO_FAR_VIEW = "off";

    private static final String DH_IMPLEMENTATION = "io.github.bearisbug.synaxis.game.client.farview.dh.DhFarView";

    /** What the far view can count on from Distant Horizons for the whole session. */
    public enum Gate {
        /** DH is not installed: vanilla view distance, nothing touched. */
        ABSENT,
        /** DH is installed in another version or surface: DH runs on the player's settings, nothing touched. */
        UNSUPPORTED,
        /** DH 3.3.2 with the whole surface; its events are bound. */
        SUPPORTED
    }

    private static volatile Gate gate = Gate.ABSENT;
    private static volatile BooleanSupplier handshaken = () -> false;
    private static volatile @Nullable FarViewCache cache;
    private static volatile @Nullable FarViewConnection current;
    private static volatile @Nullable FarViewConnection last;

    private FarViewClient() {
    }

    /**
     * Wires the far view into the client: the announcement receiver, the connection boundaries, the
     * HUD line, and Distant Horizons if it is there and supported. The payload type itself is
     * registered by {@code SynaxisClientMod} with the others.
     *
     * @param handshakeVerified whether this connection completed {@code synaxis:handshake}, which is
     *                          what makes it a Synaxis connection
     */
    public static void init(BooleanSupplier handshakeVerified) {
        handshaken = handshakeVerified;
        final FarViewCache store = new FarViewCache(FabricLoader.getInstance().getGameDir().resolve("synaxis").resolve("farview"));
        cache = store;
        // What a connection left over a day ago goes now; the rest waits for the next announcement
        store.dropStaleParts(null, System.currentTimeMillis());
        // The settings file is there from the first start, before any far view downloads; every announcement reads it again
        FarViewSettings.load(FabricLoader.getInstance().getConfigDir());
        ClientConfigurationNetworking.registerGlobalReceiver(FarViewPayload.TYPE, (payload, context) ->
                onAnnouncement(payload, server(context.packetContext().orElse(PacketContext.CONNECTION, null))));
        // A connection attempt starts with login: whatever the last connection had under way stops there,
        // which also covers a connection lost before its play phase
        ClientLoginConnectionEvents.INIT.register((_, _) -> {
            cancel("a new connection");
            current = null;
        });
        ClientPlayConnectionEvents.DISCONNECT.register((_, _) -> cancel("disconnected"));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("synaxis", "farview"), (graphics, _) -> {
            final FarViewConnection connection = current;
            final Component line = connection == null ? null : connection.hudLine();
            // Top-left: at the bottom-left the line runs into the offhand slot, which starts at x 93 of a 427 wide GUI
            if (line != null) graphics.text(Minecraft.getInstance().font, line, 2, 2, 0xFFFFFFFF);
        });
        gate = gate(FabricLoader.getInstance().isModLoaded(DH_MOD_ID), FarViewClient::loadDh);
    }

    /** The address of the server of a connection, null when it is not an IP address. */
    private static @Nullable InetAddress server(@Nullable Connection connection) {
        return connection != null && connection.getRemoteAddress() instanceof InetSocketAddress address ? address.getAddress() : null;
    }

    private static FarViewDh loadDh() {
        try {
            return (FarViewDh) Class.forName(DH_IMPLEMENTATION).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("could not load " + DH_IMPLEMENTATION, e);
        }
    }

    /**
     * The start-up half of availability ({@code API-M3-015} 可用性): DH loaded, version
     * {@value #DH_VERSION}, API major {@value #DH_API_MAJOR}, every member of the surface table. In
     * that order, each only when the one before held, so a DH of another version is asked its version
     * and nothing else; only a supported DH is installed.
     *
     * @param loaded whether Fabric loaded DH
     * @param dh     the DH side, asked for only when DH is loaded
     * @return what the session can count on
     */
    public static Gate gate(boolean loaded, Supplier<FarViewDh> dh) {
        if (!loaded) return Gate.ABSENT;
        try {
            final FarViewDh side = dh.get();
            final String version = side.modVersion();
            if (!DH_VERSION.equals(version)) {
                LOGGER.warn("{}Far view off: Distant Horizons {} is not the supported {}", PREFIX, version, DH_VERSION);
                return Gate.UNSUPPORTED;
            }
            final int major = side.apiMajorVersion();
            if (major != DH_API_MAJOR) {
                LOGGER.warn("{}Far view off: Distant Horizons {} (API {}) is not the supported {} (API {})", PREFIX, version, major,
                        DH_VERSION, DH_API_MAJOR);
                return Gate.UNSUPPORTED;
            }
            final List<String> problems = side.surfaceProblems();
            if (!problems.isEmpty()) {
                LOGGER.warn("{}Far view off: Distant Horizons {} is not the supported {}: {}", PREFIX, version, DH_VERSION, problems);
                return Gate.UNSUPPORTED;
            }
            side.install();
            return Gate.SUPPORTED;
        } catch (RuntimeException | LinkageError e) {
            // The far view is optional: a DH that cannot be reached never stops the client from starting
            // With its cause: the DH class that is missing, or the initializer that failed
            LOGGER.warn("{}Far view off: Distant Horizons could not be reached: {}", PREFIX, e.toString(), e);
            return Gate.UNSUPPORTED;
        }
    }

    /**
     * The per connection half of availability: a supported DH, a radius other than 0, and an
     * announcement that could be read.
     *
     * @param gate         what DH offers
     * @param settings     the player's setting
     * @param announcement the announcement, null for none or one that could not be read
     * @return whether this connection draws a far view
     */
    public static boolean available(Gate gate, FarViewSettings settings, @Nullable FarViewAnnouncement announcement) {
        return gate == Gate.SUPPORTED && settings.on() && announcement != null;
    }

    private static void onAnnouncement(FarViewPayload payload, @Nullable InetAddress server) {
        final FarViewCache store = cache;
        if (store == null || !takesAnnouncement(current)) return;
        final FarViewSettings settings = FarViewSettings.load(FabricLoader.getInstance().getConfigDir());
        final FarViewConnection connection = announced(payload.announcement(), System.nanoTime(), gate, settings, store,
                FarViewDownloader.standard(store), server);
        current = connection;
        last = connection;
        connection.begin();
    }

    /**
     * Whether the connection of this moment takes an announcement: only its first ({@code API-M3-015} 宣告条,
     * at most one a connection, kept until the next login starts). A repeated one is ignored with a WARN line
     * and the first one's far view goes on, so a server cannot start a download, its threads and its requests
     * over and over, or have two downloads write one {@code .part}.
     *
     * @param current the far view of the connection of this moment, null before its announcement
     * @return whether the announcement is taken
     */
    static boolean takesAnnouncement(@Nullable FarViewConnection current) {
        if (current == null) return true;
        LOGGER.warn("{}Far view announcement repeated on one connection: ignored", PREFIX);
        return false;
    }

    /**
     * The far view of a connection that got an announcement, not begun yet: the announcement as this client
     * takes it ({@link #admitted}), the {@code .part} files it does not name or a day old dropped, whether it
     * draws a far view.
     *
     * @param received   the announcement, null when it could not be read
     * @param now        {@link System#nanoTime()} when it was handled
     * @param gate       what DH offers
     * @param settings   the player's setting
     * @param store      the cache
     * @param downloader the downloads
     * @param server     the address of the server, null when unknown
     * @return the connection's far view
     */
    static FarViewConnection announced(@Nullable FarViewAnnouncement received, long now, Gate gate, FarViewSettings settings,
                                       FarViewCache store, FarViewDownloader downloader, @Nullable InetAddress server) {
        final FarViewAnnouncement announcement = admitted(received);
        final Set<String> named = new HashSet<>();
        if (announcement != null) announcement.tiles().forEach(tile -> named.add(FarViewCache.hex(tile)));
        store.dropStaleParts(named, System.currentTimeMillis());
        final boolean on = available(gate, settings, announcement);
        final FarViewConnection connection = new FarViewConnection(announcement, now, settings, on, store,
                downloader, server);
        if (received == null) {
            LOGGER.info("{}announcement received, but it could not be read: far view off", PREFIX);
        } else if (announcement != null) {
            final long coarse = announcement.tiles().stream().filter(t -> t.layer() == FarViewAnnouncement.LAYER_COARSE).count();
            LOGGER.info("{}announcement received: build {}, {} tile(s), {} coarse, view {}{}", PREFIX, announcement.buildId(),
                    announcement.tiles().size(), coarse, on ? connection.viewKey() : "-",
                    on ? "" : "; far view off (" + reason(gate) + ")");
        }
        return connection;
    }

    /**
     * An announcement as this client takes it ({@code API-M3-015}, M3-S {@code REQ-M3-032}): none when its
     * objects together are over the cache limit, when its URLs are not all absolute http(s) URLs of one
     * scheme, host and port, or when two of its tiles name one object. Each refusal is one WARN line.
     *
     * @param announcement the announcement, null for none
     * @return it, or null
     */
    static @Nullable FarViewAnnouncement admitted(@Nullable FarViewAnnouncement announcement) {
        if (announcement == null) return null;
        final long bytes = announcement.tiles().stream().mapToLong(FarViewAnnouncement.Tile::bytes).sum();
        if (bytes > FarViewCache.LIMIT_BYTES) {
            LOGGER.warn("{}Far view announcement of {} bytes is over the cache limit of {} bytes: ignored", PREFIX, bytes,
                    FarViewCache.LIMIT_BYTES);
            return null;
        }
        String first = null;
        final Set<String> digests = new HashSet<>();
        for (FarViewAnnouncement.Tile tile : announcement.tiles()) {
            final String origin;
            try {
                origin = origin(tile.url());
            } catch (IllegalArgumentException e) {
                LOGGER.warn("{}Far view announcement names a url that cannot be requested: {}: ignored", PREFIX, e.getMessage());
                return null;
            }
            if (first == null) {
                first = origin;
            } else if (!first.equals(origin)) {
                LOGGER.warn("{}Far view announcement names more than one host: ignored", PREFIX);
                return null;
            }
            // A pack's header names its own tile, so no shard announces one object for two tiles; the two downloads
            // would write one .part, named by the digest alone
            final String hex = FarViewCache.hex(tile);
            if (!digests.add(hex)) {
                LOGGER.warn("{}Far view announcement names object {} for two tiles: ignored", PREFIX, hex.substring(0, 12));
                return null;
            }
        }
        return announcement;
    }

    /**
     * {@code scheme://host:port} of an absolute http(s) URL, the default port written out; no user info.
     *
     * @param url the URL
     * @return its origin
     * @throws IllegalArgumentException if it is no absolute http(s) URL with a host
     */
    static String origin(String url) {
        final URI uri = URI.create(url);
        final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) throw new IllegalArgumentException(url + " is not http or https");
        if (uri.getHost() == null) throw new IllegalArgumentException(url + " names no host");
        final int port = uri.getPort() >= 0 ? uri.getPort() : scheme.equals("https") ? 443 : 80;
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }

    /** Why a connection whose announcement was taken draws no far view. */
    private static String reason(Gate gate) {
        return switch (gate) {
            case ABSENT -> "no Distant Horizons";
            case UNSUPPORTED -> "unsupported Distant Horizons";
            case SUPPORTED -> FarViewSettings.KEY + " is 0";
        };
    }

    private static void cancel(String why) {
        final FarViewConnection connection = current;
        if (connection != null) connection.cancel(why);
    }

    // ---- What the DH side and the tests read

    /**
     * Whether the connection of this moment is a Synaxis one: it completed {@code synaxis:handshake},
     * it sent {@value FarViewAnnouncement#CHANNEL}, or its level is of a {@value #SYNAXIS_NAMESPACE}
     * dimension type ({@code synaxis:map} of a map shard, {@code synaxis:match} of a match). The
     * handshake alone does not tell: a map shard serves in the core mode, which answers the hello from
     * the tick thread, and that answer can land after {@code FinishConfiguration}, where the client
     * drops it. DH is only touched on such connections.
     *
     * @return whether it is
     */
    public static boolean synaxisConnection() {
        if (handshaken.getAsBoolean() || current != null) return true;
        final ClientLevel level = Minecraft.getInstance().level;
        return level != null && level.dimensionTypeRegistration()
                .is(key -> key.identifier().getNamespace().equals(SYNAXIS_NAMESPACE));
    }

    /** @return what DH offers this session */
    public static Gate gate() {
        return gate;
    }

    /** @return the far view of the connection of this moment, null before its announcement or after the next login starts */
    public static @Nullable FarViewConnection current() {
        return current;
    }

    /** @return the far view of the last connection that got an announcement, kept after it ended (for tests) */
    public static @Nullable FarViewConnection last() {
        return last;
    }

    /**
     * The library directory the connection of this moment pins DH to: the far view's, or on a Synaxis
     * connection without a far view the empty {@value #NO_FAR_VIEW} library, which read only keeps
     * empty, so DH has nothing to draw there and the player's own DH settings stay as they are. A
     * Synaxis connection whose far view was cancelled with its level gets {@value #NO_FAR_VIEW} too
     * ({@code docs/DESIGN-M3.md} §28.2). With {@link FarViewTestHook#pinOff} off ({@code counter-off})
     * a Synaxis connection without a far view leaves DH its own folder.
     *
     * @return {@code synaxis/farview/dh/<view_key>} or {@code synaxis/farview/dh/off}, null on any other
     *         connection, where DH keeps its own folder
     */
    public static @Nullable Path libraryDirectory() {
        final FarViewCache store = cache;
        if (store == null) return null;
        return library(store, current, synaxisConnection() && FarViewTestHook.pinOff);
    }

    /**
     * The library a connection pins DH to.
     *
     * @param store      the cache
     * @param connection the far view of the connection, null before an announcement
     * @param synaxis    whether the connection is a Synaxis one
     * @return {@code dh/<view_key>} for a far view not cancelled; {@code dh/off} for a Synaxis connection
     *         without one, or whose far view was cancelled with its level (§28.2); else null
     */
    static @Nullable Path library(FarViewCache store, @Nullable FarViewConnection connection, boolean synaxis) {
        if (connection != null && connection.available() && !connection.cancelled()) {
            return store.library(connection.viewKey());
        }
        return synaxis ? store.library(NO_FAR_VIEW) : null;
    }

    /**
     * DH loaded a level: a matching marker's load check starts now; otherwise the connection's bake
     * starts when its objects are there.
     *
     * @param levelWrapper DH's level wrapper
     * @param baker        the DH side's bake
     */
    public static void levelLoaded(Object levelWrapper, FarViewConnection.Baker baker) {
        final FarViewConnection connection = current;
        if (connection != null && connection.available()) connection.levelLoaded(levelWrapper, baker);
    }

    /**
     * A level or the world was unloaded: stops the connection's download or bake.
     *
     * @param why for the log
     */
    public static void unloaded(String why) {
        cancel(why);
    }
}
