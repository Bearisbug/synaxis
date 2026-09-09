package dev.gameengine.listener;

import dev.gameengine.collision.BoundingBox;
import dev.gameengine.coordinate.Pos;
import dev.gameengine.entity.Entity;
import dev.gameengine.entity.LivingEntity;
import dev.gameengine.entity.Player;
import dev.gameengine.entity.attribute.Attribute;
import dev.gameengine.event.entity.EntityAttackEvent;
import dev.gameengine.event.player.PlayerEntityInteractEvent;
import dev.gameengine.instance.Instance;
import dev.gameengine.network.packet.client.play.ClientAttackPacket;
import dev.gameengine.network.packet.client.play.ClientInteractEntityPacket;

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
            final double maxDistanceSquared = Math.pow(player.getAttributeValue(Attribute.ENTITY_INTERACTION_RANGE) + 1, 2);

            final double distSquared = getDistSquared(player, entity);

            return distSquared >= maxDistanceSquared;
        }
        return false;
    }

    private static double getDistSquared(Player player, Entity entity) {
        final Pos playerPos = player.getPosition();
        final double eyeHeight = player.getEyeHeight();
        final double px = playerPos.x();
        final double py = playerPos.y() + eyeHeight;
        final double pz = playerPos.z();

        final BoundingBox box = entity.getBoundingBox();
        final double halfWidth = box.width() / 2;
        final double height = box.height();
        final Pos entityPos = entity.getPosition();

        final double minX = entityPos.x() - halfWidth;
        final double maxX = entityPos.x() + halfWidth;
        final double minY = entityPos.y();
        final double maxY = entityPos.y() + height;
        final double minZ = entityPos.z() - halfWidth;
        final double maxZ = entityPos.z() + halfWidth;

        final double clampX = Math.clamp(px, minX, maxX);
        final double clampY = Math.clamp(py, minY, maxY);
        final double clampZ = Math.clamp(pz, minZ, maxZ);

        final double dx = px - clampX;
        final double dy = py - clampY;
        final double dz = pz - clampZ;
        return dx * dx + dy * dy + dz * dz;
    }
}