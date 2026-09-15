package io.github.bearisbug.synaxis.command.builder.arguments;

import io.github.bearisbug.synaxis.command.ArgumentParserType;
import io.github.bearisbug.synaxis.command.CommandSender;
import io.github.bearisbug.synaxis.command.builder.CommandContext;
import io.github.bearisbug.synaxis.command.builder.exception.ArgumentSyntaxException;
import io.github.bearisbug.synaxis.command.builder.parser.CommandParser;
import io.github.bearisbug.synaxis.command.builder.parser.ValidSyntaxHolder;
import io.github.bearisbug.synaxis.utils.StringUtils;

import java.util.ArrayList;
import java.util.List;

public class ArgumentGroup extends Argument<CommandContext> {

    public static final int INVALID_ARGUMENTS_ERROR = 1;

    private final Argument<?>[] group;

    public ArgumentGroup(String id, Argument<?>... group) {
        super(id, true, false);
        this.group = group;
    }

    @Override
    public CommandContext parse(CommandSender sender, String input) throws ArgumentSyntaxException {
        List<ValidSyntaxHolder> validSyntaxes = new ArrayList<>();
        CommandParser.parse(sender, null, group, input.split(StringUtils.SPACE), input, validSyntaxes, null);

        CommandContext context = new CommandContext(input);
        CommandParser.findMostCorrectSyntax(validSyntaxes, context);
        if (validSyntaxes.isEmpty()) {
            throw new ArgumentSyntaxException("Invalid arguments", input, INVALID_ARGUMENTS_ERROR);
        }

        return context;
    }

    @Override
    public ArgumentParserType parser() {
        return null;
    }

    public List<Argument<?>> group() {
        return List.of(group);
    }
}
