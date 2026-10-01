package io.github.bearisbug.synaxis.game.client.gametest.farview;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps Distant Horizons' self updater quiet in the gametests. Once a newer DH than the pinned 3.3.2 is
 * out for 26.2, DH opens its update screen on the title screen, and every client gametest has to end
 * there. The switch is DH's own setting ({@code Config.Client.Advanced.AutoUpdater.enableAutoUpdater}),
 * set once the client has started, after DH loaded its config and before the title screen opens. Only
 * the gametest mod has this class; what the shipped modpack does about the updater is a question for
 * {@code docs/DESIGN-M3.md} §28.2.
 */
public final class DhUpdaterOff implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("synaxis-gametest");

    @Override
    public void onInitializeClient() {
        if (!FabricLoader.getInstance().isModLoaded("distanthorizons")) return;
        ClientLifecycleEvents.CLIENT_STARTED.register(_ -> {
            try {
                final Object entry = Class.forName("com.seibel.distanthorizons.core.config.Config$Client$Advanced$AutoUpdater")
                        .getField("enableAutoUpdater").get(null);
                entry.getClass().getMethod("set", Object.class).invoke(entry, Boolean.FALSE);
                LOGGER.info("[synaxis/farview] Distant Horizons' auto updater turned off for the gametests");
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.warn("[synaxis/farview] could not turn off Distant Horizons' auto updater: {}", e.toString());
            }
        });
    }
}
