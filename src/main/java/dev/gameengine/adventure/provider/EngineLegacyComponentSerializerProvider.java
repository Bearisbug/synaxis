package dev.gameengine.adventure.provider;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.function.Consumer;

@SuppressWarnings("UnstableApiUsage") // we are permitted to provide this
public final class EngineLegacyComponentSerializerProvider implements LegacyComponentSerializer.Provider {
    @Override
    public LegacyComponentSerializer legacyAmpersand() {
        return LegacyComponentSerializer.builder()
                .character(LegacyComponentSerializer.AMPERSAND_CHAR)
                .flattener(EngineFlattenerProvider.INSTANCE)
                .build();
    }

    @Override
    public LegacyComponentSerializer legacySection() {
        return LegacyComponentSerializer.builder()
                .character(LegacyComponentSerializer.SECTION_CHAR)
                .flattener(EngineFlattenerProvider.INSTANCE)
                .build();
    }

    @Override
    public Consumer<LegacyComponentSerializer.Builder> legacy() {
        // we will provide our flattener to allow for custom translations/etc
        return builder -> builder.flattener(EngineFlattenerProvider.INSTANCE);
    }
}
