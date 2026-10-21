package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.particle.Particle;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BOOLEAN;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.DOUBLE;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.FLOAT;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.INT;

public record ParticlePacket(Particle particle, boolean overrideLimiter, boolean longDistance, double x, double y, double z,
                             float offsetX, float offsetY, float offsetZ, float maxSpeed,
                             int particleCount) implements ServerPacket.Play {
    public ParticlePacket(Particle particle, double x, double y, double z, float offsetX, float offsetY, float offsetZ, float maxSpeed, int particleCount) {
        this(particle, false, false, x, y, z, offsetX, offsetY, offsetZ, maxSpeed, particleCount);
    }

    public ParticlePacket(Particle particle, boolean overrideLimiter, boolean longDistance, Point position, Point offset, float maxSpeed, int particleCount) {
        this(particle, overrideLimiter, longDistance, position.x(), position.y(), position.z(), (float) offset.x(), (float) offset.y(), (float) offset.z(), maxSpeed, particleCount);
    }

    public ParticlePacket(Particle particle, Point position, Point offset, float maxSpeed, int particleCount) {
        this(particle, false, false, position, offset, maxSpeed, particleCount);
    }

    public static final NetworkBuffer.Type<ParticlePacket> SERIALIZER = NetworkBufferTemplate.template(
            BOOLEAN, ParticlePacket::overrideLimiter,
            BOOLEAN, ParticlePacket::longDistance,
            DOUBLE, ParticlePacket::x,
            DOUBLE, ParticlePacket::y,
            DOUBLE, ParticlePacket::z,
            FLOAT, ParticlePacket::offsetX,
            FLOAT, ParticlePacket::offsetY,
            FLOAT, ParticlePacket::offsetZ,
            FLOAT, ParticlePacket::maxSpeed,
            INT, ParticlePacket::particleCount,
            Particle.NETWORK_TYPE, ParticlePacket::particle,
            (overrideLimiter, longDistance, x, y, z, offsetX, offsetY, offsetZ, maxSpeed, particleCount, particle) ->
                    new ParticlePacket(particle, overrideLimiter, longDistance, x, y, z, offsetX, offsetY, offsetZ, maxSpeed, particleCount));
}
