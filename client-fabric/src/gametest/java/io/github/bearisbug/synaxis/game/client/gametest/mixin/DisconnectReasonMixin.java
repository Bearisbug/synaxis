package io.github.bearisbug.synaxis.game.client.gametest.mixin;

import io.github.bearisbug.synaxis.game.client.gametest.PlayableClientGameTest;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands the reason of every disconnect the client handles to the playable script, so that a step
 * that finds the client out of the world can say why, whatever screen the disconnect left behind.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
abstract class DisconnectReasonMixin {

    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void synaxis$recordReason(DisconnectionDetails details, CallbackInfo info) {
        PlayableClientGameTest.disconnected(details.reason());
    }
}
