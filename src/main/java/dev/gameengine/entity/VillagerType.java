package dev.gameengine.entity;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.kyori.adventure.key.Key;
import dev.gameengine.codec.Codec;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.registry.StaticProtocolObject;

/**
 * 原为生成物，自 REQ-008 起作为普通源码维护；重新生成的方法见 `docs/ENGINE.md` 升版本 checklist。
 */
public enum VillagerType implements StaticProtocolObject<VillagerType> {
    DESERT("desert"),

    JUNGLE("jungle"),

    PLAINS("plains"),

    SAVANNA("savanna"),

    SNOW("snow"),

    SWAMP("swamp"),

    TAIGA("taiga");

    private static final Map<Key, VillagerType> BY_KEY = Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(VillagerType::key, Function.identity()));

    public static final NetworkBuffer.Type<VillagerType> NETWORK_TYPE = NetworkBuffer.Enum(VillagerType.class);

    public static final Codec<VillagerType> CODEC = Codec.KEY.transform(BY_KEY::get, VillagerType::key);

    private final Key key;

    VillagerType(String key) {
        this.key = Key.key(key);
    }

    @Override
    public Key key() {
        return this.key;
    }

    @Override
    public int id() {
        return this.ordinal();
    }
}
