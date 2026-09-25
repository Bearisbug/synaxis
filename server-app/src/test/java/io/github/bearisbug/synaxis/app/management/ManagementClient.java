package io.github.bearisbug.synaxis.app.management;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * A management client for the tests: the JDK's own WebSocket client, an implementation that shares
 * no code with the server's, connecting the way an operator's tool would.
 */
final class ManagementClient implements WebSocket.Listener, AutoCloseable {

    static final Duration WAIT = Duration.ofSeconds(10);

    private final BlockingQueue<JsonObject> received = new LinkedBlockingQueue<>();
    private final List<JsonObject> notifications = new ArrayList<>();
    private final StringBuilder partial = new StringBuilder();
    /** The close code the server sent, completed when the connection ends. */
    final CompletableFuture<Integer> closed = new CompletableFuture<>();
    private @Nullable WebSocket socket;

    private ManagementClient() {
    }

    /**
     * Opens a connection, the {@code Authorization} header being whatever the test says.
     *
     * @param address       where the interface listens
     * @param authorization the header, null for none
     * @return the open connection
     * @throws ExecutionException with a {@link java.net.http.WebSocketHandshakeException} when refused
     */
    static ManagementClient connect(InetSocketAddress address, @Nullable String authorization) throws Exception {
        final ManagementClient client = new ManagementClient();
        // Never through the proxy of the machine: the interface is on loopback
        final WebSocket.Builder builder = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build()
                .newWebSocketBuilder().connectTimeout(WAIT);
        if (authorization != null) builder.header("Authorization", authorization);
        client.socket = builder.buildAsync(URI.create("ws://" + address.getHostString() + ":" + address.getPort() + "/"),
                client).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        return client;
    }

    WebSocket socket() {
        if (socket == null) throw new IllegalStateException("not connected");
        return socket;
    }

    void send(String text) throws Exception {
        socket().sendText(text, true).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Sends a request and waits for the answer with its id, keeping the notifications that arrive
     * meanwhile.
     */
    JsonObject call(int id, String method, @Nullable String params) throws Exception {
        send("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\""
                + (params == null ? "" : ",\"params\":" + params) + "}");
        return answer(id, null);
    }

    /** Waits for the answer with this id; while waiting, runs {@code meanwhile} (a tick) when given. */
    JsonObject answer(int id, @Nullable Runnable meanwhile) throws Exception {
        return next(message -> message.has("id") && !message.get("id").isJsonNull()
                && message.get("id").getAsInt() == id, meanwhile);
    }

    /** Waits for the next notification of this method. */
    JsonObject notification(String method, @Nullable Runnable meanwhile) throws Exception {
        for (JsonObject kept : notifications) {
            if (method.equals(kept.get("method").getAsString())) {
                notifications.remove(kept);
                return kept;
            }
        }
        return next(message -> !message.has("id") && method.equals(message.get("method").getAsString()), meanwhile);
    }

    /** The next message of any kind, null if none comes within the time. */
    @Nullable JsonObject poll(Duration within) throws InterruptedException {
        return received.poll(within.toMillis(), TimeUnit.MILLISECONDS);
    }

    private JsonObject next(Predicate<JsonObject> wanted, @Nullable Runnable meanwhile) throws Exception {
        final long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            final JsonObject message = received.poll(20, TimeUnit.MILLISECONDS);
            if (message == null) {
                if (meanwhile != null) meanwhile.run();
                continue;
            }
            if (wanted.test(message)) return message;
            if (!message.has("id")) notifications.add(message);
        }
        throw new TimeoutException("no such message within " + WAIT);
    }

    int awaitClose() throws Exception {
        try {
            return closed.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError("the connection is still open after " + WAIT, e);
        }
    }

    static void await(BooleanSupplier condition, String what) {
        final long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("not within " + WAIT + ": " + what);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    @Override
    public @Nullable CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            final JsonElement message = JsonParser.parseString(partial.toString());
            partial.setLength(0);
            received.add(message.getAsJsonObject());
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public @Nullable CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        closed.complete(statusCode);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        closed.completeExceptionally(error);
    }

    @Override
    public void close() {
        if (socket != null && !socket.isOutputClosed()) socket.abort();
    }
}
