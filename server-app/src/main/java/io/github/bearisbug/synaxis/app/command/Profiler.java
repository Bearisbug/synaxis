package io.github.bearisbug.synaxis.app.command;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;

/**
 * The one JFR recording of the process ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-034},
 * {@code API-PROD-011}), shared by the {@code profile} command and the management method
 * {@code synaxis:profile} ({@code API-PROD-022}): the JDK's own {@code profile} settings for a
 * given number of seconds, written to {@code profiles/profile-<shard_id>-<UTC time>.jfr}.
 * <p>
 * Only one runs at a time. The wait and the write happen on a virtual thread; a recording still
 * running when the process ends is dropped with it.
 */
public final class Profiler {

    /** How long a recording runs when no length is given. */
    public static final int DEFAULT_SECONDS = 60;
    /** The shortest recording. */
    public static final int MIN_SECONDS = 1;
    /** The longest recording: a mistyped length must not hold the only recording for hours. */
    public static final int MAX_SECONDS = 600;

    private static final String SETTINGS = "profile";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneOffset.UTC);

    /** How a request to record ended. */
    public sealed interface Start permits Started, Busy {
    }

    /**
     * The recording runs.
     *
     * @param path    where it is written, absolute
     * @param written completed with the path once the file is written, exceptionally if it could not be
     */
    public record Started(Path path, CompletableFuture<Path> written) implements Start {
    }

    /**
     * Another recording runs.
     *
     * @param path where that one is going to be written
     */
    public record Busy(Path path) implements Start {
    }

    private final Path directory;
    private final String shardId;
    private final Clock clock;
    // Guarded by this: the file of the recording that runs, null while none does
    private @Nullable Path running;

    /**
     * @param directory where the recordings go, {@code profiles} of the working directory in a shard
     * @param shardId   the shard id of the file names
     * @param clock     the time of the file names
     */
    public Profiler(Path directory, String shardId, Clock clock) {
        this.directory = directory.toAbsolutePath();
        this.shardId = shardId;
        this.clock = clock;
    }

    /**
     * Starts recording, unless a recording runs already.
     *
     * @param seconds how long to record, {@value #MIN_SECONDS} to {@value #MAX_SECONDS}
     * @return the recording, or the path of the one that runs
     * @throws IllegalArgumentException if the length is out of range
     * @throws IOException              if the directory cannot be created or JFR cannot start
     */
    public synchronized Start start(int seconds) throws IOException {
        if (seconds < MIN_SECONDS || seconds > MAX_SECONDS)
            throw new IllegalArgumentException("a recording lasts " + MIN_SECONDS + " to " + MAX_SECONDS
                    + " s, not " + seconds);
        if (running != null) return new Busy(running);
        final Path path = directory.resolve("profile-" + shardId + "-" + STAMP.format(clock.instant()) + ".jfr");
        Files.createDirectories(directory);
        final Recording recording;
        try {
            recording = new Recording(Configuration.getConfiguration(SETTINGS));
        } catch (ParseException e) {
            throw new IOException("the JFR settings '" + SETTINGS + "' cannot be read", e);
        }
        recording.setName("synaxis-profile");
        recording.start();
        running = path;
        final CompletableFuture<Path> written = new CompletableFuture<>();
        Thread.ofVirtual().name("Syn-Profile").start(() -> {
            Exception failure = null;
            try {
                Thread.sleep(Duration.ofSeconds(seconds));
                recording.stop();
                recording.dump(path);
            } catch (IOException | InterruptedException | RuntimeException e) {
                failure = e;
            } finally {
                recording.close();
                finished();
            }
            // Only once the next recording may start, so whoever is told can start one
            if (failure == null) written.complete(path);
            else written.completeExceptionally(failure);
        });
        return new Started(path, written);
    }

    private synchronized void finished() {
        running = null;
    }
}
