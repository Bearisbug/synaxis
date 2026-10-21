package io.github.bearisbug.synaxis.network.player;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.bearisbug.synaxis.SessionServer;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * Asks the session server whether a client joined this server ({@code docs/DESIGN-PROD.md}
 * {@code API-PROD-002}): the verification step of an online mode login, run on a virtual thread of
 * its own so that the read loop of the connection keeps going.
 * <p>
 * The answers are three: {@link Result.Verified} (200 with a profile), {@link Result.Unverified}
 * (204, the name did not join with that server hash) and {@link Result.Unavailable} (no answer in
 * time, 429, 5xx, or a 200 that is not a profile). Only the last one is retried, once.
 * <p>
 * A 429 is the session server asking this address to wait: for its {@code Retry-After} in seconds
 * (10 when missing or not a number of seconds, at most 60) nothing is retried and no query is sent,
 * every verification in that time is unavailable at once.
 * <p>
 * Log volume: nothing per login. Each outcome is counted and {@link #tick(long)} writes the counts
 * of the last 10 seconds as at most one line, so a peer that keeps failing verification does not
 * decide how much is logged ({@code docs/DESIGN.md} §15).
 */
@ApiStatus.Internal
public final class SessionClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionClient.class);

    private static final long REPORT_INTERVAL = TimeUnit.SECONDS.toNanos(10);
    /** The random back-off before the one retry of an unavailable answer. */
    private static final long RETRY_DELAY_MIN_MILLIS = 500, RETRY_DELAY_MAX_MILLIS = 1_500;
    /** How long a 429 stops the queries without a usable {@code Retry-After}, and at most with one. */
    static final long DEFAULT_RETRY_AFTER_SECONDS = 10, MAX_RETRY_AFTER_SECONDS = 60;

    /** What the session server said about one login. */
    public sealed interface Result {
        record Verified(GameProfile profile) implements Result {
        }

        record Unverified() implements Result {
        }

        record Unavailable(String cause) implements Result {
        }
    }

    private final SessionServer server;
    private final HttpClient http;
    private final Semaphore inFlight;
    // Until when (System#nanoTime) the session server asked not to be queried, after a 429
    private final AtomicLong quietUntil = new AtomicLong(System.nanoTime());

    private final AtomicLong verified = new AtomicLong(), unverified = new AtomicLong(), unavailable = new AtomicLong();
    // The cause of the last unavailable answer, set before the answer is counted: a report that counted
    // one has a cause, whichever thread wins the race between counting and reporting
    private final AtomicReference<@Nullable String> lastUnavailableCause = new AtomicReference<>();
    // Since the process started, for the metrics endpoint (API-PROD-015); the three above are per report
    private final AtomicLong verifiedTotal = new AtomicLong(), unverifiedTotal = new AtomicLong(),
            unavailableTotal = new AtomicLong(), retries = new AtomicLong();
    private volatile LongConsumer queryObserver = _ -> {
    };
    private boolean reportWindowStarted;
    private long reportWindowStart;

    public SessionClient(SessionServer server) {
        this.server = server;
        this.inFlight = new Semaphore(server.maxInFlight(), true);
        this.http = HttpClient.newBuilder()
                .connectTimeout(server.timeout())
                .proxy(new LoopbackDirect(ProxySelector.getDefault()))
                .build();
    }

    /**
     * Verifies one login, retrying an unavailable answer once after a random 0.5 to 1.5 s unless a
     * 429 asked to wait. Blocks, so it runs on the verification thread of the connection.
     *
     * @param deadlineMillis when the login times out ({@link System#currentTimeMillis()}); a query
     *                       that cannot start before it, queue included, is unavailable
     * @param online         whether the connection is still there; checked once a query slot is
     *                       held and before the retry, so a peer that left asks nothing more
     * @return the final answer, counted in the report of {@link #tick(long)} unless the peer left
     */
    public Result verify(String username, String serverHash, @Nullable SocketAddress clientAddress, long deadlineMillis,
                         BooleanSupplier online) throws InterruptedException {
        Result result = query(username, serverHash, clientAddress, deadlineMillis, online);
        if (result instanceof Result.Unavailable && !quiet() && online.getAsBoolean()) {
            final long delay = ThreadLocalRandom.current().nextLong(RETRY_DELAY_MIN_MILLIS, RETRY_DELAY_MAX_MILLIS + 1);
            if (System.currentTimeMillis() + delay < deadlineMillis) {
                retries.incrementAndGet();
                Thread.sleep(delay);
                result = query(username, serverHash, clientAddress, deadlineMillis, online);
            }
        }
        if (!online.getAsBoolean()) return result;
        if (result instanceof Result.Unavailable(String cause)) lastUnavailableCause.set(cause);
        (switch (result) {
            case Result.Verified _ -> verified;
            case Result.Unverified _ -> unverified;
            case Result.Unavailable _ -> unavailable;
        }).incrementAndGet();
        (switch (result) {
            case Result.Verified _ -> verifiedTotal;
            case Result.Unverified _ -> unverifiedTotal;
            case Result.Unavailable _ -> unavailableTotal;
        }).incrementAndGet();
        return result;
    }

    private Result query(String username, String serverHash, @Nullable SocketAddress clientAddress, long deadlineMillis,
                         BooleanSupplier online) throws InterruptedException {
        if (quiet()) return quietAnswer();
        final long queueBudget = deadlineMillis - System.currentTimeMillis();
        if (queueBudget <= 0 || !inFlight.tryAcquire(queueBudget, TimeUnit.MILLISECONDS))
            return new Result.Unavailable("no query slot before the login timeout");
        try {
            if (!online.getAsBoolean()) return new Result.Unavailable("the peer left");
            // A 429 may have come back while this query waited for its slot
            if (quiet()) return quietAnswer();
            final long remaining = deadlineMillis - System.currentTimeMillis();
            if (remaining <= 0) return new Result.Unavailable("no time left before the login timeout");
            final HttpRequest request = HttpRequest.newBuilder(uri(username, serverHash, clientAddress))
                    .timeout(Duration.ofMillis(Math.min(server.timeout().toMillis(), remaining)))
                    .GET().build();
            final HttpResponse<String> response;
            final long sent = System.nanoTime();
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (IOException e) {
                return new Result.Unavailable(describe(e));
            } finally {
                queryObserver.accept(System.nanoTime() - sent);
            }
            return switch (response.statusCode()) {
                case 200 -> profile(response.body());
                case 204 -> new Result.Unverified();
                case 429 -> {
                    final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(
                            retryAfterSeconds(response.headers().firstValue("Retry-After").orElse(null)));
                    quietUntil.accumulateAndGet(until, (current, next) -> next - current > 0 ? next : current);
                    yield new Result.Unavailable("status 429");
                }
                default -> new Result.Unavailable("status " + response.statusCode());
            };
        } finally {
            inFlight.release();
        }
    }

    private boolean quiet() {
        return quietUntil.get() - System.nanoTime() > 0;
    }

    private static Result quietAnswer() {
        return new Result.Unavailable("the session server asked to wait (429)");
    }

    /**
     * How long a 429 stops the queries: its {@code Retry-After} in seconds, at most
     * {@link #MAX_RETRY_AFTER_SECONDS}; {@link #DEFAULT_RETRY_AFTER_SECONDS} when the header is
     * missing or not a number of seconds, an HTTP date included.
     *
     * @param value the header, null if missing
     * @return the seconds to wait
     */
    static long retryAfterSeconds(@Nullable String value) {
        if (value != null) {
            try {
                final long seconds = Long.parseLong(value.strip());
                if (seconds >= 0) return Math.min(seconds, MAX_RETRY_AFTER_SECONDS);
            } catch (NumberFormatException _) {
                // Not a number of seconds, the default applies
            }
        }
        return DEFAULT_RETRY_AFTER_SECONDS;
    }

    /**
     * The final answers since the process started, one per login, counted like the report of
     * {@link #tick(long)}: a login whose peer left before the answer is not counted.
     *
     * @return verified, unverified and unavailable, in that order
     */
    public long[] outcomes() {
        return new long[]{verifiedTotal.get(), unverifiedTotal.get(), unavailableTotal.get()};
    }

    /** The retries of an unavailable answer since the process started. */
    public long retries() {
        return retries.get();
    }

    /**
     * Tells {@code observer} how long each query to the session server took, in nanoseconds,
     * failures and timeouts included, on the verification thread of the login.
     *
     * @param observer the observer
     */
    public void observeQueries(LongConsumer observer) {
        this.queryObserver = observer;
    }

    private URI uri(String username, String serverHash, @Nullable SocketAddress clientAddress) {
        final StringBuilder query = new StringBuilder(server.hasJoinedUrl().toString())
                .append("?username=").append(URLEncoder.encode(username, StandardCharsets.UTF_8))
                .append("&serverId=").append(URLEncoder.encode(serverHash, StandardCharsets.UTF_8));
        if (server.sendClientIp() && clientAddress instanceof InetSocketAddress socketAddress
                && socketAddress.getAddress() != null) {
            query.append("&ip=").append(URLEncoder.encode(socketAddress.getAddress().getHostAddress(), StandardCharsets.UTF_8));
        }
        return URI.create(query.toString());
    }

    /** A 200 carries the profile; one that does not is a broken answer, not a verdict. */
    static Result profile(String body) {
        try {
            final JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            final UUID uuid = undashedUuid(json.get("id").getAsString());
            final String name = json.get("name").getAsString();
            final List<GameProfile.Property> properties = new ArrayList<>();
            final JsonElement propertiesJson = json.get("properties");
            if (propertiesJson != null && !propertiesJson.isJsonNull()) {
                final JsonArray array = propertiesJson.getAsJsonArray();
                for (JsonElement element : array) {
                    final JsonObject property = element.getAsJsonObject();
                    final JsonElement signature = property.get("signature");
                    properties.add(new GameProfile.Property(property.get("name").getAsString(),
                            property.get("value").getAsString(),
                            signature == null || signature.isJsonNull() ? null : signature.getAsString()));
                }
            }
            return new Result.Verified(new GameProfile(uuid, name, properties));
        } catch (RuntimeException e) {
            // Gson, a missing member, a malformed uuid or a name GameProfile refuses
            return new Result.Unavailable("malformed profile: " + describe(e));
        }
    }

    /**
     * {@code Type: detail}, the detail being the first message along the cause chain or, when no
     * exception of the chain has one, the type of the innermost cause: a refused connection is a
     * {@code ConnectException} over a {@code ClosedChannelException}, none of them with a message.
     */
    private static String describe(Exception e) {
        Throwable innermost = e;
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null) return e.getClass().getSimpleName() + ": " + cause.getMessage();
            innermost = cause;
        }
        return innermost == e ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + innermost.getClass().getSimpleName();
    }

    private static UUID undashedUuid(String id) {
        if (id.length() != 32) throw new IllegalArgumentException("not an undashed uuid: " + id);
        return new UUID(Long.parseUnsignedLong(id, 0, 16, 16), Long.parseUnsignedLong(id, 16, 32, 16));
    }

    /**
     * Writes the outcomes counted over the last 10 seconds as one line, if a login failed
     * verification in that time, ending with the cause of the last unavailable answer ({@code -} if
     * the line counts none). Called once per tick.
     *
     * @param nanoTime the time of the tick, {@link System#nanoTime()}
     */
    public void tick(long nanoTime) {
        if (!reportWindowStarted) {
            reportWindowStarted = true;
            reportWindowStart = nanoTime;
            return;
        }
        if (nanoTime - reportWindowStart < REPORT_INTERVAL) return;
        reportWindowStart = nanoTime;
        final long verifiedCount = verified.getAndSet(0);
        final long unverifiedCount = unverified.getAndSet(0);
        final long unavailableCount = unavailable.getAndSet(0);
        if (unverifiedCount == 0 && unavailableCount == 0) return;
        LOGGER.warn("Session verification in the last 10 s: verified={} unverified={} unavailable={} session_server={} "
                        + "last_unavailable_cause={}", verifiedCount, unverifiedCount, unavailableCount, server.hasJoinedUrl(),
                unavailableCount == 0 ? "-" : lastUnavailableCause.get());
    }

    /**
     * The default proxy selector, except for loopback addresses: a stand-in session server on the
     * same host is reached directly even when a proxy is configured for the outside world.
     */
    static final class LoopbackDirect extends ProxySelector {
        private final @Nullable ProxySelector fallback;

        LoopbackDirect(@Nullable ProxySelector fallback) {
            this.fallback = fallback;
        }

        @Override
        public List<Proxy> select(URI uri) {
            final String host = uri.getHost();
            if (fallback == null || host == null || isLoopback(host)) return List.of(Proxy.NO_PROXY);
            return fallback.select(uri);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException failure) {
            if (fallback != null) fallback.connectFailed(uri, address, failure);
        }

        private static boolean isLoopback(String host) {
            if (host.equalsIgnoreCase("localhost")) return true;
            // Only a literal is looked at, resolving a name here would block the query on DNS
            final String literal = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
            try {
                return InetAddress.ofLiteral(literal).isLoopbackAddress();
            } catch (IllegalArgumentException _) {
                return false;
            }
        }
    }
}
