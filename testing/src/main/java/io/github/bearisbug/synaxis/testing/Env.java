package io.github.bearisbug.synaxis.testing;

import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventFilter;
import io.github.bearisbug.synaxis.instance.ChunkLoader;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;

public interface Env extends AutoCloseable {
    /**
     * An environment on a process of its own, configured as the {@link EnvTest} one plus
     * {@code settings}, for a test that needs a setting that one does not have. The caller closes
     * it when done, with try-with-resources or {@link #close()} in an {@code @AfterEach};
     * {@code process().stop()} alone skips the failure checks of {@link #close()}.
     *
     * @param settings the changes to the {@link EnvTest} configuration
     * @return the environment
     */
    static Env create(UnaryOperator<ServerConfig.Builder> settings) {
        return new EnvImpl(ServerProcess.of(settings.apply(EnvTestExt.CONFIG.toBuilder()).build()));
    }

    ServerProcess process();

    /**
     * Stops the process. The environments this module hands out ({@link EnvTest}, {@link #create})
     * first fail the test with what the server threw since the last check and with every listener of
     * {@link #listen} whose last follow-up never ran, and stop the process even when that fails.
     */
    @Override
    default void close() {
        process().stop();
    }

    TestConnection createConnection(GameProfile gameProfile);

    default TestConnection createConnection() {
        return createConnection(new GameProfile(UUID.randomUUID(), "RandName"));
    }

    /**
     * Tracks the events of a type that reach the event node of {@code actor}, until the first
     * {@link Collector#collect()} of the returned collector. For an entity or an instance that node
     * is the one it listens on itself; collecting takes only this tracker's listener off it, so the
     * actor's own listeners and the other trackers on it keep hearing.
     *
     * @param eventType the event type
     * @param filter    how an event finds its actor
     * @param actor     the actor
     * @param <E>       the event type
     * @param <H>       the type of the actor
     * @return the collector
     */
    <E extends Event, H> Collector<E> trackEvent(Class<E> eventType, EventFilter<? super E, H> filter, H actor);

    <E extends Event> FlexibleListener<E> listen(Class<E> eventType);

    default void tick() {
        process().ticker().tick(System.nanoTime());
    }

    /**
     * Ticks through {@link #tick()} for as long as the condition holds, so what the server throws
     * meanwhile fails the test at the tick it happened.
     *
     * @param condition checked before every tick
     * @param timeout   how long to tick at most, null for no limit
     * @return true once the condition is false, false if the timeout ran out first
     */
    default boolean tickWhile(BooleanSupplier condition, @Nullable Duration timeout) {
        final long start = System.nanoTime();
        while (condition.getAsBoolean()) {
            tick();
            if (timeout != null && System.nanoTime() - start > timeout.toNanos()) {
                return false;
            }
        }
        return true;
    }

    default Player createPlayer(Instance instance, Pos pos) {
        return createConnection().connect(instance, pos);
    }

    default Instance createFlatInstance() {
        return createFlatInstance(null);
    }

    default Instance createFlatInstance(@Nullable ChunkLoader chunkLoader) {
        var instance = process().instance().createInstanceContainer(chunkLoader);
        instance.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.STONE));
        return instance;
    }

    default Instance createEmptyInstance() {
        return process().instance().createInstanceContainer();
    }

    default Instance createEmptyInstance(ChunkLoader chunkLoader) {
        return process().instance().createInstanceContainer(chunkLoader);
    }

    default void destroyInstance(Instance instance) {
        process().instance().unregisterInstance(instance);
    }
}
