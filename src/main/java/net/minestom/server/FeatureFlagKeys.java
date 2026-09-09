package net.minestom.server;

import net.minestom.server.registry.RegistryKey;
import org.jetbrains.annotations.ApiStatus;

/**
 * This class contains all the generated constants for {@link FeatureFlag}
 * <br>
 * 原为生成物，自 REQ-008 起作为普通源码维护；重新生成的方法见 `docs/ENGINE.md` 升版本 checklist。
 */
@SuppressWarnings("unused")
@ApiStatus.NonExtendable
public interface FeatureFlagKeys {
    RegistryKey<FeatureFlag> MINECART_IMPROVEMENTS = RegistryKey.unsafeOf("minecart_improvements");

    RegistryKey<FeatureFlag> REDSTONE_EXPERIMENTS = RegistryKey.unsafeOf("redstone_experiments");

    RegistryKey<FeatureFlag> TRADE_REBALANCE = RegistryKey.unsafeOf("trade_rebalance");

    RegistryKey<FeatureFlag> VANILLA = RegistryKey.unsafeOf("vanilla");
}
