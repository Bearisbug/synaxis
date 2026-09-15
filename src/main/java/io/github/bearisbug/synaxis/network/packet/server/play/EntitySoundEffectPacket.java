package io.github.bearisbug.synaxis.network.packet.server.play;

import net.kyori.adventure.sound.Sound;
import io.github.bearisbug.synaxis.adventure.AdventurePacketConvertor;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.sound.SoundEvent;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.FLOAT;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.LONG;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

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
