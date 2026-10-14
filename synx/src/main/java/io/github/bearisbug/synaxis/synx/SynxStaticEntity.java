package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.entity.MetadataDef;
import io.github.bearisbug.synaxis.network.packet.server.play.SpawnEntityPacket;

/**
 * A static entity of segment ③ ({@code docs/DESIGN-M3.md} API-M3-005 静态实体): a plain entity with
 * no AI and no gravity that does not synchronize its position on a period, and for an item frame the
 * facing the spawn packet carries. The engine has no item frame meta, so the frame's fields go in as raw
 * metadata entries.
 */
final class SynxStaticEntity extends Entity {
    private final int objectData;

    SynxStaticEntity(ServerProcess process, EntityType type, int objectData) {
        super(process, type);
        this.objectData = objectData;
        setNoGravity(true);
        // It never moves on the server: the spawn, the sync due 20 ticks after it and the one on coming back
        // into the simulation distance tell the viewers enough. Long.MAX_VALUE would overflow the next tick due
        setSynchronizationTicks(Integer.MAX_VALUE);
    }

    /** The data field of the spawn packet: the facing of an item frame, 0 for the other types. */
    int objectData() {
        return objectData;
    }

    <T> void setMetadata(MetadataDef.Entry<T> entry, T value) {
        metadata.set(entry, value);
    }

    @Override
    protected SpawnEntityPacket getSpawnPacket() {
        final SpawnEntityPacket packet = super.getSpawnPacket();
        if (objectData == packet.data()) return packet;
        return new SpawnEntityPacket(packet.entityId(), packet.uuid(), packet.type(), packet.position(),
                packet.headRot(), objectData, packet.velocity());
    }
}
