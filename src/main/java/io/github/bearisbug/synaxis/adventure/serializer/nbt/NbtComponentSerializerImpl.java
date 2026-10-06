package io.github.bearisbug.synaxis.adventure.serializer.nbt;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.adventure.AdventureRegistries;
import io.github.bearisbug.synaxis.adventure.ComponentCodecs;
import io.github.bearisbug.synaxis.codec.Transcoder;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.registry.RegistryTranscoder;
import org.jetbrains.annotations.Nullable;

record NbtComponentSerializerImpl(@Nullable Registries registries) implements NbtComponentSerializer {
    static final NbtComponentSerializer INSTANCE = new NbtComponentSerializerImpl(null);

    @Override
    public Component deserialize(BinaryTag input) {
        final Transcoder<BinaryTag> coder = new RegistryTranscoder<>(Transcoder.NBT, context());
        return ComponentCodecs.COMPONENT.decode(coder, input).orElseThrow();
    }

    @Override
    public BinaryTag serialize(Component component) {
        final Transcoder<BinaryTag> coder = new RegistryTranscoder<>(Transcoder.NBT, context());
        return ComponentCodecs.COMPONENT.encode(coder, component).orElseThrow();
    }

    private Registries context() {
        return registries != null ? registries : AdventureRegistries.vanilla();
    }
}
