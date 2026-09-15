package io.github.bearisbug.synaxis.event.book;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.ItemEvent;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;
import io.github.bearisbug.synaxis.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

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

    public @Nullable String getTitle() {
        return title;
    }

    public boolean isSigned() {
        return title != null;
    }
}
