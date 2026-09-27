package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.app.shard.ShardDirectory;
import io.github.bearisbug.synaxis.app.shard.ShardSettings;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Step (1) of a map shard in the directory mode ({@code docs/DESIGN-M3.md} {@code API-M3-010},
 * §19 「分片目录写错时拒启」 and 「分片目录决定容量」, the refusals of {@code Main}): the store alone
 * decides the directory mode, the shard id has to be a channel, the shard takes transfers and its
 * capacity is {@code synaxis.max-players}.
 */
class MainDirectoryModeTest {

    private static final String MAX_PLAYERS = "synaxis.max-players";

    @TempDir
    Path mapRoot;

    @AfterEach
    void clearProperties() {
        System.clearProperty(MAX_PLAYERS);
        System.clearProperty(ShardMain.ACCEPT_TRANSFERS);
    }

    private ShardSettings settings() {
        return new ShardSettings(mapRoot, "synth-000000000000-p776", 4, 100);
    }

    private Path file() {
        return mapRoot.resolve(ShardDirectory.FILE_NAME).toAbsolutePath();
    }

    /** The example of {@code API-M3-010} under the map root. */
    private void writeExample() throws IOException, URISyntaxException {
        Files.copy(Path.of(Objects.requireNonNull(getClass().getResource("/shards/shards.json")).toURI()), file());
    }

    /** A directory built with {@code new}, as {@code applyDirectory} takes it without {@code load}. */
    private static ShardDirectory capacity(int capacity) {
        return new ShardDirectory("synth", List.of(new ShardDirectory.Region("r0", new SynxManifest.Bounds(0, 0, 127, 127),
                List.of())), List.of(new ShardDirectory.Channel("r0-c1", "r0", 1, "127.0.0.1", 25601, capacity)), "r0-c1");
    }

    private static String refused(Executable executable) {
        return assertThrows(IllegalArgumentException.class, executable).getMessage();
    }

    @Test
    void aShardStoringInMemoryWithoutTheFileIsNotInTheDirectoryMode() {
        assertNull(Main.shardDirectory(settings(), Main.STORE_MEMORY, "local"));
        assertNull(System.getProperty(MAX_PLAYERS), "nothing is written");
    }

    @Test
    void aShardStoringInMemoryWithTheFileIsRefused() throws Exception {
        writeExample();
        assertEquals("the shard directory " + file() + " is only read by a shard storing in PostgreSQL "
                + "(synaxis.app.store=postgres)", refused(() -> Main.shardDirectory(settings(), Main.STORE_MEMORY, "r0-c1")));
    }

    @Test
    void aShardStoringInPostgresWithoutTheFileIsRefused() {
        assertEquals("a map shard storing in PostgreSQL needs its shard directory " + file() + ": no such file",
                refused(() -> Main.shardDirectory(settings(), Main.STORE_POSTGRES, "r0-c1")));
    }

    @Test
    void aShardIdThatIsNoChannelIsRefused() throws Exception {
        writeExample();
        assertEquals("synaxis.app.shard-id=local is not a channel of the shard directory " + file(),
                refused(() -> Main.shardDirectory(settings(), Main.STORE_POSTGRES, "local")));
        assertNull(System.getProperty(MAX_PLAYERS), "nothing is written before the channel is found");
    }

    @Test
    void aShardInTheDirectoryModeReadsItsChannelAndWritesItsCapacity() throws Exception {
        writeExample();
        final ShardDirectory directory = Main.shardDirectory(settings(), Main.STORE_POSTGRES, "r1-c1");
        assertNotNull(directory);
        assertEquals("r1", directory.ownRegion().regionId());
        assertEquals("200", System.getProperty(MAX_PLAYERS));
    }

    @Test
    void aShardThatTakesNoTransfersIsRefused() {
        System.setProperty(ShardMain.ACCEPT_TRANSFERS, "false");
        assertEquals("a shard in the shard directory takes transfers, synaxis.accept-transfers must not be false",
                refused(() -> Main.applyDirectory(capacity(200))));
        assertNull(System.getProperty(MAX_PLAYERS), "refused before the capacity is written");
        System.setProperty(ShardMain.ACCEPT_TRANSFERS, "true");
        assertDoesNotThrow(() -> Main.applyDirectory(capacity(200)));
    }

    @Test
    void theCapacityIsWrittenWhenMaxPlayersIsNotGiven() {
        Main.applyDirectory(capacity(2));
        assertEquals("2", System.getProperty(MAX_PLAYERS));
    }

    /** The key compares as the integer the engine reads it as; one that is none is left to ServerConfig. */
    @Test
    void aGivenMaxPlayersHasToBeTheCapacity() {
        System.setProperty(MAX_PLAYERS, "0200");
        assertDoesNotThrow(() -> Main.applyDirectory(capacity(200)));
        assertEquals("0200", System.getProperty(MAX_PLAYERS), "a value that agrees is left as given");

        System.setProperty(MAX_PLAYERS, "150");
        assertEquals("synaxis.max-players=150 disagrees with the capacity 200 of r0-c1 in the shard directory",
                refused(() -> Main.applyDirectory(capacity(200))));

        System.setProperty(MAX_PLAYERS, "abc");
        assertDoesNotThrow(() -> Main.applyDirectory(capacity(200)));
        assertEquals("abc", System.getProperty(MAX_PLAYERS), "ServerConfig refuses it in step (2)");
    }
}
