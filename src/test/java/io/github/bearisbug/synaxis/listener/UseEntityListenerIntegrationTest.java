package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.entity.PlayerHand;
import io.github.bearisbug.synaxis.entity.attribute.Attribute;
import io.github.bearisbug.synaxis.entity.metadata.other.InteractionMeta;
import io.github.bearisbug.synaxis.event.entity.EntityAttackEvent;
import io.github.bearisbug.synaxis.event.player.PlayerEntityInteractEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.item.component.AttackRange;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientAttackPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientInteractEntityPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class UseEntityListenerIntegrationTest {

    private Player player;
    private Entity targetEntity;
    private boolean eventWasCalled;

    @BeforeEach
    public void setup(Env env) {
        Instance instance = env.createFlatInstance();

        player = env.createPlayer(instance, new Pos(0, 0, 0));
        player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE).setBaseValue(5.0);

        targetEntity = new Entity(EntityType.SLIME);
        targetEntity.setInstance(instance, new Pos(2, 0, 2)).join();

        eventWasCalled = false;

        player.eventNode().addListener(PlayerEntityInteractEvent.class, event -> {
            if (event.getPlayer().equals(player) && event.getTarget().equals(targetEntity)) {
                eventWasCalled = true;
            }
        });
    }

    @Test
    public void testInteractionWithinRange() {
        ClientInteractEntityPacket packet = new ClientInteractEntityPacket(
                targetEntity.getEntityId(),
                PlayerHand.MAIN,
                Vec.ZERO,
                false
        );

        UseEntityListener.useEntityListener(packet, player);
        assertTrue(eventWasCalled, "Expected PlayerEntityInteractEvent to be called for nearby target");
    }

    @Test
    public void testInteractionOutOfRange() {
        player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE).setBaseValue(1.0);

        targetEntity.teleport(new Pos(10, 0, 10)).join();
        ClientInteractEntityPacket packet = new ClientInteractEntityPacket(
                targetEntity.getEntityId(),
                PlayerHand.MAIN,
                Vec.ZERO,
                false
        );

        eventWasCalled = false;
        UseEntityListener.useEntityListener(packet, player);
        assertFalse(eventWasCalled, "Expected PlayerEntityInteractEvent NOT to be called for out-of-range target");
    }

    @Test
    public void testInteractionConsideringHitboxAndEyePosition() {
        player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE).setBaseValue(1.5);

        targetEntity.teleport(new Pos(1.6, 0, 0)).join();

        ClientInteractEntityPacket packet = new ClientInteractEntityPacket(
                targetEntity.getEntityId(),
                PlayerHand.MAIN,
                Vec.ZERO,
                false
        );

        eventWasCalled = false;
        UseEntityListener.useEntityListener(packet, player);
        assertTrue(eventWasCalled, "Expected PlayerEntityInteractEvent to be called considering hitbox size and eye position");
    }


    @Test
    public void testInteractionConsideringEyeHeight() {
        player.teleport(new Pos(0, 1.6, 0)).join();
        targetEntity.teleport(new Pos(0, 1.6, 2)).join();

        ClientInteractEntityPacket packet = new ClientInteractEntityPacket(
                targetEntity.getEntityId(),
                PlayerHand.MAIN,
                Vec.ZERO,
                false
        );

        eventWasCalled = false;
        UseEntityListener.useEntityListener(packet, player);
        assertTrue(eventWasCalled, "Expected PlayerEntityInteractEvent to be called considering eye height");
    }

    @Test
    public void anInteractionEntityIsReachedAsFarAsItsWidthSays() {
        player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE).setBaseValue(3.0);
        assertTrue(attackReaches(new Pos(-5, 0, 0)),
                "6 blocks wide, its edge is 2 blocks from the player, well within reach");
        assertFalse(attackReaches(new Pos(-9.5, 0, 0)), "its edge is 6.5 blocks away, out of reach");
    }

    /**
     * Vanilla 26.2's rule for an attack without an attack range on the item, {@code isWithinAttackRange(…, 3.0)}: the
     * box may be as far as the range plus three blocks from the eyes, that distance included. The 1 block high box at
     * y 1 holds the eyes' height, so the distance is the one along x.
     */
    @Test
    public void anAttackReachesTheRangePlusThreeBlocksAndNoFurther() {
        player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE).setBaseValue(3.0);
        assertTrue(attackReaches(new Pos(-9, 1, 0)), "its edge is 6 blocks away");
        assertFalse(attackReaches(new Pos(-9.01, 1, 0)), "its edge is 6.01 blocks away");
    }

    /**
     * Vanilla 26.2's rule for using an entity, {@code isWithinEntityInteractionRange(…, 3.0)}: out of reach once the
     * box is the range plus three blocks from the eyes.
     */
    @Test
    public void aUseReachesUpToTheRangePlusThreeBlocks() {
        player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE).setBaseValue(3.0);
        assertTrue(useReaches(new Pos(-8.99, 1, 0)), "its edge is 5.99 blocks away");
        assertFalse(useReaches(new Pos(-9, 1, 0)), "its edge is 6 blocks away");
    }

    /**
     * An item with an attack range attacks by it instead of the entity interaction range, as vanilla 26.2 does: a
     * spear reaches 4.5 blocks plus its 0.125 margin plus the three blocks, and an item with a minimum reach refuses a
     * box closer than the minimum less the margin and the three blocks.
     */
    @Test
    public void theAttackRangeOfTheItemInHandDecides() {
        player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE).setBaseValue(3.0);
        player.setItemInMainHand(ItemStack.of(Material.IRON_SPEAR));
        assertTrue(attackReaches(new Pos(-10.6, 1, 0)), "its edge is 7.6 blocks away");
        assertFalse(attackReaches(new Pos(-10.65, 1, 0)), "its edge is 7.65 blocks away");

        player.setItemInMainHand(ItemStack.of(Material.STICK)
                .with(DataComponents.ATTACK_RANGE, new AttackRange(5f, 6f, 5f, 6f, 0f, 1f)));
        assertFalse(attackReaches(new Pos(-4.5, 1, 0)), "its edge is 1.5 blocks away, under 5 - 3");
        assertTrue(attackReaches(new Pos(-5.5, 1, 0)), "its edge is 2.5 blocks away");
    }

    /** Whether an attack on a 6 blocks wide interaction entity at {@code at} reaches it. */
    private boolean attackReaches(Pos at) {
        final Entity zone = zoneAt(at);
        final AtomicBoolean attacked = new AtomicBoolean();
        player.eventNode().addListener(EntityAttackEvent.class, event -> {
            if (event.getTarget() == zone) attacked.set(true);
        });

        UseEntityListener.attackEntityListener(new ClientAttackPacket(zone.getEntityId()), player);
        zone.remove();
        return attacked.get();
    }

    /** Whether using a 6 blocks wide interaction entity at {@code at} reaches it. */
    private boolean useReaches(Pos at) {
        final Entity zone = zoneAt(at);
        final AtomicBoolean used = new AtomicBoolean();
        player.eventNode().addListener(PlayerEntityInteractEvent.class, event -> {
            if (event.getTarget() == zone) used.set(true);
        });

        UseEntityListener.useEntityListener(new ClientInteractEntityPacket(zone.getEntityId(), PlayerHand.MAIN, Vec.ZERO,
                false), player);
        zone.remove();
        return used.get();
    }

    private Entity zoneAt(Pos at) {
        final Entity zone = new Entity(EntityType.INTERACTION);
        ((InteractionMeta) zone.getEntityMeta()).setWidth(6);
        zone.setInstance(player.getInstance(), at).join();
        assertTrue(zone.isViewer(player));
        return zone;
    }
}
