package io.github.bearisbug.synaxis.entity.metadata;

// https://minecraft.wiki/w/Minecraft_Wiki:Projects/wiki.vg_merge/Object_Data
public interface ObjectDataProvider {

    int getObjectData();

    default boolean requiresVelocityPacketAtSpawn() {
        return true;
    }

}
