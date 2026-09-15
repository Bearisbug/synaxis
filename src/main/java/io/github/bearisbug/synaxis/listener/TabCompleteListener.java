package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.command.CommandSender;
import io.github.bearisbug.synaxis.command.builder.suggestion.Suggestion;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientTabCompletePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.TabCompletePacket;
import org.jetbrains.annotations.Nullable;

public class TabCompleteListener {

    /**
     * A space-allowing argument makes the command parser retry once per remaining word, so parsing cost grows
     * quadratically with the input length. {@link ClientTabCompletePacket#MAX_TEXT_LENGTH} is far above the 256
     * characters a command can ever be, and suggestions for text which could never be executed are worthless,
     * so anything longer is dropped instead of parsed on the tick thread.
     */
    private static final int MAX_SUGGESTION_LENGTH = 256;

    public static void listener(ClientTabCompletePacket packet, Player player) {
        final String text = packet.text();
        if (text.length() > MAX_SUGGESTION_LENGTH) return;
        final Suggestion suggestion = getSuggestion(player.process(), player, text);
        if (suggestion != null) {
            player.sendPacket(new TabCompletePacket(
                    packet.transactionId(),
                    suggestion.getStart(),
                    suggestion.getLength(),
                    suggestion.getEntries().stream()
                            .map(suggestionEntry -> new TabCompletePacket.Match(suggestionEntry.getEntry(), suggestionEntry.getTooltip()))
                            .toList())
            );
        }
    }

    public static @Nullable Suggestion getSuggestion(ServerProcess process, CommandSender commandSender, String text) {
        if (text.startsWith("/")) {
            text = text.substring(1);
        }
        if (text.endsWith(" ")) {
            // Append a placeholder char if the command ends with a space allowing the parser to find suggestion
            // for the next arg without typing the first char of it, this is probably the most hacky solution, but hey
            // it works as intended :)
            text = text + '\00';
        }
        return process.command().parseCommand(commandSender, text).suggestion(commandSender);
    }
}
