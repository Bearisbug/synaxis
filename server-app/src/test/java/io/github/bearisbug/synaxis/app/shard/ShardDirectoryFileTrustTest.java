package io.github.bearisbug.synaxis.app.shard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security review 2026-10 (surface 2): who may write {@code shards.json}, whose {@code host:port} of
 * a channel goes into that shard's heartbeat and from there into every {@code Transfer} other shards
 * send to it, and how its reader takes malformed text. The shard checks who may have written it in step
 * (3) ({@link MapFileTrust}, {@code docs/DESIGN-M3.md} {@code API-M3-002} 文件信任); reading it is
 * {@link ShardDirectory#load} alone, which only holds it to its size.
 */
public class ShardDirectoryFileTrustTest {

    @TempDir
    Path directory;

    private static String example() throws IOException {
        return Files.readString(ShardDirectoryTest.resource(ShardDirectory.FILE_NAME));
    }

    private Path write(String text) throws IOException {
        final Path file = directory.resolve(ShardDirectory.FILE_NAME);
        Files.writeString(file, text);
        return file;
    }

    /**
     * {@code ConfigFile} refuses a {@code synaxis.properties} that group or others may write, or whose
     * directory they may write without the sticky bit (D-03); so does the shard a {@code shards.json} of
     * 0666 in a directory of 0777 ({@link MapFileTrust}). {@code ShardDirectory#load} still reads it: it
     * only reads, the check is step (3)'s.
     */
    @Test
    public void aShardsJsonAnyoneMayWriteIsRefused() throws IOException {
        final Path file = write(example().replace("\"host\": \"127.0.0.1\", \"port\": 25601",
                "\"host\": \"192.0.2.66\", \"port\": 25999"));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-rw-"));
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwxrwx"));
        try {
            final ShardDirectory.Channel own = ShardDirectory.load(file, "r0-c1").ownChannel();
            assertEquals("192.0.2.66", own.host());
            assertEquals(25999, own.port());
            final MapFileTrust trust = new MapFileTrust(directory, MapFileTrust::unixUid);
            assertEquals(file.toRealPath() + ": writable by group or others (rw-rw-rw-); a map release is written by its owner alone",
                    assertThrows(IllegalArgumentException.class, () -> trust.check(file)).getMessage());
        } finally {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        }
    }

    /**
     * The host is held to 1 to 253 characters without whitespace and not with exactly one colon, a port
     * written into it ({@code docs/DESIGN-M3.md} {@code API-M3-010}); there is no further hostname or address
     * syntax, and an IPv6 literal, which has two colons or more, passes.
     */
    @Test
    public void theHostIsCheckedForItsLengthWhitespaceAndAPortInIt() throws IOException {
        for (String host : new String[]{"例子.测试", "-", "\\u0007", "::1", "[2001:db8::7]"}) {
            final Path file = write(example().replace("\"host\": \"127.0.0.1\", \"port\": 25601",
                    "\"host\": \"" + host + "\", \"port\": 25601"));
            final String read = ShardDirectory.load(file, "r0-c1").ownChannel().host();
            assertTrue(!read.isEmpty() && read.length() <= 253, host);
        }
        final String address = "\"host\": \"127.0.0.1\", \"port\": 25601";
        refused(example().replace(address, "\"host\": \"a b\", \"port\": 25601"),
                "host a b is not a host name or address: it has whitespace");
        refused(example().replace(address, "\"host\": \"host:25565\", \"port\": 25601"),
                "host host:25565 is not a host name or address: the port goes in port");
    }

    private void refused(String text, String expected) throws IOException {
        final Path file = write(text);
        final String message = assertThrows(IllegalArgumentException.class, () -> ShardDirectory.load(file, "r0-c1"))
                .getMessage();
        final String detail = message.length() > 300 ? message.substring(0, 300) + "..." : message;
        System.out.println("refused: " + detail);
        assertTrue(message.contains(expected), detail);
    }

    @Test
    public void malformedNumbersNamesAndNestingAreRefusedQuickly() {
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            final String example = example();
            final String port = "\"port\": 25601";
            refused(example.replace(port, "\"port\": -1"), "port -1 is not from 1 to 65535");
            refused(example.replace(port, "\"port\": 65536"), "is not from 1 to 65535");
            refused(example.replace(port, "\"port\": 1e400"), "port is not an integer");
            refused(example.replace(port, "\"port\": 25601.5"), "port is not an integer");
            refused(example.replace(port, "\"port\": 1" + "0".repeat(200_000)), "not JSON");
            refused(example.replace(port, port + ", \"port\": 25602"), "is given twice");
            refused(example.replace("\"format_version\": 1,", "\"format_version\": 1, \"x\": "
                    + "[".repeat(100_000) + "]".repeat(100_000) + ","), "not JSON");
        });
    }
}
