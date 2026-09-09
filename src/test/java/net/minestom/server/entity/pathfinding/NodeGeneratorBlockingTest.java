package net.minestom.server.entity.pathfinding;

import net.minestom.server.collision.BoundingBox;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.pathfinding.generators.GroundNodeGenerator;
import net.minestom.server.entity.pathfinding.generators.NodeGenerator;
import net.minestom.server.entity.pathfinding.generators.WaterNodeGenerator;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NodeGeneratorBlockingTest {
    private static final BoundingBox ZOMBIE_BOX = new BoundingBox(0.6, 1.95, 0.6);

    /**
     * Stone below y=40, {@code surface} at y=40 and air above.
     */
    private static Block.Getter surface(Block surface) {
        return (_, y, _, _) -> {
            if (y < 40) return Block.STONE;
            return y == 40 ? surface : Block.AIR;
        };
    }

    private static boolean canWalkOver(Block block) {
        final NodeGenerator generator = new GroundNodeGenerator();
        return generator.canMoveTowards(surface(block), new Vec(0.5, 40, 0.5), new Vec(1.5, 40, 0.5), ZOMBIE_BOX);
    }

    @Test
    public void cropsDoNotBlockMovement() {
        assertTrue(canWalkOver(Block.WHEAT), "1/16-height crops must not block pathfinding");
    }

    @Test
    public void nonDefaultAirDoesNotBlockMovement() {
        assertTrue(canWalkOver(Block.CAVE_AIR), "CAVE_AIR must be walkable like AIR");
        assertTrue(canWalkOver(Block.VOID_AIR), "VOID_AIR must be walkable like AIR");
    }

    @Test
    public void fullBlocksStillBlockMovement() {
        assertFalse(canWalkOver(Block.STONE), "a full block must still block pathfinding");
    }

    @Test
    public void waterGeneratorProducesNodes() {
        final Block.Getter water = (_, _, _, _) -> Block.WATER;
        final NodeGenerator generator = new WaterNodeGenerator();
        final PNode start = new PNode(new Vec(0.5, 40, 0.5), 0, 0, PNode.Type.FLY, null);
        var walkable = generator.getWalkable(water, Set.of(), start, new Vec(5.5, 40, 0.5), ZOMBIE_BOX);
        assertFalse(walkable.isEmpty(), "WaterNodeGenerator never produces a node, water pathfinding is dead");
    }
}
