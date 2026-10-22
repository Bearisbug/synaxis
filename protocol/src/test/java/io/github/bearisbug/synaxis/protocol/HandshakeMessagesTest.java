package io.github.bearisbug.synaxis.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The {@code synaxis:handshake} envelope of {@code API-M2-002}. The version axis itself lives in
 * {@link ProtocolSchema}: both ends carry the same value or the connection is rejected, so the
 * decoder only has to refuse a version that cannot exist.
 */
class HandshakeMessagesTest {

    @Test
    void theHelloPairRoundTrips() {
        final C2SHello hello = new C2SHello(7, ProtocolSchema.SCHEMA_VERSION);
        final MsgBuf request = MsgBuf.writing();
        hello.write(request);
        assertEquals(hello, HandshakeMessages.read(MsgBuf.reading(request.toByteArray())));

        final S2CHello response = new S2CHello(ProtocolSchema.SCHEMA_VERSION, 20, 1234);
        final MsgBuf reply = MsgBuf.writing();
        response.write(reply);
        assertEquals(response, HandshakeMessages.read(MsgBuf.reading(reply.toByteArray())));
    }

    @Test
    void anUnknownMsgIdIsReportedWithoutBeingMalformed() {
        final MsgBuf buf = MsgBuf.writing();
        buf.writeVarInt(0x7E);
        final UnknownMessageException thrown =
                assertThrows(UnknownMessageException.class, () -> HandshakeMessages.read(MsgBuf.reading(buf.toByteArray())));
        assertEquals(0x7E, thrown.msgId());
    }

    @Test
    void aVersionBelowOneIsMalformed() {
        assertThrows(MalformedMessageException.class, () -> new C2SHello(1, 0));
        assertThrows(MalformedMessageException.class, () -> new C2SHello(0, 1));
        assertThrows(MalformedMessageException.class, () -> new S2CHello(0, 20, 0));
    }

    @Test
    void aTickRateOutsideAnUnsignedByteIsMalformed() {
        assertThrows(MalformedMessageException.class, () -> new S2CHello(1, 256, 0));
        assertThrows(MalformedMessageException.class, () -> new S2CHello(1, 20, -1));
    }

    @Test
    void aTruncatedHelloIsMalformed() {
        final MsgBuf buf = MsgBuf.writing();
        buf.writeVarInt(C2SHello.MSG_ID);
        buf.writeVarInt(1);
        assertThrows(MalformedMessageException.class, () -> HandshakeMessages.read(MsgBuf.reading(buf.toByteArray())));
    }
}
