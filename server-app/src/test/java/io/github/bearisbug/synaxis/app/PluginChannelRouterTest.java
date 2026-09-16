package io.github.bearisbug.synaxis.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Channel namespace of REQ-M2-006: a plugin can only name channels inside its own namespace, which
 * is also what keeps {@code synaxis} reserved for the host.
 */
class PluginChannelRouterTest {

    @Test
    void aBareNameGetsTheOwnPrefix() {
        assertEquals("a:game", PluginChannelRouter.channelId("a", "game"));
    }

    @Test
    void theOwnPrefixIsAccepted() {
        assertEquals("a:game", PluginChannelRouter.channelId("a", "a:game"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"b:game", "synaxis:handshake", "", "a:", ":game", "a:b:game"})
    void aNameOutsideTheOwnNamespaceIsRefused(String name) {
        assertThrows(IllegalArgumentException.class, () -> PluginChannelRouter.channelId("a", name));
    }

    @Test
    void aChannelIdOverTheWireLimitIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> PluginChannelRouter.channelId("a", "g".repeat(256)));
    }

    @Test
    void theSameChannelCannotBeRegisteredTwice() {
        final PluginChannelRouter router = new PluginChannelRouter();
        router.register("a", "game", (_, _) -> {
        });
        assertThrows(IllegalStateException.class, () -> router.register("a", "game", (_, _) -> {
        }));
    }
}
