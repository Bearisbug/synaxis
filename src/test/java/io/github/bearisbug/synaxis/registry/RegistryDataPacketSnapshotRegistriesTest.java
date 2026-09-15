package io.github.bearisbug.synaxis.registry;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.Result;
import io.github.bearisbug.synaxis.codec.Transcoder;
import io.github.bearisbug.synaxis.network.packet.server.configuration.RegistryDataPacket;
import io.github.bearisbug.synaxis.testing.RegistriesTest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The registry data packet is built outside the registry lock, so it has to be built entirely from
 * the snapshot it took: an entry removed halfway through shifts every following id down.
 */
@RegistriesTest
public class RegistryDataPacketSnapshotRegistriesTest {

    private static CompoundBinaryTag value(String name) {
        return CompoundBinaryTag.builder().putString("name", name).build();
    }

    @Test
    public void entriesKeepTheirOwnKeyWhileTheRegistryIsMutated(Registries registries) {
        final AtomicReference<Runnable> onEncode = new AtomicReference<>(() -> {
        });
        // Encoding an entry is the point where another thread gets to mutate the registry
        final Codec<BinaryTag> codec = new Codec<>() {
            @Override
            public <D> Result<D> encode(Transcoder<D> coder, @Nullable BinaryTag value) {
                onEncode.get().run();
                return Codec.NBT.encode(coder, value);
            }

            @Override
            public <D> Result<BinaryTag> decode(Transcoder<D> coder, D value) {
                return Codec.NBT.decode(coder, value);
            }
        };

        final DynamicRegistry<BinaryTag> registry = DynamicRegistry.create(Key.key("synaxis:test"), codec);
        registry.register("synaxis:a", value("a"));
        registry.register("synaxis:b", value("b"));
        registry.register("synaxis:c", value("c"));

        onEncode.set(() -> {
            onEncode.set(() -> {
            });
            registry.remove(Key.key("synaxis:a"));
        });

        final RegistryDataPacket packet = (RegistryDataPacket) registry.registryDataPacket(registries, false);
        assertEquals(List.of("synaxis:a", "synaxis:b", "synaxis:c"),
                packet.entries().stream().map(RegistryDataPacket.Entry::id).toList());
        for (RegistryDataPacket.Entry entry : packet.entries()) {
            assertEquals(value(entry.id().substring("synaxis:".length())), entry.data());
        }
    }
}
