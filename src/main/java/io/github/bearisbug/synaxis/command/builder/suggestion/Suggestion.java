package io.github.bearisbug.synaxis.command.builder.suggestion;

import java.util.ArrayList;
import java.util.List;

/**
 * The completions a {@link SuggestionCallback} offers for the argument being typed, sent back to the client.
 * <p>
 * The start and the length are offsets into the text the client sent, its leading {@code /} included. By default
 * they cover the last word, which the client replaces with the entry the player picks; after a trailing space the
 * length counts the placeholder the parsed input ends with, one past the end of the text of the client, which it
 * tolerates.
 */
public class Suggestion {

    private final String input;
    private int start;
    private int length;
    private final List<SuggestionEntry> suggestionEntries = new ArrayList<>();

    public Suggestion(String input, int start, int length) {
        this.input = input;
        this.start = start;
        this.length = length;
    }

    /**
     * The command as the parser read it: the text of the client without its leading {@code /}, and after a trailing
     * space with a placeholder character appended.
     *
     * @return the parsed input
     */
    public String getInput() {
        return input;
    }

    /**
     * Where the range the client replaces starts.
     *
     * @return an offset into the text of the client, its leading {@code /} included
     */
    public int getStart() {
        return start;
    }

    /**
     * Moves where the range the client replaces starts.
     *
     * @param start an offset into the text of the client, its leading {@code /} included, not into
     *              {@link #getInput()}
     */
    public void setStart(int start) {
        this.start = start;
    }

    public int getLength() {
        return length;
    }

    public void setLength(int length) {
        this.length = length;
    }

    public List<SuggestionEntry> getEntries() {
        return suggestionEntries;
    }

    public void addEntry(SuggestionEntry entry) {
        this.suggestionEntries.add(entry);
    }

}
