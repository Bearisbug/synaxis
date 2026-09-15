package io.github.bearisbug.synaxis.testing;

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

public interface Env {
    ServerProcess process();

    TestConnection createConnection(GameProfile gameProfile);

    default TestConnection createConnection() {
        return createConnection(new GameProfile(UUID.randomUUID(), "RandName"));
    }

    <E extends Event, H> Collector<E> trackEvent(Class<E> eventType, EventFilter<? super E, H> filter, H actor);

    <E extends Event> FlexibleListener<E> listen(Class<E> eventType);

    default void tick() {
        process().ticker().tick(System.nanoTime());
    }

    default boolean tickWhile(BooleanSupplier condition, @Nullable Duration timeout) {
        var ticker = process().ticker();
        final long start = System.nanoTime();
        while (condition.getAsBoolean()) {
            final long tick = System.nanoTime();
            ticker.tick(tick);
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
