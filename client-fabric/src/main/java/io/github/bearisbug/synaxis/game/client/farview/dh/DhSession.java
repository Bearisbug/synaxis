package io.github.bearisbug.synaxis.game.client.farview.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiMaxHorizontalResolution;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue;
import com.seibel.distanthorizons.api.interfaces.config.client.IDhApiGraphicsConfig;
import io.github.bearisbug.synaxis.game.client.farview.FarViewClient;
import io.github.bearisbug.synaxis.game.client.farview.FarViewConnection;
import io.github.bearisbug.synaxis.game.client.farview.FarViewTestHook;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What a Synaxis connection sets on Distant Horizons for the time its world is loaded
 * ({@code API-M3-015} 只读与渲染参数): read only on every world load, synchronously in the event, before
 * the level takes the near chunks DH queued; then, on the client thread, the far view's radius and
 * finest resolution. Both are API overrides, cleared when the world unloads.
 * <p>
 * A connection without a far view gets no override: its library is the empty {@code dh/off}
 * ({@code DhSaveFolder}), which read only keeps empty, so DH draws nothing beyond the vanilla view.
 * {@code renderingEnabled} is never overridden: DH 3.3.2 answers that override by writing
 * {@code rendererMode = "DISABLED"} and {@code enableRendering = false} into the player's
 * {@code DistantHorizons.toml} ({@code QuickRenderToggleConfigEventHandler}), which clearing the
 * override does not undo, so DH would stay off on every later server.
 */
final class DhSession {

    private static final Logger LOGGER = LoggerFactory.getLogger("synaxis");

    /** The name DH shows players for the settings this mod controls. */
    static final String API_USER = "Synaxis";

    private static final List<IDhApiConfigValue<?>> OVERRIDDEN = new CopyOnWriteArrayList<>();

    private DhSession() {
    }

    static void worldLoaded() {
        if (!FarViewClient.synaxisConnection()) return;
        if (DhTestHook.readOnly) {
            DhApi.Delayed.worldProxy.setReadOnly(true);
        } else {
            LOGGER.warn("{}the test hook leaves Distant Horizons writable", FarViewClient.PREFIX);
        }
        final FarViewConnection connection = FarViewClient.current();
        Minecraft.getInstance().execute(() -> apply(connection));
    }

    private static void apply(@Nullable FarViewConnection connection) {
        if (connection == null || !connection.available()) return;
        final IDhApiGraphicsConfig graphics = DhApi.Delayed.configs.graphics();
        if (FarViewTestHook.radius) {
            set("chunkRenderDistance", graphics.chunkRenderDistance(), connection.settings().chunkRenderDistance());
        }
        set("maxHorizontalResolution", graphics.maxHorizontalResolution(), connection.announcement().baseLevel() == 1
                ? EDhApiMaxHorizontalResolution.TWO_BLOCKS : EDhApiMaxHorizontalResolution.FOUR_BLOCKS);
    }

    private static <T> void set(String name, IDhApiConfigValue<T> value, T setting) {
        if (!value.getCanBeOverrodeByApi()) {
            LOGGER.warn("{}Distant Horizons does not let the API set {}; it stays {}", FarViewClient.PREFIX, name,
                    value.getValue());
            return;
        }
        value.setValue(setting, API_USER);
        OVERRIDDEN.add(value);
    }

    static void worldUnloaded() {
        Minecraft.getInstance().execute(() -> {
            for (IDhApiConfigValue<?> value : OVERRIDDEN) value.clearValue();
            OVERRIDDEN.clear();
        });
    }
}
