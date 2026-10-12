package io.github.bearisbug.synaxis.game.client;

import io.github.bearisbug.synaxis.game.client.farview.FarViewClient;
import io.github.bearisbug.synaxis.game.client.farview.FarViewPayload;
import io.github.bearisbug.synaxis.game.client.mixin.BossHealthOverlayAccessor;
import io.github.bearisbug.synaxis.game.client.play.ActionKeys;
import io.github.bearisbug.synaxis.game.client.play.PanelScreen;
import io.github.bearisbug.synaxis.game.client.play.PlayClient;
import io.github.bearisbug.synaxis.game.client.play.PlayHud;
import io.github.bearisbug.synaxis.game.client.play.PlayPayload;
import io.github.bearisbug.synaxis.game.client.play.SceneReadyProbe;
import io.github.bearisbug.synaxis.game.protocol.S2CHello;
import io.netty.buffer.Unpooled;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;

/**
 * The client entry point: the two channels of {@code API-M2-001} and what listens on them.
 * <p>
 * Camera, input and rendering are stage 6b. What is here is stage 6a — the handshake
 * ({@code REQ-M2-008}) and the selection and ordering path ({@code REQ-M2-019}) — and it is
 * deliberately thin: every decision either channel makes lives in {@link ClientHandshake} or
 * {@link RtsClient}, where a test can reach it. This class is the wiring those two cannot be given
 * without a running client, and it is the part {@code docs/TEST.md} TC-GAME-046 and TC-GAME-047
 * have to verify by hand.
 */
public final class SynaxisClientMod implements ClientModInitializer {

    private static final ClientHandshake HANDSHAKE = new ClientHandshake();

    private static final RtsClient RTS = new RtsClient(new MinecraftWorldView(), System::nanoTime,
            message -> ClientPlayNetworking.send(new GamePayload(message)));

    /**
     * The scene layer of the host ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-002}): scene, HUD and actions. A
     * result's text goes to the vanilla action bar (§6.4.8).
     */
    private static final PlayClient PLAY = new PlayClient(message -> ClientPlayNetworking.send(new PlayPayload(message)),
            text -> Minecraft.getInstance().gui.hud.setOverlayMessage(text, false), System::nanoTime);

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.serverboundPlay().register(GamePayload.TYPE, GamePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(GamePayload.TYPE, GamePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(PlayPayload.TYPE, PlayPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(PlayPayload.TYPE, PlayPayload.CODEC);
        // Both configuration directions: the encoder finds a codec through this table, so leaving
        // the serverbound one out is a payload that cannot be sent at all, whatever the server does.
        PayloadTypeRegistry.serverboundConfiguration().register(HandshakePayload.TYPE, HandshakePayload.CODEC);
        PayloadTypeRegistry.clientboundConfiguration().register(HandshakePayload.TYPE, HandshakePayload.CODEC);
        // The far view announcement of a map shard (docs/DESIGN-M3.md API-M3-015), clientbound only
        PayloadTypeRegistry.clientboundConfiguration().register(FarViewPayload.TYPE, FarViewPayload.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(GamePayload.TYPE, (payload, _) -> {
            // Null means the decoder already dropped and warned about an unknown id.
            if (payload.message() != null) RTS.receive(payload.message());
        });
        ClientPlayNetworking.registerGlobalReceiver(PlayPayload.TYPE, (payload, context) -> {
            if (payload.message() != null) PLAY.receive(payload.message(), context.client().getConnection().registryAccess());
        });
        ClientConfigurationNetworking.registerGlobalReceiver(HandshakePayload.TYPE, (payload, _) -> {
            if (payload.message() instanceof S2CHello hello) HANDSHAKE.onServerHello(hello);
        });

        // START is the only one of the four configuration events whose contract allows sending,
        // and the engine's opening minecraft:brand is what fires it — before the server's three
        // second wait begins. READY is deprecated and fires with COMPLETE, long after that budget.
        ClientConfigurationConnectionEvents.START.register((_, _) ->
                HANDSHAKE.beginConfiguration(hello ->
                        ClientConfigurationNetworking.send(new HandshakePayload(hello))));

        // The login phase form, which the server picks with synaxis.app.handshake-phase=login. It
        // carries raw bytes rather than a payload type, and a future completed with null is how a
        // client says it does not know the channel.
        ClientLoginNetworking.registerGlobalReceiver(HandshakePayload.TYPE.id(), (_, _, buf, _) -> {
            final byte[] request = new byte[buf.readableBytes()];
            buf.readBytes(request);
            final byte[] answer = HANDSHAKE.answerLoginQuery(request);
            return CompletableFuture.completedFuture(
                    answer == null ? null : new FriendlyByteBuf(Unpooled.wrappedBuffer(answer)));
        });

        // Both of these belong to the start of something rather than the end. The handshake's is
        // the start of a connection attempt, before either phase; resetting any later would wipe a
        // handshake that the login phase had already completed. The RTS client's and the scene
        // mirror's are the start of the play phase: ClientPlayConnectionEvents.INIT fires from
        // ClientPacketListener's constructor, which runs on the packet processor thread — the one
        // that also delivers rts:game and synaxis:play — so the binding table and the scene stay
        // single threaded and every packet of the previous connection is behind it in the same
        // queue. DISCONNECT is not usable for this: Fabric fires it from Connection#channelInactive,
        // on a netty thread, which would have it clear the table while the main thread is still
        // handling that connection's last packets.
        ClientLoginConnectionEvents.INIT.register((_, _) -> HANDSHAKE.reset());
        ClientPlayConnectionEvents.INIT.register((_, _) -> {
            RTS.reset();
            PLAY.reset();
        });

        FarViewClient.init(() -> HANDSHAKE.state() == ClientHandshake.State.VERIFIED);
        PlayHud.register(PLAY, () -> ((BossHealthOverlayAccessor) Minecraft.getInstance().gui.hud.getBossOverlay())
                .synaxis$events().size());
        // The action key's presses, the TEXTs' time to live, the scene's readiness and the panel, in that order
        // at the end of every client tick: on the client thread, which also delivers synaxis:play
        ActionKeys.register();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ActionKeys.tick(PLAY);
            PLAY.tick(view(client));
            PanelScreen.sync(client, PLAY);
        });
    }

    /** The client as {@link SceneReadyProbe} judges it: in a level, the screen open, the chunk underfoot. */
    private static SceneReadyProbe.View view(Minecraft client) {
        final Screen screen = client.gui.screen();
        final boolean inWorld = client.level != null && client.player != null;
        final boolean chunk = inWorld && client.level.hasChunk(SectionPos.blockToSectionCoord(client.player.blockPosition().getX()),
                SectionPos.blockToSectionCoord(client.player.blockPosition().getZ()));
        return new SceneReadyProbe.View(inWorld, screen == null ? null : screen.getClass(), chunk);
    }
}
