package io.github.bearisbug.synaxis.app;

import ch.qos.logback.classic.Level;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerPluginMessageEvent;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;
import io.github.bearisbug.synaxis.plugin.instance.PluginInstances;
import io.github.bearisbug.synaxis.plugin.play.PluginPlay;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The plugin host wires the scene layer and the managed instances in ({@code docs/DESIGN-PLAY.md}
 * §6.1, {@code API-PLAY-005}, {@code API-PLAY-006}): the context it hands a plugin has a
 * {@code play()} and an {@code instances()} of that plugin's own instead of the refusing defaults,
 * and a payload on {@code synaxis:play} reaches the scene layer instead of being dropped as a payload
 * on a channel nobody registered.
 */
@EnvTest
class PluginHostPlayWiringIntegrationTest {

    @TempDir
    Path workingDirectory;

    private @Nullable EventLedger ledger;

    /** Stops the ledger's writer before JUnit deletes the working directory, as in PluginHostIntegrationTest. */
    @AfterEach
    void closeLedger() {
        if (ledger != null) ledger.close();
    }

    /** Takes the two capabilities in its {@code onEnable} and keeps what it got. */
    private static final class CapturingPlugin implements SynaxisPlugin {

        private final PluginDescriptor descriptor;
        private @Nullable PluginPlay play;
        private @Nullable PluginInstances instances;
        private @Nullable UnsupportedOperationException refused;

        CapturingPlugin(String id) {
            this.descriptor = new PluginDescriptor(id, "1.0.0", PluginApi.API_VERSION);
        }

        @Override
        public PluginDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public void onEnable(PluginContext context) {
            try {
                this.play = context.play();
                this.instances = context.instances();
            } catch (UnsupportedOperationException e) {
                this.refused = e;
            }
        }
    }

    private PluginHost host(Env env) {
        final EventLedger opened = new EventLedger(workingDirectory.resolve("logs"), "test",
                EventLedger.DEFAULT_MAX_BYTES_PER_DAY);
        opened.start();
        this.ledger = opened;
        return new PluginHost(env.process(), workingDirectory.resolve("plugins"), opened);
    }

    @Test
    void theHostsContextHandsEachPluginItsOwnPlayAndInstances(Env env) {
        final CapturingPlugin first = new CapturingPlugin("first");
        final CapturingPlugin second = new CapturingPlugin("second");
        final PluginHost host = host(env);

        host.enable(List.of(first, second), Set.of());

        assertNull(first.refused, "the context of the host refused a capability");
        assertNull(second.refused, "the context of the host refused a capability");
        assertNotNull(first.play);
        assertNotNull(first.instances);
        assertNotSame(first.play, second.play, "two plugins share one play capability");
        assertNotSame(first.instances, second.instances, "two plugins share one instance capability");
        host.disableAll();
    }

    @Test
    void aPayloadOnThePlayChannelReachesTheSceneLayer(Env env) {
        final PluginHost host = host(env);
        host.enable(List.of(new CapturingPlugin("probe")), Set.of());
        final Player player = env.createPlayer(env.createFlatInstance(), new Pos(0, 41, 0));

        try (LogCapture logs = LogCapture.of(PluginChannelRouter.class)) {
            // C2S_SCENE_READY (msg_id 0x01) for scene_seq 1, a scene this player is not in: nothing to do
            env.process().eventHandler().call(new PlayerPluginMessageEvent(player, "synaxis:play", new byte[]{0x01, 0x01}));

            assertEquals(List.of(), logs.messagesAt(Level.WARN), "the payload was dropped as one on an unregistered channel");
            assertEquals(List.of(), logs.messagesAt(Level.ERROR));
        }
        host.disableAll();
    }
}
