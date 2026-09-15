package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.instance.gamerule.GameRule;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.utils.validate.Check;

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
