package io.github.bearisbug.synaxis.client.mixin;

import java.util.Map;
import java.util.UUID;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The boss bars vanilla holds: {@code BossHealthOverlay} has nothing public that says how many there are, and
 * the HUD slot {@code TOP_CENTER} of {@code docs/DESIGN-PLAY.md} §6.4.8 starts below the last one drawn.
 */
@Mixin(BossHealthOverlay.class)
public interface BossHealthOverlayAccessor {

    @Accessor("events")
    Map<UUID, LerpingBossEvent> synaxis$events();
}
