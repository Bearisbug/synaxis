package io.github.bearisbug.synaxis.message;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.packet.server.play.SystemChatPacket;
import io.github.bearisbug.synaxis.network.player.ClientSettings;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Messenger} sends a {@link ChatPosition#GAME_INFO} message above the hot bar, which a
 * player with the chat turned off still sees, and keeps the chat positions out of that chat.
 */
@EnvTest
public class MessengerIntegrationTest {

    @Test
    public void gameInfoGoesAboveTheHotBarEvenWithTheChatOff(Env env) {
        final var connection = env.createConnection();
        final Player player = connection.connect(env.createFlatInstance(), new Pos(0, 40, 0));
        final ClientSettings settings = player.getSettings();
        player.refreshSettings(new ClientSettings(settings.locale(), settings.viewDistance(), ChatMessageType.NONE,
                settings.chatColors(), settings.displayedSkinParts(), settings.mainHand(),
                settings.enableTextFiltering(), settings.allowServerListings(), settings.particleSetting()));
        final var collector = connection.trackIncoming(SystemChatPacket.class);

        assertTrue(Messenger.sendMessage(player, Component.text("above the hot bar"), ChatPosition.GAME_INFO));
        assertFalse(Messenger.sendMessage(player, Component.text("in the chat"), ChatPosition.SYSTEM_MESSAGE));
        Messenger.sendMessage(List.of(player), Component.text("to a group"), ChatPosition.GAME_INFO);

        assertEquals(List.of(new SystemChatPacket(Component.text("above the hot bar"), true),
                new SystemChatPacket(Component.text("to a group"), true)), collector.collect());
    }

    @Test
    public void theOtherPositionsGoToTheChat(Env env) {
        final var connection = env.createConnection();
        final Player player = connection.connect(env.createFlatInstance(), new Pos(0, 40, 0));
        final var collector = connection.trackIncoming(SystemChatPacket.class);

        assertTrue(Messenger.sendMessage(player, Component.text("system"), ChatPosition.SYSTEM_MESSAGE));
        Messenger.sendMessage(List.of(player), Component.text("chat"), ChatPosition.CHAT);

        assertEquals(List.of(new SystemChatPacket(Component.text("system"), false),
                new SystemChatPacket(Component.text("chat"), false)), collector.collect());
    }
}
