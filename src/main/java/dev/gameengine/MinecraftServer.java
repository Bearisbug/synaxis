package dev.gameengine;

import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Thin launcher around a {@link ServerProcess}: it creates one, holds it, and starts its threads.
 * <p>
 * Everything the server owns is reached through the process, which can also be created directly with
 * {@link ServerProcess#of()} when the caller wants to hold several of them in the same JVM.
 */
public final class MinecraftServer implements MinecraftConstants {

    public static final ComponentLogger LOGGER = ComponentLogger.logger(MinecraftServer.class);

    // Threads
    public static final String THREAD_NAME_BENCHMARK = "Ge-Benchmark";

    // Config
    // Can be modified at performance cost when increased
    @Deprecated
    public static final int TICK_PER_SECOND = JvmFlags.SERVER_TICKS_PER_SECOND;
    public static final int TICK_MS = 1000 / TICK_PER_SECOND;

    private final ServerProcess process;

    private MinecraftServer(ServerProcess process) {
        this.process = process;
    }

    public static MinecraftServer init(Auth auth) {
        return new MinecraftServer(ServerProcess.of(auth));
    }

    public static MinecraftServer init() {
        return init(new Auth.Offline());
    }

    /**
     * Gets the process created by {@link #init()}.
     *
     * @return the server process
     */
    public ServerProcess process() {
        return process;
    }

    /**
     * Starts the server.
     * <p>
     * It should be called after {@link #init()} and probably your own initialization code.
     *
     * @param address the server address
     * @throws IllegalStateException if the server is already running
     */
    public void start(SocketAddress address) {
        process.start(address);
        process.dispatcher().start();
        new TickSchedulerThread(process, 1_000_000_000L / TICK_PER_SECOND,
                process.config().maxTickCatchUp()).start();
    }

    public void start(String address, int port) {
        start(new InetSocketAddress(address, port));
    }

    /**
     * Stops this server properly (saves if needed, kicking players, etc.)
     */
    public void stop() {
        process.stop();
    }
}
