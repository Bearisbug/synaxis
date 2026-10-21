package io.github.bearisbug.synaxis.network.plugin;

import io.github.bearisbug.synaxis.network.packet.server.login.LoginPluginRequestPacket;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@ApiStatus.Internal
public class LoginPluginMessageProcessor {
    private static final AtomicInteger REQUEST_ID = new AtomicInteger(0);

    private final Map<Integer, LoginPlugin.Request> requestByMsgId = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final PlayerConnection connection;

    public LoginPluginMessageProcessor(PlayerConnection connection) {
        this.connection = connection;
    }

    /**
     * Sends a login plugin request to the client.
     *
     * @return the answer of the client, failed with an {@link IllegalStateException} if this
     * processor is closed first
     * @throws IllegalStateException if this processor is closed
     */
    public CompletableFuture<LoginPlugin.Response> request(String channel, byte [] requestPayload) {
        if (closed.get()) throw new IllegalStateException("login plugin requests are only valid during LOGIN");
        LoginPlugin.Request request = new LoginPlugin.Request(channel, requestPayload);

        final int messageId = REQUEST_ID.getAndIncrement();
        requestByMsgId.put(messageId, request);
        if (closed.get()) {
            // Closed after the check above: close() may have gone through the map before this request was in it
            if (requestByMsgId.remove(messageId) != null) request.responseFuture().completeExceptionally(abandoned());
            return request.responseFuture();
        }
        connection.sendPacket(new LoginPluginRequestPacket(messageId, request.channel(), request.payload()));

        return request.responseFuture();
    }

    /**
     * Completes the request the client answered.
     *
     * @throws IllegalStateException if no request with this id is waiting for an answer
     */
    public void handleResponse(int messageId, byte @Nullable [] responseData) {
        LoginPlugin.Request request = requestByMsgId.remove(messageId);
        if (request == null) {
            throw new IllegalStateException("Received unexpected Login Plugin Response id " + messageId + " of "
                    + (responseData == null ? "no" : responseData.length) + " bytes");
        }
        request.responseFuture().complete(new LoginPlugin.Response(request.channel(), responseData));
    }

    public void awaitReplies(long timeout, TimeUnit timeUnit) throws Exception {
        if (requestByMsgId.isEmpty()) {
            return;
        }
        CompletableFuture<?>[] futures = requestByMsgId.values().stream()
                .map(LoginPlugin.Request::responseFuture)
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures).get(timeout, timeUnit);
    }

    /**
     * Ends the login plugin requests of the connection, once it leaves the login state or
     * disconnects: the requests still waiting for an answer fail with an
     * {@link IllegalStateException}, and {@link #request} refuses new ones. Only the first call
     * does anything.
     */
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (Integer messageId : List.copyOf(requestByMsgId.keySet())) {
            final LoginPlugin.Request request = requestByMsgId.remove(messageId);
            if (request != null) request.responseFuture().completeExceptionally(abandoned());
        }
    }

    private static IllegalStateException abandoned() {
        return new IllegalStateException("login plugin request abandoned: connection left LOGIN");
    }
}
