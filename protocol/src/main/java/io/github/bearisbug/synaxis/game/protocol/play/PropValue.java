package io.github.bearisbug.synaxis.game.protocol.play;

import io.github.bearisbug.synaxis.game.protocol.MalformedMessageException;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * The value of one widget prop: the closed set of nine value types of {@code API-PLAY-003}.
 * <p>
 * The type decides the length of the value, which is what lets a client skip a {@code prop_id} it does not
 * know; a type it does not know cannot be skipped and is malformed. All nine are defined in version 1, and a
 * new type raises {@link PlayProtocol#version()}.
 */
public sealed interface PropValue {

    /** Value type 1, a VarInt. */
    int TYPE_VAR_INT = 1;
    /** Value type 2, a VarLong. */
    int TYPE_VAR_LONG = 2;
    /** Value type 3, a finite f32. */
    int TYPE_F32 = 3;
    /** Value type 4, a string of at most {@value Str#MAX_BYTES} bytes. */
    int TYPE_STRING = 4;
    /** Value type 5, a component. */
    int TYPE_COMPONENT = 5;
    /** Value type 6, a bool u8. */
    int TYPE_BOOL = 6;
    /** Value type 7, three finite f32. */
    int TYPE_VEC3F = 7;
    /** Value type 8, scoreboard or panel rows. */
    int TYPE_ROWS = 8;
    /** Value type 9, panel buttons. */
    int TYPE_BUTTONS = 9;

    /**
     * The value type this value is encoded as.
     *
     * @return the type id, 1 to 9
     */
    int type();

    /**
     * Writes the value alone; the prop writes its id and the type in front of it.
     *
     * @param buf the writing buffer
     */
    void write(MsgBuf buf);

    /**
     * Reads a value of the given type.
     *
     * @param type the value type read in front of it
     * @param buf  the reading buffer
     * @return the value
     * @throws MalformedMessageException if the type is not one of the nine or the value cannot be decoded
     */
    static PropValue read(int type, MsgBuf buf) {
        return switch (type) {
            case TYPE_VAR_INT -> new VarInt(buf.readVarInt());
            case TYPE_VAR_LONG -> new VarLong(buf.readVarLong());
            case TYPE_F32 -> new F32(buf.readFloat());
            case TYPE_STRING -> new Str(buf.readString(Str.MAX_BYTES));
            case TYPE_COMPONENT -> new Component(EncodedComponent.read(buf));
            case TYPE_BOOL -> new Bool(PlayFields.readBool(buf, "bool"));
            case TYPE_VEC3F -> new Vec3f(buf.readFloat(), buf.readFloat(), buf.readFloat());
            case TYPE_ROWS -> Rows.read(buf);
            case TYPE_BUTTONS -> Buttons.read(buf);
            default -> throw new MalformedMessageException("unknown value type: " + type);
        };
    }

    /**
     * Value type 1.
     *
     * @param value any {@code int}
     */
    record VarInt(int value) implements PropValue {
        @Override
        public int type() {
            return TYPE_VAR_INT;
        }

        @Override
        public void write(MsgBuf buf) {
            buf.writeVarInt(value);
        }
    }

    /**
     * Value type 2.
     *
     * @param value any {@code long}
     */
    record VarLong(long value) implements PropValue {
        @Override
        public int type() {
            return TYPE_VAR_LONG;
        }

        @Override
        public void write(MsgBuf buf) {
            buf.writeVarLong(value);
        }
    }

    /**
     * Value type 3.
     *
     * @param value a finite value
     */
    record F32(float value) implements PropValue {
        /**
         * Checks that the value is finite.
         */
        public F32 {
            if (!Float.isFinite(value)) throw new MalformedMessageException("f32 value is not finite: " + value);
        }

        @Override
        public int type() {
            return TYPE_F32;
        }

        @Override
        public void write(MsgBuf buf) {
            buf.writeFloat(value);
        }
    }

    /**
     * Value type 4.
     *
     * @param value at most {@value #MAX_BYTES} bytes of UTF-8
     */
    record Str(String value) implements PropValue {
        /** The cap on the encoded string. */
        public static final int MAX_BYTES = 64;

        /**
         * Checks the cap.
         */
        public Str {
            PlayFields.checkString("string value", value, 0, MAX_BYTES);
        }

        @Override
        public int type() {
            return TYPE_STRING;
        }

        @Override
        public void write(MsgBuf buf) {
            buf.writeString(value);
        }
    }

    /**
     * Value type 5.
     *
     * @param value the component
     */
    record Component(EncodedComponent value) implements PropValue {
        /**
         * Checks that a component is present.
         */
        public Component {
            if (value == null) throw new MalformedMessageException("component value is missing");
        }

        @Override
        public int type() {
            return TYPE_COMPONENT;
        }

        @Override
        public void write(MsgBuf buf) {
            value.write(buf);
        }
    }

    /**
     * Value type 6, encoded as a u8 that is 0 or 1.
     *
     * @param value the flag
     */
    record Bool(boolean value) implements PropValue {
        @Override
        public int type() {
            return TYPE_BOOL;
        }

        @Override
        public void write(MsgBuf buf) {
            buf.writeUnsignedByte(value ? 1 : 0);
        }
    }

    /**
     * Value type 7.
     *
     * @param x a finite value
     * @param y a finite value
     * @param z a finite value
     */
    record Vec3f(float x, float y, float z) implements PropValue {
        /**
         * Checks that all three are finite.
         */
        public Vec3f {
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                throw new MalformedMessageException("vec3f value is not finite: " + x + ", " + y + ", " + z);
            }
        }

        @Override
        public int type() {
            return TYPE_VEC3F;
        }

        @Override
        public void write(MsgBuf buf) {
            buf.writeFloat(x);
            buf.writeFloat(y);
            buf.writeFloat(z);
        }
    }

    /**
     * Value type 8: the rows of a scoreboard or a panel.
     *
     * @param rows at most {@value #MAX_ROWS} rows; a panel takes at most {@value Widget#MAX_PANEL_ROWS}
     */
    record Rows(List<Row> rows) implements PropValue {
        /** The cap on the row count of one value, the most any version 1 kind takes. */
        public static final int MAX_ROWS = 16;

        /**
         * Copies the rows and checks the cap.
         */
        public Rows {
            rows = List.copyOf(rows);
            if (rows.size() > MAX_ROWS) {
                throw new MalformedMessageException("%d rows are over the cap of %d".formatted(rows.size(), MAX_ROWS));
            }
        }

        static Rows read(MsgBuf buf) {
            final int count = buf.readCount(MAX_ROWS);
            final List<Row> rows = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                rows.add(new Row(EncodedComponent.read(buf), buf.readString(Row.MAX_VALUE_BYTES),
                        PlayFields.readBool(buf, "highlight")));
            }
            return new Rows(rows);
        }

        @Override
        public int type() {
            return TYPE_ROWS;
        }

        @Override
        public void write(MsgBuf buf) {
            buf.writeVarInt(rows.size());
            for (final Row row : rows) {
                row.label().write(buf);
                buf.writeString(row.value());
                buf.writeUnsignedByte(row.highlight() ? 1 : 0);
            }
        }
    }

    /**
     * Value type 9: the buttons of a panel.
     *
     * @param buttons at most {@value #MAX_BUTTONS} buttons
     */
    record Buttons(List<Button> buttons) implements PropValue {
        /** The cap on the button count. */
        public static final int MAX_BUTTONS = 4;

        /**
         * Copies the buttons and checks the cap.
         */
        public Buttons {
            buttons = List.copyOf(buttons);
            if (buttons.size() > MAX_BUTTONS) {
                throw new MalformedMessageException(
                        "%d buttons are over the cap of %d".formatted(buttons.size(), MAX_BUTTONS));
            }
        }

        static Buttons read(MsgBuf buf) {
            final int count = buf.readCount(MAX_BUTTONS);
            final List<Button> buttons = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                buttons.add(new Button(buf.readString(PlayProtocol.MAX_ID_BYTES), EncodedComponent.read(buf)));
            }
            return new Buttons(buttons);
        }

        @Override
        public int type() {
            return TYPE_BUTTONS;
        }

        @Override
        public void write(MsgBuf buf) {
            buf.writeVarInt(buttons.size());
            for (final Button button : buttons) {
                buf.writeString(button.actionId());
                button.label().write(buf);
            }
        }
    }

    /**
     * One row of {@link Rows}.
     *
     * @param label     the name column
     * @param value     the value column, at most {@value #MAX_VALUE_BYTES} bytes
     * @param highlight whether the row is drawn highlighted, typically the viewer's own
     */
    record Row(EncodedComponent label, String value, boolean highlight) {
        /** The cap on the encoded value column. */
        public static final int MAX_VALUE_BYTES = 32;

        /**
         * Checks the label and the cap.
         */
        public Row {
            if (label == null) throw new MalformedMessageException("row label is missing");
            PlayFields.checkString("row value", value, 0, MAX_VALUE_BYTES);
        }
    }

    /**
     * One button of {@link Buttons}: clicking it sends a {@link C2SAction} with its action id.
     *
     * @param actionId the action, 1 to {@value PlayProtocol#MAX_ID_BYTES} bytes
     * @param label    the button text
     */
    record Button(String actionId, EncodedComponent label) {
        /**
         * Checks the action id and the label.
         */
        public Button {
            PlayFields.checkString("button action_id", actionId, 1, PlayProtocol.MAX_ID_BYTES);
            if (label == null) throw new MalformedMessageException("button label is missing");
        }
    }
}
