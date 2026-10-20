package io.github.bearisbug.synaxis.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.packet.server.play.SystemChatPacket;
import io.github.bearisbug.synaxis.network.PacketSendingUtils;

import java.util.Collection;

/**
 * Utility class to handle client chat settings. A {@link ChatPosition#GAME_INFO} message is shown
 * above the hot bar (an overlay), the other positions go to the chat.
 */
public final class Messenger {
    /**
     * The message sent to the client if they send a chat message but it is rejected by the server.
     */
    public static final Component CANNOT_SEND_MESSAGE = Component.translatable("chat.cannotSend", NamedTextColor.RED);
    private static final SystemChatPacket CANNOT_SEND_PACKET = new SystemChatPacket(CANNOT_SEND_MESSAGE, false);

    /**
     * Sends a message to a player, respecting their chat settings.
     *
     * @param player   the player
     * @param message  the message
     * @param position the position
     * @return if the message was sent
     */
    public static boolean sendMessage(Player player, Component message, ChatPosition position) {
        if (player.getSettings().chatMessageType().accepts(position)) {
            player.sendPacket(new SystemChatPacket(message, position == ChatPosition.GAME_INFO));
            return true;
        }
        return false;
    }

    /**
     * Sends a message to some players, respecting their chat settings.
     *
     * @param players  the players
     * @param message  the message
     * @param position the position
     */
    public static void sendMessage(Collection<? extends Player> players, Component message, ChatPosition position) {
        PacketSendingUtils.sendGroupedPacket(players, new SystemChatPacket(message, position == ChatPosition.GAME_INFO),
                player -> player.getSettings().chatMessageType().accepts(position));
    }

    /**
     * Checks if the server should receive messages from a player, given their chat settings.
     *
     * @param player the player
     * @return if the server should receive messages from them
     */
    public static boolean canReceiveMessage(Player player) {
        return player.getSettings().chatMessageType() == ChatMessageType.FULL;
    }

    /**
     * Checks if the server should receive commands from a player, given their chat settings.
     *
     * @param player the player
     * @return if the server should receive commands from them
     */
    public static boolean canReceiveCommand(Player player) {
        return player.getSettings().chatMessageType() != ChatMessageType.NONE;
    }

    /**
     * Sends a message to the player informing them we are rejecting their message or command.
     *
     * @param player the player
     */
    public static void sendRejectionMessage(Player player) {
        player.sendPacket(CANNOT_SEND_PACKET);
    }
}
