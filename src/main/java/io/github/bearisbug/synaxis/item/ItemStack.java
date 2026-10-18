package io.github.bearisbug.synaxis.item;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.DataComponentValue;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.event.HoverEventSource;
import net.kyori.adventure.util.RGBLike;
import io.github.bearisbug.synaxis.adventure.EngineDataComponentValue;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.Result;
import io.github.bearisbug.synaxis.codec.StructCodec;
import io.github.bearisbug.synaxis.codec.Transcoder;
import io.github.bearisbug.synaxis.component.DataComponent;
import io.github.bearisbug.synaxis.component.DataComponentMap;
import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.item.component.CustomData;
import io.github.bearisbug.synaxis.item.component.CustomModelData;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.registry.RegistryTranscoder;
import io.github.bearisbug.synaxis.tag.Tag;
import io.github.bearisbug.synaxis.tag.TagReadable;
import io.github.bearisbug.synaxis.utils.Unit;
import io.github.bearisbug.synaxis.utils.validate.Check;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;
import java.util.function.UnaryOperator;

/**
 * Represents an immutable item to be placed inside {@link io.github.bearisbug.synaxis.inventory.PlayerInventory},
 * {@link io.github.bearisbug.synaxis.inventory.Inventory} or even on the ground {@link io.github.bearisbug.synaxis.entity.ItemEntity}.
 * <p>
 * An item stack cannot be null, {@link ItemStack#AIR} should be used instead.
 */
