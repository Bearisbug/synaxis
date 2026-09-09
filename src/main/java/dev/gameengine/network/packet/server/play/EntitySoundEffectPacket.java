package dev.gameengine.network.packet.server.play;

import net.kyori.adventure.sound.Sound;
import dev.gameengine.adventure.AdventurePacketConvertor;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.packet.server.ServerPacket;
import dev.gameengine.sound.SoundEvent;

import static dev.gameengine.network.NetworkBuffer.FLOAT;
import static dev.gameengine.network.NetworkBuffer.LONG;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record EntitySoundEffectPacket(
        SoundEvent soundEvent,
        Sound.Source source,
        int entityId,
        float volume,
        float pitch,
        long seed
) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<EntitySoundEffectPacket> SERIALIZER = new NetworkBuffer.Type<>() {
        @Override
        public void write(NetworkBuffer buffer, EntitySoundEffectPacket value) {
            buffer.write(SoundEvent.NETWORK_TYPE, value.soundEvent);
            buffer.write(VAR_INT, AdventurePacketConvertor.getSoundSourceValue(value.source));
            buffer.write(VAR_INT, value.entityId);
            buffer.write(FLOAT, value.volume);
            buffer.write(FLOAT, value.pitch);
            buffer.write(LONG, value.seed);
        }

        @Override
        public EntitySoundEffectPacket read(NetworkBuffer buffer) {
            return new EntitySoundEffectPacket(buffer.read(SoundEvent.NETWORK_TYPE),
                    buffer.read(NetworkBuffer.Enum(Sound.Source.class)),
                    buffer.read(VAR_INT),
                    buffer.read(FLOAT),
                    buffer.read(FLOAT),
                    buffer.read(LONG));
        }
    };
}
