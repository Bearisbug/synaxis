package io.github.bearisbug.synaxis.adventure.audience;

import net.kyori.adventure.bossbar.BossBar;
import io.github.bearisbug.synaxis.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.AbstractCollection;
import java.util.Collections;
import java.util.Iterator;

import static net.kyori.adventure.text.Component.text;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class PacketGroupingAudienceTest {

    /**
     * The players of an audience are a live view. The last of them leaving between the emptiness check and the
     * iteration leaves nothing to show the bar to, which is not an error.
     */
    @Test
    void theLastPlayerLeavingWhileABarIsShownIsNotAnError() {
        final PacketGroupingAudience audience = PacketGroupingAudience.of(new AbstractCollection<Player>() {
            @Override
            public boolean isEmpty() {
                return false;
            }

            @Override
            public Iterator<Player> iterator() {
                return Collections.emptyIterator();
            }

            @Override
            public int size() {
                return 0;
            }
        });
        final BossBar bar = BossBar.bossBar(text("bar"), 1f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);

        assertDoesNotThrow(() -> audience.showBossBar(bar));
        assertDoesNotThrow(() -> audience.hideBossBar(bar));
    }
}
