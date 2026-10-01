package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.ServerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The boolean keys of {@link ServerConfig} ({@code docs/DESIGN-PROD.md} {@code API-PROD-023}, §19
 * the scenario of boolean keys): {@code true} or {@code false} as written, anything else refuses
 * the process instead of reading as false. Through the path of a launch: {@code synaxis.properties}
 * applied to the JVM properties, then the process configuration read from them. Test classes of
 * this module run one after the other, so the JVM properties set here are seen by nothing else;
 * each test clears what it set.
 */
class BooleanKeysTest {

    /** Every boolean key {@link ServerConfig#fromSystemProperties()} reads. */
    private static final List<String> KEYS = List.of(
            "synaxis.shutdown-on-signal",
            "synaxis.accept-transfers",
            "synaxis.hide-online-players",
            "synaxis.suppress-connection-accept-errors",
            "synaxis.suppress-connection-io-errors",
            "synaxis.reject-malformed-packet",
            "synaxis.reject-misused-packet",
            "synaxis.tcp-no-delay",
            "synaxis.viewable-packet",
            "synaxis.enforce-entity-interaction-range",
            "synaxis.registry.unsafe-ops",
            "synaxis.inside-test",
            "synaxis.new-socket-write-lock",
            "synaxis.proxy-protocol",
            "synaxis.proxy-protocol.required");

    @TempDir
    Path directory;

    /** Writes the file and applies it as a launcher does; the keys it set are cleared by the caller. */
    private ConfigFile.Outcome apply(String... lines) throws IOException {
        final Path file = directory.resolve(ConfigFile.DEFAULT_FILE);
        ConfigFileTest.write(file, String.join("\n", lines) + "\n");
        final ConfigFile.Outcome outcome = ConfigFile.apply(null, file, System::getProperty, System::setProperty);
        assertNull(outcome.refusal(), "the file itself is fine: its keys are known");
        assertEquals(List.of(), outcome.overridden(), "nothing on the command line of this JVM");
        return outcome;
    }

    private static void clear(ConfigFile.Outcome outcome) {
        outcome.keys().forEach(System::clearProperty);
    }

    @Test
    void aTrailingBlankAfterTrueInTheFileRefusesTheProcess() throws IOException {
        for (String key : KEYS) {
            assertNull(System.getProperty(key), key + " is not given to this JVM");
            final ConfigFile.Outcome outcome = apply(key + "=true ");
            try {
                assertEquals("true ", System.getProperty(key), "the file keeps the blank");
                final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                        ServerConfig::fromSystemProperties, key);
                assertEquals("Property '" + key + "' is not true or false: \"true \"", refused.getMessage());
            } finally {
                clear(outcome);
            }
        }
    }

    @Test
    void exactlyTrueAndFalseAreRead() throws IOException {
        final ServerConfig defaults = ServerConfig.fromSystemProperties();
        assertTrue(defaults.enforceInteractionLimit() && defaults.rejectMalformedPacket() && !defaults.hideOnlinePlayers());

        final ConfigFile.Outcome outcome = apply("synaxis.enforce-entity-interaction-range=false",
                "synaxis.reject-malformed-packet=false", "synaxis.hide-online-players=true");
        try {
            final ServerConfig config = ServerConfig.fromSystemProperties();
            assertFalse(config.enforceInteractionLimit());
            assertFalse(config.rejectMalformedPacket());
            assertTrue(config.hideOnlinePlayers());
        } finally {
            clear(outcome);
        }
    }

    @Test
    void otherSpellingsOnTheCommandLineRefuseTheProcess() {
        final String key = "synaxis.reject-malformed-packet";
        // Before, each of these read as false but TRUE and True, which read as true
        for (String value : List.of("yes", "1", "on", "TRUE", "True", "False", " true", "true\t", "")) {
            System.setProperty(key, value);
            try {
                final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                        ServerConfig::fromSystemProperties, "\"" + value + "\"");
                assertEquals("Property '" + key + "' is not true or false: \"" + value + "\"", refused.getMessage());
            } finally {
                System.clearProperty(key);
            }
        }
    }
}
