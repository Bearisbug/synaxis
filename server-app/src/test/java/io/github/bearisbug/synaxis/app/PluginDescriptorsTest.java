package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Descriptor validation of REQ-M2-003: the guard of the {@code [*] -> constructed} transition of
 * {@code docs/DESIGN-M2.md} §11.2.
 */
class PluginDescriptorsTest {

    private static PluginDescriptor descriptor(String id) {
        return new PluginDescriptor(id, "1.0.0", PluginApi.API_VERSION);
    }

    @Test
    void acceptsALegalId() {
        assertDoesNotThrow(() -> PluginDescriptors.validate(descriptor("probe-plugin")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Synaxis RTS", "", "-probe", "probe.plugin", "PROBE"})
    void rejectsAnIdOutsideThePattern(String id) {
        final IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> PluginDescriptors.validate(descriptor(id)));
        assertTrue(thrown.getMessage().contains(PluginDescriptors.ID_PATTERN), thrown.getMessage());
    }

    @Test
    void rejectsAnIdOverSixtyFourCharacters() {
        assertThrows(IllegalArgumentException.class,
                () -> PluginDescriptors.validate(descriptor("a".repeat(65))));
        assertDoesNotThrow(() -> PluginDescriptors.validate(descriptor("a".repeat(64))));
    }

    @Test
    void rejectsTheReservedHostId() {
        final IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> PluginDescriptors.validate(descriptor("synaxis")));
        assertTrue(thrown.getMessage().contains("reserved"), thrown.getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void rejectsAnApiVersionThatIsNotTheHostOne(int apiVersion) {
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> PluginDescriptors.validate(new PluginDescriptor("probe", "1.0.0", apiVersion)));
        assertTrue(thrown.getMessage().contains(String.valueOf(PluginApi.API_VERSION)), thrown.getMessage());
        assertTrue(thrown.getMessage().contains(String.valueOf(apiVersion)), thrown.getMessage());
    }
}
