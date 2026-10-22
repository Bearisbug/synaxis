package io.github.bearisbug.synaxis.protocol.play;

import io.github.bearisbug.synaxis.protocol.MalformedMessageException;
import io.github.bearisbug.synaxis.protocol.MsgBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * The scene a connection is in now ({@code API-PLAY-002}): its id, title, camera, cursor and action table.
 * <p>
 * It replaces the scene header and the action table only. The widgets of the scene owner are cleared by the
 * {@code CLEAR_OWNER} of the {@link S2CHud} the host sends right after it in the same tick (§6.4.3).
 *
 * @param sceneSeq the scene's number on this connection, from 1
 * @param sceneId  {@code <plugin_id>:<name>}, 1 to {@value PlayProtocol#MAX_ID_BYTES} bytes
 * @param title    the title
 * @param camera   the camera profile, a u8 kept as received: version 1 knows {@link #CAMERA_VANILLA} only and
 *                 the client falls back to it for any other value
 * @param cursor   the cursor mode, a u8 kept as received: version 1 knows {@link #CURSOR_LOCKED} only and the
 *                 client falls back to it for any other value
 * @param actions  the action table, at most {@value #MAX_ACTIONS} entries on distinct slots
 */
public record S2CScene(int sceneSeq, String sceneId, EncodedComponent title, int camera, int cursor,
                       List<Action> actions) implements PlayMessage {

    /** The {@code msg_id} of this message on {@code synaxis:play}. */
    public static final int MSG_ID = 0x81;

    /** The cap on the action table, one entry per action key. */
    public static final int MAX_ACTIONS = 6;

    /** Camera profile 0, the vanilla camera. */
    public static final int CAMERA_VANILLA = 0;

    /** Cursor mode 0, the cursor locked to the view. */
    public static final int CURSOR_LOCKED = 0;

    /**
     * One entry of the action table.
     *
     * @param slot       the action key, 0 to {@value #MAX_ACTIONS} minus one
     * @param actionId   {@code <plugin_id>:<name>}, 1 to {@value PlayProtocol#MAX_ID_BYTES} bytes
     * @param label      what the key does
     * @param cooldownMs the cooldown the client starts locally on a press, 0 to
     *                   {@value PlayProtocol#MAX_COOLDOWN_MS}
     */
    public record Action(int slot, String actionId, EncodedComponent label, int cooldownMs) {
        /**
         * Checks the slot, the action id, the label and the cooldown.
         */
        public Action {
            if (slot < 0 || slot >= MAX_ACTIONS) {
                throw new MalformedMessageException("action slot %d is outside [0, %d]".formatted(slot, MAX_ACTIONS - 1));
            }
            PlayFields.checkString("action_id", actionId, 1, PlayProtocol.MAX_ID_BYTES);
            if (label == null) throw new MalformedMessageException("action label is missing");
            PlayFields.checkCooldown(cooldownMs);
        }
    }

    /**
     * Checks every field and copies the action table.
     */
    public S2CScene {
        if (sceneSeq < 1) throw new MalformedMessageException("scene_seq must be >= 1: " + sceneSeq);
        PlayFields.checkString("scene_id", sceneId, 1, PlayProtocol.MAX_ID_BYTES);
        if (title == null) throw new MalformedMessageException("title is missing");
        PlayFields.checkU8("camera", camera);
        PlayFields.checkU8("cursor", cursor);
        actions = List.copyOf(actions);
        if (actions.size() > MAX_ACTIONS) {
            throw new MalformedMessageException(
                    "%d actions are over the cap of %d".formatted(actions.size(), MAX_ACTIONS));
        }
        int slots = 0;
        for (final Action action : actions) {
            final int bit = 1 << action.slot();
            if ((slots & bit) != 0) throw new MalformedMessageException("action slot " + action.slot() + " is taken twice");
            slots |= bit;
        }
    }

    /**
     * Reads the body, with {@code msg_id} already taken by {@link PlayMessages#read(MsgBuf)}.
     *
     * @param buf the reading buffer
     * @return the decoded message
     */
    public static S2CScene read(MsgBuf buf) {
        final int sceneSeq = buf.readVarInt();
        final String sceneId = buf.readString(PlayProtocol.MAX_ID_BYTES);
        final EncodedComponent title = EncodedComponent.read(buf);
        final int camera = buf.readUnsignedByte();
        final int cursor = buf.readUnsignedByte();
        final int count = buf.readCount(MAX_ACTIONS);
        final List<Action> actions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            actions.add(new Action(buf.readUnsignedByte(), buf.readString(PlayProtocol.MAX_ID_BYTES),
                    EncodedComponent.read(buf), buf.readVarInt()));
        }
        return new S2CScene(sceneSeq, sceneId, title, camera, cursor, actions);
    }

    @Override
    public int msgId() {
        return MSG_ID;
    }

    @Override
    public void write(MsgBuf buf) {
        buf.writeVarInt(MSG_ID);
        buf.writeVarInt(sceneSeq);
        buf.writeString(sceneId);
        title.write(buf);
        buf.writeUnsignedByte(camera);
        buf.writeUnsignedByte(cursor);
        buf.writeVarInt(actions.size());
        for (final Action action : actions) {
            buf.writeUnsignedByte(action.slot());
            buf.writeString(action.actionId());
            action.label().write(buf);
            buf.writeVarInt(action.cooldownMs());
        }
    }
}
