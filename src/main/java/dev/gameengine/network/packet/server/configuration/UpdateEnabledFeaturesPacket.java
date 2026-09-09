package dev.gameengine.network.packet.server.configuration;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import java.util.List;

import static dev.gameengine.network.NetworkBuffer.STRING;

public record UpdateEnabledFeaturesPacket(List<String> features) implements ServerPacket.Configuration {
    public static final int MAX_FEATURES = 1024;

    public UpdateEnabledFeaturesPacket {
        if (features.size() > MAX_FEATURES)
            throw new IllegalArgumentException("Too many features");
        features = List.copyOf(features);
    }

    public static final NetworkBuffer.Type<UpdateEnabledFeaturesPacket> SERIALIZER = NetworkBufferTemplate.template(
            STRING.list(MAX_FEATURES), UpdateEnabledFeaturesPacket::features,
            UpdateEnabledFeaturesPacket::new
    );
}
