package dev.gameengine.adventure.provider;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.text.event.DataComponentValue;
import net.kyori.adventure.text.event.DataComponentValueConverterRegistry;
import net.kyori.adventure.text.serializer.gson.GsonDataComponentValue;
import dev.gameengine.adventure.AdventureRegistries;
import dev.gameengine.adventure.EngineDataComponentValue;
import dev.gameengine.adventure.serializer.nbt.NbtDataComponentValue;
import dev.gameengine.codec.Transcoder;
import dev.gameengine.component.DataComponent;
import dev.gameengine.registry.RegistryTranscoder;

import java.util.List;

import static net.kyori.adventure.text.event.DataComponentValueConverterRegistry.Conversion.convert;

@SuppressWarnings("UnstableApiUsage") // we are permitted to provide this
public final class EngineDataComponentValueConverterProvider implements DataComponentValueConverterRegistry.Provider {

    @Override
    public Key id() {
        return Key.key("minestom", "data_component_value_converter");
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<DataComponentValueConverterRegistry.Conversion<?, ?>> conversions() {
        return List.of(
                // GSON
                convert(GsonDataComponentValue.class, EngineDataComponentValue.class, (key, gsonValue) -> {
                    if (gsonValue instanceof DataComponentValue.Removed)
                        return EngineDataComponentValue.removed();
                    final DataComponent<Object> component = (DataComponent<Object>) DataComponent.fromKey(key);
                    if (component == null) throw new IllegalArgumentException("Unknown data component: " + key);
                    final Object value = component.decode(new RegistryTranscoder<>(Transcoder.JSON,
                            AdventureRegistries.vanilla()), gsonValue.element()).orElseThrow("failed to decode " + key);
                    return EngineDataComponentValue.dataComponentValue(value);
                }),
                convert(EngineDataComponentValue.class, GsonDataComponentValue.class, (key, engineValue) -> {
                    if (engineValue instanceof DataComponentValue.Removed)
                        return GsonDataComponentValue.gsonDataComponentValue(JsonNull.INSTANCE);
                    final DataComponent<Object> component = (DataComponent<Object>) DataComponent.fromKey(key);
                    if (component == null) throw new IllegalArgumentException("Unknown data component: " + key);
                    final JsonElement value = component.encode(new RegistryTranscoder<>(Transcoder.JSON,
                            AdventureRegistries.vanilla()), engineValue.value()).orElseThrow("failed to encode " + key);
                    return GsonDataComponentValue.gsonDataComponentValue(value);
                }),

                // NBT
                convert(NbtDataComponentValue.class, EngineDataComponentValue.class, (key, nbtValue) -> {
                    if (nbtValue instanceof DataComponentValue.Removed)
                        return EngineDataComponentValue.removed();
                    final DataComponent<Object> component = (DataComponent<Object>) DataComponent.fromKey(key);
                    if (component == null) throw new IllegalArgumentException("Unknown data component: " + key);
                    final Object value = component.decode(new RegistryTranscoder<>(Transcoder.NBT,
                            AdventureRegistries.vanilla()), nbtValue.value()).orElseThrow("failed to decode " + key);
                    return EngineDataComponentValue.dataComponentValue(value);
                }),
                convert(EngineDataComponentValue.class, NbtDataComponentValue.class, (key, engineValue) -> {
                    if (engineValue instanceof DataComponentValue.Removed)
                        return NbtDataComponentValue.removed();
                    final DataComponent<Object> component = (DataComponent<Object>) DataComponent.fromKey(key);
                    if (component == null) throw new IllegalArgumentException("Unknown data component: " + key);
                    final BinaryTag value = component.encode(new RegistryTranscoder<>(Transcoder.NBT,
                            AdventureRegistries.vanilla()), engineValue.value()).orElseThrow("failed to encode " + key);
                    return NbtDataComponentValue.nbtDataComponentValue(value);
                })
        );
    }

}
