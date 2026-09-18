package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.FeatureFlag;
import io.github.bearisbug.synaxis.entity.Player;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AsyncPlayerConfigurationEventTest {

    // The event only stores the player, building a real one needs a whole connection
    private static final @Nullable Player NO_PLAYER = null;

    @Test
    public void theVanillaFeatureFlagIsEnabledByDefault() {
        var event = new AsyncPlayerConfigurationEvent(NO_PLAYER, true);
        assertEquals(1, event.getFeatureFlags().size());
        assertTrue(event.getFeatureFlags().contains(FeatureFlag.VANILLA),
                "Without the vanilla feature set the client gets nothing at all");
    }

    @Test
    public void registryDataFollowsWhetherThisIsTheFirstConfiguration() {
        assertTrue(new AsyncPlayerConfigurationEvent(NO_PLAYER, true).willSendRegistryData());
        assertFalse(new AsyncPlayerConfigurationEvent(NO_PLAYER, false).willSendRegistryData());
    }

    @Test
    public void theRemainingDefaultsAreTheConservativeOnes() {
        var event = new AsyncPlayerConfigurationEvent(NO_PLAYER, true);
        assertTrue(event.isFirstConfig());
        assertFalse(event.isHardcore());
        assertFalse(event.willClearChat());
        assertNull(event.getSpawningInstance(), "The spawning instance has to be chosen by a listener");
    }

    @Test
    public void aFeatureFlagCanBeAddedAndRemoved() {
        var event = new AsyncPlayerConfigurationEvent(NO_PLAYER, true);
        var extra = FeatureFlag.TRADE_REBALANCE;

        assertFalse(event.removeFeatureFlag(extra), "Removing an absent flag reports no change");
        event.addFeatureFlag(extra);
        assertTrue(event.getFeatureFlags().contains(extra));
        assertTrue(event.removeFeatureFlag(extra));
        assertFalse(event.getFeatureFlags().contains(extra));
    }

    @Test
    public void theExposedFeatureFlagSetIsAnUnmodifiableView() {
        var event = new AsyncPlayerConfigurationEvent(NO_PLAYER, true);
        var flags = event.getFeatureFlags();
        assertThrows(UnsupportedOperationException.class, () -> flags.add(FeatureFlag.TRADE_REBALANCE));
    }

    @Test
    public void theSettersAreReadBackByTheConfigurationSender() {
        var event = new AsyncPlayerConfigurationEvent(NO_PLAYER, false);
        event.setHardcore(true);
        event.setClearChat(true);
        event.setSendRegistryData(true);

        assertTrue(event.isHardcore());
        assertTrue(event.willClearChat());
        assertTrue(event.willSendRegistryData());
    }
}
