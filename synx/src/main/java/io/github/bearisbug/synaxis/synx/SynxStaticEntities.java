package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.adventure.serializer.nbt.NbtComponentSerializer;
import io.github.bearisbug.synaxis.coordinate.Direction;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.entity.Metadata;
import io.github.bearisbug.synaxis.entity.MetadataDef;
import io.github.bearisbug.synaxis.entity.metadata.display.AbstractDisplayMeta;
import io.github.bearisbug.synaxis.entity.metadata.display.BlockDisplayMeta;
import io.github.bearisbug.synaxis.entity.metadata.display.ItemDisplayMeta;
import io.github.bearisbug.synaxis.entity.metadata.display.TextDisplayMeta;
import io.github.bearisbug.synaxis.entity.metadata.other.ArmorStandMeta;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.tag.Tag;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Spawns the static entities of segment ③ when a chunk of the region loads ({@code docs/DESIGN-M3.md}
 * API-M3-005 静态实体, §28.1 静态实体还原范围): type, position and rotation always, and for six types
 * the fields the table lists; the source NBT hangs on {@link #SOURCE} for plugins. Any other type is
 * skipped with one WARN per type; a field that does not read is skipped with a WARN, the entity still
 * spawns. Changes at run time are not kept: the entities go with the chunk.
 */
final class SynxStaticEntities {
    /** The complete source NBT of a static entity. */
    static final Tag<BinaryTag> SOURCE = Tag.NBT("synx_source");

    private static final Logger LOGGER = LoggerFactory.getLogger(SynxStaticEntities.class);

    // HangingEntity DATA_DIRECTION, ItemFrame DATA_ITEM and DATA_ROTATION, after the 8 entries of Entity
    static final MetadataDef.Entry<Direction> FRAME_DIRECTION = new MetadataDef.Entry.Index<>(8, Metadata::Direction, Direction.SOUTH);
    static final MetadataDef.Entry<ItemStack> FRAME_ITEM = new MetadataDef.Entry.Index<>(9, Metadata::ItemStack, ItemStack.AIR);
    static final MetadataDef.Entry<Integer> FRAME_ROTATION = new MetadataDef.Entry.Index<>(10, Metadata::VarInt, 0);

    private static final Set<EntityType> COVERED = Set.of(EntityType.ITEM_FRAME, EntityType.GLOW_ITEM_FRAME,
            EntityType.TEXT_DISPLAY, EntityType.ITEM_DISPLAY, EntityType.BLOCK_DISPLAY, EntityType.ARMOR_STAND);

    private SynxStaticEntities() {
    }

    /**
     * Spawns {@code entities} into {@code instance}, on the thread that loads their chunk.
     *
     * @param warned the types already reported as not covered, shared by the loader
     */
    static void spawn(Instance instance, List<CompoundBinaryTag> entities, Set<String> warned) {
        for (CompoundBinaryTag nbt : entities) {
            final String id = nbt.getString("id");
            final EntityType type = EntityType.fromKey(id);
            if (type == null || !COVERED.contains(type)) {
                if (warned.add(id)) {
                    LOGGER.warn("Static entity type {} is not one the shard spawns, skipped (docs/DESIGN-M3.md §28.1)", id);
                }
                continue;
            }
            final ListBinaryTag pos = nbt.getList("Pos");
            final ListBinaryTag rotation = nbt.getList("Rotation");
            final Pos position = new Pos(pos.getDouble(0), pos.getDouble(1), pos.getDouble(2),
                    rotation.getFloat(0), rotation.getFloat(1));
            final boolean frame = type == EntityType.ITEM_FRAME || type == EntityType.GLOW_ITEM_FRAME;
            final Direction facing = frame ? facing(nbt) : null;
            final SynxStaticEntity entity = new SynxStaticEntity(instance.process(), type,
                    facing != null ? facing.ordinal() : 0);
            try {
                map(entity, type, facing, nbt, instance.registries());
            } catch (RuntimeException e) {
                LOGGER.warn("Static entity {} at {}: a field does not read ({}), spawned without the rest of them",
                        id, position, e.getMessage());
            }
            entity.setTag(SOURCE, nbt);
            var _ = entity.setInstance(instance, position);
        }
    }

    private static @Nullable Direction facing(CompoundBinaryTag nbt) {
        final int facing = nbt.getByte("Facing");
        return facing >= 0 && facing < Direction.values().length ? Direction.values()[facing] : null;
    }

    private static void map(SynxStaticEntity entity, EntityType type, @Nullable Direction facing,
                            CompoundBinaryTag nbt, Registries registries) {
        if (type == EntityType.ITEM_FRAME || type == EntityType.GLOW_ITEM_FRAME) {
            if (facing != null) entity.setMetadata(FRAME_DIRECTION, facing);
            if (nbt.get("Item") instanceof CompoundBinaryTag item) {
                entity.setMetadata(FRAME_ITEM, ItemStack.fromItemNBT(item, registries));
            }
            entity.setMetadata(FRAME_ROTATION, (int) nbt.getByte("ItemRotation"));
            entity.setInvisible(nbt.getBoolean("Invisible"));
            // Fixed only keeps players from breaking or turning the frame, which a static entity never lets them
        } else if (type == EntityType.TEXT_DISPLAY) {
            final TextDisplayMeta meta = (TextDisplayMeta) entity.getEntityMeta();
            final BinaryTag text = nbt.get("text");
            if (text != null) meta.setText(NbtComponentSerializer.nbt().deserialize(text));
            billboard(meta, nbt);
            if (nbt.get("background") != null) meta.setBackgroundColor(nbt.getInt("background"));
        } else if (type == EntityType.ITEM_DISPLAY) {
            final ItemDisplayMeta meta = (ItemDisplayMeta) entity.getEntityMeta();
            if (nbt.get("item") instanceof CompoundBinaryTag item) meta.setItemStack(ItemStack.fromItemNBT(item, registries));
        } else if (type == EntityType.BLOCK_DISPLAY) {
            final BlockDisplayMeta meta = (BlockDisplayMeta) entity.getEntityMeta();
            if (nbt.get("block_state") instanceof CompoundBinaryTag state) meta.setBlockState(block(state));
        } else if (type == EntityType.ARMOR_STAND) {
            final ArmorStandMeta meta = (ArmorStandMeta) entity.getEntityMeta();
            meta.setSmall(nbt.getBoolean("Small"));
            meta.setHasArms(nbt.getBoolean("ShowArms"));
            meta.setHasNoBasePlate(nbt.getBoolean("NoBasePlate"));
            meta.setMarker(nbt.getBoolean("Marker"));
            entity.setInvisible(nbt.getBoolean("Invisible"));
        }
    }

    private static void billboard(AbstractDisplayMeta meta, CompoundBinaryTag nbt) {
        if (!(nbt.get("billboard") instanceof StringBinaryTag billboard)) return;
        meta.setBillboardRenderConstraints(AbstractDisplayMeta.BillboardConstraints.valueOf(
                billboard.value().toUpperCase(Locale.ROOT)));
    }

    /** {@code {Name, Properties}} as a save writes a block state. */
    private static Block block(CompoundBinaryTag state) {
        final String name = state.getString("Name");
        final Block block = Block.fromKey(name);
        if (block == null) throw new IllegalArgumentException("unknown block " + name);
        final CompoundBinaryTag properties = state.getCompound("Properties");
        if (properties.size() == 0) return block;
        final Map<String, String> values = new HashMap<>();
        for (String key : properties.keySet()) values.put(key, properties.getString(key));
        return block.withProperties(values);
    }
}
