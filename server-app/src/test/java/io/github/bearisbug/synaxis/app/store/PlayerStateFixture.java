package io.github.bearisbug.synaxis.app.store;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.inventory.PlayerInventory;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.testing.Env;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.github.bearisbug.synaxis.app.store.StoreFixture.await;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.connect;
import static io.github.bearisbug.synaxis.app.store.StoreFixture.spawn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the tests of {@code synaxis:player} share: a store with the section installed on a node standing where the
 * plugin host's node is, players that log in and out the way the admission gate and a real disconnect have them, and
 * the state such a player is put in.
 */
final class PlayerStateFixture {

    /** Where {@link StoreFixture#enterPlay} puts a player's respawn point, which is where it first spawns. */
    static final Pos RESPAWN = new Pos(0, 42, 0);

    private PlayerStateFixture() {
    }

    /**
     * A store in memory with {@code synaxis:player} installed under the given rule.
     *
     * @param store   the store
     * @param backend its memory, which a test reads and seeds directly
     * @param section the section
     */
    record Memory(PlayerStore store, MemoryBackend backend, PlayerStateSection section) {
    }

    static Memory memory(Env env, PositionRule rule, PlayerStore.Options options) {
        final MemoryBackend backend = new MemoryBackend();
        final PlayerStore store = new PlayerStore(env.process(), backend, RedisLink.disabled("memory"), "memory", false,
                options, (_, _) -> {
        });
        final EventNode<Event> node = hostNode(env);
        store.attach(node);
        return new Memory(store, backend, install(env, store, node, rule));
    }

    /** A node where the plugin host's {@code synaxis-host} is: a child of the root at priority 0. */
    static EventNode<Event> hostNode(Env env) {
        final EventNode<Event> node = EventNode.all("synaxis-host");
        env.process().eventHandler().addChild(node);
        return node;
    }

    /** Installs the section on a store whose listeners are on {@code node}, the way {@code Main} does in its steps (4) and (5). */
    static PlayerStateSection install(Env env, PlayerStore store, EventNode<Event> node, PositionRule rule) {
        final PlayerStateSection section = new PlayerStateSection(env.process().registries());
        section.install(store, node);
        section.useRule(rule);
        return section;
    }

    /** Connects, loads, admits and spawns a player, the way the admission gate does. */
    static Player online(Env env, PlayerStore store, Instance instance, UUID account, String name) {
        final Player player = connect(env.process(), account, name);
        assertTrue(store.load(player), "loaded");
        assertTrue(store.admitted(player), "admitted");
        spawn(env.process(), player, instance);
        return player;
    }

    /** Disconnects a player in the world, the way its connection closing does, and waits for its logout save. */
    static void leave(Env env, PlayerStore store, Player player) {
        player.getPlayerConnection().disconnect();
        await(env, () -> store.versionOf(player) == -1, "the logout save of " + player.getUsername());
    }

    /** Puts a player in a state, on the tick thread it belongs to. */
    static void play(Player player, Pos position, int heldSlot, float health, Map<Integer, ItemStack> inventory) {
        player.acquirable().sync(_ -> {
            player.teleport(position).join();
            player.setHeldItemSlot((byte) heldSlot);
            player.setHealth(health);
            final PlayerInventory slots = player.getInventory();
            slots.clear();
            inventory.forEach(slots::setItemStack);
        });
    }

    /** The 46 slots of a player's inventory, air included. */
    static List<ItemStack> slots(Player player) {
        return List.of(player.getInventory().getItemStacks());
    }

    /** The 46 slots holding {@code inventory}, air elsewhere. */
    static List<ItemStack> slots(Map<Integer, ItemStack> inventory) {
        final List<ItemStack> all = new ArrayList<>();
        for (int slot = 0; slot < PlayerInventory.INVENTORY_SIZE; slot++) all.add(inventory.getOrDefault(slot, ItemStack.AIR));
        return all;
    }

    /**
     * Seeds a character's {@code synaxis:player} as a stored row, as a previous session would have left it.
     *
     * @return the character id
     */
    static long seed(MemoryBackend backend, UUID account, String json) throws StoreBackend.FencedException {
        final StoreBackend.LoadOutcome.Acquired character = (StoreBackend.LoadOutcome.Acquired) backend.load(account, "Seeded", 0);
        backend.write(0, List.of(new StoreBackend.Write(character.characterId(), character.version(),
                Map.of(PlayerStateSection.ID, new StoreBackend.StoredSection(1, json)), false)));
        return character.characterId();
    }

    /** The stored {@code synaxis:player} of a character, null without one. */
    static @Nullable String stored(MemoryBackend backend, UUID account) {
        final StoreBackend.StoredSection section =
                ((StoreBackend.LoadOutcome.Acquired) backend.load(account, "Reader", 0)).sections().get(PlayerStateSection.ID);
        return section == null ? null : section.json();
    }

    /** A saved state at {@code position} in {@code world}, with one dirt in hotbar slot 4, held slot 4 and health 9. */
    static String saved(String world, Pos position) {
        return "{\"world\": " + world + ", \"pos\": {\"x\": " + position.x() + ", \"y\": " + position.y() + ", \"z\": "
                + position.z() + ", \"yaw\": " + position.yaw() + ", \"pitch\": " + position.pitch() + "}, "
                + "\"inventory\": [{\"slot\": 4, \"item\": {\"id\": \"minecraft:dirt\", \"count\": 1}}], "
                + "\"held_slot\": 4, \"health\": 9.0}";
    }

    /** That saved state applied: one dirt in hotbar slot 4, held slot 4 and health 9. */
    static void assertSavedStateApplied(Player player, String what) {
        assertEquals(slots(Map.of(4, ItemStack.of(Material.DIRT))), slots(player),
                what + ": the inventory");
        assertEquals(4, player.getHeldSlot(), what + ": the held slot");
        assertEquals(9f, player.getHealth(), what + ": the health");
    }
}
