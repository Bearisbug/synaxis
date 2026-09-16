package io.github.bearisbug.synaxis.game.rts;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.plugin.Admission;
import io.github.bearisbug.synaxis.plugin.AdmissionProvider;
import io.github.bearisbug.synaxis.plugin.AdmissionRequest;
import io.github.bearisbug.synaxis.plugin.PluginAdmission;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginChannels;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginEvents;
import io.github.bearisbug.synaxis.plugin.PluginScheduler;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The one compile time plugin of M2 (REQ-M2-003): what it builds while the host enables it, and
 * what it answers when the host asks where a connecting player belongs (REQ-M2-024).
 */
@EnvTest
class RtsPluginIntegrationTest {

    @TempDir
    Path dataDirectory;

    /**
     * The host side of {@link PluginContext}, reduced to the capabilities this plugin uses. The
     * real one lives in {@code :server-app}, which a plugin must not depend on.
     */
    private static final class ProbeContext implements PluginContext {

        private final Env env;
        private final Path dataDirectory;
        private @Nullable AdmissionProvider provider;

        ProbeContext(Env env, Path dataDirectory) {
            this.env = env;
            this.dataDirectory = dataDirectory;
        }

        @Override
        public ServerProcess process() {
            return env.process();
        }

        @Override
        public PluginEvents events() {
            return new PluginEvents() {
                @Override
                public <E extends Event> void addListener(Class<E> eventType, Consumer<E> listener) {
                    env.process().eventHandler().addListener(eventType, listener);
                }
            };
        }

        @Override
        public PluginScheduler scheduler() {
            throw new UnsupportedOperationException("the rts plugin schedules nothing yet");
        }

        @Override
        public PluginChannels channels() {
            throw new UnsupportedOperationException("the rts plugin owns no channel yet");
        }

        @Override
        public PluginAdmission admission() {
            return registered -> this.provider = registered;
        }

        @Override
        public Path dataDirectory() {
            return dataDirectory;
        }

        @Override
        public Logger logger() {
            return LoggerFactory.getLogger("plugin.rts");
        }
    }

    @Test
    void theDescriptorIsTheOneTheHostAccepts() {
        final RtsPlugin plugin = new RtsPlugin();
        assertEquals("rts", plugin.descriptor().id());
        assertEquals(PluginApi.API_VERSION, plugin.descriptor().apiVersion());
    }

    @Test
    void enablingBuildsTheMatchAndTakesTheAdmissionSlot(Env env) {
        final ProbeContext context = new ProbeContext(env, dataDirectory);
        new RtsPlugin().onEnable(context);

        final AdmissionProvider provider = context.provider;
        assertNotNull(provider, "the plugin registered no admission provider");
        final Admission.Accept accept = assertInstanceOf(Admission.Accept.class,
                provider.admit(new AdmissionRequest(UUID.randomUUID(), "tester", 0, 0)));
        assertEquals(1, accept.team());

        // The generator is wired: ground up to the spawn height, air above it.
        final Instance match = accept.instance();
        match.loadChunk(0, 0).join();
        assertEquals(Block.STONE, match.getBlock(0, 39, 0));
        assertEquals(Block.AIR, match.getBlock(0, 40, 0));

        // And the connection the provider accepted really can spawn in there.
        final Player player = env.createPlayer(match, new Pos(0, 42, 0));
        assertEquals(match, player.getInstance());
    }
}
