package io.github.bearisbug.synaxis.app;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step 5 of the startup contract (API-M2-010): which of the three outcomes a launch gets.
 */
class CoreModeTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty("synaxis.app.core-mode");
    }

    @Test
    void theCoreModeFollowsThePluginsWhenNobodySaysOtherwise() {
        assertEquals(CoreMode.Setting.AUTO, CoreMode.setting());
    }

    @Test
    void theCoreModeCanBeAskedForAndRefusedByProperty() {
        System.setProperty("synaxis.app.core-mode", " ON ");
        assertEquals(CoreMode.Setting.ON, CoreMode.setting());
        System.setProperty("synaxis.app.core-mode", "off");
        assertEquals(CoreMode.Setting.OFF, CoreMode.setting());
    }

    @Test
    void aCoreModeSettingThatIsNotAModeIsRefused() {
        System.setProperty("synaxis.app.core-mode", "maybe");
        final IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, CoreMode::setting);
        assertTrue(refusal.getMessage().contains("synaxis.app.core-mode=maybe"), refusal.getMessage());
    }

    @Test
    void withoutAnyPluginTheProcessServesTheCoreWorld() {
        assertEquals(CoreMode.Mode.CORE, CoreMode.decide(CoreMode.Setting.AUTO, 0, false, 0));
    }

    @Test
    void aPluginThatProvidesGameplayKeepsTheCoreModeOut() {
        assertEquals(CoreMode.Mode.GAMEPLAY, CoreMode.decide(CoreMode.Setting.AUTO, 1, true, 1));
    }

    @Test
    void halfProvidedGameplayIsStillRefused() {
        assertEquals(CoreMode.Mode.REFUSED, CoreMode.decide(CoreMode.Setting.AUTO, 1, true, 0));
        assertEquals(CoreMode.Mode.REFUSED, CoreMode.decide(CoreMode.Setting.AUTO, 1, false, 1));
        assertEquals(CoreMode.Mode.REFUSED, CoreMode.decide(CoreMode.Setting.AUTO, 1, true, 2));
    }

    // A plugin that took part and provided nothing came up wrong; the core world must not cover
    // that up, or an unreadable map turns into a server nobody can play on instead of a refusal
    @Test
    void aPluginThatProvidedNothingIsRefusedRatherThanFallenBackOn() {
        assertEquals(CoreMode.Mode.REFUSED, CoreMode.decide(CoreMode.Setting.AUTO, 1, false, 0));
    }

    @Test
    void theCoreModeIsServedEvenWithAGameplayPluginOnceItIsAskedFor() {
        assertEquals(CoreMode.Mode.CORE, CoreMode.decide(CoreMode.Setting.ON, 1, true, 1));
    }

    @Test
    void theCoreModeIsNotFallenBackToOnceItIsRefused() {
        assertEquals(CoreMode.Mode.REFUSED, CoreMode.decide(CoreMode.Setting.OFF, 0, false, 0));
        assertEquals(CoreMode.Mode.GAMEPLAY, CoreMode.decide(CoreMode.Setting.OFF, 1, true, 1));
    }
}
