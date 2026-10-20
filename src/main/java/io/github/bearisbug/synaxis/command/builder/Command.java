package io.github.bearisbug.synaxis.command.builder;

import io.github.bearisbug.synaxis.command.CommandSender;
import io.github.bearisbug.synaxis.command.builder.arguments.Argument;
import io.github.bearisbug.synaxis.command.builder.arguments.ArgumentType;
import io.github.bearisbug.synaxis.command.builder.condition.CommandCondition;
import io.github.bearisbug.synaxis.utils.validate.Check;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Represents a command which has suggestion/auto-completion.
 * <p>
 * The command works using a list of valid syntaxes.
 * For instance we could build the command
 * "/health set Notch 50" into multiple argument types "/health [set/add/remove] [username] [integer]"
 * <p>
 * All the default argument types can be found in {@link ArgumentType}
 * and the syntax be created/registered using {@link #addSyntax(CommandExecutor, Argument[])}.
 * <p>
 * If the command is executed with an incorrect syntax or without any argument, the default {@link CommandExecutor} will be called,
 * you can set it using {@link #setDefaultExecutor(CommandExecutor)}. When an argument with an error callback fails, the callback is
 * called in its place, as {@link Argument#setCallback(ArgumentCallback)} tells.
 * <p>
 * Before any syntax to be successfully executed the {@link CommandSender} needs to validated
 * the {@link CommandCondition} sets with {@link #setCondition(CommandCondition)} (ignored if null).
 * <p>
 * Some {@link Argument} could also require additional condition (eg: a number which need to be between 2 values),
 * in this case, if the whole syntax is correct but not the argument condition,
 * you can listen to its error code using {@link #setArgumentCallback(ArgumentCallback, Argument)} or {@link Argument#setCallback(ArgumentCallback)}.
 * <p>
 * Set a command up before it is registered. Once it is, a change to its structure (condition, executors, syntaxes,
 * subcommands) takes effect at no set time, when any command is next registered or unregistered: to change it,
 * unregister it and register it again. A permission that changes at runtime belongs inside the condition, which is
 * asked every time. A command is not thread-safe: do not change it from another thread once it is registered.
 */
public class Command {

    private final static Logger LOGGER = LoggerFactory.getLogger(Command.class);

    private final String name;
    private final String[] aliases;
    private final String[] names;

    private @Nullable CommandExecutor defaultExecutor;
    private @Nullable CommandCondition condition;

    private final List<Command> subcommands;
    private final List<CommandSyntax> syntaxes;

    /**
     * Creates a {@link Command} with a name and one or multiple aliases.
     * <p>
     * The name and every alias is non-empty lower-case text without whitespace, not starting with {@code /}; the
     * same goes for a subcommand. A command is looked up by its lower-cased name but parsed by its name as given, so
     * an upper-case letter would leave it found yet never run.
     *
     * @param name    the name of the command
     * @param aliases the command aliases
     * @throws IllegalArgumentException if the name or an alias breaks that rule
     * @see #Command(String)
     */
    public Command(String name, String... aliases) {
        checkName(name);
        for (String alias : aliases) checkName(alias);
        this.name = name;
        this.aliases = aliases;
        this.names = Stream.concat(Arrays.stream(aliases), Stream.of(name)).toArray(String[]::new);

        this.subcommands = new ArrayList<>();
        this.syntaxes = new ArrayList<>();
    }

    /**
     * Creates a {@link Command} with a name and no alias.
     *
     * @param name the name of the command
     * @throws IllegalArgumentException if the name breaks the rule of {@link #Command(String, String...)}
     * @see #Command(String, String...)
     */
    public Command(String name) {
        this(name, new String[0]);
    }

    private static void checkName(String name) {
        final boolean valid = !name.isEmpty() && !name.startsWith("/") && name.equals(name.toLowerCase(Locale.ROOT))
                && name.chars().noneMatch(Character::isWhitespace);
        Check.argCondition(!valid, "a command name is non-empty lower-case text without whitespace or a leading '/': '"
                + name + "'");
    }

    /**
     * Gets the {@link CommandCondition}.
     * <p>
     * It is called after the parsing and just before the execution no matter the syntax used and can be used to check permissions or
     * the {@link CommandSender} type.
     * <p>
     * Worth mentioning that the condition is also used to know if the command known from a player (at connection).
     *
     * @return the command condition, null if not any
     */
    @Nullable
    public CommandCondition getCondition() {
        return condition;
    }

    /**
     * Sets the {@link CommandCondition}.
     * <p>
     * Once the command is registered this takes effect at no set time: unregister the command and register it again
     * to change it, and do not change it from another thread. A permission that changes at runtime is checked inside
     * the condition instead.
     *
     * @param commandCondition the new command condition, null to do not call anything
     * @see #getCondition()
     */
    public void setCondition(@Nullable CommandCondition commandCondition) {
        this.condition = commandCondition;
    }

    /**
     * Sets an {@link ArgumentCallback}.
     * <p>
     * The argument callback is called when there's an error in the argument, instead of an executor;
     * {@link Argument#setCallback(ArgumentCallback)} tells when exactly.
     *
     * @param callback the callback for the argument
     * @param argument the argument which get the callback
     */
    public void setArgumentCallback(ArgumentCallback callback, Argument<?> argument) {
        argument.setCallback(callback);
    }

    /**
     * Adds a subcommand.
     * <p>
     * Once this command is registered this takes effect at no set time: unregister it and register it again to change
     * it, and do not change it from another thread.
     *
     * @param command the subcommand
     */
    public void addSubcommand(Command command) {
        this.subcommands.add(command);
    }

    public void addSubcommands(Command... commands) {
        for (Command command : commands) {
            addSubcommand(command);
        }
    }

    public List<Command> getSubcommands() {
        return Collections.unmodifiableList(subcommands);
    }

    /**
     * Adds a new syntax in the command.
     * <p>
     * A syntax is simply a list of arguments and an executor called when successfully parsed.
     * <p>
     * Syntaxes share their leading arguments: arguments of the same class and id at the same place are one node, the
     * first registered instance being the one parsed, so they have to be configured the same
     * ({@link Argument#sameConfiguration}). The arguments of a parsed command are keyed by id, so an id appears once
     * on a syntax, the names of the subcommands above it included. Two syntaxes ending at the same place with a
     * different executor or condition would leave one of them never run, the shorter syntaxes that optional arguments
     * add included. {@link io.github.bearisbug.synaxis.command.CommandManager#register(Command)} refuses a command
     * breaking any of this.
     * <p>
     * An argument taking the rest of the input ({@link Argument#useRemaining()}: a string array, a loop, a command)
     * must be the last argument of a syntax, since it leaves nothing for an argument after it.
     * <p>
     * The condition decides whether a sender sees the syntax among its completions and may run its executor; it does
     * not guard another syntax sharing the arguments. Once the command is registered, adding a syntax takes effect at
     * no set time: unregister the command and register it again to change it, and do not change it from another
     * thread.
     *
     * @param commandCondition the condition to use the syntax
     * @param executor         the executor to call when the syntax is successfully received
     * @param args             all the arguments of the syntax, the length needs to be higher than 0
     * @return the created {@link CommandSyntax syntaxes},
     * there can be multiple of them when optional arguments are used
     * @throws IllegalArgumentException if an argument taking the rest of the input is not the last one
     */
    @SuppressWarnings("unchecked")
    public Collection<CommandSyntax> addConditionalSyntax(@Nullable CommandCondition commandCondition,
                                                          CommandExecutor executor,
                                                          Argument<?>... args) {
        for (int i = 0; i < args.length - 1; i++) {
            Check.argCondition(args[i].useRemaining(), "Argument '" + args[i].getId()
                    + "' takes the rest of the input and must be the last of the syntax");
        }
        // Check optional argument(s)
        boolean hasOptional = false;
        {
            for (Argument<?> argument : args) {
                if (argument.isOptional()) {
                    hasOptional = true;
                }
                if (hasOptional && !argument.isOptional()) {
                    LOGGER.warn("Optional arguments are followed by a non-optional one, the default values will be ignored.");
                    hasOptional = false;
                    break;
                }
            }
        }

        if (!hasOptional) {
            final CommandSyntax syntax = new CommandSyntax(commandCondition, executor, args);
            this.syntaxes.add(syntax);
            return List.of(syntax);
        } else {
            List<CommandSyntax> optionalSyntaxes = new ArrayList<>();

            // the 'args' array starts by all the required arguments, followed by the optional ones
            List<Argument<?>> requiredArguments = new ArrayList<>();
            Map<String, Function<CommandSender, Object>> defaultValuesMap = new HashMap<>();
            boolean optionalBranch = false;
            int i = 0;
            for (Argument<?> argument : args) {
                final boolean isLast = ++i == args.length;
                if (argument.isOptional()) {
                    // Set default value
                    defaultValuesMap.put(argument.getId(), (Function<CommandSender, Object>) argument.getDefaultValue());

                    if (!optionalBranch && !requiredArguments.isEmpty()) {
                        // First optional argument, create a syntax with current cached arguments
                        final CommandSyntax syntax = new CommandSyntax(commandCondition, executor, defaultValuesMap,
                                requiredArguments.toArray(new Argument<?>[0]));
                        optionalSyntaxes.add(syntax);
                        optionalBranch = true;
                    } else {
                        // New optional argument, save syntax with current cached arguments and save default value
                        final CommandSyntax syntax = new CommandSyntax(commandCondition, executor, defaultValuesMap,
                                requiredArguments.toArray(new Argument<?>[0]));
                        optionalSyntaxes.add(syntax);
                    }
                }
                requiredArguments.add(argument);
                if (isLast) {
                    // Create the last syntax
                    final CommandSyntax syntax = new CommandSyntax(commandCondition, executor, defaultValuesMap,
                            requiredArguments.toArray(new Argument<?>[0]));
                    optionalSyntaxes.add(syntax);
                }
            }

            this.syntaxes.addAll(optionalSyntaxes);
            return optionalSyntaxes;
        }
    }

    /**
     * Adds a new syntax without condition.
     *
     * @see #addConditionalSyntax(CommandCondition, CommandExecutor, Argument[])
     */
    public Collection<CommandSyntax> addSyntax(CommandExecutor executor, Argument<?>... args) {
        return addConditionalSyntax(null, executor, args);
    }

    /**
     * Creates a syntax from a formatted string.
     * <p>
     * The format is a list of words separated by spaces. A word without angle brackets is a literal; {@code Type<id>}
     * is an argument, the type being one of the names {@link ArgumentType#generate(String)} knows, in any case
     * ({@code integer}, {@code word}, {@code stringarray}, {@code player}, ...). For example
     * {@code "set Integer<amount> Player<target>"} is the literal {@code set}, an integer {@code amount} and a single
     * player {@code target}. The format is not final.
     *
     * @param executor the syntax executor
     * @param format   the syntax format
     * @return the newly created {@link CommandSyntax syntaxes}.
     * @throws IllegalArgumentException if a type is unknown, a {@code <} is not closed, or an argument taking the rest
     *                                  of the input is not the last one
     */
    @ApiStatus.Experimental
    public Collection<CommandSyntax> addSyntax(CommandExecutor executor, String format) {
        return addSyntax(executor, ArgumentType.generate(format));
    }

    /**
     * Gets the main command's name.
     *
     * @return the main command's name
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the command's aliases.
     *
     * @return the command aliases, never null, empty when the command has no alias
     */
    public String[] getAliases() {
        return aliases;
    }

    /**
     * Gets all the possible names for this command.
     * <p>
     * Include {@link #getName()} and {@link #getAliases()}.
     *
     * @return this command names
     */
    public String[] getNames() {
        return names;
    }

    /**
     * Gets the default {@link CommandExecutor} which is called when there is no argument
     * or if no corresponding syntax has been found.
     *
     * @return the default executor, null if not any
     * @see #setDefaultExecutor(CommandExecutor)
     */
    @Nullable
    public CommandExecutor getDefaultExecutor() {
        return defaultExecutor;
    }

    /**
     * Sets the default {@link CommandExecutor}.
     * <p>
     * Once the command is registered this takes effect at no set time: unregister the command and register it again
     * to change it, and do not change it from another thread.
     *
     * @param executor the new default executor, null to remove it
     * @see #getDefaultExecutor()
     */
    public void setDefaultExecutor(@Nullable CommandExecutor executor) {
        this.defaultExecutor = executor;
    }

    /**
     * Gets all the syntaxes of this command.
     *
     * @return a collection containing all this command syntaxes
     * @see #addSyntax(CommandExecutor, Argument[])
     */
    public Collection<CommandSyntax> getSyntaxes() {
        return syntaxes;
    }

    /**
     * Called when a {@link CommandSender} executes this command before any syntax callback.
     * <p>
     * WARNING: the {@link CommandCondition} is not executed, and all the {@link CommandSyntax} are not checked,
     * this is called every time a {@link CommandSender} send a command which start by {@link #getName()} or {@link #getAliases()}.
     * <p>
     * Can be used if you wish to still suggest the player syntaxes but want to parse things mostly by yourself.
     *
     * @param sender  the {@link CommandSender}
     * @param context the UNCHECKED context of the command, some can be null even when unexpected
     * @param command the raw UNCHECKED received command
     */
    public void globalListener(CommandSender sender, @UnknownNullability CommandContext context, String command) {
    }

    public static boolean isValidName(Command command, String name) {
        for (String commandName : command.getNames()) {
            if (commandName.equals(name)) {
                return true;
            }
        }
        return false;
    }

}
