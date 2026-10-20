package io.github.bearisbug.synaxis.network;

import com.google.gson.JsonElement;
import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.adventure.ComponentCodecs;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.Transcoder;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.registry.RegistryTranscoder;
import io.github.bearisbug.synaxis.utils.json.JsonUtil;
import io.github.bearisbug.synaxis.utils.validate.Check;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.NBT;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.STRING;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.UNTRUSTED_NBT;

final class NetworkBufferCodecTypes {

    record JsonComponentType() implements NetworkBufferTypeImpl<Component> {
        @Override
        public void write(NetworkBuffer buffer, Component value) {
            final Registries registries = buffer.registries();
            final Transcoder<JsonElement> coder = registries != null
                    ? new RegistryTranscoder<>(Transcoder.JSON, registries)
                    : Transcoder.JSON;
            final String json = JsonUtil.toJson(ComponentCodecs.COMPONENT.encode(coder, value).orElseThrow());
            buffer.write(STRING, json);
        }

        @Override
        public Component read(NetworkBuffer buffer) {
            final Registries registries = buffer.registries();
            final Transcoder<JsonElement> coder = registries != null
                    ? new RegistryTranscoder<>(Transcoder.JSON, registries)
                    : Transcoder.JSON;
            final JsonElement json = JsonUtil.fromJson(buffer.read(STRING));
            return ComponentCodecs.COMPONENT.decode(coder, json).orElseThrow();
        }
    }

    record TypedNbtType<T>(Codec<T> nbtType) implements NetworkBufferTypeImpl<T> {
        @Override
        public void write(NetworkBuffer buffer, T value) {
            final Registries registries = buffer.registries();
            Check.stateCondition(registries == null, "Buffer does not have registries");
            buffer.write(NBT, nbtType.encode(new RegistryTranscoder<>(Transcoder.NBT, registries), value).orElseThrow("Invalid NBT tag"));
        }

        @Override
        public T read(NetworkBuffer buffer) {
            final Registries registries = buffer.registries();
            Check.stateCondition(registries == null, "Buffer does not have registries");
            return nbtType.decode(new RegistryTranscoder<>(Transcoder.NBT, registries), buffer.read(UNTRUSTED_NBT)).orElseThrow("Invalid NBT tag");
        }
    }
}
