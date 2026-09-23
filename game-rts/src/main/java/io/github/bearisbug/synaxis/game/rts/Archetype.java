package io.github.bearisbug.synaxis.game.rts;

import io.github.bearisbug.synaxis.game.protocol.ArchetypeBox;
import io.github.bearisbug.synaxis.item.Material;

/**
 * The static template behind an archetype number ({@code docs/DESIGN-M2.md} §9, the archetype
 * template row).
 * <p>
 * It is our own data model: nothing here is registered with any Minecraft registry, and only the
 * number travels on the wire. Speed, radius and {@code view_range} never leave the server; the
 * picking box does, so it is the one field that lives in {@code :protocol} instead of here
 * ({@link ArchetypeBox}).
 * <p>
 * {@link #viewRange()} is a configuration value rather than a threshold written into the code that
 * uses it. The client culls a display entity beyond
 * {@code view_range × 64 × clamp(renderDistance / 8, 1, 2.5) × entityDistanceScaling} blocks,
 * measured from the render camera, and the player can take that last multiplier down to 0.5. 5.0
 * keeps units drawn out to the render distance fog at that setting: with a render distance of 10
 * it reaches 200 blocks, which covers the fog edge 160 blocks out horizontally up to a camera 120
 * blocks high (96 high at render distance 8). Change the zoom cap, the camera height or the unit
 * sizes and this is the one number to move (§15, the visible range row).
 *
 * @param id        the archetype number carried by {@code S2C_UNIT_SPAWN}
 * @param item      what the display entity shows
 * @param speed     {@code archetype_speed}, in blocks per tick
 * @param radius    {@code archetype_radius}, the half width the pathfinder keeps clear
 * @param viewRange {@code archetype_view_range}, the display entity's client side culling range
 */
record Archetype(int id, Material item, float speed, float radius, float viewRange) {

    /** The one archetype M2 fields; the rest of the roster is M3 (§28.3). */
    static final Archetype SOLDIER = new Archetype(0, Material.IRON_GOLEM_SPAWN_EGG, 0.15f, 0.45f, 5.0f);

    /**
     * The picking box of this archetype, the half of the template the client holds too.
     *
     * @return its box
     */
    ArchetypeBox box() {
        return ArchetypeBox.of(id);
    }
}
