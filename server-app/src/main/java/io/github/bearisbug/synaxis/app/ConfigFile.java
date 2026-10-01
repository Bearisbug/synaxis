package io.github.bearisbug.synaxis.app;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;

/**
 * The configuration file {@value #DEFAULT_FILE} ({@code docs/DESIGN-PROD.md} {@code API-PROD-023}).
 * Its keys are the {@code synaxis.*} system properties, letter for letter: every key of the file
 * the command line did not give becomes a system property before anything reads one, so the
 * command line wins over the file and the file over every default. It is read once and never again.
 * <p>
 * {@link Main} applies the file as it is initialised, before the logging is set up and before the
 * engine is loaded. So this class has no logger and names nothing of the engine: a logger here
 * would set the logging up before the file could say where the log goes, and an engine class could
 * load {@code JvmFlags} before the file could set its keys.
 */
public final class ConfigFile {

    /** Names the file on the command line; the file itself cannot. */
    public static final String PATH_PROPERTY = "synaxis.config-file";

    /** The file read when {@value #PATH_PROPERTY} is not given, in the working directory. */
    public static final String DEFAULT_FILE = "synaxis.properties";

    /**
     * Every key the file takes: the one table of them, which {@code docs/DESIGN-PROD.md}
     * {@code API-PROD-023} writes out. The config key gate ({@code ConfigKeyGateTest}) fails when a
     * class of this repository reads a {@code synaxis.*} key missing here, and when no class reads
     * one listed here.
     */
    public static final SortedSet<String> KEYS = Collections.unmodifiableSortedSet(new TreeSet<>(List.of(
            // The engine, per process: ServerConfig#fromSystemProperties
            "synaxis.shutdown-on-signal",
            "synaxis.max-tick-catch-up",
            "synaxis.chunk-view-distance",
            "synaxis.entity-view-distance",
            "synaxis.entity-synchronization-ticks",
            "synaxis.dispatcher-threads",
            "synaxis.thread-provider",
            "synaxis.thread-grid-exponent",
            "synaxis.send-light-after-block-placement-delay",
            "synaxis.login-timeout",
            "synaxis.login-plugin-message-timeout",
            "synaxis.known-packs-response-timeout",
            "synaxis.accept-transfers",
            "synaxis.packet-per-tick",
            "synaxis.packet-queue-size",
            "synaxis.keep-alive-delay",
            "synaxis.keep-alive-kick",
            "synaxis.player.chunk-update-limiter-history-size",
            "synaxis.max-connections",
            "synaxis.accept-backoff-ms",
            "synaxis.max-connections-per-ip",
            "synaxis.handshake-timeout-ms",
            "synaxis.login-rate-per-ip",
            "synaxis.login-burst-per-ip",
            "synaxis.status-rate-per-ip",
            "synaxis.status-burst-per-ip",
            "synaxis.transfer-rate-per-ip",
            "synaxis.transfer-burst-per-ip",
            "synaxis.connection-gate.trusted-networks",
            "synaxis.max-players",
            "synaxis.max-joins-per-tick",
            "synaxis.motd",
            "synaxis.server-icon",
            "synaxis.hide-online-players",
            "synaxis.chat-rate-per-second",
            "synaxis.chat-burst",
            "synaxis.packet-limit.window-ms",
            "synaxis.packet-limit.max-packets",
            "synaxis.packet-limit.action",
            "synaxis.packet-limit.overrides",
            "synaxis.packet-limit.max-inflated-bytes-per-second",
            "synaxis.tab-complete-rate-per-second",
            "synaxis.outbound.max-backlog-bytes",
            "synaxis.tab-latency-interval-ms",
            "synaxis.compression-threshold",
            "synaxis.suppress-connection-accept-errors",
            "synaxis.suppress-connection-io-errors",
            "synaxis.suppress-malformed-packet-error-level",
            "synaxis.suppress-misused-packet-error-level",
            "synaxis.reject-malformed-packet",
            "synaxis.reject-misused-packet",
            "synaxis.send-buffer-size",
            "synaxis.receive-buffer-size",
            "synaxis.tcp-no-delay",
            "synaxis.socket-timeout",
            "synaxis.pooled-buffer-size",
            "synaxis.chunk-queue.min-per-tick",
            "synaxis.chunk-queue.max-per-tick",
            "synaxis.chunk-queue.multiplier",
            "synaxis.viewable-packet",
            "synaxis.enforce-entity-interaction-range",
            "synaxis.max-visible-entities-per-player",
            "synaxis.watchdog.early-warning-ms",
            "synaxis.watchdog.early-warning-every-ms",
            "synaxis.watchdog.timeout-ms",
            "synaxis.watchdog.halt-after-ms",
            "synaxis.registry.unsafe-ops",
            "synaxis.inside-test",
            "synaxis.new-socket-write-lock",
            "synaxis.proxy-protocol",
            "synaxis.proxy-protocol.required",
            "synaxis.proxy-protocol.trusted-networks",
            // The engine, per JVM, read as the class is loaded: JvmFlags
            "synaxis.tps",
            "synaxis.automatic-component-translation",
            "synaxis.grouped-packet",
            "synaxis.cached-packet",
            "synaxis.max-packet-size",
            "synaxis.max-packet-size-pre-auth",
            "synaxis.warn-unread-bytes-packet",
            "synaxis.event.multiple-parents",
            "synaxis.template-compiler",
            "synaxis.nbt.max-depth",
            "synaxis.nbt.max-bytes",
            "synaxis.compression-level",
            // The engine, per JVM, owned by the package reading them: tag.TagFlags,
            // thread.AcquirableImpl, utils.collection.ConcurrentMessageQueues
            "synaxis.serialization.serialize-empty-nbt-compound",
            "synaxis.tag-handler-cache",
            "synaxis.acquirable-strict",
            "synaxis.unsafe-collections",
            // The host
            "synaxis.app.port",
            "synaxis.app.plugins",
            "synaxis.app.shard-id",
            "synaxis.app.ledger-max-bytes-per-day",
            "synaxis.app.auth",
            "synaxis.app.auth.session-server",
            "synaxis.app.store",
            "synaxis.app.whitelist",
            "synaxis.app.core-mode",
            "synaxis.app.handshake-phase",
            "synaxis.app.metrics.bind",
            "synaxis.app.management.enabled",
            "synaxis.app.management.bind",
            "synaxis.app.transfer-cooldown-ms",
            // The map shard host, app.shard.ShardSettings
            "synaxis.map.root",
            "synaxis.map.build",
            "synaxis.simulation-distance",
            "synaxis.chunk-unload-grace-ticks",
            "synaxis.farview.url-root",
            // The map shard, :synx SynxOptions and OverlayOptions
            "synaxis.world-writable",
            "synaxis.synx.section-cache-bytes",
            "synaxis.overlay.cap",
            "synaxis.overlay.flush-interval-s",
            "synaxis.relight-threads",
            "synaxis.relight.resend-light",
            // The logging setup, logback.xml
            "synaxis.app.log",
            // The RTS plugin, game-rts
            "synaxis.app.map",
            "synaxis.app.units")));

