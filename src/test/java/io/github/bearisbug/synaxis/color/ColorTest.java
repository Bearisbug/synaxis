package io.github.bearisbug.synaxis.color;

import com.google.gson.JsonPrimitive;
import net.kyori.adventure.util.RGBLike;
import io.github.bearisbug.synaxis.codec.Result;
import io.github.bearisbug.synaxis.codec.Transcoder;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ColorTest {

    /**
     * Each component is one unsigned byte on the wire, so a component above 127 reads back as itself.
     */
    @Test
    public void rgbBytesReadBackComponentsAbove127() {
        final Color color = new Color(200, 100, 250);

        final byte[] written = NetworkBuffer.makeArray(Color.RGB_BYTE_NETWORK_TYPE, color);
        final RGBLike back = NetworkBuffer.wrap(written, 0, written.length).read(Color.RGB_BYTE_NETWORK_TYPE);

        assertArrayEquals(new byte[]{(byte) 200, 100, (byte) 250}, written);
        assertEquals(color, back);
    }

    @Test
    public void anInvalidHexStringIsAnErrorNamingIt() {
        final var error = assertInstanceOf(Result.Error.class,
                Color.STRING_CODEC.decode(Transcoder.JSON, new JsonPrimitive("#12345g")));

        assertTrue(error.message().contains("invalid hex color '#12345g', expected #RRGGBB"), error.message());
    }
}
