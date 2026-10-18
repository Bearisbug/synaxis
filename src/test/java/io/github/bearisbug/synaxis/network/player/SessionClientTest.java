package io.github.bearisbug.synaxis.network.player;

import com.sun.net.httpserver.HttpServer;
import io.github.bearisbug.synaxis.SessionServer;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a 200 of {@code hasJoined} is read ({@code docs/DESIGN-PROD.md} {@code API-PROD-002}). */
public class SessionClientTest {

    @Test
    public void aProfileKeepsItsPropertiesAsSent() {
        final var verified = assertInstanceOf(SessionClient.Result.Verified.class, SessionClient.profile("""
                {"id":"069a79f444e94726a5befca90e38aaf5","name":"Notch","properties":[
                 {"name":"textures","value":"dGV4dHVyZXM=","signature":"c2lnbmF0dXJl"},
                 {"name":"unsigned","value":"eA=="}]}"""));
        assertEquals(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), verified.profile().uuid());
        assertEquals("Notch", verified.profile().name());
        assertEquals(List.of(new GameProfile.Property("textures", "dGV4dHVyZXM=", "c2lnbmF0dXJl"),
                new GameProfile.Property("unsigned", "eA==", null)), verified.profile().properties());
    }

    @Test
    public void aTwoHundredThatIsNotAProfileIsUnavailableNotAVerdict() {
        for (String body : List.of("", "not json", "{}", "{\"id\":\"xyz\",\"name\":\"Notch\"}",
                "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"AMuchTooLongPlayerName\"}")) {
            assertInstanceOf(SessionClient.Result.Unavailable.class, SessionClient.profile(body), body);
        }
    }

    /**
     * A 429 stops the queries for its {@code Retry-After} in seconds, at most 60; 10 when it has
     * none or one that is not a number of seconds ({@code docs/TEST.md} TC-PROD-583).
     */
    @Test
    public void theRetryAfterOfATooManyRequestsAnswerIsSecondsUpToAMinute() {
        assertEquals(10, SessionClient.retryAfterSeconds(null));
        assertEquals(10, SessionClient.retryAfterSeconds(""));
        assertEquals(10, SessionClient.retryAfterSeconds("soon"));
        assertEquals(10, SessionClient.retryAfterSeconds("-1"));
        assertEquals(10, SessionClient.retryAfterSeconds("Wed, 01 Oct 2026 07:28:00 GMT"));
        assertEquals(0, SessionClient.retryAfterSeconds("0"));
        assertEquals(3, SessionClient.retryAfterSeconds(" 3 "));
        assertEquals(60, SessionClient.retryAfterSeconds("60"));
        assertEquals(60, SessionClient.retryAfterSeconds("3600"));
    }

    /**
     * An unavailable answer says why: the status the session server answered, or the exception the
     * query failed with and its message, which a refused connection only carries on its cause.
     */
    @Test
    public void anUnavailableAnswerCarriesItsCause() throws Exception {
        final HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/hasJoined", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            final SessionClient.Result answer = verify(server.getAddress().getPort());
            assertEquals("status 503", assertInstanceOf(SessionClient.Result.Unavailable.class, answer).cause());
        } finally {
            server.stop(0);
        }

        final int closed;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = socket.getLocalPort();
        }
        final String cause = assertInstanceOf(SessionClient.Result.Unavailable.class, verify(closed)).cause();
        assertTrue(cause.startsWith("ConnectException: "), cause);
        assertFalse(cause.substring("ConnectException: ".length()).isBlank(), cause);
    }

    private static SessionClient.Result verify(int port) throws InterruptedException {
        final SessionClient client = new SessionClient(SessionServer.at(
                URI.create("http://127.0.0.1:" + port + "/hasJoined")));
        return client.verify("Notch", "hash", null, System.currentTimeMillis() + 10_000, () -> true);
    }
}
