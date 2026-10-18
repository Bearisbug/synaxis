package io.github.bearisbug.synaxis.adventure.bossbar;

import net.kyori.adventure.bossbar.BossBar;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.network.packet.server.play.BossBarPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;

import static net.kyori.adventure.text.Component.text;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A boss bar is changed on the tick thread of its owner while its viewers come and go on theirs. The client drops
 * the connection on an update for a bar it does not know, so for each player the add has to be queued before any
 * update, and no update after the removal.
 */
@EnvTest
public class BossBarPacketOrderIntegrationTest {

    private static BossBar sampleBossBar() {
        return BossBar.bossBar(text("Test Boss Bar"), 1.0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
    }

    @Test
    public void anUpdateRacingTheAddIsQueuedAfterIt(Env env) throws Exception {
        env.process().connection().setPlayerProvider(RecordingPlayer::new);
        final RecordingPlayer player = (RecordingPlayer) env.createPlayer(env.createEmptyInstance(), new Pos(0, 40, 0));
        final BossBarManager manager = env.process().bossBar();
        final BossBar bar = sampleBossBar();
        final AtomicReference<FutureTask<Void>> update = new AtomicReference<>();
        player.beforeQueuing(BossBarPacket.AddAction.class, () -> update.set(RecordingPlayer.aside(() -> bar.progress(0.5f))));

        manager.addBossBar(player, bar);
        RecordingPlayer.join(update.get());

        assertEquals(List.of(BossBarPacket.AddAction.class, BossBarPacket.UpdateHealthAction.class), player.received);
    }

    @Test
    public void aRemovalRacingAnUpdateIsQueuedAfterIt(Env env) throws Exception {
        env.process().connection().setPlayerProvider(RecordingPlayer::new);
        final RecordingPlayer player = (RecordingPlayer) env.createPlayer(env.createEmptyInstance(), new Pos(0, 40, 0));
        final BossBarManager manager = env.process().bossBar();
        final BossBar bar = sampleBossBar();
        manager.addBossBar(player, bar);
        final AtomicReference<FutureTask<Void>> hide = new AtomicReference<>();
        player.beforeQueuing(BossBarPacket.UpdateHealthAction.class,
                () -> hide.set(RecordingPlayer.aside(() -> manager.removeBossBar(player, bar))));

        bar.progress(0.5f);
        RecordingPlayer.join(hide.get());

        assertEquals(List.of(BossBarPacket.AddAction.class, BossBarPacket.UpdateHealthAction.class,
                BossBarPacket.RemoveAction.class), player.received);
    }
}
