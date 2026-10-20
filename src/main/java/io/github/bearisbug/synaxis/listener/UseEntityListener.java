package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.collision.BoundingBox;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.LivingEntity;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.entity.attribute.Attribute;
import io.github.bearisbug.synaxis.entity.metadata.other.InteractionMeta;
import io.github.bearisbug.synaxis.event.entity.EntityAttackEvent;
import io.github.bearisbug.synaxis.event.player.PlayerEntityInteractEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientAttackPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientInteractEntityPacket;

public class UseEntityListener {

    public static void useEntityListener(ClientInteractEntityPacket packet, Player player) {
        final Instance instance = player.getInstance();
        if (instance == null) return;
        final Entity entity = instance.getEntityById(packet.targetId());
        if (entity == null || invalidUse(player, entity))
            return;
        player.process().eventHandler().call(new PlayerEntityInteractEvent(player, entity, packet.hand(), packet.location()));
    }

    public static void attackEntityListener(ClientAttackPacket packet, Player player) {
        final Instance instance = player.getInstance();
        if (instance == null) return;
        final Entity entity = instance.getEntityById(packet.targetId());
        if (entity == null || invalidUse(player, entity))
            return;
        if (entity instanceof LivingEntity livingEntity && livingEntity.isDead()) // Can't attack dead entities
            return;
        player.process().eventHandler().call(new EntityAttackEvent(player, entity));
    }

    static boolean invalidUse(Player player, Entity entity) {
        if (!entity.isViewer(player))
            return true;

        if (player.process().config().enforceInteractionLimit()) {
            // The client hits an interaction entity as wide and high as its metadata says, its bounding box is
            // the 0 by 0 of its type
            final BoundingBox box = entity.getEntityMeta() instanceof InteractionMeta interaction
                    ? new BoundingBox(interaction.getWidth(), interaction.getHeight(), interaction.getWidth())
                    : entity.getBoundingBox();
            final double halfWidth = box.width() / 2;
            final Pos position = entity.getPosition();
            return PlayerActionListener.beyondReach(player, player.getAttributeValue(Attribute.ENTITY_INTERACTION_RANGE),
                    position.x() - halfWidth, position.y(), position.z() - halfWidth,
                    position.x() + halfWidth, position.y() + box.height(), position.z() + halfWidth);
        }
        return false;
    }
}