package io.github.bearisbug.synaxis.game.client.gametest.mixin;

import io.github.bearisbug.synaxis.game.client.gametest.light.LightProbe;
import java.util.BitSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts the light packets per chunk and the block updates per position the client handles, for the
 * runtime light cases ({@code docs/DESIGN-M3.md} §19). A handler runs twice, first on the netty
 * thread, which hands it over to the client thread, then there; only the second call counts.
 */
@Mixin(ClientPacketListener.class)
abstract class LightUpdateCounterMixin {

    @Inject(method = "handleLightUpdatePacket", at = @At("HEAD"))
    private void synaxis$countLightPacket(ClientboundLightUpdatePacket packet, CallbackInfo info) {
        if (!Minecraft.getInstance().isSameThread()) return;
        final ClientboundLightUpdatePacketData data = packet.getLightData();
        final BitSet sections = (BitSet) data.getSkyYMask().clone();
        sections.or(data.getBlockYMask());
        sections.or(data.getEmptySkyYMask());
        sections.or(data.getEmptyBlockYMask());
        final Minecraft client = Minecraft.getInstance();
        final int count = client.level == null ? 0 : client.level.getSectionsCount();
        // Bit 0 and bit count + 1 are the sections below and above the world
        final int carried = sections.get(1, count + 1).cardinality();
        LightProbe.lightPacket(packet.getX(), packet.getZ(), carried, count > 0 && carried == count);
    }

    @Inject(method = "handleBlockUpdate", at = @At("HEAD"))
    private void synaxis$countBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo info) {
        if (Minecraft.getInstance().isSameThread()) LightProbe.blockUpdate(packet.getPos());
    }
}
