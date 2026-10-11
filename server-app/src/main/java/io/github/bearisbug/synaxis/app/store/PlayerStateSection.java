package io.github.bearisbug.synaxis.app.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.bearisbug.synaxis.codec.Transcoder;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.entity.attribute.Attribute;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.event.player.PlayerDisconnectEvent;
import io.github.bearisbug.synaxis.event.player.PlayerSpawnEvent;
import io.github.bearisbug.synaxis.inventory.PlayerInventory;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.plugin.DataSection;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.registry.RegistryTranscoder;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import io.github.bearisbug.synaxis.tag.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The host's player section {@code synaxis:player} ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-047},
 * {@code API-PROD-032}, §12.5): the position and the world it is in, the 46 inventory slots, the held slot and the
 * health of a player, stored with the plugin sections of its character.
 * <p>
 * The store loads it with the other sections. It is applied once, at the player's first spawn, and only from then on
 * captured, at the start of every snapshot: a player that leaves between its admission and its first spawn carries
 * the engine's starting state, not its own, and leaves the stored section as it is. A capture equal to the value the
 * section already has is not marked dirty, so a player that did nothing is not written.
 */
public final class PlayerStateSection implements DataSection<PlayerState> {

    /** The id of the section, under the host's reserved plugin id. */
    public static final String ID = "synaxis:player";

    /**
     * Set once the saved state is applied at the first spawn; only then is the state captured. In memory only, it goes
     * with the {@code Player}. Not a {@code synaxis:} name, which the translation key and configuration key gates would
     * take for a key.
     */
    static final Tag<Boolean> APPLIED = Tag.Boolean("player-state.applied");

    private static final int SCHEMA_VERSION = 1;
    private static final int HOTBAR_SIZE = 9;

    private final Transcoder<JsonElement> json;
    // The players that arrived with a transfer ticket, until their first spawn or their disconnect
    private final Set<Player> claimed = ConcurrentHashMap.newKeySet();
    private volatile @Nullable PlayerStore store;
    private volatile @Nullable PositionRule rule;

