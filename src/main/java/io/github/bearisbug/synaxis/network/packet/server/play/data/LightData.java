package io.github.bearisbug.synaxis.network.packet.server.play.data;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;

import java.util.BitSet;
import java.util.List;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BITSET;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.BYTE_ARRAY;

public record LightData(
        BitSet skyMask, BitSet blockMask,
        BitSet emptySkyMask, BitSet emptyBlockMask,
        List<byte[]> skyLight,
        List<byte[]> blockLight
) {
    public LightData {
        skyMask = (BitSet) skyMask.clone();
        blockMask = (BitSet) blockMask.clone();
        emptySkyMask = (BitSet) emptySkyMask.clone();
        emptyBlockMask = (BitSet) emptyBlockMask.clone();
        // The byte arrays are kept as given, not copied
        skyLight = List.copyOf(skyLight);
        blockLight = List.copyOf(blockLight);
    }

    // The 254 block sections of the tallest world (4064 blocks) plus the light sections below and above it
    public static final int MAX_SECTIONS = 4096 / 16;

    public static final NetworkBuffer.Type<LightData> NETWORK_TYPE = NetworkBufferTemplate.template(
            BITSET, LightData::skyMask,
            BITSET, LightData::blockMask,
            BITSET, LightData::emptySkyMask,
            BITSET, LightData::emptyBlockMask,
            BYTE_ARRAY.list(MAX_SECTIONS), LightData::skyLight,
            BYTE_ARRAY.list(MAX_SECTIONS), LightData::blockLight,
            LightData::new
    );
}
