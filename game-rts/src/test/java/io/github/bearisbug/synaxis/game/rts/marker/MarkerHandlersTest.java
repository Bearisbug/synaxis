package io.github.bearisbug.synaxis.game.rts.marker;

import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.instance.block.BlockUtils;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The data marker handlers this module lists as {@link BlockHandler} services (API-M3-003): the keys a map
 * source may name, and what the client sees of a marked block entity. The baker's fixture save names the
 * same two keys ({@code BakerMainTest}).
 */
class MarkerHandlersTest {

    @Test
    void theServicesAreTheDoorAndTheLootMarker() {
        final Map<String, BlockHandler> byKey = new TreeMap<>();
        for (BlockHandler handler : ServiceLoader.load(BlockHandler.class)) byKey.put(handler.getKey().asString(), handler);

        assertEquals(List.of("synaxis:door", "synaxis:loot"), List.copyOf(byKey.keySet()));
        assertInstanceOf(DoorMarker.class, byKey.get("synaxis:door"));
        assertInstanceOf(LootMarker.class, byKey.get("synaxis:loot"));
    }

    @Test
    void theClientSeesNoTagOfADoorAndOnlyTheTableOfALoot() {
        final Block chest = Block.CHEST.withNbt(CompoundBinaryTag.builder()
                .putString("table", "t1").putByte("locked", (byte) 1).build());

        assertEquals(CompoundBinaryTag.empty(), BlockUtils.extractClientNbt(chest.withHandler(new DoorMarker())));
        assertEquals(CompoundBinaryTag.builder().putString("table", "t1").build(),
                BlockUtils.extractClientNbt(chest.withHandler(new LootMarker())));
    }
}
