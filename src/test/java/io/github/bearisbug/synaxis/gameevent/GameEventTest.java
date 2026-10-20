package io.github.bearisbug.synaxis.gameevent;

import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

public class GameEventTest {

    @Test
    public void load() {
        assertNotNull(GameEventImpl.REGISTRY);
    }

    @Test
    @SuppressWarnings("removal") // registry() is what is checked
    public void theRegistryHoldsTheVanillaGameEvents() {
        assertEquals(61, GameEvent.values().size());
        for (GameEvent event : GameEvent.values()) assertNotNull(event.registry(), event.name());

        assertSame(GameEvent.BLOCK_ACTIVATE, GameEvent.fromKey("block_activate"));
        assertEquals(0, GameEvent.BLOCK_ACTIVATE.id());
        assertEquals(Key.key("block_activate"), GameEvent.BLOCK_ACTIVATE.key());
    }
}
