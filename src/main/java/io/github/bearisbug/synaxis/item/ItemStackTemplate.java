package io.github.bearisbug.synaxis.item;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.component.DataComponent;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;

public interface ItemStackTemplate {
    NetworkBuffer.Type<ItemStack> NETWORK_TYPE = NetworkBufferTemplate.template(
            Material.NETWORK_TYPE, ItemStack::material,
            NetworkBuffer.VAR_INT, ItemStack::amount,
            DataComponent.PATCH_NETWORK_TYPE, ItemStack::componentPatch,
            ItemStack::of);
    Codec<ItemStack> CODEC = ItemStack.CODEC
            .orElse(Material.CODEC.transform(ItemStack::of, ItemStack::material));
}
