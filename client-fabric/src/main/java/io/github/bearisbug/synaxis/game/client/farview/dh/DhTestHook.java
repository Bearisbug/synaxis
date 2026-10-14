package io.github.bearisbug.synaxis.game.client.farview.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue;
import com.seibel.distanthorizons.api.interfaces.config.client.IDhApiGraphicsConfig;
import com.seibel.distanthorizons.api.interfaces.config.client.IDhApiMultiThreadingConfig;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.world.AbstractDhWorld;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import io.github.bearisbug.synaxis.game.client.farview.FarViewColumns;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The static hook the client gametests of M3-G ({@code FarViewClientGameTest}) reach the far view's
 * DH side through: switches that take one step of the bake away for a counter-proof, holds that stop
 * the bake at a chosen point, queries on DH's library, DH's config as the far view leaves it, and
 * {@link #unloadLevel}, which stands in for DH's own cleanup of an earlier connection's level. With
 * nothing set, which is how the mod ships, every switch is the designed behaviour and nothing waits.
 * <p>
 * The library queries ({@link #count}, {@link #topPoint}) run on the calling thread, on DH's own
 * connection to the library of the level loaded last; a gametest calls them from a platform thread of
 * its own, never the render thread. The config reads and overrides ({@link #graphics}, {@link #threads},
 * {@link #overdrawPreventionRadius}, {@link #renderingEnabled}) are the opposite: client thread only.
 */
public final class DhTestHook {

    /** False: data sources go to DH without {@code applyToParent} ({@code v1-noparent}). */
    public static volatile boolean applyToParent = true;
    /** False: DH is left writable on world load ({@code v1-writable}). */
    public static volatile boolean readOnly = true;
    /** False: the coarse layer is written at the coarse level, one source a block ({@code v1-coarse-only}). */
    public static volatile boolean expandCoarse = true;
    /** False: the marker is written once every future completed, without the row checks. */
    public static volatile boolean writeCheck = true;
    /** True: the bake carries on through a cancel. */
    public static volatile boolean ignoreCancel;
    /** Set: the bake waits on it before it reads the first pack. */
    public static volatile @Nullable CountDownLatch holdStart;
    /** Set: the bake waits on it, every earlier source completed, before handing over the last one. */
    public static volatile @Nullable CountDownLatch holdBeforeLast;
    /** Counted down when the bake reaches {@link #holdBeforeLast}. */
    public static volatile @Nullable CountDownLatch reachedLast;
    /** The bake thread last started, while it runs; null once it ended, so it keeps no level alive. */
    public static volatile @Nullable Thread bakeThread;

    private DhTestHook() {
    }

    /** Puts every switch back to the designed behaviour. */
    public static void reset() {
        applyToParent = true;
        readOnly = true;
        expandCoarse = true;
        writeCheck = true;
        ignoreCancel = false;
        holdStart = null;
        holdBeforeLast = null;
        reachedLast = null;
    }

    /**
     * Counts rows of DH's {@code FullData} table of the level loaded last.
     *
     * @param where the condition, e.g. {@code DetailLevel = 2}
     * @return the count, null without a level or when DH's connection gives no answer
     */
    public static @Nullable Long count(String where) {
        final IDhApiLevelWrapper level = DhFarView.level();
        if (level == null) return null;
        return DhLibraryBaker.count(((ILevelWrapper) level).getDhLevel().getFullDataProvider().repo, where);
    }

    /**
     * The topmost non-air data point DH holds for a block column, read back through
     * {@code FullDataSourceProviderV2#get} from the data source the bake wrote it into.
     *
     * @param x         the block column's x
     * @param z         the block column's z
     * @param baseLevel the level the column was written at
     * @return {@code state}, {@code block}, {@code bottom}, {@code top} (exclusive), {@code sky}, {@code light},
     *         {@code biome} (DH's serial string) and {@code points} of that point; empty when the column holds only air; null without a level
     */
    public static @Nullable Map<String, Object> topPoint(int x, int z, int baseLevel) {
        final IDhApiLevelWrapper level = DhFarView.level();
        if (level == null) return null;
        // A section at detail level d is 2^d blocks a side, so d also shifts a block coordinate to the section's
        final int sectionDetail = FarViewColumns.SECTION_BLOCK_DETAIL + baseLevel;
        final long position = DhSectionPos.encode((byte) sectionDetail, x >> sectionDetail, z >> sectionDetail);
        final FullDataSourceV2 source = ((ILevelWrapper) level).getDhLevel().getFullDataProvider().get(position);
        try {
            final List<DhApiTerrainDataPoint> column = source.getApiDataPointColumn((x >> baseLevel) & (FarViewColumns.WIDTH - 1),
                    (z >> baseLevel) & (FarViewColumns.WIDTH - 1));
            DhApiTerrainDataPoint top = null;
            for (DhApiTerrainDataPoint point : column) {
                if (point.blockStateWrapper == null || point.blockStateWrapper.isAir()) continue;
                if (top == null || point.topYBlockPos > top.topYBlockPos) top = point;
            }
            final Map<String, Object> found = new LinkedHashMap<>();
            if (top == null) return found;
            final BlockState state = (BlockState) top.blockStateWrapper.getWrappedMcObject();
            found.put("state", Block.BLOCK_STATE_REGISTRY.getId(state));
            found.put("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            found.put("bottom", top.bottomYBlockPos);
            found.put("top", top.topYBlockPos);
            found.put("sky", top.skyLightLevel);
            found.put("light", top.blockLightLevel);
            found.put("biome", top.biomeWrapper == null ? null : top.biomeWrapper.getSerialString());
            found.put("points", column.size());
            return found;
        } finally {
            source.close();
        }
    }

    /**
     * Whether DH's world is read only now.
     *
     * @return the flag, null without a loaded world
     */
    public static @Nullable Boolean readOnlyNow() {
        try {
            return DhApi.Delayed.worldProxy.getReadOnly();
        } catch (IllegalStateException _) {
            return null;
        }
    }

    /**
     * The save folder of the level loaded last.
     *
     * @return its path, null without a level
     */
    public static @Nullable String saveFolder() {
        final IDhApiLevelWrapper level = DhFarView.level();
        return level == null ? null : level.getDhSaveFolder().getPath();
    }

    /**
     * The level wrapper DH loaded last on a Synaxis connection.
     *
     * @return the wrapper, null without a level
     */
    public static @Nullable Object levelWrapper() {
        return DhFarView.level();
    }

    /**
     * What DH's cleanup does to the level of a connection that ended, 30 s after that level's last use
     * ({@code ClientLevelWrapper#tickCleanup}): {@code AbstractDhWorld#unloadLevel} with that level's wrapper.
     * Every map shard's level has the same DH id, so DH finds the level of this connection under it,
     * closes nothing, since the id still has this connection's wrapper, and fires
     * {@code DhApiLevelUnloadEvent} with the earlier wrapper.
     *
     * @param wrapper a wrapper from {@link #levelWrapper} of a connection that ended
     * @return what {@code unloadLevel} returned, null without a DH world
     */
    public static @Nullable Boolean unloadLevel(Object wrapper) {
        final AbstractDhWorld world = SharedApi.getAbstractDhWorld();
        return world == null ? null : world.unloadLevel((ILevelWrapper) wrapper);
    }

    /**
     * The graphics settings the far view sets ({@code chunkRenderDistance}, {@code maxHorizontalResolution})
     * and the two it must leave alone ({@code renderingEnabled}, see {@link DhSession};
     * {@code overdrawPreventionRadius}), each as its API value, its value in effect and whether the API may
     * set it. Call on the client thread.
     *
     * @return by name
     */
    public static Map<String, String> graphics() {
        final IDhApiGraphicsConfig graphics = DhApi.Delayed.configs.graphics();
        final Map<String, String> found = new LinkedHashMap<>();
        found.put("chunkRenderDistance", describe(graphics.chunkRenderDistance()));
        found.put("maxHorizontalResolution", describe(graphics.maxHorizontalResolution()));
        found.put("renderingEnabled", describe(graphics.renderingEnabled()));
        found.put("overdrawPreventionRadius", describe(graphics.overdrawPreventionRadius()));
        return found;
    }

    private static String describe(IDhApiConfigValue<?> value) {
        return "api=" + value.getApiValue() + " value=" + value.getValue() + " overridable=" + value.getCanBeOverrodeByApi();
    }

    /**
     * Overrides DH's worker threads ({@code multiThreading.threadCount} and {@code threadRuntimeRatio}) for a machine
     * whose few cores the software renderer takes most of: the 2 core runner of {@code farview-gametest.yml}, where DH's
     * default share, one thread on two cores, left a bake writing for 18 minutes. Call on the client thread.
     *
     * @param count        the threads
     * @param runtimeRatio the share of the time they run, 1 for all of it
     * @return both settings as DH has them afterwards
     */
    public static String threads(int count, double runtimeRatio) {
        final IDhApiMultiThreadingConfig threading = DhApi.Delayed.configs.multiThreading();
        threading.threadCount().setValue(count, DhSession.API_USER);
        threading.threadRuntimeRatio().setValue(runtimeRatio, DhSession.API_USER);
        return "threadCount " + describe(threading.threadCount()) + ", threadRuntimeRatio " + describe(threading.threadRuntimeRatio());
    }

    /**
     * Overrides or clears {@code overdrawPreventionRadius} (V6a). Call on the client thread.
     *
     * @param radius the value, null to clear the override
     */
    public static void overdrawPreventionRadius(@Nullable Float radius) {
        final IDhApiConfigValue<Float> value = DhApi.Delayed.configs.graphics().overdrawPreventionRadius();
        if (radius == null) value.clearValue();
        else value.setValue(radius, DhSession.API_USER);
    }

    /**
     * Overrides or clears {@code renderingEnabled}, for the gametest that shows why the far view never does
     * (DhSession). Call on the client thread.
     *
     * @param enabled the value, null to clear the override
     */
    public static void renderingEnabled(@Nullable Boolean enabled) {
        final IDhApiConfigValue<Boolean> value = DhApi.Delayed.configs.graphics().renderingEnabled();
        if (enabled == null) value.clearValue();
        else value.setValue(enabled, DhSession.API_USER);
    }
}
