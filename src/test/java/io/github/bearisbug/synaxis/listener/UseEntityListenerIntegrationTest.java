package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.entity.PlayerHand;
import io.github.bearisbug.synaxis.entity.attribute.Attribute;
import io.github.bearisbug.synaxis.event.player.PlayerEntityInteractEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientInteractEntityPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
}
