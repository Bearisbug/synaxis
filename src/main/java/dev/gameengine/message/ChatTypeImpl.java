package dev.gameengine.message;

import java.util.Objects;

record ChatTypeImpl(
        ChatTypeDecoration chat,
        ChatTypeDecoration narration
) implements ChatType {

    ChatTypeImpl {
        Objects.requireNonNull(chat, "missing chat");
        Objects.requireNonNull(narration, "missing narration");
    }

}
