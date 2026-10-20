package io.github.bearisbug.synaxis.entity.damage;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.LivingEntity;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.sound.SoundEvent;
import io.github.bearisbug.synaxis.tag.TagHandler;
import io.github.bearisbug.synaxis.tag.Taggable;
import io.github.bearisbug.synaxis.utils.validate.Check;
import org.jetbrains.annotations.Nullable;

/**
 * Represents one infliction of damage: its type, direct source, attacker, source position and amount. Required when
 * calling {@link LivingEntity#damage(Damage)} and retrieved in
 * {@link io.github.bearisbug.synaxis.event.entity.EntityDamageEvent}.
 * <p>
 * This class can be extended if you need to include custom fields and/or methods.
 */
public class Damage implements Taggable {
    private final RegistryKey<DamageType> typeKey;
    private final Entity source;
    private final Entity attacker;
    private final Point sourcePosition;
    private final TagHandler tagHandler = TagHandler.newHandler();

    private float amount;

    /**
     * Creates a new damage.
     *
     * @param type           the type of this damage
     * @param source         the entity that directly causes the damage, null if none: for a melee hit the attacker
     *                       itself, for a projectile the projectile (the arrow, not the archer)
     * @param attacker       the entity that initiated the damage, null if none: for a melee hit the same as
     *                       {@code source}, for a projectile its shooter
     * @param sourcePosition the position of the source of damage, null if none
     * @param amount         amount of damage, negative heals
     * @throws IllegalArgumentException if {@code amount} is NaN
     */
    public Damage(RegistryKey<DamageType> type, @Nullable Entity source, @Nullable Entity attacker, @Nullable Point sourcePosition, float amount) {
        Check.argCondition(Float.isNaN(amount), "Damage amount cannot be NaN");
        this.typeKey = type;
        this.source = source;
        this.attacker = attacker;
        this.sourcePosition = sourcePosition;
        this.amount = amount;
    }

    /**
     * Gets the type of this damage.
     * <p>
     * It does not have to be unique to this object.
     *
     * @return the damage type
     */
    public RegistryKey<DamageType> getType() {
        return typeKey;
    }

    /**
     * Gets the integer id of the damage type that has been set
     *
     * @param registries the registries of the process the damage is applied in
     * @return The integer id of the damage type
     */
    public int getTypeId(Registries registries) {
        final int id = registries.damageType().getId(typeKey);
        Check.argCondition(id == -1, "Damage type is not registered: {0}", typeKey);
        return id;
    }

    /**
     * Gets the "attacker" of the damage.
     * This is the indirect cause of the damage, like the shooter of a projectile, or null if there was none.
     *
     * @return the attacker
     */
    public @Nullable Entity getAttacker() {
        return attacker;
    }

    /**
     * Gets the direct source of the damage.
     * This is the entity that directly causes the damage, like a projectile, or null if there was none.
     *
     * @return the source
     */
    public @Nullable Entity getSource() {
        return source;
    }

    /**
     * Gets the position of the source of the damage, or null if there is none.
     * This may differ from the source entity's position.
     *
     * @return The source position
     */
    public @Nullable Point getSourcePosition() {
        return sourcePosition;
    }

    private DamageType type(Registries registries) {
        final DamageType type = registries.damageType().get(typeKey);
        Check.argCondition(type == null, "Damage type is not registered: {0}", typeKey);
        return type;
    }

    /**
     * Builds the death message linked to this damage type.
     * <p>
     * Used in {@link Player#kill()} to broadcast the proper message. The arguments are the ones of
     * the vanilla messages: the name of the killed player, then the name of the attacker, or of the
     * source when there is no attacker, if there is one. The death message type of the damage type
     * (such as {@code fall_variants}) is not taken into account.
     *
     * @param killed the player who has been killed
     * @return the death message, null to do not send anything
     */
    public @Nullable Component buildDeathMessage(Player killed) {
        final String key = "death.attack." + type(killed.process()).messageId();
        final Component killedName = Component.text(killed.getUsername());
        final Entity killer = getAttacker() != null ? getAttacker() : getSource();
        if (killer == null) return Component.translatable(key, killedName);
        return Component.translatable(key, killedName, nameOf(killer));
    }

    private static Component nameOf(Entity entity) {
        if (entity instanceof Player player) return player.getName();
        final Component customName = entity.get(DataComponents.CUSTOM_NAME);
        return customName != null ? customName : Component.translatable(entity.getEntityType().translationKey());
    }

    /**
     * Convenient method to create an {@link EntityProjectileDamage}.
     *
     * @param shooter    the shooter
     * @param projectile the actual projectile
     * @param amount     amount of damage
     * @return a new {@link EntityProjectileDamage}
     */
    public static Damage fromProjectile(@Nullable Entity shooter, Entity projectile, float amount) {
        return new EntityProjectileDamage(shooter, projectile, amount);
    }

    /**
     * Convenient method to create an {@link EntityDamage} of type {@link DamageType#PLAYER_ATTACK}.
     *
     * @param player the player damager
     * @param amount amount of damage
     * @return a new {@link EntityDamage}
     */
    public static EntityDamage fromPlayer(Player player, float amount) {
        return new EntityDamage(DamageType.PLAYER_ATTACK, player, amount);
    }

    /**
     * Convenient method to create an {@link EntityDamage} of type {@link DamageType#MOB_ATTACK}.
     *
     * @param entity the entity damager
     * @param amount amount of damage
     * @return a new {@link EntityDamage}
     */
    public static EntityDamage fromEntity(Entity entity, float amount) {
        return new EntityDamage(entity, amount);
    }

    public static PositionalDamage fromPosition(RegistryKey<DamageType> type, Point sourcePosition, float amount) {
        return new PositionalDamage(type, sourcePosition, amount);
    }

    /**
     * Builds the text sent to a player in his death screen: the death message, as in vanilla.
     *
     * @param killed the player who has been killed
     * @return the death screen text, null to do not send anything
     * @see #buildDeathMessage(Player)
     */
    public @Nullable Component buildDeathScreenText(Player killed) {
        return buildDeathMessage(killed);
    }

    /**
     * Sound event to play when the given entity is hit by this damage. Possible to return null if no sound should be played
     *
     * @param entity the entity hit by this damage
     * @return the sound to play when the given entity is hurt by this damage type. Can be null if no sound should play
     */
    public @Nullable SoundEvent getSound(LivingEntity entity) {
        if (entity instanceof Player player) {
            return getPlayerSound(player);
        }
        return getGenericSound(entity);
    }

    protected SoundEvent getGenericSound(LivingEntity entity) {
        return SoundEvent.ENTITY_GENERIC_HURT;
    }

    protected SoundEvent getPlayerSound(Player player) {
        if (DamageType.ON_FIRE.equals(typeKey)) return SoundEvent.ENTITY_PLAYER_HURT_ON_FIRE;
        return SoundEvent.ENTITY_PLAYER_HURT;
    }

    @Override
    public TagHandler tagHandler() {
        return tagHandler;
    }

    public float getAmount() {
        return amount;
    }

    /**
     * Changes the amount of damage.
     *
     * @param amount the new amount, negative heals
     * @throws IllegalArgumentException if {@code amount} is NaN
     */
    public void setAmount(float amount) {
        Check.argCondition(Float.isNaN(amount), "Damage amount cannot be NaN");
        this.amount = amount;
    }
}
