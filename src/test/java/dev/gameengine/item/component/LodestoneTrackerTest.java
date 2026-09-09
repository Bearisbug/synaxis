package dev.gameengine.item.component;

import dev.gameengine.component.DataComponent;
import dev.gameengine.component.DataComponents;
import dev.gameengine.coordinate.Vec;

import java.util.List;
import java.util.Map;

public class LodestoneTrackerTest extends AbstractItemComponentRegistriesTest<LodestoneTracker> {

    @Override
    protected DataComponent<LodestoneTracker> component() {
        return DataComponents.LODESTONE_TRACKER;
    }

    @Override
    protected List<Map.Entry<String, LodestoneTracker>> directReadWriteEntries() {
        return List.of(
            Map.entry("tracked", new LodestoneTracker("minecraft:overworld", Vec.ZERO, true)),
            Map.entry("not tracked", new LodestoneTracker("minecraft:overworld", new Vec(1, 2, 3), false))
        );
    }

}
