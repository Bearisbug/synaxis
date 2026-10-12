package io.github.bearisbug.synaxis.game.client.play;

import io.github.bearisbug.synaxis.game.protocol.play.ActionResultCode;
import io.github.bearisbug.synaxis.game.protocol.play.C2SAction;
import io.github.bearisbug.synaxis.game.protocol.play.EncodedComponent;
import io.github.bearisbug.synaxis.game.protocol.play.PlayMessage;
import io.github.bearisbug.synaxis.game.protocol.play.PropValue;
import io.github.bearisbug.synaxis.game.protocol.play.S2CActionResult;
import io.github.bearisbug.synaxis.game.protocol.play.S2CHud;
import io.github.bearisbug.synaxis.game.protocol.play.S2CScene;
import io.github.bearisbug.synaxis.game.protocol.play.Widget;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client's mirror of its connection's scene layer ({@code docs/DESIGN-PLAY.md} §6.4.3, §6.4.8,
 * {@code ENT-PlayScene}): the scene and its action table from {@code S2C_SCENE}, the widgets of {@code S2C_HUD}
 * addressed by {@code (owner, widget_id)}, the actions this client sent and their results. The four kinds of
 * version 1 are kept; a kind this version does not know is dropped with a warning ({@code API-PLAY-003}).
 * <p>
 * It decides what the action key and a panel button send, keeps the local cooldown of each action key and
 * restarts it from every result (§6.4.4), counts a {@code TIMER} down from the moment its SET arrived, lets a
 * {@code TEXT} go when its time to live is up, and answers each scene with one {@code C2S_SCENE_READY} through
 * its {@link SceneReadyProbe}. What it sends goes to the sender it was given; the texts a result shows go to
 * the notice sink (the vanilla action bar in the mod).
 * <p>
 * One connection's worth: {@link #reset} at {@code ClientPlayConnectionEvents.INIT}. Single threaded, like
 * {@code RtsClient}: the payloads, that event, the client tick, the HUD and the panel all run on the client
 * thread.
 */
