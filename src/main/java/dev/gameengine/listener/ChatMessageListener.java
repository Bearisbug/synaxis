package dev.gameengine.listener;

import dev.gameengine.MinecraftServer;
import dev.gameengine.command.CommandManager;
import dev.gameengine.entity.Player;
import dev.gameengine.event.EventDispatcher;
import dev.gameengine.event.player.PlayerChatEvent;
import dev.gameengine.message.ChatPosition;
import dev.gameengine.message.Messenger;
import dev.gameengine.monitoring.EventsJFR;
import dev.gameengine.network.ConnectionManager;
import dev.gameengine.network.packet.client.play.ClientChatMessagePacket;
import dev.gameengine.network.packet.client.play.ClientCommandChatPacket;
import dev.gameengine.network.packet.client.play.ClientSignedCommandChatPacket;

import java.util.Collection;

public class ChatMessageListener {
    private static final CommandManager COMMAND_MANAGER = MinecraftServer.getCommandManager();
    private static final ConnectionManager CONNECTION_MANAGER = MinecraftServer.getConnectionManager();

    public static void commandChatListener(ClientCommandChatPacket packet, Player player) {
        final String command = packet.message();
        EventsJFR.newPlayerCommand(player.getUuid(), command).commit();
        if (Messenger.canReceiveCommand(player)) {
            COMMAND_MANAGER.execute(player, command);
        } else {
            Messenger.sendRejectionMessage(player);
        }
    }

    public static void signedCommandChatListener(ClientSignedCommandChatPacket packet, Player player) {
        // Intentionally do the same thing as commandChatListener. We don't use signed commands, but Geyser
        // and Gate (the proxy) always send them so this is for compatibility with them.
        final String command = packet.message();
        EventsJFR.newPlayerCommand(player.getUuid(), command).commit();
        if (Messenger.canReceiveCommand(player)) {
            COMMAND_MANAGER.execute(player, command);
        } else {
            Messenger.sendRejectionMessage(player);
        }
    }

    public static void chatMessageListener(ClientChatMessagePacket packet, Player player) {
        final String message = packet.message();
        EventsJFR.newPlayerChat(player.getUuid(), message).commit();
        if (!Messenger.canReceiveMessage(player)) {
            Messenger.sendRejectionMessage(player);
            return;
        }

        final Collection<Player> players = CONNECTION_MANAGER.getOnlinePlayers();
        PlayerChatEvent playerChatEvent = new PlayerChatEvent(player, players, message);

        // Call the event
        EventDispatcher.callCancellable(playerChatEvent, () -> {
            final Collection<Player> recipients = playerChatEvent.getRecipients();

            if (!recipients.isEmpty()) {
                // delegate to the messenger to avoid sending messages we shouldn't be
                Messenger.sendMessage(
                        recipients,
                        playerChatEvent.getFormattedMessage(),
                        ChatPosition.CHAT);
            }
        });
    }
}
