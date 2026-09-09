package net.minestom.server.entity;

import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import net.minestom.server.entity.metadata.EntityMeta;
import net.minestom.server.entity.metadata.LivingEntityMeta;
import net.minestom.server.entity.metadata.avatar.MannequinMeta;
import net.minestom.server.entity.metadata.avatar.PlayerMeta;
import net.minestom.server.entity.metadata.display.BlockDisplayMeta;
import net.minestom.server.entity.metadata.display.ItemDisplayMeta;
import net.minestom.server.entity.metadata.display.TextDisplayMeta;
import net.minestom.server.entity.metadata.item.EyeOfEnderMeta;
import net.minestom.server.entity.metadata.item.FireballMeta;
import net.minestom.server.entity.metadata.item.ItemEntityMeta;
import net.minestom.server.entity.metadata.item.LingeringPotionMeta;
import net.minestom.server.entity.metadata.item.SmallFireballMeta;
import net.minestom.server.entity.metadata.item.SnowballMeta;
import net.minestom.server.entity.metadata.item.SplashPotionMeta;
import net.minestom.server.entity.metadata.item.ThrownEggMeta;
import net.minestom.server.entity.metadata.item.ThrownEnderPearlMeta;
import net.minestom.server.entity.metadata.item.ThrownExperienceBottleMeta;
import net.minestom.server.entity.metadata.other.AllayMeta;
import net.minestom.server.entity.metadata.other.AreaEffectCloudMeta;
import net.minestom.server.entity.metadata.other.ArmorStandMeta;
import net.minestom.server.entity.metadata.other.BoatMeta;
import net.minestom.server.entity.metadata.other.EndCrystalMeta;
import net.minestom.server.entity.metadata.other.EnderDragonMeta;
import net.minestom.server.entity.metadata.other.EvokerFangsMeta;
import net.minestom.server.entity.metadata.other.ExperienceOrbMeta;
import net.minestom.server.entity.metadata.other.FallingBlockMeta;
import net.minestom.server.entity.metadata.other.FishingHookMeta;
import net.minestom.server.entity.metadata.other.GlowItemFrameMeta;
import net.minestom.server.entity.metadata.other.InteractionMeta;
import net.minestom.server.entity.metadata.other.ItemFrameMeta;
import net.minestom.server.entity.metadata.other.LeashKnotMeta;
import net.minestom.server.entity.metadata.other.LightningBoltMeta;
import net.minestom.server.entity.metadata.other.LlamaSpitMeta;
import net.minestom.server.entity.metadata.other.MarkerMeta;
import net.minestom.server.entity.metadata.other.OminousItemSpawnerMeta;
import net.minestom.server.entity.metadata.other.PaintingMeta;
import net.minestom.server.entity.metadata.other.PrimedTntMeta;
import net.minestom.server.entity.metadata.other.ShulkerBulletMeta;
import net.minestom.server.entity.metadata.other.TraderLlamaMeta;
import net.minestom.server.entity.metadata.projectile.ArrowMeta;
import net.minestom.server.entity.metadata.projectile.BreezeWindChargeMeta;
import net.minestom.server.entity.metadata.projectile.DragonFireballMeta;
import net.minestom.server.entity.metadata.projectile.FireworkRocketMeta;
import net.minestom.server.entity.metadata.projectile.SpectralArrowMeta;
import net.minestom.server.entity.metadata.projectile.ThrownTridentMeta;
import net.minestom.server.entity.metadata.projectile.WindChargeMeta;
import net.minestom.server.entity.metadata.projectile.WitherSkullMeta;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.CheckReturnValue;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static java.util.Map.entry;

public final class MetadataHolder {
    private final Consumer<Map<Integer, Metadata.Entry<?>>> changesListener;
    // Indexed by metadata id, resized on demand. Copy-on-write and published through the volatile
    // reference, so a reader always observes a fully built array and concurrent writers to different
    // ids cannot drop each other's value while growing it.
    private volatile Metadata.@Nullable Entry<?>[] entries = new Metadata.Entry<?>[0];

    // Number of open batches, a plain flag makes nested try/finally blocks flush the outer batch early
    private final AtomicInteger openBatches = new AtomicInteger();
    private final Int2ObjectArrayMap<Metadata.Entry<?>> notNotifiedChanges = new Int2ObjectArrayMap<>();

