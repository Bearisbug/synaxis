package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.testing.Env;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The visible entity limit ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-024}, {@code API-PROD-019})
 * holds while other tick threads take entities in for a player.
 */
public class VisibleEntityLimitConcurrencyTest {

    private static final Pos CENTER = new Pos(0.5, 42, 0.5);
    private static final int LIMIT = 100;

    private Env env;

    @AfterEach
    void tearDown() {
        env.process().stop();
    }

    /** Steps back and forth over a border of the player's entity view square, on its own tick thread. */
    private static final class Crosser extends Entity {
        private final Pos inside, outside;
        private final int period, phase;

        Crosser(Pos inside, Pos outside, int period, int phase) {
            super(EntityType.ZOMBIE);
            this.inside = inside;
            this.outside = outside;
            this.period = period;
            this.phase = phase;
            setNoGravity(true);
        }

        @Override
        public void update(long time) {
            refreshPosition(((getAliveTicks() + phase) / period) % 2 == 0 ? inside : outside);
        }
    }

    /**
     * 4 tick threads on a grid of 2 by 2 chunk cells, 400 entities stepping in and out over the four
     * borders of the player's entity view square: every step in is a take-in from the tick thread of
     * the entity's cell, while the player applies the limit on its own.
     */
    @Test
    public void takeInsFromOtherTickThreadsStayUnderTheLimit() {
        env = Env.create(settings -> settings.maxVisibleEntitiesPerPlayer(LIMIT)
                .dispatcherThreads(4)
                .threadProvider(ServerConfig.ThreadProviderKind.GRID)
                .threadGridExponent(1));
        final Instance instance = env.createFlatInstance();
        final Player player = env.createPlayer(instance, CENTER);
        final int viewDistance = env.process().config().entityViewDistance();
        // The first block past the square, and the last one in it, on each side
        final double high = (viewDistance + 1) * 16, low = -viewDistance * 16;
        final List<Entity> crossers = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            final double along = low + 1.5 + i * (high - low - 3) / 100;
            final int period = 3 + i % 5;
            crossers.add(spawn(instance, new Pos(high - 0.5, 42, along), new Pos(high + 0.5, 42, along), period, i));
            crossers.add(spawn(instance, new Pos(low + 0.5, 42, along), new Pos(low - 0.5, 42, along), period, i + 1));
            crossers.add(spawn(instance, new Pos(along, 42, high - 0.5), new Pos(along, 42, high + 0.5), period, i + 2));
            crossers.add(spawn(instance, new Pos(along, 42, low + 0.5), new Pos(along, 42, low - 0.5), period, i + 3));
        }

        int most = 0, over = 0;
        final int ticks = 1_000;
        for (int tick = 0; tick < ticks; tick++) {
            env.tick();
            int seen = 0;
            for (Entity crosser : crossers) if (crosser.isViewer(player)) seen++;
            most = Math.max(most, seen);
            if (seen > LIMIT) over++;
        }
        assertEquals(0, over, "ticks out of " + ticks + " ending with more than " + LIMIT + " seen, at most " + most);
    }

    private static Entity spawn(Instance instance, Pos inside, Pos outside, int period, int phase) {
        final Entity entity = new Crosser(inside, outside, period, phase);
        entity.setInstance(instance, outside).join();
        instance.loadChunk(inside).join();
        return entity;
    }
}
