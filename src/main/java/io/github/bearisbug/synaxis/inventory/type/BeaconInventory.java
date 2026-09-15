package io.github.bearisbug.synaxis.inventory.type;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.inventory.Inventory;
import io.github.bearisbug.synaxis.inventory.InventoryProperty;
import io.github.bearisbug.synaxis.inventory.InventoryType;
import io.github.bearisbug.synaxis.potion.PotionEffect;
import org.jetbrains.annotations.Nullable;

public class BeaconInventory extends Inventory {

    private short powerLevel;
    private @Nullable PotionEffect firstPotionEffect;
    private @Nullable PotionEffect secondPotionEffect;

    public BeaconInventory(Component title) {
        super(InventoryType.BEACON, title);
    }

    public BeaconInventory(String title) {
        super(InventoryType.BEACON, title);
    }

    /**
     * Gets the beacon power level.
     *
     * @return the power level
     */
    public short getPowerLevel() {
        return powerLevel;
    }

    /**
     * Changes the beacon power level.
     *
     * @param powerLevel the new beacon power level
     */
    public void setPowerLevel(short powerLevel) {
        this.powerLevel = powerLevel;
        sendProperty(InventoryProperty.BEACON_POWER_LEVEL, powerLevel);
    }

    /**
     * Gets the first potion effect.
     *
     * @return the first potion effect, can be null
     */
    public @Nullable PotionEffect getFirstPotionEffect() {
        return firstPotionEffect;
    }

    /**
     * Changes the first potion effect.
     *
     * @param firstPotionEffect the new first potion effect, can be null
     */
    public void setFirstPotionEffect(@Nullable PotionEffect firstPotionEffect) {
        this.firstPotionEffect = firstPotionEffect;
        sendProperty(InventoryProperty.BEACON_FIRST_POTION, firstPotionEffect == null ? -1 : (short) firstPotionEffect.id());
    }

    /**
     * Gets the second potion effect.
     *
     * @return the second potion effect, can be null
     */
    public @Nullable PotionEffect getSecondPotionEffect() {
        return secondPotionEffect;
    }

    /**
     * Changes the second potion effect.
     *
     * @param secondPotionEffect the new second potion effect, can be null
     */
    public void setSecondPotionEffect(@Nullable PotionEffect secondPotionEffect) {
        this.secondPotionEffect = secondPotionEffect;
        sendProperty(InventoryProperty.BEACON_SECOND_POTION, secondPotionEffect == null ? -1 : (short) secondPotionEffect.id());
    }
}
