package io.github.bearisbug.synaxis.item.predicate;

import net.kyori.adventure.nbt.BinaryTag;
import io.github.bearisbug.synaxis.adventure.EngineAdventure;
import io.github.bearisbug.synaxis.codec.Result;
import io.github.bearisbug.synaxis.codec.Transcoder;
import io.github.bearisbug.synaxis.instance.block.predicate.DataComponentPredicates;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.registry.RegistryTranscoder;
import io.github.bearisbug.synaxis.testing.RegistriesTest;
import org.junit.jupiter.api.Test;

import static io.github.bearisbug.synaxis.codec.CodecAssertions.assertOk;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The component predicates of an item predicate are inlined into it. A client sends item predicates in the lock of a
 * container and in container predicates, so one it gets wrong has to fail to decode, as it does in vanilla.
 */
@RegistriesTest
public class ItemPredicateRegistriesTest {

    /** It used to decode as a predicate with no component constraint at all, which matches every item. */
    @Test
    public void aComponentThatDoesNotDecodeIsAnError(Registries registries) {
        final Result<ItemPredicate> result = ItemPredicate.CODEC.decode(new RegistryTranscoder<>(Transcoder.NBT, registries),
                snbt("{components: {\"minecraft:damage\": \"x\"}}"));

        final String message = assertInstanceOf(Result.Error.class, result).message();
        assertTrue(message.contains("minecraft:damage"), message);
    }

    @Test
    public void anEmptyPredicateConstrainsNoComponent(Registries registries) {
        final ItemPredicate predicate = assertOk(ItemPredicate.CODEC.decode(
                new RegistryTranscoder<>(Transcoder.NBT, registries), snbt("{}")));

        assertEquals(new ItemPredicate(null, null, DataComponentPredicates.EMPTY), predicate);
    }

    private static BinaryTag snbt(String snbt) {
        return assertDoesNotThrow(() -> EngineAdventure.tagStringIO().asTag(snbt));
    }
}
