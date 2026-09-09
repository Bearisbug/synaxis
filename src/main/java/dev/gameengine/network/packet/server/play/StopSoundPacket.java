package dev.gameengine.network.packet.server.play;

import net.kyori.adventure.sound.Sound;
import dev.gameengine.adventure.AdventurePacketConvertor;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.packet.server.ServerPacket;
import org.jetbrains.annotations.Nullable;

import static dev.gameengine.network.NetworkBuffer.BYTE;
import static dev.gameengine.network.NetworkBuffer.STRING;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record StopSoundPacket(byte flags, @Nullable Sound.Source source,
                              @Nullable String sound) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<StopSoundPacket> SERIALIZER = new NetworkBuffer.Type<>() {
        @Override
        public void write(NetworkBuffer buffer, StopSoundPacket value) {
            buffer.write(BYTE, value.flags());
            if (value.flags == 3 || value.flags == 1) {
                assert value.source != null;
                buffer.write(VAR_INT, AdventurePacketConvertor.getSoundSourceValue(value.source));
            }
            if (value.flags == 2 || value.flags == 3) {
                assert value.sound != null;
                buffer.write(STRING, value.sound);
            }
        }

        @Override
        public StopSoundPacket read(NetworkBuffer buffer) {
            byte flags = buffer.read(BYTE);
            var source = flags == 3 || flags == 1 ? buffer.read(NetworkBuffer.Enum(Sound.Source.class)) : null;
            var sound = flags == 2 || flags == 3 ? buffer.read(STRING) : null;
            return new StopSoundPacket(flags, source, sound);
        }
    };
}