    /**
     * @deprecated Use {@link #MetadataHolder(Consumer)} instead.
     */
    @Deprecated(forRemoval = true)
    public MetadataHolder(@Nullable Entity entity) {
        this(entity == null ? _ -> {
        } : entity::notifyMetadataChanges);
    }

    public MetadataHolder(Consumer<Map<Integer, Metadata.Entry<?>>> changesListener) {
        this.changesListener = Objects.requireNonNull(changesListener, "changesListener");
    }

    @SuppressWarnings("unchecked")
    @CheckReturnValue
    public <T extends @UnknownNullability Object> T get(MetadataDef.Entry<T> entry) {
        final int id = entry.index();

        final Metadata.Entry<?> value = entryAt(id);
        if (value == null) return entry.defaultValue();
        return switch (entry) {
            case MetadataDef.Entry.Index<T> _ -> (T) value.value();
            case MetadataDef.Entry.BitMask bitMask -> {
                final byte maskValue = (byte) value.value();
                yield (T) ((Boolean) getMaskBit(maskValue, bitMask.bitMask()));
            }
            case MetadataDef.Entry.ByteMask byteMask -> {
                final byte maskValue = (byte) value.value();
                yield (T) ((Byte) getMaskByte(maskValue, byteMask.byteMask(), byteMask.offset()));
            }
        };
    }

    public <T extends @UnknownNullability Object> void set(MetadataDef.Entry<T> entry, T value) {
        final int id = entry.index();

        final Metadata.Entry<?> result;
        // Masks are a read-modify-write of a shared byte, the whole sequence has to be atomic
        synchronized (this) {
            T current = get(entry);

            // If a metadata value is unchanged we should not send it. In particular we need to be careful with
            //  sending bitmasks which will overwrite client-predicted values. See PR 3089 for more info.
            // However, interpolation delay is expected to be sent regularly with the same value to begin
            //  interpolation so we always send it for now.
            if (Objects.equals(current, value) && entry != MetadataDef.Display.INTERPOLATION_DELAY) {
                return;
            }

            result = switch (entry) {
                case MetadataDef.Entry.Index<T> v -> v.function().apply(value);
                case MetadataDef.Entry.BitMask bitMask -> {
                    Metadata.Entry<?> currentEntry = entryAt(id);
                    // An unset mask byte holds its declared default, not 0, otherwise writing one bit
                    // resets every sibling bit of the same byte
                    byte maskValue = currentEntry != null ? (byte) currentEntry.value() : bitMask.containerDefault();
                    maskValue = setMaskBit(maskValue, bitMask.bitMask(), (Boolean) value);
                    yield Metadata.Byte(maskValue);
                }
                case MetadataDef.Entry.ByteMask byteMask -> {
                    Metadata.Entry<?> currentEntry = entryAt(id);
                    byte maskValue = currentEntry != null ? (byte) currentEntry.value() : byteMask.containerDefault();
                    maskValue = setMaskByte(maskValue, byteMask.byteMask(), byteMask.offset(), (Byte) value);
                    yield Metadata.Byte(maskValue);
                }
            };

            putEntry(id, result);
        }

        // Reading the batch state and queueing has to be atomic against the flush, otherwise an update
        // queued right after a flush is never sent
        final boolean batched;
        synchronized (this.notNotifiedChanges) {
            batched = openBatches.get() > 0;
            if (batched) this.notNotifiedChanges.put(id, result);
        }
        if (!batched) this.changesListener.accept(Map.of(id, result));
    }

    private static boolean getMaskBit(byte maskValue, byte bit) {
        return (maskValue & bit) == bit;
    }

    private static byte setMaskBit(byte mask, byte bit, boolean value) {
        return value ? (byte) (mask | bit) : (byte) (mask & ~bit);
    }

    private static byte getMaskByte(byte data, byte byteMask, int offset) {
        return (byte) ((data & byteMask) >> offset);
    }

    private static byte setMaskByte(byte data, byte byteMask, int offset, byte newValue) {
        return (byte) ((data & ~byteMask) | ((newValue << offset) & byteMask));
    }

