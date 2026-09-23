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
 * What bans and the whitelist of {@code docs/DESIGN-PROD.md} {@code API-PROD-009} say are
 * {@code translate} keys: the two disconnect reasons of §14 with the expiry of a ban, and the answers
 * of the GM commands {@code ban}, {@code unban} and {@code whitelist}. A player only reads a sentence
 * for them if the mod ships text for each.
 */
class ModerationTextTest {

    @ParameterizedTest
    @ValueSource(strings = {"en_us", "zh_cn"})
    void everyBanAndWhitelistMessageHasTextInTheModJar(String language) throws IOException {
        final String translations;
        try (InputStream stream = ModerationTextTest.class.getResourceAsStream(
                "/assets/synaxis/lang/" + language + ".json")) {
            assertNotNull(stream, "the mod does not ship " + language);
            translations = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (final String key : List.of("synaxis:banned", "synaxis:banned-until", "synaxis:not-whitelisted",
                "synaxis:ban-done", "synaxis:unban-done", "synaxis:unban-none", "synaxis:whitelist-added",
                "synaxis:whitelist-removed", "synaxis:moderation-no-target", "synaxis:moderation-bad-duration",
                "synaxis:moderation-bad-reason", "synaxis:moderation-unavailable")) {
            assertTrue(translations.contains('"' + key + '"'), language + " has no text for " + key);
        }
    }
}
