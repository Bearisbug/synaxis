package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.potion.PotionType;
import org.jetbrains.annotations.Nullable;

public record ClientSetBeaconEffectPacket(@Nullable PotionType primaryEffect,
                                          @Nullable PotionType secondaryEffect) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientSetBeaconEffectPacket> SERIALIZER = NetworkBufferTemplate.template(
            PotionType.NETWORK_TYPE.optional(), ClientSetBeaconEffectPacket::primaryEffect,
            PotionType.NETWORK_TYPE.optional(), ClientSetBeaconEffectPacket::secondaryEffect,
            ClientSetBeaconEffectPacket::new
    );
}
