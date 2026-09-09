package dev.gameengine.adventure.serializer.nbt;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.text.Component;
import dev.gameengine.MinecraftServer;
import dev.gameengine.codec.Codec;
import dev.gameengine.codec.Transcoder;
import dev.gameengine.registry.RegistryTranscoder;

final class NbtComponentSerializerImpl implements NbtComponentSerializer {
    static final NbtComponentSerializer INSTANCE = new NbtComponentSerializerImpl();

    @Override
    public Component deserialize(BinaryTag input) {
        final Transcoder<BinaryTag> coder = new RegistryTranscoder<>(Transcoder.NBT, MinecraftServer.getRegistries());
        return Codec.COMPONENT.decode(coder, input).orElseThrow();
    }

    @Override
    public BinaryTag serialize(Component component) {
        final Transcoder<BinaryTag> coder = new RegistryTranscoder<>(Transcoder.NBT, MinecraftServer.getRegistries());
        return Codec.COMPONENT.encode(coder, component).orElseThrow();
    }

}
