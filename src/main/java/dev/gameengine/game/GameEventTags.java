package dev.gameengine.game;

import dev.gameengine.registry.TagKey;
import org.jetbrains.annotations.ApiStatus;

/**
 * This class contains all the generated constants for {@link GameEvent}
 * <br>
 * 原为生成物，自 REQ-008 起作为普通源码维护；重新生成的方法见 `docs/ENGINE.md` 升版本 checklist。
 */
@SuppressWarnings("unused")
@ApiStatus.NonExtendable
public interface GameEventTags {
    TagKey<GameEvent> ALLAY_CAN_LISTEN = TagKey.unsafeOf("allay_can_listen");

    TagKey<GameEvent> IGNORE_VIBRATIONS_SNEAKING = TagKey.unsafeOf("ignore_vibrations_sneaking");

    TagKey<GameEvent> SHRIEKER_CAN_LISTEN = TagKey.unsafeOf("shrieker_can_listen");

    TagKey<GameEvent> VIBRATIONS = TagKey.unsafeOf("vibrations");

    TagKey<GameEvent> WARDEN_CAN_LISTEN = TagKey.unsafeOf("warden_can_listen");
}
