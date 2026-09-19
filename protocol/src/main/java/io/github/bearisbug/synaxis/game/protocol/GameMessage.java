package io.github.bearisbug.synaxis.game.protocol;

/**
 * The seven messages of the RTS slice channel ({@code API-M2-003} to {@code API-M2-006}): camera,
 * selection, order, spawn, snapshot, despawn and order result.
 */
public sealed interface GameMessage extends Message
        permits C2SCamera, C2SSelect, C2SMoveOrder, S2CUnitSpawn, S2CUnitState, S2CUnitDespawn, S2COrderResult {
}
