package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.crypto.ArgumentSignatures;
import io.github.bearisbug.synaxis.crypto.LastSeenMessages;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.EventFilter;
import io.github.bearisbug.synaxis.event.player.PlayerChatEvent;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientChatMessagePacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientCommandChatPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientSignedCommandChatPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.DisconnectPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.JoinGamePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SystemChatPacket;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-PROD-009 (docs/DESIGN-PROD.md API-PROD-008): the engine sends a chat message to nobody unless
 * a listener names the recipients, each connection is held to 1 message a second with a burst of 3,
 * and the client is told secure chat is not enforced.
 */
@EnvTest
public class ChatMessageListenerIntegrationTest {

    private static final int ONLINE = 50;

    @Test
    public void withoutAListenerNobodyReceivesTheMessage(Env env) {
        var instance = env.createEmptyInstance();
        final List<Player> players = new ArrayList<>();
        final List<Collector<SystemChatPacket>> received = new ArrayList<>();
        for (int i = 0; i < ONLINE; i++) {
            final TestConnection connection = env.createConnection();
            players.add(connection.connect(instance, Pos.ZERO));
            received.add(connection.trackIncoming(SystemChatPacket.class));
        }
        final Player sender = players.getFirst();
        var events = env.trackEvent(PlayerChatEvent.class, EventFilter.PLAYER, sender);

        sender.addPacketToQueue(chat("hello everyone"));
        sender.interpretPacketQueue();

        final int sent = received.stream().mapToInt(collector -> collector.collect().size()).sum();
        assertEquals(0, sent, "outbound chat packets for one message with " + ONLINE + " online");
        events.assertSingle(event -> assertTrue(event.getRecipients().isEmpty(),
                "the event starts with no recipients, got " + event.getRecipients().size()));
    }

    @Test
    public void onlyTheRecipientsAListenerNamesReceiveIt(Env env) {
        var instance = env.createEmptyInstance();
        var senderConnection = env.createConnection();
        var sender = senderConnection.connect(instance, Pos.ZERO);
        var recipientConnection = env.createConnection();
        var recipient = recipientConnection.connect(instance, Pos.ZERO);
        var bystanderConnection = env.createConnection();
        bystanderConnection.connect(instance, Pos.ZERO);
        var toSender = senderConnection.trackIncoming(SystemChatPacket.class);
        var toRecipient = recipientConnection.trackIncoming(SystemChatPacket.class);
        var toBystander = bystanderConnection.trackIncoming(SystemChatPacket.class);
        final AtomicReference<Component> formatted = new AtomicReference<>();
        env.listen(PlayerChatEvent.class).followup(event -> {
            event.getRecipients().add(recipient);
            formatted.set(event.getFormattedMessage());
        });

        sender.addPacketToQueue(chat("psst"));
        sender.interpretPacketQueue();

        toRecipient.assertSingle(packet -> {
            assertFalse(packet.overlay(), "chat goes to the chat box, not the action bar");
            assertEquals(formatted.get(), packet.message());
        });
        toSender.assertEmpty();
        toBystander.assertEmpty();
    }

    @Test
    public void tenMessagesInOneSecondLetThreeThroughAndKeepTheConnection(Env env) {
        var instance = env.createEmptyInstance();
        var sender = env.createPlayer(instance, Pos.ZERO);
        var events = env.trackEvent(PlayerChatEvent.class, EventFilter.PLAYER, sender);

        for (int i = 0; i < 10; i++) sender.addPacketToQueue(chat("spam " + i));
        sender.interpretPacketQueue();

        events.assertCount(3);
        assertEquals(7, sender.chatRateLimiter().dropped());
        assertTrue(sender.isOnline(), "a chat flood is dropped, not kicked");
    }

    /**
     * Chat and commands take the characters the vanilla server takes: a section sign, a control
     * character or DEL ends the connection with {@code multiplayer.disconnect.illegal_characters}
     * before the message reaches a listener, so the recipients a listener names get nothing. Any
     * other character, outside ASCII included, goes through.
     */
    @Test
    public void illegalCharactersEndTheConnection(Env env) {
        var instance = env.createEmptyInstance();
        var recipientConnection = env.createConnection();
        var recipient = recipientConnection.connect(instance, Pos.ZERO);
        var toRecipient = recipientConnection.trackIncoming(SystemChatPacket.class);
        env.process().eventHandler().addListener(PlayerChatEvent.class, event -> event.getRecipients().add(recipient));
        final List<ClientPacket> illegal = List.of(chat("hi\n[Server] restarting in 5 minutes"), chat("\u00a7cred"),
                chat("del\u007f"), new ClientCommandChatPacket("list\n"),
                new ClientSignedCommandChatPacket("list\u00a7", 0L, 0L, new ArgumentSignatures(List.of()),
                        new LastSeenMessages.Update(0, new BitSet()), (byte) 0));
        for (ClientPacket packet : illegal) {
            var senderConnection = env.createConnection();
            var sender = senderConnection.connect(instance, Pos.ZERO);
            var disconnects = senderConnection.trackIncoming(DisconnectPacket.class);

            sender.addPacketToQueue(packet);
            sender.interpretPacketQueue();

            assertFalse(sender.isOnline(), packet + " left the connection open");
            disconnects.assertSingle(disconnect -> assertEquals("multiplayer.disconnect.illegal_characters",
                    assertInstanceOf(TranslatableComponent.class, disconnect.message()).key()));
        }
        toRecipient.assertEmpty();

        var legal = recipientConnection.trackIncoming(SystemChatPacket.class);
        var sender = env.createPlayer(instance, Pos.ZERO);
        sender.addPacketToQueue(chat("na\u00efve \u4f60\u597d \u2713"));
        sender.interpretPacketQueue();
        assertTrue(sender.isOnline());
        legal.assertSingle();
    }

    @Test
    public void joinGameDoesNotEnforceSecureChat(Env env) {
        var instance = env.createEmptyInstance();
        var connection = env.createConnection();
        var joins = connection.trackIncoming(JoinGamePacket.class);
        connection.connect(instance, Pos.ZERO);

        joins.assertSingle(packet -> {
            assertFalse(packet.enforcesSecureChat(), "chat is relayed as system messages, never signed");
            assertTrue(packet.onlineMode(), "online mode stays on for the tablist heads");
        });
    }

    private static ClientChatMessagePacket chat(String message) {
        return new ClientChatMessagePacket(message, System.currentTimeMillis(), 0L, null, 0, new BitSet(), (byte) 0);
    }
}
