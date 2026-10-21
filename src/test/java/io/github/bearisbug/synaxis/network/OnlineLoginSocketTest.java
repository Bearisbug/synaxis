package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.Auth;
import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.crypto.KeyUtils;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerPreLoginEvent;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginAcknowledgedPacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginStartPacket;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.EncryptionRequestPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginSuccessPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.SessionClient;
import io.github.bearisbug.synaxis.network.socket.Server;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The online mode login of {@code docs/DESIGN-PROD.md} {@code REQ-PROD-002} and
 * {@code REQ-PROD-003} through a real server on loopback: a JCE client on one side, a stand-in
 * session server on the other.
 */
public class OnlineLoginSocketTest {

    private static final Duration GENEROUS = Duration.ofSeconds(10);

    @Test
    public void anOnlineLoginTakesTheIdentityTheSessionServerVouchesFor() throws Exception {
        try (TestSessionServer sessions = new TestSessionServer();
             Rig rig = Rig.online(sessions, GENEROUS, 32);
             EncryptedLoginClient client = rig.client()) {
            final UUID mojang = UUID.randomUUID();
            sessions.profile("Steve", mojang, "skin-of-steve");

            client.handshakeAndStart("Steve");
            final EncryptionRequestPacket request = client.readEncryptionRequest();
            assertTrue(request.shouldAuthenticate(), "an online mode request asks the client to join a session");
            assertEquals("", request.serverId());
            client.answer(request);
            final LoginSuccessPacket success = assertInstanceOf(LoginSuccessPacket.class, client.readLoginOutcome());

            final GameProfile profile = success.gameProfile();
            assertEquals(mojang, profile.uuid(), "the uuid is the session server's");
            assertNotEquals(GameProfile.offlineUuid("Steve"), profile.uuid());
            assertEquals("Steve", profile.name());
            assertEquals(List.of(new GameProfile.Property("textures", "skin-of-steve", "sig-skin-of-steve")),
                    profile.properties(), "the signed textures travel with the profile (REQ-PROD-003)");
            assertEquals(List.of(new TestSessionServer.Query("Steve", client.serverHash())), sessions.queries(),
                    "one query, with the server hash the client computed");
            assertFalse(contains(client.rawIncoming(), "Steve"),
                    "the login success crossed the wire in plaintext");
        }
    }

    @Test
    public void aNameThatDidNotJoinIsRefusedWithAnEncryptedAuthFailed() throws Exception {
        try (TestSessionServer sessions = new TestSessionServer();
             Rig rig = Rig.online(sessions, GENEROUS, 32);
             EncryptedLoginClient client = rig.client()) {
            sessions.script(TestSessionServer.Answer.status(204));

            client.handshakeAndStart("Cracked");
            client.answer(client.readEncryptionRequest());
            // Read through the cipher: a plaintext refusal would not even parse
            assertEquals("synaxis:auth-failed", EncryptedLoginClient.loginDisconnectKey(client.readLoginOutcome()));
            assertFalse(contains(client.rawIncoming(), "synaxis:auth-failed"), "the refusal was sent in plaintext");
            assertEquals(1, sessions.queries().size(), "a 204 is a verdict, it is not retried");
            assertTrue(client.closedWithoutAnotherPacket());
        }
    }

    @Test
    public void anUnavailableSessionServerIsAskedOnceMore() throws Exception {
        try (TestSessionServer sessions = new TestSessionServer();
             Rig rig = Rig.online(sessions, GENEROUS, 32);
             EncryptedLoginClient client = rig.client()) {
            sessions.script(TestSessionServer.Answer.status(503), TestSessionServer.Answer.ok());

            client.login("Retried");
            assertEquals(2, sessions.queries().size());
        }
    }

    @Test
    public void twoUnavailableAnswersRefuseWithAuthUnavailable() throws Exception {
        try (TestSessionServer sessions = new TestSessionServer();
             Rig rig = Rig.online(sessions, GENEROUS, 32);
             EncryptedLoginClient client = rig.client()) {
            sessions.script(TestSessionServer.Answer.status(503), TestSessionServer.Answer.status(429));

            client.handshakeAndStart("Unlucky");
            client.answer(client.readEncryptionRequest());
            assertEquals("synaxis:auth-unavailable", EncryptedLoginClient.loginDisconnectKey(client.readLoginOutcome()));
            assertEquals(2, sessions.queries().size(), "retried exactly once");
        }
    }

