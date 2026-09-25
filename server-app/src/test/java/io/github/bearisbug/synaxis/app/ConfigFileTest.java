package io.github.bearisbug.synaxis.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code synaxis.properties} ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-033}, {@code API-PROD-023}):
 * the command line over the file, the file over the defaults, an unknown key refused with the
 * nearest known one. Applied to a map rather than to the JVM properties, which every test of this
 * JVM shares; {@code ConfigFileLaunchTest} launches the real thing.
 */
class ConfigFileTest {

    @TempDir
    Path directory;

    private final Map<String, String> properties = new HashMap<>();

    private Path file(String... lines) throws IOException {
        final Path file = directory.resolve(ConfigFile.DEFAULT_FILE);
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return file;
    }

    private ConfigFile.Outcome apply(Path fallback) {
        return ConfigFile.apply(null, fallback, properties::get, properties::put);
    }

    @Test
    void theCommandLineWinsOverTheFile() throws IOException {
        properties.put("synaxis.max-players", "200");
        final Path file = file("synaxis.max-players=300", "synaxis.motd=Synaxis");

        final ConfigFile.Outcome outcome = apply(file);

        assertNull(outcome.refusal());
        assertEquals(Map.of("synaxis.max-players", "200", "synaxis.motd", "Synaxis"), properties);
        assertEquals(List.of("synaxis.max-players", "synaxis.motd"), outcome.keys());
        assertEquals(List.of("synaxis.max-players"), outcome.overridden());
        assertEquals("path=" + file.toAbsolutePath() + " keys=2 overridden_by_command_line=[synaxis.max-players]",
                outcome.describe());
    }

    @Test
    void theFileWinsOverTheShardDefaults() throws IOException {
        apply(file("synaxis.chunk-view-distance=10", "synaxis.dispatcher-threads=3"));

        // What ShardMain#main does next: its defaults only fill what is still unset
        assertEquals(Map.of("synaxis.chunk-queue.max-per-tick", "4", "synaxis.max-visible-entities-per-player", "100"),
                ShardMain.viewDefaults(properties::get));
        assertEquals(Map.of("synaxis.thread-provider", "grid"), ShardMain.threadDefaults(properties::get, 12));
    }

    @Test
    void anUnknownKeyIsRefusedWithTheNearestKnownKeyAndNothingIsSet() throws IOException {
        final Path file = file("synaxis.motd=Synaxis", "synaxis.max-player=1");

        final ConfigFile.Outcome outcome = apply(file);

        assertEquals(file.toAbsolutePath() + ": unknown key synaxis.max-player (nearest known key synaxis.max-players)",
                outcome.refusal());
        assertEquals(Map.of(), properties, "a refused file sets no key, not even its known ones");
    }

    @Test
    void everyUnknownKeyIsListedAndTheFileCannotNameItself() throws IOException {
        final Path file = file("synaxis.app.auht=offline", "server-port=25565", "synaxis.config-file=other.properties");

        assertEquals(file.toAbsolutePath() + ": "
                        + "unknown key server-port (nearest known key synaxis.motd); "
                        + "unknown key synaxis.app.auht (nearest known key synaxis.app.auth); "
                        + "synaxis.config-file names the file and is only read from the command line",
                apply(file).refusal());
        assertEquals(Map.of(), properties);
    }

    @Test
    void aKeyGivenTwiceIsRefused() throws IOException {
        final Path file = file("synaxis.max-players=300", "synaxis.max-players=500");

        assertEquals(file.toAbsolutePath() + ": synaxis.max-players is given twice", apply(file).refusal());
        assertEquals(Map.of(), properties);
    }

    @Test
    void noFileWhereNoneWasNamedIsNoFile() {
        final Path missing = directory.resolve(ConfigFile.DEFAULT_FILE);

        final ConfigFile.Outcome outcome = apply(missing);

        assertNull(outcome.refusal());
        assertFalse(outcome.read());
        assertEquals("none, " + missing.toAbsolutePath() + " does not exist", outcome.describe());
    }

    @Test
    void aNamedFileThatDoesNotExistIsRefused() {
        final Path named = directory.resolve("shard.properties");

        final ConfigFile.Outcome outcome = ConfigFile.apply(named.toString(), directory.resolve(ConfigFile.DEFAULT_FILE),
                properties::get, properties::put);

        assertEquals(named.toAbsolutePath() + ": cannot be read: java.nio.file.NoSuchFileException: " + named,
                outcome.refusal());
    }

    @Test
    void aNamedFileIsReadInsteadOfTheOneInTheWorkingDirectory() throws IOException {
        file("synaxis.motd=working directory");
        final Path named = directory.resolve("shard.properties");
        Files.writeString(named, "synaxis.motd=named\n");

        final ConfigFile.Outcome outcome = ConfigFile.apply(named.toString(), directory.resolve(ConfigFile.DEFAULT_FILE),
                properties::get, properties::put);

        assertNull(outcome.refusal());
        assertEquals(named.toAbsolutePath(), outcome.file());
        assertEquals(Map.of("synaxis.motd", "named"), properties);
    }

    @Test
    void theFileIsUtf8InThePropertiesFormat() throws IOException {
        final Path file = file(
                "# a comment",
                "! another comment",
                "synaxis.motd = 同步 \\",
                "    第二段\\n第二行",
                "synaxis.app.shard-id: eu-1",
                "synaxis.app.auth offline",
                "synaxis.max-players=300  ");

        final SortedMap<String, String> entries = ConfigFile.read(file);

        assertEquals("同步 第二段\n第二行", entries.get("synaxis.motd"));
        assertEquals("eu-1", entries.get("synaxis.app.shard-id"));
        assertEquals("offline", entries.get("synaxis.app.auth"));
        // The value as written: the reader of the key decides whether 300 and two spaces is a number
        assertEquals("300  ", entries.get("synaxis.max-players"));
    }

    @Test
    void aFileThatIsNotUtf8IsRefused() throws IOException {
        final Path file = directory.resolve(ConfigFile.DEFAULT_FILE);
        Files.write(file, new byte[]{'s', '=', (byte) 0xC3, (byte) 0x28, '\n'});

        final String refusal = apply(file).refusal();
        assertTrue(refusal != null && refusal.startsWith(file.toAbsolutePath() + ": cannot be read: "
                + "java.nio.charset.MalformedInputException"), refusal);
    }

    @Test
    void theNearestKnownKeyIsTheFewestEditsAwayAndTheFirstOfATie() {
        assertEquals("synaxis.max-players", ConfigFile.nearest("synaxis.max-player"));
        assertEquals("synaxis.app.auth", ConfigFile.nearest("synaxis.app.auht"));
        // Two edits from synaxis.app.log and from synaxis.app.port, and no key is nearer
        assertEquals("synaxis.app.log", ConfigFile.nearest("synaxis.app.or"));
    }
}
