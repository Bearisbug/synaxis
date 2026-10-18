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

    /**
     * Hides or shows the tooltip lines of a component.
     *
     * @param component the component
     * @param hidden    true to hide its lines, false to show them
     * @return the display, this one when nothing changes
     */
    public TooltipDisplay withHidden(DataComponent<?> component, boolean hidden) {
        if (hiddenComponents.contains(component) == hidden) return this;

        var newHiddenComponents = new HashSet<>(hiddenComponents);
        if (hidden) newHiddenComponents.add(component);
        else newHiddenComponents.remove(component);
        return new TooltipDisplay(hideTooltip, newHiddenComponents);
    }

    /**
     * Shows the tooltip lines of a component.
     *
     * @param component the component
     * @return the display, this one when nothing changes
     * @deprecated the name reads as adding the component to the hidden ones, it removes it; use
     * {@link #withHidden(DataComponent, boolean) withHidden(component, false)}
     */
    @Deprecated(forRemoval = true)
    public TooltipDisplay with(DataComponent<?> component) {
        return withHidden(component, false);
    }

    /**
     * Hides the tooltip lines of a component.
     *
     * @param component the component
     * @return the display, this one when nothing changes
     * @deprecated the name reads as removing the component from the hidden ones, it adds it; use
     * {@link #withHidden(DataComponent, boolean) withHidden(component, true)}
     */
    @Deprecated(forRemoval = true)
    public TooltipDisplay without(DataComponent<?> component) {
        return withHidden(component, true);
    }
}
