package io.github.bearisbug.synaxis.client;

import io.github.bearisbug.synaxis.protocol.C2SHello;
import io.github.bearisbug.synaxis.protocol.HandshakeMessage;
import io.github.bearisbug.synaxis.protocol.HandshakeMessages;
import io.github.bearisbug.synaxis.protocol.MalformedMessageException;
import io.github.bearisbug.synaxis.protocol.MsgBuf;
import io.github.bearisbug.synaxis.protocol.ProtocolSchema;
import io.github.bearisbug.synaxis.protocol.S2CHello;
import io.github.bearisbug.synaxis.protocol.UnknownMessageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client half of {@code API-M2-002}: one {@code C2S_HELLO} per connection, and what to make of
 * the answer.
 * <p>
 * <b>Both phases are supported and the client does not choose between them.</b> Which phase the
 * negotiation happens in is the server's launch argument {@code synaxis.app.handshake-phase}
 * ({@code configuration} by default, {@code ADR-M2-010}). Fabric's networking API sends a
 * configuration phase payload to a server that never sends {@code minecraft:register}, and the real
 * client regression's R2 checks that this form is accepted; {@code login} is the server's fallback.
 * So this class answers whichever way it is spoken to: {@link #beginConfiguration(Sink)} opens the
 * exchange when the server waits for one, {@link #answerLoginQuery(byte[])} answers when the
 * server asks first, and switching the server between the two changes nothing here.
 * <p>
 * <b>The state has to survive the login to configuration boundary.</b> A server running the login
 * form still routes {@code synaxis:handshake} during configuration, so a hello sent there after
 * one was already answered during login is the second hello of that connection — which §8 makes a
 * protocol error. {@link #reset()} therefore belongs to the start of a connection attempt, not to
 * the start of a phase.
 * <p>
 * <b>A refusal is shown, not worked around.</b> The client does not reconnect: the server's
 * disconnect reason is a {@code translate} key, the mod ships the text for it, and the vanilla
 * disconnect screen is the whole of the client's part.
 */
public final class ClientHandshake {

    private static final Logger LOGGER = LoggerFactory.getLogger("synaxis");

    /**
     * What {@code mod_version} carries. §9 keeps it out of every decision — it reaches the server's
     * log and its ledger and nothing else — so one number per wire schema is all it has to be.
     */
    public static final int MOD_VERSION = 1;

    /**
     * How far this connection's handshake has got.
     */
    public enum State {
        /** Nothing has been said yet. */
        IDLE,
        /** This client sent its hello and is waiting for the answer (configuration phase only). */
        SENT,
        /** Both halves were exchanged. */
        VERIFIED
    }

    /**
     * Where the hello goes. The production answer is one call to Fabric's configuration sender.
     */
    @FunctionalInterface
    public interface Sink {

        /**
         * Sends the hello on {@code synaxis:handshake}.
         *
         * @param hello the message
         */
        void send(C2SHello hello);
    }

    private State state = State.IDLE;
    private S2CHello serverHello;

    /**
     * Opens the configuration phase exchange, which is what
     * {@code ClientConfigurationConnectionEvents.START} is for — the one event of the four whose
     * contract allows sending, and the one the engine's opening {@code minecraft:brand} fires.
     * <p>
     * Sends nothing if this connection has already handshaken, in either phase.
     *
     * @param sink where to send it
     */
    public void beginConfiguration(Sink sink) {
        if (state != State.IDLE) {
            LOGGER.warn("Not sending a second C2S_HELLO on this connection: state={}", state);
            return;
        }
        state = State.SENT;
        sink.send(new C2SHello(MOD_VERSION, ProtocolSchema.SCHEMA_VERSION));
    }

    /**
     * Takes the server's answer in the configuration phase. Getting one at all means the schemas
     * matched — the server disconnects rather than answering when they do not.
     *
     * @param hello the answer
     */
    public void onServerHello(S2CHello hello) {
        if (state != State.SENT) {
            LOGGER.warn("Ignoring an S2C_HELLO this connection did not ask for: state={}", state);
            return;
        }
        state = State.VERIFIED;
        serverHello = hello;
        LOGGER.info("Handshake accepted: schema_version={} tick_rate={} server_tick={}",
                hello.schemaVersion(), hello.tickRate(), hello.serverTick());
    }

    /**
     * Answers the login phase form of the same negotiation, where the server asks first and
     * {@code S2C_HELLO} is the question.
     * <p>
     * A schema the server names and this build does not speak is still answered: silence is how a
     * client without the mod answers, and the server reads it as
     * {@code synaxis:client-mod-required} — the wrong thing to show a player whose modpack is
     * merely out of date.
     *
     * @param request the request payload
     * @return the answer payload, or {@code null} to leave the request unanswered — this build
     *         cannot read it, or this connection has already handshaken
     */
    public byte[] answerLoginQuery(byte[] request) {
        if (state != State.IDLE) {
            LOGGER.warn("Leaving a second login query unanswered on this connection: state={}", state);
            return null;
        }
        final HandshakeMessage message;
        try {
            message = HandshakeMessages.read(MsgBuf.reading(request));
        } catch (UnknownMessageException | MalformedMessageException e) {
            LOGGER.warn("Leaving a login query on {} unanswered: {}",
                    HandshakePayload.TYPE.id(), e.getMessage());
            return null;
        }
        if (!(message instanceof S2CHello hello)) {
            LOGGER.warn("Leaving a login query unanswered: a server asks with S2C_HELLO, not with msg_id=0x{}",
                    Integer.toHexString(message.msgId()));
            return null;
        }
        if (hello.schemaVersion() != ProtocolSchema.SCHEMA_VERSION) {
            LOGGER.warn("This server speaks schema_version={} and this mod speaks {}; answering anyway "
                            + "so the refusal names the version rather than the mod",
                    hello.schemaVersion(), ProtocolSchema.SCHEMA_VERSION);
        }
        state = State.VERIFIED;
        serverHello = hello;
        return new C2SHello(MOD_VERSION, ProtocolSchema.SCHEMA_VERSION).encode();
    }

    /**
     * Forgets this connection. Belongs to the start of a connection attempt, before either phase.
     */
    public void reset() {
        state = State.IDLE;
        serverHello = null;
    }

    /**
     * @return how far this connection's handshake has got
     */
    public State state() {
        return state;
    }

    /**
     * @return what the server said about itself, or {@code null} if it has not said it yet
     */
    public S2CHello serverHello() {
        return serverHello;
    }
}
