package io.github.bearisbug.synaxis.plugin;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A plugin that does not override {@link SynaxisPlugin#translationKeys()} declares no key of its own
 * ({@code docs/DESIGN-PROD.md} {@code API-PROD-029}): what it sends comes from {@link DisconnectKeys},
 * which the translation key gate reads on its own.
 */
class SynaxisPluginTest {

    @Test
    void aPluginDeclaresNoTranslationKeyUnlessItSaysSo() {
        final SynaxisPlugin plugin = new SynaxisPlugin() {
            @Override
            public PluginDescriptor descriptor() {
                return new PluginDescriptor("quiet", "1.0.0", PluginApi.API_VERSION);
            }

            @Override
            public void onEnable(PluginContext context) {
            }
        };

        assertEquals(Set.of(), plugin.translationKeys());
    }
}
