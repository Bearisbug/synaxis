package io.github.bearisbug.synaxis.listener;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerChatEvent;
import io.github.bearisbug.synaxis.message.ChatPosition;
import io.github.bearisbug.synaxis.message.Messenger;
import io.github.bearisbug.synaxis.monitoring.EventsJFR;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientChatMessagePacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientCommandChatPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientSignedCommandChatPacket;

import java.util.Collection;
import java.util.List;

public class ChatMessageListener {
    private static final Component ILLEGAL_CHARACTERS = Component.translatable("multiplayer.disconnect.illegal_characters");

    public static void commandChatListener(ClientCommandChatPacket packet, Player player) {
        executeCommand(player, packet.message());
    }

    public static void signedCommandChatListener(ClientSignedCommandChatPacket packet, Player player) {
        // Intentionally do the same thing as commandChatListener. We don't use signed commands, but Geyser
        // and Gate (the proxy) always send them so this is for compatibility with them.
        executeCommand(player, packet.message());
    }

    private static void executeCommand(Player player, String command) {
        if (!isAllowedChatText(command)) {
            player.kick(ILLEGAL_CHARACTERS);
            return;
        }
        EventsJFR.newPlayerCommand(player.getUuid(), command).commit();
        if (Messenger.canReceiveCommand(player)) {
            player.process().command().execute(player, command);
        } else {
            Messenger.sendRejectionMessage(player);
        }
    }

    public static void chatMessageListener(ClientChatMessagePacket packet, Player player) {
        final String message = packet.message();
        if (!isAllowedChatText(message)) {
            player.kick(ILLEGAL_CHARACTERS);
            return;
        }
        // Over the connection's rate the message is dropped and counted, and the player stays.
        if (!player.chatRateLimiter().tryAcquire(System.nanoTime())) return;
        EventsJFR.newPlayerChat(player.getUuid(), message).commit();
        if (!Messenger.canReceiveMessage(player)) {
            Messenger.sendRejectionMessage(player);
            return;
        }

        // Nobody receives a chat message unless a listener adds them: the channel, the instance or
        // the whisper target is the plugins' decision (docs/DESIGN-PROD.md API-PROD-008).
        PlayerChatEvent playerChatEvent = new PlayerChatEvent(player, List.of(), message);

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

    /**
     * Whether a chat message or a command holds only characters the vanilla server takes: no section
     * sign, which the client reads as a formatting code, no control character, a line break among
     * them, and no DEL (docs/DESIGN-PROD.md API-PROD-008). The vanilla client cannot type any of them.
     */
    private static boolean isAllowedChatText(String text) {
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == '\u00a7' || c < ' ' || c == '\u007f') return false;
        }
        return true;
    }
}
