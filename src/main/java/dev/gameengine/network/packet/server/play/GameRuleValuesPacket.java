package dev.gameengine.network.packet.server.play;

import dev.gameengine.instance.gamerule.GameRule;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;
import dev.gameengine.registry.Registries;
import dev.gameengine.registry.RegistryKey;

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
