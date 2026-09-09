package net.minestom.server.potion;

import net.minestom.server.registry.TagKey;
import org.jetbrains.annotations.ApiStatus;

/**
 * This class contains all the generated constants for {@link PotionType}
 * <br>
 * 原为生成物，自 REQ-008 起作为普通源码维护；重新生成的方法见 `docs/ENGINE.md` 升版本 checklist。
 */
@SuppressWarnings("unused")
@ApiStatus.NonExtendable
public interface PotionTypeTags {
    TagKey<PotionType> TRADEABLE = TagKey.unsafeOf("tradeable");
}
