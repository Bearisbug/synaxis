package io.github.bearisbug.synaxis.game.rts;

import io.github.bearisbug.synaxis.coordinate.Pos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Eight way A* over the baked navigation mesh, followed by a string pull (REQ-M2-017).
 * <p>
 * The mesh is generated with the map and never changes, so a search needs no lock and no copy: it
 * reads {@link NavGrid} and writes only into the scratch arrays of this object, which one match
 * owns and only the tick thread touches. The arrays are kept between searches and cleared by a
 * stamp rather than by filling them — a 512×512 map is a quarter of a million columns, and wiping
 * three arrays of that size four times a tick would cost more than the searches.
 * <p>
 * Two budgets of §15 are enforced here; the third ({@code ≤ 4 requests per tick}) belongs to the
 * queue that feeds it. A search that expands more than {@value #MAX_EXPANSIONS} nodes gives up and
 * answers {@code no-path}: a bounded wrong answer the client can act on beats an unbounded tick.
 * The search leans toward the goal by {@link #GOAL_BIAS} so that the budget buys distance rather
 * than breadth; without it a map this open swallows the whole budget within about 150 blocks.
 * <p>
 * Diagonal steps may not cut a corner — both orthogonal neighbours have to be walkable — and the
 * string pull only shortcuts through a corridor wide enough for the archetype's radius, so the
 * smoothed path stays walkable for a unit that is not a point.
 */
final class Pathfinder {

    /** §15: the node expansion budget of one search. */
    static final int MAX_EXPANSIONS = 4_000;

    /**
     * How much the search leans toward the goal, which decides what the node budget reaches.
     * <p>
     * The map is a plain with scattered pillars, so the octile distance is exact almost everywhere
     * and every staircase between two columns costs exactly the same: A* has no reason to prefer
     * one, expands the whole equal cost plateau around the start, and runs out of budget while the
     * goal is still inside the frontier. Leaning on the heuristic is what collapses that plateau.
     * Measured over 160,000 column pairs on both presets, {@value} answers every connected pair,
     * while {@code 1.05} still gives a walkable one up wrong. The price is that a route may cost
     * that many times the shortest one: on this terrain it came out as 1.3% more walking on average
     * and 8% in the worst pair, against a fifth of the nodes expanded.
     */
    private static final float GOAL_BIAS = 1.15f;

    /** The cost of a diagonal step, the orthogonal one being 1. */
    private static final float DIAGONAL_COST = (float) Math.sqrt(2);

    /** How finely a candidate shortcut is sampled, in blocks. */
    private static final double CORRIDOR_STEP = 0.5;

    private static final int[] NEIGHBOUR_X = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] NEIGHBOUR_Z = {0, 0, 1, -1, 1, -1, 1, -1};

    /**
     * The outcome of one search: the waypoints to walk and what it cost to find them, which §20
     * writes into {@code unit_order_issued} as {@code path_nodes} and {@code path_ms}.
     *
     * @param waypoints the smoothed path, ending on the destination; empty when there is none
     * @param nodes     how many nodes the search expanded
     */
    record Path(List<Pos> waypoints, int nodes) {

        Path {
            waypoints = List.copyOf(waypoints);
        }

        boolean found() {
            return !waypoints.isEmpty();
        }
    }

    private static final Path NONE = new Path(List.of(), 0);

    private final NavGrid grid;
    private final int sizeX;

    /** Which search last wrote a column, so that the other two arrays can be read without clearing. */
    private final int[] stamp;
    private final float[] cost;
    private final int[] cameFrom;
    private final Heap open;

    private int searches;

    Pathfinder(NavGrid grid, MapPreset preset) {
        this.grid = grid;
        this.sizeX = preset.sizeX();
        final int columns = preset.sizeX() * preset.sizeZ();
        this.stamp = new int[columns];
        this.cost = new float[columns];
        this.cameFrom = new int[columns];
        this.open = new Heap(Math.min(columns, MAX_EXPANSIONS * 8));
    }

    /**
     * Searches for a path from where a unit stands to a column.
     *
     * @param from   where the unit is, which is also where the string pull starts measuring from
     * @param toX    the destination column, which the caller has already checked is walkable
     * @param toZ    the destination column
     * @param radius the half width of the unit, which the shortcuts have to keep clear
     * @return the path, {@link Path#found()} false when there is none within the budget
     */
    Path find(Pos from, int toX, int toZ, float radius) {
        final int fromX = (int) Math.floor(from.x());
        final int fromZ = (int) Math.floor(from.z());
        if (!grid.isWalkable(fromX, fromZ) || !grid.isWalkable(toX, toZ)) return NONE;
        final int start = index(fromX, fromZ);
        final int goal = index(toX, toZ);
        if (start == goal) return new Path(List.of(waypoint(toX, toZ)), 0);

        final int search = ++searches;
        open.clear();
        stamp[start] = search;
        cost[start] = 0;
        cameFrom[start] = start;
        open.push(heuristic(fromX, fromZ, toX, toZ), start);

        int expanded = 0;
        while (!open.isEmpty()) {
            final int current = open.popValue();
            if (current == goal) return new Path(smooth(from, trace(start, goal), radius), expanded);
            if (++expanded > MAX_EXPANSIONS) return NONE;
            final int x = current % sizeX;
            final int z = current / sizeX;
            final float currentCost = cost[current];
            for (int i = 0; i < NEIGHBOUR_X.length; i++) {
                final int nextX = x + NEIGHBOUR_X[i];
                final int nextZ = z + NEIGHBOUR_Z[i];
                if (!grid.isWalkable(nextX, nextZ)) continue;
                final boolean diagonal = NEIGHBOUR_X[i] != 0 && NEIGHBOUR_Z[i] != 0;
                // A diagonal step that squeezes between two obstacles would clip both of them
                if (diagonal && (!grid.isWalkable(x, nextZ) || !grid.isWalkable(nextX, z))) continue;
                final int next = index(nextX, nextZ);
                final float candidate = currentCost + (diagonal ? DIAGONAL_COST : 1f);
                if (stamp[next] == search && cost[next] <= candidate) continue;
                stamp[next] = search;
                cost[next] = candidate;
                cameFrom[next] = current;
                open.push(candidate + heuristic(nextX, nextZ, toX, toZ), next);
            }
        }
        return NONE;
    }

    /** The octile distance, the exact remaining cost over open ground, leaned on by {@link #GOAL_BIAS}. */
    private static float heuristic(int fromX, int fromZ, int toX, int toZ) {
        final int dx = Math.abs(toX - fromX);
        final int dz = Math.abs(toZ - fromZ);
        return (Math.max(dx, dz) + (DIAGONAL_COST - 1f) * Math.min(dx, dz)) * GOAL_BIAS;
    }

    private List<Pos> trace(int start, int goal) {
        final List<Pos> reversed = new ArrayList<>();
        int current = goal;
        while (current != start) {
            reversed.add(waypoint(current % sizeX, current / sizeX));
            current = cameFrom[current];
        }
        Collections.reverse(reversed);
        return reversed;
    }

    /**
     * The string pull: a waypoint is kept only when dropping it would make the unit walk through
     * something. Walking a grid path as it comes out of A* looks like a staircase; this is what
     * turns it into the straight lines a player expects.
     */
    private List<Pos> smooth(Pos from, List<Pos> path, float radius) {
        if (path.size() < 2) return path;
        final List<Pos> pulled = new ArrayList<>();
        Pos anchor = from;
        for (int i = 0; i < path.size() - 1; i++) {
            if (walkableCorridor(anchor, path.get(i + 1), radius)) continue;
            anchor = path.get(i);
            pulled.add(anchor);
        }
        pulled.add(path.getLast());
        return pulled;
    }

    /**
     * Whether a unit of that radius can walk the straight line between two points: the segment is
     * sampled every {@value #CORRIDOR_STEP} blocks and the four columns the unit's box touches at
     * each sample have to be walkable.
     */
    private boolean walkableCorridor(Pos from, Pos to, float radius) {
        final double dx = to.x() - from.x();
        final double dz = to.z() - from.z();
        final int samples = (int) Math.ceil(Math.hypot(dx, dz) / CORRIDOR_STEP);
        for (int i = 0; i <= samples; i++) {
            final double at = (double) i / samples;
            final double x = from.x() + dx * at;
            final double z = from.z() + dz * at;
            if (!grid.isWalkable((int) Math.floor(x - radius), (int) Math.floor(z - radius))
                    || !grid.isWalkable((int) Math.floor(x - radius), (int) Math.floor(z + radius))
                    || !grid.isWalkable((int) Math.floor(x + radius), (int) Math.floor(z - radius))
                    || !grid.isWalkable((int) Math.floor(x + radius), (int) Math.floor(z + radius))) {
                return false;
            }
        }
        return true;
    }

    /** The middle of a column, at the height a unit stands on it. */
    private Pos waypoint(int x, int z) {
        return new Pos(x + 0.5, grid.surfaceY(x, z), z + 0.5);
    }

    private int index(int x, int z) {
        return z * sizeX + x;
    }

    /**
     * A binary heap of (priority, column) pairs packed into one {@code long} each: the priority
     * goes in the high half as raw float bits, which sort like the floats themselves as long as
     * they are not negative, and a path cost never is. It exists because the JDK has no primitive
     * priority queue and boxing four thousand nodes per search, four searches a tick, is a lot of
     * garbage for something this small.
     */
    private static final class Heap {

        private long[] entries;
        private int size;

        Heap(int capacity) {
            this.entries = new long[Math.max(16, capacity)];
        }

        void clear() {
            size = 0;
        }

        boolean isEmpty() {
            return size == 0;
        }

        void push(float priority, int value) {
            if (size == entries.length) entries = Arrays.copyOf(entries, size * 2);
            long entry = ((long) Float.floatToIntBits(priority) << 32) | (value & 0xFFFFFFFFL);
            int child = size++;
            while (child > 0) {
                final int parent = (child - 1) >>> 1;
                if (entries[parent] <= entry) break;
                entries[child] = entries[parent];
                child = parent;
            }
            entries[child] = entry;
        }

        int popValue() {
            final long head = entries[0];
            final long last = entries[--size];
            int parent = 0;
            while (true) {
                final int left = parent * 2 + 1;
                if (left >= size) break;
                final int right = left + 1;
                final int smaller = right < size && entries[right] < entries[left] ? right : left;
                if (entries[smaller] >= last) break;
                entries[parent] = entries[smaller];
                parent = smaller;
            }
            if (size > 0) entries[parent] = last;
            return (int) head;
        }
    }
}
