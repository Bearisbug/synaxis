package io.github.bearisbug.synaxis.particle;

import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.server.play.ParticlePacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ParticleDataTest {

    @Test
    public void testDustParticleDefault() {
        Particle particle = Particle.DUST;
        ParticlePacket packet = new ParticlePacket(particle, false, true, 0, 0, 0, 0, 0, 0, 0, 0);
        assertDoesNotThrow(() -> ParticlePacket.SERIALIZER.write(NetworkBuffer.resizableBuffer(), packet));
    }

    @Test
    public void testDustParticleInvalid() {
        var particle = Particle.DUST.withProperties(null, 1);
        ParticlePacket packet = new ParticlePacket(particle, false, true, 0, 0, 0, 0, 0, 0, 0, 0);
        assertThrows(NullPointerException.class, () -> ParticlePacket.SERIALIZER.write(NetworkBuffer.resizableBuffer(), packet));
    }

    @Test
    public void testParticleValid() {
        var particle = Particle.ENTITY_EFFECT;
        ParticlePacket packet = new ParticlePacket(particle, false, true, 0, 0, 0, 0, 0, 0, 0, 0);
        assertDoesNotThrow(() -> ParticlePacket.SERIALIZER.write(NetworkBuffer.resizableBuffer(), packet));
    }

    @Test
    public void testParticleData() {
        var particle = Particle.ENTITY_EFFECT;
        ParticlePacket packet = new ParticlePacket(particle, false, true, 0, 0, 0, 0, 0, 0, 0, 0);
        assertDoesNotThrow(() -> ParticlePacket.SERIALIZER.write(NetworkBuffer.resizableBuffer(), packet));
    }

    @Test
    public void invalidBlock() {
        var particle = Particle.BLOCK.withBlock(null);
        ParticlePacket packet = new ParticlePacket(particle, false, true, 0, 0, 0, 0, 0, 0, 0, 0);
        assertThrows(NullPointerException.class, () -> ParticlePacket.SERIALIZER.write(NetworkBuffer.resizableBuffer(), packet));
    }

    /**
     * The item of an item particle is an item stack template on the wire, which a 26.2 client cannot build from air:
     * it fails to decode the packet and drops the connection.
     */
    @Test
    public void itemParticleDefaultIsNotAir() {
        byte[] expected = NetworkBuffer.makeArray(buffer -> {
            buffer.write(NetworkBuffer.VAR_INT, Particle.ITEM.id());
            buffer.write(NetworkBuffer.VAR_INT, Material.STONE.id());
            buffer.write(NetworkBuffer.VAR_INT, 1); // amount
            buffer.write(NetworkBuffer.VAR_INT, 0); // added components
            buffer.write(NetworkBuffer.VAR_INT, 0); // removed components
        });
        assertArrayEquals(expected, NetworkBuffer.makeArray(Particle.NETWORK_TYPE, Particle.ITEM));
    }

    @Test
    public void itemParticleRejectsAir() {
        assertThrows(IllegalArgumentException.class, () -> Particle.ITEM.withItem(ItemStack.AIR));
    }
}
