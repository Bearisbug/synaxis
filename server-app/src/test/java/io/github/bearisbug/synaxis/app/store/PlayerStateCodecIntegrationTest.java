package io.github.bearisbug.synaxis.app.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.bearisbug.synaxis.component.DataComponentMap;
import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.instance.block.predicate.ComponentPredicateSet;
import io.github.bearisbug.synaxis.instance.block.predicate.DataComponentPredicate;
import io.github.bearisbug.synaxis.instance.block.predicate.DataComponentPredicates;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.item.component.EnchantmentList;
import io.github.bearisbug.synaxis.item.component.PotionContents;
import io.github.bearisbug.synaxis.item.predicate.ItemPredicate;
import io.github.bearisbug.synaxis.potion.CustomPotionEffect;
import io.github.bearisbug.synaxis.potion.PotionEffect;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.tag.Tag;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.utils.Range;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JSON of {@code synaxis:player} ({@code docs/DESIGN-PROD.md} {@code API-PROD-032}, §9): its layout, a round
 * trip of items whose components name registry entries or whose custom data holds typed tags, the layout of schema
 * version 1, and the stored sections a login is refused for.
 */
@EnvTest
class PlayerStateCodecIntegrationTest {

    private static final String VALID = """
            {"world": {"kind": "map", "map_id": "demo", "build_id": "b20261011"},
             "pos": {"x": 1024.5, "y": 71.0, "z": -12.25, "yaw": 90.0, "pitch": 12.5},
             "inventory": [{"slot": 0, "item": "{count:63,id:\\"minecraft:stone\\"}"},
                           {"slot": 1, "item": "{count:1,components:{\\"minecraft:damage\\":3},id:\\"minecraft:iron_pickaxe\\"}"}],
             "held_slot": 1,
             "health": 17.5}""";

    /** The same state as {@link #VALID} as schema version 1 stored it, with each item as the JSON of the codec. */
    private static final String VERSION_1 = """
            {"world": {"kind": "map", "map_id": "demo", "build_id": "b20261011"},
             "pos": {"x": 1024.5, "y": 71.0, "z": -12.25, "yaw": 90.0, "pitch": 12.5},
             "inventory": [{"slot": 0, "item": {"id": "minecraft:stone", "count": 63}},
                           {"slot": 1, "item": {"id": "minecraft:iron_pickaxe", "count": 1, "components": {"minecraft:damage": 3}}}],
             "held_slot": 1,
             "health": 17.5}""";

    @Test
    void theLayoutIsTheOneOfTheDesign(Env env) {
        final PlayerStateSection section = new PlayerStateSection(env.process().registries());
        final PlayerState state = new PlayerState(new PlayerState.MapShard("demo", "b20261011"),
                new Pos(1024.5, 71, -12.25, 90, 12.5f),
                Map.of(1, ItemStack.builder(Material.IRON_PICKAXE).set(DataComponents.DAMAGE, 3).build(),
                        0, ItemStack.of(Material.STONE, 63)),
                1, 17.5f);

        final JsonObject encoded = section.encode(state).getAsJsonObject();

        assertEquals(JsonParser.parseString(VALID), encoded);
        assertEquals(state, section.decode(JsonParser.parseString(VALID), 2), "the design's example decodes to the state");
        assertEquals(state, section.decode(JsonParser.parseString(VERSION_1), 1), "a section of schema version 1 still decodes");
        assertEquals(2, section.schemaVersion());
        assertEquals(PlayerState.DEFAULT, section.createDefault());
        assertTrue(section.createDefault().isDefault(), "the default was never captured");
    }

    @Test
    void theCoreAndTheRtsWorldsCarryNoMap(Env env) {
        final PlayerStateSection section = new PlayerStateSection(env.process().registries());
        for (PlayerState.World world : new PlayerState.World[]{new PlayerState.Core(), new PlayerState.Rts()}) {
            final PlayerState state = new PlayerState(world, new Pos(8.5, 40, -3.5, 45, 10), Map.of(), 0, 20f);
            final JsonElement encoded = section.encode(state);
            assertEquals(1, encoded.getAsJsonObject().getAsJsonObject("world").size(), "only the kind: " + encoded);
            assertEquals(state, section.decode(JsonParser.parseString(encoded.toString()), section.schemaVersion()));
        }
    }

