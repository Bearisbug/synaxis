package io.github.bearisbug.synaxis.potion;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.utils.validate.Check;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BOOLEAN;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

/**
 * Represents a custom effect in {@link io.github.bearisbug.synaxis.component.DataComponents#POTION_CONTENTS}.
 */
public record CustomPotionEffect(PotionEffect id, Settings settings) {

    public static final NetworkBuffer.Type<CustomPotionEffect> NETWORK_TYPE = NetworkBufferTemplate.template(
            PotionEffect.NETWORK_TYPE, CustomPotionEffect::id,
            Settings.NETWORK_TYPE, CustomPotionEffect::settings,
            CustomPotionEffect::new);
    public static final Codec<CustomPotionEffect> CODEC = StructCodec.struct(
            "id", PotionEffect.CODEC, CustomPotionEffect::id,
            StructCodec.INLINE, Settings.CODEC, CustomPotionEffect::settings,
            CustomPotionEffect::new);

    public CustomPotionEffect {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(settings, "settings");
    }

    public CustomPotionEffect(PotionEffect id, int amplifier, int duration, boolean isAmbient, boolean showParticles, boolean showIcon) {
        this(id, new Settings(amplifier, duration, isAmbient, showParticles, showIcon, null));
    }

    public int amplifier() {
        return settings.amplifier;
    }

    public int duration() {
        return settings.duration;
    }

    public boolean isAmbient() {
        return settings.isAmbient;
    }

    public boolean showParticles() {
        return settings.showParticles;
    }

    public boolean showIcon() {
        return settings.showIcon;
    }

    /**
     * The settings of a custom potion effect.
     * <p>
     * The amplifier is an unsigned byte, from 0 to 255 as in 26.2: it is stored as a byte, so 200 is stored as the
     * byte {@code -56} and read back as 200. A value outside that range is refused here, which makes reading a packet
     * carrying one fail. A JSON number above 255 is cut to a byte by the transcoder before it gets here, as 26.2 does.
     *
     * @param amplifier     the amplifier, from 0 (level 1) to 255
     * @param duration      the duration in ticks
     * @param isAmbient     whether the effect is ambient
     * @param showParticles whether the effect shows particles
     * @param showIcon      whether the effect shows its icon
     * @param hiddenEffect  the settings of the effect this one hides, null for none
     */
    public record Settings(
            int amplifier, int duration,
            boolean isAmbient, boolean showParticles, boolean showIcon,
            @Nullable Settings hiddenEffect
    ) {
        public static final NetworkBuffer.Type<Settings> NETWORK_TYPE = NetworkBuffer.Recursive(self ->
                NetworkBufferTemplate.template(
                        VAR_INT, Settings::amplifier,
                        VAR_INT, Settings::duration,
                        BOOLEAN, Settings::isAmbient,
                        BOOLEAN, Settings::showParticles,
                        BOOLEAN, Settings::showIcon,
                        self.optional(), Settings::hiddenEffect,
                        Settings::new
                ));
        public static final Codec<Settings> CODEC = Codec.Recursive(self -> StructCodec.struct(
                "amplifier", Codec.BYTE.<Integer>transform(Byte::toUnsignedInt, i -> (byte) (int) i).optional(0), Settings::amplifier,
                "duration", Codec.INT.optional(0), Settings::duration,
                "ambient", Codec.BOOLEAN.optional(false), Settings::isAmbient,
                "show_particles", Codec.BOOLEAN.optional(true), Settings::showParticles,
                "show_icon", Codec.BOOLEAN.optional(), Settings::showIcon,
                "hidden_effect", self.optional(), Settings::hiddenEffect,
                Settings::withOptionalIcon
        ));

        public Settings {
            Check.argCondition(amplifier < 0 || amplifier > 255, "amplifier must be within [0, 255], got {0}", amplifier);
        }

        // Exists because showIcon needs to default to the value of showParticles which we can't do inline.
        private static Settings withOptionalIcon(
                int amplifier, int duration,
                boolean isAmbient, boolean showParticles,
                @Nullable Boolean showIcon,
                @Nullable Settings hiddenEffect
        ) {
            return new Settings(amplifier, duration, isAmbient, showParticles,
                    Objects.requireNonNullElse(showIcon, showParticles), hiddenEffect);
        }

    }

}
