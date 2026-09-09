package dev.gameengine.item;

import dev.gameengine.codec.Codec;
import dev.gameengine.component.DataComponent;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;

public interface ItemStackTemplate {
    NetworkBuffer.Type<ItemStack> NETWORK_TYPE = NetworkBufferTemplate.template(
            Material.NETWORK_TYPE, ItemStack::material,
            NetworkBuffer.VAR_INT, ItemStack::amount,
            DataComponent.PATCH_NETWORK_TYPE, ItemStack::componentPatch,
            ItemStack::of);
    Codec<ItemStack> CODEC = ItemStack.CODEC
            .orElse(Material.CODEC.transform(ItemStack::of, ItemStack::material));
}
