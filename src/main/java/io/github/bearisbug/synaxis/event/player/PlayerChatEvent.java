package io.github.bearisbug.synaxis.event.player;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Called every time a {@link Player} writes and sends something in the chat.
 * <p>
 * The engine calls it with no recipients: unless a listener adds players to {@link #getRecipients()},
 * the message goes to nobody. A host may fill them in with a listener of its own before the plugins
 * see the event ({@code docs/DESIGN-PROD.md} {@code API-PROD-008}). Cancelling it sends the message to
 * nobody, whoever the recipients are, and the final message can be changed.
 */
public class PlayerChatEvent implements PlayerInstanceEvent, CancellableEvent {
    private final Player player;
    private final List<Player> recipients;
    private final String rawMessage;
    private Component formattedMessage;
    private boolean cancelled;

    public PlayerChatEvent(Player player, Collection<Player> recipients,
                           String rawMessage) {
        this.player = player;
        this.recipients = new ArrayList<>(recipients);
        this.rawMessage = rawMessage;
        formattedMessage = buildDefaultChatMessage();
    }

    /**
     * Returns the players who will receive the message.
     * <p>
     * The engine starts the event with none; it can be modified to add and remove recipients.
     *
     * @return a modifiable list of the message's targets
     */
    @SuppressWarnings("PreferredInterfaceType") // wider type kept for binary compatibility until the next breaking release
    public Collection<Player> getRecipients() {
        return recipients;
    }

    /**
     * Gets the original message content sent by the player.
     *
     * @return the sender's message
     */
    public String getRawMessage() {
        return rawMessage;
    }

    /**
     * Gets the final message component that will be sent.
     *
     * @return the chat message component
     */
    public Component getFormattedMessage() {
        return formattedMessage;
    }

    /**
     * Used to change the final message component.
     *
     * @param message the new message component
     */
    public void setFormattedMessage(Component message) {
        formattedMessage = message;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    private Component buildDefaultChatMessage() {
        return Component.translatable("chat.type.text")
                .arguments(
                        Component.text(player.getUsername())
                                .insertion(player.getUsername())
                                .hoverEvent(player),
                        Component.text(rawMessage));
    }
}