    public void setNotifyAboutChanges(boolean notifyAboutChanges) {
        if (!notifyAboutChanges) {
            // Ask future metadata changes to be cached
            openBatches.incrementAndGet();
            return;
        }
        Int2ObjectArrayMap<Metadata.Entry<?>> entries;
        synchronized (this.notNotifiedChanges) {
            if (openBatches.updateAndGet(open -> Math.max(0, open - 1)) > 0) return; // Still nested
            if (this.notNotifiedChanges.isEmpty()) return;
            entries = new Int2ObjectArrayMap<>(this.notNotifiedChanges);
            this.notNotifiedChanges.clear();
        }
        this.changesListener.accept(entries);
    }

    /**
     * Copies the entries shared by every entity type, the {@link MetadataDef} base indices, from
     * {@code source}. Used by {@link Entity#switchEntityType(EntityType)}, which otherwise drops them.
     *
     * @param source the holder to copy the shared entries from
     */
    void copySharedEntries(MetadataHolder source) {
        final var sourceEntries = source.entries;
        final int length = Math.min(MetadataDef.count(MetadataDef.class), sourceEntries.length);
        if (length <= 0) return;
        synchronized (this) {
            final var copy = Arrays.copyOf(this.entries, Math.max(this.entries.length, length));
            System.arraycopy(sourceEntries, 0, copy, 0, length);
            this.entries = copy;
        }
    }

    private Metadata.@Nullable Entry<?> entryAt(int id) {
        final var entries = this.entries;
        return id >= 0 && id < entries.length ? entries[id] : null;
    }

    private void putEntry(int id, Metadata.Entry<?> entry) {
        final var copy = Arrays.copyOf(this.entries, Math.max(this.entries.length, id + 1));
        copy[id] = entry;
        this.entries = copy;
    }

    public Map<Integer, Metadata.Entry<?>> getEntries() {
        final var entries = this.entries;
        int[] ids = new int[entries.length];
        Metadata.Entry<?>[] values = new Metadata.Entry<?>[entries.length];
        int count = 0;
        for (int id = 0; id < entries.length; id++) {
            final var entry = entries[id];
            if (entry == null) continue; // skip gaps between metadata ids
            ids[count] = id;
            values[count] = entry;
            count++;
        }
        return Map.copyOf(new Int2ObjectArrayMap<>(ids, values, count));
    }