// Static fields intentionally construct the implementation subclass; the cycle cannot
// race because the implementation types are only ever reached through this interface
@SuppressWarnings("ClassInitializationDeadlock")
public sealed interface ItemStack extends TagReadable, DataComponent.Holder, HoverEventSource<HoverEvent.ShowItem>
        permits ItemStackImpl {

    NetworkBuffer.Type<ItemStack> NETWORK_TYPE = ItemStackImpl.networkType(DataComponent.PATCH_NETWORK_TYPE);
    NetworkBuffer.Type<ItemStack> UNTRUSTED_NETWORK_TYPE = ItemStackImpl.networkType(DataComponent.UNTRUSTED_PATCH_NETWORK_TYPE);
    NetworkBuffer.Type<ItemStack> STRICT_NETWORK_TYPE = NETWORK_TYPE.transform(itemStack -> {
        Check.argCondition(itemStack.amount() == 0 || itemStack.isAir(), "ItemStack cannot be empty");
        return itemStack;
    }, itemStack -> {
        Check.argCondition(itemStack.amount() == 0 || itemStack.isAir(), "ItemStack cannot be empty");
        return itemStack;
    });
    Codec<ItemStack> CODEC = new StructCodec<>() {
        // These exist because Mojang optionally decodes count (ie missing will default to 1),
        // but when encoding they always include the 1. We want to preserve this behavior and
        // since it's currently a one off we can just do it here in a gross way.
        private static final StructCodec<ItemStack> DECODER = StructCodec.struct(
                "id", Material.CODEC, ItemStack::material,
                "count", Codec.INT.optional(1), ItemStack::amount,
                "components", DataComponent.PATCH_CODEC.optional(DataComponentMap.EMPTY), ItemStack::componentPatch,
                ItemStack::of);
        private static final StructCodec<ItemStack> ENCODER = StructCodec.struct(
                "id", Material.CODEC, ItemStack::material,
                "count", Codec.INT, ItemStack::amount,
                "components", DataComponent.PATCH_CODEC.optional(DataComponentMap.EMPTY), ItemStack::componentPatch,
                ItemStack::of);

        @Override
        public <D> Result<ItemStack> decodeFromMap(Transcoder<D> coder, Transcoder.MapLike<D> map) {
            return DECODER.decodeFromMap(coder, map);
        }

        @Override
        public <D> Result<D> encodeToMap(Transcoder<D> coder, ItemStack value, Transcoder.MapBuilder<D> map) {
            return ENCODER.encodeToMap(coder, value, map);
        }
    };

    /**
     * Constant AIR item. Should be used instead of 'null'.
     */
    ItemStack AIR = new ItemStackImpl(Material.AIR, 0, DataComponentMap.EMPTY);

    @Contract(value = "_ -> new", pure = true)
    static Builder builder(Material material) {
        return new ItemStackImpl.Builder(material, 1);
    }

    @Contract(value = "_ -> new", pure = true)
    static ItemStack of(Material material) {
        return of(material, 1);
    }

    @Contract(value = "_ ,_ -> new", pure = true)
    static ItemStack of(Material material, int amount) {
        return ItemStackImpl.create(material, amount);
    }

    @Contract(value = "_ ,_ -> new", pure = true)
    static ItemStack of(Material material, DataComponentMap components) {
        return ItemStackImpl.create(material, 1, components);
    }

    @Contract(value = "_ ,_, _ -> new", pure = true)
    static ItemStack of(Material material, int amount, DataComponentMap components) {
        return ItemStackImpl.create(material, amount, components);
    }

    /**
     * Converts this item to an NBT tag containing the id (material), count (amount), and components.
     *
     * @param nbtCompound The nbt representation of the item
     * @param registries  The registries to use when decoding
     */
    static ItemStack fromItemNBT(CompoundBinaryTag nbtCompound, Registries registries) {
        final Transcoder<BinaryTag> coder = new RegistryTranscoder<>(Transcoder.NBT, registries);
        return CODEC.decode(coder, nbtCompound).orElseThrow("Invalid NBT for ItemStack");
    }

    @Contract(pure = true)
    Material material();

    @Contract(pure = true)
    int amount();

    @Contract(pure = true)
    DataComponentMap componentPatch();

    /**
     * Returns the resolved component map, including material defaults and explicit overrides.
     *
     * @return the complete resolved component map
     */
    @Contract(pure = true)
    DataComponentMap components();

    @Contract(value = "_, -> new", pure = true)
    ItemStack with(Consumer<Builder> consumer);

    /**
     * Returns a new ItemStack with the given Material set.
     *
     * @param material The material to apply
     * @return A new item stack with the new material
     *
     * <p>Note: When material is AIR, the resulting amount will always be 0. For others, the amount will be >0, e.g. 1 if 0 before</p>
     */
    @Contract(value = "_, -> new", pure = true)
    ItemStack withMaterial(Material material);

    @Contract(value = "_, -> new", pure = true)
    ItemStack withAmount(int amount);

    @Contract(value = "_, -> new", pure = true)
    default ItemStack withAmount(IntUnaryOperator intUnaryOperator) {
        return withAmount(intUnaryOperator.applyAsInt(amount()));
    }

    /**
     * <p>Returns a new ItemStack with the given component set to the given value.</p>
     *
     * <p>Note: this should not be used to remove components, see {@link #without(DataComponent)}.</p>
     */
    @Contract(value = "_, _ -> new", pure = true)
    <T> ItemStack with(DataComponent<T> component, T value);

    /**
     * Returns a new ItemStack with the given {@link Unit} component applied.
     *
     * @param component The unit component to apply
     * @return A new ItemStack with the given component applied
     */
    @Contract(value = "_ -> new", pure = true)
    default ItemStack with(DataComponent<Unit> component) {
        return with(component, Unit.INSTANCE);
    }

    /**
     * Applies a transformation to the value of a component, only if present.
     * <p>
     * Without the component the operator is not called and this item is returned: unlike the vanilla
     * {@code ItemStack#update}, there is no default value to start from.
     *
     * @param component The component type to modify
     * @param operator  The transformation function
     * @param <T>       The component type
     * @return A new ItemStack if the component was transformed, otherwise this.
     */
    default <T> ItemStack update(DataComponent<T> component, UnaryOperator<T> operator) {
        T value = get(component);
        if (value == null) return this;
        return with(component, operator.apply(value));
    }

    /**
     * Applies a transformation to the value of a component, only if present.
     * <p>
     * A call passing an implicitly typed lambda is ambiguous with {@link #with(DataComponent, Object)} and does not
     * compile: use {@link #update(DataComponent, UnaryOperator)}, which this delegates to.
     *
     * @param component The component type to modify
     * @param operator  The transformation function
     * @param <T>       The component type
     * @return A new ItemStack if the component was transformed, otherwise this.
     * @deprecated use {@link #update(DataComponent, UnaryOperator)}
     */
    @Deprecated(forRemoval = true)
    default <T> ItemStack with(DataComponent<T> component, UnaryOperator<T> operator) {
        return update(component, operator);
    }

    /**
     * <p>Removes the given component from this item. This will explicitly remove the component from the item, as opposed
     * to reverting back to the default.</p>
     *
     * <p>For example, if {@link DataComponents#FOOD} is applied to an apple, and then this method is called,
     * the resulting itemstack will not be a food item at all, as opposed to returning to the default apple
     * food type. Likewise, if this method is called on a default apple, it will no longer be a food item.</p>
     *
     * @param component The component to remove
     * @return A new ItemStack without the given component
     */
    @Contract(value = "_, -> new", pure = true)
    ItemStack without(DataComponent<?> component);

    /**
     * Returns a new ItemStack with the given component reset to the material default.
     *
     * @param component The component to reset
     * @return A new ItemStack without an explicit override for the given component
     */
    @Contract(value = "_, -> new", pure = true)
    ItemStack reset(DataComponent<?> component);

    @Contract(value = "_, -> new", pure = true)
    default ItemStack withCustomName(Component customName) {
        return with(DataComponents.CUSTOM_NAME, customName);
    }

    @Contract(value = "_, -> new", pure = true)
    default ItemStack withLore(Component... lore) {
        return with(DataComponents.LORE, List.of(lore));
    }

    @Contract(value = "_, -> new", pure = true)
    default ItemStack withLore(List<Component> lore) {
        return with(DataComponents.LORE, lore);
    }

    /**
     * Sets the item model, see {@link Builder#itemModel(String)}.
     *
     * @param model the namespaced id of the model, such as {@code synaxis:unit/archer}
     * @return the item with that model
     * @throws IllegalArgumentException if the model is not a namespaced id, which a 26.2 client cannot parse
     */
    @Contract(value = "_, -> new", pure = true)
    default ItemStack withItemModel(String model) {
        return with(DataComponents.ITEM_MODEL, model);
    }

    @Contract(value = "_, _, _, _ -> new", pure = true)
    default ItemStack withCustomModelData(List<Float> floats, List<Boolean> flags, List<String> strings, List<RGBLike> colors) {
        return with(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(floats, flags, strings, colors));
    }

    @Contract(value = "_ -> new", pure = true)
    default ItemStack withGlowing(boolean glowing) {
        return with(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, glowing);
    }

    /**
     * Hides the tooltip lines of every component that adds some, see {@link Builder#hideExtraTooltip()}.
     *
     * @return the item showing only its name and lore
     */
    @Contract(value = "-> new", pure = true)
    default ItemStack withoutExtraTooltip() {
        return builder().hideExtraTooltip().build();
    }

    /**
     * Gets the maximum stack size of this item.
     *
     * @return the {@link DataComponents#MAX_STACK_SIZE} of this item, 1 without it as for a 26.2 client; every
     * vanilla item prototype has the component
     */
    @Contract(pure = true)
    default int maxStackSize() {
        return get(DataComponents.MAX_STACK_SIZE, 1);
    }

    @Contract(value = "_ -> new", pure = true)
    default ItemStack withMaxStackSize(int maxStackSize) {
        return with(DataComponents.MAX_STACK_SIZE, maxStackSize);
    }

    /**
     * Sets a tag in the custom data of this item, see {@link CustomData#withTag(Tag, Object)}.
     */
    @Contract(value = "_, _ -> new", pure = true)
    default <T> ItemStack withTag(Tag<T> tag, @Nullable T value) {
        return with(DataComponents.CUSTOM_DATA, get(DataComponents.CUSTOM_DATA, CustomData.EMPTY).withTag(tag, value));
    }

    @Override
    @Contract(pure = true)
    default <T> @UnknownNullability T getTag(Tag<T> tag) {
        return get(DataComponents.CUSTOM_DATA, CustomData.EMPTY).getTag(tag);
    }

    /**
     * Removes some of the stack.
     *
     * @param amount the amount to remove
     * @return the item with the remaining amount, {@link #AIR} when nothing remains
     */
    @Contract(value = "_, -> new", pure = true)
    ItemStack consume(int amount);

    /**
     * Wears this item down, as a vanilla damageable item.
     * <p>
     * An item with {@link DataComponents#UNBREAKABLE}, or without {@link DataComponents#MAX_DAMAGE} or
     * {@link DataComponents#DAMAGE}, is returned as it is.
     *
     * @param amount the damage to add, negative to repair; the damage does not go below 0
     * @return the item with its new damage, {@link #AIR} (the whole stack) once the damage reaches the max damage
     */
    @Contract(value = "_, -> new", pure = true)
    ItemStack damage(int amount);

    @Contract(pure = true)
    default boolean isAir() {
        return material() == Material.AIR;
    }

    @Contract(pure = true)
    boolean isSimilar(ItemStack itemStack);

    /**
     * Converts this itemstack back into a builder (starting from the current state).
     *
     * @return this itemstack, as a builder.
     */
    ItemStack.Builder builder();

    /**
     * Converts this item to an NBT tag containing the id (material), count (amount), and components (diff)
     *
     * @param registries The registries to use.
     * @return The nbt representation of the item
     */
    CompoundBinaryTag toItemNBT(Registries registries);

    @Override
    default HoverEvent<HoverEvent.ShowItem> asHoverEvent(UnaryOperator<HoverEvent.ShowItem> op) {
        if (componentPatch().isEmpty())
            return HoverEvent.showItem(op.apply(HoverEvent.ShowItem.showItem(material(), amount())));

        final Map<Key, DataComponentValue> dataComponents = new HashMap<>();
        for (final DataComponent.Value entry : componentPatch().entrySet()) {
            // A component without a codec has no NBT form for the client to read
            if (!entry.component().isSerialized()) continue;
            dataComponents.put(entry.component().key(), entry.value() == null
                    ? EngineDataComponentValue.removed()
                    : EngineDataComponentValue.dataComponentValue(entry.value()));
        }
        return HoverEvent.showItem(op.apply(HoverEvent.ShowItem.showItem(material(), amount(), dataComponents)));
    }

    // These functions are mirrors of ComponentHolder, but we can't actually implement that interface
    // because it conflicts with DataComponent.Holder.

    @SuppressWarnings("PreferredInterfaceType")
    // wider type kept for binary compatibility until the next breaking release
    static Collection<Component> textComponents(ItemStack itemStack) {
        final var components = new ArrayList<>(itemStack.get(DataComponents.LORE, List.of()));
        final var displayName = itemStack.get(DataComponents.CUSTOM_NAME);
        if (displayName != null) components.add(displayName);
        final var itemName = itemStack.get(DataComponents.ITEM_NAME);
        if (itemName != null) components.add(itemName);
        return List.copyOf(components);
    }

    static ItemStack copyWithOperator(ItemStack itemStack, UnaryOperator<Component> operator) {
        return itemStack
                .update(DataComponents.CUSTOM_NAME, operator)
                .update(DataComponents.ITEM_NAME, operator)
                .update(DataComponents.LORE, lines -> {
                    final var translatedComponents = new ArrayList<Component>();
                    lines.forEach(component -> translatedComponents.add(operator.apply(component)));
                    return translatedComponents;
                });
    }

    sealed interface Hash permits ItemStackHashImpl.Air, ItemStackHashImpl.Item {
        Hash AIR = new ItemStackHashImpl.Air();

        /**
         * Creates a hash of an {@link ItemStack} using the passed registries. Used in packets to identify the item.
         *
         * @param itemStack  The item stack to hash
         * @param registries The registries to use
         * @return the {@link Hash}
         */
        static Hash of(ItemStack itemStack, Registries registries) {
            return ItemStackHashImpl.of(new RegistryTranscoder<>(Transcoder.CRC32_HASH, registries), itemStack);
        }

        NetworkBuffer.Type<Hash> NETWORK_TYPE = ItemStackHashImpl.NETWORK_TYPE;
    }

    sealed interface Builder permits ItemStackImpl.Builder {

        @Contract(value = "_ -> this")
        Builder material(Material material);

        @Contract(value = "_ -> this")
        Builder amount(int amount);

        @Contract(value = "_, _ -> this")
        <T> Builder set(DataComponent<T> component, T value);

        @Contract(value = "_ -> this")
        default Builder set(DataComponent<Unit> component) {
            return set(component, Unit.INSTANCE);
        }

        @Contract(value = "_ -> this")
        Builder remove(DataComponent<?> component);

        default Builder customName(Component customName) {
            return set(DataComponents.CUSTOM_NAME, customName);
        }

        default Builder lore(Component... lore) {
            return set(DataComponents.LORE, List.of(lore));
        }

        default Builder lore(List<Component> lore) {
            return set(DataComponents.LORE, lore);
        }

        /**
         * Sets the item model, the model a client renders this item with.
         *
         * @param model the namespaced id of the model, such as {@code synaxis:unit/archer} (a-z 0-9 . _ - /)
         * @return this builder
         * @throws IllegalArgumentException if the model is not a namespaced id, which a 26.2 client cannot parse
         */
        default Builder itemModel(String model) {
            return set(DataComponents.ITEM_MODEL, model);
        }

        default Builder customModelData(List<Float> floats, List<Boolean> flags, List<String> strings, List<RGBLike> colors) {
            return set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(floats, flags, strings, colors));
        }

        default Builder glowing() {
            return set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }

        default Builder glowing(boolean glowing) {
            return set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, glowing);
        }

        default Builder maxStackSize(int maxStackSize) {
            return set(DataComponents.MAX_STACK_SIZE, maxStackSize);
        }

        /**
         * <p>Hides all components which append tooltip lines using {@link DataComponents#TOOLTIP_DISPLAY}.
         * The result should be an item with only name and lore.</p>
         * <p>The components it hides are added to those the display already hides, and a hidden tooltip stays
         * hidden.</p>
         *
         * @return this builder
         */
        Builder hideExtraTooltip();

        @Contract(value = "_, _ -> this")
        <T> Builder set(Tag<T> tag, @Nullable T value);

        default <T> void setTag(Tag<T> tag, @Nullable T value) {
            set(tag, value);
        }

        @Contract(value = "-> new", pure = true)
        ItemStack build();
    }
}
