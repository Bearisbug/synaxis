package dev.gameengine.instance.fluid;

import dev.gameengine.registry.RegistryKey;
import org.jetbrains.annotations.ApiStatus;

/**
 * This class contains all the generated constants for {@link Fluid}
 * <br>
 * 原为生成物，自 REQ-008 起作为普通源码维护；重新生成的方法见 `docs/ENGINE.md` 升版本 checklist。
 */
@SuppressWarnings("unused")
@ApiStatus.NonExtendable
public interface FluidKeys {
    RegistryKey<Fluid> EMPTY = RegistryKey.unsafeOf("empty");

    RegistryKey<Fluid> FLOWING_LAVA = RegistryKey.unsafeOf("flowing_lava");

    RegistryKey<Fluid> FLOWING_WATER = RegistryKey.unsafeOf("flowing_water");

    RegistryKey<Fluid> LAVA = RegistryKey.unsafeOf("lava");

    RegistryKey<Fluid> WATER = RegistryKey.unsafeOf("water");
}
