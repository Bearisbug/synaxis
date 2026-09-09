package dev.gameengine.item.component;

import dev.gameengine.component.DataComponent;
import dev.gameengine.component.DataComponents;
import dev.gameengine.item.Material;

import java.util.List;
import java.util.Map;

public class PotDecorationsTest extends AbstractItemComponentRegistriesTest<PotDecorations> {
    @Override
    protected DataComponent<PotDecorations> component() {
        return DataComponents.POT_DECORATIONS;
    }

    @Override
    protected List<Map.Entry<String, PotDecorations>> directReadWriteEntries() {
        return List.of(
                Map.entry("instance", PotDecorations.EMPTY),
                Map.entry("one", new PotDecorations(Material.DIAMOND, PotDecorations.DEFAULT_ITEM, PotDecorations.DEFAULT_ITEM, PotDecorations.DEFAULT_ITEM)),
                Map.entry("two", new PotDecorations(Material.DIAMOND, Material.DIAMOND, PotDecorations.DEFAULT_ITEM, PotDecorations.DEFAULT_ITEM)),
                Map.entry("three", new PotDecorations(Material.DIAMOND, Material.DIAMOND, Material.DIAMOND, PotDecorations.DEFAULT_ITEM)),
                Map.entry("four", new PotDecorations(Material.DIAMOND, Material.DIAMOND, Material.DIAMOND, Material.DIAMOND))
        );
    }
}
