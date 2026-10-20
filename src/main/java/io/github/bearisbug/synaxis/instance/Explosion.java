package io.github.bearisbug.synaxis.instance;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.network.packet.server.play.ExplosionPacket;
import io.github.bearisbug.synaxis.particle.Particle;
import io.github.bearisbug.synaxis.sound.SoundEvent;
import io.github.bearisbug.synaxis.network.PacketSendingUtils;
import io.github.bearisbug.synaxis.utils.WeightedList;

import java.util.List;

/**
 * Abstract explosion.
 * Instance can provide a supplier through {@link Instance#setExplosionSupplier}
 *
 * @deprecated set the blocks with {@link Instance#setBlock(int, int, int, Block)}, then send an
 * {@link ExplosionPacket} with {@link PacketSendingUtils}
 */
@Deprecated(forRemoval = true)
public abstract class Explosion {

    private final float centerX;
    private final float centerY;
    private final float centerZ;
    private final float strength;

    public Explosion(float centerX, float centerY, float centerZ, float strength) {
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.strength = strength;
    }

    public float getStrength() {
        return strength;
    }

    public float getCenterX() {
        return centerX;
    }

    public float getCenterY() {
        return centerY;
    }

    public float getCenterZ() {
        return centerZ;
    }

    /**
     * Prepares the list of blocks that will be broken. Also pushes and damage entities affected by this explosion
     *
     * @param instance instance to perform this explosion in
     * @return list of blocks that will be broken.
     */
    protected abstract List<Point> prepare(Instance instance);

    /**
     * Performs the explosion and send the corresponding packet
     *
     * @param instance instance to perform this explosion in
     */
    public void apply(Instance instance) {
        List<Point> blocks = prepare(instance);
        for (final Point pos : blocks) {
            instance.setBlock(pos, Block.AIR);
        }

        ExplosionPacket packet = new ExplosionPacket(
                // TODO(1.21.9): explosion update
                new Vec(centerX, centerY, centerZ), 0, 0, Vec.ZERO,
                Particle.EXPLOSION, SoundEvent.ENTITY_GENERIC_EXPLODE, WeightedList.of());
        postExplosion(instance, blocks, packet);
        PacketSendingUtils.sendGroupedPacket(instance.getPlayers(), packet);

        postSend(instance, blocks);
    }

    /**
     * Called after removing blocks and preparing the packet, but before sending it.
     *
     * @param instance the instance in which the explosion occurs
     * @param blocks   the block positions returned by prepare
     * @param packet   the explosion packet sent to the clients, read-only; its radius and block count are 0
     *                 for now. To change what the clients get, override {@link #apply(Instance)}.
     */
    protected void postExplosion(Instance instance, List<Point> blocks, ExplosionPacket packet) {
    }

    /**
     * Called after sending the explosion packet. Can be used to (re)set blocks that have been destroyed.
     *
     * @param instance the instance in which the explosion occurs
     * @param blocks   the block positions returned by prepare
     */
    protected void postSend(Instance instance, List<Point> blocks) {
    }
}
