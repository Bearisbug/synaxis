package net.minestom.server.item.component;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.codec.Transcoder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.minestom.server.codec.CodecAssertions.assertOk;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class FireworkListTest {

    /**
     * Vanilla's minecraft:fireworks component treats flight_duration as optional with a default of 1, so
     * {@code [fireworks={explosions:[]}]} has to decode.
     */
    @Test
    public void flightDurationIsOptional() {
        final var nbt = CompoundBinaryTag.builder()
                .put("explosions", assertOk(FireworkExplosion.CODEC.list().encode(Transcoder.NBT, List.of())))
                .build();

        final FireworkList decoded = assertOk(FireworkList.NBT_TYPE.decode(Transcoder.NBT, nbt));
        assertEquals(1, decoded.flightDuration());
        assertEquals(List.of(), decoded.explosions());
    }

    @Test
    public void flightDurationRoundTrips() {
        final var value = new FireworkList(3, List.of());
        assertEquals(value, assertOk(FireworkList.NBT_TYPE.decode(Transcoder.NBT,
                assertOk(FireworkList.NBT_TYPE.encode(Transcoder.NBT, value)))));
    }
}
