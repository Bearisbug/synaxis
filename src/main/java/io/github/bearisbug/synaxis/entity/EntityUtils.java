package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.metadata.AgeableMobMeta;

import java.util.List;
import java.util.Set;

public final class EntityUtils {

    /**
     * Gets the position offset at which a passenger rides the given vehicle.
     *
     * @param vehicle the target vehicle
     * @param passenger the target passenger
     * @param passengerIndex the index of the passenger in the vehicle's passenger list
     * @return the height offset for the passenger of this vehicle
     */
    public static Point getPassengerPositionOffset(Entity vehicle, Entity passenger, int passengerIndex) {
        return unrotatedOffset(vehicle, passenger, passengerIndex)
                .rotateAroundY(Math.toRadians(-vehicle.getPosition().yaw()));
    }

    /** The offset of {@link #getPassengerPositionOffset} for a vehicle facing yaw 0. */
    private static Vec unrotatedOffset(Entity vehicle, Entity passenger, int passengerIndex) {
        // Special cases: Boats, Rafts, Camel, Happy Ghast
        EntityType vehicleType = vehicle.getEntityType();
        EntityType passengerType = passenger.getEntityType();
        double animalOffset = ANIMALS.contains(passengerType) ? 0.2 : 0;
        if (vehicleType == EntityType.CAMEL || vehicleType == EntityType.CAMEL_HUSK) {
            Vec vehicleOffset = firstAttachmentOrDefault(passengerType, "VEHICLE", Vec.ZERO);
            // Check pose
            boolean isSitting = vehicle.getPose() == EntityPose.SITTING;
            boolean isBaby = vehicle.getEntityMeta() instanceof AgeableMobMeta ageableMeta && ageableMeta.isBaby();
            // Calculate height
            double yOffset = isBaby ? 0.09375 : 0.375;
            double height = isBaby ? 1.4 : vehicleType.height() - yOffset;
            if (isSitting) {
                // Some magic constants, somewhat unavoidable since these are not stored in entity data
                if (isBaby) {
                    height -= 0.855;
                } else {
                    height -= 1.23f;
                }
            }
            return new Vec(0, height, (passengerIndex == 0 ? 0.5 : -0.7) + animalOffset).sub(vehicleOffset);
        } else if (vehicleType == EntityType.HAPPY_GHAST) {
            List<Vec> positions = vehicleType.entityAttachments("PASSENGER");
            Vec passengerOffset = positions != null && !positions.isEmpty()
                    ? positions.get(Math.min(passengerIndex, positions.size() - 1)) : Vec.ZERO;
            Vec vehicleOffset = firstAttachmentOrDefault(passengerType, "VEHICLE", Vec.ZERO);
            return passengerOffset.sub(vehicleOffset);
        } else if (vehicleType.key().value().contains("boat")) {
            // As AbstractBoat: a single passenger sits in the middle, or forward of the chest of a chest boat;
            // two sit front and back whatever the boat
            final double z;
            if (vehicle.getPassengers().size() == 1) {
                z = CHEST_BOATS.contains(vehicleType) ? 0.15 : 0;
            } else {
                z = (passengerIndex == 0 ? 0.2 : -0.6) + animalOffset;
            }
            return new Vec(0, vehicleType.height() / 3f, z);
        } else if (vehicleType.key().value().contains("raft")) {
            return new Vec(0, vehicleType.height() / 0.8888f, passengerIndex == 0 ? 0.2 : -0.6);
        } else {
            // Passenger position offset is a combination of vehicle passenger position and passenger vehicle position (if it exists)
            Vec passengerOffset = firstAttachmentOrDefault(
                    vehicleType, "PASSENGER", new Vec(0, vehicleType.height(), 0));
            Vec vehicleOffset = firstAttachmentOrDefault(passengerType, "VEHICLE", Vec.ZERO);
            return passengerOffset.sub(vehicleOffset);
        }
    }

    private static Vec firstAttachmentOrDefault(EntityType entityType, String attachmentName, Vec defaultValue) {
        final List<Vec> attachments = entityType.entityAttachments(attachmentName);
        return attachments == null ? defaultValue : attachments.getFirst();
    }

    private static final Set<EntityType> CHEST_BOATS = Set.of(
            EntityType.ACACIA_CHEST_BOAT,
            EntityType.BIRCH_CHEST_BOAT,
            EntityType.CHERRY_CHEST_BOAT,
            EntityType.DARK_OAK_CHEST_BOAT,
            EntityType.JUNGLE_CHEST_BOAT,
            EntityType.MANGROVE_CHEST_BOAT,
            EntityType.OAK_CHEST_BOAT,
            EntityType.PALE_OAK_CHEST_BOAT,
            EntityType.SPRUCE_CHEST_BOAT
    );

    private static final Set<EntityType> ANIMALS = Set.of(
            EntityType.ARMADILLO,
            EntityType.AXOLOTL,
            EntityType.BEE,
            EntityType.CAMEL,
            EntityType.CAMEL_HUSK,
            EntityType.CAT,
            EntityType.CHICKEN,
            EntityType.COW,
            EntityType.DONKEY,
            EntityType.FOX,
            EntityType.FROG,
            EntityType.GOAT,
            EntityType.HAPPY_GHAST,
            EntityType.HOGLIN,
            EntityType.HORSE,
            EntityType.LLAMA,
            EntityType.MOOSHROOM,
            EntityType.MULE,
            EntityType.NAUTILUS,
            EntityType.OCELOT,
            EntityType.PANDA,
            EntityType.PARROT,
            EntityType.PIG,
            EntityType.POLAR_BEAR,
            EntityType.RABBIT,
            EntityType.SHEEP,
            EntityType.SKELETON_HORSE,
            EntityType.SNIFFER,
            EntityType.STRIDER,
            EntityType.TRADER_LLAMA,
            EntityType.TURTLE,
            EntityType.WOLF,
            EntityType.ZOMBIE_HORSE,
            EntityType.ZOMBIE_NAUTILUS
    );

    private EntityUtils() {}
}