    private ConfigFile() {
    }

    /**
     * What reading the file came to.
     *
     * @param file       the file, read or looked for
     * @param read       whether there was one to read
     * @param keys       the keys it gives
     * @param overridden those of its keys the command line gave too, which keep the command line's value
     * @param refusal    why the process must not start, null when it may
     */
    record Outcome(Path file, boolean read, List<String> keys, List<String> overridden, @Nullable String refusal) {

        /** Returns the boot self-check line after {@code Effective config file: }. */
        String describe() {
            if (!read) return "none, " + file + " does not exist";
            return "path=" + file + " keys=" + keys.size() + " overridden_by_command_line=" + overridden;
        }
    }

    /**
     * Reads the file of this process, the one {@value #PATH_PROPERTY} names or else
     * {@value #DEFAULT_FILE} in the working directory, and sets each of its keys the command line
     * did not give as a system property.
     *
     * @return what it came to
     */
    static Outcome applyToSystemProperties() {
        return apply(System.getProperty(PATH_PROPERTY), Path.of(DEFAULT_FILE), System::getProperty, System::setProperty);
    }

    /**
     * Reads a file and sets each of its keys the properties do not have yet. A refused file sets
     * nothing: a missing file that was named, one that cannot be read or parsed, a key given twice,
     * {@value #PATH_PROPERTY} in the file, or a key outside {@link #KEYS}.
     *
     * @param named      the path the command line named, null when it named none
     * @param fallback   the file read when none was named, which need not exist
     * @param properties the properties already set
     * @param set        sets one property
     * @return what it came to
     */
    static Outcome apply(@Nullable String named, Path fallback, UnaryOperator<String> properties,
                         BiConsumer<String, String> set) {
        Path file = fallback.toAbsolutePath();
        final SortedMap<String, String> entries;
        try {
            if (named != null) file = Path.of(named).toAbsolutePath();
            else if (!Files.exists(file)) return new Outcome(file, false, List.of(), List.of(), null);
            entries = read(file);
        } catch (IOException e) {
            return refused(file, "cannot be read: " + e);
        } catch (IllegalArgumentException e) {
            return refused(file, e.getMessage());
        }
        final List<String> problems = new ArrayList<>();
        for (final String key : entries.keySet()) {
            if (PATH_PROPERTY.equals(key))
                problems.add(PATH_PROPERTY + " names the file and is only read from the command line");
            else if (!KEYS.contains(key))
                problems.add("unknown key " + key + " (nearest known key " + nearest(key) + ")");
        }
        if (!problems.isEmpty()) return refused(file, String.join("; ", problems));
        final List<String> overridden = new ArrayList<>();
        entries.forEach((key, value) -> {
            if (properties.apply(key) != null) overridden.add(key);
            else set.accept(key, value);
        });
        return new Outcome(file, true, List.copyOf(entries.keySet()), List.copyOf(overridden), null);
    }

