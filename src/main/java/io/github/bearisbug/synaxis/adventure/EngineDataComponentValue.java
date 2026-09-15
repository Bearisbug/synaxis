package io.github.bearisbug.synaxis.adventure;

import net.kyori.adventure.text.event.DataComponentValue;
import org.jetbrains.annotations.Nullable;

public sealed interface EngineDataComponentValue extends DataComponentValue permits EngineDataComponentValueImpl, EngineDataComponentValueImpl.Removed {

    static EngineDataComponentValue removed() {
        return EngineDataComponentValueImpl.Removed.INSTANCE;
    }

    static EngineDataComponentValue dataComponentValue(final @Nullable Object data) {
        return new EngineDataComponentValueImpl(data);
    }

    @Nullable Object value();
}
