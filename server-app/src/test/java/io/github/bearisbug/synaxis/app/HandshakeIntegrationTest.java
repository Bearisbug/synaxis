package io.github.bearisbug.synaxis.app;

import ch.qos.logback.classic.Level;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerPreLoginEvent;
import io.github.bearisbug.synaxis.event.player.PlayerPluginMessageEvent;
import io.github.bearisbug.synaxis.game.protocol.C2SHello;
import io.github.bearisbug.synaxis.game.protocol.HandshakeMessage;
import io.github.bearisbug.synaxis.game.protocol.HandshakeMessages;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import io.github.bearisbug.synaxis.game.protocol.ProtocolSchema;
import io.github.bearisbug.synaxis.game.protocol.S2CHello;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.DisconnectPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.PluginMessagePacket;
import io.github.bearisbug.synaxis.network.packet.server.configuration.FinishConfigurationPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginDisconnectPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginPluginRequestPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.plugin.Admission;
import io.github.bearisbug.synaxis.plugin.AdmissionRequest;
import io.github.bearisbug.synaxis.plugin.DisconnectKeys;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four exits of the client mod handshake and the hand off between the two threads that run it
 * (REQ-M2-008 and the second half of REQ-M2-024; {@code API-M2-002}, {@code API-M2-011}).
 * <p>
 * The tick thread is played by the test thread — it calls {@link HandshakeGate#receive} exactly as
 * the channel router does when {@code PlayerPluginMessageEvent} is dispatched from inside a tick —
 * and the configuration virtual thread by a real virtual thread parked in
 * {@link HandshakeGate#await}. Both arrival orders are covered: a hello that lands while the waiter
 * is parked, and one that lands before anybody waits.
 * <p>
 * Connections are built here rather than taken from {@code Env#createConnection}: the assertions
 * are about the disconnect reason on the wire and about a login plugin request being answered
 * synchronously, and neither is reachable through a connection that only records packets.
 */
@EnvTest
class HandshakeIntegrationTest {

    /**
     * The budget of the tests that have to spend it. Three seconds is the contract (§17); a test
     * that waited it out would spend that on every build, and what it proves — the wait is bounded
     * and its exit is a kick — does not depend on the number.
     */
    private static final Duration SHORT_BUDGET = Duration.ofMillis(300);

    /**
     * Stands in for "longer than anything in this test can reach".
     */
    private static final Duration UNREACHABLE_BUDGET = Duration.ofSeconds(30);

    @TempDir
    Path workingDirectory;

    /** The ledger of {@link #host}, started so that {@link #onlyLedgerLine} can drain it to disk. */
    private @Nullable EventLedger ledger;

    /**
     * Stops the writer thread of the ledger before JUnit deletes {@link #workingDirectory}. Left
     * running, it creates {@code logs/} and the day's file whenever it gets round to the first line,
     * which on a loaded run can be after the test has returned: into the middle of the deletion
     * ({@code DirectoryNotEmptyException}) or after it (the temp directory comes back).
     */
    @AfterEach
    void closeLedger() {
        if (ledger != null) ledger.close();
    }

    /**
     * A connection in the configuration phase that keeps what was written to it, and optionally
     * answers a login plugin request on the spot — which is what makes the login phase tests
     * deterministic: the answer is in before {@code sendPluginRequest} has even returned its
     * future.
     */
    private static final class Wire extends PlayerConnection {

        private final List<ServerPacket> sent = new CopyOnWriteArrayList<>();
        private final CountDownLatch finished = new CountDownLatch(1);
        private boolean answersLogin;
        private byte @Nullable [] loginAnswer;
        private volatile @Nullable Thread late;

        Wire(ServerProcess process, ConnectionState state) {
            super(process);
            setClientState(state);
            setServerState(state);
        }

        void answerLoginWith(byte @Nullable [] answer) {
            this.answersLogin = true;
            this.loginAnswer = answer;
        }

        /**
         * Holds whatever the given thread writes until {@link FinishConfigurationPacket} has been
         * written: a tick thread that loses the processor right after its {@code complete} and gets
         * it back only once the configuration thread it woke has finished the phase.
         */
        void holdUntilFinished(Thread writer) {
            this.late = writer;
        }

        @Override
        public void sendPacket(SendablePacket packet) {
            final ServerPacket server = packet instanceof ServerPacket direct ? direct
                    : SendablePacket.extractServerPacket(getServerState(), packet);
            if (Thread.currentThread() == late) awaitFinished();
            sent.add(server);
            if (server instanceof FinishConfigurationPacket) finished.countDown();
            if (!(server instanceof LoginPluginRequestPacket request) || !answersLogin) return;
            try {
                loginPluginMessageProcessor().handleResponse(request.messageId(), loginAnswer);
            } catch (Exception e) {
                throw new IllegalStateException("could not answer the login plugin request", e);
            }
        }

        private void awaitFinished() {
            try {
                if (!finished.await(10, TimeUnit.SECONDS))
                    throw new IllegalStateException("the configuration phase never finished");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("localhost", 25565);
        }

        <T extends ServerPacket> List<T> of(Class<T> type) {
            return sent.stream().filter(type::isInstance).map(type::cast).toList();
        }
    }

    private HandshakeGate gate(Env env, HandshakeGate.Phase phase, Duration budget) {
        final HandshakeGate gate = new HandshakeGate(phase, budget,
                new EventLedger(workingDirectory.resolve("logs"), "test", EventLedger.DEFAULT_MAX_BYTES_PER_DAY));
        gate.attach(env.process().eventHandler());
        return gate;
    }

    private static Wire wire(Env env) {
        return new Wire(env.process(), ConnectionState.CONFIGURATION);
    }

    /**
     * A connection still in the login state, which is where the login plugin request of the
     * fallback phase is sent and answered.
     */
    private static Wire loginWire(Env env) {
        return new Wire(env.process(), ConnectionState.LOGIN);
    }

    private static Player player(Env env, Wire wire) {
        final Player player = env.process().connection()
                .createPlayer(wire, new GameProfile(UUID.randomUUID(), "tester"));
        assertNotNull(player);
        return player;
    }

    private static byte[] hello(int modVersion, int schemaVersion) {
        final MsgBuf buf = MsgBuf.writing();
        new C2SHello(modVersion, schemaVersion).write(buf);
        return buf.toByteArray();
    }

    private static CompletableFuture<HandshakeGate.HelloResult> awaitOnVirtualThread(HandshakeGate gate,
                                                                                     Player player) {
        final CompletableFuture<HandshakeGate.HelloResult> awaited = new CompletableFuture<>();
        Thread.startVirtualThread(() -> awaited.complete(gate.await(player)));
        return awaited;
    }

    private static S2CHello onlyAnswer(Wire wire) {
        final List<PluginMessagePacket> payloads = wire.of(PluginMessagePacket.class);
        assertEquals(1, payloads.size(), "expected exactly one answer, got " + payloads);
        assertEquals(HandshakeGate.CHANNEL, payloads.getFirst().channel());
        final HandshakeMessage message = HandshakeMessages.read(MsgBuf.reading(payloads.getFirst().data()));
        return assertInstanceOf(S2CHello.class, message);
    }

    private static void assertKickedWith(String reasonKey, Wire wire) {
        final List<Component> reasons = Stream.concat(
                wire.of(DisconnectPacket.class).stream().map(DisconnectPacket::message),
                wire.of(LoginDisconnectPacket.class).stream().map(LoginDisconnectPacket::kickMessage)).toList();
        assertEquals(1, reasons.size(), "expected exactly one disconnect, got " + reasons);
        assertEquals(reasonKey, assertInstanceOf(TranslatableComponent.class, reasons.getFirst()).key());
        assertFalse(wire.isOnline());
    }

    @Test
    void aMatchingHelloIsAnsweredAndWakesTheWaitingConfigurationThread(Env env) throws Exception {
        final Wire wire = wire(env);
        final Player player = player(env, wire);
        final HandshakeGate gate = gate(env, HandshakeGate.Phase.CONFIGURATION, HandshakeGate.BUDGET);
        gate.require();

        final CompletableFuture<HandshakeGate.HelloResult> awaited = awaitOnVirtualThread(gate, player);
        gate.receive(player, hello(7, ProtocolSchema.SCHEMA_VERSION));

        assertEquals(new HandshakeGate.HelloResult(7, ProtocolSchema.SCHEMA_VERSION),
                awaited.get(5, TimeUnit.SECONDS));
        final S2CHello answer = onlyAnswer(wire);
        assertEquals(ProtocolSchema.SCHEMA_VERSION, answer.schemaVersion());
        assertEquals(20, answer.tickRate());
        assertTrue(wire.isOnline());
    }

    @Test
    void aHelloThatBeatsTheWaiterIsStillHandedOver(Env env) throws Exception {
        final Wire wire = wire(env);
        final Player player = player(env, wire);
        final HandshakeGate gate = gate(env, HandshakeGate.Phase.CONFIGURATION, HandshakeGate.BUDGET);
        gate.require();

        gate.receive(player, hello(3, ProtocolSchema.SCHEMA_VERSION));

        assertEquals(new HandshakeGate.HelloResult(3, ProtocolSchema.SCHEMA_VERSION),
                awaitOnVirtualThread(gate, player).get(5, TimeUnit.SECONDS));
    }

    @Test
    void aMismatchedSchemaKicksWithProtocolVersionMismatch(Env env) throws Exception {
        final Wire wire = wire(env);
        final Player player = player(env, wire);
        final HandshakeGate gate = gate(env, HandshakeGate.Phase.CONFIGURATION, HandshakeGate.BUDGET);
        gate.require();

        final CompletableFuture<HandshakeGate.HelloResult> awaited = awaitOnVirtualThread(gate, player);
        gate.receive(player, hello(7, ProtocolSchema.SCHEMA_VERSION + 1));

        assertNull(awaited.get(5, TimeUnit.SECONDS), "a refused connection must not reach the admission call");
        assertEquals(List.of(), wire.of(PluginMessagePacket.class));
        assertKickedWith(DisconnectKeys.PROTOCOL_VERSION_MISMATCH, wire);
    }

    @Test
    void aSilentConnectionIsKickedOnceTheBudgetIsSpent(Env env) {
        final Wire wire = wire(env);
        final Player player = player(env, wire);
        final HandshakeGate gate = gate(env, HandshakeGate.Phase.CONFIGURATION, SHORT_BUDGET);
        gate.require();

        final long start = System.nanoTime();
        assertNull(gate.await(player));

        assertTrue(System.nanoTime() - start >= SHORT_BUDGET.toNanos(), "the wait has to be the whole budget");
        assertKickedWith(DisconnectKeys.CLIENT_MOD_REQUIRED, wire);
    }

    @Test
    void aHelloThatLosesToTheTimeoutChangesNothing(Env env) {
        final Wire wire = wire(env);
        final Player player = player(env, wire);
        final HandshakeGate gate = gate(env, HandshakeGate.Phase.CONFIGURATION, SHORT_BUDGET);
        gate.require();
        assertNull(gate.await(player));

        gate.receive(player, hello(7, ProtocolSchema.SCHEMA_VERSION));

        assertEquals(List.of(), wire.of(PluginMessagePacket.class),
                "the future is the only arbiter: a hello that lost to the timeout is not answered");
        assertKickedWith(DisconnectKeys.CLIENT_MOD_REQUIRED, wire);
    }

    @Test
    void aDisconnectDuringTheWaitWakesTheConfigurationThreadAtOnce(Env env) throws Exception {
        final Wire wire = wire(env);
        final Player player = player(env, wire);
        final HandshakeGate gate = gate(env, HandshakeGate.Phase.CONFIGURATION, UNREACHABLE_BUDGET);
        gate.require();

        final CompletableFuture<HandshakeGate.HelloResult> awaited = awaitOnVirtualThread(gate, player);
        wire.disconnect();

        assertNull(awaited.get(5, TimeUnit.SECONDS), "the waiter wakes on the disconnect, not on the budget");
    }

    @Test
    void aSecondHelloOnTheSameConnectionIsAProtocolError(Env env) throws Exception {
        final Wire wire = wire(env);
        final Player player = player(env, wire);
        final HandshakeGate gate = gate(env, HandshakeGate.Phase.CONFIGURATION, HandshakeGate.BUDGET);
        gate.require();
        final CompletableFuture<HandshakeGate.HelloResult> awaited = awaitOnVirtualThread(gate, player);
        gate.receive(player, hello(7, ProtocolSchema.SCHEMA_VERSION));
        assertNotNull(awaited.get(5, TimeUnit.SECONDS));

        try (LogCapture logs = LogCapture.of(HandshakeGate.class)) {
            gate.receive(player, hello(7, ProtocolSchema.SCHEMA_VERSION));

            assertEquals(1, logs.messagesAt(Level.WARN).size(), logs.messagesAt(Level.WARN).toString());
        }
        assertKickedWith("synaxis:protocol-error", wire);
    }

    @Test
    void anUnknownMessageIdIsDroppedAndTheConnectionStays(Env env) {
        final Wire wire = wire(env);
        final Player player = player(env, wire);
        final HandshakeGate gate = gate(env, HandshakeGate.Phase.CONFIGURATION, HandshakeGate.BUDGET);
        gate.require();
        final MsgBuf unknown = MsgBuf.writing();
        unknown.writeVarInt(0x7E);

        try (LogCapture logs = LogCapture.of(HandshakeGate.class)) {
            gate.receive(player, unknown.toByteArray());

            assertEquals(1, logs.messagesAt(Level.WARN).size(), logs.messagesAt(Level.WARN).toString());
        }
        assertTrue(wire.isOnline(), "an unknown msg_id is dropped, not punished");
    }

    @Test
    void aMalformedPayloadIsAProtocolError(Env env) {
        final Wire wire = wire(env);
        final Player player = player(env, wire);
        final HandshakeGate gate = gate(env, HandshakeGate.Phase.CONFIGURATION, HandshakeGate.BUDGET);
        gate.require();
        final MsgBuf truncated = MsgBuf.writing();
        truncated.writeVarInt(C2SHello.MSG_ID);

        gate.receive(player, truncated.toByteArray());

        assertKickedWith("synaxis:protocol-error", wire);
    }

    @Test
    void anAnswerSentUpstreamIsAProtocolError(Env env) {
        final Wire wire = wire(env);
        final Player player = player(env, wire);
        final HandshakeGate gate = gate(env, HandshakeGate.Phase.CONFIGURATION, HandshakeGate.BUDGET);
        gate.require();
        final MsgBuf answer = MsgBuf.writing();
        new S2CHello(ProtocolSchema.SCHEMA_VERSION, 20, 0).write(answer);

        gate.receive(player, answer.toByteArray());

        assertKickedWith("synaxis:protocol-error", wire);
    }

    @Test
    void theCoreModeDoesNotWaitButStillAnswersAHelloThatArrives(Env env) {
        final Wire wire = wire(env);
        final Player player = player(env, wire);
        final HandshakeGate gate = gate(env, HandshakeGate.Phase.CONFIGURATION, UNREACHABLE_BUDGET);

        final long start = System.nanoTime();
        assertEquals(HandshakeGate.NONE, gate.await(player), "a core mode connection is let in as it is");
        assertTrue(System.nanoTime() - start < Duration.ofSeconds(5).toNanos(), "nothing may be waited on here");

        gate.receive(player, hello(7, ProtocolSchema.SCHEMA_VERSION));

        assertEquals(ProtocolSchema.SCHEMA_VERSION, onlyAnswer(wire).schemaVersion());
        assertTrue(wire.isOnline());
    }

    @Test
    void theCoreModeStillRefusesAMismatchedSchema(Env env) {
        final Wire wire = wire(env);
        final Player player = player(env, wire);
        final HandshakeGate gate = gate(env, HandshakeGate.Phase.CONFIGURATION, UNREACHABLE_BUDGET);

        gate.receive(player, hello(7, ProtocolSchema.SCHEMA_VERSION + 1));

        assertKickedWith(DisconnectKeys.PROTOCOL_VERSION_MISMATCH, wire);
    }

    @Test
    void theLoginPhaseAsksForTheHelloAndLetsAModdedClientThrough(Env env) {
        final Wire wire = loginWire(env);
        final Player player = player(env, wire);
        wire.answerLoginWith(hello(7, ProtocolSchema.SCHEMA_VERSION));
        final HandshakeGate gate = gate(env, HandshakeGate.Phase.LOGIN, HandshakeGate.BUDGET);
        gate.require();

        preLogin(env, wire);

        final List<LoginPluginRequestPacket> requests = wire.of(LoginPluginRequestPacket.class);
        assertEquals(1, requests.size(), "expected exactly one request, got " + requests);
        assertEquals(HandshakeGate.CHANNEL, requests.getFirst().channel());
        assertEquals(new HandshakeGate.HelloResult(7, ProtocolSchema.SCHEMA_VERSION), gate.await(player));
        assertEquals(List.of(), wire.of(PluginMessagePacket.class), "the login plugin request carried S2C_HELLO");
        assertTrue(wire.isOnline());
    }

    @Test
    void theLoginPhaseKicksAClientThatDoesNotKnowTheChannel(Env env) {
        final Wire wire = loginWire(env);
        wire.answerLoginWith(null);
        final HandshakeGate gate = gate(env, HandshakeGate.Phase.LOGIN, HandshakeGate.BUDGET);
        gate.require();

        preLogin(env, wire);

        assertKickedWith(DisconnectKeys.CLIENT_MOD_REQUIRED, wire);
    }

    @Test
    void theLoginPhaseKicksAMismatchedSchema(Env env) {
        final Wire wire = loginWire(env);
        wire.answerLoginWith(hello(7, ProtocolSchema.SCHEMA_VERSION + 1));
        final HandshakeGate gate = gate(env, HandshakeGate.Phase.LOGIN, HandshakeGate.BUDGET);
        gate.require();

        preLogin(env, wire);

        assertKickedWith(DisconnectKeys.PROTOCOL_VERSION_MISMATCH, wire);
    }

    @Test
    void theLoginPhaseKicksAClientThatNeverAnswers(Env env) {
        final Wire wire = loginWire(env);
        final HandshakeGate gate = gate(env, HandshakeGate.Phase.LOGIN, SHORT_BUDGET);
        gate.require();

        preLogin(env, wire);

        assertKickedWith(DisconnectKeys.CLIENT_MOD_REQUIRED, wire);
    }

    @Test
    void aConnectionThatLeavesBeforeItIsAPlayerIsReclaimed(Env env) {
        final Wire wire = loginWire(env);
        wire.answerLoginWith(hello(7, ProtocolSchema.SCHEMA_VERSION));
        final HandshakeGate gate = gate(env, HandshakeGate.Phase.LOGIN, HandshakeGate.BUDGET);
        gate.require();
        preLogin(env, wire);
        assertEquals(1, gate.openSessions(), "the login phase hands its result over through a session");

        // Between LoginSuccess and LoginAcknowledged the connection has no player yet, so closing
        // here fires no PlayerDisconnectEvent. A client can open and drop such a connection as
        // often as it likes, so whatever the gate holds for it has to come back without one.
        wire.disconnect();
        env.tick();

        assertEquals(0, gate.openSessions(), "a connection that is gone may not be held on to");
    }

    @Test
    void theConfigurationPhaseAsksNothingDuringLogin(Env env) {
        final Wire wire = loginWire(env);
        final HandshakeGate gate = gate(env, HandshakeGate.Phase.CONFIGURATION, SHORT_BUDGET);
        gate.require();

        preLogin(env, wire);

        assertEquals(List.of(), wire.of(LoginPluginRequestPacket.class));
        assertTrue(wire.isOnline(), "the configuration phase decides nothing while the client logs in");
    }

    @Test
    void aVerifiedConnectionReachesTheAdmissionProviderWithWhatItReported(Env env) {
        final Wire wire = wire(env);
        final Player player = player(env, wire);
        final Instance match = env.createFlatInstance();
        final PluginHost host = host(env);
        final List<AdmissionRequest> seen = new CopyOnWriteArrayList<>();
        host.registerHostAdmission(request -> {
            seen.add(request);
            return new Admission.Accept(match);
        });
        host.requireHandshake();

        final Throwable failure = configure(env, player, hello(7, ProtocolSchema.SCHEMA_VERSION));

        assertNull(failure, "the configuration phase must not throw");
        assertEquals(List.of(new AdmissionRequest(wire.connectionId(), player.getUsername(),
                7, ProtocolSchema.SCHEMA_VERSION, player.getUuid(),
                ((InetSocketAddress) wire.getRemoteAddress()).getAddress())), seen);
        assertTrue(wire.isOnline());
    }

    @Test
    void theAnswerIsQueuedBeforeFinishConfigurationHoweverLateTheTickThreadIs(Env env) {
        final Wire wire = wire(env);
        final Player player = player(env, wire);
        final Instance match = env.createFlatInstance();
        final PluginHost host = host(env);
        host.registerHostAdmission(_ -> new Admission.Accept(match));
        host.requireHandshake();
        // This thread interprets the hello, and whatever it writes waits for the configuration thread
        // it wakes to finish the phase: the schedule of GitHub Actions run 36459937261, made certain
        wire.holdUntilFinished(Thread.currentThread());

        assertNull(configure(env, player, hello(7, ProtocolSchema.SCHEMA_VERSION)),
                "the configuration phase must not throw");

        // The write thread encodes the queue in this order and moves the connection to the play
        // state as it writes FinishConfigurationPacket. An answer behind it goes out as a play
        // packet, which the client mod has no codec for and drops: the client stays SENT.
        final List<ServerPacket> order = wire.sent.stream()
                .filter(packet -> packet instanceof FinishConfigurationPacket
                        || (packet instanceof PluginMessagePacket message && message.channel().equals(HandshakeGate.CHANNEL)))
                .toList();
        assertEquals(List.of("PluginMessagePacket", "FinishConfigurationPacket"),
                order.stream().map(packet -> packet.getClass().getSimpleName()).toList(),
                "S2C_HELLO has to be queued before FinishConfigurationPacket");
        final HandshakeMessage answer = HandshakeMessages.read(MsgBuf.reading(((PluginMessagePacket) order.getFirst()).data()));
        assertEquals(ProtocolSchema.SCHEMA_VERSION, assertInstanceOf(S2CHello.class, answer).schemaVersion());
        final String verified = onlyLedgerLine("client_verified");
        assertTrue(verified.contains("\"mod_version\":7"), verified);
    }

    @Test
    void aRejectedConnectionIsToldTheMatchIsUnavailableRatherThanAnInternalError(Env env) {
        final Wire wire = wire(env);
        final Player player = player(env, wire);
        final PluginHost host = host(env);
        host.registerHostAdmission(_ -> new Admission.Reject(DisconnectKeys.MATCH_UNAVAILABLE));
        host.requireHandshake();

        final Throwable failure = configure(env, player, hello(7, ProtocolSchema.SCHEMA_VERSION));

        // The engine demands a spawning instance right after this event and throws a
        // NullPointerException at a connection that has none, which is what would turn the designed
        // refusal into synaxis:internal-error (§26 phase 4).
        assertNull(failure, "a refused connection must be kicked, not left to the requireNonNull");
        assertKickedWith(DisconnectKeys.MATCH_UNAVAILABLE, wire);
        // §20 registers client_rejected with both of these, and the reason alone does not say
        // which client it was: this one handshook, so its schema version is known here too.
        final String rejection = onlyLedgerLine("client_rejected");
        assertTrue(rejection.contains("\"reason\":\"match-unavailable\""), rejection);
        assertTrue(rejection.contains("\"schema_version\":" + ProtocolSchema.SCHEMA_VERSION), rejection);
    }

    /**
     * @return the one ledger line of the given event, failing when there is not exactly one
     */
    private String onlyLedgerLine(String event) {
        // The writer thread owns the file; closing drains what is queued and forces it
        Objects.requireNonNull(ledger, "only a test that built a host has a ledger").close();
        try (var files = Files.list(workingDirectory.resolve("logs"))) {
            final List<String> lines = new ArrayList<>();
            for (Path file : files.toList()) lines.addAll(Files.readAllLines(file));
            final List<String> matching = lines.stream()
                    .filter(line -> line.contains("\"event\":\"" + event + "\"")).toList();
            assertEquals(1, matching.size(), "expected exactly one " + event + ", got " + lines);
            return matching.getFirst();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void theWaitingThreadIsTheOneThatKicksAMismatchedSchema(Env env) {
        final Wire wire = wire(env);
        final Player player = player(env, wire);
        final HandshakeGate gate = gate(env, HandshakeGate.Phase.CONFIGURATION, HandshakeGate.BUDGET);
        gate.require();

        gate.receive(player, hello(7, ProtocolSchema.SCHEMA_VERSION + 1));

        // The kick belongs to the thread that then returns without a spawning instance. Done from
        // the tick thread it races the engine's requireNonNull two statements after the event, and
        // the player gets synaxis:internal-error instead of the designed reason (§26 phase 4).
        assertTrue(wire.isOnline(), "the tick thread hands the verdict over, it does not act on it");
        assertNull(gate.await(player));
        assertKickedWith(DisconnectKeys.PROTOCOL_VERSION_MISMATCH, wire);
    }

    @Test
    void aHelloAtTheEndOfTheBudgetIsEitherAnsweredOrKicked(Env env) throws Exception {
        final Wire wire = wire(env);
        final Player player = player(env, wire);
        final HandshakeGate gate = gate(env, HandshakeGate.Phase.CONFIGURATION, SHORT_BUDGET);
        gate.require();

        final CompletableFuture<HandshakeGate.HelloResult> awaited = awaitOnVirtualThread(gate, player);
        // A little after the budget. Usually the waiter has claimed the future by then and the hello
        // changes nothing; when the waiter is held between its timeout and its claim, the hello takes
        // the future first, and in the gameplay mode its tick thread leaves the answer to the waiter.
        Thread.sleep(SHORT_BUDGET.toMillis() + 100);
        gate.receive(player, hello(7, ProtocolSchema.SCHEMA_VERSION));
        final HandshakeGate.HelloResult result = awaited.get(5, TimeUnit.SECONDS);

        // §16: exactly one of the two takes effect, never both and never neither. Neither would be a
        // connection left online with no answer and no spawning instance: synaxis:internal-error.
        if (result == null) {
            assertEquals(List.of(), wire.of(PluginMessagePacket.class));
            assertKickedWith(DisconnectKeys.CLIENT_MOD_REQUIRED, wire);
        } else {
            assertEquals(new HandshakeGate.HelloResult(7, ProtocolSchema.SCHEMA_VERSION), result);
            assertEquals(ProtocolSchema.SCHEMA_VERSION, onlyAnswer(wire).schemaVersion());
            assertTrue(wire.isOnline());
        }
    }

    private PluginHost host(Env env) {
        ledger = new EventLedger(workingDirectory.resolve("logs"), "test", EventLedger.DEFAULT_MAX_BYTES_PER_DAY);
        ledger.start();
        return new PluginHost(env.process(), workingDirectory.resolve("plugins"), ledger);
    }

    /**
     * Runs the configuration phase of one connection the way the engine does — on a virtual thread,
     * through the whole host wiring — and hands it the given hello over the channel router from
     * this thread, which is the tick thread's part.
     *
     * @param payload the handshake payload to deliver, {@code null} to send none at all
     * @return whatever the configuration phase threw, or {@code null} when it returned
     */
    private static @Nullable Throwable configure(Env env, Player player, byte @Nullable [] payload) {
        final CompletableFuture<@Nullable Throwable> outcome = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try {
                env.process().connection().doConfiguration(player, false);
                outcome.complete(null);
            } catch (Throwable throwable) {
                outcome.complete(throwable);
            }
        });
        if (payload != null)
            env.process().eventHandler().call(new PlayerPluginMessageEvent(player, HandshakeGate.CHANNEL, payload));
        try {
            return outcome.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void anUnknownPhaseIsARefusedLaunchArgument() {
        System.setProperty(HandshakeGate.PHASE_PROPERTY, "later");
        try {
            assertThrows(IllegalArgumentException.class, HandshakeGate::phase);
        } finally {
            System.clearProperty(HandshakeGate.PHASE_PROPERTY);
        }
    }

    @Test
    void theConfigurationPhaseIsTheDefault() {
        assertEquals(HandshakeGate.Phase.CONFIGURATION, HandshakeGate.phase(),
                "ADR-M2-010 is the default; the login phase is the fallback spike 4 may force");
    }

    @Test
    void theLoginPhaseLeavesAConnectionThatDropsBeforeItIsAsked(Env env) {
        final Wire wire = loginWire(env);
        final HandshakeGate gate = gate(env, HandshakeGate.Phase.LOGIN, HandshakeGate.BUDGET);
        gate.require();
        // What a disconnect between the gate's isOnline check and its request leaves behind: the
        // login plugin requests of the connection have ended, so sendPluginRequest throws.
        wire.loginPluginMessageProcessor().close();

        preLogin(env, wire);
        env.tick();

        assertEquals(List.of(), wire.of(LoginPluginRequestPacket.class));
        assertEquals(List.of(), wire.of(LoginDisconnectPacket.class), "there is no one left to refuse");
    }

    /**
     * Runs the pre login event the way {@code transitionLoginToConfig} does.
     */
    private static void preLogin(Env env, Wire wire) {
        // The engine asserts this event is called from a virtual thread, and the gate blocks on it.
        final Thread thread = Thread.startVirtualThread(() -> env.process().eventHandler()
                .call(new AsyncPlayerPreLoginEvent(wire, new GameProfile(UUID.randomUUID(), "tester"),
                        wire.loginPluginMessageProcessor())));
        try {
            thread.join(Duration.ofSeconds(10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        assertFalse(thread.isAlive(), "the pre login event never returned");
    }

    @Test
    void aRejectionKeyOfThePluginsOwnNamespaceIsLedgeredWhole(Env env) {
        final Wire wire = wire(env);
        final Player player = player(env, wire);
        final PluginHost host = host(env);
        host.registerHostAdmission(_ -> new Admission.Reject("rts:match-unavailable"));
        host.requireHandshake();

        assertNull(configure(env, player, hello(7, ProtocolSchema.SCHEMA_VERSION)));

        assertKickedWith("rts:match-unavailable", wire);
        // Only the synaxis namespace is dropped: this is not the host's match-unavailable (§20)
        final String rejection = onlyLedgerLine("client_rejected");
        assertTrue(rejection.contains("\"reason\":\"rts:match-unavailable\""), rejection);
    }
}
