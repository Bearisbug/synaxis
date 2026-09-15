package io.github.bearisbug.synaxis.message;

import io.github.bearisbug.synaxis.registry.RegistryKey;

/**
 * This class contains all the generated constants for {@link ChatType}
 * <br>
 * 原为生成物，自 REQ-008 起作为普通源码维护；重新生成的方法见 `docs/ENGINE.md` 升版本 checklist。
 */
@SuppressWarnings("unused")
sealed interface ChatTypes permits ChatType {
    RegistryKey<ChatType> CHAT = RegistryKey.unsafeOf("chat");

    RegistryKey<ChatType> EMOTE_COMMAND = RegistryKey.unsafeOf("emote_command");

    RegistryKey<ChatType> MSG_COMMAND_INCOMING = RegistryKey.unsafeOf("msg_command_incoming");

    RegistryKey<ChatType> MSG_COMMAND_OUTGOING = RegistryKey.unsafeOf("msg_command_outgoing");

    RegistryKey<ChatType> SAY_COMMAND = RegistryKey.unsafeOf("say_command");

    RegistryKey<ChatType> TEAM_MSG_COMMAND_INCOMING = RegistryKey.unsafeOf("team_msg_command_incoming");

    RegistryKey<ChatType> TEAM_MSG_COMMAND_OUTGOING = RegistryKey.unsafeOf("team_msg_command_outgoing");
}
