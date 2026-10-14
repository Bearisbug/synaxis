package io.github.bearisbug.synaxis.app.shard;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.synx.OverlayOptions;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import io.github.bearisbug.synaxis.synx.SynxOptions;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §19 「属主既不是 root 也不是地图根目录的属主时拒启」 ({@code docs/DESIGN-M3.md} {@code API-M3-002} 文件信任):
 * the owners read by a stand-in, the map root owned by uid 501.
 */
public class MapFileTrustTest {

    private static final int ROOT_UID = 501;

    @TempDir
    static Path baked;
    private static ShardSettings release;

    @BeforeAll
    static void bake() {
        release = MapFixture.bake(baked);
    }

    /** The map root owned by {@value #ROOT_UID}, the manifest by {@code manifestUid}, everything else by root. */
    private static MapFileTrust.Owners owners(int manifestUid) throws IOException {
        final Path root = release.mapRoot().toRealPath();
        final Path manifest = release.buildDir().resolve(SynxManifest.FILE_NAME).toRealPath();
        return real -> real.equals(root) ? Integer.valueOf(ROOT_UID)
                : real.equals(manifest) ? Integer.valueOf(manifestUid) : Integer.valueOf(0);
    }

    @Test
    public void aFileOwnedByNeitherRootNorTheOwnerOfTheMapRootIsRefused() throws IOException {
        final Path manifest = release.buildDir().resolve(SynxManifest.FILE_NAME);
        assertDoesNotThrow(() -> new MapFileTrust(release.mapRoot(), owners(0)).check(manifest), "root");
        assertDoesNotThrow(() -> new MapFileTrust(release.mapRoot(), owners(ROOT_UID)).check(manifest), "the owner of the map root");
        final MapFileTrust trust = new MapFileTrust(release.mapRoot(), owners(502));
        assertEquals(manifest.toRealPath() + ": owned by uid 502, neither root nor uid 501 that owns synaxis.map.root "
                + release.mapRoot().toRealPath(), assertThrows(IllegalArgumentException.class, () -> trust.check(manifest))
                .getMessage());
        assertTrue(trust.checked());
    }

    /** Without the {@code unix} attribute view neither rule is checked, and the start line says so. */
    @Test
    public void aFileSystemWithoutPosixPermissionsIsNotCheckedAndTheStartSaysSo() throws IOException {
        final MapFileTrust.Owners none = _ -> null;
        final MapFileTrust trust = new MapFileTrust(release.mapRoot(), none);
        trust.check(release.buildDir().resolve(SynxManifest.FILE_NAME));
        assertFalse(trust.checked());

        final List<String> lines = verifiedLines(none);
        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.getFirst().endsWith(" permissions=unchecked (the file system has no POSIX permissions)"), lines.getFirst());
        final List<String> checked = verifiedLines(MapFileTrust::unixUid);
        assertEquals(1, checked.size(), checked.toString());
        assertTrue(checked.getFirst().endsWith("world=World[minY=0, height=64]"), checked.getFirst());
    }

    /**
     * §19 「符号链接按它指向的位置查」, the directory the link itself is in: whoever may write it may point
     * the link elsewhere, so it is held to the rule of the directory of a file.
     */
    @Test
    public void theDirectoryASymbolicLinkIsInIsCheckedToo(@TempDir Path maps) throws IOException {
        final ShardSettings copy = MapFixture.copy(release, maps);
        final Path regions = copy.buildDir().resolve("regions");
        final Path region = regions.resolve("r.0.0.synx");
        final Path elsewhere = Files.createDirectories(maps.resolve("elsewhere"));
        mode(elsewhere, "rwxr-xr-x");
        final Path target = Files.move(region, elsewhere.resolve(region.getFileName()));
        Files.createSymbolicLink(region, target);
        assertNull(refusal(copy), "a link in a directory of its owner alone");
        mode(regions, "rwxrwxr-x");
        assertEquals(target.toRealPath() + ": its directory " + regions.toRealPath()
                + " is writable by group or others without the sticky bit (rwxrwxr-x); they may replace the file",
                refusal(copy));
        Files.setAttribute(regions, "unix:mode", 01775);
        assertNull(refusal(copy), "the sticky bit lets it pass, as it does the directory of a file");

        // The release directory a link in a map root of its own, read without shards.json
        final Path linked = Files.createDirectories(maps.resolve("linked"));
        Files.createSymbolicLink(linked.resolve(copy.mapBuild()), copy.buildDir());
        final ShardSettings link = new ShardSettings(linked, copy.mapBuild(), 4, 100);
        mode(linked, "rwxr-xr-x");
        assertNull(refusal(link), "a map root of its owner alone");
        mode(linked, "rwxrwxr-x");
        assertEquals(copy.buildDir().toRealPath() + ": its directory " + linked.toRealPath()
                + " is writable by group or others without the sticky bit (rwxrwxr-x); they may replace the file",
                refusal(link));
    }

    private static void mode(Path path, String permissions) throws IOException {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
    }

    /** Step (3) of {@code Main} on a shard storing in memory: the refusal, or null when the release passes. */
    private static @Nullable String refusal(ShardSettings settings) {
        final ServerProcess process = ServerProcess.of(ServerConfig.builder().insideTest(true).shutdownOnSignal(false)
                .chunkViewDistance(12).build());
        try {
            ShardWorld.prepare(process, settings, new SynxOptions(true, 1 << 20, 1, false), new OverlayOptions(16_384, 10),
                    null);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        } finally {
            process.stop();
        }
    }

    /** The {@code Map release … verified} line of step (3) with the owners read by {@code owners}. */
    private static List<String> verifiedLines(MapFileTrust.Owners owners) {
        final ServerProcess process = ServerProcess.of(ServerConfig.builder().insideTest(true).shutdownOnSignal(false)
                .chunkViewDistance(12).build());
        final Logger logger = (Logger) LoggerFactory.getLogger(ShardWorld.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ShardWorld.prepare(process, release, new SynxOptions(true, 1 << 20, 1, false), new OverlayOptions(16_384, 10),
                    null, owners);
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .filter(line -> line.startsWith("Map release ")).toList();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            process.stop();
        }
    }
}
