package io.github.bearisbug.synaxis.game.protocol;

/**
 * A unit entering this player's interest set ({@code API-M2-006}), sent once to bind {@code unit_id}
 * to the engine's {@code entity_id}.
 * <p>
 * <b>It carries no position and no facing.</b> Those travel on the engine's own entity packets and
 * nowhere else: a second copy in our messages would give the client two time bases, and the picking
 * box would drift away from the rendered model exactly when the unit moves. The client builds its
 * box from the entity's interpolated position plus the archetype's size.
 * <p>
 * Arrival order against the engine's spawn packet is not defined; a client that has not yet bound
 * the pair simply treats the unit as not pickable.
 *
 * @param unitId    the match scoped unit id
 * @param entityId  the engine entity carrying the visuals
 * @param archetype the unit archetype; an unknown one is a placeholder model and a warning, not an
 *                  error
 * @param team      a u8, 0 for neutral
 * @param hp        current health, {@code >= 0}
 * @param maxHp     maximum health, {@code >= 0}
 * @param flags     a u8; bit 0 ({@link #FLAG_COMMANDABLE}) is evaluated per receiving connection,
 *                  every other bit is reserved, written as 0 and ignored when read
 */
public record S2CUnitSpawn(int unitId, int entityId, int archetype, int team,
                           int hp, int maxHp, int flags) implements GameMessage {

    /** The {@code msg_id} of this message on the slice channel. */
    public static final int MSG_ID = 0x81;

    /**
     * {@code flags} bit 0: the receiving player may select and order this unit. Selecting and
     * ordering share one judgement — {@code owner(unit, sender)} of §9 — so this is one bit, not two.
     */
    public static final int FLAG_COMMANDABLE = 0x01;

    /**
     * Checks the ids, the two u8 fields and the health pair.
     */
    public S2CUnitSpawn {
        if (unitId < 1) throw new MalformedMessageException("unit_id must be >= 1: " + unitId);
        if (entityId < 0) throw new MalformedMessageException("entity_id must be >= 0: " + entityId);
        if (archetype < 0) throw new MalformedMessageException("archetype must be >= 0: " + archetype);
        if ((team & ~0xFF) != 0) throw new MalformedMessageException("team is not a u8: " + team);
        if (hp < 0) throw new MalformedMessageException("hp must be >= 0: " + hp);
        if (maxHp < 0) throw new MalformedMessageException("max_hp must be >= 0: " + maxHp);
        if ((flags & ~0xFF) != 0) throw new MalformedMessageException("flags is not a u8: " + flags);
    }

    /**
     * Reads the body, with {@code msg_id} already taken by {@link GameMessages#read(MsgBuf)}.
     *
     * @param buf the reading buffer
     * @return the decoded message
     */
    public static S2CUnitSpawn read(MsgBuf buf) {
        return new S2CUnitSpawn(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readUnsignedByte(),
                buf.readVarInt(), buf.readVarInt(), buf.readUnsignedByte());
    }

    @Override
    public int msgId() {
        return MSG_ID;
    }

    @Override
    public void write(MsgBuf buf) {
        buf.writeVarInt(MSG_ID);
        buf.writeVarInt(unitId);
        buf.writeVarInt(entityId);
        buf.writeVarInt(archetype);
        buf.writeUnsignedByte(team);
        buf.writeVarInt(hp);
        buf.writeVarInt(maxHp);
        buf.writeUnsignedByte(flags);
    }
}