    /**
     * Encoded with a transcoder that has no registries, an enchantment fails with "Missing registries in transcoder"
     * and the whole section of that player could never be written again.
     */
    @Test
    void itemsWhoseComponentsNameRegistryEntriesSurviveTheRoundTrip(Env env) {
        final PlayerStateSection section = new PlayerStateSection(env.process().registries());
        final ItemStack sword = ItemStack.builder(Material.IRON_SWORD)
                .set(DataComponents.ENCHANTMENTS, new EnchantmentList(RegistryKey.unsafeOf("minecraft:sharpness"), 5)).build();
        final ItemStack book = ItemStack.builder(Material.ENCHANTED_BOOK)
                .set(DataComponents.STORED_ENCHANTMENTS, new EnchantmentList(RegistryKey.unsafeOf("minecraft:efficiency"), 3))
                .build();
        final PlayerState state = new PlayerState(new PlayerState.Core(), new Pos(8.5, 40, -3.5), Map.of(3, sword, 4, book),
                0, 20f);

        final String stored = section.encode(state).toString();

        assertEquals(state, section.decode(JsonParser.parseString(stored), section.schemaVersion()), stored);
    }

    /** The amplifier of an effect is an unsigned byte, as in 26.2: read back as a signed one, 200 came back as -56. */
    @Test
    void aPotionEffectAboveAmplifier127SurvivesTheRoundTrip(Env env) {
        final PlayerStateSection section = new PlayerStateSection(env.process().registries());
        final ItemStack potion = ItemStack.builder(Material.POTION)
                .set(DataComponents.POTION_CONTENTS,
                        new PotionContents(new CustomPotionEffect(PotionEffect.SPEED, 200, 600, false, true, true)))
                .build();
        final PlayerState state = new PlayerState(new PlayerState.Core(), new Pos(8.5, 40, -3.5), Map.of(0, potion), 0, 20f);

        final String stored = section.encode(state).toString();
        final PlayerState back = section.decode(JsonParser.parseString(stored), section.schemaVersion());

        assertEquals(200, back.inventory().get(0).get(DataComponents.POTION_CONTENTS).customEffects().getFirst().amplifier(),
                stored);
        assertEquals(state, back, stored);
    }

    /**
     * JSON has one kind of number: stored as the JSON of the codec, the custom data of an item came back with an int
     * or a double where a byte, a short, a float or a long was, and with a list where an int array was, which a tag
     * of the stored type reads as no value.
     */
    @Test
    void theTagsOfAnItemKeepTheirTypes(Env env) {
        final PlayerStateSection section = new PlayerStateSection(env.process().registries());
        final UUID owner = UUID.fromString("5d2b6f1e-3c4a-4f7b-9e21-8a6c0d4b7f13");
        final ItemStack sword = ItemStack.of(Material.IRON_SWORD)
                .withTag(Tag.Boolean("soulbound"), true)
                .withTag(Tag.Byte("tier"), (byte) 3)
                .withTag(Tag.Short("charges"), (short) 12)
                .withTag(Tag.Float("bonus"), 0.5f)
                .withTag(Tag.Long("serial"), 5L)
                .withTag(Tag.UUID("owner"), owner);
        final PlayerState state = new PlayerState(new PlayerState.Core(), new Pos(8.5, 40, -3.5), Map.of(0, sword), 0, 20f);

        final String stored = section.encode(state).toString();
        final ItemStack back = section.decode(JsonParser.parseString(stored), section.schemaVersion()).inventory().get(0);

        assertEquals(List.of(true, (byte) 3, (short) 12, 0.5f, 5L, owner),
                Arrays.asList(back.getTag(Tag.Boolean("soulbound")), back.getTag(Tag.Byte("tier")),
                        back.getTag(Tag.Short("charges")), back.getTag(Tag.Float("bonus")),
                        back.getTag(Tag.Long("serial")), back.getTag(Tag.UUID("owner"))), stored);
        assertEquals(sword, back, "the item stacks with one that never left the server");
    }

