package io.github.bearisbug.synaxis.entity.player;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.EntityPose;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@EnvTest
public class PlayerSwimmingPoseIntegrationTest {

    @Test
    public void sprintingWithSubmergedEyesSwims(Env env) {
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();
        instance.setBlock(0, 40, 0, Block.WATER);
        instance.setBlock(0, 41, 0, Block.WATER);

        var player = env.createPlayer(instance, new Pos(0.5, 40, 0.5));
        player.setSprinting(true);
        env.tick();

        assertEquals(EntityPose.SWIMMING, player.getPose());
    }

    @Test
    public void sprintingOnLandDoesNotSwim(Env env) {
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();

        var player = env.createPlayer(instance, new Pos(0.5, 40, 0.5));
        player.setSprinting(true);
        env.tick();

        assertEquals(EntityPose.STANDING, player.getPose());
    }

    @Test
    public void leavingTheWaterStopsSwimming(Env env) {
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();
        instance.setBlock(0, 40, 0, Block.WATER);
        instance.setBlock(0, 41, 0, Block.WATER);

        var player = env.createPlayer(instance, new Pos(0.5, 40, 0.5));
        player.setSprinting(true);
        env.tick();
        assertEquals(EntityPose.SWIMMING, player.getPose());

        instance.setBlock(0, 40, 0, Block.AIR);
        instance.setBlock(0, 41, 0, Block.AIR);
        env.tick();
        assertEquals(EntityPose.STANDING, player.getPose());
    }

    /** The box of a player at x 0.1 reaches into chunk -1: with that chunk unloaded, the player keeps standing. */
    @Test
    public void aBoxReachingIntoAnUnloadedChunkKeepsStanding(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0.1, 40, 0.5));
        final Chunk neighbour = instance.getChunk(-1, 0);
        assertNotNull(neighbour);
        instance.unloadChunk(neighbour);

        env.tick();
        env.tick();

        assertEquals(EntityPose.STANDING, player.getPose());
    }

    /** Out of any instance there are no blocks to fit between: sneaking sneaks. */
    @Test
    public void sneakingOutsideAnyInstanceSneaks(Env env) {
        var player = new Player(new SilentConnection(env.process()), new GameProfile(UUID.randomUUID(), "Outside"));

        player.setSneaking(true);

        assertEquals(EntityPose.SNEAKING, player.getPose());
    }

    private static final class SilentConnection extends PlayerConnection {
        private SilentConnection(ServerProcess process) {
            super(process);
        }

        @Override
        public void sendPacket(SendablePacket packet) {
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("localhost", 25565);
        }
    }
}
