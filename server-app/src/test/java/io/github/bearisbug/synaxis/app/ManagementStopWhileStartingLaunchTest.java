package io.github.bearisbug.synaxis.app;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.bearisbug.synaxis.plugin.SynaxisPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code minecraft:server/stop} while a real Main is still starting, in a JVM of its own
 * ({@code docs/DESIGN-PROD.md} {@code API-PROD-022} the {@code server/stop} row, §11.4 below the
 * table, §19 the scenario of a stop while starting). The management interface listens before the
 * gameplay plugin is enabled; a stop that got through then ran the whole shutdown sequence before
 * Main registered its shutdown tasks, and Main went on to bind the game port of a process that no
 * longer ticked and never exited. Refused then, the process starts as usual, and a stop once it is
 * ready ends it. {@link GameplayFixturePlugin} holds the startup in its {@code onEnable} until the
 * test has had the refusal.
 */
public class ManagementStopWhileStartingLaunchTest {

    private static final String READY = "synaxis server-app listening on";
    private static final Pattern LISTENING = Pattern.compile("Management interface listening on 127\\.0\\.0\\.1:(\\d+)");

    @Test
    public void aStopWhileThePluginsAreEnabledIsRefusedAndOneOnceReadyEndsTheProcess(@TempDir Path directory)
            throws Exception {
        final byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        final String token = HexFormat.of().formatHex(secret);
        // The plugin is listed by a services file on a class path entry of this test alone
        final Path classes = directory.resolve("classes");
        Files.writeString(Files.createDirectories(classes.resolve("META-INF/services"))
                .resolve(SynaxisPlugin.class.getName()), GameplayFixturePlugin.class.getName() + "\n");
        final Path release = directory.resolve("release");
        final List<String> command = List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx1g",
                "-cp", classes + File.pathSeparator + System.getProperty("java.class.path"),
                "-Dlogback.configurationFile=logback.xml", "-D" + GameplayFixturePlugin.RELEASE + "=" + release,
                "-Dsynaxis.app.log=stdout", "-Dsynaxis.app.auth=offline",
                // Ports the system picks: one taken in between by another test would refuse the start
                "-Dsynaxis.app.port=0", "-Dsynaxis.app.metrics.bind=127.0.0.1:0",
                "-Dsynaxis.app.management.enabled=true", "-Dsynaxis.app.management.bind=127.0.0.1:0",
                Main.class.getName());
        final ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        builder.environment().put("SYNAXIS_MANAGEMENT_TOKEN", token);
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
        try (Socket management = connectOnceListening(output, process)) {
            upgrade(management, token);

            // The port answers before the plugin is enabled: this call lands while onEnable waits for the release
            final JsonObject early = call(management, 1, "minecraft:server/stop");
            final String logAtTheReply = String.join("\n", output);

            assertTrue(early.has("error"), "a stop before the game port is bound is refused, not "
                    + early + ":\n" + logAtTheReply);
            assertEquals(-32000, early.getAsJsonObject("error").get("code").getAsInt(), early.toString());
            Files.createFile(release);
            awaitLine(output, process, READY);
            assertTrue(String.join("\n", output).lines().noneMatch(line -> line.contains("Stopping synaxis server.")),
                    "nothing stopped:\n" + String.join("\n", output));
            assertTrue(process.isAlive(), "the process is up after its ready line");

            final JsonObject late = call(management, 2, "minecraft:server/stop");
            assertTrue(late.has("result") && late.get("result").getAsBoolean(), "a stop once ready ends it: " + late);
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the JVM exits:\n" + String.join("\n", output));
            reader.join(TimeUnit.SECONDS.toMillis(10));
            final String log = String.join("\n", output);
            assertEquals(0, process.exitValue(), log);
            assertTrue(log.contains("synaxis server stopped successfully."), log);
            assertTrue(log.contains("Disabling plugin: plugin_id=" + GameplayFixturePlugin.ID),
                    "the shutdown tasks ran, onDisable among them:\n" + log);
            assertEquals(List.of("unavailable", "ok"), stopResults(directory));
        } finally {
            process.destroyForcibly();
            process.waitFor(30, TimeUnit.SECONDS);
            reader.join(TimeUnit.SECONDS.toMillis(10));
        }
    }

    /** Connects as soon as the interface says where it listens, looking every 2 ms. */
    private static Socket connectOnceListening(List<String> output, Process process) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (true) {
            for (String line : output) {
                final Matcher listening = LISTENING.matcher(line);
                if (!listening.find()) continue;
                final Socket socket = new Socket(Proxy.NO_PROXY);
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                        Integer.parseInt(listening.group(1))), 1_000);
                socket.setSoTimeout(30_000);
                return socket;
            }
            if (!process.isAlive() || System.nanoTime() > deadline)
                throw new AssertionError("the management interface never listened:\n" + String.join("\n", output));
            Thread.sleep(2);
        }
    }

    private static void upgrade(Socket socket, String token) throws IOException {
        socket.getOutputStream().write(("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n"
                + "Authorization: Bearer " + token + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        final ByteArrayOutputStream answer = new ByteArrayOutputStream();
        final InputStream in = socket.getInputStream();
        while (!answer.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) answer.write(in.read());
        assertTrue(answer.toString(StandardCharsets.ISO_8859_1).startsWith("HTTP/1.1 101"),
                answer.toString(StandardCharsets.ISO_8859_1));
    }

    /** Sends one short request as a masked text frame and reads the one text frame answering it. */
    private static JsonObject call(Socket socket, int id, String method) throws IOException {
        final byte[] payload = ("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        final ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x81);
        frame.write(0x80 | payload.length);
        final byte[] mask = {0x11, 0x22, 0x33, 0x44};
        frame.writeBytes(mask);
        for (int index = 0; index < payload.length; index++) frame.write(payload[index] ^ mask[index % 4]);
        socket.getOutputStream().write(frame.toByteArray());
        final InputStream in = socket.getInputStream();
        assertEquals(0x81, in.read(), "a final text frame");
        final int first = in.read();
        final int length = first < 126 ? first : (in.read() << 8) | in.read();
        return JsonParser.parseString(new String(in.readNBytes(length), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void awaitLine(List<String> output, Process process, String text) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (output.stream().noneMatch(line -> line.contains(text))) {
            if (!process.isAlive() || System.nanoTime() > deadline)
                throw new AssertionError("no line with \"" + text + "\":\n" + String.join("\n", output));
            Thread.sleep(50);
        }
    }

    /** The results the ledger recorded for minecraft:server/stop, in order. */
    private static List<String> stopResults(Path directory) throws IOException {
        final List<String> results = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory.resolve("logs"))) {
            for (Path file : files.filter(path -> path.getFileName().toString().startsWith("events-")).sorted().toList()) {
                for (String line : Files.readAllLines(file)) {
                    if (!line.contains("\"minecraft:server/stop\"")) continue;
                    results.add(JsonParser.parseString(line).getAsJsonObject().get("result").getAsString());
                }
            }
        }
        return results;
    }
}
