package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.instance.gamerule.GameRule;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.registry.RegistryKey;

import java.util.Map;

public record GameRuleValuesPacket(Map<RegistryKey<GameRule<?>>, String> values) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<GameRuleValuesPacket> SERIALIZER = NetworkBufferTemplate.template(
            RegistryKey.networkType(Registries::gameRule).mapValue(NetworkBuffer.STRING, GameRule.staticRegistry().size()), GameRuleValuesPacket::values,
            GameRuleValuesPacket::new
    );

    public GameRuleValuesPacket {
        values = Map.copyOf(values);
    }
}
