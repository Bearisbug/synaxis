package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.potion.PotionEffect;
import org.jetbrains.annotations.Nullable;

public record ClientSetBeaconEffectPacket(@Nullable PotionEffect primaryEffect,
                                          @Nullable PotionEffect secondaryEffect) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientSetBeaconEffectPacket> SERIALIZER = NetworkBufferTemplate.template(
            PotionEffect.NETWORK_TYPE.optional(), ClientSetBeaconEffectPacket::primaryEffect,
            PotionEffect.NETWORK_TYPE.optional(), ClientSetBeaconEffectPacket::secondaryEffect,
            ClientSetBeaconEffectPacket::new
    );
}
