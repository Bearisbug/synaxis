package io.github.bearisbug.synaxis.network;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.bearisbug.synaxis.SessionServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for Mojang's {@code hasJoined} inside the test JVM ({@code docs/DESIGN-PROD.md} §19):
 * scripted answers, the queries it received, and how many of them were in flight at once.
 */
final class TestSessionServer implements AutoCloseable {

    /** One scripted answer: an HTTP status after an optional delay, 200 carrying the profile. */
    record Answer(int status, Duration delay) {
        static Answer ok() {
            return new Answer(200, Duration.ZERO);
        }

        static Answer status(int status) {
            return new Answer(status, Duration.ZERO);
        }

        static Answer okAfter(Duration delay) {
            return new Answer(200, delay);
        }
    }

    /** What the server was asked. */
    record Query(String username, String serverId) {
    }

    private final HttpServer server;
    private final Deque<Answer> script = new ArrayDeque<>();
    private final Map<String, UUID> uuids = new HashMap<>();
    private final Map<String, String> textures = new HashMap<>();
    private final List<Query> queries = new CopyOnWriteArrayList<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger peakInFlight = new AtomicInteger();
    private volatile CountDownLatch gate = new CountDownLatch(0);

    TestSessionServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/session/minecraft/hasJoined", this::hasJoined);
        server.start();
    }

    /** The online mode settings pointing at this server, with a short timeout. */
    SessionServer sessionServer(Duration timeout, int maxInFlight) {
        return new SessionServer(URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/session/minecraft/hasJoined"), false, timeout, maxInFlight);
    }

    /** The uuid the profile of {@code username} carries, a random one unless set here. */
    synchronized void profile(String username, UUID uuid, String texturesValue) {
        uuids.put(username, uuid);
        textures.put(username, texturesValue);
    }

    /** Answers the next queries in this order, then the default 200. */
    synchronized void script(Answer... answers) {
        script.addAll(List.of(answers));
    }

    /** Holds every query until {@link #release()}. */
    void hold() {
        this.gate = new CountDownLatch(1);
    }

    void release() {
        gate.countDown();
    }

    List<Query> queries() {
        return List.copyOf(queries);
    }

    int peakInFlight() {
        return peakInFlight.get();
    }

    int inFlight() {
        return inFlight.get();
    }

    private void hasJoined(HttpExchange exchange) throws IOException {
        final int now = inFlight.incrementAndGet();
        peakInFlight.accumulateAndGet(now, Math::max);
        try {
            final Map<String, String> parameters = parameters(exchange.getRequestURI().getRawQuery());
            final String username = parameters.getOrDefault("username", "");
            queries.add(new Query(username, parameters.getOrDefault("serverId", "")));
            gate.await(30, TimeUnit.SECONDS);
            final Answer answer;
            final UUID uuid;
            final String texture;
            synchronized (this) {
                answer = script.isEmpty() ? Answer.ok() : script.poll();
                uuid = uuids.computeIfAbsent(username, _ -> UUID.randomUUID());
                texture = textures.getOrDefault(username, "");
            }
            if (!answer.delay().isZero()) Thread.sleep(answer.delay());
            if (answer.status() != 200) {
                exchange.sendResponseHeaders(answer.status(), -1);
                return;
            }
            final String body = "{\"id\":\"" + uuid.toString().replace("-", "") + "\",\"name\":\"" + username
                    + "\",\"properties\":[{\"name\":\"textures\",\"value\":\"" + texture
                    + "\",\"signature\":\"sig-" + texture + "\"}]}";
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } catch (IOException _) {
            // The client gave up on this query (its timeout), nobody reads the answer
        } finally {
            inFlight.decrementAndGet();
            exchange.close();
        }
    }

    private static Map<String, String> parameters(String query) {
        final Map<String, String> parameters = new HashMap<>();
        if (query == null) return parameters;
        for (String pair : query.split("&", -1)) {
            final int equals = pair.indexOf('=');
            if (equals < 0) continue;
            parameters.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
        }
        return parameters;
    }

    @Override
    public void close() {
        release();
        server.stop(0);
    }
}
