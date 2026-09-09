package dev.gameengine.item.component;

import dev.gameengine.codec.Codec;
import dev.gameengine.codec.StructCodec;
import dev.gameengine.coordinate.Point;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.play.data.WorldPos;
import org.jetbrains.annotations.Nullable;

public record LodestoneTracker(@Nullable WorldPos target, boolean tracked) {

    public static final NetworkBuffer.Type<LodestoneTracker> NETWORK_TYPE = NetworkBufferTemplate.template(
            WorldPos.NETWORK_TYPE.optional(), LodestoneTracker::target,
            NetworkBuffer.BOOLEAN, LodestoneTracker::tracked,
            LodestoneTracker::new);
    public static final Codec<LodestoneTracker> CODEC = StructCodec.struct(
            "target", WorldPos.CODEC.optional(), LodestoneTracker::target,
            "tracked", Codec.BOOLEAN.optional(true), LodestoneTracker::tracked,
            LodestoneTracker::new);

    public LodestoneTracker(String dimension, Point blockPosition, boolean tracked) {
        this(new WorldPos(dimension, blockPosition), tracked);
    }

    public LodestoneTracker withTarget(@Nullable WorldPos target) {
        return new LodestoneTracker(target, tracked);
    }

    public LodestoneTracker withTracked(boolean tracked) {
        return new LodestoneTracker(target, tracked);
    }

}
