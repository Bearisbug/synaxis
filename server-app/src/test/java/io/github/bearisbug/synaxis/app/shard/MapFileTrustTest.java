package io.github.bearisbug.synaxis.app.shard;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.synx.OverlayOptions;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import io.github.bearisbug.synaxis.synx.SynxOptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
