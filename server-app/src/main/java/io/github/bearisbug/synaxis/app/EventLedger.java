package io.github.bearisbug.synaxis.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * The local analytics ledger of {@code docs/DESIGN-M2.md} §20: one JSON object per line in
 * {@code logs/events-YYYYMMDD.jsonl}.
 * <p>
 * Each event is appended on its own, without keeping a writer open, because M2 only records
 * process lifetime events. A higher rate event would need buffering first.
 * <p>
 * The rate is not the host's to choose, though: {@code PluginAnalytics} hands this method to every
 * plugin, and §20 asks for one event per inbound order packet, so a directory that cannot be
 * written to reports through a {@link LogBudget} rather than once per event
 * ({@code docs/DESIGN.md} §15).
 */
final class EventLedger {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventLedger.class);

    private static final DateTimeFormatter FILE_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.systemDefault());

    private final Path directory;

    /**
     * The budget of the append failure. One for the whole ledger: an unwritable directory is one
     * fact about the file, not one per event that ran into it.
     */
    private final LogBudget failures = new LogBudget(LOGGER, "events");

    EventLedger(Path directory) {
        this.directory = directory;
    }

    /**
     * Appends one event.
     *
     * @param event      the event name, without the {@code EVT:} prefix the design document uses
     * @param attributes the attributes of that event, written in iteration order
     */
    void record(String event, Map<String, ?> attributes) {
        final Instant now = Instant.now();
        final StringBuilder line = new StringBuilder(128);
        line.append("{\"ts\":\"").append(now).append("\",\"event\":\"").append(event).append('"');
        for (Map.Entry<String, ?> attribute : attributes.entrySet()) {
            line.append(",\"").append(attribute.getKey()).append("\":");
            appendValue(line, attribute.getValue());
        }
        line.append('}').append(System.lineSeparator());
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("events-" + FILE_DATE.format(now) + ".jsonl"), line,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            failures.warn(e, "Could not append to the event ledger: event={}", event);
        }
    }

    private static void appendValue(StringBuilder line, Object value) {
        if (value instanceof Number number) {
            line.append(number);
            return;
        }
        line.append('"');
        final String text = String.valueOf(value);
        for (int i = 0; i < text.length(); i++) {
            final char character = text.charAt(i);
            switch (character) {
                case '"' -> line.append("\\\"");
                case '\\' -> line.append("\\\\");
                case '\n' -> line.append("\\n");
                case '\r' -> line.append("\\r");
                case '\t' -> line.append("\\t");
                default -> {
                    if (character < ' ') line.append("\\u%04x".formatted((int) character));
                    else line.append(character);
                }
            }
        }
        line.append('"');
    }
}