    @SuppressWarnings("JavacQuirks")
    static final Map<EntityType, BiFunction<@Nullable Entity, MetadataHolder, ? extends EntityMeta>> ENTITY_META_SUPPLIER = Map.ofEntries(
            entry(EntityType.ACACIA_BOAT, BoatMeta::new),
            entry(EntityType.ACACIA_CHEST_BOAT, BoatMeta::new),
            entry(EntityType.ALLAY, AllayMeta::new),
            entry(EntityType.AREA_EFFECT_CLOUD, AreaEffectCloudMeta::new),
            entry(EntityType.ARMOR_STAND, ArmorStandMeta::new),
            entry(EntityType.ARROW, ArrowMeta::new),
            entry(EntityType.BAMBOO_RAFT, BoatMeta::new),
            entry(EntityType.BAMBOO_CHEST_RAFT, BoatMeta::new),
            entry(EntityType.BIRCH_BOAT, BoatMeta::new),
            entry(EntityType.BIRCH_CHEST_BOAT, BoatMeta::new),
            entry(EntityType.BLOCK_DISPLAY, BlockDisplayMeta::new),
            entry(EntityType.BREEZE_WIND_CHARGE, BreezeWindChargeMeta::new),
            entry(EntityType.CHERRY_BOAT, BoatMeta::new),
            entry(EntityType.CHERRY_CHEST_BOAT, BoatMeta::new),
            entry(EntityType.DARK_OAK_BOAT, BoatMeta::new),
            entry(EntityType.DARK_OAK_CHEST_BOAT, BoatMeta::new),
            entry(EntityType.DRAGON_FIREBALL, DragonFireballMeta::new),
            entry(EntityType.END_CRYSTAL, EndCrystalMeta::new),
            entry(EntityType.ENDER_DRAGON, EnderDragonMeta::new),
            entry(EntityType.EVOKER_FANGS, EvokerFangsMeta::new),
            entry(EntityType.EXPERIENCE_ORB, ExperienceOrbMeta::new),
            entry(EntityType.EYE_OF_ENDER, EyeOfEnderMeta::new),
            entry(EntityType.FALLING_BLOCK, FallingBlockMeta::new),
            entry(EntityType.FIREBALL, FireballMeta::new),
            entry(EntityType.FIREWORK_ROCKET, FireworkRocketMeta::new),
            entry(EntityType.GLOW_ITEM_FRAME, GlowItemFrameMeta::new),
            entry(EntityType.INTERACTION, InteractionMeta::new),
            entry(EntityType.ITEM, ItemEntityMeta::new),
            entry(EntityType.ITEM_DISPLAY, ItemDisplayMeta::new),
            entry(EntityType.ITEM_FRAME, ItemFrameMeta::new),
            entry(EntityType.JUNGLE_BOAT, BoatMeta::new),
            entry(EntityType.JUNGLE_CHEST_BOAT, BoatMeta::new),
            entry(EntityType.LEASH_KNOT, LeashKnotMeta::new),
            entry(EntityType.LIGHTNING_BOLT, LightningBoltMeta::new),
            entry(EntityType.LINGERING_POTION, LingeringPotionMeta::new),
            entry(EntityType.LLAMA_SPIT, LlamaSpitMeta::new),
            entry(EntityType.MANGROVE_BOAT, BoatMeta::new),
            entry(EntityType.MANGROVE_CHEST_BOAT, BoatMeta::new),
            entry(EntityType.MANNEQUIN, MannequinMeta::new),
            entry(EntityType.MARKER, MarkerMeta::new),
            entry(EntityType.TEXT_DISPLAY, TextDisplayMeta::new),
            entry(EntityType.OAK_BOAT, BoatMeta::new),
            entry(EntityType.OAK_CHEST_BOAT, BoatMeta::new),
            entry(EntityType.OMINOUS_ITEM_SPAWNER, OminousItemSpawnerMeta::new),
            entry(EntityType.PAINTING, PaintingMeta::new),
            entry(EntityType.PALE_OAK_BOAT, BoatMeta::new),
            entry(EntityType.PALE_OAK_CHEST_BOAT, BoatMeta::new),
            entry(EntityType.TNT, PrimedTntMeta::new),
            entry(EntityType.SHULKER_BULLET, ShulkerBulletMeta::new),
            entry(EntityType.SMALL_FIREBALL, SmallFireballMeta::new),
            entry(EntityType.SNOWBALL, SnowballMeta::new),
            entry(EntityType.SPECTRAL_ARROW, SpectralArrowMeta::new),
            entry(EntityType.SPLASH_POTION, SplashPotionMeta::new),
            entry(EntityType.SPRUCE_BOAT, BoatMeta::new),
            entry(EntityType.SPRUCE_CHEST_BOAT, BoatMeta::new),
            entry(EntityType.EGG, ThrownEggMeta::new),
            entry(EntityType.ENDER_PEARL, ThrownEnderPearlMeta::new),
            entry(EntityType.EXPERIENCE_BOTTLE, ThrownExperienceBottleMeta::new),
            entry(EntityType.TRIDENT, ThrownTridentMeta::new),
            entry(EntityType.TRADER_LLAMA, TraderLlamaMeta::new),
            entry(EntityType.WIND_CHARGE, WindChargeMeta::new),
            entry(EntityType.WITHER_SKULL, WitherSkullMeta::new),
            entry(EntityType.PLAYER, PlayerMeta::new),
            entry(EntityType.FISHING_BOBBER, FishingHookMeta::new)
    );

    @ApiStatus.Internal
    public static EntityMeta createMeta(EntityType entityType, @Nullable Entity entity, MetadataHolder metadata) {
        final var supplier = ENTITY_META_SUPPLIER.get(entityType);
        if (supplier != null) return supplier.apply(entity, metadata);
        // Types without a dedicated meta still have to answer getEntityMeta(). The entity registry marks
        // the LIVING and PLAYER packet types (EntityType#shouldSendAttributes), and only those own the
        // LivingEntityMeta indices; everything else only owns the shared EntityMeta ones.
        return entityType.shouldSendAttributes()
                ? new LivingEntityMeta(entity, metadata)
                : new EntityMeta(entity, metadata);
    }
}
