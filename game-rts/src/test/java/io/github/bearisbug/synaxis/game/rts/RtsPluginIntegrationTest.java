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
import io.github.bearisbug.synaxis.plugin.PluginAnalytics;
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
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        private final RecordedEvents events = new RecordedEvents();
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
        public PluginAnalytics analytics() {
            return events;
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
        MatchSupport.registerMatchDimension(env);
        System.setProperty("synaxis.app.map", MapPreset.SMALL.id());
        final ProbeContext context = new ProbeContext(env, dataDirectory);
        try {
            new RtsPlugin().onEnable(context);
        } finally {
            System.clearProperty("synaxis.app.map");
        }

        final AdmissionProvider provider = context.provider;
        assertNotNull(provider, "the plugin registered no admission provider");
        final Admission.Accept accept = assertInstanceOf(Admission.Accept.class,
                provider.admit(new AdmissionRequest(UUID.randomUUID(), "tester", 0, 0)));
        assertEquals(1, accept.team());

        // The map is already there, the whole of it, and no chunk is ever loaded again
        final Instance match = accept.instance();
        assertFalse(match.hasEnabledAutoChunkLoad(), "the match kept loading chunks after its map was generated");
        assertEquals(MapPreset.SMALL.chunkCount(), match.getChunks().size());
        assertEquals(Block.STONE, match.getBlock(0, MapTerrain.GROUND_HEIGHT - 1, 0));
        assertEquals(List.of("match_created", "match_started"), context.events.names());

        // And the connection the provider accepted really can spawn in there
        final Player player = env.createPlayer(match, new Pos(0.5, MapTerrain.GROUND_HEIGHT, 0.5));
        assertEquals(match, player.getInstance());
    }
}
