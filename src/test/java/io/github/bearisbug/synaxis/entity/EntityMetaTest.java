package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.entity.metadata.EntityMeta;
import io.github.bearisbug.synaxis.entity.metadata.LivingEntityMeta;
import io.github.bearisbug.synaxis.entity.metadata.display.AbstractDisplayMeta;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityMetaDataPacket;
import io.github.bearisbug.synaxis.registry.Registries;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class EntityMetaTest {

    @Test
    public void everyEntityTypeIsCreatableAndSerializable() {
        for (EntityType entityType : EntityType.values()) {
            final Entity entity = new Entity(entityType);
            final EntityMeta meta = entity.getEntityMeta();
            assertNotNull(meta, entityType.name());
            if (entityType.shouldSendAttributes()) {
                assertInstanceOf(LivingEntityMeta.class, meta, entityType.name());
            }
            meta.setInvisible(true);

            final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(64, Registries.vanilla());
            EntityMetaDataPacket.SERIALIZER.write(buffer, entity.getMetadataPacket());
        }
    }

    @Test
    public void displayBrightnessOverride() {
        final var meta = (AbstractDisplayMeta) new Entity(EntityType.BLOCK_DISPLAY).getEntityMeta();
        assertEquals(-1, meta.getBrightnessOverride());
        assertEquals(0, meta.getBlockLight());
        assertEquals(0, meta.getSkyLight());

        meta.setBrightness(15, 7);
        assertEquals(15, meta.getBlockLight());
        assertEquals(7, meta.getSkyLight());

        assertThrows(IllegalArgumentException.class, () -> meta.setBrightness(16, 0));
        assertThrows(IllegalArgumentException.class, () -> meta.setBrightness(0, -1));
        assertEquals(15, meta.getBlockLight());
        assertEquals(7, meta.getSkyLight());
    }
}
