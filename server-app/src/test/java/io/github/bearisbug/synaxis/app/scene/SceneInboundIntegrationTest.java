package io.github.bearisbug.synaxis.app.scene;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.event.player.PlayerPluginMessageEvent;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import io.github.bearisbug.synaxis.game.protocol.play.ActionResultCode;
import io.github.bearisbug.synaxis.game.protocol.play.C2SAction;
import io.github.bearisbug.synaxis.game.protocol.play.C2SSceneReady;
import io.github.bearisbug.synaxis.game.protocol.play.EncodedComponent;
import io.github.bearisbug.synaxis.game.protocol.play.PlayMessage;
import io.github.bearisbug.synaxis.game.protocol.play.PlayProtocol;
import io.github.bearisbug.synaxis.game.protocol.play.S2CActionResult;
import io.github.bearisbug.synaxis.game.protocol.play.S2CScene;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientPluginMessagePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.DisconnectPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.PluginMessagePacket;
import io.github.bearisbug.synaxis.plugin.play.ActionResult;
import io.github.bearisbug.synaxis.plugin.play.ActionSlot;
import io.github.bearisbug.synaxis.plugin.play.CameraSpec;
import io.github.bearisbug.synaxis.plugin.play.CursorMode;
import io.github.bearisbug.synaxis.plugin.play.PluginPlay;
import io.github.bearisbug.synaxis.plugin.play.SceneSpec;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a client sends on {@code synaxis:play} ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-002}, §11.2): the
 * order the host judges an action in — stale scene, rate limit, unknown action, cooldown, the plugin's
 * handler — with exactly one answer each, a payload that does not decode ending the connection, and only
 * the first ready of the current scene reaching the ledger. The payloads go through the player's packet
 * queue, so they are handled on the tick thread that owns the player, as from a real client.
 */
@EnvTest
class SceneInboundIntegrationTest {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final Pos SPAWN = new Pos(0, 41, 0);

    private final SceneTestKit.Ledger ledger = new SceneTestKit.Ledger();
    private final AtomicLong clock = new AtomicLong(100 * SECOND);
    private final AtomicInteger queued = new AtomicInteger();
    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();

    private static final SceneSpec LOBBY = new SceneSpec("lobby", Component.text("Lobby"), new CameraSpec.Vanilla(),
            CursorMode.LOCKED, List.of(
            new ActionSlot(0, "queue", Component.text("Queue"), Duration.ofSeconds(2)),
            new ActionSlot(1, "deny", Component.text("Deny"), Duration.ZERO),
            new ActionSlot(2, "boom", Component.text("Boom"), Duration.ZERO),
            new ActionSlot(3, "switch", Component.text("Switch"), Duration.ofSeconds(5))));
    /** A reason with an argument and a fallback, the way a widget's text is written. */
    private static final Component NOT_NOW = Component.translatable("rush.no", Component.text(3)).fallback("Not now, %s s left");
    private static final SceneSpec ROUND = new SceneSpec("round", Component.text("Round"), new CameraSpec.Vanilla(),
            CursorMode.LOCKED, List.of());

    private record Client(TestConnection connection, Player player) {

        Collector<PluginMessagePacket> track() {
            return connection.trackIncoming(PluginMessagePacket.class);
        }
    }

    private SceneService scenes;
    private PluginPlay rush;

    @AfterEach
    void detachTheAppender() {
        ((Logger) LoggerFactory.getLogger(SceneService.class)).detachAppender(lines);
    }

