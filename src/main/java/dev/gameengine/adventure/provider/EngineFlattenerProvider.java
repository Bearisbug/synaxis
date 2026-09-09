package dev.gameengine.adventure.provider;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.flattener.ComponentFlattener;
import dev.gameengine.JvmFlags;
import dev.gameengine.adventure.EngineAdventure;

final class EngineFlattenerProvider {
    static final ComponentFlattener INSTANCE;
    static {
        final ComponentFlattener.Builder builder = ComponentFlattener.basic().toBuilder();

        // handle server-side translations if needed
        builder.complexMapper(TranslatableComponent.class, (component, consumer) -> {
            if (JvmFlags.AUTOMATIC_COMPONENT_TRANSLATION) {
                final Component translated = EngineAdventure.COMPONENT_TRANSLATOR.apply(component, EngineAdventure.getDefaultLocale());

                // In case the translated component is also a translatable component, we just leave the key to avoid infinite recursion
                if (translated instanceof TranslatableComponent translatable) {
                    consumer.accept(Component.text(translatable.key()));
                } else {
                    consumer.accept(translated);
                }
            }
        });

        INSTANCE = builder.build();
    }
}