    /**
     * The lock of a container is an item predicate with its component predicates inlined into it. A lock stored the
     * way schema version 2 writes it comes back equal; one whose predicate does not decode refuses the login, naming
     * the component, as any item that does not decode does, where it used to come back as a lock any item opens.
     */
    @Test
    void aContainerLockComesBackAndOneThatDoesNotDecodeIsRefused(Env env) {
        final PlayerStateSection section = new PlayerStateSection(env.process().registries());
        final ItemPredicate key = new ItemPredicate(new DataComponentPredicates(DataComponentMap.EMPTY,
                ComponentPredicateSet.EMPTY.add(new DataComponentPredicate.Damage(new Range.Int(0, 5), null))));
        final PlayerState state = new PlayerState(new PlayerState.Core(), new Pos(8.5, 40, -3.5),
                Map.of(0, ItemStack.builder(Material.CHEST).set(DataComponents.LOCK, key).build()), 0, 20f);
        final String stored = """
                {"world": {"kind": "core"},
                 "pos": {"x": 8.5, "y": 40.0, "z": -3.5, "yaw": 0.0, "pitch": 0.0},
                 "inventory": [{"slot": 0, "item": "%s"}],
                 "held_slot": 0,
                 "health": 20.0}""";
        final String lock = "{count:1,components:{\\\"minecraft:lock\\\":{predicates:{\\\"minecraft:damage\\\":"
                + "{durability:{min:0,max:5}}}}},id:\\\"minecraft:chest\\\"}";
        final String invalid = lock.replace("{min:0,max:5}", "\\\"x\\\"");

        assertEquals(state, section.decode(section.encode(state), section.schemaVersion()));
        assertEquals(state, section.decode(JsonParser.parseString(stored.formatted(lock)), 2));
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> section.decode(JsonParser.parseString(stored.formatted(invalid)), 2));
        assertTrue(refused.getMessage().contains("minecraft:lock") && refused.getMessage().contains("minecraft:damage"),
                refused.getMessage());
    }

    @Test
    void aStoredSectionOutsideTheLayoutIsRefused(Env env) {
        final PlayerStateSection section = new PlayerStateSection(env.process().registries());
        final Map<String, JsonObject> refused = new LinkedHashMap<>();
        refused.put("no world", without("world"));
        refused.put("no held_slot", without("held_slot"));
        refused.put("no pos.yaw", changed(object -> object.getAsJsonObject("pos").remove("yaw")));
        refused.put("kind nether", changed(object -> object.getAsJsonObject("world").addProperty("kind", "nether")));
        refused.put("map without map_id", changed(object -> object.getAsJsonObject("world").remove("map_id")));
        refused.put("x not finite", changed(object -> object.getAsJsonObject("pos").add("x", JsonParser.parseString("1e400"))));
        refused.put("slot 46", changed(object -> slot(object, 0).addProperty("slot", 46)));
        refused.put("slot -1", changed(object -> slot(object, 0).addProperty("slot", -1)));
        refused.put("slot 0 twice", changed(object -> slot(object, 1).addProperty("slot", 0)));
        refused.put("unknown item", changed(object -> slot(object, 0)
                .addProperty("item", "{count:63,id:\"minecraft:no_such_item\"}")));
        refused.put("item not SNBT", changed(object -> slot(object, 0).addProperty("item", "{count:63,id:")));
        refused.put("held_slot 9", changed(object -> object.addProperty("held_slot", 9)));
        refused.put("health -1", changed(object -> object.addProperty("health", -1)));

        for (Map.Entry<String, JsonObject> entry : refused.entrySet())
            assertThrows(RuntimeException.class, () -> section.decode(entry.getValue(), 2), entry.getKey());
        final JsonObject version1 = JsonParser.parseString(VERSION_1).getAsJsonObject();
        slot(version1, 0).getAsJsonObject("item").addProperty("id", "minecraft:no_such_item");
        assertThrows(RuntimeException.class, () -> section.decode(version1, 1), "unknown item at schema version 1");
    }

    private static JsonObject without(String field) {
        return changed(object -> object.remove(field));
    }

    private static JsonObject changed(Consumer<JsonObject> change) {
        final JsonObject object = JsonParser.parseString(VALID).getAsJsonObject();
        change.accept(object);
        return object;
    }

    private static JsonObject slot(JsonObject object, int index) {
        final JsonArray inventory = object.getAsJsonArray("inventory");
        return inventory.get(index).getAsJsonObject();
    }
}
