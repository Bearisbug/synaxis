package io.github.bearisbug.synaxis.app.shard;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.ShutdownParticipant;
import io.github.bearisbug.synaxis.app.ShutdownSequence;
import io.github.bearisbug.synaxis.baker.Baker;
import io.github.bearisbug.synaxis.baker.GeneratorPreset;
import io.github.bearisbug.synaxis.baker.GeneratorSource;
import io.github.bearisbug.synaxis.baker.LightVersions;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.synx.OverlayOptions;
import io.github.bearisbug.synaxis.synx.OverlayStore;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import io.github.bearisbug.synaxis.synx.SynxOptions;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/DESIGN-M3.md} {@code API-M3-002} 文件信任 on a shard ({@code REQ-M3-031}): who may have
 * written the files of a release and of the overlay before the shard reads them, the manifest and
 * {@code shards.json} read once on one channel after their size, and the region files read through the
 * one mapping the start-up check made.
 */
public class ShardWorldFileTrustTest {

    private static final SynxOptions SYNX = new SynxOptions(true, 1 << 20, 1, false);
    private static final OverlayOptions OVERLAY = new OverlayOptions(16_384, 10);
    private static final String WHO_WRITES = "a map release is written by its owner alone";

    @TempDir
    static Path baked;
    private static ShardSettings release;

    @TempDir
    Path overlayRoot;

    @BeforeAll
    static void bake() {
        release = MapFixture.bake(baked);
    }

    /** The fixture map as a directory of one region, {@code r0-c1}, would serve it. */
    private static ShardDirectory directory() {
        return new ShardDirectory(MapFixture.MAP_ID, List.of(new ShardDirectory.Region("r0", MapFixture.BOUNDS, List.of())),
                List.of(new ShardDirectory.Channel("r0-c1", "r0", 1, "127.0.0.1", 25601, 200)), "r0-c1");
    }

    private static ServerProcess process() {
        return ServerProcess.of(ServerConfig.builder().insideTest(true).shutdownOnSignal(false).chunkViewDistance(12).build());
    }

    /** Step (3) of {@code Main}: the refusal, or null when the release passes. */
    private static @Nullable String refusal(ShardSettings settings, @Nullable ShardDirectory directory) {
        final ServerProcess process = process();
        try {
            ShardWorld.prepare(process, settings, SYNX, OVERLAY, directory);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        } finally {
            process.stop();
        }
    }

    private static void mode(Path path, String permissions) throws IOException {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
    }

    private static String real(Path path) throws IOException {
        return path.toRealPath().toString();
    }

    /** A copy of the release with {@code shards.json} 0644 next to it. */
    private static ShardSettings copy(Path maps) throws IOException {
        final ShardSettings copy = MapFixture.copy(release, maps);
        final Path shards = Files.writeString(copy.mapRoot().resolve(ShardDirectory.FILE_NAME), "{}");
        mode(shards, "rw-r--r--");
        return new ShardSettings(copy.mapRoot(), copy.mapBuild(), 4, 100);
    }

    private static Path regionFile(ShardSettings settings) {
        return settings.buildDir().resolve("regions").resolve("r.0.0.synx");
    }

    /** §19 「组或他人可写的地图文件拒启」. */
    @Test
    public void aFileOfTheReleaseGroupOrOthersMayWriteRefusesTheStart(@TempDir Path maps) throws IOException {
        final ShardSettings copy = copy(maps);
        final Path shards = copy.mapRoot().resolve(ShardDirectory.FILE_NAME);
        final Path build = copy.buildDir();
        final Path region = regionFile(copy);
        final Path manifest = build.resolve(SynxManifest.FILE_NAME);
        assertNull(refusal(copy, directory()), "the release as the baker publishes it");

        mode(shards, "rw-rw-rw-");
        assertEquals(real(shards) + ": writable by group or others (rw-rw-rw-); " + WHO_WRITES, refusal(copy, directory()));
        mode(shards, "rw-r--r--");
        mode(build, "rwxrwxrwx");
        assertEquals(real(build) + ": writable by group or others (rwxrwxrwx); " + WHO_WRITES, refusal(copy, directory()));
        mode(build, "rwxr-xr-x");
        mode(region, "rw-rw-r--");
        assertEquals(real(region) + ": writable by group or others (rw-rw-r--); " + WHO_WRITES, refusal(copy, directory()));
        mode(region, "rw-r--r--");
        mode(manifest, "rw-r--rw-");
        assertEquals(real(manifest) + ": writable by group or others (rw-r--rw-); " + WHO_WRITES, refusal(copy, directory()));
        mode(manifest, "rw-r--r--");
        assertNull(refusal(copy, directory()), "all of it back as it was");

        mode(region.getParent(), "rwxrwxr-x");
        assertEquals(real(region) + ": its directory " + real(region.getParent())
                + " is writable by group or others without the sticky bit (rwxrwxr-x); they may replace the file",
                refusal(copy, directory()));
        mode(region.getParent(), "rwxr-xr-x");
    }

