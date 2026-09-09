package dev.gameengine.network.player;

import dev.gameengine.ServerFlag;
import dev.gameengine.entity.MainHand;
import dev.gameengine.entity.Player;
import dev.gameengine.message.ChatMessageType;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.utils.MathUtils;

import java.util.Locale;
import java.util.Objects;

import static dev.gameengine.network.NetworkBuffer.BOOLEAN;
import static dev.gameengine.network.NetworkBuffer.BYTE;
import static dev.gameengine.network.NetworkBuffer.Enum;
import static dev.gameengine.network.NetworkBuffer.STRING;

public record ClientSettings(Locale locale, byte viewDistance,
                             ChatMessageType chatMessageType, boolean chatColors,
                             byte displayedSkinParts, MainHand mainHand,
                             boolean enableTextFiltering, boolean allowServerListings,
                             ClientSettings.ParticleSetting particleSetting) {
    public static final ClientSettings DEFAULT = new ClientSettings(
            Locale.US, (byte) ServerFlag.CHUNK_VIEW_DISTANCE,
            ChatMessageType.FULL, true,
            (byte) 0x7F, MainHand.RIGHT,
            true, true,
            ParticleSetting.ALL
    );

    private static final NetworkBuffer.Type<Locale> LOCALE_SERIALIZER = STRING.transform(
            s -> {
                final String locale = s.replace("_", "-");
                return Locale.forLanguageTag(locale);
            },
            Locale::toLanguageTag
    );

    public static final NetworkBuffer.Type<ClientSettings> NETWORK_TYPE = NetworkBufferTemplate.template(
            LOCALE_SERIALIZER, ClientSettings::locale,
            BYTE, ClientSettings::viewDistance,
            Enum(ChatMessageType.class), ClientSettings::chatMessageType,
            BOOLEAN, ClientSettings::chatColors,
            BYTE, ClientSettings::displayedSkinParts,
            MainHand.NETWORK_TYPE, ClientSettings::mainHand,
            BOOLEAN, ClientSettings::enableTextFiltering,
            BOOLEAN, ClientSettings::allowServerListings,
            ParticleSetting.NETWORK_TYPE, ClientSettings::particleSetting,
            ClientSettings::new);

    public ClientSettings {
        Objects.requireNonNull(locale);
        // Clamp viewDistance to valid bounds
        viewDistance = (byte) MathUtils.clamp(viewDistance, 2, 32);
        Objects.requireNonNull(chatMessageType);
        Objects.requireNonNull(mainHand);
    }

    /**
     * Deprecated in favor of {@link Player#effectiveViewDistance()}
     *
     * @return The effective view distance, which is the smaller of either the client's view distance settings and the server's max view distance
     */
    @Deprecated
    public int effectiveViewDistance() {
        return Math.min(viewDistance(), ServerFlag.CHUNK_VIEW_DISTANCE);
    }

    public enum ParticleSetting {
        ALL,
        DECREASED,
        MINIMAL;

        public static final NetworkBuffer.Type<ParticleSetting> NETWORK_TYPE = Enum(ParticleSetting.class);
    }
}
