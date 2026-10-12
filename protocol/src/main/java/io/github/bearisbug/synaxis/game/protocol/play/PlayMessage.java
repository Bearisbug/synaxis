package io.github.bearisbug.synaxis.game.protocol.play;

import io.github.bearisbug.synaxis.game.protocol.Message;

/**
 * The five messages of {@code synaxis:play} version 1 ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-002}):
 * scene, HUD and action result down, scene ready and action up.
 */
public sealed interface PlayMessage extends Message
        permits S2CScene, S2CHud, S2CActionResult, C2SSceneReady, C2SAction {
}
