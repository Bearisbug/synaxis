package dev.gameengine.message;

import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.translation.Translatable;
import dev.gameengine.codec.Codec;
import dev.gameengine.adventure.ComponentCodecs;
import dev.gameengine.codec.StructCodec;

import java.util.List;

public record ChatTypeDecoration(
        String translationKey,
        List<Parameter> parameters,
        Style style
) implements Translatable {
    public static final Codec<ChatTypeDecoration> CODEC = StructCodec.struct(
            "translation_key", Codec.STRING, ChatTypeDecoration::translationKey,
            "parameters", Parameter.CODEC.list().optional(List.of()), ChatTypeDecoration::parameters,
            "style", ComponentCodecs.STYLE.optional(Style.empty()), ChatTypeDecoration::style,
            ChatTypeDecoration::new);

    public enum Parameter {
        SENDER,
        TARGET,
        CONTENT;

        public static final Codec<Parameter> CODEC = Codec.Enum(Parameter.class);
    }

}
