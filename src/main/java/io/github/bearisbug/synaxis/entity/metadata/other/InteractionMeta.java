package io.github.bearisbug.synaxis.entity.metadata.other;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.MetadataDef;
import io.github.bearisbug.synaxis.entity.MetadataHolder;
import io.github.bearisbug.synaxis.entity.metadata.EntityMeta;
import org.jetbrains.annotations.Nullable;

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
