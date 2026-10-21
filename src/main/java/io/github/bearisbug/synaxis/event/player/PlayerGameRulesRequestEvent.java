package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;

import java.util.Objects;

/**
 * Called when a player asks for the values of the game rules, with the {@code REQUEST_GAMERULE_VALUES} action of
 * {@link io.github.bearisbug.synaxis.network.packet.client.play.ClientStatusPacket}.
 * <p>
 * A notification: not cancellable, and the engine does nothing with the request. The request is the client's to
 * send, a modified client sends it at any time whatever its permission level. A listener that grants it first checks
 * a permission node ({@code docs/DESIGN-PROD.md} {@code API-PROD-010}; not {@link Player#getPermissionLevel()}, see
 * {@code REQ-PROD-011}), then sends the values itself.
 */
public class PlayerGameRulesRequestEvent implements PlayerInstanceEvent {
    private final Player player;

    public PlayerGameRulesRequestEvent(Player player) {
        this.player = Objects.requireNonNull(player, "player");
    }

    @Override
    public Player getPlayer() {
        return player;
    }
}
