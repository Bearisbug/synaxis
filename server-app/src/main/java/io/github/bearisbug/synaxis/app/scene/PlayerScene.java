package io.github.bearisbug.synaxis.app.scene;

import io.github.bearisbug.synaxis.game.protocol.play.S2CScene;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The scene layer of one connection ({@code ENT-PlayScene} of {@code docs/DESIGN-PLAY.md} §11.2): the scene
 * it is in, its action keys, the widgets every plugin set for it, the limits on its actions and what it is
 * still to be sent. Changed on the player's tick thread and in the serial part of the tick only.
 */
final class PlayerScene {

    private final ActionGate gate = new ActionGate();
    private final Outbox outbox = new Outbox();

    /** Owner, then widget id, then the full action ids of that widget's buttons. */
    private final Map<String, Map<String, Set<String>>> widgets = new HashMap<>();

    private int sceneSeq;
    private @Nullable String sceneId;
    /** The action keys of the current scene, as its {@code S2C_SCENE} carries them. */
    private List<S2CScene.Action> actions = List.of();
    private int readySeq;

    /**
     * The number of the last scene entered.
     *
     * @return its {@code scene_seq}, 0 before the first
     */
    int sceneSeq() {
        return sceneSeq;
    }

    /**
     * The scene the connection is in.
     *
     * @return its full id, null while the connection is in none
     */
    @Nullable String sceneId() {
        return sceneId;
    }

    ActionGate gate() {
        return gate;
    }

    Outbox outbox() {
        return outbox;
    }

    /**
     * Enters a scene: its header replaces the current one, the cooldowns end, the owner's widgets go and a
     * clear of them is queued behind the scene (§6.4.3, §11.2).
     *
     * @param scene the scene, numbered one above the current, with its action keys
     * @param owner the scene owner
     */
    void enter(S2CScene scene, String owner) {
        this.sceneSeq = scene.sceneSeq();
        this.sceneId = scene.sceneId();
        this.actions = scene.actions();
        gate.clearCooldowns();
        widgets.remove(owner);
        outbox.scene(scene);
        outbox.clearOwner(owner);
    }

    /**
     * Leaves the scene, keeping its number: the action keys and their cooldowns go, so do the owner's
     * widgets. Nothing happens while the connection is in no scene.
     *
     * @param owner the scene owner
     */
    void leave(String owner) {
        if (sceneId == null) return;
        sceneId = null;
        actions = List.of();
        gate.clearCooldowns();
        widgets.remove(owner);
        outbox.clearOwner(owner);
    }

    void set(String owner, String widgetId, WidgetEncoder.Encoded encoded) {
        widgets.computeIfAbsent(owner, _ -> new HashMap<>()).put(widgetId, encoded.buttonActions());
        outbox.set(owner, widgetId, encoded.widget());
    }

    void remove(String owner, String widgetId) {
        final Map<String, Set<String>> owned = widgets.get(owner);
        if (owned == null || owned.remove(widgetId) == null) return;
        outbox.remove(owner, widgetId);
    }

    void clear(String owner) {
        final Map<String, Set<String>> owned = widgets.remove(owner);
        if (owned == null || owned.isEmpty()) return;
        outbox.clearOwner(owner);
    }

    /**
     * The action key of the current scene that sends an action.
     *
     * @param actionId a full action id
     * @return the key, null when none sends it
     */
    @Nullable S2CScene.Action slotOf(String actionId) {
        for (final S2CScene.Action action : actions)
            if (action.actionId().equals(actionId)) return action;
        return null;
    }

    /**
     * Whether a panel of this connection, whoever set it, has a button sending an action.
     *
     * @param actionId a full action id
     * @return true when one does
     */
    boolean hasButton(String actionId) {
        for (final Map<String, Set<String>> owned : widgets.values())
            for (final Set<String> buttons : owned.values())
                if (buttons.contains(actionId)) return true;
        return false;
    }

    /**
     * Takes the client's word that a scene is ready, once per scene.
     *
     * @param sceneSeq the {@code scene_seq} the client says is ready
     * @return whether that is the current scene, ready for the first time
     */
    boolean ready(int sceneSeq) {
        if (sceneId == null || sceneSeq != this.sceneSeq || readySeq == sceneSeq) return false;
        readySeq = sceneSeq;
        return true;
    }
}
