package io.github.bearisbug.synaxis.game.playkit;

import java.util.List;

/**
 * Players waiting for a round ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-008}); the skeleton of
 * RUN-077 group R.
 *
 * @param <P> what is queued
 */
public final class LocalQueue<P> {

    public boolean join(P player) {
        return false;
    }

    public boolean leave(P player) {
        return false;
    }

    public int size() {
        return 0;
    }

    public List<P> players() {
        return List.of();
    }

    public List<P> take(int min, int max) {
        return List.of();
    }
}
