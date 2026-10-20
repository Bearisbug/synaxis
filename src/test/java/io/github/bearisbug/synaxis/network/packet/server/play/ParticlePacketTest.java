package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.color.Color;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.particle.Particle;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The ten fixed fields of the packet, then the particle: its id and the data of its type, as
 * {@link Particle#NETWORK_TYPE} writes it anywhere else.
 */
public class ParticlePacketTest {

    @Test
    public void aParticleWithDataIsReadAndWrittenBackByteForByte() {
        final byte[] wire = wire(buffer -> {
            buffer.write(NetworkBuffer.VAR_INT, Particle.DUST.id());
            buffer.write(NetworkBuffer.INT, 0xFF8000); // color
            buffer.write(NetworkBuffer.FLOAT, 2.5f); // scale
        });
        final NetworkBuffer buffer = NetworkBuffer.wrap(wire, 0, wire.length);

        final ParticlePacket packet = buffer.read(ParticlePacket.SERIALIZER);
        assertEquals(0, buffer.readableBytes(), () -> "bytes left unread after " + packet);
        assertArrayEquals(wire, NetworkBuffer.makeArray(ParticlePacket.SERIALIZER, packet));
        final Particle.Dust dust = assertInstanceOf(Particle.Dust.class, packet.particle());
        assertEquals(0xFF8000, Color.fromRGBLike(dust.color()).asRGB());
        assertEquals(2.5f, dust.scale());
        assertEquals(1.5, packet.y());
        assertEquals(7, packet.particleCount());
    }

    @Test
    public void anUnknownParticleIdIsReportedWithTheId() {
        final byte[] wire = wire(buffer -> buffer.write(NetworkBuffer.VAR_INT, 100_000));

        final var error = assertThrows(NullPointerException.class,
                () -> NetworkBuffer.wrap(wire, 0, wire.length).read(ParticlePacket.SERIALIZER));
        assertEquals("unknown particle id: 100000", error.getMessage());
    }

    /** The fixed fields, followed by what {@code particle} writes. */
    private static byte[] wire(Consumer<NetworkBuffer> particle) {
        return NetworkBuffer.makeArray(buffer -> {
            buffer.write(NetworkBuffer.BOOLEAN, true); // override limiter
            buffer.write(NetworkBuffer.BOOLEAN, false); // long distance
            buffer.write(NetworkBuffer.DOUBLE, 1.0);
            buffer.write(NetworkBuffer.DOUBLE, 1.5);
            buffer.write(NetworkBuffer.DOUBLE, -2.0);
            buffer.write(NetworkBuffer.FLOAT, 0.25f); // offset
            buffer.write(NetworkBuffer.FLOAT, 0.5f);
            buffer.write(NetworkBuffer.FLOAT, 0.75f);
            buffer.write(NetworkBuffer.FLOAT, 0.1f); // max speed
            buffer.write(NetworkBuffer.INT, 7); // count
            particle.accept(buffer);
        });
    }
}
