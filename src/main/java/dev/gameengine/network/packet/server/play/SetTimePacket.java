package dev.gameengine.network.packet.server.play;

import dev.gameengine.registry.BuiltinRegistries;
import net.kyori.adventure.nbt.BinaryTag;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;
import dev.gameengine.registry.RegistryKey;

import java.util.Map;

import static dev.gameengine.network.NetworkBuffer.FLOAT;
import static dev.gameengine.network.NetworkBuffer.LONG;
import static dev.gameengine.network.NetworkBuffer.VAR_LONG;

public record SetTimePacket(long gameTime,
                            Map<RegistryKey<BinaryTag>, ClockState> clocks) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<SetTimePacket> SERIALIZER = NetworkBufferTemplate.template(
            LONG, SetTimePacket::gameTime,
            RegistryKey.<BinaryTag>networkType(registries -> registries.opaque(BuiltinRegistries.WORLD_CLOCK)).mapValue(ClockState.NETWORK_TYPE), SetTimePacket::clocks,
            SetTimePacket::new);

    public SetTimePacket {
        clocks = Map.copyOf(clocks);
    }

    /**
     * Represents a clock state update for time.
     *
     * @param totalTicks  the number of ticks since this clock was ticking
     * @param partialTick the partial tick of the clock (based on rate), wiped on full update
     * @param rate        the rate of the clock in ticks, 1 for normal
     */
    public record ClockState(long totalTicks, float partialTick, float rate) {
        public static final NetworkBuffer.Type<ClockState> NETWORK_TYPE = NetworkBufferTemplate.template(
                VAR_LONG, ClockState::totalTicks,
                FLOAT, ClockState::partialTick,
                FLOAT, ClockState::rate,
                ClockState::new);
    }
}
