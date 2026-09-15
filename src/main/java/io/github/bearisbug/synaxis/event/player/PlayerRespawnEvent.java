package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;

/**
 * Called when {@link Player#respawn()} is executed (for custom respawn or as a result of
 * {@link io.github.bearisbug.synaxis.network.packet.client.play.ClientStatusPacket}
 */
public class PlayerRespawnEvent implements PlayerInstanceEvent {

    private final Player player;
    private Pos respawnPosition;

    public PlayerRespawnEvent(Player player) {
        this.player = player;
        this.respawnPosition = player.getRespawnPoint();
    }

    /**
     * Gets the respawn position.
     * <p>
     * Is by default {@link Player#getRespawnPoint()}
     *
     * @return the respawn position
     */
    public Pos getRespawnPosition() {
        return respawnPosition;
    }

    /**
     * Changes the respawn position.
     *
     * @param respawnPosition the new respawn position
     */
    public void setRespawnPosition(Pos respawnPosition) {
        this.respawnPosition = respawnPosition;
    }

    @Override
    public Player getPlayer() {
        return player;
    }
}
