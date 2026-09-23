package io.github.bearisbug.synaxis.game.client.gametest.cull;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.minecraft.world.entity.Entity;

/**
 * The entities the last frame actually extracted for rendering, the list
 * {@code LevelExtractor#extractVisibleEntities} builds after its distance, frustum and section
 * checks. {@code LevelExtractorMixin} fills it; spike S3 reads it on the client thread, which is the
 * thread the extraction runs on.
 */
public final class RenderedEntities {

    private static final Set<Entity> LAST_FRAME = Collections.newSetFromMap(new IdentityHashMap<>());

    private RenderedEntities() {}

    public static void beginFrame() {
        LAST_FRAME.clear();
    }

    public static void add(Entity entity) {
        LAST_FRAME.add(entity);
    }

    public static boolean contains(Entity entity) {
        return LAST_FRAME.contains(entity);
    }
}
