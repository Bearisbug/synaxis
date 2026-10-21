package io.github.bearisbug.synaxis.entity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.key.Key;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.utils.mojang.MojangUtils;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

/**
 * Contains all the data required to store a skin.
 * <p>
 * Can be applied to a player with {@link Player#setSkin(PlayerSkin)}
 * or in the linked event {@link io.github.bearisbug.synaxis.event.player.PlayerSkinInitEvent}. That
 * event runs on the tick scheduler thread: fetch the skin with {@link #fromUuid} or
 * {@link #fromUsername} beforehand, off the tick threads, and only set it there.
 * <p>
 * In online mode a player already has its skin, taken from the verified profile at login
 * ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-003}): do not call these two methods for an online player.
 */
public record PlayerSkin(String textures, String signature) {

    /**
     * Gets a skin from a Mojang UUID.
     * <p>
     * Blocks on a request to Mojang: never call it on a tick thread or in a
     * {@code PlayerSkinInitEvent} listener.
     *
     * @param uuid Mojang UUID
     * @return the skin when found, otherwise null: when the UUID does not exist or is malformed, Mojang's API is
     * unreachable or rate limits the request, or the response is not of the expected form. The caller cannot tell
     * these apart, and nothing is logged
     */
    @Blocking
    public static @Nullable PlayerSkin fromUuid(String uuid) {
        final JsonObject jsonObject = MojangUtils.fromUuid(uuid);
        if (jsonObject == null) return null;
        try {
            final JsonArray propertiesArray = jsonObject.get("properties").getAsJsonArray();
            for (JsonElement jsonElement : propertiesArray) {
                final JsonObject propertyObject = jsonElement.getAsJsonObject();
                final String name = propertyObject.get("name").getAsString();
                if (!name.equals("textures")) continue;
                final String textureValue = propertyObject.get("value").getAsString();
                final String signatureValue = propertyObject.get("signature").getAsString();
                return new PlayerSkin(textureValue, signatureValue);
            }
            return null;
        } catch (Exception _) {
            return null;
        }
    }

    /**
     * Gets a skin from a Minecraft username.
     * <p>
     * Blocks on two requests to Mojang: never call it on a tick thread or in a
     * {@code PlayerSkinInitEvent} listener.
     *
     * @param username the Minecraft username
     * @return the skin when found, otherwise null: when the username does not exist or is malformed, Mojang's API is
     * unreachable or rate limits the request, or the response is not of the expected form. The caller cannot tell
     * these apart, and nothing is logged
     */
    @Blocking
    public static @Nullable PlayerSkin fromUsername(String username) {
        final JsonObject jsonObject = MojangUtils.fromUsername(username);
        if (jsonObject == null) return null;
        try {
            final String uuid = jsonObject.get("id").getAsString();
            // Retrieve the skin data from the mojang uuid
            return fromUuid(uuid);
        } catch (Exception _) {
            return null;
        }
    }

    public record Patch(
            @Nullable Key body,
            @Nullable Key cape,
            @Nullable Key elytra,
            @Nullable Boolean slim
    ) {
        public static final Patch EMPTY = new Patch(null, null, null, null);

        public static final NetworkBuffer.Type<Patch> NETWORK_TYPE = NetworkBufferTemplate.template(
                NetworkBuffer.KEY.optional(), Patch::body,
                NetworkBuffer.KEY.optional(), Patch::cape,
                NetworkBuffer.KEY.optional(), Patch::elytra,
                NetworkBuffer.BOOLEAN.optional(), Patch::slim,
                Patch::new);
        public static final StructCodec<Patch> CODEC = StructCodec.struct(
                "texture", Codec.KEY.optional(), Patch::body,
                "cape", Codec.KEY.optional(), Patch::cape,
                "elytra", Codec.KEY.optional(), Patch::elytra,
                "model", Model.BOOLEAN_CODEC.optional(), Patch::slim,
                Patch::new);

        public Patch(Key body) {
            this(body, null, null, null);
        }

        /// Use [#model()] instead
        /// @return slim
        @Deprecated
        @Override
        public @Nullable Boolean slim() {
            return slim;
        }

        public @Nullable Model model() {
            if (slim == null) return null;
            return slim ? Model.SLIM : Model.WIDE;
        }
    }

    public enum Model {
        WIDE,
        SLIM;

        public static final Codec<Model> CODEC = Codec.Enum(Model.class);
        public static final NetworkBuffer.Type<Model> NETWORK_TYPE = NetworkBuffer.Enum(Model.class); // slim == 1

        // TODO(next): move to model directly in the codec instead of weird boolean
        private static final Codec<Boolean> BOOLEAN_CODEC = CODEC.transform(
                model -> model == Model.SLIM,
                slim -> slim ? Model.SLIM : Model.WIDE
        );
    }

}
