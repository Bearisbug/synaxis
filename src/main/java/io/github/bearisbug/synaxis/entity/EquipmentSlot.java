package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static io.github.bearisbug.synaxis.inventory.PlayerInventoryUtils.BOOTS_SLOT;
import static io.github.bearisbug.synaxis.inventory.PlayerInventoryUtils.CHESTPLATE_SLOT;
import static io.github.bearisbug.synaxis.inventory.PlayerInventoryUtils.HELMET_SLOT;
import static io.github.bearisbug.synaxis.inventory.PlayerInventoryUtils.LEGGINGS_SLOT;

public enum EquipmentSlot {
    MAIN_HAND(0, 0, "mainhand", false, -1),
    OFF_HAND(5, 1, "offhand", false, -1),
    BOOTS(1, 2, "feet", true, BOOTS_SLOT),
    LEGGINGS(2, 3, "legs", true, LEGGINGS_SLOT),
    CHESTPLATE(3, 4, "chest", true, CHESTPLATE_SLOT),
    HELMET(4, 5, "head", true, HELMET_SLOT),
    BODY(6, 6, "body", false, -1),
    SADDLE(7, 7, "saddle", false, -1);

    private static final List<EquipmentSlot> ARMORS = List.of(BOOTS, LEGGINGS, CHESTPLATE, HELMET);
    private static final Map<String, EquipmentSlot> BY_NBT_NAME = Arrays.stream(values())
            .collect(Collectors.toMap(EquipmentSlot::nbtName, slot -> slot));
    private static final Map<Integer, EquipmentSlot> BY_PROTOCOL_ID = Arrays.stream(values())
            .collect(Collectors.toMap(EquipmentSlot::protocolId, slot -> slot));
    private static final Map<Integer, EquipmentSlot> BY_LEGACY_PROTOCOL_ID = Arrays.stream(values())
            .collect(Collectors.toMap(EquipmentSlot::legacyProtocolId, slot -> slot));

    public static final NetworkBuffer.Type<EquipmentSlot> NETWORK_TYPE = NetworkBuffer.VAR_INT
            .transform(id -> Objects.requireNonNull(BY_PROTOCOL_ID.get(id), () -> "unknown equipment slot id: " + id),
                    EquipmentSlot::protocolId);
    public static final Codec<EquipmentSlot> CODEC = Codec.STRING
            .transform(name -> Objects.requireNonNull(BY_NBT_NAME.get(name),
                    () -> "unknown equipment slot: " + name + ", expected one of " + BY_NBT_NAME.keySet()), EquipmentSlot::nbtName);

    public static List<EquipmentSlot> armors() {
        return ARMORS;
    }

    /**
     * Gets the slot of a slot number of the {@code set_equipment} packet ({@code EntityEquipmentPacket}).
     *
     * @param legacyProtocolId the slot number, 0 to 7
     * @return the slot
     * @throws IllegalStateException if no slot has this number
     * @see #legacyProtocolId()
     */
    public static EquipmentSlot fromLegacyProtocolId(int legacyProtocolId) {
        final EquipmentSlot slot = BY_LEGACY_PROTOCOL_ID.get(legacyProtocolId);
        if (slot != null) return slot;

        throw new IllegalStateException("Unexpected value: " + legacyProtocolId);
    }

    private final int protocolId;
    private final int legacyProtocolId;
    private final String nbtName;
    private final boolean armor;
    private final int armorSlot;

    EquipmentSlot(int protocolId, int legacyProtocolId, String nbtName, boolean armor, int armorSlot) {
        this.protocolId = protocolId;
        this.legacyProtocolId = legacyProtocolId;
        this.nbtName = nbtName;
        this.armor = armor;
        this.armorSlot = armorSlot;
    }

    public int protocolId() {
        return protocolId;
    }

    /**
     * Gets the slot number of the {@code set_equipment} packet ({@code EntityEquipmentPacket}), 0 to 7. The item
     * components encode the slot with {@link #protocolId()} instead.
     *
     * @return the slot number in {@code set_equipment}
     */
    public int legacyProtocolId() {
        return legacyProtocolId;
    }

    public String nbtName() {
        return nbtName;
    }

    public boolean isHand() {
        return this == MAIN_HAND || this == OFF_HAND;
    }

    public boolean isArmor() {
        return armor;
    }

    /**
     * Gets the slot of the {@code PlayerInventory} holding this armor piece.
     *
     * @return the inventory slot, -1 when this is not an armor slot ({@link #isArmor()} is false)
     */
    public int armorSlot() {
        return armorSlot;
    }
}
