package dev.gameengine.instance.fluid;

import dev.gameengine.registry.TagKey;
import org.jetbrains.annotations.ApiStatus;

/**
 * This class contains all the generated constants for {@link Fluid}
 * <br>
 * 原为生成物，自 REQ-008 起作为普通源码维护；重新生成的方法见 `docs/ENGINE.md` 升版本 checklist。
 */
@SuppressWarnings("unused")
@ApiStatus.NonExtendable
public interface FluidTags {
    TagKey<Fluid> BUBBLE_COLUMN_CAN_OCCUPY = TagKey.unsafeOf("bubble_column_can_occupy");

    TagKey<Fluid> LAVA = TagKey.unsafeOf("lava");

    TagKey<Fluid> SUPPORTS_FROGSPAWN = TagKey.unsafeOf("supports_frogspawn");

    TagKey<Fluid> SUPPORTS_LILY_PAD = TagKey.unsafeOf("supports_lily_pad");

    TagKey<Fluid> SUPPORTS_SUGAR_CANE_ADJACENTLY = TagKey.unsafeOf("supports_sugar_cane_adjacently");

    TagKey<Fluid> WATER = TagKey.unsafeOf("water");
}
