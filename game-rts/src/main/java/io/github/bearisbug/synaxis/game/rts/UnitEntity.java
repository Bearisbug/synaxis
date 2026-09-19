package io.github.bearisbug.synaxis.game.rts;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.entity.metadata.display.ItemDisplayMeta;
import io.github.bearisbug.synaxis.game.protocol.ArchetypeBox;
import io.github.bearisbug.synaxis.item.ItemStack;

/**
 * The visible half of a unit: one {@code ITEM_DISPLAY} entity, configured the five ways REQ-M2-016
 * requires and used for nothing else.
 * <p>
 * A unit is not a Minecraft entity; it only borrows one so that the vanilla renderer draws it and
 * the engine's own entity packets carry its position. Everything the engine would otherwise do to
 * an entity is turned off here, because a unit's position is decided by the pathfinder and by
 * nothing else:
 * <ol>
 *   <li>no physics and no gravity — the server states positions, it does not simulate them;</li>
 *   <li>{@code setPosRotInterpolationDuration(2)} at spawn and never again, so the client
 *       interpolates over exactly the two ticks between two advances. It is
 *       {@code posRotInterpolationDuration} and not {@code transformationInterpolationDuration}:
 *       the other one smooths the model transform and leaves the position jumping;</li>
 *   <li>a position resynchronisation every {@value #SYNCHRONIZATION_TICKS} ticks — widened, not
 *       switched off. The periodic resync is what corrects the quantisation error the relative
 *       position packets accumulate, and a display entity has no second path back to the truth;</li>
 *   <li>{@code view_range} from the archetype, so the client keeps rendering a unit in the corner
 *       of an RTS viewport;</li>
 *   <li>a culling box from the archetype's {@link ArchetypeBox}, never zero — zero means "never
 *       cull" to the client, which is a coincidence rather than a box.</li>
 * </ol>
 * <p>
 * <b>Nothing selection related is ever set on it.</b> Entity metadata is global to the entity, so a
 * glow put on it for the player who selected the unit would light it up for everyone watching; the
 * selection box is drawn by the client that selected it (§19, {@code ADR-M2-002}).
 */
final class UnitEntity extends Entity {

    /** The interpolation window, in ticks: exactly the interval between two advances. */
    static final int POS_ROT_INTERPOLATION_TICKS = 2;

    /** §16: how often the engine resends an absolute position, to clear the accumulated error. */
    static final long SYNCHRONIZATION_TICKS = 100;

    private final int unitId;
    private final UnitViewers interest;

    /**
     * What the interest set is made of: the engine adds and removes viewers, and a unit entering or
     * leaving one player's viewer set is that unit entering or leaving that player's interest set
     * ({@code API-M2-006}). The plugin computes no radius of its own.
     */
    interface UnitViewers {

        /**
         * A player started seeing this unit.
         *
         * @param unitId the unit
         * @param player the player who now sees it
         */
        void viewerAdded(int unitId, Player player);

        /**
         * A player stopped seeing this unit.
         *
         * @param unitId the unit
         * @param player the player who no longer sees it
         */
        void viewerRemoved(int unitId, Player player);
    }

    UnitEntity(ServerProcess process, int unitId, Archetype archetype, UnitViewers interest) {
        super(process, EntityType.ITEM_DISPLAY);
        this.unitId = unitId;
        this.interest = interest;

        setHasPhysics(false);
        setNoGravity(true);
        setSynchronizationTicks(SYNCHRONIZATION_TICKS);

        final ArchetypeBox box = archetype.box();
        final ItemDisplayMeta meta = (ItemDisplayMeta) getEntityMeta();
        meta.setPosRotInterpolationDuration(POS_ROT_INTERPOLATION_TICKS);
        meta.setViewRange(archetype.viewRange());
        meta.setWidth(box.width());
        meta.setHeight(box.height());
        meta.setItemStack(ItemStack.of(archetype.item()));
    }

    @Override
    public void updateNewViewer(Player player) {
        super.updateNewViewer(player);
        interest.viewerAdded(unitId, player);
    }

    @Override
    public void updateOldViewer(Player player) {
        super.updateOldViewer(player);
        interest.viewerRemoved(unitId, player);
    }
}
