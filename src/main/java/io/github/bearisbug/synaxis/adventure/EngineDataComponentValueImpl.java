package io.github.bearisbug.synaxis.adventure;

import net.kyori.adventure.text.event.DataComponentValue;
import org.jetbrains.annotations.Nullable;

record EngineDataComponentValueImpl(@Nullable Object value) implements EngineDataComponentValue {
    record Removed() implements EngineDataComponentValue, DataComponentValue.Removed {
        static final EngineDataComponentValue INSTANCE = new EngineDataComponentValueImpl.Removed();

        @Override
        public @Nullable Object value() {
            return null;
        }
    }

}
