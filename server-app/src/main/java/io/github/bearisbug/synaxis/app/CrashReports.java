package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.plugin.LogBudget;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The crash report writer of {@code docs/DESIGN-PROD.md} {@code API-PROD-013}: one text file per
 * fatal event in {@code crash-reports/<yyyyMMdd-HHmmss>-<shard_id>.txt}, carrying the trigger, the
 * thread dump, the effective configuration with its secrets reduced to {@value #SECRET_MARK}, the
 * plugins with their versions and the git head the build came from. Whatever the umask, a report is
 * created 0600 and the directory, when the writer is the one creating it, 0700.
 * <p>
 * Every caller is already on a failing path — a fatal error here, the tick watchdog of
 * {@code REQ-PROD-014} later — so {@link #write} never throws: each section is taken on its own and a
 * section that fails is written as its failure, and a report that cannot be written at all ends as
 * one budgeted ERROR line.
 */
final class CrashReports {

    private static final Logger LOGGER = LoggerFactory.getLogger(CrashReports.class);

    /** What a secret value is written as, so that the report says it was set without leaking it. */
    static final String SECRET_MARK = "<set>";

    /**
     * Configuration keys whose value is a secret. The repository keeps secrets in environment
     * variables ({@code SYNAXIS_*}, all of which are masked); this covers a secret passed as a
     * system property all the same.
     */
    private static final Pattern SECRET_KEY = Pattern.compile("(?i).*(password|secret|token|credential).*");

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
    private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY_DIRECTORY =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));

    /** Written by the build (server-app/build.gradle.kts), absent when running from an IDE. */
    private static final String BUILD_INFO = "/synaxis-build.properties";

    private final Path directory;
    private final String shardId;
    private final Clock clock;
    private final Supplier<String> configuration;
    private final Supplier<List<String>> plugins;

    /** A crash report that cannot be written is one fact about the directory, not one per crash. */
    private final LogBudget failures = new LogBudget(LOGGER, "crash reports not written");

    /**
     * @param directory     where the reports go, created on the first report
     * @param shardId       the shard id of this process, part of the file name
     * @param clock         the clock of the file name and the report header
     * @param configuration the effective configuration, already masked (see {@link #describe})
     * @param plugins       the enabled plugins, one {@code id version} entry each
     */
    CrashReports(Path directory, String shardId, Clock clock, Supplier<String> configuration,
                 Supplier<List<String>> plugins) {
        this.directory = directory;
        this.shardId = shardId;
        this.clock = clock;
        this.configuration = configuration;
        this.plugins = plugins;
    }

    /**
     * Writes one crash report.
     *
     * @param reason what triggered it, written first
     * @param cause  the throwable behind it, {@code null} when there is none (a stalled tick)
     * @return the file written, {@code null} when it could not be written
     */
    @Nullable Path write(String reason, @Nullable Throwable cause) {
        final String report = render(reason, cause);
        final String stamp = FILE_TIME.format(clock.instant().atZone(clock.getZone()));
        try {
            Files.createDirectories(directory, OWNER_ONLY_DIRECTORY);
            // Two reports inside one second get a suffix instead of overwriting the first one
            for (int attempt = 1; ; attempt++) {
                final String suffix = attempt == 1 ? "" : "-" + attempt;
                final Path file = directory.resolve(stamp + "-" + shardId + suffix + ".txt");
                try {
                    Files.writeString(Files.createFile(file, OWNER_ONLY), report, StandardCharsets.UTF_8);
                    return file;
                } catch (FileAlreadyExistsException _) {
                    // try the next suffix
                }
            }
        } catch (IOException | RuntimeException e) {
            failures.error(e, "Could not write a crash report: reason={} directory={}", reason, directory);
            return null;
        }
    }

    private String render(String reason, @Nullable Throwable cause) {
        final StringBuilder report = new StringBuilder(64 * 1024);
        report.append("Synaxis crash report\n");
        report.append("Time: ").append(clock.instant()).append('\n');
        report.append("Shard: ").append(shardId).append('\n');
        report.append("Reason: ").append(reason).append('\n');
        section(report, "Cause", () -> cause == null ? "(none)" : stackTrace(cause));
        section(report, "Git head", CrashReports::gitHead);
        section(report, "Plugins", () -> {
            final List<String> enabled = plugins.get();
            return enabled.isEmpty() ? "(none enabled)" : String.join("\n", enabled);
        });
        section(report, "Effective configuration", configuration);
        section(report, "Thread dump", CrashReports::threadDump);
        return report.toString();
    }

    private static void section(StringBuilder report, String title, Supplier<String> body) {
        report.append("\n-- ").append(title).append(" --\n");
        String text;
        try {
            text = body.get();
        } catch (RuntimeException e) {
            // One broken section must not cost the others, least of all the thread dump
            text = "(could not be collected: " + e + ")";
        }
        report.append(text);
        if (!text.endsWith("\n")) report.append('\n');
    }

    /**
     * The effective configuration as the crash report prints it: the engine's settings, then every
     * {@code synaxis.*} system property, the {@code SYNAXIS_*} environment variables and the JVM
     * arguments. Environment variable values are never printed — that is where the repository keeps
     * its secrets — and neither is any property whose name says it is a secret.
     *
     * @param settings    the engine settings, printed as they are (they carry no secret)
     * @param properties  the system properties
     * @param environment the environment variables
     * @param jvmArguments the JVM input arguments
     * @return the text of the section
     */
    static String describe(List<String> settings, Properties properties, Map<String, String> environment,
                           List<String> jvmArguments) {
        final StringBuilder text = new StringBuilder();
        for (String setting : settings) text.append(setting).append('\n');
        final Map<String, String> synaxisProperties = new TreeMap<>();
        for (String name : properties.stringPropertyNames()) {
            if (name.startsWith("synaxis.")) synaxisProperties.put(name, masked(name, properties.getProperty(name)));
        }
        synaxisProperties.forEach((name, value) -> text.append(name).append('=').append(value).append('\n'));
        new TreeMap<>(environment).keySet().stream()
                .filter(name -> name.startsWith("SYNAXIS_"))
                .forEach(name -> text.append("env ").append(name).append('=').append(SECRET_MARK).append('\n'));
        for (String argument : jvmArguments) text.append("jvm ").append(maskedArgument(argument)).append('\n');
        return text.toString();
    }

    private static String masked(String name, String value) {
        return SECRET_KEY.matcher(name).matches() ? SECRET_MARK : value;
    }

    private static String maskedArgument(String argument) {
        if (!argument.startsWith("-D")) return argument;
        final int equals = argument.indexOf('=');
        if (equals < 0) return argument;
        final String name = argument.substring(2, equals);
        return "-D" + name + "=" + masked(name, argument.substring(equals + 1));
    }

    static String gitHead() {
        try (InputStream stream = CrashReports.class.getResourceAsStream(BUILD_INFO)) {
            if (stream == null) return "unknown (no " + BUILD_INFO.substring(1) + " on the classpath)";
            final Properties info = new Properties();
            info.load(stream);
            return info.getProperty("git.head", "unknown");
        } catch (IOException e) {
            return "unknown (" + e + ")";
        }
    }

    private static String stackTrace(Throwable throwable) {
        final StringWriter text = new StringWriter();
        throwable.printStackTrace(new PrintWriter(text));
        return text.toString();
    }

    /**
     * Every platform thread with its whole stack and the locks it holds or waits on.
     * {@link ThreadInfo#toString()} is not used: it cuts every stack at eight frames, and a stalled
     * tick is usually deeper than that.
     */
    static String threadDump() {
        final StringBuilder dump = new StringBuilder();
        for (ThreadInfo thread : ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
            dump.append('"').append(thread.getThreadName()).append("\" id=").append(thread.getThreadId())
                    .append(' ').append(thread.getThreadState().name().toLowerCase(Locale.ROOT));
            if (thread.getLockName() != null) dump.append(" on ").append(thread.getLockName());
            if (thread.getLockOwnerName() != null)
                dump.append(" owned by \"").append(thread.getLockOwnerName()).append("\" id=").append(thread.getLockOwnerId());
            dump.append('\n');
            final StackTraceElement[] frames = thread.getStackTrace();
            final MonitorInfo[] monitors = thread.getLockedMonitors();
            for (int depth = 0; depth < frames.length; depth++) {
                dump.append("\tat ").append(frames[depth]).append('\n');
                for (MonitorInfo monitor : monitors) {
                    if (monitor.getLockedStackDepth() == depth) dump.append("\t- locked ").append(monitor).append('\n');
                }
            }
            for (LockInfo lock : thread.getLockedSynchronizers()) dump.append("\t- holds ").append(lock).append('\n');
            dump.append('\n');
        }
        return dump.toString();
    }
}
