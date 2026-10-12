package io.github.bearisbug.synaxis.game.rush;

import io.github.bearisbug.synaxis.coordinate.BlockVec;
import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.entity.metadata.display.AbstractDisplayMeta;
import io.github.bearisbug.synaxis.entity.metadata.display.TextDisplayMeta;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.generator.GenerationUnit;
import io.github.bearisbug.synaxis.instance.generator.Generator;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The ground of the lobby and of a round ({@code docs/DESIGN-PLAY.md} §6.7.3), on the match dimension
 * type, y 0 to 64. Both are four layers of stone, y 36 to 39, with the players standing at y 40 and a
 * wall of barriers, y 40 to 43, on the outermost ring of the floor. The lobby covers its 3 × 3 chunks;
 * the round is 50 × 50 blocks, 48 × 48 inside the wall, with the three beacon blocks on the floor and a
 * text display above each.
 */
final class Arena {

    /** The height the players stand at. */
    static final int GROUND = 40;

    /** Where a player lands in the lobby and in a round. */
    static final Pos SPAWN = new Pos(0.5, GROUND, 0.5);

    /** The chunk radius of the lobby: 3 × 3 chunks. */
    static final int LOBBY_RADIUS = 1;

    /** The chunk radius of a round: 5 × 5 chunks. */
    static final int ROUND_RADIUS = 2;

    private static final int FLOOR_DEPTH = 4;
    private static final int WALL_HEIGHT = 4;
    private static final int LOBBY_MIN = -16;
    private static final int LOBBY_MAX = 32;
    private static final int ROUND_MIN = -25;
    private static final int ROUND_MAX = 25;

    private Arena() {
    }

    static Generator lobby() {
        return unit -> platform(unit, LOBBY_MIN, LOBBY_MAX);
    }

    static Generator round() {
        return unit -> {
            platform(unit, ROUND_MIN, ROUND_MAX);
            for (final Beacon beacon : beacons())
                fill(unit, beacon.x(), beacon.y(), beacon.z(), beacon.x() + 1, beacon.y() + 1, beacon.z() + 1, Block.BEACON);
        };
    }

    /**
     * The beacons of a new round, nobody's yet: A at (−12, −12), B at (12, −12), C at (0, 14).
     *
     * @return three new beacons
     */
    static List<Beacon> beacons() {
        return List.of(new Beacon('A', -12, GROUND, -12), new Beacon('B', 12, GROUND, -12), new Beacon('C', 0, GROUND, 14));
    }

    /**
     * Puts up the label of a beacon: its letter in plain text, which the client draws as it is.
     *
     * @param instance the instance of the round
     * @param beacon   the beacon
     * @return the text display
     */
    static Entity label(Instance instance, Beacon beacon) {
        final Entity label = new Entity(EntityType.TEXT_DISPLAY);
        label.setNoGravity(true);
        final TextDisplayMeta meta = (TextDisplayMeta) label.getEntityMeta();
        meta.setBillboardRenderConstraints(AbstractDisplayMeta.BillboardConstraints.CENTER);
        meta.setText(text(beacon, null));
        var _ = label.setInstance(instance, beacon.label());
        return label;
    }

    /**
     * Writes who owns a beacon on its label.
     *
     * @param label  the text display of the beacon
     * @param beacon the beacon
     * @param owner  the name of the owner, null when nobody owns it
     */
    static void relabel(Entity label, Beacon beacon, @Nullable String owner) {
        ((TextDisplayMeta) label.getEntityMeta()).setText(text(beacon, owner));
    }

    private static Component text(Beacon beacon, @Nullable String owner) {
        final String letter = String.valueOf(beacon.letter());
        return Component.text(owner == null ? letter : letter + ": " + owner);
    }

    /** The floor from {@code min} to {@code max} on x and z, and the wall on its outermost ring. */
    private static void platform(GenerationUnit unit, int min, int max) {
        fill(unit, min, GROUND - FLOOR_DEPTH, min, max, GROUND, max, Block.STONE);
        final int top = GROUND + WALL_HEIGHT;
        fill(unit, min, GROUND, min, max, top, min + 1, Block.BARRIER);
        fill(unit, min, GROUND, max - 1, max, top, max, Block.BARRIER);
        fill(unit, min, GROUND, min, min + 1, top, max, Block.BARRIER);
        fill(unit, max - 1, GROUND, min, max, top, max, Block.BARRIER);
    }

    /** Fills the part of a box that lies in this unit; the corners are the low one and the high one, exclusive. */
    private static void fill(GenerationUnit unit, int x0, int y0, int z0, int x1, int y1, int z1, Block block) {
        final Point start = unit.absoluteStart();
        final Point end = unit.absoluteEnd();
        final int minX = Math.max(x0, start.blockX());
        final int minY = Math.max(y0, start.blockY());
        final int minZ = Math.max(z0, start.blockZ());
        final int maxX = Math.min(x1, end.blockX());
        final int maxY = Math.min(y1, end.blockY());
        final int maxZ = Math.min(z1, end.blockZ());
        if (minX >= maxX || minY >= maxY || minZ >= maxZ) return;
        unit.modifier().fill(new BlockVec(minX, minY, minZ), new BlockVec(maxX, maxY, maxZ), block);
    }
}
