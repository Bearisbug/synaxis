package io.github.bearisbug.synaxis.item.component;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.kyori.adventure.key.Key;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.registry.StaticProtocolObject;

/**
 * 原为生成物，自 REQ-008 起作为普通源码维护；重新生成的方法见 `docs/ENGINE.md` 升版本 checklist。
 */
enum ConsumeEffectType implements StaticProtocolObject<ConsumeEffectType> {
    APPLY_EFFECTS("apply_effects"),

    REMOVE_EFFECTS("remove_effects"),

    CLEAR_ALL_EFFECTS("clear_all_effects"),

    TELEPORT_RANDOMLY("teleport_randomly"),

    PLAY_SOUND("play_sound");

    private static final Map<Key, ConsumeEffectType> BY_KEY = Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(ConsumeEffectType::key, Function.identity()));

    public static final NetworkBuffer.Type<ConsumeEffectType> NETWORK_TYPE = NetworkBuffer.Enum(ConsumeEffectType.class);

    public static final Codec<ConsumeEffectType> CODEC = Codec.KEY.transform(BY_KEY::get, ConsumeEffectType::key);

    private final Key key;

    ConsumeEffectType(String key) {
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
