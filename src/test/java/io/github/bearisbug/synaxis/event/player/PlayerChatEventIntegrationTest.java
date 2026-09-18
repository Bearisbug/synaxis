package io.github.bearisbug.synaxis.event.player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class PlayerChatEventIntegrationTest {

    @Test
    public void theDefaultMessageIsTheVanillaChatTranslation(Env env) {
        var instance = env.createEmptyInstance();
        var player = env.createPlayer(instance, Pos.ZERO);
        var event = new PlayerChatEvent(player, List.of(player), "hello");

        assertEquals("hello", event.getRawMessage());
        var message = assertInstanceOf(TranslatableComponent.class, event.getFormattedMessage());
        assertEquals("chat.type.text", message.key());
        assertEquals(2, message.arguments().size());

        var sender = assertInstanceOf(TextComponent.class, message.arguments().getFirst().asComponent());
        assertEquals(player.getUsername(), sender.content());
        assertEquals(player.getUsername(), sender.insertion(), "Shift-clicking the name inserts it");
        assertNotNull(sender.hoverEvent(), "Hovering the name shows the player");

        assertEquals(Component.text("hello"), message.arguments().get(1).asComponent());
    }

    @Test
    public void theFormattedMessageCanBeReplaced(Env env) {
        var instance = env.createEmptyInstance();
        var player = env.createPlayer(instance, Pos.ZERO);
        var event = new PlayerChatEvent(player, List.of(player), "hello");

        event.setFormattedMessage(Component.text("rewritten"));
        assertEquals(Component.text("rewritten"), event.getFormattedMessage());
    }

    @Test
    public void theRecipientListIsACopyThatListenersMayEdit(Env env) {
        var instance = env.createEmptyInstance();
        var first = env.createPlayer(instance, Pos.ZERO);
        var second = env.createPlayer(instance, Pos.ZERO);
        var recipients = List.of(first, second);
        var event = new PlayerChatEvent(first, recipients, "hello");

        event.getRecipients().remove(second);
        assertEquals(List.of(first), List.copyOf(event.getRecipients()));
        assertEquals(2, recipients.size(), "The collection given to the event must not be mutated");
    }

    @Test
    public void theChatCanBeCancelled(Env env) {
        var instance = env.createEmptyInstance();
        var player = env.createPlayer(instance, Pos.ZERO);
        var event = new PlayerChatEvent(player, List.of(player), "hello");

        assertFalse(event.isCancelled());
        event.setCancelled(true);
        assertTrue(event.isCancelled());
    }
}