    /**
     * A 429 is the session server asking to wait: it is not retried, and until its
     * {@code Retry-After} is over no login asks again ({@code docs/TEST.md} TC-PROD-583).
     */
    @Test
    public void aTooManyRequestsAnswerIsNotRetried() throws Exception {
        try (TestSessionServer sessions = new TestSessionServer();
             Rig rig = Rig.online(sessions, GENEROUS, 32);
             EncryptedLoginClient client = rig.client()) {
            sessions.script(TestSessionServer.Answer.tooManyRequests(null));

            client.handshakeAndStart("Throttled");
            client.answer(client.readEncryptionRequest());
            final ServerPacket outcome = client.readLoginOutcome();
            assertEquals(1, sessions.queries().size(), "a 429 was asked again");
            assertEquals("synaxis:auth-unavailable", EncryptedLoginClient.loginDisconnectKey(outcome));
        }
    }

    @Test
    public void noLoginAsksTheSessionServerBeforeTheRetryAfterIsOver() throws Exception {
        try (TestSessionServer sessions = new TestSessionServer();
             Rig rig = Rig.online(sessions, GENEROUS, 32)) {
            sessions.script(TestSessionServer.Answer.tooManyRequests("5"));
            final ServerPacket first, second;
            try (EncryptedLoginClient client = rig.client()) {
                client.handshakeAndStart("First");
                client.answer(client.readEncryptionRequest());
                first = client.readLoginOutcome();
            }
            final long answered = System.nanoTime();
            try (EncryptedLoginClient client = rig.client()) {
                client.handshakeAndStart("Second");
                client.answer(client.readEncryptionRequest());
                second = client.readLoginOutcome();
            }
            assertTrue(System.nanoTime() - answered < TimeUnit.SECONDS.toNanos(5), "the second login took too long to tell");
            assertEquals(List.of("First"), sessions.queries().stream().map(TestSessionServer.Query::username).toList(),
                    "a login within the Retry-After asked the session server");
            assertEquals("synaxis:auth-unavailable", EncryptedLoginClient.loginDisconnectKey(first));
            assertEquals("synaxis:auth-unavailable", EncryptedLoginClient.loginDisconnectKey(second));

            Thread.sleep(Math.max(0, TimeUnit.SECONDS.toMillis(5) - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - answered)) + 100);
            try (EncryptedLoginClient third = rig.client()) {
                third.login("Third");
            }
            assertEquals(2, sessions.queries().size(), "the login after the Retry-After asks");
        }
    }

    @Test
    public void aSessionServerThatDoesNotAnswerInTimeIsUnavailable() throws Exception {
        try (TestSessionServer sessions = new TestSessionServer();
             Rig rig = Rig.online(sessions, Duration.ofSeconds(1), 32);
             EncryptedLoginClient client = rig.client()) {
            final Duration late = Duration.ofSeconds(3);
            sessions.script(TestSessionServer.Answer.okAfter(late), TestSessionServer.Answer.okAfter(late));

            client.handshakeAndStart("Patient");
            client.answer(client.readEncryptionRequest());
            assertEquals("synaxis:auth-unavailable", EncryptedLoginClient.loginDisconnectKey(client.readLoginOutcome()));
            assertEquals(2, sessions.queries().size());
        }
    }

    @Test
    public void noMoreThanMaxInFlightQueriesRunAtOnce() throws Exception {
        final int maxInFlight = 32;
        final int logins = maxInFlight + 10;
        try (TestSessionServer sessions = new TestSessionServer();
             Rig rig = Rig.online(sessions, GENEROUS, maxInFlight)) {
            sessions.hold();
            final List<CompletableFuture<LoginSuccessPacket>> results = new ArrayList<>();
            final List<EncryptedLoginClient> clients = new ArrayList<>();
            for (int i = 0; i < logins; i++) {
                final EncryptedLoginClient client = rig.client();
                clients.add(client);
                final String name = "storm" + i;
                final CompletableFuture<LoginSuccessPacket> result = new CompletableFuture<>();
                Thread.startVirtualThread(() -> {
                    try {
                        result.complete(client.login(name));
                    } catch (Throwable t) {
                        result.completeExceptionally(t);
                    }
                });
                results.add(result);
            }
            awaitUntil(() -> sessions.inFlight() >= maxInFlight, "the first queries are held");
            Thread.sleep(500);
            assertEquals(maxInFlight, sessions.inFlight(), "the queries past the limit wait for a slot");

            sessions.release();
            for (CompletableFuture<LoginSuccessPacket> result : results) result.get(30, TimeUnit.SECONDS);
            assertEquals(maxInFlight, sessions.peakInFlight());
            assertEquals(logins, sessions.queries().size());
            for (EncryptedLoginClient client : clients) client.close();
        }
    }

    @Test
    public void aKeyWithoutARequestIsAProtocolError() throws Exception {
        try (TestSessionServer sessions = new TestSessionServer();
             Rig rig = Rig.online(sessions, GENEROUS, 32);
             EncryptedLoginClient client = rig.client()) {
            client.handshake(rig.port());
            client.sendKey(new byte[128], new byte[128]);
            assertEquals("synaxis:protocol-error", EncryptedLoginClient.loginDisconnectKey(client.read(ConnectionState.LOGIN)));
            assertEquals(List.of(), sessions.queries());
        }
    }

    @Test
    public void aWrongChallengeIsAProtocolError() throws Exception {
        try (TestSessionServer sessions = new TestSessionServer();
             Rig rig = Rig.online(sessions, GENEROUS, 32);
             EncryptedLoginClient client = rig.client()) {
            client.handshakeAndStart("Forger");
            final EncryptionRequestPacket request = client.readEncryptionRequest();
            final PublicKey key = KeyUtils.publicRSAKeyFrom(request.publicKey());
            final byte[] wrong = request.verifyToken().clone();
            wrong[0] ^= 1;
            client.sendKey(EncryptedLoginClient.encrypt(key, new byte[16]), EncryptedLoginClient.encrypt(key, wrong));
            assertEquals("synaxis:protocol-error", EncryptedLoginClient.loginDisconnectKey(client.read(ConnectionState.LOGIN)));
            assertEquals(List.of(), sessions.queries());
        }
    }

    @Test
    public void aSecretThatIsNotAnAes128KeyIsAProtocolError() throws Exception {
        try (TestSessionServer sessions = new TestSessionServer();
             Rig rig = Rig.online(sessions, GENEROUS, 32);
             EncryptedLoginClient client = rig.client()) {
            client.handshakeAndStart("Short");
            final EncryptionRequestPacket request = client.readEncryptionRequest();
            final PublicKey key = KeyUtils.publicRSAKeyFrom(request.publicKey());
            client.sendKey(EncryptedLoginClient.encrypt(key, new byte[15]), EncryptedLoginClient.encrypt(key, request.verifyToken()));
            assertEquals("synaxis:protocol-error", EncryptedLoginClient.loginDisconnectKey(client.read(ConnectionState.LOGIN)));
            assertEquals(List.of(), sessions.queries());
        }
    }

    @Test
    public void aSecondKeyIsAProtocolError() throws Exception {
        final AtomicInteger preLogins = new AtomicInteger();
        try (TestSessionServer sessions = new TestSessionServer();
             Rig rig = Rig.online(sessions, GENEROUS, 32);
             EncryptedLoginClient client = rig.client()) {
            rig.process().eventHandler().addListener(AsyncPlayerPreLoginEvent.class, _ -> preLogins.incrementAndGet());
            sessions.hold();
            client.handshakeAndStart("Twice");
            final EncryptionRequestPacket request = client.readEncryptionRequest();
            client.answer(request);
            // Behind the first key, so already under the cipher, as a peer that sends it twice has it
            final PublicKey key = KeyUtils.publicRSAKeyFrom(request.publicKey());
            client.sendKey(EncryptedLoginClient.encrypt(key, new byte[16]), EncryptedLoginClient.encrypt(key, request.verifyToken()));
            assertEquals("synaxis:protocol-error", EncryptedLoginClient.loginDisconnectKey(client.readLoginOutcome()));
            sessions.release();
            Thread.sleep(300);
            assertEquals(0, preLogins.get(), "the verification of the first key is dropped with the connection");
        }
    }

    @Test
    public void aKeyToAnOfflineServerIsAProtocolError() throws Exception {
        try (Rig rig = Rig.start(new Auth.Offline());
             EncryptedLoginClient client = rig.client()) {
            client.handshake(rig.port());
            client.sendKey(new byte[128], new byte[128]);
            assertEquals("synaxis:protocol-error", EncryptedLoginClient.loginDisconnectKey(client.read(ConnectionState.LOGIN)));
        }
    }

    @Test
    public void aPeerThatLeavesDuringVerificationIsForgotten() throws Exception {
        final AtomicInteger preLogins = new AtomicInteger();
        try (TestSessionServer sessions = new TestSessionServer();
             Rig rig = Rig.online(sessions, GENEROUS, 32)) {
            rig.process().eventHandler().addListener(AsyncPlayerPreLoginEvent.class, _ -> preLogins.incrementAndGet());
            sessions.script(TestSessionServer.Answer.okAfter(Duration.ofSeconds(2)));
            try (EncryptedLoginClient client = rig.client()) {
                client.handshakeAndStart("Leaver");
                client.answer(client.readEncryptionRequest());
                awaitUntil(() -> sessions.queries().size() == 1, "the query is under way");
            }
            Thread.sleep(3_000);
            assertEquals(0, preLogins.get(), "the answer reached a connection that is gone");
            assertEquals(0, rig.process().connection().getConfigPlayers().size());
        }
    }

    @Test
    public void aPeerThatLeavesDuringAnUnavailableQueryIsNotRetried() throws Exception {
        try (TestSessionServer sessions = new TestSessionServer();
             Rig rig = Rig.online(sessions, GENEROUS, 32)) {
            sessions.script(new TestSessionServer.Answer(503, Duration.ofMillis(500)), TestSessionServer.Answer.ok());
            try (EncryptedLoginClient client = rig.client()) {
                client.handshakeAndStart("Quitter");
                client.answer(client.readEncryptionRequest());
                awaitUntil(() -> sessions.queries().size() == 1, "the query is under way");
            }
            // The 503 comes back to a connection that is gone; the back-off would be over by now
            Thread.sleep(2_500);
            assertEquals(1, sessions.queries().size(), "a peer that left was asked for once more");
        }
    }

    @Test
    public void aPeerThatLeavesWhileQueuedForASlotAsksNothing() throws Exception {
        try (TestSessionServer sessions = new TestSessionServer();
             Rig rig = Rig.online(sessions, GENEROUS, 1);
             EncryptedLoginClient first = rig.client()) {
            sessions.hold();
            final CompletableFuture<LoginSuccessPacket> firstResult = new CompletableFuture<>();
            Thread.startVirtualThread(() -> {
                try {
                    firstResult.complete(first.login("Holder"));
                } catch (Throwable t) {
                    firstResult.completeExceptionally(t);
                }
            });
            awaitUntil(() -> sessions.inFlight() == 1, "the only slot is taken");
            final SessionClient sessionClient = rig.process().connection().sessionClient();
            assertNotNull(sessionClient);
            try (EncryptedLoginClient queued = rig.client()) {
                queued.handshakeAndStart("Queued");
                queued.answer(queued.readEncryptionRequest());
                awaitUntil(() -> sessionClient.queuedQueries() == 1, "the server queued the peer for the slot");
            }
            // The queued peer's socket is given back to the gate once the server has dropped the connection
            awaitUntil(() -> rig.gate().connections() == 1, "the server saw the queued peer leave");
            sessions.release();
            firstResult.get(10, TimeUnit.SECONDS);
            Thread.sleep(500);
            assertEquals(List.of("Holder"), sessions.queries().stream().map(TestSessionServer.Query::username).toList(),
                    "a peer that left while queued took the slot to the session server");
        }
    }

    /**
     * L3 seats go to authenticated logins ({@code docs/TEST.md} TC-PROD-580): two logins that stop
     * at the encryption request hold none of the two seats, and a third one that answers it gets in.
     */
    @Test
    public void loginsThatStopAtTheEncryptionRequestHoldNoSeat() throws Exception {
        try (TestSessionServer sessions = new TestSessionServer();
             Rig rig = Rig.start(new Auth.Online(sessions.sessionServer(GENEROUS, 32)), base().maxPlayers(2));
             EncryptedLoginClient first = rig.client();
             EncryptedLoginClient second = rig.client();
             EncryptedLoginClient third = rig.client()) {
            first.handshakeAndStart("Squatter0");
            first.readEncryptionRequest();
            second.handshakeAndStart("Squatter1");
            second.readEncryptionRequest();

            assertInstanceOf(LoginSuccessPacket.class, third.login("Player"));
            assertEquals(1, rig.gate().seats(), "only the authenticated login holds a seat");
        }
    }

    /**
     * A login that finds every seat taken once it is authenticated is refused there, before the
     * login event, with a {@code synaxis:shard-full} it reads through the cipher; a transfer the
     * same as a login ({@code docs/TEST.md} TC-PROD-581).
     */
    @Test
    public void anAuthenticatedTransferThatFindsTheSeatsTakenIsRefusedEncrypted() throws Exception {
        final AtomicInteger preLogins = new AtomicInteger();
        try (TestSessionServer sessions = new TestSessionServer();
             Rig rig = Rig.start(new Auth.Online(sessions.sessionServer(GENEROUS, 32)),
                     base().maxPlayers(1).acceptTransfers(true));
             EncryptedLoginClient late = rig.client();
             EncryptedLoginClient seated = rig.client()) {
            rig.process().eventHandler().addListener(AsyncPlayerPreLoginEvent.class, _ -> preLogins.incrementAndGet());
            late.handshake(rig.port(), ClientHandshakePacket.Intent.TRANSFER);
            late.send(ConnectionState.LOGIN, new ClientLoginStartPacket("Late", UUID.randomUUID()));
            final EncryptionRequestPacket request = late.readEncryptionRequest();

            assertInstanceOf(LoginSuccessPacket.class, seated.login("Seated"), "the seat the transfer did not hold");
            late.answer(request);
            assertEquals("synaxis:shard-full", EncryptedLoginClient.loginDisconnectKey(late.readLoginOutcome()));
            assertFalse(contains(late.rawIncoming(), "synaxis:shard-full"), "the refusal was sent in plaintext");
            assertTrue(late.closedWithoutAnotherPacket());
            assertEquals(2, sessions.queries().size(), "both were verified");
            assertEquals(1, preLogins.get(), "the login event ran for the refused transfer");
            assertEquals(1, rig.gate().seats());
            assertEquals(1, rig.gate().rejected(ConnectionGate.Reason.FULL));
        }
    }

    /** Encrypted offline mode takes the seat once the key is in ({@code docs/TEST.md} TC-PROD-582). */
    @Test
    public void anEncryptedOfflineLoginTakesItsSeatOnceItsKeyIsIn() throws Exception {
        try (Rig rig = Rig.start(new Auth.Offline(true), base().maxPlayers(1));
             EncryptedLoginClient waiting = rig.client();
             EncryptedLoginClient keyed = rig.client()) {
            waiting.handshakeAndStart("Waiting");
            final EncryptionRequestPacket request = waiting.readEncryptionRequest();
            assertEquals(0, rig.gate().seats(), "a login waiting for its key holds a seat");

            assertInstanceOf(LoginSuccessPacket.class, keyed.login("Keyed"));
            assertEquals(1, rig.gate().seats());
            waiting.answer(request);
            assertEquals("synaxis:shard-full", EncryptedLoginClient.loginDisconnectKey(waiting.readLoginOutcome()));
        }
    }

    @Test
    public void encryptedOfflineModeEncryptsWithoutAskingForASession() throws Exception {
        try (Rig rig = Rig.start(new Auth.Offline(true));
             EncryptedLoginClient client = rig.client()) {
            client.handshakeAndStart("Local");
            final EncryptionRequestPacket request = client.readEncryptionRequest();
            assertFalse(request.shouldAuthenticate(), "the client must not join a session");
            client.answer(request);
            final LoginSuccessPacket success = assertInstanceOf(LoginSuccessPacket.class, client.readLoginOutcome());
            assertEquals(GameProfile.offlineUuid("Local"), success.gameProfile().uuid());
            assertFalse(contains(client.rawIncoming(), "Local"), "the login success crossed the wire in plaintext");
        }
    }

    /** {@code API-PROD-015}: the sums behind synaxis_packets_total, synaxis_bytes_total and synaxis_cipher_cpu_seconds_total. */
    @Test
    public void theTrafficAndTheCipherTimeOfAnEncryptedLoginAreCounted() throws Exception {
        try (Rig rig = Rig.start(new Auth.Offline(true));
             EncryptedLoginClient client = rig.client()) {
            final TrafficCounters traffic = rig.process().connection().traffic();
            assertEquals(0, traffic.packetsIn() + traffic.packetsOut() + traffic.bytesIn() + traffic.bytesOut());
            client.handshakeAndStart("Counted");
            client.answer(client.readEncryptionRequest());
            assertInstanceOf(LoginSuccessPacket.class, client.readLoginOutcome());
            // handshake, login start, key; the success went out after the key was read
            assertEquals(3, traffic.packetsIn(), "packets read off the socket");
            assertEquals(0, traffic.decryptNanos(), "nothing encrypted came in yet");
            client.send(ConnectionState.LOGIN, new ClientLoginAcknowledgedPacket());
            awaitUntil(() -> traffic.packetsIn() == 4, "the acknowledgement is read");
            assertTrue(traffic.decryptNanos() > 0, "the acknowledgement was decrypted");
            assertTrue(traffic.packetsOut() >= 2, "encryption request and login success: " + traffic.packetsOut());
            assertTrue(traffic.bytesIn() > 256, "the key alone is two 128 byte RSA blocks: " + traffic.bytesIn());
            assertTrue(traffic.bytesOut() > 162, "the request carries a 162 byte public key: " + traffic.bytesOut());
            assertTrue(traffic.encryptNanos() > 0, "the login success was encrypted");
        }
    }

    /** {@code API-PROD-015}: synaxis_auth_hasjoined_total, _retries_total and _seconds. */
    @Test
    public void theFinalAnswerTheRetryAndEveryQueryTimeAreCounted() throws Exception {
        try (TestSessionServer sessions = new TestSessionServer();
             Rig rig = Rig.online(sessions, GENEROUS, 32);
             EncryptedLoginClient client = rig.client()) {
            final SessionClient sessionClient = rig.process().connection().sessionClient();
            assertNotNull(sessionClient);
            final List<Long> queries = new CopyOnWriteArrayList<>();
            sessionClient.observeQueries(queries::add);
            sessions.script(TestSessionServer.Answer.status(503), TestSessionServer.Answer.ok());

            client.login("Retried");
            assertArrayEquals(new long[]{1, 0, 0}, sessionClient.outcomes(), "one login, counted once as verified");
            assertEquals(1, sessionClient.retries(), "the unavailable answer was retried once");
            assertEquals(2, queries.size(), "both queries were timed");
            assertTrue(queries.stream().allMatch(nanos -> nanos > 0));
        }
    }

    private static boolean contains(byte[] haystack, String needle) {
        final byte[] bytes = needle.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i + bytes.length <= haystack.length; i++) {
            for (int j = 0; j < bytes.length; j++) if (haystack[i + j] != bytes[j]) continue outer;
            return true;
        }
        return false;
    }

    /** L1 and L2 of the gate are not under test here: one address opens every connection. */
    private static ServerConfig.Builder base() {
        return ServerConfig.builder().insideTest(true).connectionGateTrustedNetworks("127.0.0.0/8");
    }

    private static void awaitUntil(BooleanSupplier condition, String what) throws InterruptedException {
        for (int i = 0; i < 400 && !condition.getAsBoolean(); i++) Thread.sleep(25);
        assertTrue(condition.getAsBoolean(), what);
    }

    /** A process whose socket server listens on a free loopback port, no tick running. */
    private record Rig(ServerProcess process) implements AutoCloseable {

        static Rig online(TestSessionServer sessions, Duration timeout, int maxInFlight) throws IOException {
            return start(new Auth.Online(sessions.sessionServer(timeout, maxInFlight)));
        }

        static Rig start(Auth auth) throws IOException {
            return start(auth, base());
        }

        static Rig start(Auth auth, ServerConfig.Builder config) throws IOException {
            final ServerProcess process = ServerProcess.of(auth, config.build());
            final Server server = process.server();
            server.init(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            server.start();
            return new Rig(process);
        }

        int port() {
            return process.server().getPort();
        }

        ConnectionGate gate() {
            return process.connection().gate();
        }

        EncryptedLoginClient client() throws IOException {
            return new EncryptedLoginClient(port(), process.registries());
        }

        @Override
        public void close() {
            process.stop();
        }
    }
}