    /**
     * The scene layer with {@code rush} as the scene owner and four actions; the payloads of
     * {@code synaxis:play} reach it the way the plugin host's router hands them over.
     */
    private void start(Env env, @Nullable String owner) {
        scenes = SceneTestKit.service(env, owner, ledger, clock::get);
        final EventNode<Event> router = EventNode.all("router-stand-in");
        router.addListener(PlayerPluginMessageEvent.class, event -> {
            if (event.getIdentifier().equals(PlayProtocol.CHANNEL)) scenes.receive(event.getPlayer(), event.getMessage());
        });
        env.process().eventHandler().addChild(router);
        rush = scenes.forPlugin("rush");
        rush.onAction("queue", (_, _) -> {
            queued.incrementAndGet();
            return new ActionResult.Ok();
        });
        rush.onAction("deny", (_, _) -> new ActionResult.Rejected(NOT_NOW));
        rush.onAction("boom", (_, _) -> {
            throw new IllegalStateException("a handler that fails");
        });
        rush.onAction("switch", (player, _) -> {
            rush.enter(player, ROUND);
            return new ActionResult.Ok();
        });
        scenes.sealActions("rush");
        lines.start();
        ((Logger) LoggerFactory.getLogger(SceneService.class)).addAppender(lines);
    }

    /** A player of the scene owner in the lobby, its scene sent: {@code scene_seq} 1. */
    private Client inTheLobby(Env env) {
        final Client client = join(env);
        rush.enter(client.player(), LOBBY);
        env.tick();
        return client;
    }

    private static Client join(Env env) {
        final TestConnection connection = env.createConnection();
        return new Client(connection, connection.connect(env.createFlatInstance(), SPAWN));
    }

    /** Enters a scene on the tick thread that owns the player, which the test thread no longer is once a tick ran. */
    private void enterOwned(Client client, SceneSpec scene) {
        client.player().acquirable().sync(player -> rush.enter(player, scene));
    }

    private static byte[] action(int sceneSeq, int actionSeq, String actionId) {
        final MsgBuf buf = MsgBuf.writing();
        new C2SAction(sceneSeq, actionSeq, actionId, new C2SAction.None()).write(buf);
        return buf.toByteArray();
    }

    private static byte[] ready(int sceneSeq) {
        final MsgBuf buf = MsgBuf.writing();
        new C2SSceneReady(sceneSeq).write(buf);
        return buf.toByteArray();
    }

    /** Queues the payloads as a client sends them, then ticks once: the player handles them on its thread. */
    private static void send(Env env, Client client, byte[]... payloads) {
        for (byte[] payload : payloads)
            client.player().addPacketToQueue(new ClientPluginMessagePacket(PlayProtocol.CHANNEL, payload));
        env.tick();
    }

    private static List<S2CActionResult> results(Collector<PluginMessagePacket> packets) {
        final List<S2CActionResult> results = new ArrayList<>();
        for (PlayMessage message : SceneTestKit.messages(packets))
            if (message instanceof S2CActionResult result) results.add(result);
        return results;
    }

    private static List<Integer> codes(List<S2CActionResult> results) {
        return results.stream().map(S2CActionResult::code).toList();
    }

    private static List<Integer> times(int count, ActionResultCode code) {
        return Collections.nCopies(count, code.wireId());
    }

