package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;

public enum CopperGolemWeatherState {
    UNAFFECTED,
    EXPOSED,
    WEATHERED,
    OXIDIZED;

    public static final NetworkBuffer.Type<CopperGolemWeatherState> NETWORK_TYPE = NetworkBuffer.Enum(CopperGolemWeatherState.class);
    public static final Codec<CopperGolemWeatherState> CODEC = Codec.Enum(CopperGolemWeatherState.class);
}
