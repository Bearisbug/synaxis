package io.github.bearisbug.synaxis.network.player;

import io.github.bearisbug.synaxis.entity.MainHand;
import io.github.bearisbug.synaxis.message.ChatMessageType;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.utils.MathUtils;

import java.util.Locale;
import java.util.Objects;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BOOLEAN;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.BYTE;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.Enum;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.STRING;

public record ClientSettings(Locale locale, byte viewDistance,
                             ChatMessageType chatMessageType, boolean chatColors,
                             byte displayedSkinParts, MainHand mainHand,
                             boolean enableTextFiltering, boolean allowServerListings,
                             ClientSettings.ParticleSetting particleSetting) {
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

    /**
     * The settings assumed before the client sends its own, using the server view distance.
     */
    public static ClientSettings defaultSettings(int viewDistance) {
        return new ClientSettings(
                Locale.US, (byte) viewDistance,
                ChatMessageType.FULL, true,
                (byte) 0x7F, MainHand.RIGHT,
                true, true,
                ParticleSetting.ALL
        );
    }

    public ClientSettings {
        Objects.requireNonNull(locale);
        // Clamp viewDistance to valid bounds
        viewDistance = (byte) MathUtils.clamp(viewDistance, 2, 32);
        Objects.requireNonNull(chatMessageType);
        Objects.requireNonNull(mainHand);
    }

    public enum ParticleSetting {
        ALL,
        DECREASED,
        MINIMAL;

        public static final NetworkBuffer.Type<ParticleSetting> NETWORK_TYPE = Enum(ParticleSetting.class);
    }
}
