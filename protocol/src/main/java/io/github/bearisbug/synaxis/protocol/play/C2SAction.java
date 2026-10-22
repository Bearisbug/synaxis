package io.github.bearisbug.synaxis.protocol.play;

import io.github.bearisbug.synaxis.protocol.MalformedMessageException;
import io.github.bearisbug.synaxis.protocol.MsgBuf;

/**
 * An action key press or a panel button click ({@code API-PLAY-002}); the host answers every one with exactly
 * one {@link S2CActionResult}.
 *
 * @param sceneSeq  the scene the client was in when it sent this, 0 before its first scene; the host drops
 *                  an action of an earlier scene as stale
 * @param actionSeq the client's number for this action, rising per connection
 * @param actionId  the action, 1 to {@value PlayProtocol#MAX_ID_BYTES} bytes
 * @param target    what the action aims at
 */
public record C2SAction(int sceneSeq, int actionSeq, String actionId, Target target) implements PlayMessage {

    /** The {@code msg_id} of this message on {@code synaxis:play}. */
    public static final int MSG_ID = 0x02;
    /** The {@code target} byte of {@link None}. */
    public static final int TARGET_NONE = 0;
    /** The {@code target} byte of {@link OfEntity}. */
    public static final int TARGET_ENTITY = 1;
    /** The {@code target} byte of {@link OfBlock}. */
    public static final int TARGET_BLOCK = 2;

    /**
     * What an action aims at, as the client saw it: {@code target u8} and its payload. The host checks it as
     * untrusted input.
     */
    public sealed interface Target permits None, OfEntity, OfBlock {
        /**
         * The {@code target} byte.
         *
         * @return {@link #TARGET_NONE}, {@link #TARGET_ENTITY} or {@link #TARGET_BLOCK}
         */
        int code();
    }

    /**
     * Target 0: nothing.
     */
    public record None() implements Target {
        @Override
        public int code() {
            return TARGET_NONE;
        }
    }

    /**
     * Target 1: an entity.
     *
     * @param entityId the entity id the client hit
     */
    public record OfEntity(int entityId) implements Target {
        @Override
        public int code() {
            return TARGET_ENTITY;
        }
    }

    /**
     * Target 2: a block.
     *
     * @param x the block X
     * @param y the block Y
     * @param z the block Z
     */
    public record OfBlock(int x, int y, int z) implements Target {
        @Override
        public int code() {
            return TARGET_BLOCK;
        }
    }

    /**
     * Checks every field.
     */
    public C2SAction {
        if (sceneSeq < 0) throw new MalformedMessageException("scene_seq must be >= 0: " + sceneSeq);
        if (actionSeq < 0) throw new MalformedMessageException("action_seq must be >= 0: " + actionSeq);
        PlayFields.checkString("action_id", actionId, 1, PlayProtocol.MAX_ID_BYTES);
        if (target == null) throw new MalformedMessageException("target is missing");
    }

    /**
     * Reads the body, with {@code msg_id} already taken by {@link PlayMessages#read(MsgBuf)}.
     *
     * @param buf the reading buffer
     * @return the decoded message
     */
    public static C2SAction read(MsgBuf buf) {
        final int sceneSeq = buf.readVarInt();
        final int actionSeq = buf.readVarInt();
        final String actionId = buf.readString(PlayProtocol.MAX_ID_BYTES);
        final int code = buf.readUnsignedByte();
        final Target target = switch (code) {
            case TARGET_NONE -> new None();
            case TARGET_ENTITY -> new OfEntity(buf.readVarInt());
            case TARGET_BLOCK -> new OfBlock(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
            default -> throw new MalformedMessageException("unknown target: " + code);
        };
        return new C2SAction(sceneSeq, actionSeq, actionId, target);
    }

    @Override
    public int msgId() {
        return MSG_ID;
    }

    @Override
    public void write(MsgBuf buf) {
        buf.writeVarInt(MSG_ID);
        buf.writeVarInt(sceneSeq);
        buf.writeVarInt(actionSeq);
        buf.writeString(actionId);
        buf.writeUnsignedByte(target.code());
        switch (target) {
            case None _ -> {
            }
            case OfEntity entity -> buf.writeVarInt(entity.entityId());
            case OfBlock block -> {
                buf.writeVarInt(block.x());
                buf.writeVarInt(block.y());
                buf.writeVarInt(block.z());
            }
        }
    }
}
