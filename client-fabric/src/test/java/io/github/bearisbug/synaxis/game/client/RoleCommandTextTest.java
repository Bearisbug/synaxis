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
 * What the GM command {@code role} of {@code docs/DESIGN-PROD.md} {@code API-PROD-010} answers are
 * {@code translate} keys; a GM only reads a sentence for them if the mod ships text for each.
 */
class RoleCommandTextTest {

    @ParameterizedTest
    @ValueSource(strings = {"en_us", "zh_cn"})
    void everyAnswerOfTheRoleCommandHasTextInTheModJar(String language) throws IOException {
        final String translations;
        try (InputStream stream = RoleCommandTextTest.class.getResourceAsStream(
                "/assets/synaxis/lang/" + language + ".json")) {
            assertNotNull(stream, "the mod does not ship " + language);
            translations = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (final String key : List.of("synaxis:role-granted", "synaxis:role-revoked", "synaxis:role-list",
                "synaxis:role-unknown", "synaxis:role-not-granted", "synaxis:role-no-target",
                "synaxis:role-unavailable")) {
            assertTrue(translations.contains('"' + key + '"'), language + " has no text for " + key);
        }
    }
}
