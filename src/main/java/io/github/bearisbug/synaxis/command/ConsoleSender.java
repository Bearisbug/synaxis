package io.github.bearisbug.synaxis.command;

import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.pointer.Pointers;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.tag.TagHandler;

/**
 * Represents the console when sending a command to the server.
 */
public class ConsoleSender implements CommandSender {
    private final ServerProcess process;

    public ConsoleSender(ServerProcess process) {
        this.process = process;
    }

    @Override
    public ServerProcess process() {
        return process;
    }

    private static final ComponentLogger LOGGER = ComponentLogger.logger(ConsoleSender.class);

    private final TagHandler tagHandler = TagHandler.newHandler();

    private final Identity identity = Identity.nil();
    private final Pointers pointers = Pointers.builder()
            .withStatic(Identity.UUID, this.identity.uuid())
            .build();

    /**
     * The console has no client to translate for it: the engine's flattener writes a translatable
     * answer as the server side translation when {@code synaxis.automatic-component-translation} is
     * on and has one, else as its fallback with its arguments filled in, else as its key.
     */
    private static final PlainTextComponentSerializer TEXT = PlainTextComponentSerializer.plainText();

    @Override
    public void sendMessage(String message) {
        LOGGER.info(message);
    }

    @Override
    public void sendMessage(Component message) {
        LOGGER.info(TEXT.serialize(message));
    }

    @Override
    public TagHandler tagHandler() {
        return tagHandler;
    }

    @Override
    public Identity identity() {
        return this.identity;
    }

    @Override
    public Pointers pointers() {
        return this.pointers;
    }
}
