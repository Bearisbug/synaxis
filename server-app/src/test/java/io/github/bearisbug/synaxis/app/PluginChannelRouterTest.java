package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.game.protocol.play.PlayProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.function.Supplier;

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

    /**
     * The ids of the scene layer follow the rule of the channels, {@link PluginIds}: a name one takes the other
     * takes, as long as the id stays within the 64 bytes of {@code synaxis:play}.
     */
    @ParameterizedTest
    @ValueSource(strings = {"game", "a:game", "a.b/c-d_e", "b:game", "synaxis:handshake", "", "a:", ":game", "a:b:game",
            "Game", "g a"})
    void aChannelAndAnIdOfTheSceneLayerTakeTheSameNames(String name) {
        assertEquals(outcome(() -> PluginChannelRouter.channelId("a", name)),
                outcome(() -> PluginIds.qualify("a", name, PlayProtocol.MAX_ID_BYTES, "an id")), name);
    }

    private static String outcome(Supplier<String> qualify) {
        try {
            return qualify.get();
        } catch (IllegalArgumentException _) {
            return "refused";
        }
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
