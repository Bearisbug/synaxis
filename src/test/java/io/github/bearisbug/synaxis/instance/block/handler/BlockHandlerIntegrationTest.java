package io.github.bearisbug.synaxis.instance.block.handler;

import net.kyori.adventure.key.Key;
import io.github.bearisbug.synaxis.coordinate.BlockVec;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.PlayerHand;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.coordinate.BlockFace;
import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerBlockPlacementPacket;
import io.github.bearisbug.synaxis.tag.Tag;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
class BlockHandlerIntegrationTest {

    @Test
    void testOnPlace(Env env) {
        var instance = env.createFlatInstance();
        var blockPosition = new Vec(-64, 40, 64);

        var handler = new BlockHandler() {
            @Override
            public void onPlace(Placement placement) {
                assertEquals(blockPosition, placement.getBlockPosition());
            }

            @Override
            public Key getKey() {
                return Key.key("synaxis:test");
            }
        };

        instance.setBlock(blockPosition, Block.STONE.withHandler(handler));
    }

    @Test
    void testOnDestroy(Env env) {
        var instance = env.createFlatInstance();
        var blockPosition = new Vec(64, 40, -64);

        var handler = new BlockHandler() {
            @Override
            public void onDestroy(Destroy destroy) {
                assertEquals(blockPosition, destroy.getBlockPosition());
            }

            @Override
            public Key getKey() {
                return Key.key("synaxis:test");
            }
        };

        instance.setBlock(blockPosition, Block.STONE.withHandler(handler));
        instance.setBlock(blockPosition, Block.AIR);
    }

    @Test
    void testOnInteract(Env env) {
        var instance = env.createFlatInstance();
        var blockPosition = new Vec(-64, 40, 64);

        AtomicBoolean interacted = new AtomicBoolean(false);
        var handler = new BlockHandler() {
            @Override
            public boolean onInteract(Interaction interaction) {
                interacted.set(true);
                assertEquals(blockPosition, interaction.getBlockPosition());
                return false;
            }

            @Override
            public Key getKey() {
                return Key.key("synaxis:test");
            }
        };

        instance.setBlock(blockPosition, Block.STONE.withHandler(handler));
        var player = env.createPlayer(instance, blockPosition.asPos());
        player.addPacketToQueue(new ClientPlayerBlockPlacementPacket(PlayerHand.MAIN, blockPosition, BlockFace.TOP, 0, 0, 0, false, false, 1));
        player.interpretPacketQueue(); // Use packets

        assertTrue(interacted.get());
    }

    @Test
    void testTick(Env env) {
        var instance = env.createFlatInstance();
        var blockPosition = new BlockVec(64, 40, -64);

        AtomicBoolean ticked = new AtomicBoolean(false);
        var handler = new BlockHandler() {
            @Override
            public void tick(Tick tick) {
                ticked.set(true);
                assertEquals(tick.getBlockPosition(), blockPosition.asVec());
            }

            @Override
            public Key getKey() {
                return Key.key("synaxis:test");
            }

            @Override
            public boolean isTickable() {
                return true;
            }
        };

        instance.setBlock(blockPosition, Block.STONE.withHandler(handler));
        // Tick the chunk
        var chunk = instance.getChunk(4, -4);
        assertNotNull(chunk);
        chunk.tick(0);

        assertTrue(ticked.get());
    }

    @Test
    void testTickRemoved(Env env) {
        var instance = env.createFlatInstance();
        var blockPosition = new BlockVec(64, 40, -64);

        AtomicBoolean ticked = new AtomicBoolean(false);
        var handler = new BlockHandler() {
            @Override
            public void tick(Tick tick) {
                ticked.set(true);
                assertEquals(tick.getBlockPosition(), blockPosition.asVec());
            }

            @Override
            public Key getKey() {
                return Key.key("synaxis:test");
            }

            @Override
            public boolean isTickable() {
                return true;
            }
        };

        instance.setBlock(blockPosition, Block.STONE.withHandler(handler));
        // Tick the chunk
        var chunk = instance.getChunk(4, -4);
        assertNotNull(chunk);
        chunk.tick(0);

        assertTrue(ticked.get());
        // Now assume there is no chunk left.
        ticked.set(false);
        instance.setBlock(blockPosition, Block.AIR);
        chunk.tick(0);
        assertFalse(ticked.get(), "Chunk ticked block when it no longer exists!");
    }

    // The contract BlockHandler documents: setting a block whose previous block has a handler destroys
    // that one first, even when only the NBT of the block changes
    @Test
    void changingOnlyTheNbtDestroysAndPlacesOnce(Env env) {
        var instance = env.createFlatInstance();
        var blockPosition = new Vec(8, 40, 8);
        var count = Tag.Integer("count");

        AtomicInteger destroyed = new AtomicInteger();
        AtomicInteger placed = new AtomicInteger();
        var handler = new BlockHandler() {
            @Override
            public void onPlace(Placement placement) {
                placed.incrementAndGet();
            }

            @Override
            public void onDestroy(Destroy destroy) {
                destroyed.incrementAndGet();
                assertEquals(1, (int) destroy.getNewBlock().getTag(count));
            }

            @Override
            public Key getKey() {
                return Key.key("synaxis:test");
            }
        };

        instance.setBlock(blockPosition, Block.STONE.withHandler(handler));
        placed.set(0);
        instance.setBlock(blockPosition, instance.getBlock(blockPosition).withTag(count, 1));

        assertEquals(1, destroyed.get(), "onDestroy calls for an NBT only change");
        assertEquals(1, placed.get(), "onPlace calls for an NBT only change");
    }
}
