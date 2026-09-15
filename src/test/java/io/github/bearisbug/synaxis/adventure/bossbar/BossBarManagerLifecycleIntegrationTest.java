package io.github.bearisbug.synaxis.adventure.bossbar;

import net.kyori.adventure.bossbar.BossBar;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static net.kyori.adventure.text.Component.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class BossBarManagerLifecycleIntegrationTest {

    private static BossBar sampleBossBar() {
        return BossBar.bossBar(text("Test Boss Bar"), 1.0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
    }

    @Test
    public void hideDropsHolder(Env env) {
        final BossBarManager manager = env.process().bossBar();
        final Player player = env.createPlayer(env.createEmptyInstance(), new Pos(0, 40, 0));
        final BossBar bar = sampleBossBar();

        player.showBossBar(bar);
        assertEquals(1, manager.bars.size());

        player.hideBossBar(bar);
        assertTrue(manager.bars.isEmpty());
    }

    @Test
    public void removeAllDropsHolder(Env env) {
        final BossBarManager manager = env.process().bossBar();
        final Player player = env.createPlayer(env.createEmptyInstance(), new Pos(0, 40, 0));
        final BossBar bar = sampleBossBar();

        player.showBossBar(bar);
        assertEquals(1, manager.bars.size());

        manager.removeAllBossBars(player);
        assertTrue(manager.bars.isEmpty());
    }

    @Test
    public void hideKeepsHolderForRemainingViewer(Env env) {
        final BossBarManager manager = env.process().bossBar();
        final var instance = env.createEmptyInstance();
        final Player first = env.createPlayer(instance, new Pos(0, 40, 0));
        final Player second = env.createPlayer(instance, new Pos(0, 40, 0));
        final BossBar bar = sampleBossBar();

        first.showBossBar(bar);
        second.showBossBar(bar);
        first.hideBossBar(bar);

        assertEquals(1, manager.bars.size());
        assertEquals(1, manager.getBossBarViewers(bar).size());
    }
}
