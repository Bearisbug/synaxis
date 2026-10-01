package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.Launched;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.freePort;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ports of a real Main in a JVM of its own ({@code docs/DESIGN-PROD.md} {@code API-PROD-015},
 * {@code API-PROD-022}, §19 the scenarios of a game port taken and of a wildcard bind, §28.2 the row
 * of a game port that cannot be bound). A game port that cannot be bound refuses the start the way
 * the two operations ports do, and the process exits with 1; it used to stay alive with its metrics
 * endpoint answering. Either operations port bound to the wildcard address is warned about once at
 * the start; a specific address, loopback here, is not.
 */
public class OpsPortsLaunchTest {

    private static final String READY = "synaxis server-app listening on";
    private static final String WILDCARD = "is the wildcard address";

    @Test
    public void aGamePortTakenRefusesTheStart(@TempDir Path directory) throws Exception {
        final int port = freePort();
        // Bound the way the engine binds the game port, so that its bind fails on every system
        try (ServerSocketChannel taken = ServerSocketChannel.open(StandardProtocolFamily.INET)) {
            taken.bind(new InetSocketAddress("0.0.0.0", port));
            try (Launched main = Launched.start(directory, Main.class, "-Dsynaxis.app.log=stdout",
                    "-Dsynaxis.app.auth=offline", "-Dsynaxis.app.core-mode=on", "-Dsynaxis.app.port=" + port,
                    "-Dsynaxis.app.metrics.bind=127.0.0.1:0")) {
                assertEquals(1, main.awaitExit(), main.log());
                final String log = main.log();
                assertTrue(log.contains("Refusing to start: synaxis.app.port=" + port + " cannot be bound: "), log);
                assertTrue(log.contains("Metrics endpoint listening on"), "refused with the metrics endpoint up: " + log);
                assertFalse(log.contains(READY), log);
                assertFalse(log.contains(WILDCARD), "a loopback endpoint is not warned about: " + log);
            }
        }
    }

    @Test
    public void aWildcardBindOfEitherOperationsPortIsWarnedAboutAtTheStart(@TempDir Path directory) throws Exception {
        final byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        final ProcessBuilder builder = new ProcessBuilder(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx1g",
                "-cp", System.getProperty("java.class.path"), "-Dlogback.configurationFile=logback.xml",
                "-Dsynaxis.app.log=stdout", "-Dsynaxis.app.auth=offline", "-Dsynaxis.app.core-mode=on",
                "-Dsynaxis.app.port=0", "-Dsynaxis.app.metrics.bind=0.0.0.0:0",
                "-Dsynaxis.app.management.enabled=true", "-Dsynaxis.app.management.bind=[::]:0",
                Main.class.getName())).directory(directory.toFile()).redirectErrorStream(true);
        builder.environment().put("SYNAXIS_MANAGEMENT_TOKEN", HexFormat.of().formatHex(secret));
        final Process process = builder.start();
        final List<String> output = new CopyOnWriteArrayList<>();
        final Thread reader = Thread.ofPlatform().start(() -> {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(process.getInputStream(),
                    StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) output.add(line);
            } catch (IOException _) {
                // the JVM is gone, what it wrote is in `output`
            }
        });
        try {
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            while (output.stream().noneMatch(line -> line.contains(READY))) {
                if (!process.isAlive() || System.nanoTime() > deadline)
                    throw new AssertionError("never ready:\n" + String.join("\n", output));
                Thread.sleep(50);
            }
            final List<String> warned = output.stream().filter(line -> line.contains(WILDCARD)).toList();
            assertEquals(2, warned.size(), String.join("\n", output));
            assertTrue(warned.getFirst().contains("WARN") && warned.getFirst().contains("synaxis.app.metrics.bind=0.0.0.0:0 "),
                    warned.getFirst());
            assertTrue(warned.getLast().contains("WARN")
                    && warned.getLast().contains("synaxis.app.management.bind=[0:0:0:0:0:0:0:0]:0 "), warned.getLast());
        } finally {
            process.destroyForcibly();
            process.waitFor(30, TimeUnit.SECONDS);
            reader.join(TimeUnit.SECONDS.toMillis(10));
        }
    }
}
