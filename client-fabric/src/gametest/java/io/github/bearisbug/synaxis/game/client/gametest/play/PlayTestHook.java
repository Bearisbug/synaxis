package io.github.bearisbug.synaxis.game.client.gametest.play;

import io.github.bearisbug.synaxis.game.client.SynaxisClientMod;
import io.github.bearisbug.synaxis.game.client.play.PlayClient;
import io.github.bearisbug.synaxis.game.client.play.PlayHud;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * What {@link RushClientGameTest} reads of the client, kept in the gametest mod so that the shipped mod holds no
 * test hook ({@code docs/DESIGN-PLAY.md} §6.4.8): the client end of the test channel {@code synaxis_test:play}
 * of {@code PlayHudFixture}, the client state of every tick while a sampling runs, and the scene mirror of
 * {@code synaxis:play} as the HUD draws it, read off {@code SynaxisClientMod.PLAY} by reflection the way the
 * regression reads {@code RTS}.
 */
public final class PlayTestHook implements ClientModInitializer {

    /** One message of the test channel in either direction, its bytes as they are. */
    public record Payload(byte[] data) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("synaxis_test", "play"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC = CustomPacketPayload.codec(
                (payload, buf) -> buf.writeBytes(payload.data()), buf -> {
                    final byte[] data = new byte[buf.readableBytes()];
                    buf.readBytes(data);
                    return new Payload(data);
                });

        @Override
        public Type<Payload> type() {
            return TYPE;
        }
    }

    /**
     * The client at the end of one tick of a sampling.
     *
     * @param tick      the tick, 1 for the first one that ended after the sampling began
     * @param nanos     {@link System#nanoTime} at its end
     * @param screen    the class of the open screen, null for none
     * @param dimension the dimension name of the client's level, null for none
     * @param hasChunk  whether the level holds the chunk under the player
     */
    public record Sample(int tick, long nanos, @Nullable String screen, @Nullable String dimension, boolean hasChunk) {
    }

    /**
     * The scene the client is in, as the mirror holds it.
     *
     * @param seq   its {@code scene_seq}
     * @param id    its scene id
     * @param title its title, decoded
     */
    public record SceneSeen(int seq, String id, Component title) {
    }

    /**
     * One {@code TEXT} the mirror holds, in the order the HUD draws them.
     *
     * @param widgetId its widget id
     * @param text     the component, decoded
     * @param drawn    the string the HUD draws for it: the component resolved against the language files
     */
    public record TextSeen(String widgetId, Component text, String drawn) {
    }

    static final BlockingQueue<byte[]> ANSWERS = new LinkedBlockingQueue<>();

    /** The samples of the running sampling, null when none runs; client thread only. */
    private static @Nullable List<Sample> samples;

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.serverboundPlay().register(Payload.TYPE, Payload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Payload.TYPE, Payload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Payload.TYPE, (payload, _) -> ANSWERS.add(payload.data()));
        ClientTickEvents.END_CLIENT_TICK.register(PlayTestHook::sample);
    }

    /** Starts a sampling of every client tick; on the client thread. */
    static void startSampling() {
        samples = new ArrayList<>();
    }

    /**
     * Ends the sampling; on the client thread.
     *
     * @return its samples
     */
    static List<Sample> stopSampling() {
        final List<Sample> taken = samples == null ? List.of() : List.copyOf(samples);
        samples = null;
        return taken;
    }

    private static void sample(Minecraft client) {
        final List<Sample> running = samples;
        if (running == null) return;
        final Screen screen = client.gui.screen();
        final boolean hasChunk = client.level != null && client.player != null && client.level.hasChunk(
                client.player.blockPosition().getX() >> 4, client.player.blockPosition().getZ() >> 4);
        running.add(new Sample(running.size() + 1, System.nanoTime(), screen == null ? null : screen.getClass().getName(),
                client.level == null ? null : client.level.dimension().identifier().toString(), hasChunk));
    }

    /**
     * The scene; on the client thread.
     *
     * @return the scene, null before the connection's first {@code S2C_SCENE}
     */
    static @Nullable SceneSeen scene() {
        final PlayClient.Scene scene = play().scene();
        return scene == null ? null : new SceneSeen(scene.seq(), scene.id(), scene.title());
    }

    /**
     * The {@code TEXT}s and what the HUD draws for each; on the client thread.
     *
     * @return them top to bottom, none before the connection's first scene
     */
    static List<TextSeen> texts() {
        final PlayClient play = play();
        final List<Component> lines = PlayHud.lines(play);
        final List<PlayClient.Text> texts = play.texts();
        final List<TextSeen> seen = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            seen.add(new TextSeen(texts.get(i).widgetId(), texts.get(i).text(), lines.get(i).getString()));
        }
        return seen;
    }

    private static PlayClient play() {
        try {
            final Field field = SynaxisClientMod.class.getDeclaredField("PLAY");
            field.setAccessible(true);
            return (PlayClient) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("SynaxisClientMod.PLAY is not readable; the play hook reads it by reflection", e);
        }
    }
}
