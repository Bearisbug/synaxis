package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityPositionSyncPacket;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.TestConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per player visible entity limit ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-024},
 * {@code API-PROD-019}): the nearest ones up to the limit, always visible entities on top.
 */
public class VisibleEntityLimitTest {

    private static final Pos CENTER = new Pos(0.5, 42, 0.5);
    private static final int ORDINARY = 150;
    private static final int LIMIT = 100;

    private Env env;

    @AfterEach
    void tearDown() {
        env.process().stop();
    }

    private Instance start(int limit) {
        env = Env.create(settings -> settings.maxVisibleEntitiesPerPlayer(limit));
        return env.createFlatInstance();
    }

    private static Entity spawn(Instance instance, Pos at) {
        final Entity entity = new Entity(EntityType.ZOMBIE);
        entity.setNoGravity(true);
        entity.setInstance(instance, at).join();
        return entity;
    }

    /**
     * 150 entities on a spiral out from the center, each farther than the one before, 3 to 63 blocks.
     *
     * @return the entities, nearest first whatever order they were spawned in
     */
    private static List<Entity> spiral(Instance instance, Pos center, boolean farthestFirst) {
        final Entity[] entities = new Entity[ORDINARY];
        for (int n = 0; n < ORDINARY; n++) {
            final int i = farthestFirst ? ORDINARY - 1 - n : n;
            final double radius = 3 + i * 0.4;
            final double angle = i * 2.399963229728653;
            entities[i] = spawn(instance, center.add(radius * Math.cos(angle), 0, radius * Math.sin(angle)));
        }
        return List.of(entities);
    }

    private void refresh() {
        for (int i = 0; i < EntityView.REFRESH_INTERVAL_TICKS; i++) env.tick();
    }

    private static Set<Entity> seenBy(Player player, List<Entity> entities) {
        return entities.stream().filter(entity -> entity.isViewer(player)).collect(Collectors.toSet());
    }

    private static Set<Entity> nearest(List<Entity> entities, Pos from, int count) {
        return entities.stream()
                .sorted(Comparator.comparingDouble(entity -> entity.getPosition().distanceSquared(from)))
                .limit(count)
                .collect(Collectors.toSet());
    }

    /** The §19 scenario, with the far ones spawned first so that they take the places the near ones need. */
    @Test
    public void thePlayerSeesTheNearestHundredAndTheAlwaysVisibleOnes() {
        final Instance instance = start(LIMIT);
        final Player player = env.createPlayer(instance, CENTER);
        final List<Entity> alwaysVisible = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final Entity boss = new Entity(EntityType.ZOMBIE);
            boss.setNoGravity(true);
            boss.setAlwaysVisible(true);
            boss.setInstance(instance, CENTER.add(70, 0, i * 4)).join();
            alwaysVisible.add(boss);
        }
        final List<Entity> ordinary = spiral(instance, CENTER, true);
        assertEquals(LIMIT, seenBy(player, ordinary).size(), "past the limit an entity is turned away when it arrives");
        assertFalse(ordinary.getFirst().isViewer(player), "the nearest one arrived last, with no place left");

        refresh();
        assertEquals(nearest(ordinary, CENTER, LIMIT), seenBy(player, ordinary));
        for (Entity boss : alwaysVisible) assertTrue(boss.isViewer(player));
    }

    @Test
    public void movingChangesWhichOnesAreNearest() {
        final Instance instance = start(LIMIT);
        final Player player = env.createPlayer(instance, CENTER);
        final List<Entity> ordinary = spiral(instance, CENTER, false);
        refresh();
        assertEquals(nearest(ordinary, CENTER, LIMIT), seenBy(player, ordinary));

        final Pos elsewhere = CENTER.add(40, 0, 0);
        player.acquirable().sync(owned -> owned.teleport(elsewhere).join());
        refresh();
        final Set<Entity> expected = nearest(ordinary, elsewhere, LIMIT);
        assertEquals(expected, seenBy(player, ordinary));
        assertFalse(expected.equals(nearest(ordinary, CENTER, LIMIT)), "the move must change the nearest hundred");
    }

    @Test
    public void markingAnEntityAlwaysVisibleTakesItOutOfTheCount() {
        final Instance instance = start(LIMIT);
        final Player player = env.createPlayer(instance, CENTER);
        final List<Entity> ordinary = spiral(instance, CENTER, false);
        refresh();
        final Entity farthest = ordinary.getLast();
        assertFalse(farthest.isViewer(player));

        farthest.setAlwaysVisible(true);
        refresh();
        assertTrue(farthest.isViewer(player));
        assertEquals(LIMIT + 1, seenBy(player, ordinary).size());

        farthest.setAlwaysVisible(false);
        refresh();
        assertFalse(farthest.isViewer(player));
        assertEquals(LIMIT, seenBy(player, ordinary).size());
    }

    /**
     * With viewable packets on, a move is sent once to every player around the chunk unless the
     * entity says its viewers cannot be predicted from the chunk; the limit makes them unpredictable.
     */
    @Test
    public void aHiddenEntitySendsNoMovesToThePlayer() {
        env = Env.create(settings -> settings.maxVisibleEntitiesPerPlayer(1).viewablePacket(true));
        final Instance instance = env.createFlatInstance();
        final TestConnection connection = env.createConnection();
        final Player player = connection.connect(instance, CENTER);
        final Entity near = spawn(instance, CENTER.add(3, 0, 0));
        final Entity far = spawn(instance, CENTER.add(10, 0, 0));
        refresh();
        assertTrue(near.isViewer(player));
        assertFalse(far.isViewer(player));
        assertFalse(far.hasPredictableViewers(), "a packet for everyone around the chunk would reach the player");

        final Collector<EntityPositionSyncPacket> moves = connection.trackIncoming(EntityPositionSyncPacket.class);
        near.acquirable().sync(owned -> owned.teleport(CENTER.add(4, 0, 0)).join());
        far.acquirable().sync(owned -> owned.teleport(CENTER.add(11, 0, 0)).join());
        env.tick();
        final List<Integer> movedIds = moves.collect().stream().map(EntityPositionSyncPacket::entityId).toList();
        assertEquals(List.of(near.getEntityId()), movedIds);
    }

    @Test
    public void withoutALimitThePlayerSeesThemAll() {
        final Instance instance = start(0);
        final Player player = env.createPlayer(instance, CENTER);
        final List<Entity> ordinary = spiral(instance, CENTER, false);
        refresh();
        assertEquals(ORDINARY, seenBy(player, ordinary).size());
    }
}
