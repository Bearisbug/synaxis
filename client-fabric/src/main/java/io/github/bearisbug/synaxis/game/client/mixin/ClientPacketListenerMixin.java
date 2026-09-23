package io.github.bearisbug.synaxis.game.client.mixin;

import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps the "Chat messages can't be verified" toast off the screen. Vanilla shows it on joining any
 * server that does not enforce secure chat, and the Synaxis server never does: chat is relayed as
 * system messages, never as signed player chat (docs/DESIGN-PROD.md ADR-PROD-009), so the toast
 * would warn every player about the server working as designed. It is the only toast
 * {@code handleLogin} adds.
 */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {

    @Redirect(method = "handleLogin", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/toasts/ToastManager;addToast(Lnet/minecraft/client/gui/components/toasts/Toast;)V"))
    private void synaxis$skipUnsecureServerToast(ToastManager toasts, Toast toast) {
    }
}
