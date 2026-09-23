package io.github.bearisbug.synaxis.app;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.rolling.helper.CompressionMode;
import ch.qos.logback.core.status.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server log configuration of {@code docs/DESIGN-PROD.md} {@code API-PROD-014}
 * ({@code REQ-PROD-016}): the production {@code logback.xml}, loaded into a context of its own with
 * the log directory moved into a temporary directory.
 * <p>
 * The tests themselves run on {@code logback-test.xml}, which logback prefers, so that they do not
 * write {@code logs/latest.log} into the module directory.
 */
class LogbackConfigurationTest {

    private static final String MAX_FILE_SIZE = "<maxFileSize>256MB</maxFileSize>";

    private static String resource(String name) throws IOException {
        try (InputStream in = Objects.requireNonNull(LogbackConfigurationTest.class.getResourceAsStream("/" + name), name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * {@code logback.xml} with the file mode's {@code <include>} written out in place, which is
     * what lets the test move {@code logs/} somewhere else: the included resource is read from the
     * classpath as it is.
     */
    private static String fileModeConfiguration() throws IOException {
        final String included = resource("logback-file.xml");
        final String body = included.substring(included.indexOf("<included>") + "<included>".length(),
                included.indexOf("</included>"));
        final String include = "<include resource=\"logback-${synaxis.app.log:-file}.xml\"/>";
        final String main = resource("logback.xml");
        assertTrue(main.contains(include), "the mode picks the included resource");
        return main.replace(include, body);
    }

    private static LoggerContext configure(String xml, Path directory, String mode) throws JoranException {
        final LoggerContext context = new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter());
        context.putProperty("synaxis.app.log", mode);
        final JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(new ByteArrayInputStream(
                xml.replace("logs/", directory + "/").getBytes(StandardCharsets.UTF_8)));
        return context;
    }

    /** Logback prints its whole status list on the console as soon as one entry is a warning. */
    private static void assertNoConfigurationWarning(LoggerContext context) {
        final List<String> warnings = context.getStatusManager().getCopyOfStatusList().stream()
                .filter(status -> status.getLevel() >= Status.WARN).map(Status::getMessage).toList();
        assertEquals(List.of(), warnings);
    }

    private static List<String> files(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> listing = Files.list(directory)) {
            return listing.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void theFileModeHasTheDesignedRetentionBehindANonBlockingQueue(@TempDir Path directory) throws Exception {
        final String xml = fileModeConfiguration();
        assertTrue(xml.contains(MAX_FILE_SIZE), "a file rolls at 256 MB");
        assertTrue(xml.contains("<totalSizeCap>10GB</totalSizeCap>"), "all files together stay under 10 GB");
        final LoggerContext context = configure(xml, directory, "file");
        try {
            assertNoConfigurationWarning(context);
            final Appender<ILoggingEvent> file = context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("FILE");
            final AsyncAppender async = assertInstanceOf(AsyncAppender.class, file);
            assertEquals(8192, async.getQueueSize());
            assertTrue(async.isNeverBlock(), "a full queue drops a line rather than blocking a tick");
            final RollingFileAppender<?> rolling =
                    assertInstanceOf(RollingFileAppender.class, async.getAppender("ROLLING"));
            assertEquals(directory + "/latest.log", rolling.getFile());
            final SizeAndTimeBasedRollingPolicy<?> policy =
                    assertInstanceOf(SizeAndTimeBasedRollingPolicy.class, rolling.getRollingPolicy());
            assertEquals(14, policy.getMaxHistory());
            assertEquals(CompressionMode.GZ, policy.getCompressionMode());
            assertTrue(policy.getFileNamePattern().contains("%d{yyyy-MM-dd}"), "and it rolls every day");
        } finally {
            context.stop();
        }
    }

    // §19: "日志按天或按大小滚动并压缩", with the file size cap lowered to 1 MB
    @Test
    void threeMegabytesOfLogRollIntoCompressedFiles(@TempDir Path directory) throws Exception {
        // The console stays out of it: 3 MB of it would only fill the test report
        final LoggerContext context = configure(fileModeConfiguration()
                .replace(MAX_FILE_SIZE, "<maxFileSize>1MB</maxFileSize>")
                .replace("<appender-ref ref=\"STDOUT\"/>", ""), directory, "file");
        final Logger logger = context.getLogger("rolling");
        final String line = "x".repeat(1_000);
        for (int i = 0; i < 3_000; i++) logger.info("{} {}", i, line);
        context.stop();

        final List<String> written = awaitCompressedFiles(directory, 2);
        assertTrue(written.contains("latest.log"), written.toString());
        assertTrue(written.stream().filter(name -> name.endsWith(".log.gz")).count() >= 2, written.toString());
    }

    @Test
    void theStdoutModeWritesNoFile(@TempDir Path directory) throws Exception {
        final LoggerContext context = configure(resource("logback.xml"), directory, "stdout");
        assertNoConfigurationWarning(context);
        final List<String> appenders = new ArrayList<>();
        context.getLogger(Logger.ROOT_LOGGER_NAME).iteratorForAppenders()
                .forEachRemaining(appender -> appenders.add(appender.getName()));
        context.getLogger("stdout").info("only on the console");
        context.stop();

        assertEquals(List.of("STDOUT"), appenders, "the console is all there is");
        assertEquals(List.of(), files(directory));
    }

    @Test
    void aModeThatDoesNotExistIsReportedByLogback(@TempDir Path directory) throws Exception {
        final LoggerContext context = configure(resource("logback.xml"), directory, "files");
        try {
            final List<String> warnings = context.getStatusManager().getCopyOfStatusList().stream()
                    .filter(status -> status.getLevel() >= Status.WARN).map(Status::getMessage).toList();
            assertTrue(warnings.stream().anyMatch(message -> message.contains("logback-files.xml")), warnings.toString());
        } finally {
            context.stop();
        }
    }

    /** Compression runs on a thread of its own and can outlive the stop of the context. */
    private static List<String> awaitCompressedFiles(Path directory, int count) throws IOException, InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        List<String> written = files(directory);
        while (written.stream().filter(name -> name.endsWith(".log.gz")).count() < count
                || written.stream().anyMatch(name -> name.endsWith(".tmp"))) {
            if (System.nanoTime() > deadline) break;
            TimeUnit.MILLISECONDS.sleep(50);
            written = files(directory);
        }
        return written;
    }
}
