package io.github.bearisbug.synaxis.command.builder.arguments.minecraft.registry;

import io.github.bearisbug.synaxis.command.ArgumentParserType;
import io.github.bearisbug.synaxis.command.builder.arguments.minecraft.SuggestionType;
import io.github.bearisbug.synaxis.entity.EntityType;
import org.jetbrains.annotations.Nullable;

/**
 * Represents an argument giving an {@link EntityType}.
 */
public class ArgumentEntityType extends ArgumentRegistry<EntityType> {

    public ArgumentEntityType(String id) {
        super(id);
        suggestionType = SuggestionType.SUMMONABLE_ENTITIES;
    }

    @Override
    public ArgumentParserType parser() {
        return ArgumentParserType.RESOURCE_LOCATION;
    }

    @Override
    public @Nullable EntityType getRegistry(String value) {
        return EntityType.fromKey(value);
    }

    @Override
    public String toString() {
        return String.format("EntityType<%s>", getId());
    }
}
