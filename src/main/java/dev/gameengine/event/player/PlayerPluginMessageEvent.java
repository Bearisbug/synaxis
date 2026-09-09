package dev.gameengine.event.player;

import dev.gameengine.entity.Player;
import dev.gameengine.event.trait.PlayerInstanceEvent;
import dev.gameengine.network.packet.client.common.ClientPluginMessagePacket;

import java.nio.charset.StandardCharsets;

/**
 * Called when a player send {@link ClientPluginMessagePacket}.
 */
public class PlayerPluginMessageEvent implements PlayerInstanceEvent {

    private final Player player;
    private final String identifier;
    private final byte[] message;

    public PlayerPluginMessageEvent(Player player, String identifier, byte[] message) {
        this.player = player;
        this.identifier = identifier;
        this.message = message;
    }

    /**
     * Gets the message identifier.
     *
     * @return the identifier
     */
    public String getIdentifier() {
        return identifier;
    }

    /**
     * Gets the message data as a byte array.
     *
     * @return the message
     */
    public byte[] getMessage() {
        return message;
    }

    /**
     * Gets the message data as a String.
     *
     * @return the message
     */
    public String getMessageString() {
        return new String(message, StandardCharsets.UTF_8);
    }

    @Override
    public Player getPlayer() {
        return player;
    }
}
