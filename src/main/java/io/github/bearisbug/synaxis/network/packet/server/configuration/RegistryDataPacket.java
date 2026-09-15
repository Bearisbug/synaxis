package io.github.bearisbug.synaxis.network.packet.server.configuration;

import net.kyori.adventure.nbt.BinaryTag;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.NBT;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.STRING;

public record RegistryDataPacket(
        String registryId,
        List<Entry> entries
) implements ServerPacket.Configuration {
    public static final NetworkBuffer.Type<RegistryDataPacket> SERIALIZER = NetworkBufferTemplate.template(
            STRING, RegistryDataPacket::registryId,
            Entry.SERIALIZER.list(Integer.MAX_VALUE), RegistryDataPacket::entries,
            RegistryDataPacket::new);

    public RegistryDataPacket {
        entries = List.copyOf(entries);
    }

    public record Entry(
            String id,
            @Nullable BinaryTag data
    ) {
        public static final NetworkBuffer.Type<Entry> SERIALIZER = NetworkBufferTemplate.template(
                STRING, Entry::id, NBT.optional(), Entry::data, Entry::new);
    }
}
