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

import java.util.LinkedHashMap;
import java.util.Map;
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

    /**
     * The soft budget of {@code admit}. It is measured after the call returns and never cuts it
     * short: the call is inline on this connection's configuration virtual thread, so there is
     * nothing here that could abort it, and turning a late answer into a rejection would only kick a
     * player the plugin just let in (API-M2-011).
     */
    static final long BUDGET_MILLIS = 200;

    private final AdmissionRegistry registry;
    private final HandshakeGate handshake;
    private final EventLedger ledger;

    AdmissionGate(AdmissionRegistry registry, HandshakeGate handshake, EventLedger ledger) {
        this.registry = registry;
        this.handshake = handshake;
        this.ledger = ledger;
    }

    void attach(EventNode<Event> node) {
        node.addListener(AsyncPlayerConfigurationEvent.class, this::admit);
    }

    private void admit(AsyncPlayerConfigurationEvent event) {
        final Player player = event.getPlayer();
        final UUID connectionId = player.getPlayerConnection().connectionId();
        // The handshake runs first and owns the whole failing half of it: a connection it turned
        // away has already been kicked, and returning here without a spawning instance is what
        // keeps the engine from throwing at one (§11.1 awaiting_hello).
        final HandshakeGate.HelloResult hello = handshake.await(player);
        if (hello == null) return;
        final AdmissionProvider provider = registry.provider();
        if (provider == null) {
            refuse(player, connectionId, hello, DisconnectKeys.MATCH_UNAVAILABLE,
                    "no admission provider is registered");
            return;
        }
        final Admission admission;
        final long start = System.nanoTime();
        try {
            admission = provider.admit(new AdmissionRequest(connectionId, player.getUsername(),
                    hello.modVersion(), hello.schemaVersion()));
        } catch (Throwable throwable) {
            LOGGER.error("Admission provider failed: plugin_id={} connection_id={}",
                    registry.pluginId(), connectionId, throwable);
            refuse(player, connectionId, hello, DisconnectKeys.MATCH_UNAVAILABLE,
                    "the admission provider threw");
            return;
        }
        final long admitMs = (System.nanoTime() - start) / 1_000_000L;
        if (admitMs > BUDGET_MILLIS)
            LOGGER.warn("Admission provider is over its {} ms budget: plugin_id={} connection_id={} admit_ms={}. "
                    + "Its answer stands, this connection just took longer to let in.",
                    BUDGET_MILLIS, registry.pluginId(), connectionId, admitMs);
        switch (admission) {
            case Admission.Accept accept -> event.setSpawningInstance(accept.instance());
            case Admission.Reject reject ->
                    refuse(player, connectionId, hello, reject.reasonKey(),
                            "the admission provider refused");
        }
    }

    private void refuse(Player player, UUID connectionId, HandshakeGate.HelloResult hello,
                        String reasonKey, String cause) {
        LOGGER.info("Refusing a connection: connection_id={} reason={} cause={}", connectionId, reasonKey, cause);
        final Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("connection_id", connectionId);
        // §20 names the reason without the translate prefix the disconnect key carries.
        attributes.put("reason", reasonKey.substring(reasonKey.indexOf(':') + 1));
        attributes.put("schema_version", hello.schemaVersion());
        ledger.record("client_rejected", attributes);
        // Kick and return without setting a spawning instance: the engine checks isOnline() right
        // after this event, so a kicked player never reaches the requireNonNull on that instance.
        player.kick(Component.translatable(reasonKey));
    }
}
