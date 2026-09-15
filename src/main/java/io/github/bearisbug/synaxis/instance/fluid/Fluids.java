package io.github.bearisbug.synaxis.instance.fluid;

/**
 * This class contains all the generated constants for {@link Fluid}
 * <br>
 * 原为生成物，自 REQ-008 起作为普通源码维护；重新生成的方法见 `docs/ENGINE.md` 升版本 checklist。
 */
@SuppressWarnings("unused")
sealed interface Fluids permits Fluid {
    Fluid EMPTY = FluidImpl.get(FluidKeys.EMPTY);

    Fluid FLOWING_WATER = FluidImpl.get(FluidKeys.FLOWING_WATER);

    Fluid WATER = FluidImpl.get(FluidKeys.WATER);

    Fluid FLOWING_LAVA = FluidImpl.get(FluidKeys.FLOWING_LAVA);

    Fluid LAVA = FluidImpl.get(FluidKeys.LAVA);
}
