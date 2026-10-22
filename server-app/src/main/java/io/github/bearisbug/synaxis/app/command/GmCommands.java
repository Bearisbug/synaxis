package io.github.bearisbug.synaxis.app.command;

import io.github.bearisbug.synaxis.command.CommandSender;
import io.github.bearisbug.synaxis.entity.Player;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * What every GM command of the host does the same way ({@code docs/DESIGN-PROD.md} {@code API-PROD-011}): its
 * answer, and its {@code gm_command} event (§20), whose attributes are put together here alone, for the commands of
 * the console and the players and for the management methods.
 */
public final class GmCommands {

    private GmCommands() {
    }

    /**
     * Answers a command with a translatable system message.
     *
     * @param sender    who ran the command
     * @param key       the translation key
     * @param fallback  the text of a client without the key
     * @param arguments the arguments, each as plain text
     */
    public static void reply(CommandSender sender, String key, String fallback, String... arguments) {
        final Component[] components = new Component[arguments.length];
        for (int i = 0; i < arguments.length; i++) components[i] = Component.text(arguments[i]);
        sender.sendMessage(Component.translatable(key, fallback, components));
    }

    /**
     * Writes the {@code gm_command} event of a command a player or the console ran.
     *
     * @param analytics the event ledger
     * @param sender    who ran it, a player or the console
     * @param command   what was typed
     * @param target    the account or the network it was run on, null for none
     * @param result    how it ended
     */
    public static void record(BiConsumer<String, Map<String, ?>> analytics, CommandSender sender, String command,
                              @Nullable Object target, String result) {
        record(analytics, sender instanceof Player ? "player" : "console",
                sender instanceof Player player ? player.getUuid() : null, command, target, result);
    }

    /**
     * Writes one {@code gm_command} event.
     *
     * @param analytics the event ledger
     * @param actor     {@code player}, {@code console} or {@code management}
     * @param actorUuid the player, null for the console and the management interface
     * @param command   what was run
     * @param target    the account or the network it was run on, null for none
     * @param result    how it ended
     */
    public static void record(BiConsumer<String, Map<String, ?>> analytics, String actor, @Nullable UUID actorUuid,
                              String command, @Nullable Object target, String result) {
        final Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("actor", actor);
        attributes.put("actor_uuid", actorUuid);
        attributes.put("command", command);
        attributes.put("target", target);
        attributes.put("result", result);
        analytics.accept("gm_command", attributes);
    }
}
