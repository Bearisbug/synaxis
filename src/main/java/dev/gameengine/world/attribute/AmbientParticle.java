package dev.gameengine.world.attribute;

import dev.gameengine.codec.Codec;
import dev.gameengine.codec.StructCodec;
import dev.gameengine.particle.Particle;

public record AmbientParticle(Particle particle, float probability) {
    public static final Codec<AmbientParticle> CODEC = StructCodec.struct(
            "particle", Particle.CODEC, AmbientParticle::particle,
            "probability", Codec.FLOAT, AmbientParticle::probability,
            AmbientParticle::new);
}
