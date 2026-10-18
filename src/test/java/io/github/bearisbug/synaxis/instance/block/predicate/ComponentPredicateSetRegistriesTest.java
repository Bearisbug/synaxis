package io.github.bearisbug.synaxis.instance.block.predicate;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.Result;
import io.github.bearisbug.synaxis.codec.Transcoder;
import io.github.bearisbug.synaxis.component.DataComponent;
import io.github.bearisbug.synaxis.component.DataComponentMap;
import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.item.component.EnchantmentList;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.registry.RegistryTranscoder;
import io.github.bearisbug.synaxis.testing.RegistriesTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static io.github.bearisbug.synaxis.codec.CodecAssertions.assertOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DataComponentPredicate.Exists} is stored as the name of its component and an empty map, the way vanilla
 * writes it, and a predicate type of the same name is tried first when it is read back.
 */
@RegistriesTest
public class ComponentPredicateSetRegistriesTest {

    /**
     * Fifteen components share their name with a predicate type, and four of those types cannot read an empty map: a
     * stored item carrying such a predicate refused the login of its player.
     */
    @Test
    public void anExistsPredicateNamedLikeAPredicateTypeLoadsBack(Registries registries) {
        final Transcoder<JsonElement> coder = new RegistryTranscoder<>(Transcoder.JSON, registries);
        final List<String> unreadable = new ArrayList<>();
        for (RegistryKey<Codec<? extends DataComponentPredicate>> type : registries.componentPredicateTypes().keys()) {
            final DataComponent<?> component = Objects.requireNonNull(DataComponent.fromKey(type.key()), type.key().asString());
            final ComponentPredicateSet stored = ComponentPredicateSet.EMPTY.add(new DataComponentPredicate.Exists(component));
            final JsonElement encoded = assertOk(ComponentPredicateSet.CODEC.encode(coder, stored));
            if (!(ComponentPredicateSet.CODEC.decode(coder, encoded) instanceof Result.Ok<ComponentPredicateSet>))
                unreadable.add(type.key().asString());
        }

        assertEquals(List.of(), unreadable);
    }

    /** Read back as a predicate of the same name or as itself, it holds for the items that have the component. */
    @Test
    public void theLoadedPredicateTestsForThePresenceOfTheComponent(Registries registries) {
        final Transcoder<JsonElement> coder = new RegistryTranscoder<>(Transcoder.JSON, registries);

        final ComponentPredicateSet enchanted = roundTrip(coder, DataComponents.ENCHANTMENTS);
        assertEquals(ComponentPredicateSet.EMPTY.add(new DataComponentPredicate.Exists(DataComponents.ENCHANTMENTS)), enchanted);
        assertTrue(enchanted.test(DataComponentMap.builder().set(DataComponents.ENCHANTMENTS,
                new EnchantmentList(RegistryKey.unsafeOf("minecraft:sharpness"), 5))));
        assertFalse(enchanted.test(DataComponentMap.EMPTY));

        final ComponentPredicateSet damaged = roundTrip(coder, DataComponents.DAMAGE);
        assertTrue(damaged.test(DataComponentMap.builder().set(DataComponents.DAMAGE, 3)));
        assertFalse(damaged.test(DataComponentMap.EMPTY));
    }

    /** Only an empty map stands for a presence check: anything else is the predicate type it is named after. */
    @Test
    public void aSameNamedPredicateThatDoesNotDecodeIsStillAnError(Registries registries) {
        final Transcoder<JsonElement> coder = new RegistryTranscoder<>(Transcoder.JSON, registries);

        assertInstanceOf(Result.Error.class, ComponentPredicateSet.CODEC.decode(coder,
                JsonParser.parseString("{\"minecraft:enchantments\": {\"levels\": 1}}")));
    }

    /** A name that is neither a predicate type nor a component is an error of the read, not an exception out of it. */
    @Test
    public void aNameThatIsNeitherAPredicateTypeNorAComponentIsAnError(Registries registries) {
        final Transcoder<JsonElement> coder = new RegistryTranscoder<>(Transcoder.JSON, registries);

        assertInstanceOf(Result.Error.class, ComponentPredicateSet.CODEC.decode(coder,
                JsonParser.parseString("{\"synaxis:no_such_component\": {}}")));
    }

    private static ComponentPredicateSet roundTrip(Transcoder<JsonElement> coder, DataComponent<?> component) {
        final ComponentPredicateSet stored = ComponentPredicateSet.EMPTY.add(new DataComponentPredicate.Exists(component));
        return assertOk(ComponentPredicateSet.CODEC.decode(coder, assertOk(ComponentPredicateSet.CODEC.encode(coder, stored))));
    }
}