    private static Outcome refused(Path file, String why) {
        return new Outcome(file, false, List.of(), List.of(), file + ": " + why);
    }

    /**
     * Reads a file in the format of {@link Properties}, as UTF-8.
     *
     * @param file the file
     * @return its keys and values, sorted by key
     * @throws IOException              if it cannot be read
     * @throws IllegalArgumentException if it gives a key twice or holds a malformed escape
     */
    static SortedMap<String, String> read(Path file) throws IOException {
        final SortedMap<String, String> entries = new TreeMap<>();
        // Properties would keep the last of two lines with the same key without a word
        @SuppressWarnings("serial") final Properties properties = new Properties() {
            @Override
            public synchronized @Nullable Object put(Object key, Object value) {
                if (entries.putIfAbsent((String) key, (String) value) != null)
                    throw new IllegalArgumentException(key + " is given twice");
                return null;
            }
        };
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return entries;
    }

    /**
     * Returns the key of {@link #KEYS} fewest single character edits away, the first in order of a tie.
     *
     * @param key a key that is not in {@link #KEYS}
     * @return the nearest known key
     */
    static String nearest(String key) {
        String nearest = KEYS.first();
        int fewest = Integer.MAX_VALUE;
        for (final String known : KEYS) {
            final int edits = edits(key, known);
            if (edits < fewest) {
                nearest = known;
                fewest = edits;
            }
        }
        return nearest;
    }

    /** The Levenshtein distance of the two. */
    private static int edits(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                final int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j], current[j - 1]) + 1);
            }
            final int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
