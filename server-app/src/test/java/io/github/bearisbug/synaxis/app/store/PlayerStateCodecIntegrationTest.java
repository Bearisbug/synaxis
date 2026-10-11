package io.github.bearisbug.synaxis.app.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.item.component.EnchantmentList;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JSON of {@code synaxis:player} ({@code docs/DESIGN-PROD.md} {@code API-PROD-032}, §9): its layout, a round
 * trip of items whose components name registry entries, and the stored sections a login is refused for.
 */
@EnvTest
class PlayerStateCodecIntegrationTest {

    private static final String VALID = """
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
        assertEquals(state, section.decode(JsonParser.parseString(VALID), 1), "the design's example decodes to the state");
        assertEquals(1, section.schemaVersion());
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
            assertEquals(state, section.decode(JsonParser.parseString(encoded.toString()), 1));
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

        assertEquals(state, section.decode(JsonParser.parseString(stored), 1), stored);
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
        refused.put("unknown item", changed(object -> slot(object, 0).getAsJsonObject("item")
                .addProperty("id", "minecraft:no_such_item")));
        refused.put("held_slot 9", changed(object -> object.addProperty("held_slot", 9)));
        refused.put("health -1", changed(object -> object.addProperty("health", -1)));

        for (Map.Entry<String, JsonObject> entry : refused.entrySet())
            assertThrows(RuntimeException.class, () -> section.decode(entry.getValue(), 1), entry.getKey());
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
