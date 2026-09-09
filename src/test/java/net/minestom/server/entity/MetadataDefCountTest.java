package net.minestom.server.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MetadataDefCountTest {

    @Test
    public void countOfAClassWithoutOwnEntries() {
        // Slime and MagmaCube add no metadata of their own, so they have no entry in the index map
        assertEquals(MetadataDef.count(MetadataDef.AbstractCubeMob.class), MetadataDef.count(MetadataDef.Slime.class));
        assertEquals(MetadataDef.count(MetadataDef.AbstractCubeMob.class), MetadataDef.count(MetadataDef.MagmaCube.class));
    }
}
