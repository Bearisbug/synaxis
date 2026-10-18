package io.github.bearisbug.synaxis.app.play;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.ItemEntity;
import io.github.bearisbug.synaxis.entity.LivingEntity;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.entity.attribute.Attribute;
import io.github.bearisbug.synaxis.entity.damage.Damage;
import io.github.bearisbug.synaxis.entity.damage.DamageType;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.event.entity.EntityAttackEvent;
import io.github.bearisbug.synaxis.event.item.ItemDropEvent;
import io.github.bearisbug.synaxis.event.item.PickupItemEvent;
import io.github.bearisbug.synaxis.event.player.PlayerChatEvent;
import io.github.bearisbug.synaxis.event.player.PlayerSpawnEvent;
import io.github.bearisbug.synaxis.event.player.PlayerTickEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.inventory.PlayerInventory;
import io.github.bearisbug.synaxis.inventory.TransactionOption;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.tag.Tag;
import io.github.bearisbug.synaxis.timer.TimeUnit;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The host's default play layer ({@code docs/DESIGN-PROD.md} {@code API-PROD-031}, {@code ADR-PROD-017}): what a
 * player can do in the core world without any plugin, and the chat under a gameplay plugin. The engine has no play
 * rules of its own; it only dispatches the events these listeners act on.
 * <p>
 * Every listener sits on the host node, in two stages. The decisions run on the host node itself, after the root node
 * and the instance and entity scoped listeners and before the global scope plugin listeners below it, so a plugin sees
 * the default outcome and can change or cancel it. Changing the world on a cancellable event, spawning a dropped item
 * or taking a picked up one into the inventory, waits for {@code synaxis-play-apply}, the child of the host node at
 * priority 5 after {@code MONITOR}, and is done only for an event nobody cancelled by then: done before a plugin
 * cancels the event, it would copy the item. Both stages skip an event that reaches them cancelled, which is how the
 * store's freeze ({@code API-PROD-005}) stops them.
 */
public final class DefaultPlay {

    /**
     * What the layer can turn on, each one independently.
     */
    public enum Feature {
        /** Fills empty chat recipients with the players of the sender's instance. */
        CHAT,
        /** Spawns an item entity for a drop. */
        DROP,
        /** Takes a picked up stack into the player's inventory. */
        PICKUP,
        /** Hurts and knocks back the living entity a player attacks. */
        MELEE,
        /** Hurts a player below the void line every tick. */
        VOID_DAMAGE,
        /** Gives a new character stone and an iron pickaxe on its first spawn. */
        STARTER_KIT,
        /** Keeps a training dummy beside the spawn of the core world. */
        TRAINING_DUMMY
    }

    /**
     * The child of the host node the world changing half of {@link Feature#DROP} and {@link Feature#PICKUP} runs in.
     */
    static final String APPLY_NODE = "synaxis-play-apply";

    /**
     * After {@code MONITOR} (4), the store's freeze (3) and the plugin priorities (−2..2) below the host node
     * ({@code API-PROD-018}).
     */
    static final int APPLY_PRIORITY = 5;

    /**
     * How long a dropped item cannot be picked up, 2 s.
     */
    private static final int PICKUP_DELAY_TICKS = 40;

    /**
     * How fast a dropped item leaves the eyes, in blocks a second: 0.3 a tick.
     */
    private static final double DROP_SPEED = 6;

    /**
     * The tick of the target, in its alive ticks, when a melee hit last landed on it. Not a {@code synaxis:} name, which
     * the translation key and configuration key gates would take for a key.
     */
    static final Tag<Long> MELEE_TICK = Tag.Long("default-play.melee-tick");

    /**
     * How many of its own ticks a target is safe from the next melee hit, whoever lands it.
     */
    private static final long MELEE_COOLDOWN_TICKS = 10;

    /**
     * The vanilla knockback of a hit without a knockback enchantment.
     */
    private static final float KNOCKBACK = 0.4f;

    /**
     * What a player below the void line loses every tick.
     */
    private static final float VOID_DAMAGE_PER_TICK = 4;

    private DefaultPlay() {
    }

    /**
     * Attaches the given features. On the startup thread, after step (5) decided the mode and before the port is bound,
     * at most once a process.
     *
     * @param process        the engine process
     * @param hostNode       the plugin host's node {@code synaxis-host}
     * @param features       what to turn on
     * @param freshCharacter whether a player at its first spawn plays a new character, which {@link Feature#STARTER_KIT}
     *                       asks on the tick scheduler thread
     * @param coreWorld      the core world, where {@link Feature#TRAINING_DUMMY} stands; null without that feature
     * @throws IllegalArgumentException if {@code features} has {@link Feature#TRAINING_DUMMY} and {@code coreWorld} is
     *                                  null
     */
    public static void attach(ServerProcess process, EventNode<Event> hostNode, Set<Feature> features,
                              Predicate<Player> freshCharacter, @Nullable Instance coreWorld) {
        if (features.contains(Feature.TRAINING_DUMMY) && coreWorld == null)
            throw new IllegalArgumentException("the training dummy needs the core world");
        if (features.contains(Feature.CHAT)) hostNode.addListener(PlayerChatEvent.class, DefaultPlay::fillRecipients);
        if (features.contains(Feature.PICKUP)) hostNode.addListener(PickupItemEvent.class, DefaultPlay::judgePickup);
        if (features.contains(Feature.MELEE)) hostNode.addListener(EntityAttackEvent.class, DefaultPlay::melee);
        if (features.contains(Feature.VOID_DAMAGE))
            hostNode.addListener(PlayerTickEvent.class, DefaultPlay::voidDamage);
        if (features.contains(Feature.STARTER_KIT))
            hostNode.addListener(PlayerSpawnEvent.class, event -> giveStarterKit(event, freshCharacter));
        if (features.contains(Feature.DROP) || features.contains(Feature.PICKUP)) {
            final EventNode<Event> apply = EventNode.all(APPLY_NODE).setPriority(APPLY_PRIORITY);
            if (features.contains(Feature.DROP)) apply.addListener(ItemDropEvent.class, DefaultPlay::spawnDrop);
            if (features.contains(Feature.PICKUP)) apply.addListener(PickupItemEvent.class, DefaultPlay::applyPickup);
            hostNode.addChild(apply);
        }
        if (features.contains(Feature.TRAINING_DUMMY))
            new TrainingDummy(process, Objects.requireNonNull(coreWorld)).attach(hostNode);
    }

