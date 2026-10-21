package io.github.bearisbug.synaxis.event.player;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Called when a player die in {@link Player#kill()}.
 * <p>
 * The death text is shown on the death screen of the player, the chat message goes to every player of the instance
 * the player died in, the dead one included. Both come from the last damage, or are the vanilla
 * {@code death.attack.generic} message for a player killed without any.
 */
public class PlayerDeathEvent implements PlayerInstanceEvent {

    private final Player player;
    private Component deathText;
    private Component chatMessage;

    public PlayerDeathEvent(Player player, Component deathText, Component chatMessage) {
        this.player = player;
        this.deathText = deathText;
        this.chatMessage = chatMessage;
    }

    /**
     * Gets the text displayed in the death screen.
     *
     * @return the death text, can be null
     */
    public @Nullable Component getDeathText() {
        return deathText;
    }

    /**
     * Changes the text displayed in the death screen.
     *
     * @param deathText the death text to display; null sends no death screen at all, the client then stays in the
     *                  world dead, {@link Component#empty()} shows the screen without a text
     */
    public void setDeathText(@Nullable Component deathText) {
        this.deathText = deathText;
    }

    /**
     * Gets the message sent to the chat of the players of the instance the player died in.
     *
     * @return the death chat message, null if none is sent
     */
    public @Nullable Component getChatMessage() {
        return chatMessage;
    }

    /**
     * Changes the text sent to the chat of the players of the instance the player died in. To send it to other
     * players, set null and send it yourself.
     *
     * @param chatMessage the death message to send, null to remove
     */
    public void setChatMessage(@Nullable Component chatMessage) {
        this.chatMessage = chatMessage;
    }

    @Override
    public Player getPlayer() {
        return player;
    }
}
