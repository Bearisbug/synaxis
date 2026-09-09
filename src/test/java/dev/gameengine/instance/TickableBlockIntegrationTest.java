package dev.gameengine.instance;

import net.kyori.adventure.key.Key;
import dev.gameengine.coordinate.Point;
import dev.gameengine.instance.block.Block;
import dev.gameengine.instance.block.BlockHandler;
import dev.gameengine.testing.Env;
import dev.gameengine.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnvTest
public class TickableBlockIntegrationTest {

    // Making a block non-tickable from its own tick is the usual way to write a furnace or a crop,
    // the removal must not make the iteration skip the entries behind it.
    @Test
    public void handlerRemovingItselfDoesNotSkipOtherBlocks(Env env) {
        var instance = env.createFlatInstance();
        var chunk = instance.loadChunk(0, 0).join();

        Set<Point> ticked = new HashSet<>();
        BlockHandler handler = new BlockHandler() {
            @Override
            public Key getKey() {
                return Key.key("test:self_removing");
            }

            @Override
            public boolean isTickable() {
                return true;
            }

            @Override
            public void tick(Tick tick) {
                ticked.add(tick.getBlockPosition());
                tick.getInstance().setBlock(tick.getBlockPosition(), Block.STONE);
            }
        };

        for (int x = 0; x < 16; x++) instance.setBlock(x, 41, 0, Block.DIRT.withHandler(handler));
        chunk.tick(0);
        assertEquals(16, ticked.size(), "tick iteration skipped entries after a self-removal");
    }
}
