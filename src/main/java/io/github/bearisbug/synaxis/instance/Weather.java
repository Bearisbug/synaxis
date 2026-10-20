package io.github.bearisbug.synaxis.instance;

import it.unimi.dsi.fastutil.floats.FloatUnaryOperator;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChangeGameStatePacket;
import io.github.bearisbug.synaxis.utils.MathUtils;
import org.jetbrains.annotations.Contract;

import java.util.Collection;
import java.util.List;

/**
 * Represents the possible weather properties of an instance
 *
 * @param rainLevel    a percentage between 0 and 1
 *                     used to change how heavy the rain is
 *                     higher values darken the sky and increase rain opacity
 * @param thunderLevel a percentage between 0 and 1
 *                     used to change how heavy the thunder is
 *                     higher values further darken the sky
 */
public record Weather(float rainLevel, float thunderLevel) {
    public static final Weather CLEAR = new Weather(0, 0);
    public static final Weather RAIN = new Weather(1, 0);
    public static final Weather THUNDER = new Weather(1, 1);

    /**
     * @throws IllegalArgumentException if {@code rainLevel} is not between 0 and 1
     * @throws IllegalArgumentException if {@code thunderLevel} is not between 0 and 1
     */
    public Weather {
        // Built on every tick of a transition, so the message is only built when it is thrown
        if (!MathUtils.isBetween(rainLevel, 0, 1))
            throw new IllegalArgumentException("Rain level should be between 0 and 1 but was " + rainLevel);
        if (!MathUtils.isBetween(thunderLevel, 0, 1))
            throw new IllegalArgumentException("Thunder level should be between 0 and 1 but was " + thunderLevel);
    }

    @Contract(pure = true)
    public Weather withRainLevel(float rainLevel) {
        return new Weather(rainLevel, thunderLevel);
    }

    /**
     * {@return true if {@code rainLevel} is > 0}
     */
    public boolean isRaining() {
        return rainLevel > 0;
    }

    @Contract(pure = true)
    public Weather withRainLevel(FloatUnaryOperator operator) {
        return withRainLevel(operator.apply(rainLevel));
    }

    @Contract(pure = true)
    public Weather withThunderLevel(float thunderLevel) {
        return new Weather(rainLevel, thunderLevel);
    }

    @Contract(pure = true)
    public Weather withThunderLevel(FloatUnaryOperator operator) {
        return withThunderLevel(operator.apply(thunderLevel));
    }

    public ChangeGameStatePacket createIsRainingPacket() {
        return new ChangeGameStatePacket(isRaining() ? ChangeGameStatePacket.Reason.BEGIN_RAINING : ChangeGameStatePacket.Reason.END_RAINING, 0);
    }

    public ChangeGameStatePacket createRainLevelPacket() {
        return new ChangeGameStatePacket(ChangeGameStatePacket.Reason.RAIN_LEVEL_CHANGE, rainLevel);
    }

    public ChangeGameStatePacket createThunderLevelPacket() {
        return new ChangeGameStatePacket(ChangeGameStatePacket.Reason.THUNDER_LEVEL_CHANGE, thunderLevel);
    }

    @SuppressWarnings("PreferredInterfaceType") // wider type kept for binary compatibility until the next breaking release
    public Collection<SendablePacket> createWeatherPackets() {
        return List.of(createIsRainingPacket(), createRainLevelPacket(), createThunderLevelPacket());
    }
}
