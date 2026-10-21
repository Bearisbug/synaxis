package io.github.bearisbug.synaxis.item.component;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.network.NetworkBuffer;

import java.util.List;

public record PotDecorations(
        Material back,
        Material left,
        Material right,
        Material front
) {
    public static final Material DEFAULT_ITEM = Material.BRICK;
    public static final PotDecorations EMPTY = new PotDecorations(DEFAULT_ITEM);

    public static final NetworkBuffer.Type<PotDecorations> NETWORK_TYPE = Material.NETWORK_TYPE.list(4).transform(PotDecorations::new, PotDecorations::asList);
    public static final Codec<PotDecorations> NBT_TYPE = Material.CODEC.list(4).transform(PotDecorations::new, PotDecorations::asList);

    public PotDecorations(List<Material> list) {
        this(getOrDefault(list, 0), getOrDefault(list, 1), getOrDefault(list, 2), getOrDefault(list, 3));
    }

    public PotDecorations(Material material) {
        this(material, material, material, material);
    }

    public List<Material> asList() {
        return List.of(back, left, right, front);
    }

    private static Material getOrDefault(List<Material> list, int index) {
        return index < list.size() ? list.get(index) : DEFAULT_ITEM;
    }
}
