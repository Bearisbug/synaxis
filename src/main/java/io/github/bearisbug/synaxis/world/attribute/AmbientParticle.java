package io.github.bearisbug.synaxis.world.attribute;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;
import io.github.bearisbug.synaxis.particle.Particle;

public record AmbientParticle(Particle particle, float probability) {
    public static final Codec<AmbientParticle> CODEC = StructCodec.struct(
            "particle", Particle.CODEC, AmbientParticle::particle,
            "probability", Codec.FLOAT, AmbientParticle::probability,
            AmbientParticle::new);
}
