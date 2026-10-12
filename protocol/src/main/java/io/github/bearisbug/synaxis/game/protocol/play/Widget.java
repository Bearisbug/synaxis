package io.github.bearisbug.synaxis.game.protocol.play;

import io.github.bearisbug.synaxis.game.protocol.MalformedMessageException;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One HUD widget as {@code API-PLAY-003} encodes it: {@code kind u8, slot u8, order varint} and up to
 * {@value #MAX_PROPS} props, each a {@code prop_id varint}, a value type and a {@link PropValue}.
 * <p>
 * The record keeps every prop it was given, in order, so that it re-encodes byte for byte. For the four kinds
 * of version 1 ({@link #TEXT}, {@link #TIMER}, {@link #SCOREBOARD}, {@link #PANEL}) a prop the table declares
 * must carry its declared type; a {@code prop_id} the table does not declare is kept and left for the client
 * to skip, because a newer sender adds props without raising the channel version (§6.4.1). A kind this version
 * does not know, the four of version 2 included, is kept whole and dropped by the client with a warning.
 *
 * @param kind  the widget kind, a u8; the client draws the four of version 1 and drops the rest
 * @param slot  the layout slot, {@link #TOP_LEFT} to {@link #CENTER}
 * @param order the position within the slot, ascending
 * @param props the props, at most {@value #MAX_PROPS}
 */
public record Widget(int kind, int slot, int order, List<Prop> props) {

    /** Kind 1: one line of text, optionally with a time to live. */
    public static final int TEXT = 1;
    /** Kind 2: a label and a remaining time the client counts down itself. */
    public static final int TIMER = 2;
    /** Kind 4: a title and up to {@value PropValue.Rows#MAX_ROWS} rows. */
    public static final int SCOREBOARD = 4;
    /** Kind 6: a centred panel opened as a screen, with up to {@value #MAX_PANEL_ROWS} rows and four buttons. */
    public static final int PANEL = 6;

    /** Slot 0. */
    public static final int TOP_LEFT = 0;
    /** Slot 1, which makes room for the vanilla boss bars. */
    public static final int TOP_CENTER = 1;
    /** Slot 2. */
    public static final int TOP_RIGHT = 2;
    /** Slot 3. */
    public static final int LEFT = 3;
    /** Slot 4. */
    public static final int RIGHT = 4;
    /** Slot 5, which makes room for the vanilla action bar and hotbar. */
    public static final int BOTTOM = 5;
    /** Slot 6, the only slot of a {@link #PANEL}. */
    public static final int CENTER = 6;

    /** Prop 1, a component: the text of a TEXT, the label of a TIMER, the title of a SCOREBOARD or PANEL. */
    public static final int PROP_TEXT = 1;
    /** Prop 2 of a TIMER, a VarInt: the remaining milliseconds when the widget was sent. */
    public static final int PROP_REMAINING_MS = 2;
    /** Prop 3 of a TIMER, a bool: whether the client counts down. */
    public static final int PROP_RUNNING = 3;
    /** Prop 7 of a SCOREBOARD or PANEL, rows. */
    public static final int PROP_ROWS = 7;
    /** Prop 10 of a PANEL, buttons. */
    public static final int PROP_BUTTONS = 10;
    /** Prop 13 of a TEXT, a VarInt: how long it shows in milliseconds, 0 for as long as it is set. */
    public static final int PROP_TTL_MS = 13;

    /** The cap on the prop count. */
    public static final int MAX_PROPS = 16;
    /** The cap on the rows of a {@link #PANEL}. */
    public static final int MAX_PANEL_ROWS = 12;

    /** What {@link #declaredType(int, int)} answers for a prop the table does not declare. */
    private static final int UNDECLARED = 0;

    /**
     * One prop.
     *
     * @param id    the {@code prop_id}
     * @param value the value, which also gives the value type
     */
    public record Prop(int id, PropValue value) {
        /**
         * Checks that a value is present.
         */
        public Prop {
            if (value == null) throw new MalformedMessageException("value of prop " + id + " is missing");
        }
    }

    /**
     * Copies the props and checks the kind, the slot, the prop count, the declared types and the panel's own
     * limits.
     */
    public Widget {
        PlayFields.checkU8("kind", kind);
        if (slot < TOP_LEFT || slot > CENTER) {
            throw new MalformedMessageException("slot %d is outside [%d, %d]".formatted(slot, TOP_LEFT, CENTER));
        }
        props = List.copyOf(props);
        if (props.size() > MAX_PROPS) {
            throw new MalformedMessageException("%d props are over the cap of %d".formatted(props.size(), MAX_PROPS));
        }
        for (final Prop prop : props) {
            final int declared = declaredType(kind, prop.id());
            // Skipped by its type: a newer sender adds props without a version bump, and a kind this version
            // does not know declares none (API-PLAY-003).
            if (declared == UNDECLARED) continue;
            if (prop.value().type() != declared) {
                throw new MalformedMessageException("prop %d of kind %d carries value type %d, not %d"
                        .formatted(prop.id(), kind, prop.value().type(), declared));
            }
        }
        if (kind == PANEL) {
            if (slot != CENTER) throw new MalformedMessageException("a PANEL is on slot " + slot + ", not CENTER");
            for (final Prop prop : props) {
                if (prop.id() == PROP_ROWS && prop.value() instanceof PropValue.Rows rows
                        && rows.rows().size() > MAX_PANEL_ROWS) {
                    throw new MalformedMessageException("a PANEL has %d rows, over the cap of %d"
                            .formatted(rows.rows().size(), MAX_PANEL_ROWS));
                }
            }
        }
    }

    /**
     * A {@link #TEXT}.
     *
     * @param slot  the slot
     * @param order the position within the slot
     * @param text  the line
     * @param ttlMs how long it shows in milliseconds, 0 for as long as it is set
     * @return the widget
     */
    public static Widget text(int slot, int order, EncodedComponent text, int ttlMs) {
        return new Widget(TEXT, slot, order, List.of(
                new Prop(PROP_TEXT, new PropValue.Component(text)),
                new Prop(PROP_TTL_MS, new PropValue.VarInt(ttlMs))));
    }

    /**
     * A {@link #TIMER}.
     *
     * @param slot        the slot
     * @param order       the position within the slot
     * @param label       the label
     * @param remainingMs the remaining milliseconds now
     * @param running     whether the client counts down
     * @return the widget
     */
    public static Widget timer(int slot, int order, EncodedComponent label, int remainingMs, boolean running) {
        return new Widget(TIMER, slot, order, List.of(
                new Prop(PROP_TEXT, new PropValue.Component(label)),
                new Prop(PROP_REMAINING_MS, new PropValue.VarInt(remainingMs)),
                new Prop(PROP_RUNNING, new PropValue.Bool(running))));
    }

    /**
     * A {@link #SCOREBOARD}.
     *
     * @param slot  the slot
     * @param order the position within the slot
     * @param title the title
     * @param rows  up to {@value PropValue.Rows#MAX_ROWS} rows
     * @return the widget
     */
    public static Widget scoreboard(int slot, int order, EncodedComponent title, List<PropValue.Row> rows) {
        return new Widget(SCOREBOARD, slot, order, List.of(
                new Prop(PROP_TEXT, new PropValue.Component(title)),
                new Prop(PROP_ROWS, new PropValue.Rows(rows))));
    }

    /**
     * A {@link #PANEL}, always on {@link #CENTER} with order 0.
     *
     * @param title   the title
     * @param rows    up to {@value #MAX_PANEL_ROWS} rows
     * @param buttons up to {@value PropValue.Buttons#MAX_BUTTONS} buttons
     * @return the widget
     */
    public static Widget panel(EncodedComponent title, List<PropValue.Row> rows, List<PropValue.Button> buttons) {
        return new Widget(PANEL, CENTER, 0, List.of(
                new Prop(PROP_TEXT, new PropValue.Component(title)),
                new Prop(PROP_ROWS, new PropValue.Rows(rows)),
                new Prop(PROP_BUTTONS, new PropValue.Buttons(buttons))));
    }

    /**
     * The first prop with this id.
     *
     * @param id the {@code prop_id}
     * @return its value, or empty when the widget has none
     */
    public Optional<PropValue> prop(int id) {
        for (final Prop prop : props) {
            if (prop.id() == id) return Optional.of(prop.value());
        }
        return Optional.empty();
    }

    /**
     * Reads a widget.
     *
     * @param buf the reading buffer
     * @return the widget
     * @throws MalformedMessageException if the widget cannot be decoded or breaks a rule of the record
     */
    public static Widget read(MsgBuf buf) {
        final int kind = buf.readUnsignedByte();
        final int slot = buf.readUnsignedByte();
        final int order = buf.readVarInt();
        final int count = buf.readCount(MAX_PROPS);
        final List<Prop> props = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final int id = buf.readVarInt();
            props.add(new Prop(id, PropValue.read(buf.readUnsignedByte(), buf)));
        }
        return new Widget(kind, slot, order, props);
    }

    /**
     * Writes the widget.
     *
     * @param buf the writing buffer
     */
    public void write(MsgBuf buf) {
        buf.writeUnsignedByte(kind);
        buf.writeUnsignedByte(slot);
        buf.writeVarInt(order);
        buf.writeVarInt(props.size());
        for (final Prop prop : props) {
            buf.writeVarInt(prop.id());
            buf.writeUnsignedByte(prop.value().type());
            prop.value().write(buf);
        }
    }

    /**
     * The value type the table of {@code API-PLAY-003} declares for a prop of a version 1 kind.
     */
    private static int declaredType(int kind, int propId) {
        return switch (kind) {
            case TEXT -> switch (propId) {
                case PROP_TEXT -> PropValue.TYPE_COMPONENT;
                case PROP_TTL_MS -> PropValue.TYPE_VAR_INT;
                default -> UNDECLARED;
            };
            case TIMER -> switch (propId) {
                case PROP_TEXT -> PropValue.TYPE_COMPONENT;
                case PROP_REMAINING_MS -> PropValue.TYPE_VAR_INT;
                case PROP_RUNNING -> PropValue.TYPE_BOOL;
                default -> UNDECLARED;
            };
            case SCOREBOARD -> switch (propId) {
                case PROP_TEXT -> PropValue.TYPE_COMPONENT;
                case PROP_ROWS -> PropValue.TYPE_ROWS;
                default -> UNDECLARED;
            };
            case PANEL -> switch (propId) {
                case PROP_TEXT -> PropValue.TYPE_COMPONENT;
                case PROP_ROWS -> PropValue.TYPE_ROWS;
                case PROP_BUTTONS -> PropValue.TYPE_BUTTONS;
                default -> UNDECLARED;
            };
            default -> UNDECLARED;
        };
    }
}
