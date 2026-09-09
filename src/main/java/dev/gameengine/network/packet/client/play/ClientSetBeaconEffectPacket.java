package dev.gameengine.network.packet.client.play;

import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.client.ClientPacket;
import dev.gameengine.potion.PotionType;
import org.jetbrains.annotations.Nullable;

public record ClientSetBeaconEffectPacket(@Nullable PotionType primaryEffect,
                                          @Nullable PotionType secondaryEffect) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientSetBeaconEffectPacket> SERIALIZER = NetworkBufferTemplate.template(
            PotionType.NETWORK_TYPE.optional(), ClientSetBeaconEffectPacket::primaryEffect,
            PotionType.NETWORK_TYPE.optional(), ClientSetBeaconEffectPacket::secondaryEffect,
            ClientSetBeaconEffectPacket::new
    );
}
