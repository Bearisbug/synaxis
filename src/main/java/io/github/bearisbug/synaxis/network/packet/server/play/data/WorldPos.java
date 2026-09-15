package io.github.bearisbug.synaxis.network.packet.server.play.data;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.StructCodec;
import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;

public record WorldPos(String dimension, Point blockPosition) {
    public static final NetworkBuffer.Type<WorldPos> NETWORK_TYPE = NetworkBufferTemplate.template(
            NetworkBuffer.STRING, WorldPos::dimension,
            NetworkBuffer.BLOCK_POSITION, WorldPos::blockPosition,
            WorldPos::new);
    public static final Codec<WorldPos> CODEC = StructCodec.struct(
            "dimension", Codec.STRING, WorldPos::dimension,
            "pos", Codec.BLOCK_POSITION, WorldPos::blockPosition,
            WorldPos::new
    );

    public WorldPos withDimension(String dimension) {
        return new WorldPos(dimension, blockPosition);
    }

    public WorldPos withBlockPosition(Point blockPosition) {
        return new WorldPos(dimension, blockPosition);
    }
}