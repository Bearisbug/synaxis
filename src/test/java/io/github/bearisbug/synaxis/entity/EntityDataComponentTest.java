package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.component.DataComponents;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The data components an entity holds: the custom name, kept in its metadata, and the custom data, its tags. */
public class EntityDataComponentTest {

    @Test
    public void theCustomNameIsTheOneOfTheMetadata() {
        final Entity entity = new Entity(EntityType.ZOMBIE);
        final Component name = Component.text("Bob");

        entity.set(DataComponents.CUSTOM_NAME, name);

        assertEquals(name, entity.get(DataComponents.CUSTOM_NAME));
        assertEquals(name, entity.getEntityMeta().get(MetadataDef.CUSTOM_NAME));
    }

    @Test
    public void anyOtherComponentIsNeitherStoredNorRead() {
        final Entity entity = new Entity(EntityType.ZOMBIE);

        entity.set(DataComponents.MAX_STACK_SIZE, 3);

        assertNull(entity.get(DataComponents.MAX_STACK_SIZE));
    }
}
