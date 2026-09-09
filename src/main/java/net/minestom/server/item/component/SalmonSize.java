package net.minestom.server.item.component;

import net.minestom.server.codec.Codec;
import net.minestom.server.network.NetworkBuffer;

public enum SalmonSize {
    SMALL("small"),
    MEDIUM("medium"),
    LARGE("large");

    public static final NetworkBuffer.Type<SalmonSize> NETWORK_TYPE = NetworkBuffer.Enum(SalmonSize.class);
    public static final Codec<SalmonSize> CODEC = Codec.Enum(SalmonSize.class);

    private final String id;

    SalmonSize(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
