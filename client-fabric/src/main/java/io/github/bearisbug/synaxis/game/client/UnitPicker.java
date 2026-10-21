package io.github.bearisbug.synaxis.game.client;

import io.github.bearisbug.synaxis.game.protocol.ArchetypeBox;
import java.util.Optional;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The ray cast of {@code ADR-M2-012}.
 * <p>
 * Display entities have no hitbox, so vanilla's entity ray hits none of them and the mod does the
 * cast itself: one box per bound unit, built out of the entity's rendered position and the width
 * and height the shared {@code archetype_box} table gives that archetype. Those numbers are the
 * half of the archetype template both ends hold, and §24 freezes them into a test vector for
 * exactly this reason — a table the two ends disagree on is a unit you can see and cannot click.
 * <p>
 * There is no interaction range: an RTS camera is a hundred blocks up, and vanilla's own distance
 * check is what {@code ADR-M2-012} rejected the {@code minecraft:interaction} alternative over.
 */
public final class UnitPicker {

    private UnitPicker() {
    }

    /**
     * Finds the unit a click is pointing at.
     *
     * @param origin      where the ray starts
     * @param direction   which way it points; it need not be a unit vector
     * @param maxDistance how far to look, in blocks
     * @param bindings    the units this client knows about
     * @param world       where rendered positions come from
     * @return the nearest unit the ray enters, or {@code null} if it enters none
     */
    public static UnitBindings.Binding pick(Vec3 origin, Vec3 direction, double maxDistance,
                                            UnitBindings bindings, ClientWorldView world) {
        final Vec3 end = origin.add(direction.normalize().scale(maxDistance));
        UnitBindings.Binding nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (final UnitBindings.Binding binding : bindings.all()) {
            final AABB box = boxOf(binding, world);
            if (box == null) continue;
            final Optional<Vec3> hit = box.clip(origin, end);
            if (hit.isEmpty()) continue;
            final double distance = origin.distanceToSqr(hit.get());
            if (distance < nearestDistance) {
                nearest = binding;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    /**
     * @return the box to cast against, or {@code null} when this unit has none — either the engine
     *         has not spawned its entity here yet, or the archetype is one this build does not
     *         know, which §9 renders as a placeholder and this cannot invent a size for
     */
    private static AABB boxOf(UnitBindings.Binding binding, ClientWorldView world) {
        final Vec3 position = world.renderPosition(binding.entityId());
        if (position == null) return null;
        final ArchetypeBox size = ArchetypeBox.find(binding.archetype()).orElse(null);
        if (size == null) return null;
        // Same convention as an entity's own bounding box: centred on x and z, standing on y.
        final double half = size.width() / 2.0;
        return new AABB(position.x - half, position.y, position.z - half,
                position.x + half, position.y + size.height(), position.z + half);
    }
}
