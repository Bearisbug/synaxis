package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.testing.RegistriesTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@RegistriesTest
public class MetadataRegistriesTest {

    @Test
    public void registeredTypesRoundTripDefaultEntries(Registries registries) {
        for (int id = 0; id < Metadata.typeCount(); id++) {
            final Metadata.Type<?> type = Metadata.typeById(id);
            assertNotNull(type, "Missing metadata type definition for id " + id);
            final Metadata.Entry<?> entry = defaultEntry(type);
            final byte[] bytes = NetworkBuffer.makeArray(Metadata.Entry.SERIALIZER, entry, registries);
            final NetworkBuffer buffer = NetworkBuffer.wrap(bytes, 0, bytes.length, registries);

            final Metadata.Entry<?> result = Metadata.Entry.SERIALIZER.read(buffer);

            assertEquals(entry.type(), result.type(), "Wrong metadata type after round-trip for id " + id);
            if (entry.value() instanceof float[] expected && result.value() instanceof float[] actual) {
                assertArrayEquals(expected, actual, "Wrong metadata value after round-trip for id " + id);
            } else {
                assertEquals(entry.value(), result.value(), "Wrong metadata value after round-trip for id " + id);
            }
        }
    }

    private static <T> Metadata.Entry<T> defaultEntry(Metadata.Type<T> type) {
        return type.entry(type.defaultValue());
    }
}
