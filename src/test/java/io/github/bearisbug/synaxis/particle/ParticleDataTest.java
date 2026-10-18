package io.github.bearisbug.synaxis.particle;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.util.RGBLike;
import io.github.bearisbug.synaxis.codec.Result;
import io.github.bearisbug.synaxis.codec.Transcoder;
import io.github.bearisbug.synaxis.color.AlphaColor;
import io.github.bearisbug.synaxis.color.Color;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.server.play.ParticlePacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.Map;

import static io.github.bearisbug.synaxis.codec.CodecAssertions.assertError;
import static io.github.bearisbug.synaxis.codec.CodecAssertions.assertOk;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ParticleDataTest {

    @Test
    public void testDustParticleDefault() {
        Particle particle = Particle.DUST;
        ParticlePacket packet = new ParticlePacket(particle, false, true, 0, 0, 0, 0, 0, 0, 0, 0);
        assertDoesNotThrow(() -> ParticlePacket.SERIALIZER.write(NetworkBuffer.resizableBuffer(), packet));
    }

    @Test
    public void testDustParticleInvalid() {
        assertThrows(NullPointerException.class, () -> Particle.DUST.withProperties(null, 1));
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
        assertThrows(NullPointerException.class, () -> Particle.BLOCK.withBlock(null));
    }

    // A null field used to be accepted and to throw on the writer thread of every viewer, kicking each of them
    @Test
    public void aNullFieldIsRejectedWhereTheParticleIsMade() {
        assertNullField("block", () -> Particle.BLOCK.withBlock(null));
        assertNullField("block", () -> Particle.BLOCK_MARKER.withBlock(null));
        assertNullField("block", () -> Particle.FALLING_DUST.withBlock(null));
        assertNullField("block", () -> Particle.DUST_PILLAR.withBlock(null));
        assertNullField("block", () -> Particle.BLOCK_CRUMBLE.withBlock(null));
        assertNullField("color", () -> Particle.DUST.withColor(null));
        assertNullField("color", () -> Particle.DUST_COLOR_TRANSITION.withColor(null));
        assertNullField("transitionColor", () -> Particle.DUST_COLOR_TRANSITION.withTransitionColor(null));
        assertNullField("color", () -> Particle.EFFECT.withColor(null));
        assertNullField("color", () -> Particle.INSTANT_EFFECT.withColor(null));
        assertNullField("color", () -> Particle.ENTITY_EFFECT.withColor((AlphaColor) null));
        assertNullField("color", () -> Particle.TINTED_LEAVES.withColor((AlphaColor) null));
        assertNullField("color", () -> Particle.FLASH.withColor((AlphaColor) null));
        assertNullField("color", () -> Particle.TRAIL.withColor(null));
        assertNullField("target", () -> Particle.TRAIL.withTarget(null));
        assertNullField("item", () -> Particle.ITEM.withItem(null));
        assertNullField("sourceBlockPosition is required for SourceType.BLOCK",
                () -> Particle.VIBRATION.withSourceBlockPosition(null, 1));
    }

    private static void assertNullField(String message, Executable make) {
        assertEquals(message, assertThrows(NullPointerException.class, make).getMessage());
    }

    @Test
    public void anOpaqueColorKeepsItsAlpha() {
        final RGBLike red = new Color(255, 0, 0);
        assertEquals(255, Particle.ENTITY_EFFECT.withColor(red).color().alpha());
        assertEquals(255, Particle.TINTED_LEAVES.withColor(red).color().alpha());
        assertEquals(255, Particle.FLASH.withColor(red).color().alpha());

        // Resolved by its static type, an alpha color still goes through the RGBLike overload
        final RGBLike translucent = new AlphaColor(128, 1, 2, 3);
        assertEquals(128, Particle.ENTITY_EFFECT.withColor(translucent).color().alpha());
        assertEquals(128, Particle.TINTED_LEAVES.withColor(translucent).color().alpha());
        assertEquals(128, Particle.FLASH.withColor(translucent).color().alpha());
    }

    @Test
    public void theAlphaOfAColorSurvivesTheNetwork() {
        for (Particle particle : new Particle[]{
                Particle.ENTITY_EFFECT.withColor(new AlphaColor(128, 10, 20, 30)),
                Particle.TINTED_LEAVES.withColor(new AlphaColor(128, 10, 20, 30)),
                Particle.FLASH.withColor(new AlphaColor(128, 10, 20, 30))}) {
            final NetworkBuffer buffer = NetworkBuffer.resizableBuffer();
            buffer.write(Particle.NETWORK_TYPE, particle);
            assertEquals(particle, buffer.read(Particle.NETWORK_TYPE));
        }
    }

    @Test
    public void flashIsEncodedAsAnArgbColor() {
        final Particle flash = Particle.FLASH.withColor(new AlphaColor(0x80, 0x11, 0x22, 0x33));
        final BinaryTag encoded = assertOk(Particle.CODEC.encode(Transcoder.NBT, flash));

        assertEquals(0x80112233, ((CompoundBinaryTag) encoded).getInt("color"));
        assertEquals(flash, assertOk(Particle.CODEC.decode(Transcoder.NBT, encoded)));
    }

    @Test
    public void effectColorAndPowerAreOptional() {
        final Particle decoded = assertOk(Particle.CODEC.decode(Transcoder.JAVA, Map.of("type", "minecraft:effect")));

        final Particle.Effect effect = assertInstanceOf(Particle.Effect.class, decoded);
        assertEquals(Color.WHITE, effect.color());
        assertEquals(1f, effect.power());
        assertInstanceOf(Particle.InstantEffect.class,
                assertOk(Particle.CODEC.decode(Transcoder.JAVA, Map.of("type", "minecraft:instant_effect"))));
    }

    @Test
    public void particlesTheCodecCannotHandleAreErrors() {
        assertError("unknown particle: minecraft:nope",
                Particle.CODEC.decode(Transcoder.JAVA, Map.of("type", "minecraft:nope")));
        assertError("unknown particle: Bad Key",
                Particle.CODEC.decode(Transcoder.JAVA, Map.of("type", "Bad Key")));
        assertError("the vibration particle has no codec",
                Particle.CODEC.decode(Transcoder.JAVA, Map.of("type", "minecraft:vibration")));
        assertError("the vibration particle has no codec", Particle.CODEC.encode(Transcoder.NBT, Particle.VIBRATION));

        final Result<Particle> unknownBlock = Particle.CODEC.decode(Transcoder.JAVA,
                Map.of("type", "minecraft:block", "block_state", Map.of("Name", "minecraft:nope")));
        final String message = assertInstanceOf(Result.Error.class, unknownBlock).message();
        assertTrue(message.contains("unknown block: minecraft:nope"), message);
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
