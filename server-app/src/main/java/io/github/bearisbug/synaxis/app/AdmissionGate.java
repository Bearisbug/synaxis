package io.github.bearisbug.synaxis.app;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.plugin.Admission;
import io.github.bearisbug.synaxis.plugin.AdmissionProvider;
import io.github.bearisbug.synaxis.plugin.AdmissionRequest;
import io.github.bearisbug.synaxis.plugin.DisconnectKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * The one {@code setSpawningInstance} call point of the process (API-M2-011).
 * <p>
 * The engine requires a spawning instance while the connection is still in the configuration phase
 * and throws a {@code NullPointerException} when it does not get one, which would show the player
 * {@code synaxis:internal-error} instead of the designed reason. Only a plugin knows what matches
 * exist, so the decision is taken from the registered admission provider, inline on the
 * configuration virtual thread of that connection, and a failure to decide is a kick.
 */
final class AdmissionGate {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdmissionGate.class);

    private final AdmissionRegistry registry;

    AdmissionGate(AdmissionRegistry registry) {
        this.registry = registry;
    }

    void attach(EventNode<Event> node) {
        node.addListener(AsyncPlayerConfigurationEvent.class, this::admit);
    }

    private void admit(AsyncPlayerConfigurationEvent event) {
        final Player player = event.getPlayer();
        final UUID connectionId = player.getPlayerConnection().connectionId();
        final AdmissionProvider provider = registry.provider();
        if (provider == null) {
            refuse(player, connectionId, DisconnectKeys.MATCH_UNAVAILABLE, "no admission provider is registered");
            return;
        }
        final Admission admission;
        try {
            // The handshake fields are zero until REQ-M2-008 lands, which is what fills them in.
            admission = provider.admit(new AdmissionRequest(connectionId, player.getUsername(), 0, 0));
        } catch (Throwable throwable) {
            LOGGER.error("Admission provider failed: plugin_id={} connection_id={}",
                    registry.pluginId(), connectionId, throwable);
            refuse(player, connectionId, DisconnectKeys.MATCH_UNAVAILABLE, "the admission provider threw");
            return;
        }
        switch (admission) {
            case Admission.Accept accept -> event.setSpawningInstance(accept.instance());
            case Admission.Reject reject ->
                    refuse(player, connectionId, reject.reasonKey(), "the admission provider refused");
        }
    }

    private static void refuse(Player player, UUID connectionId, String reasonKey, String cause) {
        LOGGER.info("Refusing a connection: connection_id={} reason={} cause={}", connectionId, reasonKey, cause);
        // Kick and return without setting a spawning instance: the engine checks isOnline() right
        // after this event, so a kicked player never reaches the requireNonNull on that instance.
        player.kick(Component.translatable(reasonKey));
    }
}
