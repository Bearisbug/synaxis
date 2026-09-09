package dev.gameengine.instance.light;

import net.kyori.adventure.key.Key;
import dev.gameengine.instance.LightingChunk;
import dev.gameengine.instance.block.Block;
import dev.gameengine.instance.block.BlockHandler;
import dev.gameengine.testing.Env;
import dev.gameengine.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class LightDeadlockIntegrationTest {

    // A handler reading light from onPlace runs while the instance holds the chunk write-lock,
    // relighting from there must not hand the section to a pool thread that waits for that lock.
    @Test
    public void relightInsideBlockHandlerDoesNotDeadlock(Env env) throws InterruptedException {
        var instance = env.createFlatInstance();
        instance.setChunkSupplier(LightingChunk::new);
        instance.loadChunk(0, 0).join();

        var probed = new AtomicInteger(-1);
        BlockHandler handler = new BlockHandler() {
            @Override
            public Key getKey() {
                return Key.key("test:light_probe");
            }

            @Override
            public void onPlace(Placement placement) {
                probed.set(placement.getInstance().getBlockLight(1, 41, 1));
            }
        };

        var thread = Thread.ofVirtual().unstarted(() ->
                instance.setBlock(1, 41, 1, Block.GLOWSTONE.withHandler(handler)));
        thread.start();
        assertTrue(thread.join(Duration.ofSeconds(10)), "setBlock deadlocked inside the lighting engine");
        assertEquals(15, probed.get(), "the glowstone light level was not visible to the handler");
    }
}