public final class PlayClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("synaxis");

    /** What a press inside the local cooldown shows, with the seconds left. */
    static final String COOLDOWN_KEY = "synaxis.action.cooldown";
    /** What a refusal without a reason shows. */
    static final String REJECTED_KEY = "synaxis.action.rejected";

    private static final long NANOS_PER_MS = 1_000_000L;

    /**
     * One entry of the scene's action table.
     *
     * @param slot       the action key, 0 to 5
     * @param actionId   the action the key sends
     * @param label      what the key does
     * @param cooldownMs the cooldown a press starts locally
     */
    public record Action(int slot, String actionId, Component label, int cooldownMs) {
    }

    /**
     * The scene the connection is in.
     *
     * @param seq     its {@code scene_seq}
     * @param id      its scene id, {@code <plugin_id>:<name>}
     * @param title   its title
     * @param actions its action table
     */
    public record Scene(int seq, String id, Component title, List<Action> actions) {

        /**
         * The scene owner: the plugin whose namespace the scene id is in.
         *
         * @return the part of the id before the colon
         */
        public String owner() {
            final int colon = id.indexOf(':');
            return colon < 0 ? id : id.substring(0, colon);
        }

        /**
         * The action on one key.
         *
         * @param slot the action key
         * @return the action, null when the table has none on that key
         */
        public @Nullable Action action(int slot) {
            for (final Action action : actions) {
                if (action.slot() == slot) return action;
            }
            return null;
        }
    }

    /** What one widget shows: one of the four kinds of version 1. */
    public sealed interface Content permits Text, Timer, Scoreboard, Panel {
    }

    /**
     * A {@code TEXT}.
     *
     * @param text  the line
     * @param ttlMs how long it shows from the moment its SET arrived, 0 for as long as it is set
     */
    public record Text(Component text, int ttlMs) implements Content {
    }

    /**
     * A {@code TIMER}.
     *
     * @param label       the label
     * @param remainingMs the time left when its SET was sent
     * @param running     whether the client counts it down
     */
    public record Timer(Component label, int remainingMs, boolean running) implements Content {
    }

    /**
     * A {@code SCOREBOARD}.
     *
     * @param title the title
     * @param rows  the rows, top to bottom
     */
    public record Scoreboard(Component title, List<Row> rows) implements Content {
    }

    /**
     * A {@code PANEL}, which the client opens as a screen.
     *
     * @param title   the title
     * @param rows    the rows, top to bottom
     * @param buttons the buttons, left to right
     */
    public record Panel(Component title, List<Row> rows, List<Button> buttons) implements Content {
    }

    /**
     * One row of a scoreboard or a panel.
     *
     * @param label     the name
     * @param value     the value, shown on the right
     * @param highlight whether the row stands out, the player's own for one
     */
    public record Row(Component label, String value, boolean highlight) {
    }

    /**
     * One button of a panel.
     *
     * @param actionId the action a click sends
     * @param label    the button's text
     */
    public record Button(String actionId, Component label) {
    }

    /**
     * One widget as the mirror holds it.
     *
     * @param owner         the plugin that set it
     * @param widgetId      its id within the owner
     * @param slot          its slot, 0 {@code TOP_LEFT} to 6 {@code CENTER}
     * @param order         its position within the slot
     * @param first         the number of the SET that first put this address in the table, which breaks ties of
     *                      {@code order}
     * @param set           the number of the SET this content came with, rising per connection
     * @param receivedNanos the client clock when that SET arrived
     * @param content       what it shows
     */
    public record Entry(String owner, String widgetId, int slot, int order, long first, long set, long receivedNanos,
                        Content content) {

        /**
         * The widget as {@link WidgetLayout} places it.
         *
         * @param sceneOwner whether the scene owner set it
         * @param width      its width in GUI pixels
         * @param height     its height in GUI pixels
         * @return the box
         */
        public WidgetLayout.Box box(boolean sceneOwner, int width, int height) {
            return new WidgetLayout.Box(slot, sceneOwner, order, first, width, height);
        }
    }

    /**
     * The last {@code S2C_ACTION_RESULT} that arrived.
     *
     * @param actionSeq     the action it answers
     * @param code          the result code as the client shows it
     * @param cooldownMs    the cooldown it carried
     * @param reason        its reason, empty when it had none
     * @param receivedNanos the client clock when it arrived
     */
    public record Result(int actionSeq, ActionResultCode code, int cooldownMs, Component reason, long receivedNanos) {
    }

    /** What a press of an action key did. */
    public enum Press {
        /** It sent the key's action. */
        SENT,
        /** The key's local cooldown held it back. */
        COOLING_DOWN,
        /** No scene, or the scene has no action on that key. */
        NO_ACTION
    }

    private record Address(String owner, String widgetId) {
    }

    /** What an action was sent from: an action key of a scene, or a button of a panel. */
    private record Pending(int slot, int sceneSeq, @Nullable Address panel, long panelSet) {
    }

    private final Consumer<PlayMessage> sender;
    private final Consumer<Component> notices;
    private final LongSupplier clock;
    private final SceneReadyProbe probe;

    private @Nullable Scene scene;
    private final Map<Address, Entry> widgets = new LinkedHashMap<>();
    private final Map<Address, Long> dismissed = new HashMap<>();
    private final boolean[] cooling = new boolean[S2CScene.MAX_ACTIONS];
    private final long[] coolUntil = new long[S2CScene.MAX_ACTIONS];
    private final Map<Integer, Pending> pending = new HashMap<>();
    private @Nullable Address reasonPanel;
    private long reasonSet;
    private @Nullable Component reason;
    private @Nullable Result lastResult;
    private long sets;
    private int actionSeq;
    private int actionsSent;
    private boolean localCooldown = true;

    /**
     * @param sender  where the messages to the server go
     * @param notices where the texts a result shows go
     * @param clock   the client clock in nanoseconds
     */
    public PlayClient(Consumer<PlayMessage> sender, Consumer<Component> notices, LongSupplier clock) {
        this.sender = sender;
        this.notices = notices;
        this.clock = clock;
        this.probe = new SceneReadyProbe(sender);
    }

    /**
     * Applies one message of the server.
     *
     * @param message    the message
     * @param registries the registries of the connection, for the components
     */
    public void receive(PlayMessage message, RegistryAccess registries) {
        switch (message) {
            case S2CScene enter -> {
                scene = new Scene(enter.sceneSeq(), enter.sceneId(), component(enter.title(), registries),
                        enter.actions().stream().map(action -> new Action(action.slot(), action.actionId(),
                                component(action.label(), registries), action.cooldownMs())).toList());
                // A new action table starts with no cooldown (§11.2)
                Arrays.fill(cooling, false);
            }
            case S2CHud hud -> {
                for (final S2CHud.Op op : hud.ops()) apply(op, registries);
            }
            case S2CActionResult result -> result(result, registries);
            default -> {
                // The two upward messages never come down
            }
        }
    }

    private void apply(S2CHud.Op op, RegistryAccess registries) {
        final Address address = new Address(op.owner(), op.widgetId());
        switch (op) {
            case S2CHud.SetWidget set -> {
                final Widget widget = set.widget();
                final Content content = content(widget, registries);
                if (content == null) {
                    forget(address);
                    LOGGER.warn("Dropped a widget of unknown kind {}: owner={} widget_id={}", widget.kind(), set.owner(),
                            set.widgetId());
                    return;
                }
                final Entry old = widgets.get(address);
                final long number = ++sets;
                widgets.put(address, new Entry(set.owner(), set.widgetId(), widget.slot(), widget.order(),
                        old == null ? number : old.first(), number, clock.getAsLong(), content));
            }
            case S2CHud.RemoveWidget _ -> forget(address);
            case S2CHud.ClearOwner clear -> {
                for (final Address key : List.copyOf(widgets.keySet())) {
                    if (key.owner().equals(clear.owner())) forget(key);
                }
            }
        }
    }

    private void forget(Address address) {
        widgets.remove(address);
        dismissed.remove(address);
    }

    private static @Nullable Content content(Widget widget, RegistryAccess registries) {
        // Only TEXT so far
        if (widget.kind() != Widget.TEXT) return null;
        return new Text(component(widget.prop(Widget.PROP_TEXT)
                .map(value -> value instanceof PropValue.Component c ? c.value() : null)
                .orElse(EncodedComponent.NONE), registries), 0);
    }

    private static int varInt(Widget widget, int prop) {
        return widget.prop(prop).map(value -> value instanceof PropValue.VarInt v ? v.value() : 0).orElse(0);
    }

    private static List<Row> rows(Widget widget, RegistryAccess registries) {
        return widget.prop(Widget.PROP_ROWS)
                .map(value -> value instanceof PropValue.Rows rows ? rows.rows() : List.<PropValue.Row>of())
                .orElse(List.of()).stream()
                .map(row -> new Row(component(row.label(), registries), row.value(), row.highlight())).toList();
    }

    private void result(S2CActionResult result, RegistryAccess registries) {
    }

    private void panelResult(Pending sent, ActionResultCode code, @Nullable Component said, int cooldownMs) {
        switch (code) {
            case OK -> {
                final Entry panel = widgets.get(sent.panel());
                if (panel != null && panel.set() == sent.panelSet()) dismissed.put(sent.panel(), panel.set());
            }
            case REJECTED -> showOnPanel(sent, said != null ? said : Component.translatable(REJECTED_KEY));
            case COOLDOWN -> showOnPanel(sent, said != null ? said : cooldownText(cooldownMs));
            default -> {
                // rate-limited, stale scene, unknown action: the panel stays as it is (§13)
            }
        }
    }

    private void showOnPanel(Pending sent, Component text) {
        reasonPanel = sent.panel();
        reasonSet = sent.panelSet();
        reason = text;
    }

    private void cool(int slot, long now, int cooldownMs) {
        cooling[slot] = cooldownMs > 0;
        coolUntil[slot] = now + cooldownMs * NANOS_PER_MS;
    }

    private static Component cooldownText(long cooldownMs) {
        return Component.translatable(COOLDOWN_KEY, String.valueOf(Math.ceilDiv(cooldownMs, 1000L)));
    }

    /** A component the codec cannot read shows as empty text, with one warning, and keeps the connection. */
    private static Component component(EncodedComponent encoded, RegistryAccess registries) {
        try {
            return PlayComponents.decode(encoded, registries);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("A play component could not be decoded, showing empty text: {}", e.getMessage());
            return Component.empty();
        }
    }

    /**
     * Presses an action key: sends the scene's action on that key unless its local cooldown holds it back, which
     * shows the seconds left instead.
     *
     * @param slot the action key, 0 for action key 1
     * @return what the press did
     */
    public Press press(int slot) {
        return Press.NO_ACTION;
    }

    /**
     * Clicks a panel button: sends its action. The result decides what the panel does (§13).
     *
     * @param panel  the panel's widget
     * @param button the button
     */
    public void click(Entry panel, Button button) {
    }

    private void send(String actionId, Pending from) {
        final int seq = ++actionSeq;
        pending.put(seq, from);
        actionsSent++;
        sender.accept(new C2SAction(from.sceneSeq(), seq, actionId, new C2SAction.None()));
    }

    /**
     * Closes a panel on this client only, the way Esc does: it stays in the table and opens again with its next
     * SET.
     *
     * @param panel the panel's widget
     */
    public void dismiss(Entry panel) {
    }

    /**
     * The client tick: drops the {@code TEXT}s whose time is up and lets the probe answer the scene.
     *
     * @param view the client as the probe sees it
     */
    public void tick(SceneReadyProbe.View view) {
    }

    private static boolean expired(Entry entry, long now) {
        return entry.content() instanceof Text text && text.ttlMs() > 0
                && now - entry.receivedNanos() >= text.ttlMs() * NANOS_PER_MS;
    }

    /**
     * The scene the connection is in.
     *
     * @return the scene, null before the connection's first {@code S2C_SCENE}
     */
    public @Nullable Scene scene() {
        return scene;
    }

    /**
     * The widgets that show now, in the order {@link WidgetLayout#ORDER} places them.
     *
     * @return a copy
     */
    public List<Entry> widgets() {
        final long now = clock.getAsLong();
        final String owner = scene == null ? null : scene.owner();
        return widgets.values().stream().filter(entry -> !expired(entry, now))
                .sorted(Comparator.comparing(entry -> entry.box(entry.owner().equals(owner), 0, 0), WidgetLayout.ORDER))
                .toList();
    }

    /**
     * The {@code TEXT}s that show now, in layout order.
     *
     * @return a copy
     */
    public List<Entry> texts() {
        return widgets().stream().filter(entry -> entry.content() instanceof Text).toList();
    }

    /**
     * The panel the client shows: the first in layout order that was not closed locally since its last SET.
     *
     * @return the panel's widget, null when there is none or the connection has no scene
     */
    public @Nullable Entry panel() {
        return null;
    }

    /**
     * Why the server refused the last button clicked on a panel, for the panel to show.
     *
     * @param panel the panel's widget
     * @return the reason, null when nothing was refused on this content of the panel
     */
    public @Nullable Component panelReason(Entry panel) {
        return null;
    }

    /**
     * What a {@code TIMER} shows now: the time left at its SET minus the time since it arrived, never below 0,
     * unchanged while it is not running (§6.4.4).
     *
     * @param timer the timer's widget
     * @return the milliseconds left
     */
    public long remainingMs(Entry timer) {
        throw new UnsupportedOperationException("not yet");
    }

    /**
     * What is left of an action key's local cooldown.
     *
     * @param slot the action key
     * @return the milliseconds left, rounded up, 0 when it is not cooling down
     */
    public long cooldownLeftMs(int slot) {
        return 0;
    }

    /**
     * Turns the local cooldown on or off; on by default. With it off a press is sent whatever the cooldown, so
     * the server's cooldown answers it (§6.7.6 Q4 turns it off through the gametest's hook).
     *
     * @param on whether a press inside the local cooldown is held back
     */
    public void localCooldown(boolean on) {
    }

    /**
     * @return how many actions this connection sent
     */
    public int actionsSent() {
        return 0;
    }

    /**
     * @return the last action result that arrived, null before the first
     */
    public @Nullable Result lastResult() {
        return null;
    }

    /**
     * @return the probe that answers each scene with {@code C2S_SCENE_READY}
     */
    public SceneReadyProbe probe() {
        return probe;
    }

    /** Forgets everything: a new connection starts from nothing (§6.4.3). */
    public void reset() {
        scene = null;
        widgets.clear();
        dismissed.clear();
        Arrays.fill(cooling, false);
        pending.clear();
        reasonPanel = null;
        reason = null;
        lastResult = null;
        sets = 0;
        actionSeq = 0;
        actionsSent = 0;
        probe.reset();
    }
}
