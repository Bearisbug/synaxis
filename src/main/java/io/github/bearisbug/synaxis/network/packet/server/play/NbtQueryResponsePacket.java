package io.github.bearisbug.synaxis.network.packet.server.play;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BYTE;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.NBT_COMPOUND;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record NbtQueryResponsePacket(int transactionId, CompoundBinaryTag data) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<NbtQueryResponsePacket> SERIALIZER = new NetworkBuffer.Type<>() {
        @Override
        public void write(NetworkBuffer buffer, NbtQueryResponsePacket value) {
            buffer.write(VAR_INT, value.transactionId);
            if (value.data != null) {
                buffer.write(NBT_COMPOUND, value.data);
            } else {
                // TAG_End
                buffer.write(BYTE, (byte) 0x00);
            }
        }

        @Override
        public NbtQueryResponsePacket read(NetworkBuffer buffer) {
            return new NbtQueryResponsePacket(buffer.read(VAR_INT), buffer.read(NBT_COMPOUND));
        }
    };
}
