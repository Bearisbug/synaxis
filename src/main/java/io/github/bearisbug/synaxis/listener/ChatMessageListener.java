package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerChatEvent;
import io.github.bearisbug.synaxis.message.ChatPosition;
import io.github.bearisbug.synaxis.message.Messenger;
import io.github.bearisbug.synaxis.monitoring.EventsJFR;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientChatMessagePacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientCommandChatPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientSignedCommandChatPacket;

import java.util.Collection;

public class ChatMessageListener {
    public static void commandChatListener(ClientCommandChatPacket packet, Player player) {
        final String command = packet.message();
        EventsJFR.newPlayerCommand(player.getUuid(), command).commit();
        if (Messenger.canReceiveCommand(player)) {
            player.process().command().execute(player, command);
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
            player.process().command().execute(player, command);
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

        final Collection<Player> players = player.process().connection().getOnlinePlayers();
        PlayerChatEvent playerChatEvent = new PlayerChatEvent(player, players, message);

        // Call the event
        player.process().eventHandler().callCancellable(playerChatEvent, () -> {
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
