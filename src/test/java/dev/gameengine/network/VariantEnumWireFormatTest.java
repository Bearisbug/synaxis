package dev.gameengine.network;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import dev.gameengine.codec.Codec;
import dev.gameengine.codec.Result;
import dev.gameengine.codec.Transcoder;
import dev.gameengine.entity.ArmadilloState;
import dev.gameengine.entity.CopperGolemState;
import dev.gameengine.entity.CopperGolemWeatherState;
import dev.gameengine.entity.SnifferState;
import dev.gameengine.entity.VillagerData;
import dev.gameengine.item.component.AxolotlVariant;
import dev.gameengine.item.component.FoxVariant;
import dev.gameengine.item.component.HorseVariant;
import dev.gameengine.item.component.LlamaVariant;
import dev.gameengine.item.component.MooshroomVariant;
import dev.gameengine.item.component.ParrotVariant;
import dev.gameengine.item.component.RabbitVariant;
import dev.gameengine.item.component.SalmonSize;
import dev.gameengine.item.component.TropicalFishPattern;
import dev.gameengine.registry.Registries;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Guards the wire format of the protocol enums that used to be nested in the mob {@code *Meta} classes,
 * see {@code docs/TEST.md} TC-007. Every constant is written through its network type and its codec and
 * compared against the table recorded before REQ-006 moved the enums out of {@code entity/metadata}:
 * an ordinal, a name, a VarInt or an NBT string that moved would silently desync a vanilla client, and
 * the item component ones also feed the CRC32 of {@code ItemStack.Hash}.
 */
class VariantEnumWireFormatTest {
    private static final String BASELINE = "/protocol/variant-enums-26.2.txt";

    private static <E extends Enum<E>> void collect(List<String> out, String group, E[] values,
                                                    NetworkBuffer.Type<E> networkType, @Nullable Codec<E> codec) {
        for (E value : values) {
            final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(32, Registries.vanilla());
            networkType.write(buffer, value);
            buffer.readIndex(0);
            final int wire = buffer.read(NetworkBuffer.VAR_INT);
            String nbt = "-";
            if (codec != null) {
                final Result<BinaryTag> result = codec.encode(Transcoder.NBT, value);
                final BinaryTag tag = (BinaryTag) assertInstanceOf(Result.Ok.class, result).value();
                nbt = assertInstanceOf(StringBinaryTag.class, tag).value();
            }
            out.add(group + "\t" + value.ordinal() + "\t" + value.name() + "\t" + wire + "\t" + nbt);
        }
    }

    @Test
    void everyExtractedEnumMatchesTheRecordedTable() throws IOException {
        final List<String> actual = new ArrayList<>();
        collect(actual, "fox/variant", FoxVariant.values(), FoxVariant.NETWORK_TYPE, FoxVariant.CODEC);
        collect(actual, "salmon/size", SalmonSize.values(), SalmonSize.NETWORK_TYPE, SalmonSize.CODEC);
        collect(actual, "parrot/variant", ParrotVariant.values(), ParrotVariant.NETWORK_TYPE, ParrotVariant.CODEC);
        collect(actual, "tropical_fish/pattern", TropicalFishPattern.values(), TropicalFishPattern.NETWORK_TYPE, TropicalFishPattern.CODEC);
        collect(actual, "mooshroom/variant", MooshroomVariant.values(), MooshroomVariant.NETWORK_TYPE, MooshroomVariant.CODEC);
        collect(actual, "rabbit/variant", RabbitVariant.values(), RabbitVariant.NETWORK_TYPE, RabbitVariant.CODEC);
        collect(actual, "horse/variant", HorseVariant.values(), HorseVariant.NETWORK_TYPE, HorseVariant.CODEC);
        collect(actual, "llama/variant", LlamaVariant.values(), LlamaVariant.NETWORK_TYPE, LlamaVariant.CODEC);
        collect(actual, "axolotl/variant", AxolotlVariant.values(), AxolotlVariant.NETWORK_TYPE, AxolotlVariant.CODEC);
        collect(actual, "armadillo/state", ArmadilloState.values(), ArmadilloState.NETWORK_TYPE, null);
        collect(actual, "sniffer/state", SnifferState.values(), SnifferState.NETWORK_TYPE, null);
        collect(actual, "copper_golem/state", CopperGolemState.values(), CopperGolemState.NETWORK_TYPE, CopperGolemState.CODEC);
        collect(actual, "copper_golem/weather_state", CopperGolemWeatherState.values(), CopperGolemWeatherState.NETWORK_TYPE, CopperGolemWeatherState.CODEC);
        collect(actual, "villager/level", VillagerData.Level.values(), VillagerData.Level.NETWORK_TYPE, null);

        final String expected;
        try (InputStream stream = VariantEnumWireFormatTest.class.getResourceAsStream(BASELINE)) {
            expected = new String(Objects.requireNonNull(stream, BASELINE).readAllBytes(), StandardCharsets.UTF_8);
        }
        assertEquals(expected.strip(), String.join("\n", actual));
    }

    @Test
    void everyExtractedEnumRoundTripsThroughItsNetworkType() {
        roundTrip(FoxVariant.values(), FoxVariant.NETWORK_TYPE);
        roundTrip(SalmonSize.values(), SalmonSize.NETWORK_TYPE);
        roundTrip(ParrotVariant.values(), ParrotVariant.NETWORK_TYPE);
        roundTrip(TropicalFishPattern.values(), TropicalFishPattern.NETWORK_TYPE);
        roundTrip(MooshroomVariant.values(), MooshroomVariant.NETWORK_TYPE);
        roundTrip(RabbitVariant.values(), RabbitVariant.NETWORK_TYPE);
        roundTrip(HorseVariant.values(), HorseVariant.NETWORK_TYPE);
        roundTrip(LlamaVariant.values(), LlamaVariant.NETWORK_TYPE);
        roundTrip(AxolotlVariant.values(), AxolotlVariant.NETWORK_TYPE);
        roundTrip(ArmadilloState.values(), ArmadilloState.NETWORK_TYPE);
        roundTrip(SnifferState.values(), SnifferState.NETWORK_TYPE);
        roundTrip(CopperGolemState.values(), CopperGolemState.NETWORK_TYPE);
        roundTrip(CopperGolemWeatherState.values(), CopperGolemWeatherState.NETWORK_TYPE);
        roundTrip(VillagerData.Level.values(), VillagerData.Level.NETWORK_TYPE);
    }

    private static <E extends Enum<E>> void roundTrip(E[] values, NetworkBuffer.Type<E> networkType) {
        for (E value : values) {
            final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(32, Registries.vanilla());
            networkType.write(buffer, value);
            buffer.readIndex(0);
            assertEquals(value, networkType.read(buffer), value.name());
        }
    }
}
