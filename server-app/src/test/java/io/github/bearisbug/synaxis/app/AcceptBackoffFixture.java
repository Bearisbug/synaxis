package io.github.bearisbug.synaxis.app;

import com.sun.management.OperatingSystemMXBean;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.network.socket.Server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The JVM {@link AcceptBackoffTest} starts under a low {@code ulimit -n}: a server whose process
 * then uses up every file descriptor, so that each accept fails for as long as a connection is
 * waiting in the backlog.
 */
final class AcceptBackoffFixture {

    static final String EXHAUSTED = "file descriptors exhausted";
    static final String CPU = "accept loop cpu=";

    private AcceptBackoffFixture() {
    }

    /**
     * Prints the port, uses up the file descriptors, waits for a line on standard input (sent once
     * the test has a connection waiting in the backlog), then prints the share of a core the
     * process used over the {@code args[0]} seconds that follow.
     */
    public static void main(String[] args) throws Exception {
        final long window = Long.parseLong(args[0]) * 1_000_000_000L;
        final OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        final long warm = os.getProcessCpuTime(); // loaded before the descriptors run out
        final BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        final ServerProcess process = ServerProcess.of();
        final Server server = process.server();
        server.init(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        server.start();
        System.out.println("port=" + server.getPort());

        // One connection in and out first: the accept thread parks through the JDK poller, which
        // opens descriptors of its own the first time it is used
        try (SocketChannel warmUp = SocketChannel.open(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getPort()))) {
            awaitConnections(process, 1);
            warmUp.shutdownOutput();
        }
        awaitConnections(process, 0);

        final List<FileChannel> held = new ArrayList<>();
        try {
            while (true) held.add(FileChannel.open(Path.of("/dev/null")));
        } catch (IOException _) {
            // The limit
        }
        System.out.println(EXHAUSTED + " after " + held.size() + " more (" + warm + ")");

        var _ = stdin.readLine();
        final long cpuStart = os.getProcessCpuTime();
        final long wallStart = System.nanoTime();
        Thread.sleep(window / 1_000_000L);
        final double share = (double) (os.getProcessCpuTime() - cpuStart) / (System.nanoTime() - wallStart);
        System.out.println(CPU + String.format(Locale.ROOT, "%.4f", share));
        System.out.flush();
        Runtime.getRuntime().halt(0);
    }

    private static void awaitConnections(ServerProcess process, int count) throws InterruptedException {
        for (int i = 0; i < 400 && process.connection().gate().connections() != count; i++) Thread.sleep(25);
        if (process.connection().gate().connections() != count)
            throw new IllegalStateException("the warm-up connection never came and went");
    }
}
