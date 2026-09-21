package io.github.bearisbug.synaxis.game.client;

import net.minecraft.world.phys.Vec3;

/**
 * The two questions {@code REQ-M2-019} has to put to Minecraft, and the only two.
 * <p>
 * Everything else the selection and ordering path does — building picking boxes, keeping the
 * {@code unit_id ↔ entity_id} table, deciding what a click puts on the wire — sits on this side of
 * the interface and is therefore testable without a running client. The production answer is
 * {@link MinecraftWorldView}, whose two bodies are the only lines of this feature that a real
 * client has to verify.
 */
public interface ClientWorldView {

    /**
     * Where an entity is being drawn right now.
     * <p>
     * This is the interpolated position, not the last one a packet carried: {@code ADR-M2-012}
     * makes the picking box and the rendered model share one source, so that "looks like it is
     * here" and "can be clicked here" cannot come apart while a unit moves.
     *
     * @param entityId the engine entity id a {@code S2C_UNIT_SPAWN} bound to a unit
     * @return the position, or {@code null} when this client has no such entity — the binding and
     *         the engine's spawn packet arrive in either order (§8)
     */
    Vec3 renderPosition(int entityId);

    /**
     * Where a ray first meets the ground.
     *
     * @param origin      where the ray starts
     * @param direction   which way it points; it need not be a unit vector
     * @param maxDistance how far to look, in blocks
     * @return the world point that was hit, or {@code null} when the ray reached nothing
     */
    Vec3 groundHit(Vec3 origin, Vec3 direction, double maxDistance);
}
