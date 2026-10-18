package io.github.bearisbug.synaxis.entity.damage;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.registry.RegistryKey;

/**
 * Represents damage inflicted by an {@link Entity}.
 */
public class EntityDamage extends Damage {

    /**
     * Creates a damage of type {@link DamageType#MOB_ATTACK}.
     *
     * @param source the entity damager, attacker and source
     * @param amount amount of damage
     */
    public EntityDamage(Entity source, float amount) {
        this(DamageType.MOB_ATTACK, source, amount);
    }

    /**
     * Creates a damage of the given type.
     *
     * @param type   the type of this damage
     * @param source the entity damager, attacker and source
     * @param amount amount of damage
     */
    public EntityDamage(RegistryKey<DamageType> type, Entity source, float amount) {
        super(type, source, source, null, amount);
    }

    /**
     * Gets the source of the damage.
     *
     * @return the source
     */
    @Override
    public Entity getSource() {
        return super.getSource();
    }

    @Override
    public Entity getAttacker() {
        return getSource();
    }
}