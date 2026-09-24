package io.github.bearisbug.synaxis.loadtest;

import com.google.gson.JsonObject;
import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginStartPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * {@code flood}: the gate attack of {@code docs/DESIGN-PROD.md} §15 "闸门" — one address opening
 * and closing login connections at {@code --ip-rate} for {@code --duration}. Each connection sends
 * a handshake and a login start, waits up to {@code --hold} for the first answer and hangs up.
 * What came back is counted: an answer, a close without one, or nothing before the hold ran out.
 */
final class FloodCommand {

    private FloodCommand() {
    }

    static int run(Args args) throws IOException, InterruptedException {
        final String host = args.string("host", "127.0.0.1");
        final int port = args.integer("port", 25565);
        final double rate = args.rate("ip-rate", 100);
        final double duration = args.seconds("duration", 60);
        final int holdMillis = (int) (args.seconds("hold", 0.2) * 1000);
        final Path out = Path.of(args.required("out"));
        args.requireAllRead();

        final Map<String, LongAdder> outcomes = new ConcurrentHashMap<>();
        final LongAdder opened = new LongAdder();
        final long start = System.nanoTime();
        final long count = (long) (rate * duration);
        for (long i = 0; i < count; i++) {
            final long due = start + (long) (i / rate * TimeUnit.SECONDS.toNanos(1));
            final long wait = due - System.nanoTime();
            if (wait > 0) LockSupport.parkNanos(wait);
            final long number = i;
            Thread.ofVirtual().start(() -> {
                BotStats.count(outcomes, attempt(host, port, holdMillis, number));
                opened.increment();
            });
        }
        TimeUnit.MILLISECONDS.sleep(holdMillis + 2_000L);
        final JsonObject json = new JsonObject();
        json.addProperty("kind", "flood");
        json.addProperty("host", host);
        json.addProperty("port", port);
        json.addProperty("rate_per_s", rate);
        json.addProperty("duration_s", duration);
        json.addProperty("attempts", count);
        json.addProperty("finished", opened.sum());
        json.add("outcomes", BotStats.counts(outcomes));
        json.addProperty("elapsed_s", Histogram.round((System.nanoTime() - start) / 1e9));
        Json.write(out, json);
        System.out.printf(Locale.ROOT, "flood: %d attempts, %s; wrote %s%n", count, json.get("outcomes"), out);
        return 0;
    }

    private static String attempt(String host, int port, int holdMillis, long number) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2_000);
            socket.setSoTimeout(holdMillis);
            final OutputStream out = socket.getOutputStream();
            final String name = String.format(Locale.ROOT, "flood%06d", number % 1_000_000);
            out.write(frame(Protocol.encode(ConnectionState.HANDSHAKE, new ClientHandshakePacket(
                    MinecraftServer.PROTOCOL_VERSION, host, port, ClientHandshakePacket.Intent.LOGIN))));
            out.write(frame(Protocol.encode(ConnectionState.LOGIN, new ClientLoginStartPacket(name,
                    GameProfile.offlineUuid(name)))));
            out.flush();
            final InputStream in = socket.getInputStream();
            try {
                return in.read() < 0 ? "closed_without_answer" : "answered";
            } catch (SocketTimeoutException _) {
                return "no_answer_in_hold";
            }
        } catch (IOException e) {
            return "failed: " + e.getClass().getSimpleName();
        }
    }

    private static byte[] frame(byte[] body) {
        final ByteArrayOutputStream frame = new ByteArrayOutputStream(body.length + 3);
        int remaining = body.length;
        while ((remaining & ~0x7F) != 0) {
            frame.write((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        frame.write(remaining);
        frame.writeBytes(body);
        return frame.toByteArray();
    }
}
