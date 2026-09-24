package io.github.bearisbug.synaxis.baker;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * The baker's two analytics events ({@code docs/DESIGN-M3.md} §20), one JSON object per line appended to
 * {@code <logs>/events-YYYYMMDD.jsonl} (UTC day). The baker writes two lines per run, so each is written and
 * flushed on its own; a failure to write is reported on stderr and does not change the bake's outcome.
 */
final class BakeEvents {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final Path logDir;

    BakeEvents(Path logDir) {
        this.logDir = logDir;
    }

    void record(String event, JsonObject properties) {
        final Instant now = Instant.now();
        final JsonObject line = new JsonObject();
        line.addProperty("event", event);
        line.addProperty("ts", now.toString());
        line.addProperty("shard_id", "baker");
        for (String key : properties.keySet()) line.add(key, properties.get(key));
        try {
            Files.createDirectories(logDir);
            Files.writeString(logDir.resolve("events-" + DAY.format(now) + ".jsonl"), line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException e) {
            System.err.println("baker: could not write event " + event + " to " + logDir + ": " + e);
        }
    }
}
