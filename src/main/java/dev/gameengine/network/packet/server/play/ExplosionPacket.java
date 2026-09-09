package dev.gameengine.network.packet.server.play;

import dev.gameengine.coordinate.Point;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;
import dev.gameengine.particle.Particle;
import dev.gameengine.sound.SoundEvent;
import dev.gameengine.utils.WeightedList;
import org.jetbrains.annotations.Nullable;

import static dev.gameengine.network.NetworkBuffer.VECTOR3D;

public record ExplosionPacket(
        Point center, float radius, int blockCount,
        @Nullable Point playerKnockback,
        Particle particle, SoundEvent sound,
        WeightedList<BlockParticleInfo> blockParticles
) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<ExplosionPacket> SERIALIZER = NetworkBufferTemplate.template(
            VECTOR3D, ExplosionPacket::center,
            NetworkBuffer.FLOAT, ExplosionPacket::radius,
            NetworkBuffer.INT, ExplosionPacket::blockCount,
            VECTOR3D.optional(), ExplosionPacket::playerKnockback,
            Particle.NETWORK_TYPE, ExplosionPacket::particle,
            SoundEvent.NETWORK_TYPE, ExplosionPacket::sound,
            weightedList(BlockParticleInfo.SERIALIZER), ExplosionPacket::blockParticles,
            ExplosionPacket::new);

    private static <T> NetworkBuffer.Type<WeightedList<T>> weightedList(NetworkBuffer.Type<T> valueType) {
        final NetworkBuffer.Type<WeightedList.Entry<T>> entry = NetworkBufferTemplate.template(
                valueType, WeightedList.Entry::value,
                NetworkBuffer.VAR_INT, WeightedList.Entry::weight,
                WeightedList.Entry::new);
        return entry.list().transform(WeightedList::new, WeightedList::entries);
    }

    public record BlockParticleInfo(Particle particle, float scaling, float speed) {
        public static final NetworkBuffer.Type<BlockParticleInfo> SERIALIZER = NetworkBufferTemplate.template(
                Particle.NETWORK_TYPE, BlockParticleInfo::particle,
                NetworkBuffer.FLOAT, BlockParticleInfo::scaling,
                NetworkBuffer.FLOAT, BlockParticleInfo::speed,
                BlockParticleInfo::new);
    }
}
