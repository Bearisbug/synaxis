package io.github.bearisbug.synaxis.network.player;

import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.utils.Either;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.STRING;

public record GameProfile(
        UUID uuid, String name,
        List<Property> properties
) {
    public static final int MAX_PROPERTIES = 16;

    public GameProfile {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(properties, "properties");
        if (name.length() > 16)
            throw new IllegalArgumentException("Name length cannot be greater than 16 characters");
        properties = List.copyOf(properties);
    }

    public GameProfile(UUID uuid, String name) {
        this(uuid, name, List.of());
    }

    /**
     * The uuid a username gets in offline mode: the name based uuid a vanilla client computes for
     * itself, so that the same name is the same uuid on both sides and across connections.
     *
     * @param username the username as sent in the login start
     * @return the uuid owned by that username while no authority is asked
     */
    public static UUID offlineUuid(String username) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
    }

    public static final NetworkBuffer.Type<GameProfile> SERIALIZER = NetworkBufferTemplate.template(
            NetworkBuffer.UUID, GameProfile::uuid,
            STRING, GameProfile::name,
            Property.SERIALIZER.list(MAX_PROPERTIES), GameProfile::properties,
            GameProfile::new);
    public static final StructCodec<GameProfile> CODEC = StructCodec.struct(
            "id", Codec.UUID, GameProfile::uuid,
            "name", Codec.STRING, GameProfile::name,
            "properties", Property.LIST_CODEC.optional(List.of()), GameProfile::properties,
            GameProfile::new);

    public record Property(String name, String value, @Nullable String signature) implements PlayerHeadObjectContents.ProfileProperty {
        public Property {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
        }

        public Property(String name, String value) {
            this(name, value, null);
        }

        public static final NetworkBuffer.Type<Property> SERIALIZER = NetworkBufferTemplate.template(
                STRING, Property::name,
                STRING, Property::value,
                STRING.optional(), Property::signature,
                Property::new);
        public static final Codec<Property> CODEC = StructCodec.struct(
                "name", Codec.STRING, Property::name,
                "value", Codec.STRING, Property::value,
                "signature", Codec.STRING.optional(), Property::signature,
                Property::new);

        public static final Codec<List<Property>> LIST_CODEC = Codec
                .Either(Codec.STRING.mapValue(Codec.STRING), CODEC.list())
                .transform(either -> either.unify(
                        map -> map.entrySet().stream().map(
                                entry -> new Property(entry.getKey(), entry.getValue(), null)
                        ).toList(), Function.identity()),
                        Either::right);
    }
}
