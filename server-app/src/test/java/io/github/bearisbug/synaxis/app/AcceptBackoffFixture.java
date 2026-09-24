package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.network.socket.Server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
     * accept loop used over the {@code args[0]} seconds that follow.
     */
    public static void main(String[] args) throws Exception {
        final long window = Long.parseLong(args[0]) * 1_000_000_000L;
        final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        final String carriers = carrierPrefix();
        cpuTimes(threads, carriers); // loaded before the descriptors run out
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
        System.out.println(EXHAUSTED + " after " + held.size() + " more");

        var _ = stdin.readLine();
        // The accept loop is a virtual thread, which has no CPU time of its own: it runs on the
        // carriers of the virtual thread scheduler, where nothing else in this JVM runs while no
        // connection has been accepted. The CPU time of the process would also count the JIT still compiling
        // what the start loaded and the GC, neither of which is the loop.
        final Map<Long, Long> cpuStart = cpuTimes(threads, carriers);
        final long wallStart = System.nanoTime();
        Thread.sleep(window / 1_000_000L);
        final Map<Long, Long> cpuEnd = cpuTimes(threads, carriers);
        final long wall = System.nanoTime() - wallStart;
        long cpu = 0;
        for (Map.Entry<Long, Long> entry : cpuEnd.entrySet())
            cpu += entry.getValue() - cpuStart.getOrDefault(entry.getKey(), 0L); // a new carrier ran only in the window
        System.out.println(CPU + String.format(Locale.ROOT, "%.4f", (double) cpu / wall));
        System.out.flush();
        Runtime.getRuntime().halt(0);
    }

    /** The name the carriers of the virtual thread scheduler start with, {@code ForkJoinPool-1-}. */
    private static String carrierPrefix() throws InterruptedException {
        final String[] name = new String[1];
        // "VirtualThread[#22]/runnable@ForkJoinPool-1-worker-1"
        Thread.ofVirtual().start(() -> name[0] = Thread.currentThread().toString()).join();
        final String carrier = name[0].substring(name[0].indexOf('@') + 1);
        return carrier.substring(0, carrier.indexOf("worker-"));
    }

    /** The CPU time of each carrier and of the scheduler's timer thread, by thread id. */
    private static Map<Long, Long> cpuTimes(ThreadMXBean threads, String carriers) {
        final Map<Long, Long> times = new HashMap<>();
        for (ThreadInfo info : threads.getThreadInfo(threads.getAllThreadIds())) {
            if (info == null || !info.getThreadName().startsWith(carriers)) continue;
            final long time = threads.getThreadCpuTime(info.getThreadId());
            if (time >= 0) times.put(info.getThreadId(), time);
        }
        return times;
    }

    private static void awaitConnections(ServerProcess process, int count) throws InterruptedException {
        for (int i = 0; i < 400 && process.connection().gate().connections() != count; i++) Thread.sleep(25);
        if (process.connection().gate().connections() != count)
            throw new IllegalStateException("the warm-up connection never came and went");
    }
}
