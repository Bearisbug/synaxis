package io.github.bearisbug.synaxis.item.component;

import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.Result;
import io.github.bearisbug.synaxis.codec.StructCodec;
import io.github.bearisbug.synaxis.codec.Transcoder;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;

import java.util.List;

public record FireworkList(int flightDuration, List<FireworkExplosion> explosions) {
    public static final FireworkList EMPTY = new FireworkList(0, List.of());

    public static final NetworkBuffer.Type<FireworkList> NETWORK_TYPE = NetworkBufferTemplate.template(
            NetworkBuffer.VAR_INT, FireworkList::flightDuration,
            FireworkExplosion.NETWORK_TYPE.list(256), FireworkList::explosions,
            FireworkList::new);
    public static final Codec<FireworkList> NBT_TYPE = new StructCodec<>() {
        // Mojang uses a byte here but var int for protocol so we map to byte here
        private static final Codec<Integer> FLIGHT_DURATION = Codec.BYTE.transform(Byte::intValue, Integer::byteValue);
        // Mojang decodes flight_duration optionally (defaulting to 1) but always writes it back
        private static final StructCodec<FireworkList> DECODER = StructCodec.struct(
                "flight_duration", FLIGHT_DURATION.optional(1), FireworkList::flightDuration,
                "explosions", FireworkExplosion.CODEC.list().optional(List.of()), FireworkList::explosions,
                FireworkList::new);
        private static final StructCodec<FireworkList> ENCODER = StructCodec.struct(
                "flight_duration", FLIGHT_DURATION, FireworkList::flightDuration,
                "explosions", FireworkExplosion.CODEC.list().optional(List.of()), FireworkList::explosions,
                FireworkList::new);

        @Override
        public <D> Result<FireworkList> decodeFromMap(Transcoder<D> coder, Transcoder.MapLike<D> map) {
            return DECODER.decodeFromMap(coder, map);
        }

        @Override
        public <D> Result<D> encodeToMap(Transcoder<D> coder, FireworkList value, Transcoder.MapBuilder<D> map) {
            return ENCODER.encodeToMap(coder, value, map);
        }
    };

    public FireworkList {
        explosions = List.copyOf(explosions);
    }

    public FireworkList withFlightDuration(int flightDuration) {
        return new FireworkList(flightDuration, explosions);
    }

    public FireworkList withExplosions(List<FireworkExplosion> explosions) {
        return new FireworkList(flightDuration, explosions);
    }
}
