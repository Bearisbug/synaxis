package io.github.bearisbug.synaxis.plugin.play;

/**
 * What a player aimed at when sending an action ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-005}).
 * The client reports it: it is untrusted input, to be checked against the server's own view before
 * anything is done with it ({@code docs/DESIGN-M2.md} {@code ADR-M2-012}).
 */
public sealed interface Target {

    /**
     * Nothing.
     */
    record None() implements Target {
    }

    /**
     * An entity.
     *
     * @param entityId the network id of the entity
     */
    record OfEntity(int entityId) implements Target {
    }

    /**
     * A block.
     *
     * @param x the block x
     * @param y the block y
     * @param z the block z
     */
    record OfBlock(int x, int y, int z) implements Target {
    }
}
