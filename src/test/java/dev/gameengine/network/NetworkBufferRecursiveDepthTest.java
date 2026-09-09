package dev.gameengine.network;

import dev.gameengine.potion.CustomPotionEffect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A self-nesting {@link NetworkBuffer#Recursive} type is fed straight from the wire
 * (creative inventory action -> potion contents -> hidden effect), so its nesting depth
 * has to be bounded or the read thread dies on a StackOverflowError.
 */
class NetworkBufferRecursiveDepthTest {

    @Test
    void deeplyNestedRecursiveTypeIsRejected() {
        final int depth = 50_000;
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(depth * 6 + 16);
        for (int i = 0; i < depth; i++) writeSettings(buffer, true);
        writeSettings(buffer, false);

        assertThrows(RuntimeException.class,
                () -> buffer.read(CustomPotionEffect.Settings.NETWORK_TYPE));
    }

    @Test
    void reasonableNestingStillReads() {
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(64);
        writeSettings(buffer, true);
        writeSettings(buffer, false);

        final CustomPotionEffect.Settings settings = buffer.read(CustomPotionEffect.Settings.NETWORK_TYPE);
        assertNotNull(settings.hiddenEffect());
        assertEquals(0, buffer.readableBytes());
    }

    private static void writeSettings(NetworkBuffer buffer, boolean hasHiddenEffect) {
        buffer.write(NetworkBuffer.VAR_INT, 0); // amplifier
        buffer.write(NetworkBuffer.VAR_INT, 0); // duration
        buffer.write(NetworkBuffer.BOOLEAN, false); // isAmbient
        buffer.write(NetworkBuffer.BOOLEAN, false); // showParticles
        buffer.write(NetworkBuffer.BOOLEAN, false); // showIcon
        buffer.write(NetworkBuffer.BOOLEAN, hasHiddenEffect);
    }
}
