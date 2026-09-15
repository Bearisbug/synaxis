package io.github.bearisbug.synaxis.command;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.identity.Identity;
import io.github.bearisbug.synaxis.command.builder.CommandContext;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.tag.TagHandler;

/**
 * Sender used in {@link CommandManager#executeServerCommand(String)}.
 * <p>
 * Although this class implemented {@link CommandSender} and thus {@link Audience}, no
 * data can be sent to this sender because it's purpose is to process the data of
 * {@link CommandContext#getReturnData()}.
 */
public class ServerSender implements CommandSender {
    private final ServerProcess process;

    public ServerSender(ServerProcess process) {
        this.process = process;
    }

    @Override
    public ServerProcess process() {
        return process;
    }


    private final TagHandler tagHandler = TagHandler.newHandler();


    @Override
    public TagHandler tagHandler() {
        return tagHandler;
    }

    @Override
    public Identity identity() {
        return Identity.nil();
    }
}
