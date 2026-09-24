package io.github.bearisbug.synaxis.app;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Connection gate L0' ({@code docs/DESIGN-PROD.md} {@code API-PROD-003}): a process out of file
 * descriptors fails every accept for as long as connections keep coming. The accept loop backs off
 * {@code synaxis.accept-backoff-ms} after each failure instead of spinning on a core, so it fails at
 * most ten times a second whatever the rate of connections, and reports the failures as at most one
 * line per 10 seconds.
 */
public class AcceptBackoffTest {

    private static final int WINDOW_SECONDS = 11;
    private static final Pattern FAILED = Pattern.compile("Accepting a connection failed (\\d+) times since the last report");

    @Test
    public void aProcessOutOfFileDescriptorsBacksOffAndReportsOncePerTenSeconds() throws Exception {
        final String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        final Process process = new ProcessBuilder("/bin/sh", "-c", "ulimit -n 256 && exec \"$0\" \"$@\"",
                java, "-cp", System.getProperty("java.class.path"),
                AcceptBackoffFixture.class.getName(), Integer.toString(WINDOW_SECONDS))
                .redirectErrorStream(true)
                .start();
        final List<String> output = new CopyOnWriteArrayList<>();
        final Thread reader = Thread.ofPlatform().start(() -> {
            try (BufferedReader lines = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) output.add(line);
            } catch (IOException _) {
                // the JVM is gone, what it wrote is in `output`
            }
        });
        try {
            final int port = Integer.parseInt(awaitLine(output, "port=").substring("port=".length()));
            awaitLine(output, AcceptBackoffFixture.EXHAUSTED);
            final OutputStream stdin = process.getOutputStream();
            stdin.write("go\n".getBytes(StandardCharsets.UTF_8));
            stdin.flush();
            // Connections keep coming for the whole window, far faster than one per backoff. Some
            // systems drop the connection an accept failed on (macOS), others keep it waiting and
            // fail again at once (Linux): either way there is always one to fail on.
            final List<Socket> waiting = new ArrayList<>();
            try {
                while (process.isAlive() && waiting.size() < 2_000) {
                    final Socket socket = new Socket();
                    try {
                        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 50);
                        waiting.add(socket);
                    } catch (IOException _) {
                        socket.close(); // The backlog is full
                    }
                    Thread.sleep(20);
                }
                assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the fixture ends");
            } finally {
                for (Socket socket : waiting) socket.close();
            }
            reader.join(TimeUnit.SECONDS.toMillis(10));
            final String log = String.join("\n", output);

            final String cpu = awaitLine(output, AcceptBackoffFixture.CPU);
            final double share = Double.parseDouble(cpu.substring(cpu.indexOf('=') + 1));
            // Backing off, the loop takes 0.006 to 0.039 of a core on Linux (each retry costs the
            // carriers 1 to 4 ms) and 0.003 to 0.011 on macOS; without the backoff it takes 1.5 to
            // 1.8 on Linux. A quarter of a core is several times away from both.
            assertTrue(share < 0.25, "the accept loop used " + share + " of a core while every accept failed: " + log);

            final List<Integer> reported = output.stream().map(FAILED::matcher).filter(Matcher::find)
                    .map(matcher -> Integer.parseInt(matcher.group(1))).toList();
            assertEquals(2, reported.size(), "one line when the failures start and one 10 s later: " + log);
            assertEquals(1, reported.getFirst(), log);
            assertTrue(reported.get(1) >= 50 && reported.get(1) <= 110,
                    "the second line carries the ~100 failures of 10 s at one per 100 ms: " + log);
            assertTrue(log.contains("retrying every 100 ms: java.io.IOException: Too many open files"), log);
        } finally {
            process.destroyForcibly();
        }
    }

    private static String awaitLine(List<String> output, String prefix) throws InterruptedException {
        for (int i = 0; i < 600; i++) {
            for (String line : output) if (line.startsWith(prefix)) return line;
            Thread.sleep(50);
        }
        throw new AssertionError("no line starting with " + prefix + ": " + String.join("\n", output));
    }
}
