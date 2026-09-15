package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;

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
