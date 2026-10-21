package io.github.bearisbug.synaxis.app.scene;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.TickSchedulerThread;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.event.player.PlayerDisconnectEvent;
import io.github.bearisbug.synaxis.event.player.PlayerTickEvent;
import io.github.bearisbug.synaxis.game.protocol.MalformedMessageException;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import io.github.bearisbug.synaxis.game.protocol.UnknownMessageException;
import io.github.bearisbug.synaxis.game.protocol.play.ActionResultCode;
import io.github.bearisbug.synaxis.game.protocol.play.C2SAction;
import io.github.bearisbug.synaxis.game.protocol.play.C2SSceneReady;
import io.github.bearisbug.synaxis.game.protocol.play.EncodedComponent;
import io.github.bearisbug.synaxis.game.protocol.play.PlayMessage;
import io.github.bearisbug.synaxis.game.protocol.play.PlayMessages;
import io.github.bearisbug.synaxis.game.protocol.play.PlayProtocol;
import io.github.bearisbug.synaxis.game.protocol.play.S2CActionResult;
import io.github.bearisbug.synaxis.game.protocol.play.S2CScene;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.DisconnectReasons;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.plugin.LogBudget;
import io.github.bearisbug.synaxis.plugin.PluginAnalytics;
import io.github.bearisbug.synaxis.plugin.play.ActionHandler;
import io.github.bearisbug.synaxis.plugin.play.ActionRequest;
import io.github.bearisbug.synaxis.plugin.play.ActionResult;
import io.github.bearisbug.synaxis.plugin.play.ActionSlot;
import io.github.bearisbug.synaxis.plugin.play.CameraSpec;
import io.github.bearisbug.synaxis.plugin.play.PluginPlay;
import io.github.bearisbug.synaxis.plugin.play.SceneSpec;
import io.github.bearisbug.synaxis.plugin.play.Target;
import io.github.bearisbug.synaxis.plugin.play.Widget;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The host's scene layer ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-002}, {@code API-PLAY-005},
 * §6.4.3): the channel {@code synaxis:play}, the scene and the widget table of every connection, and
 * the actions of the plugins. One per process, built by the plugin host; each plugin sees it through
 * the {@link PluginPlay} of {@link #forPlugin}.
 * <p>
 * Nothing is sent when a plugin calls: scene and widget changes wait in the connection's {@link Outbox}
 * until the end of the player's tick, {@link PlayerTickEvent}, which the server fires at the end of every
 * player update, after the inbound packets of that tick. {@code PlayerTickEndEvent} would not do: a
 * client's {@code ClientTickEndPacket} fires it, so the client would pick how often the exit runs.
 * <p>
 * <b>Everything that arrives on the channel is the client's to pick</b>, so no line of this class is
 * written per payload: every one goes through a {@link LogBudget}. A process without a scene owner — the
 * core mode and a map shard, where the host holds the admission slot itself — drops every payload before
 * reading a byte of it, and never answers one.
 */
public final class SceneService {

    /**
     * The host channel of the scene layer, in the reserved namespace.
     */
    public static final String CHANNEL = "synaxis:play";

    /**
     * The id the host takes the admission slot under in the core mode and on a map shard, the reserved
     * namespace that no plugin may have: holding the slot under it makes no plugin the scene owner.
     */
    private static final String HOST_ID = "synaxis";

    /**
     * The reason of a {@link ActionResultCode#COOLDOWN} answer, with the seconds left as its argument
     * (§14).
     */
    private static final String COOLDOWN_KEY = "synaxis:action-cooldown";

    private static final Logger LOGGER = LoggerFactory.getLogger(SceneService.class);

    private final Supplier<@Nullable String> sceneOwner;
    private final PluginAnalytics ledger;
    private final LongSupplier nanoClock;
    private final ActionRegistry actions = new ActionRegistry(LOGGER);
    private final Map<UUID, PlayerScene> scenes = new ConcurrentHashMap<>();

    /**
     * The budget of the drop line, shared by every unknown {@code msg_id}: the client invents the id, so a
     * budget per id would be a budget per payload.
     */
    private final LogBudget drops = new LogBudget(LOGGER, "unknown play messages");

    /** The budget of the protocol error line, one per connection at most before the kick. */
    private final LogBudget refusals = new LogBudget(LOGGER, "refused play payloads");

    /** The budget of a failing flush, which a widget the plugin set drives. */
    private final LogBudget flushFailures = new LogBudget(LOGGER, "failed flushes");

    /**
     * @param process    the engine process
     * @param sceneOwner the id of the plugin allowed to enter and leave scenes, the holder of the
     *                   admission slot; null while nobody holds it
     * @param ledger     the event ledger, for {@code play_scene_entered} and {@code play_scene_ready}
     * @param nanoClock  a monotonic clock in nanoseconds, {@link System#nanoTime} outside tests; the
     *                   rate limit and the cooldowns run on it
     */
    public SceneService(ServerProcess process, Supplier<@Nullable String> sceneOwner, PluginAnalytics ledger,
                        LongSupplier nanoClock) {
        this.sceneOwner = sceneOwner;
        this.ledger = ledger;
        this.nanoClock = nanoClock;
    }

    /**
     * Hangs the listeners of the scene layer on the host node: the flush at the end of every
     * player's tick and the cleanup when a connection goes.
     *
     * @param hostNode the host node
     */
    public void attach(EventNode<Event> hostNode) {
        hostNode.addListener(PlayerTickEvent.class, event -> flush(event.getPlayer()));
        hostNode.addListener(PlayerDisconnectEvent.class,
                event -> scenes.remove(event.getPlayer().getPlayerConnection().connectionId()));
    }

    /**
     * Handles one payload a client sent on {@link #CHANNEL}, on the player's tick thread. The router
     * hands over the payloads of a connection in the play state only.
     * <p>
     * Without a scene owner the payload is dropped unread, unlogged and unanswered (§6.2). Otherwise
     * an unknown {@code msg_id} is dropped, a payload that does not decode or carries a message of
     * ours disconnects with {@code synaxis:protocol-error}, and every action is answered exactly once.
     *
     * @param player  the sender
     * @param payload the payload
     */
    public void receive(Player player, byte[] payload) {
        if (owner() == null) return;
        final PlayerConnection connection = player.getPlayerConnection();
        if (!connection.isOnline()) return;
        final PlayMessage message;
        try {
            message = PlayMessages.read(MsgBuf.reading(payload));
        } catch (UnknownMessageException e) {
            drops.warn("Dropped an unknown play message: connection_id={} msg_id=0x{}",
                    connection.connectionId(), Integer.toHexString(e.msgId()));
            return;
        } catch (MalformedMessageException e) {
            refuse(connection, "the play payload does not decode: " + e.getMessage());
            return;
        }
        switch (message) {
            case C2SSceneReady ready -> ready(player, ready);
            case C2SAction action -> act(player, action);
            default -> refuse(connection, "msg_id=0x" + Integer.toHexString(message.msgId())
                    + " is a message of ours, not a request");
        }
    }

    /**
     * The scene layer as one plugin sees it.
     *
     * @param pluginId the plugin
     * @return the play capability of that plugin
     */
    public PluginPlay forPlugin(String pluginId) {
        return new PluginPlayImpl(pluginId, this);
    }

    /**
     * Closes the action registration of a plugin, once its {@code onEnable} returned: from then on
     * {@link PluginPlay#onAction} throws.
     *
     * @param pluginId the plugin
     */
    public void sealActions(String pluginId) {
        actions.seal(pluginId);
    }

    /**
     * Takes back the actions of a plugin being torn down.
     *
     * @param pluginId the plugin
     */
    public void reclaim(String pluginId) {
        actions.reclaim(pluginId);
    }

    /**
     * Holds back everything queued for a player until a move into another instance completes, so
     * that the client sees the respawn before the scene (§6.4.3). Any thread.
     *
     * @param player the player being moved
     * @param move   completes when the move is done, whichever way it went
     */
    public void holdUntil(Player player, CompletableFuture<?> move) {
        final PlayerScene scene = sceneOf(player);
        if (scene != null) scene.outbox().holdUntil(move);
    }

    void enter(String pluginId, Player player, SceneSpec spec) {
        checkThread(player, "enter");
        final String owner = owner();
        if (!pluginId.equals(owner))
            throw new IllegalStateException("plugin_id=" + pluginId + " is not the scene owner of this process"
                    + (owner == null ? ", which has none" : ", plugin_id=" + owner + " is"));
        final PlayerConnection connection = player.getPlayerConnection();
        final PlayerScene scene = connection.getServerState() == ConnectionState.PLAY ? sceneOf(player) : null;
        if (scene == null) {
            // Offline since it dropped, before PlayerDisconnectEvent: nothing to enter, as for a widget set
            if (!connection.isOnline()) return;
            throw new IllegalStateException("connection_id=" + connection.connectionId() + " is in "
                    + connection.getServerState() + ", not play");
        }
        final String sceneId = PlayIds.qualify(pluginId, spec.id());
        final List<S2CScene.Action> table = new ArrayList<>(spec.actions().size());
        final List<PlayerScene.Slot> slots = new ArrayList<>(spec.actions().size());
        for (final ActionSlot action : spec.actions()) {
            final String actionId = PlayIds.qualify(pluginId, action.actionId());
            checkRegistered(actionId);
            final int cooldownMs = (int) action.cooldown().toMillis();
            table.add(new S2CScene.Action(action.slot(), actionId, WidgetEncoder.component(action.label()), cooldownMs));
            slots.add(new PlayerScene.Slot(action.slot(), actionId, cooldownMs));
        }
        final int camera = switch (spec.camera()) {
            case CameraSpec.Vanilla _ -> S2CScene.CAMERA_VANILLA;
        };
        final int cursor = switch (spec.cursor()) {
            case LOCKED -> S2CScene.CURSOR_LOCKED;
        };
        final S2CScene message = new S2CScene(scene.sceneSeq() + 1, sceneId, WidgetEncoder.component(spec.title()),
                camera, cursor, table);
        final int bytes = message.encode().length;
        if (bytes > PlayProtocol.MAX_MESSAGE_BYTES)
            throw new IllegalArgumentException("scene " + sceneId + " encodes to " + bytes + " bytes, over the "
                    + PlayProtocol.MAX_MESSAGE_BYTES + " a scene may take");
        scene.enter(message, pluginId, slots);
        final Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("connection_id", connection.connectionId());
        attributes.put("scene_id", sceneId);
        attributes.put("scene_seq", message.sceneSeq());
        attributes.put("owner", pluginId);
        attributes.put("downgraded", false);
        ledger.record("play_scene_entered", attributes);
    }

    void leave(String pluginId, Player player) {
        checkThread(player, "leave");
        final String owner = owner();
        if (!pluginId.equals(owner))
            throw new IllegalStateException("plugin_id=" + pluginId + " is not the scene owner of this process"
                    + (owner == null ? ", which has none" : ", plugin_id=" + owner + " is"));
        final PlayerScene scene = scenes.get(player.getPlayerConnection().connectionId());
        if (scene != null) scene.leave(pluginId);
    }

    Optional<String> currentScene(Player player) {
        final PlayerScene scene = scenes.get(player.getPlayerConnection().connectionId());
        return scene == null ? Optional.empty() : Optional.ofNullable(scene.sceneId());
    }

    void set(String pluginId, Player player, Widget widget) {
        checkThread(player, "set");
        final WidgetEncoder.Encoded encoded = WidgetEncoder.encode(pluginId, widget);
        for (final String actionId : encoded.buttonActions()) checkRegistered(actionId);
        final PlayerScene scene = sceneOf(player);
        if (scene != null) scene.set(pluginId, widget.id(), encoded);
    }

    void remove(String pluginId, Player player, String widgetId) {
        checkThread(player, "remove");
        final PlayerScene scene = scenes.get(player.getPlayerConnection().connectionId());
        if (scene != null) scene.remove(pluginId, widgetId);
    }

    void clear(String pluginId, Player player) {
        checkThread(player, "clear");
        final PlayerScene scene = scenes.get(player.getPlayerConnection().connectionId());
        if (scene != null) scene.clear(pluginId);
    }

    void onAction(String pluginId, String actionId, ActionHandler handler) {
        actions.register(pluginId, actionId, handler);
    }

    /**
     * Refuses a write from a thread that may run at the same time as the player's tick: the scene layer of
     * a connection takes no lock, and the end of the player's tick reads it (API-PLAY-005).
     *
     * @throws IllegalStateException unless the calling thread owns the player or runs the serial part of
     *                               the tick
     */
    private static void checkThread(Player player, String call) {
        if (player.acquirable().isOwned() || Thread.currentThread() instanceof TickSchedulerThread) return;
        throw new IllegalStateException(call + " runs on the tick thread that owns connection_id="
                + player.getPlayerConnection().connectionId() + " or in the serial part of the tick, not on "
                + Thread.currentThread().getName() + ": hand it over with scheduler().runOn(player, ...)");
    }

    /**
     * Refuses an action key or a panel button whose action no plugin registered, which the client would
     * show and only ever get {@code UNKNOWN_ACTION} back for: the actions are registered in
     * {@code onEnable}, so the registry is complete by the time a scene is entered.
     *
     * @throws IllegalArgumentException if no plugin registered the action
     */
    private void checkRegistered(String actionId) {
        if (actions.find(actionId) == null)
            throw new IllegalArgumentException("action " + actionId + " is not registered; register it with onAction in onEnable");
    }

    /**
     * @return the plugin that may enter and leave scenes, null when no plugin holds the admission slot
     */
    private @Nullable String owner() {
        final String holder = sceneOwner.get();
        return holder == null || holder.equals(HOST_ID) ? null : holder;
    }

    /**
     * @return the scene layer of the player's connection, made on first use; null once it is offline,
     * so that a call coming after the disconnect cannot leave an entry nobody takes away
     */
    private @Nullable PlayerScene sceneOf(Player player) {
        final PlayerConnection connection = player.getPlayerConnection();
        if (!connection.isOnline()) return null;
        return scenes.computeIfAbsent(connection.connectionId(), _ -> new PlayerScene());
    }

    private void flush(Player player) {
        final UUID connectionId = player.getPlayerConnection().connectionId();
        final PlayerScene scene = scenes.get(connectionId);
        if (scene == null) return;
        try {
            scene.outbox().flush(bytes -> player.sendPluginMessage(CHANNEL, bytes));
        } catch (RuntimeException e) {
            scene.outbox().discard();
            flushFailures.error(e, "Dropped the scene layer changes of one tick: connection_id={}", connectionId);
        }
    }

    private void ready(Player player, C2SSceneReady ready) {
        final UUID connectionId = player.getPlayerConnection().connectionId();
        final PlayerScene scene = scenes.get(connectionId);
        if (scene == null || !scene.ready(ready.sceneSeq())) return;
        final Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("connection_id", connectionId);
        attributes.put("scene_seq", ready.sceneSeq());
        ledger.record("play_scene_ready", attributes);
    }

    /**
     * Judges one action in the order of {@code API-PLAY-002} — stale scene, rate limit, unknown action,
     * cooldown, the plugin's handler — and answers it once. An action one check refuses never reaches the
     * next one.
     */
    private void act(Player player, C2SAction action) {
        final PlayerScene scene = sceneOf(player);
        if (scene == null) return;
        final long now = nanoClock.getAsLong();
        final int actionSeq = action.actionSeq();
        if (action.sceneSeq() != scene.sceneSeq()) {
            answer(player, actionSeq, ActionResultCode.STALE_SCENE, EncodedComponent.NONE, 0);
            return;
        }
        if (!scene.gate().admit(now)) {
            answer(player, actionSeq, ActionResultCode.RATE_LIMITED, EncodedComponent.NONE, 0);
            return;
        }
        final String actionId = action.actionId();
        final PlayerScene.Slot slot = scene.slotOf(actionId);
        final ActionRegistry.Registration registration = actions.find(actionId);
        if ((slot == null && !scene.hasButton(actionId)) || registration == null) {
            answer(player, actionSeq, ActionResultCode.UNKNOWN_ACTION, EncodedComponent.NONE, 0);
            return;
        }
        if (slot != null) {
            final long left = scene.gate().cooldownLeft(slot.slot(), now);
            if (left > 0) {
                final int leftMs = (int) Math.min(PlayProtocol.MAX_COOLDOWN_MS,
                        Math.ceilDiv(left, TimeUnit.MILLISECONDS.toNanos(1)));
                answer(player, actionSeq, ActionResultCode.COOLDOWN, WidgetEncoder.component(Component.translatable(
                        COOLDOWN_KEY, Component.text(Math.ceilDiv(leftMs, 1000)))), leftMs);
                return;
            }
        }
        final ActionResult result;
        try {
            result = registration.handler().handle(player, new ActionRequest(actionId, actionSeq, target(action.target())));
        } catch (Throwable throwable) {
            registration.failures().error(throwable, "Action handler failed: plugin_id={} action_id={} connection_id={}",
                    registration.pluginId(), actionId, player.getPlayerConnection().connectionId());
            answer(player, actionSeq, ActionResultCode.REJECTED, EncodedComponent.NONE, 0);
            return;
        }
        switch (result) {
            case ActionResult.Ok _ -> {
                // A handler that changed the scene started the new one with no cooldowns (§11.2)
                final int cooldownMs = slot != null && scene.sceneSeq() == action.sceneSeq() ? slot.cooldownMs() : 0;
                if (cooldownMs > 0) scene.gate().startCooldown(slot.slot(), now, TimeUnit.MILLISECONDS.toNanos(cooldownMs));
                answer(player, actionSeq, ActionResultCode.OK, EncodedComponent.NONE, cooldownMs);
            }
            case ActionResult.Rejected rejected ->
                    answer(player, actionSeq, ActionResultCode.REJECTED, reason(rejected.reasonKey()), 0);
            case null -> answer(player, actionSeq, ActionResultCode.REJECTED, EncodedComponent.NONE, 0);
        }
    }

    private static void answer(Player player, int actionSeq, ActionResultCode code, EncodedComponent reason,
                               int cooldownMs) {
        player.sendPluginMessage(CHANNEL, new S2CActionResult(actionSeq, code, reason, cooldownMs).encode());
    }

    /**
     * @return the plugin's translate key as a component, no reason for a key that is missing or does not fit
     */
    private static EncodedComponent reason(@Nullable String key) {
        if (key == null) return EncodedComponent.NONE;
        try {
            return WidgetEncoder.component(Component.translatable(key));
        } catch (IllegalArgumentException _) {
            return EncodedComponent.NONE;
        }
    }

    private static Target target(C2SAction.Target target) {
        return switch (target) {
            case C2SAction.None _ -> new Target.None();
            case C2SAction.OfEntity entity -> new Target.OfEntity(entity.entityId());
            case C2SAction.OfBlock block -> new Target.OfBlock(block.x(), block.y(), block.z());
        };
    }

    private void refuse(PlayerConnection connection, String cause) {
        refusals.warn("Refusing a play payload: connection_id={} reason={} cause={}",
                connection.connectionId(), "synaxis:protocol-error", cause);
        connection.kick(DisconnectReasons.PROTOCOL_ERROR);
    }
}
