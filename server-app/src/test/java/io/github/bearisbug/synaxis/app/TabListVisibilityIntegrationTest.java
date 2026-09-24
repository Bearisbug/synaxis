package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.ConnectionManager;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code PluginContext#tabList()} ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-027},
 * {@code API-PROD-020}): one visibility per process, owned by the plugin that set it, taken back
 * when that plugin is disabled, and a visibility that throws lists the player.
 */
@EnvTest
class TabListVisibilityIntegrationTest {

    @TempDir
    Path workingDirectory;

    private @Nullable EventLedger ledger;

    @AfterEach
    void closeLedger() {
        if (ledger != null) ledger.close();
    }

    private PluginHost host(Env env) {
        ledger = new EventLedger(workingDirectory.resolve("logs"), "test", EventLedger.DEFAULT_MAX_BYTES_PER_DAY);
        ledger.start();
        return new PluginHost(env.process(), workingDirectory.resolve("plugins"), ledger);
    }

    @Test
    void oneVisibilityPerProcessTakenBackWithItsPlugin(Env env) {
        final PluginHost host = host(env);
        final AtomicReference<Throwable> refused = new AtomicReference<>();
        host.enable(List.of(
                new VisibilityPlugin("hider", (_, subject) -> !subject.getUsername().equals("hidden"), null),
                new VisibilityPlugin("latecomer", (_, _) -> false, refused)), Set.of());

        final ConnectionManager connections = env.process().connection();
        final Player viewer = player(env.process(), "viewer");
        final Player hidden = player(env.process(), "hidden");
        assertFalse(connections.isListedFor(viewer, hidden), "the first plugin's visibility applies");
        assertTrue(connections.isListedFor(viewer, viewer), "a player always lists itself");
        assertInstanceOf(IllegalStateException.class, refused.get(), "a second plugin cannot take the slot");

        host.disableAll();
        assertTrue(connections.isListedFor(viewer, hidden), "disabling the owner lists everyone again");
    }

    @Test
    void aVisibilityThatThrowsListsThePlayer(Env env) {
        final PluginHost host = host(env);
        host.enable(List.of(new VisibilityPlugin("broken", (_, _) -> {
            throw new IllegalStateException("probe visibility failure");
        }, null)), Set.of());

        final Player viewer = player(env.process(), "viewer");
        final Player subject = player(env.process(), "subject");
        assertTrue(env.process().connection().isListedFor(viewer, subject));
        host.disableAll();
    }

    private static Player player(ServerProcess process, String name) {
        return new Player(new QuietConnection(process), new GameProfile(UUID.randomUUID(), name));
    }

    private record VisibilityPlugin(String id, BiPredicate<Player, Player> visibility,
                                    @Nullable AtomicReference<Throwable> refused) implements SynaxisPlugin {

        @Override
        public PluginDescriptor descriptor() {
            return new PluginDescriptor(id, "1.0.0", PluginApi.API_VERSION);
        }

        @Override
        public void onEnable(PluginContext ctx) {
            try {
                ctx.tabList().setVisibility(visibility);
            } catch (IllegalStateException e) {
                if (refused == null) throw e;
                refused.set(e);
            }
        }
    }

    private static final class QuietConnection extends PlayerConnection {
        QuietConnection(ServerProcess process) {
            super(process);
        }

        @Override
        public void sendPacket(SendablePacket packet) {
            // Nobody is listening
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565);
        }
    }
}
