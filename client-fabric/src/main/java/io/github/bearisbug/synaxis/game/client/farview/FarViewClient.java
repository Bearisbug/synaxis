package io.github.bearisbug.synaxis.game.client.farview;

import io.github.bearisbug.synaxis.game.protocol.FarViewAnnouncement;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
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
        cache = new FarViewCache(FabricLoader.getInstance().getGameDir().resolve("synaxis").resolve("farview"));
        ClientConfigurationNetworking.registerGlobalReceiver(FarViewPayload.TYPE, (payload, _) -> onAnnouncement(payload));
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
            if (line != null) graphics.text(Minecraft.getInstance().font, line, 2, graphics.guiHeight() - 10, 0xFFFFFFFF);
        });
        gate = gate(FabricLoader.getInstance().isModLoaded(DH_MOD_ID), FarViewClient::loadDh);
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
            LOGGER.warn("{}Far view off: Distant Horizons could not be reached: {}", PREFIX, e.toString());
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

    private static void onAnnouncement(FarViewPayload payload) {
        final long now = System.nanoTime();
        final FarViewCache store = cache;
        if (store == null) return;
        final FarViewSettings settings = FarViewSettings.load(FabricLoader.getInstance().getConfigDir());
        final FarViewAnnouncement announcement = payload.announcement();
        final boolean on = available(gate, settings, announcement);
        final FarViewConnection connection = new FarViewConnection(announcement, now, settings, on, DH_VERSION, store,
                FarViewDownloader.standard(store));
        final FarViewConnection before = current;
        if (before != null) before.cancel("a new announcement");
        current = connection;
        last = connection;
        if (announcement == null) {
            LOGGER.info("{}announcement received, but it could not be read: far view off", PREFIX);
        } else {
            final long coarse = announcement.tiles().stream().filter(t -> t.layer() == FarViewAnnouncement.LAYER_COARSE).count();
            LOGGER.info("{}announcement received: build {}, {} tile(s), {} coarse, view {}{}", PREFIX, announcement.buildId(),
                    announcement.tiles().size(), coarse, on ? connection.viewKey() : "-",
                    on ? "" : "; far view off (" + reason(settings) + ")");
        }
        connection.begin();
    }

    private static String reason(FarViewSettings settings) {
        if (gate != Gate.SUPPORTED) return gate == Gate.ABSENT ? "no Distant Horizons" : "unsupported Distant Horizons";
        return settings.on() ? "no announcement" : FarViewSettings.KEY + " is 0";
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
     * empty, so DH has nothing to draw there and the player's own DH settings stay as they are.
     *
     * @return {@code synaxis/farview/dh/<view_key>} or {@code synaxis/farview/dh/off}, null on any other
     *         connection, where DH keeps its own folder
     */
    public static @Nullable Path libraryDirectory() {
        final FarViewCache store = cache;
        if (store == null) return null;
        final FarViewConnection connection = current;
        if (connection != null && connection.available() && !connection.cancelled().get()) {
            return store.library(connection.viewKey());
        }
        return synaxisConnection() ? store.library(NO_FAR_VIEW) : null;
    }

    /**
     * DH loaded a level: the connection's bake starts when its objects are there.
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
