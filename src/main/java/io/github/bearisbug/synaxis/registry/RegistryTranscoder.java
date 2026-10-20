package io.github.bearisbug.synaxis.registry;

import io.github.bearisbug.synaxis.codec.Transcoder;
import io.github.bearisbug.synaxis.codec.TranscoderProxy;

import java.util.Objects;

public record RegistryTranscoder<D>(
        Transcoder<D> transcoder,
        Registries registries
) implements TranscoderProxy<D> {

    public RegistryTranscoder {
        Objects.requireNonNull(transcoder);
    }

    @Override
    public Transcoder<D> delegate() {
        return transcoder;
    }

}
