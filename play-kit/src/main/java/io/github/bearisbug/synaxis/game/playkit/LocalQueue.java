package io.github.bearisbug.synaxis.game.playkit;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Players waiting for a round, in the order they joined ({@code docs/DESIGN-PLAY.md}
 * {@code API-PLAY-008}). A player is in the queue at most once.
 * <p>
 * Every method takes the queue's lock, so any thread may call it: a game joins and leaves on the
 * tick thread of the player who pressed the key and takes a round's players in the serial part.
 *
 * @param <P> what is queued, a {@code Player} in a game
 */
public final class LocalQueue<P> {

    private final Set<P> queued = new LinkedHashSet<>();

    /**
     * Puts a player at the end of the queue.
     *
     * @param player the player
     * @return true if the player joined, false if it was in the queue already
     */
    public synchronized boolean join(P player) {
        return queued.add(player);
    }

    /**
     * Takes a player out of the queue.
     *
     * @param player the player
     * @return true if the player left, false if it was not in the queue
     */
    public synchronized boolean leave(P player) {
        return queued.remove(player);
    }

    /**
     * How many players wait.
     *
     * @return the size of the queue
     */
    public synchronized int size() {
        return queued.size();
    }

    /**
     * The players waiting, in the order they joined.
     *
     * @return a snapshot of the queue
     */
    public synchronized List<P> players() {
        return List.copyOf(queued);
    }

    /**
     * Takes the players of one round out of the queue, the first to join first.
     *
     * @param min the fewest players a round takes
     * @param max the most players a round takes
     * @return the players taken: none if fewer than {@code min} wait, in which case the queue is left
     *         as it was, otherwise up to {@code max}
     * @throws IllegalArgumentException if {@code min} is negative or {@code max} is below it
     */
    public synchronized List<P> take(int min, int max) {
        if (min < 0 || max < min)
            throw new IllegalArgumentException("a round takes from min to max players with 0 <= min <= max, not min="
                    + min + " max=" + max);
        if (queued.size() < min) return List.of();
        final List<P> taken = new ArrayList<>(Math.min(max, queued.size()));
        final Iterator<P> waiting = queued.iterator();
        while (taken.size() < max && waiting.hasNext()) {
            taken.add(waiting.next());
            waiting.remove();
        }
        return List.copyOf(taken);
    }
}
