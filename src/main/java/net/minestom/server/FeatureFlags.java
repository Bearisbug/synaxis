package net.minestom.server;

/**
 * This class contains all the generated constants for {@link FeatureFlag}
 * <br>
 * 原为生成物，自 REQ-008 起作为普通源码维护；重新生成的方法见 `docs/ENGINE.md` 升版本 checklist。
 */
@SuppressWarnings("unused")
sealed interface FeatureFlags permits FeatureFlag {
    FeatureFlag REDSTONE_EXPERIMENTS = FeatureFlagImpl.get(FeatureFlagKeys.REDSTONE_EXPERIMENTS);

    FeatureFlag VANILLA = FeatureFlagImpl.get(FeatureFlagKeys.VANILLA);

    FeatureFlag TRADE_REBALANCE = FeatureFlagImpl.get(FeatureFlagKeys.TRADE_REBALANCE);

    FeatureFlag MINECART_IMPROVEMENTS = FeatureFlagImpl.get(FeatureFlagKeys.MINECART_IMPROVEMENTS);
}
