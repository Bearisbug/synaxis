package io.github.bearisbug.synaxis.item;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;
import io.github.bearisbug.synaxis.component.DataComponent;
import io.github.bearisbug.synaxis.component.DataComponentMap;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;

/**
 * The 26.2 item stack template: a nested item that is never empty, held by components and particles
 * ({@code use_remainder}, {@code charged_projectiles}, {@code bundle_contents}, {@code sulfur_cube_content}, each
 * slot of {@code container}, and {@link io.github.bearisbug.synaxis.particle.Particle.Item}).
 * <p>
 * On the network a template is the material, a VarInt count and the component patch, with no prefix for an empty
 * item. A 26.2 client refuses to build a template of count 0 or of air and drops the connection while decoding one,
 * so the components holding templates refuse air when they are set. {@link #CODEC} writes the count only when it is
 * not 1, as 26.2 does, and also reads an item written as its id alone.
 * <p>
 * Data that may hold an empty item, such as an inventory slot, uses {@link ItemStack#NETWORK_TYPE} and
 * {@link ItemStack#CODEC} instead.
 */
public interface ItemStackTemplate {
    NetworkBuffer.Type<ItemStack> NETWORK_TYPE = NetworkBufferTemplate.template(
            Material.NETWORK_TYPE, ItemStack::material,
            NetworkBuffer.VAR_INT, ItemStack::amount,
            DataComponent.PATCH_NETWORK_TYPE, ItemStack::componentPatch,
            ItemStack::of);
    Codec<ItemStack> CODEC = StructCodec.struct(
                    "id", Material.CODEC, ItemStack::material,
                    "count", Codec.INT.optional(1), ItemStack::amount,
                    "components", DataComponent.PATCH_CODEC.optional(DataComponentMap.EMPTY), ItemStack::componentPatch,
                    ItemStack::of)
            .orElse(Material.CODEC.transform(ItemStack::of, ItemStack::material));
}
