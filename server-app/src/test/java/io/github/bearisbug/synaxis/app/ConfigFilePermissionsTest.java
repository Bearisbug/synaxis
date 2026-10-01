package io.github.bearisbug.synaxis.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who else may write {@code synaxis.properties} ({@code docs/DESIGN-PROD.md} {@code API-PROD-023}):
 * the file decides the login mode, the gates and the whitelist, so a file group or others may write,
 * or one in a directory group or others may write without the sticky bit, refuses the start and sets
 * no key. Its owner is not looked at: a file root owns and the service user only reads is fine.
 */
class ConfigFilePermissionsTest {

    @TempDir
    Path directory;

    private final Map<String, String> properties = new HashMap<>();

    private static Path file(Path in, String permissions) throws IOException {
        final Path file = in.resolve(ConfigFile.DEFAULT_FILE);
        Files.writeString(file, "synaxis.app.auth=offline\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(permissions));
        return file;
    }

    private ConfigFile.Outcome apply(Path file) {
        return ConfigFile.apply(null, file, properties::get, properties::put);
    }

    @Test
    void aFileGroupOrOthersMayWriteIsRefusedAndSetsNothing() throws IOException {
        for (String permissions : List.of("rw-rw-r--", "rw-r--rw-")) {
            final Path file = file(directory, permissions);

            assertEquals(file + ": writable by group or others (" + permissions + "); only the service user may "
                    + "write it", apply(file).refusal());
            assertEquals(Map.of(), properties, permissions);
        }
    }

    @Test
    void aDirectoryGroupOrOthersMayWriteIsRefusedUnlessItHasTheStickyBit() throws IOException {
        final Path shared = Files.createDirectory(directory.resolve("shared"));
        final Path file = file(shared, "rw-r--r--");
        for (String permissions : List.of("rwxrwxr-x", "rwxrwxrwx")) {
            Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString(permissions));

            assertEquals(file + ": its directory " + shared + " is writable by group or others without the sticky "
                    + "bit (" + permissions + "); they may replace the file", apply(file).refusal());
            assertEquals(Map.of(), properties, permissions);
        }

        // What /tmp is: others may add files but not replace one they do not own
        Files.setAttribute(shared, "unix:mode", 01777);
        assertNull(apply(file).refusal());
        assertEquals(Map.of("synaxis.app.auth", "offline"), properties);
    }

    @Test
    void aFileOnlyItsOwnerMayWriteIsRead() throws IOException {
        for (String permissions : List.of("rw-r--r--", "r--------")) {
            properties.clear();
            final Path file = file(directory, permissions);

            final ConfigFile.Outcome outcome = apply(file);

            assertNull(outcome.refusal(), permissions);
            assertEquals(Map.of("synaxis.app.auth", "offline"), properties, permissions);
            assertEquals("path=" + file + " keys=1 overridden_by_command_line=[]", outcome.describe());
        }
    }

    @Test
    void withoutPosixPermissionsTheFileIsReadAndTheSelfCheckSaysSo() throws IOException {
        try (FileSystem zip = FileSystems.newFileSystem(directory.resolve("config.zip"), Map.of("create", "true"))) {
            final Path file = zip.getPath(ConfigFile.DEFAULT_FILE);
            Files.writeString(file, "synaxis.motd=zipped\n");

            final ConfigFile.Outcome outcome = apply(file);

            assertNull(outcome.refusal());
            assertEquals(Map.of("synaxis.motd", "zipped"), properties);
            assertTrue(outcome.describe().endsWith(" permissions=unchecked (the file system has no POSIX permissions)"),
                    outcome.describe());
        }
    }
}
