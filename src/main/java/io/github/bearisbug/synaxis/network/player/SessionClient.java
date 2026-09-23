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

/**
 * Asks the session server whether a client joined this server ({@code docs/DESIGN-PROD.md}
 * {@code API-PROD-002}): the verification step of an online mode login, run on a virtual thread of
 * its own so that the read loop of the connection keeps going.
 * <p>
 * The answers are three: {@link Result.Verified} (200 with a profile), {@link Result.Unverified}
 * (204, the name did not join with that server hash) and {@link Result.Unavailable} (no answer in
 * time, 429, 5xx, or a 200 that is not a profile). Only the last one is retried, once.
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

    private final AtomicLong verified = new AtomicLong(), unverified = new AtomicLong(), unavailable = new AtomicLong();
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
     * Verifies one login, retrying an unavailable answer once after a random 0.5 to 1.5 s. Blocks,
     * so it runs on the verification thread of the connection.
     *
     * @param deadlineMillis when the login times out ({@link System#currentTimeMillis()}); a query
     *                       that cannot start before it, queue included, is unavailable
     * @return the final answer, counted in the report of {@link #tick(long)}
     */
    public Result verify(String username, String serverHash, @Nullable SocketAddress clientAddress, long deadlineMillis)
            throws InterruptedException {
        Result result = query(username, serverHash, clientAddress, deadlineMillis);
        if (result instanceof Result.Unavailable) {
            final long delay = ThreadLocalRandom.current().nextLong(RETRY_DELAY_MIN_MILLIS, RETRY_DELAY_MAX_MILLIS + 1);
            if (System.currentTimeMillis() + delay < deadlineMillis) {
                Thread.sleep(delay);
                result = query(username, serverHash, clientAddress, deadlineMillis);
            }
        }
        (switch (result) {
            case Result.Verified _ -> verified;
            case Result.Unverified _ -> unverified;
            case Result.Unavailable _ -> unavailable;
        }).incrementAndGet();
        return result;
    }

    private Result query(String username, String serverHash, @Nullable SocketAddress clientAddress, long deadlineMillis)
            throws InterruptedException {
        final long queueBudget = deadlineMillis - System.currentTimeMillis();
        if (queueBudget <= 0 || !inFlight.tryAcquire(queueBudget, TimeUnit.MILLISECONDS))
            return new Result.Unavailable("no query slot before the login timeout");
        try {
            final long remaining = deadlineMillis - System.currentTimeMillis();
            if (remaining <= 0) return new Result.Unavailable("no time left before the login timeout");
            final HttpRequest request = HttpRequest.newBuilder(uri(username, serverHash, clientAddress))
                    .timeout(Duration.ofMillis(Math.min(server.timeout().toMillis(), remaining)))
                    .GET().build();
            final HttpResponse<String> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (IOException e) {
                return new Result.Unavailable(e.getClass().getSimpleName());
            }
            return switch (response.statusCode()) {
                case 200 -> profile(response.body());
                case 204 -> new Result.Unverified();
                default -> new Result.Unavailable("status " + response.statusCode());
            };
        } finally {
            inFlight.release();
        }
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
            return new Result.Unavailable("malformed profile: " + e.getClass().getSimpleName());
        }
    }

    private static UUID undashedUuid(String id) {
        if (id.length() != 32) throw new IllegalArgumentException("not an undashed uuid: " + id);
        return new UUID(Long.parseUnsignedLong(id, 0, 16, 16), Long.parseUnsignedLong(id, 16, 32, 16));
    }

    /**
     * Writes the outcomes counted over the last 10 seconds as one line, if a login failed
     * verification in that time. Called once per tick.
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
        LOGGER.warn("Session verification in the last 10 s: verified={} unverified={} unavailable={} session_server={}",
                verifiedCount, unverifiedCount, unavailableCount, server.hasJoinedUrl());
    }

    /**
     * The default proxy selector, except for loopback addresses: a stand-in session server on the
     * same host is reached directly even when a proxy is configured for the outside world.
     */
    private static final class LoopbackDirect extends ProxySelector {
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
            if (!Character.isDigit(host.charAt(0)) && !host.startsWith("[")) return false;
            try {
                return InetAddress.getByName(host).isLoopbackAddress();
            } catch (IOException _) {
                return false;
            }
        }
    }
}
