package io.github.bearisbug.synaxis.app;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ledger is handed to every plugin as {@code PluginAnalytics}, and {@code docs/DESIGN-M2.md}
 * §20 asks a plugin for one event per inbound order packet, so the rate of {@code record} is the
 * client's to pick. A directory it cannot write to must therefore not cost a line per event.
 */
class EventLedgerTest {

    /** Big enough that a line per event is unmistakable, small enough to stay a unit of work. */
    private static final int FLOOD = 10_000;

    @Test
    void aFloodOfEventsIntoAnUnwritableLedgerWritesOneLine(@TempDir Path directory) throws IOException {
        // A regular file where the ledger wants its directory, so every append fails the same way
        final Path blocked = directory.resolve("logs");
        Files.writeString(blocked, "not a directory");
        final EventLedger ledger = new EventLedger(blocked);

        try (LogCapture logs = LogCapture.of(EventLedger.class)) {
            for (int i = 0; i < FLOOD; i++) {
                ledger.record("unit_order_issued", Map.of("order_seq", i));
            }

            final List<ILoggingEvent> warnings = logs.eventsAt(Level.WARN);
            assertEquals(1, warnings.size(),
                    "a plugin records one of these per inbound order packet, so the client sets the rate");
            assertTrue(warnings.getFirst().getFormattedMessage().contains("since the last report"),
                    warnings.getFirst().getFormattedMessage());
            assertNotNull(warnings.getFirst().getThrowableProxy(),
                    "the one report that is written has to say why the append failed");
        }
    }
}
