package io.github.bearisbug.synaxis.entity.damage;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.entity.LivingEntity;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.EventFilter;
import io.github.bearisbug.synaxis.event.entity.EntityDamageEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class DamageIntegrationTest {

    @Test
    public void fromPlayerIsAPlayerAttackAndFromEntityAMobAttack(Env env) {
        Player player = env.createPlayer(env.createFlatInstance(), new Pos(0, 40, 0));
        assertEquals(DamageType.PLAYER_ATTACK, Damage.fromPlayer(player, 1).getType());
        assertEquals(DamageType.MOB_ATTACK, Damage.fromEntity(new Entity(EntityType.ZOMBIE), 1).getType());
    }

    /** The vanilla death messages take the killed player, then the killer when there is one. */
    @Test
    public void deathMessagesNameTheKillerLikeVanilla(Env env) {
        final Instance instance = env.createFlatInstance();
        Player victim = env.createConnection(new GameProfile(UUID.randomUUID(), "Victim")).connect(instance, new Pos(0, 40, 0));
        Player killer = env.createConnection(new GameProfile(UUID.randomUUID(), "Killer")).connect(instance, new Pos(2, 40, 0));
        final LivingEntity zombie = new LivingEntity(EntityType.ZOMBIE);

        final List<Component> generic = arguments(new Damage(DamageType.GENERIC, null, null, null, 1).buildDeathMessage(victim));
        assertEquals(List.of(Component.text("Victim")), generic);

        final Damage melee = new Damage(DamageType.PLAYER_ATTACK, killer, killer, null, 1);
        assertEquals(List.of(Component.text("Victim"), killer.getName()), arguments(melee.buildDeathMessage(victim)));
        assertEquals(melee.buildDeathMessage(victim), melee.buildDeathScreenText(victim),
                "the death screen shows the death message");

        assertEquals(List.of(Component.text("Victim"), Component.translatable("entity.minecraft.zombie")),
                arguments(Damage.fromEntity(zombie, 1).buildDeathMessage(victim)));
    }

    private static List<Component> arguments(@Nullable Component message) {
        final TranslatableComponent translatable = assertInstanceOf(TranslatableComponent.class, message);
        assertTrue(translatable.key().startsWith("death.attack."), translatable.key());
        return translatable.arguments().stream().map(TranslationArgument::asComponent).toList();
    }

    @Test
    public void aNaNAmountIsRefusedAndAnInfiniteOneKills(Env env) {
        assertThrows(IllegalArgumentException.class, () -> new Damage(DamageType.GENERIC, null, null, null, Float.NaN));
        final Damage damage = new Damage(DamageType.GENERIC, null, null, null, 1);
        assertThrows(IllegalArgumentException.class, () -> damage.setAmount(Float.NaN));
        assertEquals(1, damage.getAmount());

        final LivingEntity zombie = new LivingEntity(EntityType.ZOMBIE);
        zombie.setInstance(env.createFlatInstance(), new Pos(0, 42, 0)).join();
        assertDoesNotThrow(() -> zombie.damage(new Damage(DamageType.GENERIC, null, null, null, Float.POSITIVE_INFINITY)));
        assertTrue(zombie.isDead(), "an infinite damage is a kill");
        assertEquals(0, zombie.getHealth());
    }

    /**
     * The type of a damage is looked up before the damage event: with the hurt animation turned off by a listener, a
     * type the registry does not know went through and the health dropped, until the death screen threw on it.
     */
    @Test
    public void anUnregisteredDamageTypeIsRefusedBeforeTheEvent(Env env) {
        final LivingEntity zombie = new LivingEntity(EntityType.ZOMBIE);
        zombie.setInstance(env.createFlatInstance(), new Pos(0, 42, 0)).join();
        final float health = zombie.getHealth();
        env.process().eventHandler().addListener(EntityDamageEvent.class, event -> event.setAnimation(false));
        final Collector<EntityDamageEvent> events = env.trackEvent(EntityDamageEvent.class, EventFilter.ENTITY, zombie);

        final Damage unknown = new Damage(RegistryKey.unsafeOf("synaxis:not_registered"), null, null, null, 5);
        assertThrows(IllegalArgumentException.class, () -> zombie.damage(unknown));

        events.assertEmpty();
        assertEquals(health, zombie.getHealth());
        assertNull(zombie.getLastDamageSource());
    }
}
