package dev.gameengine.command;

import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import dev.gameengine.ServerProcess;
import dev.gameengine.tag.TagHandler;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class CommandSenderTest {

    @Test
    public void testMessageSending() {
        SenderTest sender = new SenderTest();

        assertNull(sender.getMostRecentMessage());

        sender.sendMessage("Hey!!");
        assertEquals(sender.getMostRecentMessage(), Component.text("Hey!!"));

        sender.sendMessage(new String[]{"Message", "Sending", "Test"});
        assertEquals(sender.getMostRecentMessage(), Component.text("Test"));

        sender.sendMessage(Component.text("Message test!", NamedTextColor.GREEN));
        assertEquals(sender.getMostRecentMessage(), Component.text("Message test!", NamedTextColor.GREEN));
    }

    private static final class SenderTest implements CommandSender {

        private final TagHandler handler = TagHandler.newHandler();

        @Override
        public ServerProcess process() {
            throw new UnsupportedOperationException("this sender is not attached to a process");
        }

        private @Nullable Component mostRecentMessage = null;

        @Override
        public TagHandler tagHandler() {
            return handler;
        }

        @Override
        public void sendMessage(Component message) {
            mostRecentMessage = message;
        }

        @Nullable Component getMostRecentMessage() {
            return mostRecentMessage;
        }

        @Override
        public Identity identity() {
            return Identity.nil();
        }
    }
}
