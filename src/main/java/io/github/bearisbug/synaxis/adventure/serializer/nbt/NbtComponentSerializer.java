package io.github.bearisbug.synaxis.adventure.serializer.nbt;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.ComponentSerializer;
import io.github.bearisbug.synaxis.registry.Registries;

import java.util.Objects;

public sealed interface NbtComponentSerializer extends ComponentSerializer<Component, Component, BinaryTag> permits NbtComponentSerializerImpl {
    /**
     * Returns a serializer that resolves registry references against the vanilla registries, so it does not see the
     * entries a server registered itself. Prefer {@link #nbt(Registries)} when the registries of a process are at hand.
     *
     * @return the serializer
     */
    static NbtComponentSerializer nbt() {
        return NbtComponentSerializerImpl.INSTANCE;
    }

    /**
     * Returns a serializer bound to the given registries.
     *
     * @param registries the registries used to resolve registry references
     * @return the serializer
     */
    static NbtComponentSerializer nbt(Registries registries) {
        Objects.requireNonNull(registries, "registries");
        return new NbtComponentSerializerImpl(registries);
    }
}
