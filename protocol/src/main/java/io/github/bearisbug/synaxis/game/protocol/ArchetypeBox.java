package io.github.bearisbug.synaxis.game.protocol;

import java.util.List;

/**
 * The picking box of one archetype: the half of the archetype template of {@code docs/DESIGN-M2.md}
 * §9 that both ends hold.
 * <p>
 * The rest of the template — speed, radius and {@code view_range} — is the server's alone and stays
 * in the game module. This part cannot be: the client builds the box it ray casts against from the
 * archetype number plus the entity's interpolated position ({@code API-M2-006}), so a box that the
 * two ends disagree on is a unit you can see but cannot click. It lives here, in the jar both ends
 * ship, and the frozen vector {@code vectors/archetype_box.bin} holds the whole table to the bytes
 * it has today — that is the drift protection §24 asks for.
 * <p>
 * It is not a message: no {@code msg_id}, and nothing on the wire ever carries it.
 *
 * @param width  the horizontal side of the box in blocks, {@code > 0}
 * @param height the vertical side of the box in blocks, {@code > 0}
 */
public record ArchetypeBox(float width, float height) {

    /**
     * The table, indexed by archetype number. M2 fields one archetype (§28.3 keeps the rest for
     * M3), so index 0 is the whole of it.
     */
    private static final List<ArchetypeBox> TABLE = List.of(new ArchetypeBox(0.9f, 0.9f));

    /**
     * Checks that neither side is zero: a display entity with a zero culling box means "never cull
     * me" to the client, which is a different thing from a box, and a zero picking box cannot be
     * hit by a ray at all.
     */
    public ArchetypeBox {
        if (!(width > 0)) throw new MalformedMessageException("box width must be > 0: " + width);
        if (!(height > 0)) throw new MalformedMessageException("box height must be > 0: " + height);
    }

    /**
     * The box of one archetype.
     *
     * @param archetype the archetype number
     * @return its box
     * @throws IndexOutOfBoundsException if no archetype carries that number; the client renders an
     *                                   unknown archetype as a placeholder instead of asking
     */
    public static ArchetypeBox of(int archetype) {
        return TABLE.get(archetype);
    }

    /**
     * Writes the whole table, which is what the frozen vector holds: a count and then one pair of
     * floats per archetype, in archetype order.
     *
     * @param buf the writing buffer
     */
    public static void writeTable(MsgBuf buf) {
        buf.writeVarInt(TABLE.size());
        for (final ArchetypeBox box : TABLE) {
            buf.writeFloat(box.width());
            buf.writeFloat(box.height());
        }
    }
}
