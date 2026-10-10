package io.github.bearisbug.synaxis.app.play;

import io.github.bearisbug.synaxis.app.play.DefaultPlay.Feature;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.entity.damage.DamageType;
import io.github.bearisbug.synaxis.event.entity.EntityDamageEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code VOID_DAMAGE} of the default play layer ({@code docs/DESIGN-PROD.md} {@code API-PROD-031}): below
 * {@code Instance#isInVoid}, 64 blocks under the dimension's minimum height, a living player takes 4 points of
 * {@code minecraft:out_of_world} every tick; on the line itself it takes nothing.
 */
@EnvTest
class DefaultPlayVoidDamageIntegrationTest {

    @Test
    void belowTheVoidLineAPlayerTakesFourEveryTick(Env env) {
        final Instance instance = env.createFlatInstance();
        PlayFixture.attach(env, PlayFixture.hostNode(env), EnumSet.of(Feature.VOID_DAMAGE), null);
        final Player player = env.createPlayer(instance, new Pos(0.5, 40, 0.5));
        final List<EntityDamageEvent> damages = new CopyOnWriteArrayList<>();
        player.eventNode().addListener(EntityDamageEvent.class, damages::add);
        final int voidLine = instance.getCachedDimensionType().minY() - 64;

        teleport(player, new Pos(0.5, voidLine, 0.5));
        env.tick();
        assertEquals(20f, player.getHealth(), "on the void line");
        assertEquals(List.of(), damages, "damage on the void line");

        teleport(player, new Pos(0.5, voidLine - 1, 0.5));
        env.tick();
        assertEquals(16f, player.getHealth(), "one tick below the void line");
        for (int tick = 0; tick < 3; tick++) env.tick();
        assertFalse(player.isDead(), "alive with " + player.getHealth() + " after 4 ticks");
        env.tick();
        assertTrue(player.isDead(), "dead after 5 ticks, with " + player.getHealth());
        env.tick();

        assertEquals(5, damages.size(), "damage events, the last one deadly");
        for (EntityDamageEvent event : damages) {
            assertEquals(DamageType.OUT_OF_WORLD, event.getDamage().getType());
            assertEquals(4f, event.getDamage().getAmount());
        }
    }

    private static void teleport(Player player, Pos position) {
        player.acquirable().sync(owned -> owned.teleport(position).join());
    }
}
