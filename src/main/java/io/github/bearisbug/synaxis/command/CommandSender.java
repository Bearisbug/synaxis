package io.github.bearisbug.synaxis.command;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.identity.Identified;
import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.tag.Taggable;

/**
 * Represents something which can send commands to the server.
 * <p>
 * Main implementations are {@link Player} and {@link ConsoleSender}.
 */
public interface CommandSender extends Audience, Taggable, Identified {

    /**
     * Gets the server process this sender belongs to.
     *
     * @return the server process
     */
    ServerProcess process();

    /**
     * Sends a raw string message.
     *
     * @param message the message to send
     */
    default void sendMessage(String message) {
        this.sendMessage(Component.text(message));
    }

    /**
     * Sends multiple raw string messages.
     *
     * @param messages the messages to send
     */
    default void sendMessage(String [] messages) {
        for (String message : messages) {
            sendMessage(message);
        }
    }
}
