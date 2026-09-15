package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.codec.Ctor;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.STRING;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The compiled template path is only taken when {@code synaxis.template-compiler} is set, which a
 * static field initializer reads long before any test runs, so the generator is driven directly.
 */
public class NetworkBufferTemplateCompilerTest {

    private static <T> void assertRoundTrip(NetworkBuffer.Type<T> type, T expected) {
        final byte[] array = NetworkBuffer.makeArray(type, expected);
        final NetworkBuffer buffer = NetworkBuffer.wrap(array, 0, array.length);
        assertEquals(expected, buffer.read(type));
        assertEquals(0, buffer.readableBytes());
    }

    @Test
    public void compiledSingleFieldTemplate() {
        record CompiledSingle(int value) {
        }
        NetworkBuffer.Type<CompiledSingle> type = NetworkBufferTemplateImpl.template(
                VAR_INT, (Function<CompiledSingle, Integer>) CompiledSingle::value,
                (Ctor.F1<Integer, CompiledSingle>) CompiledSingle::new);
        assertRoundTrip(type, new CompiledSingle(12));
    }

    @Test
    public void compiledTwoFieldTemplate() {
        record CompiledPair(int first, String second) {
        }
        NetworkBuffer.Type<CompiledPair> type = NetworkBufferTemplateImpl.template(
                VAR_INT, (Function<CompiledPair, Integer>) CompiledPair::first,
                STRING, (Function<CompiledPair, String>) CompiledPair::second,
                (Ctor.F2<Integer, String, CompiledPair>) CompiledPair::new);
        assertRoundTrip(type, new CompiledPair(7, "hello"));
    }
}
