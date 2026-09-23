package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.ServerProcess;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * The JVM {@link WatchdogHaltTest} starts and expects to be halted: a real server whose first tick
 * never ends, watched the way {@link Main} watches it. The watchdog durations come from the
 * {@code synaxis.watchdog.*} system properties, like in production.
 */
final class WatchdogHaltFixture {

    static final String STALLING = "stalling the tick";

    private WatchdogHaltFixture() {
    }

    /**
     * @param args the crash report directory
     */
    public static void main(String[] args) {
        final MinecraftServer server = MinecraftServer.init();
        final ServerProcess process = server.process();
        final CrashReports crashReports = new CrashReports(Path.of(args[0]), "watchdog-test", Clock.systemUTC(),
                () -> process.config().toString(), List::of);
        Main.startWatchdog(process, crashReports);
        process.scheduler().scheduleNextTick(WatchdogHaltFixture::stall);
        server.start("127.0.0.1", 0);
    }

    @SuppressWarnings("InfiniteLoopStatement") // stuck on purpose, until the watchdog halts the JVM
    private static void stall() {
        System.out.println(STALLING);
        while (true) LockSupport.park();
    }
}
