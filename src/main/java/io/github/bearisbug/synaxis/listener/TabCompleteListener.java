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

    /**
     * The completions for a command being typed. The text loses its leading {@code /}, and after a trailing space a
     * placeholder character is appended so that the next argument is parsed without its first character typed. The
     * offsets the parser gives the {@link Suggestion} rely on both steps: they count the {@code /} back in, and the
     * placeholder in the length.
     *
     * @param text the text of the client, as typed
     * @return the completions, null if the command is unknown or the argument at the cursor has no suggestion callback
     */
    public static @Nullable Suggestion getSuggestion(ServerProcess process, CommandSender commandSender, String text) {
        if (text.startsWith("/")) {
            text = text.substring(1);
        }
        if (text.endsWith(" ")) {
            // The parser keeps the placeholder out of the arguments it hands the suggestion callback (CommandParserImpl)
            text = text + '\00';
        }
        return process.command().parseCommand(commandSender, text).suggestion(commandSender);
    }
}
