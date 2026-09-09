package dev.gameengine.command.builder.arguments.minecraft;

import dev.gameengine.color.TeamColor;
import dev.gameengine.command.ArgumentParserType;
import dev.gameengine.command.CommandSender;
import dev.gameengine.command.builder.arguments.Argument;
import dev.gameengine.command.builder.exception.ArgumentSyntaxException;

/**
 * Represents an argument which will give you a {@link TeamColor}.
 * <p>
 * Example: red, white
 */
public class ArgumentTeamColor extends Argument<TeamColor> {

    public static final int UNDEFINED_COLOR = -2;

    public ArgumentTeamColor(String id) {
        super(id);
    }

    @Override
    public TeamColor parse(CommandSender sender, String input) throws ArgumentSyntaxException {
        TeamColor color = TeamColor.fromName(input);
        if (color != null) return color;

        throw new ArgumentSyntaxException("Undefined color", input, UNDEFINED_COLOR);
    }

    @Override
    public ArgumentParserType parser() {
        return ArgumentParserType.TEAM_COLOR;
    }

    @Override
    public String toString() {
        return String.format("Color<%s>", getId());
    }
}
