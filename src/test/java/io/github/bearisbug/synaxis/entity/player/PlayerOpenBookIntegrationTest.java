package io.github.bearisbug.synaxis.entity.player;

import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.item.component.WrittenBookContent;
import io.github.bearisbug.synaxis.network.packet.server.play.OpenBookPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SetSlotPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code Player#openBook} hands the client a written book, whose title holds at most 32 characters: a longer title
 * of the book is cut, the client does not show it on an open book anyway.
 */
@EnvTest
public class PlayerOpenBookIntegrationTest {

    @Test
    public void aBookWithATitleOverThirtyTwoCharactersOpens(Env env) {
        final var instance = env.createFlatInstance();
        final var connection = env.createConnection();
        final var player = connection.connect(instance, new Pos(0, 40, 0));
        final var slots = connection.trackIncoming(SetSlotPacket.class);
        final var opened = connection.trackIncoming(OpenBookPacket.class);
        final String title = "A title of forty characters, 32 is all.";

        player.openBook(Book.book(Component.text(title + "!"), Component.text("Author"),
                List.of(Component.text("page"))));

        opened.assertSingle();
        final List<SetSlotPacket> packets = slots.collect();
        final WrittenBookContent content = Objects.requireNonNull(
                packets.getFirst().itemStack().get(DataComponents.WRITTEN_BOOK_CONTENT), "the book in the offhand");
        assertEquals(title.substring(0, 32), content.title().text());
        assertEquals("Author", content.author());
    }
}