    /**
     * Creates the section of this process.
     *
     * @param registries the process registries, which an item component that names a registry entry is encoded with
     */
    public PlayerStateSection(Registries registries) {
        this.json = new RegistryTranscoder<>(Transcoder.JSON, registries);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int schemaVersion() {
        return SCHEMA_VERSION;
    }

    @Override
    public PlayerState createDefault() {
        return PlayerState.DEFAULT;
    }

    /**
     * Registers the section with the store and hooks the first spawn and the disconnect, on the startup thread
     * ({@code Main} step (4)): after the store and before the plugins are enabled and the default play layer is
     * attached, so the host node runs this listener first among its own.
     *
     * @param store    the player store
     * @param hostNode the plugin host's node {@code synaxis-host}
     */
    public void install(PlayerStore store, EventNode<Event> hostNode) {
        store.registerHostSection(this, this::capture);
        this.store = store;
        hostNode.addListener(PlayerSpawnEvent.class, this::apply);
        hostNode.addListener(PlayerDisconnectEvent.class, event -> claimed.remove(event.getPlayer()));
    }

    /**
     * Sets the position rule of the mode, on the startup thread ({@code Main} step (5)) and before the port is bound.
     *
     * @param rule the rule
     * @throws IllegalStateException if a rule is set already
     */
    public synchronized void useRule(PositionRule rule) {
        if (this.rule != null) throw new IllegalStateException("the position rule is set already: " + this.rule);
        this.rule = rule;
    }

    /**
     * Whether a player plays a character nothing was ever captured for, which the starter kit of the default play
     * layer asks at the first spawn, on the tick scheduler thread.
     *
     * @param player the player
     * @return whether its {@code synaxis:player} is still the default
     */
    public boolean isFresh(Player player) {
        return store().hostValue(player, this).isDefault();
    }

    /**
     * Notes a player whose transfer ticket was accepted, on its configuration thread: it stays at the arrival point
     * the shard set as its respawn point instead of going to its saved position.
     *
     * @param player the player
     */
    public void claimed(Player player) {
        claimed.add(player);
    }

    /**
     * What the snapshots of the store take: nothing before the first spawn applied the saved state.
     */
    private @Nullable PlayerState capture(Player player) {
        if (!Boolean.TRUE.equals(player.getTag(APPLIED))) return null;
        final PositionRule current = rule();
        final PlayerInventory inventory = player.getInventory();
        final Map<Integer, ItemStack> slots = new HashMap<>();
        for (int slot = 0; slot < PlayerInventory.INVENTORY_SIZE; slot++) {
            final ItemStack stack = inventory.getItemStack(slot);
            if (!stack.isAir()) slots.put(slot, stack);
        }
        return new PlayerState(world(current), player.getPosition(), slots, player.getHeldSlot(), player.getHealth());
    }

    private static PlayerState.World world(PositionRule rule) {
        return switch (rule) {
            case PositionRule.Core _ -> new PlayerState.Core();
            case PositionRule.Rts _ -> new PlayerState.Rts();
            case PositionRule.MapShard map -> new PlayerState.MapShard(map.mapId(), map.buildId());
        };
    }

    /**
     * At the first spawn, on the tick scheduler thread in the serial part of the tick: the player is in its instance at
     * the engine's spawn position with its first chunks and has no tick thread yet, so no snapshot of it runs
     * alongside.
     */
    private void apply(PlayerSpawnEvent event) {
        if (!event.isFirstSpawn()) return;
        final Player player = event.getPlayer();
        final PositionRule current = rule();
        final boolean arrived = claimed.remove(player);
        final PlayerState saved = store().hostValue(player, this);
        if (!saved.isDefault()) {
            final PlayerInventory inventory = player.getInventory();
            for (int slot = 0; slot < PlayerInventory.INVENTORY_SIZE; slot++)
                inventory.setItemStack(slot, saved.inventory().getOrDefault(slot, ItemStack.AIR));
            player.setHeldItemSlot((byte) saved.heldSlot());
            if (saved.health() > 0) {
                // Above the maximum it is the maximum: setHealth clamps
                player.setHealth(saved.health());
                if (usesSavedPosition(current, saved, arrived)) {
                    var _ = player.teleport(Objects.requireNonNull(saved.position(), "a captured state has a position"));
                }
            } else {
                // Left on the death screen: a respawn, at the respawn point
                player.setHealth((float) player.getAttributeValue(Attribute.MAX_HEALTH));
            }
        }
        player.setTag(APPLIED, true);
    }

    private static boolean usesSavedPosition(PositionRule rule, PlayerState saved, boolean arrived) {
        return switch (rule) {
            case PositionRule.Core _ -> saved.world() instanceof PlayerState.Core;
            case PositionRule.Rts _ -> false;
            case PositionRule.MapShard map -> !arrived
                    && saved.world() instanceof PlayerState.MapShard(String mapId, String buildId)
                    && mapId.equals(map.mapId()) && buildId.equals(map.buildId())
                    && inside(map.bounds(), Objects.requireNonNull(saved.position(), "a captured state has a position"));
        };
    }

    private static boolean inside(SynxManifest.Bounds bounds, Pos position) {
        return position.chunkX() >= bounds.minX() && position.chunkX() <= bounds.maxX()
                && position.chunkZ() >= bounds.minZ() && position.chunkZ() <= bounds.maxZ();
    }

    @Override
    public JsonElement encode(PlayerState value) {
        final JsonObject object = new JsonObject();
        final PlayerState.World savedWorld = Objects.requireNonNull(value.world(), "the default state is never stored");
        final JsonObject world = new JsonObject();
        world.addProperty("kind", switch (savedWorld) {
            case PlayerState.Core _ -> "core";
            case PlayerState.Rts _ -> "rts";
            case PlayerState.MapShard _ -> "map";
        });
        if (savedWorld instanceof PlayerState.MapShard(String mapId, String buildId)) {
            world.addProperty("map_id", mapId);
            world.addProperty("build_id", buildId);
        }
        object.add("world", world);
        final Pos position = Objects.requireNonNull(value.position(), "the default state is never stored");
        final JsonObject pos = new JsonObject();
        pos.addProperty("x", position.x());
        pos.addProperty("y", position.y());
        pos.addProperty("z", position.z());
        pos.addProperty("yaw", position.yaw());
        pos.addProperty("pitch", position.pitch());
        object.add("pos", pos);
        final JsonArray inventory = new JsonArray();
        for (Map.Entry<Integer, ItemStack> slot : new TreeMap<>(value.inventory()).entrySet()) {
            final JsonObject entry = new JsonObject();
            entry.addProperty("slot", slot.getKey());
            entry.add("item", ItemStack.CODEC.encode(json, slot.getValue()).orElseThrow("slot " + slot.getKey()));
            inventory.add(entry);
        }
        object.add("inventory", inventory);
        object.addProperty("held_slot", value.heldSlot());
        object.addProperty("health", value.health());
        return object;
    }

    /**
     * Decodes a stored section; a field that is missing, out of its range or not a finite number, a slot given twice
     * and an item that does not decode all refuse the login ({@code API-PROD-005}).
     *
     * @throws RuntimeException for every one of those
     */
    @Override
    public PlayerState decode(JsonElement stored, int storedVersion) {
        final JsonObject object = stored.getAsJsonObject();
        final JsonObject world = field(object, "world").getAsJsonObject();
        final String kind = field(world, "kind").getAsString();
        final PlayerState.World decodedWorld = switch (kind) {
            case "core" -> new PlayerState.Core();
            case "rts" -> new PlayerState.Rts();
            case "map" -> new PlayerState.MapShard(field(world, "map_id").getAsString(),
                    field(world, "build_id").getAsString());
            default -> throw new IllegalArgumentException("world.kind " + kind + " is not core, rts or map");
        };
        final JsonObject pos = field(object, "pos").getAsJsonObject();
        final Pos position = new Pos(finite(pos, "x"), finite(pos, "y"), finite(pos, "z"),
                (float) finite(pos, "yaw"), (float) finite(pos, "pitch"));
        final Map<Integer, ItemStack> inventory = new HashMap<>();
        for (JsonElement element : field(object, "inventory").getAsJsonArray()) {
            final JsonObject entry = element.getAsJsonObject();
            final int slot = field(entry, "slot").getAsInt();
            if (slot < 0 || slot >= PlayerInventory.INVENTORY_SIZE)
                throw new IllegalArgumentException("inventory slot " + slot + " is not 0.." + (PlayerInventory.INVENTORY_SIZE - 1));
            final ItemStack stack = ItemStack.CODEC.decode(json, field(entry, "item")).orElseThrow("inventory slot " + slot);
            if (stack.isAir()) throw new IllegalArgumentException("inventory slot " + slot + " holds air");
            if (inventory.put(slot, stack) != null)
                throw new IllegalArgumentException("inventory slot " + slot + " is there twice");
        }
        final int heldSlot = field(object, "held_slot").getAsInt();
        if (heldSlot < 0 || heldSlot >= HOTBAR_SIZE)
            throw new IllegalArgumentException("held_slot " + heldSlot + " is not 0.." + (HOTBAR_SIZE - 1));
        final double health = finite(object, "health");
        if (health < 0) throw new IllegalArgumentException("health " + health + " is negative");
        return new PlayerState(decodedWorld, position, inventory, heldSlot, (float) health);
    }

    private static JsonElement field(JsonObject object, String name) {
        final JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) throw new IllegalArgumentException("no " + name);
        return value;
    }

    private static double finite(JsonObject object, String name) {
        final double value = field(object, name).getAsDouble();
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " " + value + " is not finite");
        return value;
    }

    private PlayerStore store() {
        final PlayerStore installed = store;
        if (installed == null) throw new IllegalStateException(ID + " is not installed");
        return installed;
    }

    private PositionRule rule() {
        final PositionRule current = rule;
        if (current == null) throw new IllegalStateException("no position rule is set for " + ID);
        return current;
    }
}
