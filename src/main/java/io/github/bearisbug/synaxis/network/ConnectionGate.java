package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The connection gate of {@code docs/DESIGN-PROD.md} {@code API-PROD-003}: every count a peer can
 * drive up before it is authenticated, checked before the server spends anything on it.
 * <ul>
 * <li>L0, the sockets of the whole process, checked when a socket is accepted.</li>
 * <li>L1, the concurrent connections of one address (IPv4 /32, IPv6 /64), checked when a socket is
 * accepted, or at the handshake once the PROXY protocol header has told the real address.</li>
 * <li>L2, one token bucket per address for each handshake intent (login, status, transfer).</li>
 * <li>L3, the seats of the process: connections past their login start, until they close.</li>
 * </ul>
 * L4, the joins per tick, is the play queue of {@link ConnectionManager}. Addresses of
 * {@code synaxis.connection-gate.trusted-networks} skip L1 and L2.
 * <p>
 * Log volume: a rejection is never a line of its own, the peer decides how many there are. They
 * are counted, and {@link #tick(long)} writes the counts as at most one line per 10 seconds.
 */
@ApiStatus.Internal
public final class ConnectionGate {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectionGate.class);

    static final long REPORT_INTERVAL = TimeUnit.SECONDS.toNanos(10);
    static final long SWEEP_INTERVAL = TimeUnit.SECONDS.toNanos(60);
    /** The token buckets are keyed on a value the peer picks, so their table has to be bounded. */
    static final int MAX_BUCKET_KEYS = 65_536;

    /** Why a connection was turned away, the label of {@code synaxis_gate_rejections_total}. */
    public enum Reason {
        CLOSED_AT_ACCEPT,
        TOO_MANY,
        THROTTLED,
        FULL,
        HANDSHAKE_TIMEOUT
    }

    /** What the gate makes of a handshake. */
    public enum Verdict {
        PASS,
        /** L1: the address already holds {@code synaxis.max-connections-per-ip} connections. */
        TOO_MANY,
        /** L2: the bucket of this intent is empty for the address. */
        THROTTLED
    }

    private final ServerConfig config;
    private final LongSupplier clock;
    private final List<Network> trustedNetworks;
    private final List<Network> trustedProxies;

    private final AtomicInteger connections = new AtomicInteger();
    private final Map<Prefix, AtomicInteger> connectionsPerPrefix = new ConcurrentHashMap<>();
    private final AtomicInteger seats = new AtomicInteger();

    // Guarded by itself; access ordered, the eldest entry is the least recently used address
    private final LinkedHashMap<Prefix, Buckets> buckets = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Prefix, Buckets> eldest) {
            return size() > MAX_BUCKET_KEYS;
        }
    };
    private long lastSweep;

    private final Map<Reason, AtomicLong> rejectedTotal = new EnumMap<>(Reason.class);
    private final Map<Reason, AtomicLong> rejectedSinceReport = new EnumMap<>(Reason.class);
    // Only touched by the tick thread
    private long reportWindowStart;
    private boolean reportWindowStarted;

    public ConnectionGate(ServerConfig config) {
        this(config, System::nanoTime);
    }

    ConnectionGate(ServerConfig config, LongSupplier clock) {
        this.config = config;
        this.clock = clock;
        this.trustedNetworks = parseNetworks(config.connectionGateTrustedNetworks());
        this.trustedProxies = parseNetworks("synaxis.proxy-protocol.trusted-networks", config.proxyProtocolTrustedNetworks());
        this.lastSweep = clock.getAsLong();
        for (Reason reason : Reason.values()) {
            rejectedTotal.put(reason, new AtomicLong());
            rejectedSinceReport.put(reason, new AtomicLong());
        }
    }

    /**
     * L0, and L1 unless the PROXY protocol is on: decides whether a socket that was just accepted
     * may become a connection. A socket turned away is to be closed without writing anything.
     *
     * @param remoteAddress the address of the peer of the socket
     * @return the ticket the connection releases when it closes, or null to close the socket
     */
    public @Nullable Ticket admit(SocketAddress remoteAddress) {
        if (connections.incrementAndGet() > config.maxConnections()) {
            connections.decrementAndGet();
            reject(Reason.CLOSED_AT_ACCEPT);
            return null;
        }
        final Ticket ticket = new Ticket(true);
        if (!config.proxyProtocol() && !ticket.countAddress(remoteAddress)) {
            ticket.release();
            reject(Reason.TOO_MANY);
            return null;
        }
        return ticket;
    }

    /**
     * A ticket that is not part of the socket count, for a connection the accept loop did not
     * create.
     */
    public Ticket untracked() {
        return new Ticket(false);
    }

    /**
     * L1 when the PROXY protocol is on, then L2, in that order: a connection turned away by L1 does
     * not take a token.
     *
     * @param ticket        the ticket of the connection
     * @param remoteAddress the address of the peer, the one the PROXY protocol header named if any
     * @param intent        the intent of the handshake
     * @return whether the handshake may go on
     */
    public Verdict handshake(Ticket ticket, SocketAddress remoteAddress, ClientHandshakePacket.Intent intent) {
        final Prefix prefix = Prefix.of(remoteAddress);
        if (prefix == null || isTrusted(remoteAddress)) return Verdict.PASS;
        if (config.proxyProtocol() && !ticket.countAddress(remoteAddress)) {
            reject(Reason.TOO_MANY);
            return Verdict.TOO_MANY;
        }
        final long now = clock.getAsLong();
        final boolean taken;
        synchronized (buckets) {
            final Buckets owned = buckets.computeIfAbsent(prefix, _ -> new Buckets(config, now));
            taken = owned.of(intent).tryTake(now);
        }
        if (!taken) {
            reject(Reason.THROTTLED);
            return Verdict.THROTTLED;
        }
        return Verdict.PASS;
    }

    /**
     * L3: gives the connection a seat, which it keeps until it closes. A login start that finds
     * every seat of {@code synaxis.max-players} taken is refused before anything else is done for
     * it.
     *
     * @return false if the process is full
     */
    public boolean takeSeat(Ticket ticket) {
        if (ticket.takeSeat()) return true;
        reject(Reason.FULL);
        return false;
    }

    /** Counts a connection closed for not sending its handshake in time. */
    public void handshakeTimedOut() {
        reject(Reason.HANDSHAKE_TIMEOUT);
    }

    /**
     * Writes the rejections counted over the last 10 seconds as one line, if there were any, and
     * drops the buckets that are back to full every 60 seconds. Called once per tick.
     *
     * @param nanoTime the time of the tick, {@link System#nanoTime()}
     */
    public void tick(long nanoTime) {
        if (!reportWindowStarted) {
            reportWindowStarted = true;
            reportWindowStart = nanoTime;
        } else if (nanoTime - reportWindowStart >= REPORT_INTERVAL) {
            reportWindowStart = nanoTime;
            report();
        }
        final long now = clock.getAsLong();
        synchronized (buckets) {
            if (now - lastSweep < SWEEP_INTERVAL) return;
            lastSweep = now;
            buckets.values().removeIf(owned -> owned.full(now));
        }
    }

    private void report() {
        final Map<Reason, Long> counts = new EnumMap<>(Reason.class);
        long total = 0;
        for (Reason reason : Reason.values()) {
            final long count = rejectedSinceReport.get(reason).getAndSet(0);
            counts.put(reason, count);
            total += count;
        }
        if (total == 0) return;
        LOGGER.warn("Connection gate rejected {} connections in the last 10 s: too_many={} throttled={} full={} "
                        + "closed_at_accept={} handshake_timeout={}", total, counts.get(Reason.TOO_MANY),
                counts.get(Reason.THROTTLED), counts.get(Reason.FULL), counts.get(Reason.CLOSED_AT_ACCEPT),
                counts.get(Reason.HANDSHAKE_TIMEOUT));
    }

    private void reject(Reason reason) {
        rejectedTotal.get(reason).incrementAndGet();
        rejectedSinceReport.get(reason).incrementAndGet();
    }

    /** The connections turned away for this reason since the process started. */
    public long rejected(Reason reason) {
        return rejectedTotal.get(reason).get();
    }

    /** The sockets currently counted against {@code synaxis.max-connections}. */
    public int connections() {
        return connections.get();
    }

    /** The seats currently taken, see {@code synaxis.max-players}. */
    public int seats() {
        return seats.get();
    }

    /** The addresses currently holding a token bucket. */
    int bucketKeys() {
        synchronized (buckets) {
            return buckets.size();
        }
    }

    private boolean isTrusted(SocketAddress address) {
        return anyContains(trustedNetworks, address);
    }

    /**
     * Whether {@code address}, the peer of the socket itself, may send a PROXY protocol header:
     * it is in {@code synaxis.proxy-protocol.trusted-networks} ({@code docs/DESIGN-PROD.md}
     * {@code REQ-PROD-036}).
     */
    public boolean isTrustedProxy(SocketAddress address) {
        return anyContains(trustedProxies, address);
    }

    private static boolean anyContains(List<Network> networks, SocketAddress address) {
        if (networks.isEmpty()) return false;
        if (!(address instanceof InetSocketAddress inet) || inet.getAddress() == null) return false;
        final byte[] bytes = inet.getAddress().getAddress();
        for (Network network : networks) {
            if (network.contains(bytes)) return true;
        }
        return false;
    }

    /**
     * Parses {@code synaxis.connection-gate.trusted-networks}: comma separated literal addresses,
     * each with an optional prefix length, e.g. {@code 10.0.0.0/8, fd00::/8}. Host names are
     * refused, resolving one would put the gate behind a DNS lookup.
     *
     * @throws IllegalArgumentException if an entry is not a network
     */
    public static List<Network> parseNetworks(String value) {
        return parseNetworks("synaxis.connection-gate.trusted-networks", value);
    }

    /**
     * Parses a list of networks in the format of {@link #parseNetworks(String)}.
     *
     * @param property the property the value comes from, named in the error
     * @throws IllegalArgumentException if an entry is not a network
     */
    public static List<Network> parseNetworks(String property, String value) {
        final List<Network> networks = new ArrayList<>();
        for (String entry : value.split(",", -1)) {
            final String trimmed = entry.strip();
            if (trimmed.isEmpty()) continue;
            final int slash = trimmed.indexOf('/');
            final String literal = slash < 0 ? trimmed : trimmed.substring(0, slash);
            final InetAddress address;
            try {
                address = InetAddress.ofLiteral(literal);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Not a network address in " + property + ": " + trimmed, e);
            }
            final byte[] bytes = address.getAddress();
            final int bits = bytes.length * 8;
            int prefixLength = bits;
            if (slash >= 0) {
                try {
                    prefixLength = Integer.parseInt(trimmed.substring(slash + 1));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Not a prefix length in " + property + ": " + trimmed, e);
                }
                if (prefixLength < 0 || prefixLength > bits)
                    throw new IllegalArgumentException("Prefix length out of range in " + property + ": " + trimmed);
            }
            networks.add(new Network(bytes, prefixLength));
        }
        return List.copyOf(networks);
    }

    /** A network of {@code synaxis.connection-gate.trusted-networks} or {@code synaxis.proxy-protocol.trusted-networks}. */
    public static final class Network {
        private final byte[] address;
        private final int prefixLength;

        private Network(byte[] address, int prefixLength) {
            this.address = address;
            this.prefixLength = prefixLength;
        }

        boolean contains(byte[] other) {
            if (other.length != address.length) return false;
            int remaining = prefixLength;
            for (int i = 0; i < address.length && remaining > 0; i++, remaining -= 8) {
                final int mask = remaining >= 8 ? 0xFF : (0xFF << (8 - remaining)) & 0xFF;
                if ((address[i] & mask) != (other[i] & mask)) return false;
            }
            return true;
        }
    }

    /** The key of an address: IPv4 as a whole, IPv6 by its /64, a peer usually owns the rest. */
    record Prefix(boolean ipv6, long bits) {
        static @Nullable Prefix of(SocketAddress address) {
            if (!(address instanceof InetSocketAddress inet) || inet.getAddress() == null) return null;
            final byte[] bytes = inet.getAddress().getAddress();
            long bits = 0;
            final int length = Math.min(bytes.length, 8);
            for (int i = 0; i < length; i++) bits = (bits << 8) | (bytes[i] & 0xFF);
            return new Prefix(bytes.length == 16, bits);
        }
    }

    private static final class Buckets {
        private final Bucket login, status, transfer;

        Buckets(ServerConfig config, long now) {
            this.login = new Bucket(config.loginRatePerIp(), config.loginBurstPerIp(), now);
            this.status = new Bucket(config.statusRatePerIp(), config.statusBurstPerIp(), now);
            this.transfer = new Bucket(config.transferRatePerIp(), config.transferBurstPerIp(), now);
        }

        Bucket of(ClientHandshakePacket.Intent intent) {
            return switch (intent) {
                case LOGIN -> login;
                case STATUS -> status;
                case TRANSFER -> transfer;
            };
        }

        boolean full(long now) {
            return login.full(now) && status.full(now) && transfer.full(now);
        }
    }

    /** A token bucket: {@code burst} tokens, refilled at {@code rate} per second. */
    private static final class Bucket {
        private static final double NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);

        private final double rate;
        private final int burst;
        private double tokens;
        private long refilledAt;

        Bucket(double rate, int burst, long now) {
            this.rate = rate;
            this.burst = burst;
            this.tokens = burst;
            this.refilledAt = now;
        }

        private void refill(long now) {
            tokens = Math.min(burst, tokens + (now - refilledAt) * rate / NANOS_PER_SECOND);
            refilledAt = now;
        }

        boolean tryTake(long now) {
            refill(now);
            if (tokens < 1) return false;
            tokens -= 1;
            return true;
        }

        boolean full(long now) {
            refill(now);
            return tokens >= burst;
        }
    }

    /**
     * What one connection holds in the gate: a socket of L0, the L1 count of its address and an L3
     * seat. All three are given back once, when the connection closes; whatever the connection
     * would take after that is refused, so a count taken while it is closing cannot outlive it.
     */
    public final class Ticket {
        private final boolean socket;
        private @Nullable Prefix counted;
        private boolean seat;
        private boolean released;

        private Ticket(boolean socket) {
            this.socket = socket;
        }

        private synchronized boolean countAddress(SocketAddress address) {
            if (released || counted != null) return true;
            final Prefix prefix = Prefix.of(address);
            if (prefix == null || isTrusted(address)) return true;
            final int[] count = new int[1];
            connectionsPerPrefix.compute(prefix, (_, current) -> {
                final AtomicInteger value = current != null ? current : new AtomicInteger();
                count[0] = value.incrementAndGet();
                return value;
            });
            if (count[0] > config.maxConnectionsPerIp()) {
                uncount(prefix);
                return false;
            }
            this.counted = prefix;
            return true;
        }

        private synchronized boolean takeSeat() {
            if (released || seat) return true;
            final int max = config.maxPlayers();
            if (max > 0) {
                int taken;
                do {
                    taken = seats.get();
                    if (taken >= max) return false;
                } while (!seats.compareAndSet(taken, taken + 1));
            } else {
                seats.incrementAndGet();
            }
            this.seat = true;
            return true;
        }

        /** Gives back everything this connection holds, once. */
        public synchronized void release() {
            if (released) return;
            released = true;
            if (socket) connections.decrementAndGet();
            final Prefix prefix = this.counted;
            if (prefix != null) uncount(prefix);
            if (seat) seats.decrementAndGet();
        }

        /** Whether this connection holds a seat. */
        public synchronized boolean holdsSeat() {
            return seat;
        }
    }

    private void uncount(Prefix prefix) {
        connectionsPerPrefix.computeIfPresent(prefix, (_, count) -> count.decrementAndGet() == 0 ? null : count);
    }
}
