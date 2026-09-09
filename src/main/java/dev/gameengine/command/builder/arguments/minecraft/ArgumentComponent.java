package dev.gameengine.command.builder.arguments.minecraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import net.kyori.adventure.text.Component;
import dev.gameengine.adventure.ComponentCodecs;
import dev.gameengine.codec.Result;
import dev.gameengine.codec.Transcoder;
import dev.gameengine.command.ArgumentParserType;
import dev.gameengine.command.CommandSender;
import dev.gameengine.command.builder.arguments.Argument;
import dev.gameengine.command.builder.exception.ArgumentSyntaxException;
import dev.gameengine.registry.RegistryTranscoder;
import dev.gameengine.utils.json.JsonUtil;

public class ArgumentComponent extends Argument<Component> {
    public static final int INVALID_JSON_ERROR = 1;

    public ArgumentComponent(String id) {
        super(id, true);
    }

    @Override
    public Component parse(CommandSender sender, String input) throws ArgumentSyntaxException {
        try {
            final Transcoder<JsonElement> coder = new RegistryTranscoder<>(
                    Transcoder.JSON, sender.process().registries());
            final Result<Component> result = ComponentCodecs.COMPONENT.decode(coder, JsonUtil.fromJson(input));
            return switch (result) {
                case Result.Ok(var component) -> component;
                case Result.Error(var message) ->
                        throw new ArgumentSyntaxException("Failed to parse component: " + message, input, INVALID_JSON_ERROR);
            };
        } catch (JsonParseException _) {
            throw new ArgumentSyntaxException("Invalid JSON", input, INVALID_JSON_ERROR);
        }
    }

    @Override
    public ArgumentParserType parser() {
        return ArgumentParserType.COMPONENT;
    }

    @Override
    public String toString() {
        return String.format("Component<%s>", getId());
    }
}
