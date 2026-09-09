package net.minestom.server.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class MetadataDefCountTest {

    @Test
    public void countHandlesEveryDefinitionClass() {
        // A class that adds no metadata of its own has no entry in the index map, which count() used to unbox
        for (Class<?> nested : MetadataDef.class.getDeclaredClasses()) {
            if (!MetadataDef.class.isAssignableFrom(nested)) continue;
            final Class<? extends MetadataDef> clazz = nested.asSubclass(MetadataDef.class);
            final Class<? extends MetadataDef> parent = clazz.getSuperclass().asSubclass(MetadataDef.class);
            assertTrue(MetadataDef.count(clazz) >= MetadataDef.count(parent), nested.getSimpleName());
        }
    }
}
