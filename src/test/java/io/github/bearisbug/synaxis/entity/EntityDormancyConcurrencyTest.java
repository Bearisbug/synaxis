package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.thread.TickThread;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dormancy with the entities and the player on different tick threads ({@code docs/DESIGN-PROD.md}
 * {@code REQ-PROD-040}, {@code API-PROD-019}): 4 tick threads on a grid of 2 by 2 chunk cells.
 */
public class EntityDormancyConcurrencyTest {

    private static final int FAR = 32, INTERVAL = 20, TICKS = 200;

    private Env env;

    @AfterEach
    void tearDown() {
        env.process().stop();
    }

    /**
     * 169 entities 16 blocks apart around the player, each with the §19 policy: over 200 ticks one within
     * 32 blocks of the player runs every tick and one farther runs exactly 10 times, whichever tick thread
     * ticks it and the player. The player then moves to another cell and the counts follow it.
     */
    @Test
    public void entitiesOnOtherTickThreadsRunAsFarAsThePlayerIs() {
        env = Env.create(settings -> settings.dispatcherThreads(4)
                .threadProvider(ServerConfig.ThreadProviderKind.GRID)
                .threadGridExponent(1));
        final Instance instance = env.createFlatInstance();
        final Player player = env.createPlayer(instance, new Pos(0.5, 42, 0.5));
        final Set<String> threads = ConcurrentHashMap.newKeySet();
        final List<String> foreign = new CopyOnWriteArrayList<>();
        final DormancyPolicy policy = (entity, distance) -> {
            final Thread current = Thread.currentThread();
            if (!(current instanceof TickThread) || !entity.acquirable().isOwned()) foreign.add(current.toString());
            threads.add(current.getName());
            return distance > FAR ? INTERVAL : 1;
        };
        final List<EntityDormancyIntegrationTest.Counting> entities = new ArrayList<>();
        for (int x = -96; x <= 96; x += 16) {
            for (int z = -96; z <= 96; z += 16) {
                final var entity = new EntityDormancyIntegrationTest.Counting();
                entity.setInstance(instance, new Pos(x + 0.5, 42, z + 0.5)).join();
                entity.setDormancyPolicy(policy);
                entities.add(entity);
            }
        }

        assertRuns(entities, player, 2);
        player.acquirable().sync(owned -> owned.teleport(new Pos(64.5, 42, 64.5)).join());
        // Every entity asks its policy again within 5 ticks
        assertRuns(entities, player, 5);

        assertEquals(List.of(), foreign, "a policy was asked on a thread that does not own its entity");
        assertEquals(4, threads.size(), "the entities are spread over the 4 tick threads: " + threads);
    }

    private void assertRuns(List<EntityDormancyIntegrationTest.Counting> entities, Player player, int settle) {
        for (int i = 0; i < settle; i++) env.tick();
        for (var entity : entities) entity.updates.set(0);
        for (int i = 0; i < TICKS; i++) env.tick();
        int near = 0;
        for (var entity : entities) {
            final boolean isNear = entity.getPosition().distance(player.getPosition()) <= FAR;
            if (isNear) near++;
            assertEquals(isNear ? TICKS : TICKS / INTERVAL, entity.updates.get(),
                    "runs of the entity at " + entity.getPosition() + " with the player at " + player.getPosition());
        }
        assertTrue(near > 0 && near < entities.size(), near + " entities near the player");
    }
}
