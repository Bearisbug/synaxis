package dev.gameengine.entity;

import dev.gameengine.codec.Codec;
import dev.gameengine.network.NetworkBuffer;

public enum CopperGolemWeatherState {
    UNAFFECTED,
    EXPOSED,
    WEATHERED,
    OXIDIZED;

    public static final NetworkBuffer.Type<CopperGolemWeatherState> NETWORK_TYPE = NetworkBuffer.Enum(CopperGolemWeatherState.class);
    public static final Codec<CopperGolemWeatherState> CODEC = Codec.Enum(CopperGolemWeatherState.class);
}
