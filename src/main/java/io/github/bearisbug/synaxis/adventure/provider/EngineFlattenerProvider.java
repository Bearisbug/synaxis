package io.github.bearisbug.synaxis.adventure.provider;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.flattener.ComponentFlattener;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import io.github.bearisbug.synaxis.JvmFlags;
import io.github.bearisbug.synaxis.adventure.EngineAdventure;
import org.jetbrains.annotations.Nullable;

import java.util.IllegalFormatException;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

final class EngineFlattenerProvider {
    static final ComponentFlattener INSTANCE;
    static {
        final ComponentFlattener.Builder builder = ComponentFlattener.basic().toBuilder();

        // Text without a client to translate it, the console's or a log line's: a translatable component is written as
        // the server side translation when synaxis.automatic-component-translation is on and has one, else as its
        // fallback with its arguments filled in, else as its key
        builder.complexMapper(TranslatableComponent.class, EngineFlattenerProvider::flattenTranslatable);

        INSTANCE = builder.build();
    }

    /** What writes the arguments of a fallback, built once {@link #INSTANCE} is. */
    private static final class Arguments {
        static final PlainTextComponentSerializer TEXT = PlainTextComponentSerializer.builder()
                .flattener(INSTANCE)
                .build();
    }

    private static void flattenTranslatable(TranslatableComponent component, Consumer<Component> out) {
        if (JvmFlags.AUTOMATIC_COMPONENT_TRANSLATION) {
            final Component translated = translation(component);
            if (translated != null) {
                out.accept(translated);
                return;
            }
        }
        final String fallback = component.fallback();
        if (fallback == null) {
            out.accept(Component.text(component.key()));
            return;
        }
        final Object[] arguments = component.arguments().stream()
                .map(argument -> Arguments.TEXT.serialize(argument.asComponent())).toArray();
        String text;
        try {
            text = String.format(Locale.ROOT, fallback, arguments);
        } catch (IllegalFormatException _) {
            text = fallback;
        }
        out.accept(Component.text(text));
    }

    /**
     * The server side translation of the component, or null when it has none. The component is
     * translated without its children, which the flattener walks itself after this: a
     * {@code MessageFormat} translation would end with them, a translator that returns a component
     * may leave them out.
     */
    static @Nullable Component translation(TranslatableComponent component) {
        final Component translated = EngineAdventure.COMPONENT_TRANSLATOR.apply(component.children(List.of()),
                EngineAdventure.getDefaultLocale());
        return translated instanceof TranslatableComponent ? null : translated;
    }
}
