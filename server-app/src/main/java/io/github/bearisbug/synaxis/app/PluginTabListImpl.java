package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.plugin.TabList;

import java.util.function.BiPredicate;

/**
 * Tab list capability of one plugin ({@code docs/DESIGN-PROD.md} API-PROD-020).
 *
 * @param pluginId the owning plugin
 * @param slot     the one visibility of the process
 */
record PluginTabListImpl(String pluginId, TabListSlot slot) implements TabList {

    @Override
    public void setVisibility(BiPredicate<Player, Player> visibility) {
        slot.set(pluginId, visibility);
    }
}
