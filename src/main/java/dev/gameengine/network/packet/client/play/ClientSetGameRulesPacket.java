package dev.gameengine.network.packet.client.play;

import dev.gameengine.instance.gamerule.GameRule;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;
import dev.gameengine.registry.Registries;
import dev.gameengine.registry.RegistryKey;
import dev.gameengine.utils.validate.Check;

import java.util.List;
import java.util.Objects;

public record ClientSetGameRulesPacket(List<Entry> entries) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientSetGameRulesPacket> SERIALIZER = NetworkBufferTemplate.template(
            Entry.NETWORK_TYPE.list(GameRule.staticRegistry().size()), ClientSetGameRulesPacket::entries,
            ClientSetGameRulesPacket::new
    );

    public ClientSetGameRulesPacket {
        entries = List.copyOf(entries);
    }

    public record Entry(RegistryKey<GameRule<?>> key, String value) {
        public static final NetworkBuffer.Type<Entry> NETWORK_TYPE = NetworkBufferTemplate.template(
                RegistryKey.networkType(Registries::gameRule), Entry::key,
                NetworkBuffer.STRING, Entry::value,
                Entry::new);

        public Entry {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            Check.argCondition(value.length() > Short.MAX_VALUE, "Value length cannot be greater than Short.MAX_VALUE");
        }
    }
}
