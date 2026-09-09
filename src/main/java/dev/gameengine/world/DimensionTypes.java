package dev.gameengine.world;

import dev.gameengine.registry.RegistryKey;

/**
 * This class contains all the generated constants for {@link DimensionType}
 * <br>
 * 原为生成物，自 REQ-008 起作为普通源码维护；重新生成的方法见 `docs/ENGINE.md` 升版本 checklist。
 */
@SuppressWarnings("unused")
sealed interface DimensionTypes permits DimensionType {
    RegistryKey<DimensionType> OVERWORLD = RegistryKey.unsafeOf("overworld");

    RegistryKey<DimensionType> OVERWORLD_CAVES = RegistryKey.unsafeOf("overworld_caves");

    RegistryKey<DimensionType> THE_END = RegistryKey.unsafeOf("the_end");

    RegistryKey<DimensionType> THE_NETHER = RegistryKey.unsafeOf("the_nether");
}
