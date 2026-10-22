package io.github.bearisbug.synaxis.client.gametest.play;

import io.github.bearisbug.synaxis.client.HandshakePayload;
import io.github.bearisbug.synaxis.client.SynaxisClientMod;
import io.github.bearisbug.synaxis.client.play.PlayClient;
import io.github.bearisbug.synaxis.client.play.PlayHud;
import io.github.bearisbug.synaxis.client.play.SceneReadyProbe;
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
 * What {@link PlayClientGameTest} reads of the client, kept in the gametest mod so that the shipped mod holds no
 * test hook ({@code docs/DESIGN-PLAY.md} §6.4.8): the client end of the test channel {@code synaxis_test:play}
 * of {@code PlayHudFixture}, the client state of every tick while a sampling runs, and the scene mirror of
 * {@code synaxis:play} as the HUD draws it, read off {@code SynaxisClientMod.PLAY} by reflection the way the
 * regression reads {@code HANDSHAKE}.
 * <p>
 * It also registers {@code synaxis:handshake} for the play phase, serverbound: the regression's R5 sends an
 * {@code S2C_HELLO} there, and the mod registers the channel for the configuration phase only.
 * <p>
 * Once a script starts a recording, the end of every client tick also records what arrived since the recording
 * began ({@code docs/DESIGN-PLAY.md} §6.4.8): each SET, with the moment {@code PlayClient} took it in and a
 * {@code TIMER}'s {@code remaining_ms}; each action result; and the client as {@code SceneReadyProbe} saw it when
 * it sent a {@code C2S_SCENE_READY}. What the connection held when the recording began, its SETs, its last result
 * and its last readiness, stays out. The recording samples the mirror once a tick: two SETs of one widget inside
 * one tick show as the later one, and so do two action results, so a script that reads the results one by one
 * waits for each before it presses again. The SETs are told apart by their number on the connection, which a new
 * connection starts again from 1, so a script starts its recording once it is on the server it records.
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

    /**
     * One SET the mirror took in.
     *
     * @param owner         the widget's owner
     * @param widgetId      its widget id
     * @param kind          its kind, the simple name of the content's class
     * @param set           the number of the SET on this connection
     * @param receivedNanos the client clock when {@code PlayClient} took it in
     * @param remainingMs   a {@code TIMER}'s {@code remaining_ms}, -1 for the other kinds
     */
    public record SetSeen(String owner, String widgetId, String kind, long set, long receivedNanos, int remainingMs) {
    }

    /** What a recording holds; client thread only. */
    static final class Recording {
        final List<SetSeen> sets = new ArrayList<>();
        final List<PlayClient.Result> results = new ArrayList<>();
        final List<SceneReadyProbe.Sent> readies = new ArrayList<>();
        // The newest SET number, result and readiness seen: the recording's own, or the ones there when it began
        long lastSet;
        PlayClient.@Nullable Result lastResult;
        SceneReadyProbe.@Nullable Sent lastReady;
    }

    static final BlockingQueue<byte[]> ANSWERS = new LinkedBlockingQueue<>();

    /** The samples of the running sampling, null when none runs; client thread only. */
    private static @Nullable List<Sample> samples;

    /** The running recording, null when none runs; client thread only. */
    private static @Nullable Recording recording;

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.serverboundPlay().register(Payload.TYPE, Payload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Payload.TYPE, Payload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(HandshakePayload.TYPE, HandshakePayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Payload.TYPE, (payload, _) -> ANSWERS.add(payload.data()));
        ClientTickEvents.END_CLIENT_TICK.register(PlayTestHook::sample);
        ClientTickEvents.END_CLIENT_TICK.register(_ -> record());
    }

    /** Starts a recording of the mirror's changes from now on; on the client thread. */
    static void startRecording() {
        final PlayClient play = play();
        final Recording started = new Recording();
        for (final PlayClient.Entry entry : play.widgets()) started.lastSet = Math.max(started.lastSet, entry.set());
        started.lastResult = play.lastResult();
        started.lastReady = play.probe().last();
        recording = started;
    }

    /**
     * The running recording; on the client thread.
     *
     * @return it, or an empty one when none runs
     */
    static Recording recording() {
        return recording == null ? new Recording() : recording;
    }

    /** Ends the recording; on the client thread. */
    static void stopRecording() {
        recording = null;
    }

    private static void record() {
        final Recording running = recording;
        if (running == null) return;
        final PlayClient play = play();
        long last = running.lastSet;
        for (final PlayClient.Entry entry : play.widgets()) {
            if (entry.set() <= running.lastSet) continue;
            running.sets.add(new SetSeen(entry.owner(), entry.widgetId(), entry.content().getClass().getSimpleName(), entry.set(),
                    entry.receivedNanos(), entry.content() instanceof PlayClient.Timer timer ? timer.remainingMs() : -1));
            last = Math.max(last, entry.set());
        }
        running.lastSet = last;
        final PlayClient.Result result = play.lastResult();
        if (result != null && !result.equals(running.lastResult)) running.results.add(result);
        running.lastResult = result;
        final SceneReadyProbe.Sent ready = play.probe().last();
        if (ready != null && !ready.equals(running.lastReady)) running.readies.add(ready);
        running.lastReady = ready;
    }

    /**
     * Turns the local cooldown of the action keys on or off; on the client thread.
     *
     * @param on whether a press inside the local cooldown is held back, the mod's default
     */
    static void localCooldown(boolean on) {
        play().localCooldown(on);
    }

    /**
     * @return how many actions the connection sent; on the client thread
     */
    static int actionsSent() {
        return play().actionsSent();
    }

    /**
     * Whether the mirror holds a panel at all, shown or closed locally; on the client thread.
     *
     * @return true when it does
     */
    static boolean holdsPanel() {
        return play().widgets().stream().anyMatch(entry -> entry.content() instanceof PlayClient.Panel);
    }

    /**
     * @return the reason shown on the panel the mirror shows, null for none; on the client thread
     */
    static @Nullable Component panelReason() {
        final PlayClient play = play();
        final PlayClient.Entry panel = play.panel();
        return panel == null ? null : play.panelReason(panel);
    }

    /**
     * What one {@code TIMER} shows now; on the client thread.
     *
     * @param widgetId the timer's widget id
     * @return the milliseconds left, -1 when there is no such timer
     */
    static long timerLeft(String widgetId) {
        final PlayClient play = play();
        return play.widgets().stream().filter(entry -> entry.widgetId().equals(widgetId) && entry.content() instanceof PlayClient.Timer)
                .findFirst().map(play::remainingMs).orElse(-1L);
    }

    /**
     * The widgets the mirror holds; on the client thread.
     *
     * @return {@code widget_id=Kind} in layout order
     */
    static List<String> widgets() {
        return play().widgets().stream().map(entry -> entry.widgetId() + "=" + entry.content().getClass().getSimpleName()).toList();
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
        final List<TextSeen> seen = new ArrayList<>();
        for (final PlayHud.Block block : PlayHud.blocks(play())) {
            if (!(block.entry().content() instanceof PlayClient.Text text)) continue;
            seen.add(new TextSeen(block.entry().widgetId(), text.text(), block.lines().getFirst().text().getString()));
        }
        return seen;
    }

    /**
     * The scene mirror, for what the other readers here do not give: the action table, a widget's content.
     *
     * @return {@code SynaxisClientMod.PLAY}; on the client thread only
     */
    static PlayClient play() {
        return field("PLAY", PlayClient.class);
    }

    private static <T> T field(String name, Class<T> type) {
        try {
            final Field field = SynaxisClientMod.class.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(null));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("SynaxisClientMod." + name + " is not readable; the play hook reads it by reflection", e);
        }
    }
}
