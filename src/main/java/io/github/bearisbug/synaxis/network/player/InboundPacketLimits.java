package io.github.bearisbug.synaxis.network.player;

import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.network.packet.PacketRegistry;
import io.github.bearisbug.synaxis.network.packet.PacketVanilla;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientTabCompletePacket;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The inbound packet limits of a process ({@code docs/DESIGN-PROD.md} {@code API-PROD-012}), read
 * once from its configuration; {@link #newLimiter()} gives each connection its own counters.
 * <p>
 * Three limits look at the packets a connection hands to its player's queue, and in the play state
 * at the ones it handles right away (ping, keep alive, cookie response): every packet counts
 * towards {@code synaxis.packet-limit.max-packets} in a sliding window of
 * {@code synaxis.packet-limit.window-ms}, the types named in {@code synaxis.packet-limit.overrides}
 * also count towards a limit of their own in the same window, and tab completions over
 * {@code synaxis.tab-complete-rate-per-second} in one second are dropped. A fourth one caps the
 * payload bytes a compressed connection makes the server inflate in one second. The login state
 * has no player and is not covered, the connection gate bounds it.
 */
@ApiStatus.Internal
public final class InboundPacketLimits {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    /**
     * A limit and what to do beyond it.
     *
     * @param limit  the most packets a window may hold
     * @param action what happens to the packet that goes over it
     */
    public record Rule(int limit, PacketLimitAction action) {
    }

    /** What happens to one packet. */
    public enum Verdict {
        ACCEPT, DROP, KICK
    }

    private final long windowNanos;
    private final Rule all;
    private final Map<Class<? extends ClientPacket>, Rule> overrides;
    private final int tabCompletesPerSecond;
    private final long inflatedBytesPerSecond;

    private InboundPacketLimits(ServerConfig config) {
        this.windowNanos = TimeUnit.MILLISECONDS.toNanos(config.packetLimitWindow());
        this.all = new Rule(config.packetLimitMaxPackets(), config.packetLimitAction());
        this.overrides = parseOverrides(config.packetLimitOverrides());
        this.tabCompletesPerSecond = config.tabCompleteRatePerSecond();
        this.inflatedBytesPerSecond = config.maxInflatedBytesPerSecond();
    }

    public static InboundPacketLimits of(ServerConfig config) {
        return new InboundPacketLimits(config);
    }

    /**
     * Reads {@code synaxis.packet-limit.overrides}: comma separated {@code <type>=<limit>:<DROP|KICK>},
     * the type being the simple name of an engine client packet of the configuration or play state,
     * such as {@code ClientTabCompletePacket=40:DROP}. Empty entries, such as the one a trailing comma
     * leaves, are skipped. The packets handled right away in the configuration state and only there,
     * {@code ClientSelectKnownPacksPacket} and {@code ClientFinishConfigurationPacket}, are accepted but
     * never counted.
     *
     * @param text the property, empty for none
     * @return the rule of each named type
     * @throws IllegalArgumentException if an entry is malformed or names no client packet
     */
    public static Map<Class<? extends ClientPacket>, Rule> parseOverrides(String text) {
        if (text.isBlank()) return Map.of();
        final Map<Class<? extends ClientPacket>, Rule> rules = new HashMap<>();
        for (String entry : text.split(",", -1)) {
            final String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            final int equals = trimmed.indexOf('=');
            final int colon = trimmed.lastIndexOf(':');
            if (equals <= 0 || colon < equals)
                throw new IllegalArgumentException("synaxis.packet-limit.overrides entry is not <type>=<limit>:<DROP|KICK>: "
                        + trimmed);
            final Class<? extends ClientPacket> type = clientPacket(trimmed.substring(0, equals).trim());
            final int limit;
            final PacketLimitAction action;
            try {
                limit = Integer.parseInt(trimmed.substring(equals + 1, colon).trim());
                action = PacketLimitAction.valueOf(trimmed.substring(colon + 1).trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("synaxis.packet-limit.overrides entry is not <type>=<limit>:<DROP|KICK>: "
                        + trimmed, e);
            }
            if (limit < 1)
                throw new IllegalArgumentException("synaxis.packet-limit.overrides limit must be at least 1: " + trimmed);
            if (rules.put(type, new Rule(limit, action)) != null)
                throw new IllegalArgumentException("synaxis.packet-limit.overrides names a type twice: " + type.getSimpleName());
        }
        return Map.copyOf(rules);
    }

    private static Class<? extends ClientPacket> clientPacket(String name) {
        for (PacketRegistry<? extends ClientPacket> registry : List.of(
                PacketVanilla.CLIENT_PACKET_PARSER.configuration(), PacketVanilla.CLIENT_PACKET_PARSER.play())) {
            for (PacketRegistry.PacketInfo<? extends ClientPacket> info : registry) {
                if (info.packetClass().getSimpleName().equals(name)) return info.packetClass();
            }
        }
        throw new IllegalArgumentException("synaxis.packet-limit.overrides names no client packet of the configuration "
                + "or play state: " + name);
    }

    /** Counters for one connection. */
    public Limiter newLimiter() {
        return new Limiter();
    }

    /** The counters of one connection, only ever touched by its read thread. */
    public final class Limiter {
        private final WindowCounter packets = new WindowCounter(windowNanos);
        private final Map<Class<?>, WindowCounter> perType = new HashMap<>();
        private final WindowCounter tabCompletes = new WindowCounter(SECOND);
        private final WindowCounter inflated = new WindowCounter(SECOND);
        private @Nullable String exceeded;

        private Limiter() {
        }

        /**
         * Counts a packet on its way to the player's queue, or in the play state to its listener.
         *
         * @param packet the packet
         * @param now    {@link System#nanoTime()}
         * @return whether to queue it, drop it or kick the connection
         */
        public Verdict admit(ClientPacket packet, long now) {
            Verdict verdict = Verdict.ACCEPT;
            final Rule override = overrides.get(packet.getClass());
            if (override != null) {
                final long count = perType.computeIfAbsent(packet.getClass(), _ -> new WindowCounter(windowNanos))
                        .add(now, 1);
                if (count > override.limit()) verdict = over(verdict, override.action(),
                        packet.getClass().getSimpleName() + " " + count + " > " + override.limit() + " in " + windowMillis() + " ms");
            }
            if (packet instanceof ClientTabCompletePacket) {
                final long count = tabCompletes.add(now, 1);
                if (count > tabCompletesPerSecond) verdict = over(verdict, PacketLimitAction.DROP,
                        "tab completions " + count + " > " + tabCompletesPerSecond + " in 1000 ms");
            }
            final long count = packets.add(now, 1);
            if (count > all.limit()) verdict = over(verdict, all.action(),
                    "packets " + count + " > " + all.limit() + " in " + windowMillis() + " ms");
            return verdict;
        }

        /**
         * Counts the bytes a compressed frame inflated to.
         *
         * @param bytes the payload after inflating
         * @param now   {@link System#nanoTime()}
         * @return false once the last second went over {@code synaxis.packet-limit.max-inflated-bytes-per-second}
         */
        public boolean admitInflated(long bytes, long now) {
            final long sum = inflated.add(now, bytes);
            if (sum <= inflatedBytesPerSecond) return true;
            this.exceeded = "inflated bytes " + sum + " > " + inflatedBytesPerSecond + " in 1000 ms";
            return false;
        }

        /** The limit the last packet that was not accepted went over, for the log line. */
        public @Nullable String exceeded() {
            return exceeded;
        }

        private Verdict over(Verdict current, PacketLimitAction action, String limit) {
            if (current == Verdict.KICK) return current;
            this.exceeded = limit;
            return action == PacketLimitAction.KICK ? Verdict.KICK : Verdict.DROP;
        }

        private long windowMillis() {
            return TimeUnit.NANOSECONDS.toMillis(windowNanos);
        }
    }
}
