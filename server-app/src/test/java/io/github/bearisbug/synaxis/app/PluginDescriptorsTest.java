package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;
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

    private static SynaxisPlugin withId(String id) {
        return plugin(new PluginDescriptor(id, "1.0.0", PluginApi.API_VERSION));
    }

    private static SynaxisPlugin plugin(PluginDescriptor descriptor) {
        return new SynaxisPlugin() {
            @Override
            public PluginDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public void onEnable(PluginContext ctx) {
                // Never enabled
            }
        };
    }

    @Test
    void acceptsALegalId() {
        assertDoesNotThrow(() -> PluginDescriptors.validate(withId("probe-plugin")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Synaxis RTS", "", "-probe", "probe.plugin", "PROBE"})
    void rejectsAnIdOutsideThePattern(String id) {
        final IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> PluginDescriptors.validate(withId(id)));
        assertTrue(thrown.getMessage().contains(PluginDescriptors.ID_PATTERN), thrown.getMessage());
    }

    @Test
    void rejectsAnIdOverSixtyFourCharacters() {
        assertThrows(IllegalArgumentException.class,
                () -> PluginDescriptors.validate(withId("a".repeat(65))));
        assertDoesNotThrow(() -> PluginDescriptors.validate(withId("a".repeat(64))));
    }

    @Test
    void rejectsTheReservedHostId() {
        final IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> PluginDescriptors.validate(withId("synaxis")));
        assertTrue(thrown.getMessage().contains("reserved"), thrown.getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void rejectsAnApiVersionThatIsNotTheHostOne(int apiVersion) {
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> PluginDescriptors.validate(plugin(new PluginDescriptor("probe", "1.0.0", apiVersion))));
        assertTrue(thrown.getMessage().contains(String.valueOf(PluginApi.API_VERSION)), thrown.getMessage());
        assertTrue(thrown.getMessage().contains(String.valueOf(apiVersion)), thrown.getMessage());
    }
}