    private List<String> linesAt(Level level) {
        return lines.list.stream().filter(line -> line.getLevel() == level).map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void anActionOfAnEarlierSceneIsAnsweredStaleAndNeverReachesTheHandler(Env env) {
        start(env, "rush");
        final Client client = inTheLobby(env);
        enterOwned(client, LOBBY);
        env.tick();
        final Collector<PluginMessagePacket> packets = client.track();

        send(env, client, action(1, 1, "rush:queue"));

        assertEquals(List.of(new S2CActionResult(1, ActionResultCode.STALE_SCENE, EncodedComponent.NONE, 0)), results(packets));
        assertEquals(0, queued.get());
    }

    @Test
    void aKeyStillCoolingDownIsAnsweredCooldownAndTheEleventhActionOfTheWindowIsRateLimited(Env env) {
        start(env, "rush");
        final Client client = inTheLobby(env);
        clock.set(100 * SECOND + 900_000_000L);
        final Collector<PluginMessagePacket> first = client.track();
        send(env, client, action(1, 0, "rush:queue"));
        assertEquals(List.of(new S2CActionResult(0, ActionResultCode.OK, EncodedComponent.NONE, 2_000)), results(first));

        // Eleven more in the window [101 s, 102 s), every one inside the 2 s cooldown
        clock.set(101 * SECOND + 200_000_000L);
        final Collector<PluginMessagePacket> packets = client.track();
        final byte[][] eleven = new byte[11][];
        for (int i = 0; i < 11; i++) eleven[i] = action(1, 1 + i, "rush:queue");
        send(env, client, eleven);

        final List<S2CActionResult> results = results(packets);
        final List<Integer> expected = new ArrayList<>(times(10, ActionResultCode.COOLDOWN));
        expected.add(ActionResultCode.RATE_LIMITED.wireId());
        assertEquals(expected, codes(results));
        final EncodedComponent twoSeconds = WidgetEncoder.component(
                Component.translatable("synaxis:action-cooldown", Component.text(2)));
        for (int i = 0; i < 10; i++)
            assertEquals(new S2CActionResult(1 + i, ActionResultCode.COOLDOWN, twoSeconds, 1_700), results.get(i));
        assertEquals(1, queued.get(), "the handler ran for an action still cooling down");
    }

    @Test
    void aStaleActionIsJudgedBeforeTheRateLimit(Env env) {
        start(env, "rush");
        final Client client = inTheLobby(env);
        enterOwned(client, LOBBY);
        env.tick();
        final Collector<PluginMessagePacket> packets = client.track();
        // Eleven of the scene before, then eleven of the current one, all in the window [100 s, 101 s)
        final byte[][] payloads = new byte[22][];
        for (int i = 0; i < 11; i++) payloads[i] = action(1, i, "rush:queue");
        for (int i = 11; i < 22; i++) payloads[i] = action(2, i, "rush:nope");

        send(env, client, payloads);

        final List<Integer> expected = new ArrayList<>(times(11, ActionResultCode.STALE_SCENE));
        expected.addAll(times(10, ActionResultCode.UNKNOWN_ACTION));
        expected.add(ActionResultCode.RATE_LIMITED.wireId());
        assertEquals(expected, codes(results(packets)), "a stale action took a place in the window of the rate limit");
        assertEquals(0, queued.get());
    }

    @Test
    void theRateLimitIsJudgedBeforeTheActionIsLookedUp(Env env) {
        start(env, "rush");
        final Client client = inTheLobby(env);
        final Collector<PluginMessagePacket> packets = client.track();
        final byte[][] eleven = new byte[11][];
        for (int i = 0; i < 11; i++) eleven[i] = action(1, i, "rush:nope");

        send(env, client, eleven);

        final List<Integer> expected = new ArrayList<>(times(10, ActionResultCode.UNKNOWN_ACTION));
        expected.add(ActionResultCode.RATE_LIMITED.wireId());
        assertEquals(expected, codes(results(packets)), "an unknown action escaped the rate limit");
    }

    @Test
    void aRefusalCarriesThePluginsReasonAndAFailingHandlerIsRefusedWithoutOne(Env env) {
        start(env, "rush");
        final Client client = inTheLobby(env);
        final Collector<PluginMessagePacket> packets = client.track();

        send(env, client, action(1, 4, "rush:deny"), action(1, 5, "rush:boom"));

        assertEquals(List.of(
                new S2CActionResult(4, ActionResultCode.REJECTED, WidgetEncoder.component(NOT_NOW), 0),
                new S2CActionResult(5, ActionResultCode.REJECTED, EncodedComponent.NONE, 0)), results(packets));
        final List<String> errors = linesAt(Level.ERROR);
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.getFirst().contains("action_id=rush:boom"), errors.getFirst());
    }

    @Test
    void aHandlerThatChangesTheSceneIsAnsweredWithoutStartingACooldown(Env env) {
        start(env, "rush");
        final Client client = inTheLobby(env);
        final Collector<PluginMessagePacket> packets = client.track();

        send(env, client, action(1, 9, "rush:switch"));

        final List<PlayMessage> messages = SceneTestKit.messages(packets);
        assertEquals(new S2CActionResult(9, ActionResultCode.OK, EncodedComponent.NONE, 0), messages.getFirst());
        assertEquals("rush:round", ((S2CScene) messages.get(1)).sceneId());
        assertEquals(2, ((S2CScene) messages.get(1)).sceneSeq());
    }

