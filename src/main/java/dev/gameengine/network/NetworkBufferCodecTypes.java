package dev.gameengine.network;

import com.google.gson.JsonElement;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.text.Component;
import dev.gameengine.adventure.ComponentCodecs;
import dev.gameengine.codec.Codec;
import dev.gameengine.codec.Result;
import dev.gameengine.codec.Transcoder;
import dev.gameengine.registry.Registries;
import dev.gameengine.registry.RegistryTranscoder;
import dev.gameengine.utils.json.JsonUtil;
import dev.gameengine.utils.validate.Check;

import static dev.gameengine.network.NetworkBuffer.NBT;
import static dev.gameengine.network.NetworkBuffer.STRING;
import static dev.gameengine.network.NetworkBuffer.UNTRUSTED_NBT;

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
            final Result<BinaryTag> result = nbtType.encode(new RegistryTranscoder<>(Transcoder.NBT, registries), value);
            switch (result) {
                case Result.Ok(BinaryTag tag) -> buffer.write(NBT, tag);
                case Result.Error(String message) -> throw new IllegalArgumentException("Invalid NBT tag: " + message);
            }
        }

        @Override
        public T read(NetworkBuffer buffer) {
            final Registries registries = buffer.registries();
            Check.stateCondition(registries == null, "Buffer does not have registries");
            final Result<T> result = nbtType.decode(new RegistryTranscoder<>(Transcoder.NBT, registries), buffer.read(UNTRUSTED_NBT));
            return switch (result) {
                case Result.Ok(T value) -> value;
                case Result.Error(String message) -> throw new IllegalArgumentException("Invalid NBT tag: " + message);
            };
        }
    }
}
