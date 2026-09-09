package dev.gameengine.item.component;

import dev.gameengine.network.NetworkBuffer;

public enum MapPostProcessing {
    LOCK,
    SCALE;

    public static final NetworkBuffer.Type<MapPostProcessing> NETWORK_TYPE = NetworkBuffer.Enum(MapPostProcessing.class);
}
