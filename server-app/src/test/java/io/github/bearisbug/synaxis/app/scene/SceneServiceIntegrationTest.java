package io.github.bearisbug.synaxis.app.scene;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerTickEvent;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import io.github.bearisbug.synaxis.game.protocol.play.ActionResultCode;
import io.github.bearisbug.synaxis.game.protocol.play.C2SAction;
import io.github.bearisbug.synaxis.game.protocol.play.C2SSceneReady;
import io.github.bearisbug.synaxis.game.protocol.play.EncodedComponent;
import io.github.bearisbug.synaxis.game.protocol.play.PlayMessage;
import io.github.bearisbug.synaxis.game.protocol.play.S2CActionResult;
import io.github.bearisbug.synaxis.game.protocol.play.S2CHud;
import io.github.bearisbug.synaxis.game.protocol.play.S2CScene;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.server.common.PluginMessagePacket;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.plugin.play.ActionRequest;
import io.github.bearisbug.synaxis.plugin.play.ActionResult;
import io.github.bearisbug.synaxis.plugin.play.ActionSlot;
import io.github.bearisbug.synaxis.plugin.play.CameraSpec;
import io.github.bearisbug.synaxis.plugin.play.CursorMode;
import io.github.bearisbug.synaxis.plugin.play.HudSlot;
import io.github.bearisbug.synaxis.plugin.play.PluginPlay;
import io.github.bearisbug.synaxis.plugin.play.SceneSpec;
import io.github.bearisbug.synaxis.plugin.play.Target;
import io.github.bearisbug.synaxis.plugin.play.Widget;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scene layer of the host as the plugins drive it ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-005},
 * §6.4.3, the host integration row of §19): one exit at the end of each player's tick, the scene before the
 * HUD, the whole queue held while a move is under way, a scene change clearing the scene owner's widgets and
 * keeping the overlays', a scene left keeping its number, the sizes refused before anything changes, the
 * actions of a plugin taken back when it is torn down, the scene layer of a connection dropped when it goes
 * and an enter in the meantime doing nothing, and an enter refused for a connection outside the play state.
 * The calls of a plugin come from a thread that owns the player or from the serial part of the tick, and an
 * action of a scene or a panel is one the plugin registered; once a tick ran, the test thread no longer owns
 * the player and hands its calls over with {@link #owned}. It also times the exit (§15), printed only.
 */
@EnvTest
class SceneServiceIntegrationTest {

    private static final Pos SPAWN = new Pos(0, 41, 0);

    private static final SceneSpec LOBBY = new SceneSpec("lobby", Component.text("Lobby"), new CameraSpec.Vanilla(),
            CursorMode.LOCKED, List.of(new ActionSlot(0, "queue", Component.text("Queue"), Duration.ofSeconds(2))));
    private static final SceneSpec ROUND = new SceneSpec("round", Component.text("Round"), new CameraSpec.Vanilla(),
            CursorMode.LOCKED, List.of());

    private static final Widget HINT = new Widget.Text("hint", HudSlot.BOTTOM, 0, Component.text("Press H"), Duration.ZERO);
    private static final Widget TIMER = new Widget.Timer("timer", HudSlot.TOP_CENTER, 0, Component.text("Round"),
            Duration.ofSeconds(45), true);
    private static final Widget BANNER = new Widget.Text("banner", HudSlot.TOP_LEFT, 0, Component.text("Event"), Duration.ZERO);

    private final SceneTestKit.Ledger ledger = new SceneTestKit.Ledger();

    private record Client(TestConnection connection, Player player) {

        Collector<PluginMessagePacket> track() {
            return connection.trackIncoming(PluginMessagePacket.class);
        }
    }

    private static Client join(Env env, Instance instance) {
        final TestConnection connection = env.createConnection();
        return new Client(connection, connection.connect(instance, SPAWN));
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

    private static S2CHud.SetWidget set(String owner, Widget widget) {
        return new S2CHud.SetWidget(owner, widget.id(), WidgetEncoder.encode(owner, widget).widget());
    }

    /** Runs a call of the plugin on the tick thread that owns the player, the way {@code runOn} hands one over. */
    private static void owned(Player player, Runnable call) {
        player.acquirable().sync(_ -> call.run());
    }

    /** The action of {@link #LOBBY}'s key, which a plugin registers in its {@code onEnable}. */
    private static void registerQueue(PluginPlay rush) {
        rush.onAction("queue", (_, _) -> new ActionResult.Ok());
    }

    @Test
    void everyChangeOfATickLeavesAtItsEndAndTheSceneGoesFirst(Env env) {
        final SceneService scenes = SceneTestKit.service(env, "rush", ledger, System::nanoTime);
        final PluginPlay rush = scenes.forPlugin("rush");
        registerQueue(rush);
        final Client client = join(env, env.createFlatInstance());
        final Collector<PluginMessagePacket> beforeTheTick = client.track();

        rush.hud(client.player()).set(HINT);
        rush.enter(client.player(), LOBBY);
        rush.hud(client.player()).set(TIMER);

        assertEquals(List.of(), SceneTestKit.messages(beforeTheTick), "sent before the end of the tick");
        final Collector<PluginMessagePacket> firstTick = client.track();
        env.tick();
        final List<PlayMessage> messages = SceneTestKit.messages(firstTick);
        assertEquals(List.of(new S2CScene(1, "rush:lobby", WidgetEncoder.component(Component.text("Lobby")),
                        S2CScene.CAMERA_VANILLA, S2CScene.CURSOR_LOCKED,
                        List.of(new S2CScene.Action(0, "rush:queue", WidgetEncoder.component(Component.text("Queue")), 2_000))),
                new S2CHud(List.of(new S2CHud.ClearOwner("rush"), set("rush", TIMER)))), messages,
                "the hint set before the scene was entered reached the new scene");
        assertEquals(Optional.of("rush:lobby"), rush.currentScene(client.player()));
        final Map<String, ?> entered = ledger.of("play_scene_entered").getFirst().attributes();
        assertEquals(Map.of("connection_id", client.player().getPlayerConnection().connectionId(), "scene_id", "rush:lobby",
                "scene_seq", 1, "owner", "rush", "downgraded", false), entered);

        final Collector<PluginMessagePacket> secondTick = client.track();
        env.tick();
        assertEquals(List.of(), SceneTestKit.messages(secondTick), "the second tick sent the first one's changes again");
    }

    @Test
    void aMoveUnderWayHoldsTheWholeQueueUntilItCompletes(Env env) throws InterruptedException {
        final SceneService scenes = SceneTestKit.service(env, "rush", ledger, System::nanoTime);
        final PluginPlay rush = scenes.forPlugin("rush");
        final Client client = join(env, env.createFlatInstance());
        final CompletableFuture<Boolean> move = new CompletableFuture<>();

        scenes.holdUntil(client.player(), move);
        rush.enter(client.player(), ROUND);
        rush.hud(client.player()).set(TIMER);
        final Collector<PluginMessagePacket> held = client.track();
        env.tick();
        env.tick();
        assertEquals(List.of(), SceneTestKit.messages(held), "sent while the move was under way");

        // A move completes on another thread than the one that queued the scene
        Thread.ofVirtual().start(() -> move.complete(true)).join();
        final Collector<PluginMessagePacket> released = client.track();
        env.tick();
        final List<PlayMessage> messages = SceneTestKit.messages(released);
        assertEquals(2, messages.size(), messages.toString());
        assertEquals("rush:round", ((S2CScene) messages.get(0)).sceneId());
        assertEquals(new S2CHud(List.of(new S2CHud.ClearOwner("rush"), set("rush", TIMER))), messages.get(1));
    }

    @Test
    void aSceneChangeClearsTheSceneOwnersWidgetsAndKeepsTheOverlays(Env env) {
        final SceneService scenes = SceneTestKit.service(env, "rush", ledger, System::nanoTime);
        final PluginPlay rush = scenes.forPlugin("rush");
        final PluginPlay overlay = scenes.forPlugin("x");
        registerQueue(rush);
        final Client client = join(env, env.createFlatInstance());
        final Collector<PluginMessagePacket> packets = client.track();

        rush.enter(client.player(), LOBBY);
        rush.hud(client.player()).set(TIMER);
        overlay.hud(client.player()).set(BANNER);
        env.tick();
        owned(client.player(), () -> rush.enter(client.player(), ROUND));
        env.tick();

        final Map<String, Map<String, io.github.bearisbug.synaxis.game.protocol.play.Widget>> shown =
                SceneTestKit.apply(new HashMap<>(), SceneTestKit.messages(packets));
        assertEquals(Set.of("x"), shown.keySet(), "the widgets left on the client: " + shown);
        assertEquals(Set.of("banner"), shown.get("x").keySet());

        final Collector<PluginMessagePacket> removed = client.track();
        owned(client.player(), () -> overlay.hud(client.player()).remove("banner"));
        env.tick();
        assertEquals(List.of(new S2CHud(List.of(new S2CHud.RemoveWidget("x", "banner")))), SceneTestKit.messages(removed));
    }

    @Test
    void anOverlayCanNeitherEnterNorLeaveAScene(Env env) {
        final SceneService scenes = SceneTestKit.service(env, "rush", ledger, System::nanoTime);
        final PluginPlay overlay = scenes.forPlugin("x");
        final Client client = join(env, env.createFlatInstance());
        final Collector<PluginMessagePacket> packets = client.track();

        final IllegalStateException enter = assertThrows(IllegalStateException.class, () -> overlay.enter(client.player(), LOBBY));
        final IllegalStateException leave = assertThrows(IllegalStateException.class, () -> overlay.leave(client.player()));
        env.tick();

        for (IllegalStateException refused : List.of(enter, leave))
            assertEquals("plugin_id=x is not the scene owner of this process, plugin_id=rush is; the scene owner is the "
                    + "plugin that registered admission()", refused.getMessage());

        assertEquals(Optional.empty(), overlay.currentScene(client.player()));
        assertEquals(List.of(), SceneTestKit.messages(packets));
        assertEquals(List.of(), ledger.lines);
    }

    @Test
    void aPanelOf5000BytesIsRefusedAndChangesNothing(Env env) {
        final SceneService scenes = SceneTestKit.service(env, "rush", ledger, System::nanoTime);
        final PluginPlay rush = scenes.forPlugin("rush");
        final Client client = join(env, env.createFlatInstance());
        final List<Widget.Row> rows = new ArrayList<>();
        for (int i = 0; i < 12; i++) rows.add(new Widget.Row(Component.text("r".repeat(245)), "", false));
        final Widget.Panel panel = new Widget.Panel("result", Component.text("t".repeat(2_000)), rows, List.of());
        final Collector<PluginMessagePacket> packets = client.track();

        assertThrows(IllegalArgumentException.class, () -> rush.hud(client.player()).set(panel));
        env.tick();
        // Removing it would send a REMOVE had the table taken it
        owned(client.player(), () -> rush.hud(client.player()).remove("result"));
        env.tick();

        assertEquals(List.of(), SceneTestKit.messages(packets));
    }

    @Test
    void aSceneOver8KibIsRefusedAndChangesNothing(Env env) {
        final SceneService scenes = SceneTestKit.service(env, "rush", ledger, System::nanoTime);
        final PluginPlay rush = scenes.forPlugin("rush");
        final Client client = join(env, env.createFlatInstance());
        final Component long2000 = Component.text("l".repeat(2_000));
        final List<ActionSlot> slots = new ArrayList<>();
        for (int slot = 0; slot < 4; slot++) {
            rush.onAction("a" + slot, (_, _) -> new ActionResult.Ok());
            slots.add(new ActionSlot(slot, "a" + slot, long2000, Duration.ZERO));
        }
        final SceneSpec tooBig = new SceneSpec("big", long2000, new CameraSpec.Vanilla(), CursorMode.LOCKED, slots);
        final Collector<PluginMessagePacket> packets = client.track();

        assertThrows(IllegalArgumentException.class, () -> rush.enter(client.player(), tooBig));
        env.tick();

        assertEquals(Optional.empty(), rush.currentScene(client.player()));
        assertEquals(List.of(), SceneTestKit.messages(packets));
        assertEquals(List.of(), ledger.lines);
    }

    @Test
    void aPanelButtonIsAnActionTheHostTakes(Env env) {
        final SceneService scenes = SceneTestKit.service(env, "rush", ledger, System::nanoTime);
        final PluginPlay rush = scenes.forPlugin("rush");
        final List<ActionRequest> requests = new CopyOnWriteArrayList<>();
        rush.onAction("back", (_, request) -> {
            requests.add(request);
            return new ActionResult.Ok();
        });
        scenes.sealActions("rush");
        final Client client = join(env, env.createFlatInstance());
        rush.enter(client.player(), ROUND);
        rush.hud(client.player()).set(new Widget.Panel("result", Component.text("Result"), List.of(),
                List.of(new Widget.Button("back", Component.text("Back to the lobby")))));
        env.tick();
        final Collector<PluginMessagePacket> packets = client.track();

        scenes.receive(client.player(), action(1, 7, "rush:back"));

        assertEquals(List.of(new ActionRequest("rush:back", 7, new Target.None())), requests);
        assertEquals(List.of(new S2CActionResult(7, ActionResultCode.OK, EncodedComponent.NONE, 0)),
                SceneTestKit.messages(packets));
    }

    @Test
    void theActionsOfAPluginTornDownAreTakenBack(Env env) {
        final SceneService scenes = SceneTestKit.service(env, "rush", ledger, System::nanoTime);
        final PluginPlay rush = scenes.forPlugin("rush");
        final AtomicInteger calls = new AtomicInteger();
        rush.onAction("queue", (_, _) -> {
            calls.incrementAndGet();
            return new ActionResult.Ok();
        });
        scenes.sealActions("rush");
        final Client client = join(env, env.createFlatInstance());
        rush.enter(client.player(), LOBBY);
        env.tick();

        scenes.reclaim("rush");
        final Collector<PluginMessagePacket> packets = client.track();
        scenes.receive(client.player(), action(1, 3, "rush:queue"));

        assertEquals(0, calls.get(), "the handler of a plugin torn down was called");
        assertEquals(List.of(new S2CActionResult(3, ActionResultCode.UNKNOWN_ACTION, EncodedComponent.NONE, 0)),
                SceneTestKit.messages(packets));
        assertThrows(IllegalStateException.class, () -> rush.onAction("again", (_, _) -> new ActionResult.Ok()));
    }

    @Test
    void leavingASceneKeepsItsNumberAndDropsItsKeysAndTheSceneOwnersWidgets(Env env) {
        final SceneService scenes = SceneTestKit.service(env, "rush", ledger, System::nanoTime);
        final PluginPlay rush = scenes.forPlugin("rush");
        final PluginPlay overlay = scenes.forPlugin("x");
        rush.onAction("queue", (_, _) -> new ActionResult.Ok());
        scenes.sealActions("rush");
        final Client client = join(env, env.createFlatInstance());
        rush.enter(client.player(), LOBBY);
        rush.hud(client.player()).set(TIMER);
        overlay.hud(client.player()).set(BANNER);
        env.tick();
        final Collector<PluginMessagePacket> packets = client.track();

        owned(client.player(), () -> rush.leave(client.player()));
        env.tick();
        // The number stays: an action of the scene left is unknown now, not stale
        scenes.receive(client.player(), action(1, 2, "rush:queue"));
        scenes.receive(client.player(), ready(1));
        owned(client.player(), () -> {
            rush.leave(client.player());
            overlay.hud(client.player()).clear();
        });
        env.tick();
        owned(client.player(), () -> overlay.hud(client.player()).clear());
        env.tick();

        assertEquals(Optional.empty(), rush.currentScene(client.player()));
        assertEquals(List.of(new S2CHud(List.of(new S2CHud.ClearOwner("rush"))),
                new S2CActionResult(2, ActionResultCode.UNKNOWN_ACTION, EncodedComponent.NONE, 0),
                new S2CHud(List.of(new S2CHud.ClearOwner("x")))), SceneTestKit.messages(packets));
        assertEquals(List.of(), ledger.of("play_scene_ready"), "a ready of the scene left was recorded");
    }

    @Test
    void aConnectionThatGoesTakesItsSceneLayerAlongAndCannotEnterAnother(Env env) {
        final SceneService scenes = SceneTestKit.service(env, "rush", ledger, System::nanoTime);
        final PluginPlay rush = scenes.forPlugin("rush");
        registerQueue(rush);
        final Player player = join(env, env.createFlatInstance()).player();
        rush.enter(player, LOBBY);
        env.tick();
        assertEquals(Optional.of("rush:lobby"), rush.currentScene(player));

        // A test connection only goes offline; the removal on the player's next tick, which fires
        // PlayerDisconnectEvent, is what a real connection's disconnect adds in the play state
        player.getPlayerConnection().disconnect();
        owned(player, () -> rush.enter(player, ROUND));
        assertEquals(1, ledger.of("play_scene_entered").size(), "a scene was entered for a connection offline");
        player.scheduleNextTick(Entity::remove);
        env.tick();

        assertEquals(Optional.empty(), rush.currentScene(player), "the scene layer of a connection gone was kept");
    }

    @Test
    void aConnectionOnlineButOutsideThePlayStateCannotEnterAScene(Env env) {
        final SceneService scenes = SceneTestKit.service(env, "rush", ledger, System::nanoTime);
        final PluginPlay rush = scenes.forPlugin("rush");
        final Player player = join(env, env.createFlatInstance()).player();
        final PlayerConnection connection = player.getPlayerConnection();
        connection.setServerState(ConnectionState.CONFIGURATION);

        final IllegalStateException refused = assertThrows(IllegalStateException.class, () -> rush.enter(player, LOBBY));

        assertEquals("connection_id=" + connection.connectionId() + " is in CONFIGURATION, not play", refused.getMessage());
        assertEquals(Optional.empty(), rush.currentScene(player));
        assertEquals(List.of(), ledger.of("play_scene_entered"));
        connection.setServerState(ConnectionState.PLAY);
    }

    /**
     * {@code API-PLAY-005} 线程: the scene layer of a connection takes no lock, so a call from a thread that
     * may run at the same time as the player's tick, such as the asynchronous callback of a plugin, is refused
     * and changes nothing; the same call handed over to the player's thread goes through.
     */
    @Test
    void aCallFromAThreadThatDoesNotOwnThePlayerIsRefusedAndChangesNothing(Env env) throws InterruptedException {
        final SceneService scenes = SceneTestKit.service(env, "rush", ledger, System::nanoTime);
        final PluginPlay rush = scenes.forPlugin("rush");
        final Client client = join(env, env.createFlatInstance());
        final Player player = client.player();
        rush.enter(player, ROUND);
        rush.hud(player).set(HINT);
        env.tick();
        final Collector<PluginMessagePacket> packets = client.track();
        final List<Runnable> calls = List.of(() -> rush.enter(player, ROUND), () -> rush.leave(player),
                () -> rush.hud(player).set(TIMER), () -> rush.hud(player).remove("hint"), () -> rush.hud(player).clear());
        final List<String> refused = new CopyOnWriteArrayList<>();

        Thread.ofVirtual().start(() -> {
            for (Runnable call : calls) {
                try {
                    call.run();
                } catch (IllegalStateException e) {
                    refused.add(e.getMessage());
                }
            }
        }).join();
        env.tick();

        assertEquals(5, refused.size(), refused.toString());
        for (String message : refused)
            assertTrue(message.contains("runs on the tick thread that owns connection_id="
                    + player.getPlayerConnection().connectionId()) && message.contains("runOn"), message);
        assertEquals(List.of(), SceneTestKit.messages(packets), "a refused call changed what the client is sent");
        assertEquals(Optional.of("rush:round"), rush.currentScene(player));
        assertEquals(1, ledger.of("play_scene_entered").size());

        final Collector<PluginMessagePacket> handedOver = client.track();
        owned(player, () -> rush.hud(player).set(TIMER));
        env.tick();
        assertEquals(List.of(new S2CHud(List.of(set("rush", TIMER)))), SceneTestKit.messages(handedOver));
    }

    /**
     * {@code API-PLAY-005}: an action key or a panel button whose action nobody registered is refused when the
     * scene is entered or the widget set, before anything changes, rather than shown and only ever answered
     * {@code UNKNOWN_ACTION}.
     */
    @Test
    void anActionNobodyRegisteredIsRefusedAndChangesNothing(Env env) {
        final SceneService scenes = SceneTestKit.service(env, "rush", ledger, System::nanoTime);
        final PluginPlay rush = scenes.forPlugin("rush");
        registerQueue(rush);
        rush.onAction("back", (_, _) -> new ActionResult.Ok());
        scenes.sealActions("rush");
        final Client client = join(env, env.createFlatInstance());
        final SceneSpec misspelt = new SceneSpec("lobby", Component.text("Lobby"), new CameraSpec.Vanilla(),
                CursorMode.LOCKED, List.of(new ActionSlot(0, "queu", Component.text("Queue"), Duration.ZERO)));
        final Widget.Panel panel = new Widget.Panel("result", Component.text("Result"), List.of(),
                List.of(new Widget.Button("back", Component.text("Back")), new Widget.Button("bakc", Component.text("Back"))));
        final Collector<PluginMessagePacket> packets = client.track();

        final IllegalArgumentException scene = assertThrows(IllegalArgumentException.class,
                () -> rush.enter(client.player(), misspelt));
        final IllegalArgumentException widget = assertThrows(IllegalArgumentException.class,
                () -> rush.hud(client.player()).set(panel));
        env.tick();
        // Removing it would send a REMOVE had the table taken it
        owned(client.player(), () -> rush.hud(client.player()).remove("result"));
        env.tick();

        assertEquals("action rush:queu is not registered; register it with onAction in onEnable", scene.getMessage());
        assertEquals("action rush:bakc is not registered; register it with onAction in onEnable", widget.getMessage());
        assertEquals(Optional.empty(), rush.currentScene(client.player()));
        assertEquals(List.of(), SceneTestKit.messages(packets));
        assertEquals(List.of(), ledger.lines);
    }

    /**
     * {@code API-PLAY-005}: two action keys of a scene cannot send the same action, {@code queue} and
     * {@code rush:queue} being one: {@code C2S_ACTION} carries the action id and no key, so the host could not
     * tell which key's cooldown to start, while the client counts the one pressed.
     */
    @Test
    void twoActionKeysOfASceneCannotSendTheSameAction(Env env) {
        final SceneService scenes = SceneTestKit.service(env, "rush", ledger, System::nanoTime);
        final PluginPlay rush = scenes.forPlugin("rush");
        registerQueue(rush);
        final Client client = join(env, env.createFlatInstance());
        final SceneSpec twice = new SceneSpec("lobby", Component.text("Lobby"), new CameraSpec.Vanilla(), CursorMode.LOCKED,
                List.of(new ActionSlot(0, "queue", Component.text("Queue"), Duration.ofSeconds(2)),
                        new ActionSlot(1, "rush:queue", Component.text("Queue"), Duration.ZERO)));
        final Collector<PluginMessagePacket> packets = client.track();

        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> rush.enter(client.player(), twice));
        env.tick();

        assertEquals("action rush:queue is on two action keys of scene rush:lobby: an action is sent by its id, so each "
                + "key needs an action of its own", refused.getMessage());
        assertEquals(Optional.empty(), rush.currentScene(client.player()));
        assertEquals(List.of(), SceneTestKit.messages(packets));
        assertEquals(List.of(), ledger.lines);
    }

    @Test
    void actionsAreRegisteredInOnEnableOnlyOnceEachAndInTheirOwnNamespace(Env env) {
        final SceneService scenes = SceneTestKit.service(env, "rush", ledger, System::nanoTime);
        final PluginPlay rush = scenes.forPlugin("rush");

        rush.onAction("queue", (_, _) -> new ActionResult.Ok());
        assertThrows(IllegalStateException.class, () -> rush.onAction("rush:queue", (_, _) -> new ActionResult.Ok()),
                "the same action twice");
        assertThrows(IllegalArgumentException.class, () -> rush.onAction("rts:queue", (_, _) -> new ActionResult.Ok()),
                "another plugin's namespace");
        scenes.sealActions("rush");
        assertThrows(IllegalStateException.class, () -> rush.onAction("later", (_, _) -> new ActionResult.Ok()),
                "after onEnable");
        // Another plugin is still in its onEnable
        scenes.forPlugin("x").onAction("queue", (_, _) -> new ActionResult.Ok());
    }

    /**
     * §15: the exit of the scene layer per player per tick, with a timer and a scoreboard of eight rows set
     * every tick, the load of the sample game's round. Printed, not asserted: a shared machine's reading
     * is an order of magnitude only.
     */
    @Test
    void timesTheExitOfTheSceneLayerPerPlayerPerTick(Env env) {
        final SceneService scenes = SceneTestKit.service(env, "rush", ledger, System::nanoTime);
        final PluginPlay rush = scenes.forPlugin("rush");
        final Client client = join(env, env.createFlatInstance());
        rush.enter(client.player(), ROUND);
        final int warmUp = 2_000;
        final int measured = 10_000;
        final long[] exits = new long[measured];
        final long[] sets = new long[measured];
        Collector<PluginMessagePacket> lastTick = null;
        for (int i = 0; i < warmUp + measured; i++) {
            final List<Widget.Row> rows = new ArrayList<>();
            for (int row = 0; row < 8; row++)
                rows.add(new Widget.Row(Component.text("player" + row), String.valueOf(i + row), row == 0));
            if (i == warmUp + measured - 1) lastTick = client.track();
            final long start = System.nanoTime();
            rush.hud(client.player()).set(new Widget.Timer("timer", HudSlot.TOP_CENTER, 0, Component.text("Round"),
                    Duration.ofMillis(45_000 - i), true));
            rush.hud(client.player()).set(new Widget.Scoreboard("score", HudSlot.RIGHT, 0, Component.text("Score"), rows));
            final long set = System.nanoTime();
            env.process().eventHandler().call(new PlayerTickEvent(client.player()));
            final long exit = System.nanoTime();
            if (i >= warmUp) {
                sets[i - warmUp] = set - start;
                exits[i - warmUp] = exit - set;
            }
        }
        Arrays.sort(exits);
        Arrays.sort(sets);
        System.out.printf("scene layer exit per player per tick: mean=%.4f ms p99=%.4f ms max=%.4f ms over %d ticks; "
                        + "the two sets before it: mean=%.4f ms p99=%.4f ms%n",
                mean(exits), exits[measured * 99 / 100] / 1e6, exits[measured - 1] / 1e6, measured,
                mean(sets), sets[measured * 99 / 100] / 1e6);
        assertTrue(lastTick != null);
        final List<PlayMessage> last = SceneTestKit.messages(lastTick);
        assertEquals(1, last.size(), "the exit of one tick: " + last);
        assertEquals(2, ((S2CHud) last.getFirst()).ops().size());
    }

    private static double mean(long[] nanos) {
        return Arrays.stream(nanos).average().orElse(0) / 1e6;
    }
}
