package dev.gameengine.registry;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import dev.gameengine.ServerConfig;
import dev.gameengine.ServerProcess;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
