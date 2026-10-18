package io.github.bearisbug.synaxis.registry;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Registries freeze when the owning process starts. The test fixture keeps them writable
 * ({@code insideTest}), so this test runs a process with the production configuration instead of
 * flipping a JVM wide switch, and needs no resource lock.
 */
class OpaqueRegistryFrozenTest {
    @Test
    void writeAfterStartIsRejected() {
        final ServerProcess process = ServerProcess.of(ServerConfig.defaults());
        final DynamicRegistry<BinaryTag> enchantment = process.opaque(BuiltinRegistries.ENCHANTMENT);
        process.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        try {
            assertThrows(IllegalStateException.class,
                    () -> enchantment.register("game:test", CompoundBinaryTag.empty()));
        } finally {
            process.stop();
        }
    }

    /** Removing an entry and creating or removing a tag are writes too, and are refused the same way. */
    @Test
    void removeAndTagWritesAfterStartAreRejected() {
        final ServerProcess process = ServerProcess.of(ServerConfig.defaults());
        final DynamicRegistry<BinaryTag> enchantment = process.opaque(BuiltinRegistries.ENCHANTMENT);
        final TagKey<BinaryTag> existing = Objects.requireNonNull(enchantment.tags().iterator().next().key());
        process.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        try {
            final String message = assertThrows(IllegalStateException.class,
                    () -> enchantment.remove(Key.key("minecraft:sharpness"))).getMessage();
            assertTrue(message.contains("minecraft:enchantment") && message.contains("before start()"), message);
            assertThrows(IllegalStateException.class, () -> enchantment.getOrCreateTag(TagKey.unsafeOf("game:arena")));
            assertThrows(IllegalStateException.class, () -> enchantment.removeTag(existing));
            assertSame(enchantment.getTag(existing), enchantment.getOrCreateTag(existing));
        } finally {
            process.stop();
        }
    }
}
