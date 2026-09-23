package io.github.bearisbug.synaxis.command;

import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.pointer.Pointers;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.flattener.ComponentFlattener;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import io.github.bearisbug.synaxis.JvmFlags;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.adventure.EngineAdventure;
import io.github.bearisbug.synaxis.tag.TagHandler;

import java.util.IllegalFormatException;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

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
     * The console has no client to translate for it, so a translatable answer is written as the
     * server side translation when {@code synaxis.automatic-component-translation} is on and has
     * one, else as its fallback with its arguments filled in, else as its key.
     */
    private static final PlainTextComponentSerializer TEXT = PlainTextComponentSerializer.builder()
            .flattener(ComponentFlattener.basic().toBuilder()
                    .complexMapper(TranslatableComponent.class, ConsoleSender::flattenTranslatable)
                    .build())
            .build();

    @Override
    public void sendMessage(String message) {
        LOGGER.info(message);
    }

    @Override
    public void sendMessage(Component message) {
        LOGGER.info(TEXT.serialize(message));
    }

    private static void flattenTranslatable(TranslatableComponent component, Consumer<Component> out) {
        if (JvmFlags.AUTOMATIC_COMPONENT_TRANSLATION) {
            final Component translated =
                    EngineAdventure.COMPONENT_TRANSLATOR.apply(component, EngineAdventure.getDefaultLocale());
            if (!(translated instanceof TranslatableComponent)) {
                // The translation ends with the original's children, which the flattener walks
                // itself after this; without them here they are written once
                final List<Component> children = translated.children();
                out.accept(translated.children(children.subList(0, children.size() - component.children().size())));
                return;
            }
        }
        final String fallback = component.fallback();
        if (fallback == null) {
            out.accept(Component.text(component.key()));
            return;
        }
        final Object[] arguments = component.arguments().stream()
                .map(argument -> TEXT.serialize(argument.asComponent())).toArray();
        String text;
        try {
            text = String.format(Locale.ROOT, fallback, arguments);
        } catch (IllegalFormatException _) {
            text = fallback;
        }
        out.accept(Component.text(text));
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
