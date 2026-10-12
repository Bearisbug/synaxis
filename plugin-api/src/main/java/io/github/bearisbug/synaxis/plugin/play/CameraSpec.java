package io.github.bearisbug.synaxis.plugin.play;

/**
 * The camera of a scene ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-005}). Version 1 of
 * {@code synaxis:play} has only the vanilla camera; the RTS perspective camera arrives with
 * version 3 as another subtype.
 */
public sealed interface CameraSpec {

    /**
     * The vanilla first and third person camera, wire code 0.
     */
    record Vanilla() implements CameraSpec {
    }
}
