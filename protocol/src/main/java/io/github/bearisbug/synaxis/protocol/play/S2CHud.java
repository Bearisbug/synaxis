package io.github.bearisbug.synaxis.protocol.play;

import io.github.bearisbug.synaxis.protocol.MalformedMessageException;
import io.github.bearisbug.synaxis.protocol.MsgBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * One batch of HUD changes ({@code API-PLAY-002}): the host sends one per player per tick and splits it on
 * op boundaries, so that each message keeps within {@value #MAX_OPS} ops and
 * {@value PlayProtocol#MAX_MESSAGE_BYTES} bytes.
 * <p>
 * Every op names the widget by {@code (owner, widget_id)}: the host fills in the calling plugin as the owner,
 * so plugins cannot overwrite each other's widgets (§6.4.3).
 *
 * @param ops the changes in the order the client applies them, at most {@value #MAX_OPS}
 */
public record S2CHud(List<Op> ops) implements PlayMessage {

    /** The {@code msg_id} of this message on {@code synaxis:play}. */
    public static final int MSG_ID = 0x82;

    /** The cap on the op count of one message. */
    public static final int MAX_OPS = 32;

    /** The cap on an encoded {@code widget_id}. */
    public static final int MAX_WIDGET_ID_BYTES = 32;

    /** The {@code op} of {@link SetWidget}. */
    public static final int OP_SET = 1;
    /** The {@code op} of {@link RemoveWidget}. */
    public static final int OP_REMOVE = 2;
    /** The {@code op} of {@link ClearOwner}. */
    public static final int OP_CLEAR_OWNER = 3;

    /**
     * One change: {@code op u8, owner string, widget_id string} and, for a set, the widget.
     */
    public sealed interface Op permits SetWidget, RemoveWidget, ClearOwner {
        /**
         * The {@code op} byte.
         *
         * @return {@link #OP_SET}, {@link #OP_REMOVE} or {@link #OP_CLEAR_OWNER}
         */
        int op();

        /**
         * The plugin the widget belongs to.
         *
         * @return the plugin id, 1 to {@value PlayProtocol#MAX_ID_BYTES} bytes
         */
        String owner();

        /**
         * The widget within the owner.
         *
         * @return the widget id; empty for {@link ClearOwner}
         */
        String widgetId();
    }

    /**
     * Adds a widget or replaces it whole; there are no field level updates.
     *
     * @param owner    the plugin id
     * @param widgetId the widget id, 1 to {@value #MAX_WIDGET_ID_BYTES} bytes
     * @param widget   the widget
     */
    public record SetWidget(String owner, String widgetId, Widget widget) implements Op {
        /**
         * Checks the owner, the widget id and the widget.
         */
        public SetWidget {
            checkOwner(owner);
            checkWidgetId(widgetId);
            if (widget == null) throw new MalformedMessageException("widget of a SET is missing");
        }

        @Override
        public int op() {
            return OP_SET;
        }
    }

    /**
     * Removes one widget.
     *
     * @param owner    the plugin id
     * @param widgetId the widget id, 1 to {@value #MAX_WIDGET_ID_BYTES} bytes
     */
    public record RemoveWidget(String owner, String widgetId) implements Op {
        /**
         * Checks the owner and the widget id.
         */
        public RemoveWidget {
            checkOwner(owner);
            checkWidgetId(widgetId);
        }

        @Override
        public int op() {
            return OP_REMOVE;
        }
    }

    /**
     * Removes every widget of one owner; the host sends it when the scene owner enters a new scene. Its
     * {@code widget_id} is written as the empty string, and a non empty one is malformed.
     *
     * @param owner the plugin id
     */
    public record ClearOwner(String owner) implements Op {
        /**
         * Checks the owner.
         */
        public ClearOwner {
            checkOwner(owner);
        }

        @Override
        public int op() {
            return OP_CLEAR_OWNER;
        }

        @Override
        public String widgetId() {
            return "";
        }
    }

    /**
     * Copies the ops and checks the cap.
     */
    public S2CHud {
        ops = List.copyOf(ops);
        if (ops.size() > MAX_OPS) {
            throw new MalformedMessageException("%d ops are over the cap of %d".formatted(ops.size(), MAX_OPS));
        }
    }

    /**
     * Reads the body, with {@code msg_id} already taken by {@link PlayMessages#read(MsgBuf)}.
     *
     * @param buf the reading buffer
     * @return the decoded message
     */
    public static S2CHud read(MsgBuf buf) {
        final int count = buf.readCount(MAX_OPS);
        final List<Op> ops = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final int op = buf.readUnsignedByte();
            final String owner = buf.readString(PlayProtocol.MAX_ID_BYTES);
            final String widgetId = buf.readString(MAX_WIDGET_ID_BYTES);
            ops.add(switch (op) {
                case OP_SET -> new SetWidget(owner, widgetId, Widget.read(buf));
                case OP_REMOVE -> new RemoveWidget(owner, widgetId);
                case OP_CLEAR_OWNER -> {
                    if (!widgetId.isEmpty()) {
                        throw new MalformedMessageException("CLEAR_OWNER carries widget_id " + widgetId);
                    }
                    yield new ClearOwner(owner);
                }
                default -> throw new MalformedMessageException("unknown op: " + op);
            });
        }
        return new S2CHud(ops);
    }

    @Override
    public int msgId() {
        return MSG_ID;
    }

    @Override
    public void write(MsgBuf buf) {
        buf.writeVarInt(MSG_ID);
        buf.writeVarInt(ops.size());
        for (final Op op : ops) {
            buf.writeUnsignedByte(op.op());
            buf.writeString(op.owner());
            buf.writeString(op.widgetId());
            if (op instanceof SetWidget set) set.widget().write(buf);
        }
    }

    private static void checkOwner(String owner) {
        PlayFields.checkString("owner", owner, 1, PlayProtocol.MAX_ID_BYTES);
    }

    private static void checkWidgetId(String widgetId) {
        PlayFields.checkString("widget_id", widgetId, 1, MAX_WIDGET_ID_BYTES);
    }
}
