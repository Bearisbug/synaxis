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
 * What the player store and the transfers of {@code docs/DESIGN-PROD.md} say to a player are
 * {@code translate} keys: the refused transfer (§14 {@code synaxis:transfer-rejected}), the transfer
 * that could not be saved, and the shutdown countdown. The client only shows a sentence for them if
 * the mod ships text for each.
 */
class PlayerStoreTextTest {

    @ParameterizedTest
    @ValueSource(strings = {"en_us", "zh_cn"})
    void everyStoreAndTransferMessageHasTextInTheModJar(String language) throws IOException {
        final String translations;
        try (InputStream stream = PlayerStoreTextTest.class.getResourceAsStream(
                "/assets/synaxis/lang/" + language + ".json")) {
            assertNotNull(stream, "the mod does not ship " + language);
            translations = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (final String key : List.of("synaxis:transfer-rejected", "synaxis:transfer-failed",
                "synaxis:server-stopping-in")) {
            assertTrue(translations.contains('"' + key + '"'), language + " has no text for " + key);
        }
    }
}
