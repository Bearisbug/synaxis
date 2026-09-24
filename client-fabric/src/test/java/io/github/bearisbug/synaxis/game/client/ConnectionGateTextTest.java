package io.github.bearisbug.synaxis.game.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The disconnect reasons of the connection gate, of the keep alive and of the outbound backlog
 * ({@code docs/DESIGN-PROD.md} §14, {@code REQ-PROD-001}, {@code REQ-PROD-043}, {@code REQ-PROD-025})
 * are {@code translate} keys; the disconnect screen only reads as a sentence if the mod ships text
 * for each of them.
 */
class ConnectionGateTextTest {

    @ParameterizedTest
    @ValueSource(strings = {"en_us", "zh_cn"})
    void everyReasonTheGateCanGiveHasTextInTheModJar(String language) throws IOException {
        final String translations;
        try (InputStream stream = ConnectionGateTextTest.class.getResourceAsStream(
                "/assets/synaxis/lang/" + language + ".json")) {
            assertNotNull(stream, "the mod does not ship " + language);
            translations = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (final String key : List.of("synaxis:too-many-connections", "synaxis:login-throttled",
                "synaxis:shard-full", "synaxis:keep-alive-timeout", "synaxis:outbound-backlog")) {
            assertTrue(translations.contains('"' + key + '"'), language + " has no text for " + key);
        }
    }
}
