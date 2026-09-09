package dev.gameengine.command.builder.arguments.minecraft.registry;

import dev.gameengine.command.ArgumentParserType;
import dev.gameengine.command.builder.arguments.minecraft.SuggestionType;
import dev.gameengine.entity.EntityType;

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
    public EntityType getRegistry(String value) {
        return EntityType.fromKey(value);
    }

    @Override
    public String toString() {
        return String.format("EntityType<%s>", getId());
    }
}
