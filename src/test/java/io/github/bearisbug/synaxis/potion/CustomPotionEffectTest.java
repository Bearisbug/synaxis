package io.github.bearisbug.synaxis.potion;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.ByteBinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import io.github.bearisbug.synaxis.codec.Transcoder;
import org.junit.jupiter.api.Test;

import static io.github.bearisbug.synaxis.codec.CodecAssertions.assertOk;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The amplifier of a custom potion effect is an unsigned byte, as in 26.2.
 */
public class CustomPotionEffectTest {

    @Test
    void anAmplifierAbove127RoundTrips() {
        final CustomPotionEffect effect = new CustomPotionEffect(PotionEffect.SPEED, 200, 20, false, true, true);

        final BinaryTag nbt = assertOk(CustomPotionEffect.CODEC.encode(Transcoder.NBT, effect));
        assertEquals(ByteBinaryTag.byteBinaryTag((byte) 0xC8), ((CompoundBinaryTag) nbt).get("amplifier"));
        assertEquals(200, assertOk(CustomPotionEffect.CODEC.decode(Transcoder.NBT, nbt)).amplifier());

        final JsonElement json = assertOk(CustomPotionEffect.CODEC.encode(Transcoder.JSON, effect));
        assertEquals(200, assertOk(CustomPotionEffect.CODEC.decode(Transcoder.JSON, json)).amplifier());
    }

    @Test
    void aStoredByteIsReadAsUnsigned() {
        final CompoundBinaryTag nbt = CompoundBinaryTag.builder()
                .putString("id", "minecraft:speed")
                .putByte("amplifier", (byte) -56)
                .build();
        assertEquals(200, assertOk(CustomPotionEffect.CODEC.decode(Transcoder.NBT, nbt)).amplifier());

        final JsonElement json = JsonParser.parseString("{\"id\":\"minecraft:speed\",\"amplifier\":200}");
        assertEquals(200, assertOk(CustomPotionEffect.CODEC.decode(Transcoder.JSON, json)).amplifier());
    }

    @Test
    void anAmplifierOutsideAnUnsignedByteIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new CustomPotionEffect.Settings(256, 0, false, true, true, null));
        assertThrows(IllegalArgumentException.class,
                () -> new CustomPotionEffect.Settings(-1, 0, false, true, true, null));
        assertDoesNotThrow(() -> new CustomPotionEffect.Settings(255, 0, false, true, true, null));
    }

    @Test
    void aMissingEffectIsRejectedWhereItIsMade() {
        assertEquals("id", assertThrows(NullPointerException.class,
                () -> new CustomPotionEffect(null, 0, 0, false, true, true)).getMessage());
        assertEquals("settings", assertThrows(NullPointerException.class,
                () -> new CustomPotionEffect(PotionEffect.SPEED, null)).getMessage());
        assertEquals("effect", assertThrows(NullPointerException.class,
                () -> new Potion(null, 0, 20)).getMessage());
    }
}
