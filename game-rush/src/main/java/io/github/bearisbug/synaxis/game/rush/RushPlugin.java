package io.github.bearisbug.synaxis.game.rush;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.event.player.PlayerDisconnectEvent;
import io.github.bearisbug.synaxis.event.player.PlayerSpawnEvent;
import io.github.bearisbug.synaxis.game.playkit.LocalQueue;
import io.github.bearisbug.synaxis.plugin.Admission;
import io.github.bearisbug.synaxis.plugin.DisconnectKeys;
import io.github.bearisbug.synaxis.plugin.PluginApi;
import io.github.bearisbug.synaxis.plugin.PluginContext;
import io.github.bearisbug.synaxis.plugin.PluginDescriptor;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;
import io.github.bearisbug.synaxis.plugin.instance.InstanceSpec;
import io.github.bearisbug.synaxis.plugin.instance.ManagedInstance;
import io.github.bearisbug.synaxis.plugin.play.PluginPlay;
import io.github.bearisbug.synaxis.timer.TaskSchedule;

import java.util.Set;

/**
 * The sample game rush, "Beacon Rush" ({@code docs/DESIGN-PLAY.md} §6.7, {@code API-PLAY-010}): the
 * scene owner of its process, holding the admission slot. Every player is let into the lobby; the
 * lobby starts rounds on demand in managed instances.
 * <p>
 * It is not on the runtime classpath of {@code :server-app}; {@code ./gradlew :server-app:runRush}
 * starts the host with it, and a release adds its jar and the {@code :play-kit} jar to the classpath
 * of the distribution with {@code -Dsynaxis.app.plugins=rush} ({@code ADR-PLAY-011}, §23).
 */
public final class RushPlugin implements SynaxisPlugin {

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor("rush", "0.1.0", PluginApi.API_VERSION);

    /**
     * Built with the plugin rather than in {@code onEnable}. The host builds every plugin on the
     * classpath to read its descriptor, whitelisted or not, so a classpath without {@code :play-kit}
     * fails right there, in discovery, and not only once rush is enabled (§23).
     */
    private final LocalQueue<Player> queue = new LocalQueue<>();

    @Override
    public PluginDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public Set<String> translationKeys() {
        return RushHud.keys();
    }

    @Override
    public void onEnable(PluginContext context) {
        final RushConfig config = RushConfig.fromSystemProperties();
        // On the startup thread the lobby comes back open, or closed when it could not be prepared,
        // which start() refuses: onEnable fails and the host refuses to start (API-PLAY-006)
        final ManagedInstance lobby = context.instances()
                .create(new InstanceSpec("lobby", Arena.LOBBY_RADIUS, Arena.lobby()));
        lobby.start();
        final PluginPlay play = context.play();
        final Lobby rules = new Lobby(context.instances(), lobby, queue, config, new RushHud(play), context.analytics(),
                System::nanoTime, context.logger());
        play.onAction(RushHud.QUEUE, (player, _) -> rules.toggleQueue(player));
        play.onAction(RushHud.BACK, (player, _) -> rules.backToLobby(player));
        context.events().addListener(AsyncPlayerConfigurationEvent.class,
                event -> event.getPlayer().setRespawnPoint(Arena.SPAWN));
        // Global scope, filtered by instance: on a first spawn the host applies the stored state in
        // its own node, after the instance scoped listeners
        context.events().addListener(PlayerSpawnEvent.class, event -> {
            if (event.getInstance() == lobby.instance()) rules.arrived(event.getPlayer());
        });
        context.events().addListener(PlayerDisconnectEvent.class, event -> rules.left(event.getPlayer()));
        context.scheduler().scheduleTask(rules::tick, TaskSchedule.nextTick(), TaskSchedule.tick(1));
        context.admission().register(_ -> lobby.state() == ManagedInstance.State.RUNNING
                ? new Admission.Accept(lobby.instance(), 0)
                : new Admission.Reject(DisconnectKeys.MATCH_UNAVAILABLE));
    }
}
