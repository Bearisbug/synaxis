package net.minestom.server.entity.metadata.animal;

import net.minestom.server.codec.Codec;
import net.minestom.server.component.DataComponent;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.MetadataDef;
import net.minestom.server.entity.MetadataHolder;
import net.minestom.server.network.NetworkBuffer;
import org.jetbrains.annotations.Nullable;

public class RabbitMeta extends AnimalMeta {
    public RabbitMeta(@Nullable Entity entity, MetadataHolder metadata) {
        super(entity, metadata);
    }

    /**
     * @deprecated use {@link net.minestom.server.component.DataComponents#RABBIT_VARIANT} instead.
     */
    @Deprecated
    public void setVariant(RabbitMeta.Variant variant) {
        int id = variant == Variant.KILLER_BUNNY ? 99 : variant.ordinal();
        metadata.set(MetadataDef.Rabbit.TYPE, id);
    }

    /**
     * @deprecated use {@link net.minestom.server.component.DataComponents#RABBIT_VARIANT} instead.
     */
    @Deprecated
    public RabbitMeta.Variant getVariant() {
        int id = metadata.get(MetadataDef.Rabbit.TYPE);
        if (id == 99) {
            return Variant.KILLER_BUNNY;
        }
        return Variant.VALUES[id];
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <T> @Nullable T get(DataComponent<T> component) {
        if (component == DataComponents.RABBIT_VARIANT)
            return (T) getVariant();
        return super.get(component);
    }

    @Override
    protected <T> void set(DataComponent<T> component, T value) {
        if (component == DataComponents.RABBIT_VARIANT)
            setVariant((Variant) value);
        else super.set(component, value);
    }

    public enum Variant {
        BROWN("brown"),
        WHITE("white"),
        BLACK("black"),
        BLACK_AND_WHITE("white_splotched"),
        GOLD("gold"),
        SALT_AND_PEPPER("salt"),
        KILLER_BUNNY("evil");

        // Three constant names differ from the vanilla registry, so the name cannot be used as the key
        private final String key;

        Variant(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        public static final NetworkBuffer.Type<Variant> NETWORK_TYPE = NetworkBuffer.Enum(Variant.class);
        public static final Codec<Variant> CODEC = Codec.STRING.transform(Variant::fromKey, Variant::key);

        private final static Variant[] VALUES = values();

        private static Variant fromKey(String key) {
            for (Variant variant : VALUES) {
                if (variant.key.equals(key)) return variant;
            }
            throw new IllegalArgumentException("Unknown rabbit variant: " + key);
        }
    }

}
