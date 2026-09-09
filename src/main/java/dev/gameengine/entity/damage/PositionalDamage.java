package dev.gameengine.entity.damage;

import dev.gameengine.coordinate.Point;
import dev.gameengine.registry.RegistryKey;

/**
 * Represents damage that is associated with a certain position.
 */
public class PositionalDamage extends Damage {

    public PositionalDamage(RegistryKey<DamageType> type, Point sourcePosition, float amount) {
        super(type, null, null, sourcePosition, amount);
    }

}