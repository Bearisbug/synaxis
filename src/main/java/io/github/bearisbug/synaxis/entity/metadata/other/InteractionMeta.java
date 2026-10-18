package io.github.bearisbug.synaxis.entity.metadata.other;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.MetadataDef;
import io.github.bearisbug.synaxis.entity.MetadataHolder;
import io.github.bearisbug.synaxis.entity.metadata.EntityMeta;
import org.jetbrains.annotations.Nullable;

/**
 * The metadata of an {@code interaction} entity.
 * <p>
 * Its width and height make the area a client hits it in, and the one the server measures the
 * interaction range to ({@code synaxis.enforce-entity-interaction-range}). They do not change
 * {@link Entity#getBoundingBox()}, which collisions and block placement use.
 */
public class InteractionMeta extends EntityMeta {
    public InteractionMeta(@Nullable Entity entity, MetadataHolder metadata) {
        super(entity, metadata);
    }

    public float getWidth() {
        return metadata.get(MetadataDef.Interaction.WIDTH);
    }

    public void setWidth(float value) {
        metadata.set(MetadataDef.Interaction.WIDTH, value);
    }

    public float getHeight() {
        return metadata.get(MetadataDef.Interaction.HEIGHT);
    }

    public void setHeight(float value) {
        metadata.set(MetadataDef.Interaction.HEIGHT, value);
    }

    public boolean getResponse() {
        return metadata.get(MetadataDef.Interaction.RESPONSIVE);
    }

    public void setResponse(boolean response) {
        metadata.set(MetadataDef.Interaction.RESPONSIVE, response);
    }
}
