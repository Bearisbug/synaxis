package io.github.bearisbug.synaxis.item.component;

import io.github.bearisbug.synaxis.component.DataComponent;
import io.github.bearisbug.synaxis.component.DataComponents;

import java.util.List;
import java.util.Map;

public class SeededContainerLootTest extends AbstractItemComponentRegistriesTest<SeededContainerLoot> {

    @Override
    protected DataComponent<SeededContainerLoot> component() {
        return DataComponents.CONTAINER_LOOT;
    }

    @Override
    protected List<Map.Entry<String, SeededContainerLoot>> directReadWriteEntries() {
        return List.of(
                Map.entry("instance", new SeededContainerLoot("loot_table", 1234567890L))
        );
    }

}
