package dev.gameengine.event.player;

import dev.gameengine.entity.Player;
import dev.gameengine.event.trait.PlayerInstanceEvent;

import java.util.Objects;

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