    /**
     * §19 「组或他人可写的地图文件拒启」, the order: the three files of the release are checked before a byte
     * of them is read, so a broken one of them refuses the start with who may write it, not with what is
     * wrong in it.
     */
    @Test
    public void whoMayWriteIsCheckedBeforeTheFileIsRead(@TempDir Path maps) throws IOException {
        final ShardSettings copy = copy(maps);
        final Path build = copy.buildDir();
        final Path manifest = build.resolve(SynxManifest.FILE_NAME);
        final Path region = regionFile(copy);
        final String json = Files.readString(manifest);

        Files.writeString(manifest, "{\"map_id\": ");
        mode(build, "rwxrwxrwx");
        assertEquals(real(build) + ": writable by group or others (rwxrwxrwx); " + WHO_WRITES, refusal(copy, directory()));
        mode(build, "rwxr-xr-x");
        mode(manifest, "rw-r--rw-");
        assertEquals(real(manifest) + ": writable by group or others (rw-r--rw-); " + WHO_WRITES, refusal(copy, directory()));
        mode(manifest, "rw-r--r--");
        Files.writeString(manifest, json);

        // One byte of the payload, the size as it was
        try (FileChannel channel = FileChannel.open(region, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            final ByteBuffer last = ByteBuffer.allocate(1);
            channel.read(last, channel.size() - 1);
            channel.write(ByteBuffer.wrap(new byte[]{(byte) (last.get(0) ^ 0x01)}), channel.size() - 1);
        }
        mode(region, "rw-rw-r--");
        assertEquals(real(region) + ": writable by group or others (rw-rw-r--); " + WHO_WRITES, refusal(copy, directory()));
        mode(region, "rw-r--r--");
        assertTrue(String.valueOf(refusal(copy, directory())).startsWith("synx-payload-hash: r.0.0.synx: "),
                "only now the byte it holds: " + refusal(copy, directory()));
    }

    /** §19 「符号链接按它指向的位置查」. */
    @Test
    public void aSymbolicLinkIsCheckedWhereItPoints(@TempDir Path maps) throws IOException {
        final ShardSettings copy = copy(maps);
        final Path region = regionFile(copy);
        final Path elsewhere = Files.createDirectories(maps.resolve("elsewhere"));
        final Path target = Files.move(region, elsewhere.resolve(region.getFileName()));
        Files.createSymbolicLink(region, target);
        mode(elsewhere, "rwxrwxrwx");
        assertEquals(real(target) + ": its directory " + real(elsewhere)
                + " is writable by group or others without the sticky bit (rwxrwxrwx); they may replace the file",
                refusal(copy, directory()));
        mode(elsewhere, "rwxr-xr-x");
        assertNull(refusal(copy, directory()), "the same link into a directory of its owner alone");
    }

    /** The player store of a shard nobody joined: saved at once. */
    private static final class NoPlayers implements ShutdownParticipant {
        @Override
        public void onGateClosed(Duration countdown) {
        }

        @Override
        public CompletableFuture<?> saveAll(Duration budget, boolean tickAlive) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onSaveEnded(boolean timedOut) {
        }
    }

    /** Steps (3) and (5) of {@code Main} on the overlay under {@link #overlayRoot}. */
    private ShardWorld.Started start(ServerProcess process, ShardSettings settings) {
        final ShardWorld world = ShardWorld.prepare(process, settings, SYNX, OVERLAY, null);
        final ShutdownSequence shutdown = new ShutdownSequence(process, new NoPlayers());
        process.setShutdownParticipant(shutdown);
        return world.start(shutdown, (_, _) -> {
        }, null, overlayRoot.resolve("overlay"));
    }

    /** §19 「覆盖目录组可写时拒启，新建的覆盖目录是 0700」, the shard part. */
    @Test
    public void anOverlayDirectoryGroupMayWriteRefusesTheStartBeforeTheStoreReadsIt() throws IOException {
        final Path overlay = overlayRoot.resolve("overlay");
        final Path own = Files.createDirectories(overlay.resolve(release.mapBuild()));
        mode(overlay, "rwx------");
        mode(own, "rwxrwxr-x");
        final Path segment = Files.write(own.resolve("seg.0.synx"), new byte[16]);
        mode(segment, "rw-r--r--");
        final ServerProcess refused = process();
        final Logger logger = (Logger) LoggerFactory.getLogger(OverlayStore.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertEquals(real(own) + ": writable by group or others (rwxrwxr-x); only the service user may write it",
                    assertThrows(IllegalArgumentException.class, () -> start(refused, release)).getMessage());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            refused.stop();
        }
        assertTrue(Files.exists(segment), "the store did not open: the segment without a header is still there");
        assertEquals(List.of(), appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(line -> line.contains("has no header")).toList());

        try (Stream<Path> paths = Files.walk(overlay)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        final ServerProcess started = process();
        try {
            start(started, release);
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(overlay)));
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(
                    overlay.resolve(release.mapBuild()))));
        } finally {
            started.stop();
        }
    }

    /** §19 「校验与载入用同一份映射」. */
    @Test
    public void theRegionFilesLoadFromTheMappingTheCheckMade(@TempDir Path maps) throws IOException {
        final ShardSettings copy = MapFixture.copy(release, maps);
        // Another map of the same bounds whose stone ends at y 20, not 40
        final Baker.Result other = Baker.bake(new Baker.Request("other", new GeneratorSource(new GeneratorPreset(
                unit -> unit.modifier().fillHeight(0, 20, Block.STONE), 0, 64, MapFixture.BOUNDS), Registries.vanilla()),
                maps.resolve("others"), 1, maps.resolve("logs"), Registries.vanilla(), _ -> null, LightVersions.verified(), 1));
        assertEquals(Baker.EXIT_PUBLISHED, other.exitCode(), other.toString());
        final Path otherRegion = Objects.requireNonNull(other.directory()).resolve("regions").resolve("r.0.0.synx");

        final ServerProcess process = process();
        try {
            final ShardWorld world = ShardWorld.prepare(process, copy, SYNX, OVERLAY, null);
            // Written next to the file and renamed over it, as a deploy that replaces a release would
            final Path next = regionFile(copy).resolveSibling("r.0.0.synx.next");
            Files.copy(otherRegion, next);
            Files.move(next, regionFile(copy), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            final ShutdownSequence shutdown = new ShutdownSequence(process, new NoPlayers());
            process.setShutdownParticipant(shutdown);
            final ShardWorld.Started started = world.start(shutdown, (_, _) -> {
            }, null, overlayRoot.resolve("overlay"));
            started.instance().loadChunk(7, 7).join();
            assertEquals(Block.STONE, started.instance().getBlock(7 * 16 + 8, MapFixture.FLOOR - 1, 7 * 16 + 8),
                    "the content that was checked");
            assertEquals(MapFixture.FLOOR, started.spawn().y(), "the spawn point of the content that was checked");
        } finally {
            process.stop();
        }
    }

    /** A channel that counts the bytes read through it. */
    static final class Counting implements SeekableByteChannel {
        private final FileChannel channel;
        long read;

        Counting(Path file) throws IOException {
            this.channel = FileChannel.open(file, StandardOpenOption.READ);
        }

        @Override
        public int read(ByteBuffer destination) throws IOException {
            final int n = channel.read(destination);
            if (n > 0) read += n;
            return n;
        }

        @Override
        public int write(ByteBuffer source) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long position() throws IOException {
            return channel.position();
        }

        @Override
        public SeekableByteChannel position(long position) throws IOException {
            channel.position(position);
            return this;
        }

        @Override
        public long size() throws IOException {
            return channel.size();
        }

        @Override
        public SeekableByteChannel truncate(long size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isOpen() {
            return channel.isOpen();
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    /** A file of {@code bytes} bytes, sparse: one byte written at its end. */
    static void sparse(Path file, long bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
            channel.truncate(0);
            channel.write(ByteBuffer.wrap(new byte[]{' '}), bytes - 1);
        }
    }

    /** §19 「读文件之前先比大小」, the manifest and {@code shards.json}. */
    @Test
    public void theManifestAndTheShardDirectoryAreSizedBeforeAByteIsRead(@TempDir Path maps) throws Exception {
        final ShardSettings copy = MapFixture.copy(release, maps);
        final Path manifest = copy.buildDir().resolve(SynxManifest.FILE_NAME);
        sparse(manifest, ShardWorld.MAX_MANIFEST_BYTES + 1L);
        assertEquals("synx-manifest: manifest.json: 16777217 bytes, over 16777216", refusal(copy, null));
        try (Counting channel = new Counting(manifest)) {
            assertThrows(Exception.class, () -> ShardWorld.readManifest(channel));
            assertEquals(0, channel.read, "only its size was taken");
        }

        final Path shards = maps.resolve(ShardDirectory.FILE_NAME);
        sparse(shards, ShardDirectory.MAX_BYTES + 1L);
        assertEquals("the shard directory " + shards.toAbsolutePath() + " is not valid: 1048577 bytes, over 1048576",
                assertThrows(IllegalArgumentException.class, () -> ShardDirectory.load(shards, "r0-c1")).getMessage());
        try (Counting channel = new Counting(shards)) {
            assertThrows(IllegalArgumentException.class, () -> ShardDirectory.load(channel, shards, "r0-c1"));
            assertEquals(0, channel.read, "only its size was taken");
        }
    }
}
