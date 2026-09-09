package dev.gameengine.network.packet.server.play.data;

import dev.gameengine.codec.Codec;
import dev.gameengine.codec.StructCodec;
import dev.gameengine.coordinate.Point;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;

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