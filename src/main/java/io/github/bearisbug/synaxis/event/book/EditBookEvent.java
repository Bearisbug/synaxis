package io.github.bearisbug.synaxis.event.book;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.ItemEvent;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientEditBookPacket;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Called when the client submits a book edit for a hotbar or off-hand slot.
 * <p>
 * Not cancellable. The engine does not modify the item: a listener that wants to keep the edit writes the pages and
 * the title into that slot itself. {@link #getItemStack()} is the item currently in the slot and is not guaranteed
 * to be a writable book. The pages and the title come from the client and are only bounded by
 * {@link ClientEditBookPacket#MAX_PAGES}, {@link ClientEditBookPacket#MAX_PAGE_LENGTH} and
 * {@link ClientEditBookPacket#MAX_TITLE_LENGTH}.
 */
public class EditBookEvent implements PlayerInstanceEvent, ItemEvent {

    private final Player player;
    private final ItemStack itemStack;
    private final List<String> pages;
    private final @Nullable String title;

    public EditBookEvent(
            Player player,
            ItemStack itemStack,
            List<String> pages,
            @Nullable String title
    ) {
        this.player = player;
        this.itemStack = itemStack;
        this.pages = pages;
        this.title = title;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    @Override
    public ItemStack getItemStack() {
        return itemStack;
    }

    public List<String> getPages() {
        return pages;
    }

    /**
     * Gets the title the book is signed with.
     *
     * @return the title, null when the book is not signed
     */
    public @Nullable String getTitle() {
        return title;
    }

    public boolean isSigned() {
        return title != null;
    }
}
