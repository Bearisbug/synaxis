package net.minestom.server.registry;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.ServerFlag;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertThrows;

@EnvTest
class OpaqueRegistryFrozenIntegrationTest {
    @Test
    void writeAfterStartIsRejected(Env env) {
        final DynamicRegistry<BinaryTag> enchantment = env.process().opaque(BuiltinRegistries.ENCHANTMENT);
        env.process().start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));

        // Registries never freeze inside the test JVM, so the production value has to be restored for this assertion.
        final boolean insideTest = ServerFlag.INSIDE_TEST;
        ServerFlag.INSIDE_TEST = false;
        try {
            assertThrows(IllegalStateException.class,
                    () -> enchantment.register("game:test", CompoundBinaryTag.empty()));
        } finally {
            ServerFlag.INSIDE_TEST = insideTest;
        }
    }
}
