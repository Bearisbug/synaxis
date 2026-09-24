package io.github.bearisbug.synaxis.app;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The launcher of an MMO shard: the server application of {@link Main} with the shard defaults on
 * top ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-020}, {@code API-PROD-016}). The chunks are
 * spread over the tick threads by cells of the grid, and the shard takes all cores but two for
 * them, leaving those to the tick scheduler, the network carriers and the GC. The RTS process is
 * launched through {@link Main} and keeps the engine defaults, the counter and one tick thread.
 * <p>
 * A value the operator passed on the command line wins, {@code -Dsynaxis.thread-provider=counter}
 * takes a shard back to the engine provider.
 */
public final class ShardMain {

    static final String THREAD_PROVIDER = "synaxis.thread-provider";
    static final String DISPATCHER_THREADS = "synaxis.dispatcher-threads";

    private ShardMain() {
    }

    public static void main(String[] args) {
        // Before Main reads anything: the engine takes its settings while the process is created
        threadDefaults(System::getProperty, Runtime.getRuntime().availableProcessors()).forEach(System::setProperty);
        Main.main(args);
    }

    /**
     * Returns the thread properties to set, the ones the command line left out.
     *
     * @param properties the system properties already set
     * @param cores      the processors of the machine
     * @return the properties and their values
     */
    static Map<String, String> threadDefaults(UnaryOperator<String> properties, int cores) {
        final Map<String, String> defaults = new LinkedHashMap<>();
        if (properties.apply(THREAD_PROVIDER) == null) defaults.put(THREAD_PROVIDER, "grid");
        if (properties.apply(DISPATCHER_THREADS) == null)
            defaults.put(DISPATCHER_THREADS, Integer.toString(Math.max(1, cores - 2)));
        return defaults;
    }
}
