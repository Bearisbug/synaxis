package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientSetGameRulesPacket;

import java.util.List;
import java.util.Objects;

/**
 * Called when a player asks to change game rules.
 * <p>
 * A notification: not cancellable, and the engine does nothing with the request. The request is the client's to
 * send, a modified client sends it at any time whatever its permission level. A listener that grants it first checks
 * a permission node ({@code docs/DESIGN-PROD.md} {@code API-PROD-010}; not {@link Player#getPermissionLevel()}, see
 * {@code REQ-PROD-011}), then applies the rules itself.
 */
public class PlayerSetGameRulesEvent implements PlayerInstanceEvent {
    private final Player player;
    private final List<ClientSetGameRulesPacket.Entry> requestedRules;

    public PlayerSetGameRulesEvent(Player player, List<ClientSetGameRulesPacket.Entry> requestedRules) {
        this.player = Objects.requireNonNull(player, "player");
        Objects.requireNonNull(requestedRules, "requestedRules");
        this.requestedRules = List.copyOf(requestedRules);
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    /// The requested new rules by the client.
    public List<ClientSetGameRulesPacket.Entry> getRequestedRules() {
        return requestedRules;
    }
}
