package dev.gameengine.item.component;

import dev.gameengine.adventure.EngineAdventure;
import dev.gameengine.codec.Transcoder;
import dev.gameengine.component.DataComponent;
import dev.gameengine.component.DataComponents;
import dev.gameengine.potion.PotionEffect;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static dev.gameengine.codec.CodecAssertions.assertOk;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class SuspiciousStewEffectsTest extends AbstractItemComponentRegistriesTest<SuspiciousStewEffects> {

    @Override
    protected DataComponent<SuspiciousStewEffects> component() {
        return DataComponents.SUSPICIOUS_STEW_EFFECTS;
    }

    @Override
    protected List<Map.Entry<String, SuspiciousStewEffects>> directReadWriteEntries() {
        return List.of(
                Map.entry("empty", SuspiciousStewEffects.EMPTY),
                Map.entry("single", new SuspiciousStewEffects(new SuspiciousStewEffects.Effect(PotionEffect.ABSORPTION, 100))),
                Map.entry("multi", new SuspiciousStewEffects(List.of(
                        new SuspiciousStewEffects.Effect(PotionEffect.ABSORPTION, 100),
                        new SuspiciousStewEffects.Effect(PotionEffect.STRENGTH, 2)
                )))
        );
    }

    @Test
    void nbtReadDefaultDuration() throws Exception {
        var value = assertOk(DataComponents.SUSPICIOUS_STEW_EFFECTS.decode(Transcoder.NBT, EngineAdventure.tagStringIO().asTag("""
                [{"id": "minecraft:strength"}]
                """)));
        var expected = new SuspiciousStewEffects(new SuspiciousStewEffects.Effect(PotionEffect.STRENGTH, 160));
        assertEquals(expected, value);
    }
}
