package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.entity.metadata.EntityMeta;
import io.github.bearisbug.synaxis.entity.metadata.LivingEntityMeta;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityMetaDataPacket;
import io.github.bearisbug.synaxis.registry.Registries;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
}
