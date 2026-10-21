package io.github.bearisbug.synaxis.command.builder.arguments.minecraft.registry;

import io.github.bearisbug.synaxis.command.ArgumentParserType;
import io.github.bearisbug.synaxis.particle.Particle;
import org.jetbrains.annotations.Nullable;

/**
 * Represents an argument giving a {@link Particle}.
 */
public class ArgumentParticle extends ArgumentRegistry<Particle> {

    public ArgumentParticle(String id) {
        super(id);
    }

    @Override
    public ArgumentParserType parser() {
        return ArgumentParserType.PARTICLE;
    }

    @Override
    public @Nullable Particle getRegistry(String value) {
        return Particle.fromKey(value);
    }

    @Override
    public String toString() {
        return String.format("Particle<%s>", getId());
    }
}
