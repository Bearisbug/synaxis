package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.plugin.LogBudget;
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
 * Each event is appended on its own, without keeping a writer open. What holds that up is that no
 * caller lets a client set the rate: the host's own events happen once per process or once per
 * session, and the order events of §20 are capped per connection by the order budget of §15 — the
 * two refusals a client can repeat at will, {@code stale} and {@code rate-limited}, are left out of
 * the ledger for that reason. A caller that recorded once per inbound payload would make the per
 * event open and close the bottleneck, and this would have to buffer first. Registered as debt in
 * {@code docs/DESIGN.md} §28.
 * <p>
 * The rate is not the host's to choose, though: {@code PluginAnalytics} hands this method to every
 * plugin and every plugin records from its own inbound path, so a directory that cannot be written
 * to reports through a {@link LogBudget} rather than once per event
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
