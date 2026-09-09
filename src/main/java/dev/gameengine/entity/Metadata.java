package dev.gameengine.entity;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.text.Component;
import dev.gameengine.coordinate.Point;
import dev.gameengine.coordinate.Vec;
import dev.gameengine.instance.block.Block;
import dev.gameengine.item.ItemStack;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.packet.server.play.data.WorldPos;
import dev.gameengine.network.player.ResolvableProfile;
import dev.gameengine.particle.Particle;
import dev.gameengine.registry.BuiltinRegistries;
import dev.gameengine.registry.Registries;
import dev.gameengine.registry.Registry;
import dev.gameengine.registry.RegistryKey;
import dev.gameengine.coordinate.Direction;
import dev.gameengine.utils.collection.ObjectArray;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class Metadata {
    public static Entry<Byte> Byte(byte value) {
        return BYTE.entry(value);
    }

    public static Entry<Integer> VarInt(int value) {
        return VAR_INT.entry(value);
    }

    public static Entry<Long> VarLong(long value) {
        return LONG.entry(value);
    }

    public static Entry<Float> Float(float value) {
        return FLOAT.entry(value);
    }

    public static Entry<String> String(String value) {
        return STRING.entry(value);
    }

    public static Entry<Component> Component(Component value) {
        return CHAT.entry(value);
    }

    public static Entry<@Nullable Component> OptComponent(@Nullable Component value) {
        return OPT_CHAT.entry(value);
    }

    public static Entry<ItemStack> ItemStack(ItemStack value) {
        return ITEM_STACK.entry(value);
    }

    public static Entry<Boolean> Boolean(boolean value) {
        return BOOLEAN.entry(value);
    }

    public static Entry<Point> Rotation(Point value) {
        return ROTATION.entry(value);
    }

    public static Entry<Point> BlockPosition(Point value) {
        return BLOCK_POSITION.entry(value);
    }

    public static Entry<@Nullable Point> OptBlockPosition(@Nullable Point value) {
        return OPT_BLOCK_POSITION.entry(value);
    }

    public static Entry<Direction> Direction(Direction value) {
        return DIRECTION.entry(value);
    }

    public static Entry<@Nullable UUID> OptUUID(@Nullable UUID value) {
        return OPT_UUID.entry(value);
    }

    public static Entry<Block> BlockState(Block value) {
        return BLOCK_STATE.entry(value);
    }

    public static Entry<@Nullable Block> OptBlockState(@Nullable Block value) {
        return OPT_BLOCK_STATE.entry(value);
    }

    public static Entry<Particle> Particle(Particle particle) {
        return PARTICLE.entry(particle);
    }

    public static Entry<List<Particle>> ParticleList(List<Particle> particles) {
        return PARTICLE_LIST.entry(particles);
    }

    public static Entry<VillagerData> VillagerData(VillagerData data) {
        return VILLAGER_DATA.entry(data);
    }

    public static Entry<@Nullable Integer> OptVarInt(@Nullable Integer value) {
        return OPT_VAR_INT.entry(value);
    }

    public static Entry<EntityPose> Pose(EntityPose value) {
        return POSE.entry(value);
    }

    public static Entry<RegistryKey<BinaryTag>> CatVariant(RegistryKey<BinaryTag> value) {
        return CAT_VARIANT.entry(value);
    }

    public static Entry<RegistryKey<BinaryTag>> CatSoundVariant(RegistryKey<BinaryTag> value) {
        return CAT_SOUND_VARIANT.entry(value);
    }

    public static Entry<RegistryKey<BinaryTag>> CowVariant(RegistryKey<BinaryTag> value) {
        return COW_VARIANT.entry(value);
    }

    public static Entry<RegistryKey<BinaryTag>> CowSoundVariant(RegistryKey<BinaryTag> value) {
        return COW_SOUND_VARIANT.entry(value);
    }

    public static Entry<RegistryKey<BinaryTag>> WolfVariant(RegistryKey<BinaryTag> value) {
        return WOLF_VARIANT.entry(value);
    }

    public static Entry<RegistryKey<BinaryTag>> WolfSoundVariant(RegistryKey<BinaryTag> value) {
        return WOLF_SOUND_VARIANT.entry(value);
    }

    public static Entry<RegistryKey<BinaryTag>> FrogVariant(RegistryKey<BinaryTag> value) {
        return FROG_VARIANT.entry(value);
    }

    public static Entry<RegistryKey<BinaryTag>> PigVariant(RegistryKey<BinaryTag> value) {
        return PIG_VARIANT.entry(value);
    }

    public static Entry<RegistryKey<BinaryTag>> PigSoundVariant(RegistryKey<BinaryTag> value) {
        return PIG_SOUND_VARIANT.entry(value);
    }

    public static Entry<RegistryKey<BinaryTag>> ChickenVariant(RegistryKey<BinaryTag> value) {
        return CHICKEN_VARIANT.entry(value);
    }

    public static Entry<RegistryKey<BinaryTag>> ChickenSoundVariant(RegistryKey<BinaryTag> value) {
        return CHICKEN_SOUND_VARIANT.entry(value);
    }

    public static Entry<RegistryKey<BinaryTag>> ZombieNautilusVariant(RegistryKey<BinaryTag> value) {
        return ZOMBIE_NAUTILUS_VARIANT.entry(value);
    }

    public static Entry<@Nullable WorldPos> OptGlobalPosition(@Nullable WorldPos value) {
        return OPT_GLOBAL_POSITION.entry(value);
    }

    public static Entry<RegistryKey<BinaryTag>> PaintingVariant(RegistryKey<BinaryTag> value) {
        return PAINTING_VARIANT.entry(value);
    }

    public static Entry<SnifferState> SnifferState(SnifferState value) {
        return SNIFFER_STATE.entry(value);
    }

    public static Entry<ArmadilloState> ArmadilloState(ArmadilloState value) {
        return ARMADILLO_STATE.entry(value);
    }

    public static Entry<CopperGolemState> CopperGolemState(CopperGolemState value) {
        return COPPER_GOLEM_STATE.entry(value);
    }

    public static Entry<CopperGolemWeatherState> WeatherState(CopperGolemWeatherState value) {
        return WEATHER_STATE.entry(value);
    }

    public static Entry<Point> Vector3(Point value) {
        return VECTOR3.entry(value);
    }

    public static Entry<float[]> Quaternion(float[] value) {
        return QUATERNION.entry(value);
    }

    public static Entry<ResolvableProfile> ResolvableProfile(ResolvableProfile value) {
        return RESOLVABLE_PROFILE.entry(value);
    }

    public static Entry<MainHand> MainHand(MainHand value) {
        return MAIN_HAND.entry(value);
    }

    private static Registries.Selector<BinaryTag> opaque(RegistryKey<Registry<BinaryTag>> key) {
        return registries -> registries.opaque(key);
    }

    private static final AtomicInteger NEXT_ID = new AtomicInteger(0);
    private static final ObjectArray<Type<?>> TYPES = ObjectArray.singleThread(64);

    private static final Type<Byte> BYTE = type(NetworkBuffer.BYTE, (byte) 0);
    private static final Type<Integer> VAR_INT = type(NetworkBuffer.VAR_INT, 0);
    private static final Type<Long> LONG = type(NetworkBuffer.VAR_LONG, 0L);
    private static final Type<Float> FLOAT = type(NetworkBuffer.FLOAT, 0f);
    private static final Type<String> STRING = type(NetworkBuffer.STRING, "");
    private static final Type<Component> CHAT = type(NetworkBuffer.COMPONENT, Component.empty());
    private static final Type<@Nullable Component> OPT_CHAT = type(NetworkBuffer.OPT_CHAT, null);
    private static final Type<ItemStack> ITEM_STACK = type(ItemStack.NETWORK_TYPE, ItemStack.AIR);
    private static final Type<Boolean> BOOLEAN = type(NetworkBuffer.BOOLEAN, false);
    private static final Type<Point> ROTATION = type(NetworkBuffer.VECTOR3, Vec.ZERO);
    private static final Type<Point> BLOCK_POSITION = type(NetworkBuffer.BLOCK_POSITION, Vec.ZERO);
    private static final Type<@Nullable Point> OPT_BLOCK_POSITION = type(NetworkBuffer.OPT_BLOCK_POSITION, null);
    private static final Type<Direction> DIRECTION = type(NetworkBuffer.DIRECTION, Direction.DOWN);
    private static final Type<@Nullable UUID> OPT_UUID = type(NetworkBuffer.UUID.optional(), null);
    private static final Type<Block> BLOCK_STATE = type(Block.STATE_NETWORK_TYPE, Block.AIR);
    private static final Type<@Nullable Block> OPT_BLOCK_STATE = type(new NetworkBuffer.Type<>() { // OPT_VAR_INT
        @Override
        public void write(NetworkBuffer buffer, @Nullable Block value) {
            buffer.write(NetworkBuffer.VAR_INT, value == null ? 0 : value.id());
        }

        @Override
        public @Nullable Block read(NetworkBuffer buffer) {
            int value = buffer.read(NetworkBuffer.VAR_INT);
            return value == 0 ? null : Block.fromStateId(value);
        }
    }, null);
    private static final Type<Particle> PARTICLE = type(Particle.NETWORK_TYPE, Particle.DUST);
    private static final Type<List<Particle>> PARTICLE_LIST = type(Particle.NETWORK_TYPE.list(Short.MAX_VALUE), List.of());
    private static final Type<VillagerData> VILLAGER_DATA = type(VillagerData.NETWORK_TYPE, VillagerData.DEFAULT);
    private static final Type<@Nullable Integer> OPT_VAR_INT = type(NetworkBuffer.OPTIONAL_VAR_INT, null);
    private static final Type<EntityPose> POSE = type(NetworkBuffer.POSE, EntityPose.STANDING);
    private static final Type<RegistryKey<BinaryTag>> CAT_VARIANT = type(RegistryKey.networkType(opaque(BuiltinRegistries.CAT_VARIANT)), RegistryKey.unsafeOf("minecraft:tabby"));
    private static final Type<RegistryKey<BinaryTag>> CAT_SOUND_VARIANT = type(RegistryKey.networkType(opaque(BuiltinRegistries.CAT_SOUND_VARIANT)), RegistryKey.unsafeOf("minecraft:classic"));
    private static final Type<RegistryKey<BinaryTag>> COW_VARIANT = type(RegistryKey.networkType(opaque(BuiltinRegistries.COW_VARIANT)), RegistryKey.unsafeOf("minecraft:temperate"));
    private static final Type<RegistryKey<BinaryTag>> COW_SOUND_VARIANT = type(RegistryKey.networkType(opaque(BuiltinRegistries.COW_SOUND_VARIANT)), RegistryKey.unsafeOf("minecraft:classic"));
    private static final Type<RegistryKey<BinaryTag>> WOLF_VARIANT = type(RegistryKey.networkType(opaque(BuiltinRegistries.WOLF_VARIANT)), RegistryKey.unsafeOf("minecraft:pale"));
    private static final Type<RegistryKey<BinaryTag>> WOLF_SOUND_VARIANT = type(RegistryKey.networkType(opaque(BuiltinRegistries.WOLF_SOUND_VARIANT)), RegistryKey.unsafeOf("minecraft:classic"));
    private static final Type<RegistryKey<BinaryTag>> FROG_VARIANT = type(RegistryKey.networkType(opaque(BuiltinRegistries.FROG_VARIANT)), RegistryKey.unsafeOf("minecraft:temperate"));
    private static final Type<RegistryKey<BinaryTag>> PIG_VARIANT = type(RegistryKey.networkType(opaque(BuiltinRegistries.PIG_VARIANT)), RegistryKey.unsafeOf("minecraft:temperate"));
    private static final Type<RegistryKey<BinaryTag>> PIG_SOUND_VARIANT = type(RegistryKey.networkType(opaque(BuiltinRegistries.PIG_SOUND_VARIANT)), RegistryKey.unsafeOf("minecraft:classic"));
    private static final Type<RegistryKey<BinaryTag>> CHICKEN_VARIANT = type(RegistryKey.networkType(opaque(BuiltinRegistries.CHICKEN_VARIANT)), RegistryKey.unsafeOf("minecraft:temperate"));
    private static final Type<RegistryKey<BinaryTag>> CHICKEN_SOUND_VARIANT = type(RegistryKey.networkType(opaque(BuiltinRegistries.CHICKEN_SOUND_VARIANT)), RegistryKey.unsafeOf("minecraft:classic"));
    private static final Type<RegistryKey<BinaryTag>> ZOMBIE_NAUTILUS_VARIANT = type(RegistryKey.networkType(opaque(BuiltinRegistries.ZOMBIE_NAUTILUS_VARIANT)), RegistryKey.unsafeOf("minecraft:temperate"));
    private static final Type<@Nullable WorldPos> OPT_GLOBAL_POSITION = type(WorldPos.NETWORK_TYPE.optional(), null);
    private static final Type<RegistryKey<BinaryTag>> PAINTING_VARIANT = type(RegistryKey.holderNetworkType(opaque(BuiltinRegistries.PAINTING_VARIANT)), RegistryKey.unsafeOf("minecraft:kebab"));
    private static final Type<SnifferState> SNIFFER_STATE = type(SnifferState.NETWORK_TYPE, SnifferState.IDLING);
    private static final Type<ArmadilloState> ARMADILLO_STATE = type(ArmadilloState.NETWORK_TYPE, ArmadilloState.IDLE);
    private static final Type<CopperGolemState> COPPER_GOLEM_STATE = type(CopperGolemState.NETWORK_TYPE, CopperGolemState.IDLE);
    private static final Type<CopperGolemWeatherState> WEATHER_STATE = type(CopperGolemWeatherState.NETWORK_TYPE, CopperGolemWeatherState.UNAFFECTED);
    private static final Type<Point> VECTOR3 = type(NetworkBuffer.VECTOR3, Vec.ZERO);
    private static final Type<float[]> QUATERNION = type(NetworkBuffer.QUATERNION, new float[]{0, 0, 0, 0});
    private static final Type<ResolvableProfile> RESOLVABLE_PROFILE = type(ResolvableProfile.NETWORK_TYPE, ResolvableProfile.EMPTY);
    private static final Type<MainHand> MAIN_HAND = type(MainHand.NETWORK_TYPE, MainHand.RIGHT);

    public static final byte TYPE_BYTE = id(BYTE);
    public static final byte TYPE_VARINT = id(VAR_INT);
    public static final byte TYPE_LONG = id(LONG);
    public static final byte TYPE_FLOAT = id(FLOAT);
    public static final byte TYPE_STRING = id(STRING);
    public static final byte TYPE_CHAT = id(CHAT);
    public static final byte TYPE_OPT_CHAT = id(OPT_CHAT);
    public static final byte TYPE_ITEM_STACK = id(ITEM_STACK);
    public static final byte TYPE_BOOLEAN = id(BOOLEAN);
    public static final byte TYPE_ROTATION = id(ROTATION);
    public static final byte TYPE_BLOCK_POSITION = id(BLOCK_POSITION);
    public static final byte TYPE_OPT_BLOCK_POSITION = id(OPT_BLOCK_POSITION);
    public static final byte TYPE_DIRECTION = id(DIRECTION);
    public static final byte TYPE_OPT_UUID = id(OPT_UUID);
    public static final byte TYPE_BLOCKSTATE = id(BLOCK_STATE);
    public static final byte TYPE_OPT_BLOCKSTATE = id(OPT_BLOCK_STATE);
    public static final byte TYPE_PARTICLE = id(PARTICLE);
    public static final byte TYPE_PARTICLE_LIST = id(PARTICLE_LIST);
    public static final byte TYPE_VILLAGERDATA = id(VILLAGER_DATA);
    public static final byte TYPE_OPT_VARINT = id(OPT_VAR_INT);
    public static final byte TYPE_POSE = id(POSE);
    public static final byte TYPE_CAT_VARIANT = id(CAT_VARIANT);
    public static final byte TYPE_CAT_SOUND_VARIANT = id(CAT_SOUND_VARIANT);
    public static final byte TYPE_COW_VARIANT = id(COW_VARIANT);
    public static final byte TYPE_COW_SOUND_VARIANT = id(COW_SOUND_VARIANT);
    public static final byte TYPE_WOLF_VARIANT = id(WOLF_VARIANT);
    public static final byte TYPE_WOLF_SOUND_VARIANT = id(WOLF_SOUND_VARIANT);
    public static final byte TYPE_FROG_VARIANT = id(FROG_VARIANT);
    public static final byte TYPE_PIG_VARIANT = id(PIG_VARIANT);
    public static final byte TYPE_PIG_SOUND_VARIANT = id(PIG_SOUND_VARIANT);
    public static final byte TYPE_CHICKEN_VARIANT = id(CHICKEN_VARIANT);
    public static final byte TYPE_CHICKEN_SOUND_VARIANT = id(CHICKEN_SOUND_VARIANT);
    public static final byte TYPE_ZOMBIE_NAUTILUS_VARIANT = id(ZOMBIE_NAUTILUS_VARIANT);
    public static final byte TYPE_OPT_GLOBAL_POSITION = id(OPT_GLOBAL_POSITION);
    public static final byte TYPE_PAINTING_VARIANT = id(PAINTING_VARIANT);
    public static final byte TYPE_SNIFFER_STATE = id(SNIFFER_STATE);
    public static final byte TYPE_ARMADILLO_STATE = id(ARMADILLO_STATE);
    public static final byte TYPE_COPPER_GOLEM_STATE = id(COPPER_GOLEM_STATE);
    public static final byte TYPE_WEATHER_STATE = id(WEATHER_STATE);
    public static final byte TYPE_VECTOR3 = id(VECTOR3);
    public static final byte TYPE_QUATERNION = id(QUATERNION);
    public static final byte TYPE_RESOLVABLE_PROFILE = id(RESOLVABLE_PROFILE);
    public static final byte TYPE_MAIN_HAND = id(MAIN_HAND);

    private static <T extends @UnknownNullability Object> Type<T> type(NetworkBuffer.Type<T> serializer, T defaultValue) {
        final int id = nextId();
        final Type<T> type = new Type<>(id, serializer, defaultValue);
        TYPES.set(id, type);
        return type;
    }

    static @Nullable Type<?> typeById(int id) {
        return TYPES.get(id);
    }

    static int typeCount() {
        return NEXT_ID.get();
    }

    private static int nextId() {
        return NEXT_ID.getAndIncrement();
    }

    private static byte id(Type<?> type) {
        return (byte) type.id();
    }

    record Type<T extends @UnknownNullability Object>(
            int id,
            NetworkBuffer.Type<T> serializer,
            T defaultValue
    ) {
        Entry<T> entry(T value) {
            return new MetadataImpl.EntryImpl<>(this, value);
        }
    }

    public sealed interface Entry<T extends @UnknownNullability Object> permits MetadataImpl.EntryImpl {
        NetworkBuffer.Type<Entry<?>> SERIALIZER = MetadataImpl.EntryImpl.SERIALIZER;

        int type();

        T value();
    }
}
