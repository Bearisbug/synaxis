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
 * What the seven commands of the host ({@code docs/DESIGN-PROD.md} {@code API-PROD-011}) answer a
 * player are {@code translate} keys; the player only reads a sentence for them if the mod ships
 * text for each.
 */
class HostCommandTextTest {

    @ParameterizedTest
    @ValueSource(strings = {"en_us", "zh_cn"})
    void everyAnswerOfTheHostCommandsHasTextInTheModJar(String language) throws IOException {
        final String translations;
        try (InputStream stream = HostCommandTextTest.class.getResourceAsStream(
                "/assets/synaxis/lang/" + language + ".json")) {
            assertNotNull(stream, "the mod does not ship " + language);
            translations = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (final String key : List.of("synaxis:command-list", "synaxis:command-no-player", "synaxis:command-kicked",
                "synaxis:command-broadcast", "synaxis:command-saved", "synaxis:command-stopping",
                "synaxis:command-stop-usage", "synaxis:command-already-stopping", "synaxis:command-tps",
                "synaxis:command-profile-started", "synaxis:command-profile-done", "synaxis:command-profile-busy",
                "synaxis:command-profile-usage", "synaxis:command-profile-failed")) {
            assertTrue(translations.contains('"' + key + '"'), language + " has no text for " + key);
        }
    }
}
