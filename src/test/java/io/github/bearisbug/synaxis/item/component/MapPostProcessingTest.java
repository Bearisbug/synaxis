package io.github.bearisbug.synaxis.item.component;

import io.github.bearisbug.synaxis.component.DataComponent;
import io.github.bearisbug.synaxis.component.DataComponents;

import java.util.List;
import java.util.Map;

public class MapPostProcessingTest extends AbstractItemComponentRegistriesTest<MapPostProcessing> {
    @Override
    protected DataComponent<MapPostProcessing> component() {
        return DataComponents.MAP_POST_PROCESSING;
    }

    @Override
    protected List<Map.Entry<String, MapPostProcessing>> directReadWriteEntries() {
        return List.of(
                Map.entry("lock", MapPostProcessing.LOCK),
                Map.entry("scale", MapPostProcessing.SCALE)
        );
    }
}
