package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientPluginMessagePacket;

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
     * @return the message: the array of the packet itself, shared with the listeners after this one
     * and with the channel handler the host hands it to, so read it only and copy it first to make
     * changes
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
