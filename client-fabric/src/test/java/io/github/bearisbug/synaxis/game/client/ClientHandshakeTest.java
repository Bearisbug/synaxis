package io.github.bearisbug.synaxis.game.client;

import io.github.bearisbug.synaxis.game.protocol.C2SHello;
import io.github.bearisbug.synaxis.game.protocol.HandshakeMessage;
import io.github.bearisbug.synaxis.game.protocol.HandshakeMessages;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import io.github.bearisbug.synaxis.game.protocol.ProtocolSchema;
import io.github.bearisbug.synaxis.game.protocol.S2CHello;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The client half of {@code API-M2-002}, driven without a game: the sink is a list and the login
 * phase is a byte array in and a byte array out, so everything the handshake decides — when it
 * speaks, what counts as done, what it refuses to do twice — is asserted here rather than on a
 * screen.
 */
class ClientHandshakeTest {

    private final List<C2SHello> sent = new ArrayList<>();
    private final ClientHandshake handshake = new ClientHandshake();
    private final ClientHandshake.Sink sink = sent::add;

    private static final C2SHello OURS =
            new C2SHello(ClientHandshake.MOD_VERSION, ProtocolSchema.SCHEMA_VERSION);

    @Test
    void theConfigurationPhaseOpensWithOneHelloCarryingThisBuildsSchema() {
        handshake.beginConfiguration(sink);

        assertEquals(List.of(OURS), sent);
        assertEquals(ClientHandshake.State.SENT, handshake.state());
    }

    /**
     * §8: a connection sends one {@code C2S_HELLO} and the second is a protocol error, so not
     * sending it is the client's side of that rule.
     */
    @Test
    void aSecondConfigurationStartOnTheSameConnectionSendsNothing() {
        handshake.beginConfiguration(sink);
        handshake.beginConfiguration(sink);

        assertEquals(List.of(OURS), sent);
    }

    @Test
    void theServersAnswerIsWhatCompletesTheHandshake() {
        handshake.beginConfiguration(sink);
        handshake.onServerHello(new S2CHello(ProtocolSchema.SCHEMA_VERSION, 20, 41));

        assertEquals(ClientHandshake.State.VERIFIED, handshake.state());
        assertEquals(20, handshake.serverHello().tickRate());
        assertEquals(41, handshake.serverHello().serverTick());
    }

    @Test
    void anAnswerToAHelloThisConnectionNeverSentIsIgnored() {
        handshake.onServerHello(new S2CHello(ProtocolSchema.SCHEMA_VERSION, 20, 0));

        assertEquals(ClientHandshake.State.IDLE, handshake.state());
        assertNull(handshake.serverHello());
    }

    @Test
    void theLoginPhaseAnswersTheServersQuestionWithTheSameHello() {
        final byte[] answer = handshake.answerLoginQuery(encode(new S2CHello(ProtocolSchema.SCHEMA_VERSION, 20, 7)));

        assertEquals(OURS, decode(answer));
        assertEquals(ClientHandshake.State.VERIFIED, handshake.state());
        assertEquals(7, handshake.serverHello().serverTick());
    }

    /**
     * The one interaction between the two phases, and the reason the state survives the login to
     * configuration boundary: a server running the login form still routes this channel in the
     * configuration phase, so a hello sent there would be the second one and would be kicked as
     * {@code synaxis:protocol-error}.
     */
    @Test
    void aHandshakeDoneDuringLoginStopsTheConfigurationPhaseFromSendingASecondOne() {
        handshake.answerLoginQuery(encode(new S2CHello(ProtocolSchema.SCHEMA_VERSION, 20, 0)));
        handshake.beginConfiguration(sink);

        assertEquals(List.of(), sent);
    }

    @Test
    void aSecondLoginQueryOnTheSameConnectionGoesUnanswered() {
        final byte[] request = encode(new S2CHello(ProtocolSchema.SCHEMA_VERSION, 20, 0));
        handshake.answerLoginQuery(request);

        assertNull(handshake.answerLoginQuery(request));
    }

    /**
     * Silence is how a client without the mod answers, and the server reads it as
     * {@code synaxis:client-mod-required}. Answering a server that speaks another schema is what
     * gets the player the reason that is actually true — {@code synaxis:protocol-version-mismatch}.
     */
    @Test
    void aServerOnAnotherSchemaIsStillAnsweredSoItCanNameTheRightReason() {
        final byte[] answer = handshake.answerLoginQuery(encode(new S2CHello(ProtocolSchema.SCHEMA_VERSION + 1, 20, 0)));

        assertNotNull(answer);
        assertEquals(OURS, decode(answer));
    }

    @Test
    void aQueryThisBuildCannotReadGoesUnanswered() {
        assertNull(handshake.answerLoginQuery(new byte[] {0x7F}));
        assertEquals(ClientHandshake.State.IDLE, handshake.state());
    }

    @Test
    void aClientHelloIsNotSomethingTheServerAsksWith() {
        assertNull(handshake.answerLoginQuery(encode(OURS)));
        assertEquals(ClientHandshake.State.IDLE, handshake.state());
    }

    @Test
    void aNewConnectionStartsFromScratch() {
        handshake.beginConfiguration(sink);
        handshake.reset();
        handshake.beginConfiguration(sink);

        assertEquals(List.of(OURS, OURS), sent);
    }

    private static byte[] encode(HandshakeMessage message) {
        final MsgBuf buf = MsgBuf.writing();
        message.write(buf);
        return buf.toByteArray();
    }

    private static HandshakeMessage decode(byte[] bytes) {
        return HandshakeMessages.read(MsgBuf.reading(bytes));
    }
}