    /**
     * Recipients an instance scoped listener already chose are kept; a sender without an instance gets none.
     */
    private static void fillRecipients(PlayerChatEvent event) {
        final Collection<Player> recipients = event.getRecipients();
        if (!recipients.isEmpty()) return;
        final Instance instance = event.getPlayer().getInstance();
        if (instance != null) recipients.addAll(instance.getPlayers());
    }

    /**
     * Every drop path ends in {@code Player#dropItem}: the Q key on the hotbar or in the inventory, a click outside the
     * inventory, and the cursor item of an inventory that closes. The engine takes the item away once this returns with
     * the event still allowed.
     */
    private static void spawnDrop(ItemDropEvent event) {
        final Player player = event.getPlayer();
        final Instance instance = player.getInstance();
        if (instance == null) {
            event.setCancelled(true);
            return;
        }
        final Pos position = player.getPosition();
        final ItemEntity item = new ItemEntity(event.getItemStack());
        item.setPickupDelay(PICKUP_DELAY_TICKS, TimeUnit.SERVER_TICK);
        item.setVelocity(position.direction().mul(DROP_SPEED));
        // The player stands in a loaded chunk, so this does not wait
        item.setInstance(instance, position.add(0, player.getEyeHeight(), 0)).join();
    }

    /**
     * A stack the inventory cannot take whole stays on the ground; the engine releases the item entity's claim.
     */
    private static void judgePickup(PickupItemEvent event) {
        if (event.getLivingEntity() instanceof Player player
                && !player.getInventory().addItemStack(event.getItemStack(), TransactionOption.DRY_RUN))
            event.setCancelled(true);
    }

    /**
     * A listener between the decision and here may have filled the inventory, which cancels the pickup after all.
     */
    private static void applyPickup(PickupItemEvent event) {
        if (event.getLivingEntity() instanceof Player player
                && !player.getInventory().addItemStack(event.getItemStack(), TransactionOption.ALL_OR_NOTHING))
            event.setCancelled(true);
    }

    /**
     * The attack damage and the yaw are read on the attacker's tick thread; the cooldown, the damage and the knockback
     * are decided on the target's ({@code API-PROD-016}). That is the exception {@code API-PROD-016} makes for a state
     * that has to be read in the same tick: the cooldown and whether the damage landed decide the knockback. The damage
     * type is {@code minecraft:player_attack}; a plugin replaces the melee by cancelling that damage event, which skips
     * the knockback too.
     */
    private static void melee(EntityAttackEvent event) {
        if (!(event.getEntity() instanceof Player attacker)) return;
        if (!(event.getTarget() instanceof LivingEntity target) || target == attacker) return;
        final float amount = (float) attacker.getAttributeValue(Attribute.ATTACK_DAMAGE);
        final double yaw = Math.toRadians(attacker.getPosition().yaw());
        target.acquirable().sync(owned -> {
            if (owned.isDead()) return;
            final long now = owned.getAliveTicks();
            final Long last = owned.getTag(MELEE_TICK);
            if (last != null && now - last < MELEE_COOLDOWN_TICKS) return;
            if (!owned.damage(new Damage(DamageType.PLAYER_ATTACK, attacker, attacker, null, amount))) return;
            owned.setTag(MELEE_TICK, now);
            owned.takeKnockback(KNOCKBACK, Math.sin(yaw), -Math.cos(yaw));
        });
    }

    /**
     * The flat core world has no bedrock: a player who digs through its floor at y 0 falls below the void line.
     */
    private static void voidDamage(PlayerTickEvent event) {
        final Player player = event.getPlayer();
        final Instance instance = player.getInstance();
        if (!player.isDead() && instance != null && instance.isInVoid(player.getPosition()))
            player.damage(DamageType.OUT_OF_WORLD, VOID_DAMAGE_PER_TICK);
    }

    /**
     * Runs after the host node's listeners added before it, such as the one applying the saved player state
     * ({@code API-PROD-032}). The game mode stays the engine's survival.
     */
    private static void giveStarterKit(PlayerSpawnEvent event, Predicate<Player> freshCharacter) {
        final Player player = event.getPlayer();
        if (!event.isFirstSpawn() || !freshCharacter.test(player)) return;
        final PlayerInventory inventory = player.getInventory();
        inventory.setItemStack(0, ItemStack.of(Material.STONE, 64));
        inventory.setItemStack(1, ItemStack.of(Material.IRON_PICKAXE));
    }
}
