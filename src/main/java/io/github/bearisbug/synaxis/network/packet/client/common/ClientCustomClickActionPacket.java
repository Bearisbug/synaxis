package io.github.bearisbug.synaxis.network.packet.client.common;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTag;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

public record ClientCustomClickActionPacket(Key key, BinaryTag payload) implements ClientPacket.Configuration, ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientCustomClickActionPacket> SERIALIZER = NetworkBufferTemplate.template(
            NetworkBuffer.KEY, ClientCustomClickActionPacket::key,
            NetworkBuffer.UNTRUSTED_NBT.lengthPrefixed(65536), ClientCustomClickActionPacket::payload,
            ClientCustomClickActionPacket::new);
}
