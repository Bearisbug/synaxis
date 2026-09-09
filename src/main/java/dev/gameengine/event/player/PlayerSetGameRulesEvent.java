package dev.gameengine.event.player;

import dev.gameengine.entity.Player;
import dev.gameengine.event.trait.PlayerInstanceEvent;
import dev.gameengine.network.packet.client.play.ClientSetGameRulesPacket;

import java.util.List;
import java.util.Objects;

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
