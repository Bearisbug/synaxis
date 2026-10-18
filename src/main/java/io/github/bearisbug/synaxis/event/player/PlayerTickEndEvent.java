package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;

/**
 * Called when the client reports the end of one of its own ticks ({@code ClientTickEndPacket}).
 * <p>
 * The client decides whether and how often it sends that packet, bounded only by the inbound packet
 * limit ({@code synaxis.packet-limit.max-packets}, 750 packets in 7 s by default), so this event
 * follows no server tick and cannot be trusted as a clock. Logic to run every server tick listens to
 * {@link PlayerTickEvent}.
 */
public class PlayerTickEndEvent implements PlayerInstanceEvent {
    private final Player player;

    public PlayerTickEndEvent(Player player) {
        this.player = player;
    }

    @Override
    public Player getPlayer() {
        return player;
    }
}
