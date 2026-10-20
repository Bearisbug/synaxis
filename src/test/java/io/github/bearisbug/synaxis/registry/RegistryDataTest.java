package io.github.bearisbug.synaxis.registry;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.entity.damage.DamageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("removal")
public class RegistryDataTest {

    /** Reading the components of a material before any registries bound them names the material and the way out. */
    @Test
    public void anUnboundPrototypeNamesTheMaterial() {
        final RegistryData.MaterialEntry entry = RegistryData.material("test:unbound", RegistryData.Properties.fromMap(
                Map.of("id", 0, "translationKey", "item.test.unbound", "components", Map.of())));
        final IllegalStateException error = assertThrows(IllegalStateException.class, entry::prototype);
        assertTrue(error.getMessage().contains("test:unbound"), error.getMessage());
        assertTrue(error.getMessage().contains("Registries.vanilla()"), error.getMessage());
    }

    @Test
    public void propertiesPrintTheirEntries() {
        assertEquals("{a=1}", RegistryData.Properties.fromMap(Map.of("a", 1)).toString());
    }

    /** A registry file that is not on the classpath is read from the file system, relative to the working directory. */
    @Test
    public void aFileMissingFromTheClasspathIsReadFromTheFileSystem(@TempDir Path directory) throws IOException {
        final Path file = Files.writeString(directory.resolve("extra.json"), "{}");
        try (InputStream stream = RegistryData.loadRegistryFile(file.toAbsolutePath().toString())) {
            assertNotNull(stream);
            assertEquals("{}", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * A vendored entry that does not decode stops the load with an {@link IllegalStateException} carrying the
     * message of the decode error, not wrapped in a {@link RuntimeException} or printed as the error record.
     */
    @Test
    public void anEntryThatDoesNotDecodeFailsTheLoad() {
        final Codec<DamageType> failing = Codec.INT.transform(_ -> null, _ -> 0);
        final IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> DynamicRegistry.create(BuiltinRegistries.DAMAGE_TYPE, failing));
        assertTrue(error.getMessage().startsWith("Failed to decode registry entry "), error.getMessage());
        assertFalse(error.getMessage().contains("Error["), error.getMessage());
    }
}
