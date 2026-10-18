package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityPose;
import io.github.bearisbug.synaxis.entity.EntityType;
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
        // Special cases: Boats, Rafts, Camel, Happy Ghast
        EntityType vehicleType = vehicle.getEntityType();
        if (vehicleType == EntityType.CAMEL || vehicleType == EntityType.CAMEL_HUSK) {
            Vec vehicleOffset = firstAttachmentOrDefault(passenger.getEntityType(), "VEHICLE", Vec.ZERO);
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

            double animalOffset = ANIMALS.contains(passenger.getEntityType()) ? 0.2 : 0;
            if (passengerIndex == 0) {
                return new Vec(0, height, 0.5 + animalOffset).sub(vehicleOffset).rotateAroundY(Math.toRadians(vehicle.getPosition().yaw() * -1));
            } else {
                return new Vec(0, height, -0.7 + animalOffset).sub(vehicleOffset).rotateAroundY(Math.toRadians(vehicle.getPosition().yaw() * -1));
            }
        } else if (vehicleType == EntityType.HAPPY_GHAST) {
            List<Vec> positions = vehicleType.entityAttachments("PASSENGER");
            Vec passengerOffset = positions != null && !positions.isEmpty()
                    ? positions.get(Math.min(passengerIndex, positions.size() - 1)) : Vec.ZERO;
            Vec vehicleOffset = firstAttachmentOrDefault(passenger.getEntityType(), "VEHICLE", Vec.ZERO);
            return passengerOffset.sub(vehicleOffset).rotateAroundY(Math.toRadians(-vehicle.getPosition().yaw()));
        } else if (vehicleType.key().value().contains("boat")) {
            // As AbstractBoat: a single passenger sits in the middle, or forward of the chest of a chest boat;
            // two sit front and back whatever the boat
            final double z;
            if (vehicle.getPassengers().size() == 1) {
                z = CHEST_BOATS.contains(vehicleType) ? 0.15 : 0;
            } else {
                final double animalOffset = ANIMALS.contains(passenger.getEntityType()) ? 0.2 : 0;
                z = (passengerIndex == 0 ? 0.2 : -0.6) + animalOffset;
            }
            return new Vec(0, vehicleType.height() / 3f, z).rotateAroundY(Math.toRadians(vehicle.getPosition().yaw() * -1));
        } else if (vehicleType.key().value().contains("raft")) {
            if (passengerIndex == 0) {
                return new Vec(0, vehicleType.height() / 0.8888f, 0.2).rotateAroundY(Math.toRadians(vehicle.getPosition().yaw() * -1));
            } else {
                return new Vec(0, vehicleType.height() / 0.8888f, -0.6).rotateAroundY(Math.toRadians(vehicle.getPosition().yaw() * -1));
            }
        } else {
            // Passenger position offset is a combination of vehicle passenger position and passenger vehicle position (if it exists)
            Vec passengerOffset = firstAttachmentOrDefault(
                    vehicleType, "PASSENGER", new Vec(0, vehicleType.height(), 0));
            Vec vehicleOffset = firstAttachmentOrDefault(passenger.getEntityType(), "VEHICLE", Vec.ZERO);
            return passengerOffset.sub(vehicleOffset).rotateAroundY(Math.toRadians(-vehicle.getPosition().yaw()));
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
