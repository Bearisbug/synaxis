package dev.gameengine.message;

import dev.gameengine.codec.Codec;
import dev.gameengine.codec.StructCodec;
import dev.gameengine.registry.BuiltinRegistries;
import dev.gameengine.registry.DynamicRegistry;
import dev.gameengine.registry.Holder;
import org.jetbrains.annotations.ApiStatus;

public sealed interface ChatType extends Holder.Direct<ChatType>, ChatTypes permits ChatTypeImpl {

    Codec<ChatType> REGISTRY_CODEC = StructCodec.struct(
            "chat", ChatTypeDecoration.CODEC, ChatType::chat,
            "narration", ChatTypeDecoration.CODEC, ChatType::narration,
            ChatType::create);

    static ChatType create(
            ChatTypeDecoration chat,
            ChatTypeDecoration narration
    ) {
        return new ChatTypeImpl(chat, narration);
    }

    static Builder builder() {
        return new Builder();
    }


    /**
     * <p>Creates a new registry for chat types, loading the vanilla chat types.</p>
     *
     * @see dev.gameengine.ServerProcess to get an existing instance of the registry
     */
    @ApiStatus.Internal
    static DynamicRegistry<ChatType> createDefaultRegistry() {
        return DynamicRegistry.create(BuiltinRegistries.CHAT_TYPE, REGISTRY_CODEC);
    }

    ChatTypeDecoration chat();

    ChatTypeDecoration narration();

    final class Builder {
        private ChatTypeDecoration chat;
        private ChatTypeDecoration narration;

        private Builder() {
        }

        public Builder chat(ChatTypeDecoration chat) {
            this.chat = chat;
            return this;
        }

        public Builder narration(ChatTypeDecoration narration) {
            this.narration = narration;
            return this;
        }

        public ChatType build() {
            return new ChatTypeImpl(chat, narration);
        }
    }
}
