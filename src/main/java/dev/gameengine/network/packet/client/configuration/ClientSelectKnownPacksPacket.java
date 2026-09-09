package dev.gameengine.network.packet.client.configuration;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;
import dev.gameengine.network.packet.server.configuration.SelectKnownPacksPacket;
import dev.gameengine.utils.validate.Check;

import java.util.List;

public record ClientSelectKnownPacksPacket(
        List<SelectKnownPacksPacket.Entry> entries
) implements ClientPacket.Configuration {
    private static final int MAX_ENTRIES = 64;

    public static final NetworkBuffer.Type<ClientSelectKnownPacksPacket> SERIALIZER = NetworkBufferTemplate.template(
            SelectKnownPacksPacket.Entry.SERIALIZER.list(MAX_ENTRIES), ClientSelectKnownPacksPacket::entries,
            ClientSelectKnownPacksPacket::new);

    public ClientSelectKnownPacksPacket {
        Check.argCondition(entries.size() > MAX_ENTRIES, "Too many known packs: {0} > {1}", entries.size(), MAX_ENTRIES);
        entries = List.copyOf(entries);
    }
}
