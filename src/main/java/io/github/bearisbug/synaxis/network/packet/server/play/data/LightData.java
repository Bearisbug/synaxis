package io.github.bearisbug.synaxis.network.packet.server.play.data;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;

import java.util.BitSet;
import java.util.List;

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

    // Map files are read through NETWORK_TYPE too, so the masks and arrays are bounded on read:
    // a mask holds a bit for every section, an array a nibble for every block of one
    private static final NetworkBuffer.Type<BitSet> MASK = NetworkBuffer.LongArray(MAX_SECTIONS / Long.SIZE)
            .transform(BitSet::valueOf, BitSet::toLongArray);
    private static final NetworkBuffer.Type<List<byte[]>> ARRAYS = NetworkBuffer.ByteArray(2048).list(MAX_SECTIONS);

    public static final NetworkBuffer.Type<LightData> NETWORK_TYPE = NetworkBufferTemplate.template(
            MASK, LightData::skyMask,
            MASK, LightData::blockMask,
            MASK, LightData::emptySkyMask,
            MASK, LightData::emptyBlockMask,
            ARRAYS, LightData::skyLight,
            ARRAYS, LightData::blockLight,
            LightData::new
    );
}
