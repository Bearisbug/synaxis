package io.github.bearisbug.synaxis.command.builder.arguments.minecraft;

import io.github.bearisbug.synaxis.command.ArgumentParserType;
import io.github.bearisbug.synaxis.command.CommandSender;
import io.github.bearisbug.synaxis.command.builder.arguments.Argument;
import io.github.bearisbug.synaxis.command.builder.exception.ArgumentSyntaxException;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.block.BlockUtils;

public class ArgumentBlockState extends Argument<Block> {

    public static final int NO_BLOCK = 1;
    public static final int INVALID_BLOCK = 2;
    public static final int INVALID_PROPERTY = 3;
    public static final int INVALID_PROPERTY_VALUE = 4;

    public ArgumentBlockState(String id) {
        super(id, true, false);
    }

    @Override
    public Block parse(CommandSender sender, String input) throws ArgumentSyntaxException {
        return staticParse(input);
    }

    @Override
    public ArgumentParserType parser() {
        return ArgumentParserType.BLOCK_STATE;
    }

    /**
     * @deprecated use {@link Argument#parse(CommandSender, Argument)}
     */
    @Deprecated
    public static Block staticParse(String input) throws ArgumentSyntaxException {
        final int nbtIndex = input.indexOf("[");
        if (nbtIndex == 0)
            throw new ArgumentSyntaxException("No block type", input, NO_BLOCK);

        if (nbtIndex == -1) {
            // Only block name
            Block block = Block.fromKey(input);
            if (block == null)
                throw new ArgumentSyntaxException("Invalid block type", input, INVALID_BLOCK);
            return block;
        } else {
            if (!input.endsWith("]"))
                throw new ArgumentSyntaxException("Property list need to end with ]", input, INVALID_PROPERTY);
            // Block state
            final String blockName = input.substring(0, nbtIndex);
            Block block = Block.fromKey(blockName);
            if (block == null)
                throw new ArgumentSyntaxException("Invalid block type", input, INVALID_BLOCK);

            // Compute properties
            final String query = input.substring(nbtIndex);
            final var propertyMap = BlockUtils.parseProperties(query);
            try {
                return block.withProperties(propertyMap);
            } catch (IllegalArgumentException _) {
                throw new ArgumentSyntaxException("Invalid property values", input, INVALID_PROPERTY_VALUE);
            }
        }
    }

    @Override
    public String toString() {
        return String.format("BlockState<%s>", getId());
    }
}
