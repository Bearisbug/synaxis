package io.github.bearisbug.synaxis.app.command;

import io.github.bearisbug.synaxis.app.ConfigFile;
import io.github.bearisbug.synaxis.monitoring.EventsJFR;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The one JFR recording of the process ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-034},
 * {@code API-PROD-011}), shared by the {@code profile} command and the management method
 * {@code synaxis:profile} ({@code API-PROD-022}): the JDK's own {@code profile} settings for a
 * given number of seconds, written to {@code profiles/profile-<shard_id>-<UTC time>.jfr}.
 * <p>
 * The file is handed over for analysis, so what those settings would write in clear is left out:
 * the environment of the process ({@code jdk.InitialEnvironmentVariable}, the {@code SYNAXIS_*}
 * secrets among it), the command line of every process of the machine ({@code jdk.SystemProcess}),
 * the peers of the sockets ({@code jdk.SocketRead}, {@code jdk.SocketWrite}), and of the engine's
 * own events what players say and type and where status requests come from
 * ({@code synaxis.PlayerChat}, {@code synaxis.PlayerCommand}, {@code synaxis.ServerPing}). They are
 * off in the recording and left out again when the file is written: the recordings of a JVM share
 * their chunks, so a dump carries the events any other recording turned on meanwhile, and the JDK's
 * own settings, the ones {@code jcmd JFR.start}, {@code -XX:StartFlightRecording} and JMC use, turn
 * most of them on. Writing the file also leaves out a system property whose name says it is a
 * secret ({@link ConfigFile#isSecret}), and the JVM arguments ({@code jdk.JVMInformation}) when one
 * of them sets such a property. The file is created 0600, and the directory, when the profiler is
 * the one creating it, 0700.
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
    /** The events off in the recording and left out of the file, see the class comment. */
    private static final Set<String> LEFT_OUT = Set.of("jdk.InitialEnvironmentVariable", "jdk.SystemProcess",
            "jdk.SocketRead", "jdk.SocketWrite", EventsJFR.PLAYER_CHAT, EventsJFR.PLAYER_COMMAND, EventsJFR.SERVER_PING);
    private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
    private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY_DIRECTORY =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
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
        Files.createDirectories(directory, OWNER_ONLY_DIRECTORY);
        final Map<String, String> settings;
        try {
            settings = new HashMap<>(Configuration.getConfiguration(SETTINGS).getSettings());
        } catch (ParseException e) {
            throw new IOException("the JFR settings '" + SETTINGS + "' cannot be read", e);
        }
        for (String event : LEFT_OUT) settings.put(event + "#enabled", "false");
        final Recording recording = new Recording(settings);
        recording.setName("synaxis-profile");
        recording.start();
        running = path;
        final CompletableFuture<Path> written = new CompletableFuture<>();
        Thread.ofVirtual().name("Syn-Profile").start(() -> {
            Exception failure = null;
            try {
                Thread.sleep(Duration.ofSeconds(seconds));
                recording.stop();
                writeHandedOver(recording, path);
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

    /**
     * Dumps the recording to a temporary file only its owner reads, then writes the file handed
     * over from it, created owner only, with the events {@link #handedOver}; the temporary file goes
     * either way.
     */
    private static void writeHandedOver(Recording recording, Path path) throws IOException {
        final Path dump = Files.createTempFile("synaxis-profile-", ".jfr");
        try {
            recording.dump(dump);
            Files.createFile(path, OWNER_ONLY);
            try (RecordingFile file = new RecordingFile(dump)) {
                file.write(path, Profiler::handedOver);
            }
        } finally {
            Files.deleteIfExists(dump);
        }
    }

    /** Whether an event goes into the file handed over, see the class comment. */
    private static boolean handedOver(RecordedEvent event) {
        final String name = event.getEventType().getName();
        if (LEFT_OUT.contains(name)) return false;
        return switch (name) {
            case "jdk.InitialSystemProperty" -> !ConfigFile.isSecret(event.getString("key"));
            case "jdk.JVMInformation" -> !setsASecret(event.getString("jvmArguments"));
            default -> true;
        };
    }

    /** Whether JVM arguments, as {@code jdk.JVMInformation} joins them, set a secret system property. */
    private static boolean setsASecret(@Nullable String arguments) {
        if (arguments == null) return false;
        for (String argument : arguments.split(" ", -1)) {
            final int equals = argument.indexOf('=');
            if (argument.startsWith("-D") && equals > 2 && ConfigFile.isSecret(argument.substring(2, equals)))
                return true;
        }
        return false;
    }

    private synchronized void finished() {
        running = null;
    }
}