    @Test
    void aPayloadThatDoesNotDecodeOrIsAMessageOfOursDisconnects(Env env) {
        start(env, "rush");
        final Client truncated = inTheLobby(env);
        final Client echoing = inTheLobby(env);
        final Collector<DisconnectPacket> firstKick = truncated.connection().trackIncoming(DisconnectPacket.class);
        final Collector<DisconnectPacket> secondKick = echoing.connection().trackIncoming(DisconnectPacket.class);
        final MsgBuf result = MsgBuf.writing();
        new S2CActionResult(1, ActionResultCode.OK, EncodedComponent.NONE, 0).write(result);

        send(env, truncated, new byte[]{0x02, 0x01});
        send(env, echoing, result.toByteArray());

        firstKick.assertSingle(packet -> assertEquals(Component.translatable("synaxis:protocol-error"), packet.message()));
        secondKick.assertSingle(packet -> assertEquals(Component.translatable("synaxis:protocol-error"), packet.message()));
        assertFalse(truncated.player().getPlayerConnection().isOnline());
        assertFalse(echoing.player().getPlayerConnection().isOnline());
    }

    @Test
    void anUnknownMessageIsDroppedWithoutAnAnswerAndItsLineIsWithinTheBudget(Env env) {
        start(env, "rush");
        final Client client = inTheLobby(env);
        final Collector<PluginMessagePacket> packets = client.track();

        // 200 payloads, at most 50 handled a tick
        for (int batch = 0; batch < 4; batch++) {
            final byte[][] fifty = new byte[50][];
            for (int i = 0; i < 50; i++) fifty[i] = new byte[]{0x7f, (byte) i};
            send(env, client, fifty);
        }

        assertTrue(client.player().getPlayerConnection().isOnline(), "an unknown message ended the connection");
        assertEquals(List.of(), SceneTestKit.messages(packets));
        final List<String> warnings = linesAt(Level.WARN);
        assertEquals(1, warnings.size(), "200 unknown messages wrote " + warnings.size() + " lines");
    }

    @Test
    void onlyTheFirstReadyOfTheCurrentSceneReachesTheLedger(Env env) {
        start(env, "rush");
        final Client client = inTheLobby(env);
        enterOwned(client, LOBBY);
        env.tick();

        send(env, client, ready(1), ready(2), ready(2), ready(3));

        final List<SceneTestKit.Line> recorded = ledger.of("play_scene_ready");
        assertEquals(List.of(new SceneTestKit.Line("play_scene_ready", Map.of(
                "connection_id", client.player().getPlayerConnection().connectionId(), "scene_seq", 2))), recorded);
    }

    @Test
    void withoutASceneOwnerEveryPayloadIsDroppedUnreadUnloggedAndUnanswered(Env env) {
        start(env, null);
        final Client client = join(env);
        final Collector<PluginMessagePacket> packets = client.track();
        final Collector<DisconnectPacket> kicks = client.connection().trackIncoming(DisconnectPacket.class);
        final MsgBuf result = MsgBuf.writing();
        new S2CActionResult(1, ActionResultCode.OK, EncodedComponent.NONE, 0).write(result);

        send(env, client, action(0, 0, "rush:queue"), ready(1), new byte[]{0x02, 0x01}, new byte[]{0x7f},
                result.toByteArray(), new byte[0]);

        assertEquals(List.of(), SceneTestKit.messages(packets));
        kicks.assertEmpty();
        assertTrue(client.player().getPlayerConnection().isOnline());
        assertEquals(List.of(), lines.list, "lines written for payloads nobody owns");
        assertEquals(0, queued.get());
        assertEquals(List.of(), ledger.lines);
    }
}
