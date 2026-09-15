package io.github.bearisbug.synaxis.item.component;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;
import io.github.bearisbug.synaxis.component.DataComponent;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;

import java.util.HashSet;
import java.util.Set;

public record TooltipDisplay(boolean hideTooltip, Set<DataComponent<?>> hiddenComponents) {
    public static final TooltipDisplay EMPTY = new TooltipDisplay(false, Set.of());

    public static final NetworkBuffer.Type<TooltipDisplay> NETWORK_TYPE = NetworkBufferTemplate.template(
            NetworkBuffer.BOOLEAN, TooltipDisplay::hideTooltip,
            DataComponent.NETWORK_TYPE.set(Short.MAX_VALUE), TooltipDisplay::hiddenComponents,
            TooltipDisplay::new);
    public static final Codec<TooltipDisplay> CODEC = StructCodec.struct(
            "hide_tooltip", Codec.BOOLEAN.optional(false), TooltipDisplay::hideTooltip,
            "hidden_components", DataComponent.CODEC.set(Short.MAX_VALUE).optional(Set.of()), TooltipDisplay::hiddenComponents,
            TooltipDisplay::new);

    public TooltipDisplay {
        hiddenComponents = Set.copyOf(hiddenComponents);
    }

    public TooltipDisplay withHideTooltip(boolean hide) {
        return new TooltipDisplay(hide, hiddenComponents);
    }

    public TooltipDisplay with(DataComponent<?> component) {
        if (!hiddenComponents.contains(component))
            return new TooltipDisplay(hideTooltip, hiddenComponents);

        var newHiddenComponents = new HashSet<>(hiddenComponents);
        newHiddenComponents.remove(component);
        return new TooltipDisplay(hideTooltip, newHiddenComponents);
    }

    public TooltipDisplay without(DataComponent<?> component) {
        if (hiddenComponents.contains(component))
            return new TooltipDisplay(hideTooltip, hiddenComponents);

        var newHiddenComponents = new HashSet<>(hiddenComponents);
        newHiddenComponents.add(component);
        return new TooltipDisplay(hideTooltip, newHiddenComponents);
    }
}
