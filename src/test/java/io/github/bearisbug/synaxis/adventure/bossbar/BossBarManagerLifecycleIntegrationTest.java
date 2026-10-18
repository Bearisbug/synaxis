package io.github.bearisbug.synaxis.adventure.bossbar;

import net.kyori.adventure.bossbar.BossBar;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.packet.server.play.BossBarPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;

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

    /** A removed player has had its boss bars cleared; showing it one would keep it, and its connection, reachable. */
    @Test
    public void aRemovedPlayerIsNotShownABar(Env env) {
        final BossBarManager manager = env.process().bossBar();
        final var instance = env.createEmptyInstance();
        final Player removed = env.createPlayer(instance, new Pos(0, 40, 0));
        final Player online = env.createPlayer(instance, new Pos(0, 40, 0));
        removed.remove();
        final BossBar bar = sampleBossBar();

        manager.addBossBar(removed, bar);
        assertTrue(manager.bars.isEmpty());
        manager.addBossBar(List.of(removed, online), bar);
        assertEquals(List.of(online), List.copyOf(manager.getBossBarViewers(bar)));
        assertEquals(List.of(), List.copyOf(manager.getPlayerBossBars(removed)));

        manager.removeBossBar(List.of(online), bar);
        assertTrue(manager.bars.isEmpty());
    }

    /**
     * A player that reconnects is a new object with the same UUID: it starts without the bars of its previous session,
     * and clearing what is left of that session leaves the new one alone.
     */
    @Test
    public void aReconnectedPlayerDoesNotShareTheBarsOfItsPreviousSession(Env env) {
        final BossBarManager manager = env.process().bossBar();
        final var instance = env.createEmptyInstance();
        final GameProfile profile = new GameProfile(UUID.randomUUID(), "Returning");
        final Player previous = env.createConnection(profile).connect(instance, new Pos(0, 40, 0));
        previous.remove();
        // A plugin still holding the player of the previous session
        manager.addBossBar(previous, sampleBossBar());

        final Player current = env.createConnection(profile).connect(instance, new Pos(0, 40, 0));
        final BossBar bar = sampleBossBar();
        manager.addBossBar(current, bar);
        assertEquals(List.of(bar), List.copyOf(manager.getPlayerBossBars(current)));

        manager.removeAllBossBars(previous);
        assertEquals(List.of(bar), List.copyOf(manager.getPlayerBossBars(current)));
        assertEquals(List.of(current), List.copyOf(manager.getBossBarViewers(bar)));
    }

    /**
     * The last viewer leaving drops the holder of a bar. A show that looked the holder up just before has to end up
     * on a registered holder, not on the dropped one that no update and no hide reaches.
     */
    @Test
    public void aShowRacingTheDropOfTheLastViewerKeepsTheBar(Env env) throws Exception {
        env.process().connection().setPlayerProvider(RecordingPlayer::new);
        final BossBarManager manager = env.process().bossBar();
        final var instance = env.createEmptyInstance();
        final RecordingPlayer last = (RecordingPlayer) env.createPlayer(instance, new Pos(0, 40, 0));
        final RecordingPlayer next = (RecordingPlayer) env.createPlayer(instance, new Pos(0, 40, 0));
        final BossBar bar = sampleBossBar();
        manager.addBossBar(last, bar);
        final AtomicReference<FutureTask<Void>> leave = new AtomicReference<>();
        // Adding a viewer compares it with the viewers already there, after the holder was looked up
        next.onNextCompare(() -> leave.set(RecordingPlayer.aside(() -> manager.removeAllBossBars(last))));

        manager.addBossBar(next, bar);
        RecordingPlayer.join(leave.get());

        assertEquals(List.of(next), List.copyOf(manager.getBossBarViewers(bar)));
        assertEquals(List.of(bar), List.copyOf(manager.getPlayerBossBars(next)));
    }

    /** Destroying a bar removes it from every viewer, and no later change of the bar reaches them. */
    @Test
    public void destroyRemovesTheBarFromEveryViewer(Env env) {
        env.process().connection().setPlayerProvider(RecordingPlayer::new);
        final BossBarManager manager = env.process().bossBar();
        final var instance = env.createEmptyInstance();
        final RecordingPlayer first = (RecordingPlayer) env.createPlayer(instance, new Pos(0, 40, 0));
        final RecordingPlayer second = (RecordingPlayer) env.createPlayer(instance, new Pos(0, 40, 0));
        final BossBar bar = sampleBossBar();
        manager.addBossBar(List.of(first, second), bar);

        manager.destroyBossBar(bar);
        bar.progress(0.5f);

        assertTrue(manager.bars.isEmpty());
        assertEquals(List.of(), List.copyOf(manager.getPlayerBossBars(first)));
        for (RecordingPlayer player : List.of(first, second))
            assertEquals(List.of(BossBarPacket.AddAction.class, BossBarPacket.RemoveAction.class), player.received);
    }
}
