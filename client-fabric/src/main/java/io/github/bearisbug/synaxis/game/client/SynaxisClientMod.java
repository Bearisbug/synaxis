package io.github.bearisbug.synaxis.game.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client entry point (REQ-M2-020a).
 * <p>
 * It registers the {@code rts:game} channel in both play directions and logs what arrives on it.
 * Camera, picking and rendering are later milestones; this class exists so that the stage 0 spikes
 * have a mod that loads.
 */
public final class SynaxisClientMod implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("synaxis");

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.serverboundPlay().register(GamePayload.TYPE, GamePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(GamePayload.TYPE, GamePayload.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(GamePayload.TYPE, (payload, _) -> {
            // Null means the decoder already dropped and warned about an unknown id.
            if (payload.message() == null) return;
            LOGGER.debug("rts:game msg_id={}", payload.message().msgId());
        });
    }
}
