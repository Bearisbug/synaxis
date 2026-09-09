package dev.gameengine.adventure.provider;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.function.Consumer;

@SuppressWarnings("UnstableApiUsage") // we are permitted to provide this
public final class EnginePlainTextComponentSerializerProvider implements PlainTextComponentSerializer.Provider {
    @Override
    public PlainTextComponentSerializer plainTextSimple() {
        return PlainTextComponentSerializer.builder()
                .flattener(EngineFlattenerProvider.INSTANCE)
                .build();
    }

    @Override
    public Consumer<PlainTextComponentSerializer.Builder> plainText() {
        // we will provide our flattener to allow for custom translations/etc
        return builder -> builder.flattener(EngineFlattenerProvider.INSTANCE);
    }
}
