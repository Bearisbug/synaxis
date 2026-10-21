package io.github.bearisbug.synaxis.network.packet.server.configuration;

import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import java.util.List;

public record SelectKnownPacksPacket(
        List<Entry> entries
) implements ServerPacket.Configuration {
    public static final Entry MINECRAFT_CORE = new Entry("minecraft", "core", MinecraftServer.VERSION_NAME);

    public static final NetworkBuffer.Type<SelectKnownPacksPacket> SERIALIZER = NetworkBufferTemplate.template(
            Entry.SERIALIZER.list(), SelectKnownPacksPacket::entries,
            SelectKnownPacksPacket::new);

    public SelectKnownPacksPacket {
        entries = List.copyOf(entries);
    }

    public record Entry(
            String namespace,
            String id,
            String version
    ) {
        public static final NetworkBuffer.Type<Entry> SERIALIZER = NetworkBufferTemplate.template(
                NetworkBuffer.STRING, Entry::namespace,
                NetworkBuffer.STRING, Entry::id,
                NetworkBuffer.STRING, Entry::version,
                Entry::new);
    }
}
