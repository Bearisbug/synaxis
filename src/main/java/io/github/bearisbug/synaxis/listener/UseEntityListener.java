package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.collision.BoundingBox;
import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.LivingEntity;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.entity.attribute.Attribute;
import io.github.bearisbug.synaxis.entity.metadata.other.InteractionMeta;
import io.github.bearisbug.synaxis.event.entity.EntityAttackEvent;
import io.github.bearisbug.synaxis.event.player.PlayerEntityInteractEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.item.component.AttackRange;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientAttackPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientInteractEntityPacket;

public class UseEntityListener {

    /**
     * The blocks vanilla 26.2 allows over the range when it judges using, picking, spectating or attacking an
     * entity.
     */
    private static final double MARGIN = 3.0;

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
        if (entity == null || !entity.isViewer(player) || beyondAttackRange(player, entity))
            return;
        // As vanilla 26.2: a piercing weapon stabs (PlayerStabEvent), its client never attacks with it
        if (player.getItemInMainHand().has(DataComponents.PIERCING_WEAPON))
            return;
        if (entity instanceof LivingEntity livingEntity && livingEntity.isDead()) // Can't attack dead entities
            return;
        player.process().eventHandler().call(new EntityAttackEvent(player, entity));
    }

    /**
     * Whether using, picking or spectating the entity is refused: the player does not see it, or it is out of reach
     * by vanilla 26.2's rule for all three, {@code isWithinEntityInteractionRange(…, 3.0)}: its box is at least the
     * player's {@link Attribute#ENTITY_INTERACTION_RANGE} plus three blocks from the eyes.
     */
    static boolean invalidUse(Player player, Entity entity) {
        if (!entity.isViewer(player))
            return true;
        if (!player.process().config().enforceInteractionLimit())
            return false;
        final double reach = player.getAttributeValue(Attribute.ENTITY_INTERACTION_RANGE) + MARGIN;
        return distanceSquared(player, entity) >= reach * reach;
    }

    /**
     * Whether an attack is out of reach by vanilla 26.2's rule, {@code isWithinAttackRange(…, 3.0)}: the attack range
     * of the item in the main hand, or one from 0 to the player's {@link Attribute#ENTITY_INTERACTION_RANGE} without
     * a margin when the item has none, widened by its margin and three blocks on both ends; the distance from the eyes
     * to the box may be either end.
     */
    static boolean beyondAttackRange(Player player, Entity entity) {
        if (!player.process().config().enforceInteractionLimit())
            return false;
        final AttackRange item = player.getItemInMainHand().get(DataComponents.ATTACK_RANGE);
        final float interaction = (float) player.getAttributeValue(Attribute.ENTITY_INTERACTION_RANGE);
        final AttackRange range = item != null ? item : new AttackRange(0f, interaction, 0f, interaction, 0f, 1f);
        final double distance = Math.sqrt(distanceSquared(player, entity));
        return distance < (double) (range.effectiveMinReach(player) - range.hitboxMargin()) - MARGIN
                || distance > (double) (range.effectiveMaxReach(player) + range.hitboxMargin()) + MARGIN;
    }

    private static double distanceSquared(Player player, Entity entity) {
        // The client hits an interaction entity as wide and high as its metadata says, its bounding box is
        // the 0 by 0 of its type
        final BoundingBox box = entity.getEntityMeta() instanceof InteractionMeta interaction
                ? new BoundingBox(interaction.getWidth(), interaction.getHeight(), interaction.getWidth())
                : entity.getBoundingBox();
        final double halfWidth = box.width() / 2;
        final Pos position = entity.getPosition();
        return PlayerActionListener.distanceSquared(player,
                position.x() - halfWidth, position.y(), position.z() - halfWidth,
                position.x() + halfWidth, position.y() + box.height(), position.z() + halfWidth);
    }
}
