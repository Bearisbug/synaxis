package dev.gameengine.item.component;

import dev.gameengine.codec.Codec;
import dev.gameengine.codec.StructCodec;
import dev.gameengine.entity.EntityType;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;

public record Bee(TypedCustomData<EntityType> entityData, int ticksInHive, int minTicksInHive) {
    public static final NetworkBuffer.Type<Bee> NETWORK_TYPE = NetworkBufferTemplate.template(
            TypedCustomData.networkType(EntityType.NETWORK_TYPE), Bee::entityData,
            NetworkBuffer.VAR_INT, Bee::ticksInHive,
            NetworkBuffer.VAR_INT, Bee::minTicksInHive,
            Bee::new);
    public static final Codec<Bee> CODEC = StructCodec.struct(
            "entity_data", TypedCustomData.codec(EntityType.CODEC), Bee::entityData,
            "ticks_in_hive", Codec.INT, Bee::ticksInHive,
            "min_ticks_in_hive", Codec.INT, Bee::minTicksInHive,
            Bee::new);

    public Bee withEntityData(TypedCustomData<EntityType> entityData) {
        return new Bee(entityData, ticksInHive, minTicksInHive);
    }

    public Bee withTicksInHive(int ticksInHive) {
        return new Bee(entityData, ticksInHive, minTicksInHive);
    }

    public Bee withMinTicksInHive(int minTicksInHive) {
        return new Bee(entityData, ticksInHive, minTicksInHive);
    }
}
