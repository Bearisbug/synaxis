package io.github.bearisbug.synaxis.item.component;

import io.github.bearisbug.synaxis.network.NetworkBuffer;

public enum MapPostProcessing {
    LOCK,
    SCALE;

    public static final NetworkBuffer.Type<MapPostProcessing> NETWORK_TYPE = NetworkBuffer.Enum(MapPostProcessing.class);
}
