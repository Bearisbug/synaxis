package io.github.bearisbug.synaxis.sound;

import com.google.gson.JsonPrimitive;
import io.github.bearisbug.synaxis.codec.Transcoder;
import org.junit.jupiter.api.Test;

import static io.github.bearisbug.synaxis.codec.CodecAssertions.assertError;
import static io.github.bearisbug.synaxis.codec.CodecAssertions.assertOk;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SoundEventTest {

    /** Item components and environment attributes decode sound events from data, a bad id has to be a Result.Error. */
    @Test
    void aStringThatIsNotAKeyIsAnError() {
        assertError("Unknown sound event: Minecraft:Bad Key",
                SoundEvent.CODEC.decode(Transcoder.JSON, new JsonPrimitive("Minecraft:Bad Key")));
    }

    @Test
    void aBuiltinSoundEventDecodesFromItsKey() {
        assertEquals(SoundEvents.BLOCK_STONE_BREAK,
                assertOk(SoundEvent.CODEC.decode(Transcoder.JSON, new JsonPrimitive("minecraft:block.stone.break"))));
    }
}
