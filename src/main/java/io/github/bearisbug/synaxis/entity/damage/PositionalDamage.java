package io.github.bearisbug.synaxis.entity.damage;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.registry.RegistryKey;

/**
 * Represents damage that is associated with a certain position.
 */
public class PositionalDamage extends Damage {

    public PositionalDamage(RegistryKey<DamageType> type, Point sourcePosition, float amount) {
        super(type, null, null, sourcePosition, amount);
    }

}