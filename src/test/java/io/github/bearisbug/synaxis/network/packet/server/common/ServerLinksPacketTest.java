package io.github.bearisbug.synaxis.network.packet.server.common;

import io.github.bearisbug.synaxis.utils.Either;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The two constructors of a link entry, one per kind of label. */
public class ServerLinksPacketTest {

    @Test
    public void aKnownTypeIsTheLeftLabelAndAComponentTheRightOne() {
        assertEquals(Either.left(ServerLinksPacket.KnownLinkType.WEBSITE),
                new ServerLinksPacket.Entry(ServerLinksPacket.KnownLinkType.WEBSITE, "https://example.com").linkType());
        assertEquals(Either.right(Component.text("x")),
                new ServerLinksPacket.Entry(Component.text("x"), "https://example.com").linkType());
    }

    @Test
    public void aNullTypeIsRefusedUnderItsName() {
        assertEquals("type", assertThrows(NullPointerException.class,
                () -> new ServerLinksPacket.Entry((ServerLinksPacket.KnownLinkType) null, "https://example.com")).getMessage());
        assertEquals("type", assertThrows(NullPointerException.class,
                () -> new ServerLinksPacket.Entry((Component) null, "https://example.com")).getMessage());
    }
}
