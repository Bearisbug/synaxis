package io.github.bearisbug.synaxis.plugin;

import io.github.bearisbug.synaxis.ServerProcess;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A context a plugin writes for its own tests implements the abstract methods only, and keeps
 * compiling when the host's context gains a capability: {@link PluginContext#play()} and
 * {@link PluginContext#instances()} are defaults that refuse ({@code docs/DESIGN-PLAY.md}
 * {@code API-PLAY-005}, {@code API-PLAY-006}).
 */
class PluginContextDefaultsTest {

    /** Nothing but the abstract methods, none of which these tests call. */
    private static final class OwnContext implements PluginContext {

        @Override
        public ServerProcess process() {
            throw new UnsupportedOperationException();
        }

        @Override
        public PluginEvents events() {
            throw new UnsupportedOperationException();
        }

        @Override
        public PluginScheduler scheduler() {
            throw new UnsupportedOperationException();
        }

        @Override
        public PluginChannels channels() {
            throw new UnsupportedOperationException();
        }

        @Override
        public PluginAdmission admission() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Path dataDirectory() {
            throw new UnsupportedOperationException();
        }

        @Override
        public PluginAnalytics analytics() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Logger logger() {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void aContextThatIsNotTheHostsHasNoPlay() {
        final UnsupportedOperationException refused = assertThrows(UnsupportedOperationException.class,
                new OwnContext()::play);
        assertEquals("only the host's plugin context has play", refused.getMessage());
    }

    @Test
    void aContextThatIsNotTheHostsHasNoInstances() {
        final UnsupportedOperationException refused = assertThrows(UnsupportedOperationException.class,
                new OwnContext()::instances);
        assertEquals("only the host's plugin context has instances", refused.getMessage());
    }
}
