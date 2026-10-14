package io.github.bearisbug.synaxis.synx;

/**
 * The counters of the runtime light ({@code docs/DESIGN-M3.md} {@code API-M3-005} 运行时光照, §20):
 * the first five count since the start, the percentiles and the maximum cover the window since the
 * last {@link SynxRelight#stats()}.
 *
 * @param relights          recomputations done, snapshot and computation both
 * @param skipped           target entries the install passed over: their 3×3 changed since the snapshot,
 *                          or they are missing, failed or outside the region
 * @param lightMaterialized handle chunks materialized for their light
 * @param lightPackets      {@code UpdateLightPacket}s sent, resends and the fallback together
 * @param lightSections     the sections those packets carried
 * @param queueMsP99        P99 of the time from the first change of a center until its result was all installed,
 *                          over the first 65,536 of the window
 * @param relightMsP99      P99 of the time a recomputation took on its thread from its snapshot on: the loads of
 *                          the 5×5, segment ① decoded and the materializations for light included; over the first
 *                          65,536 of the window
 * @param installMsMax      the longest install of one tick
 * @param pending           centers queued or running now, those whose results still wait for the install
 *                          included; {@code EVT:chunk_stream_stats} writes it as {@code relight_pending}, which is
 *                          not the per-position {@code relight_pending} of {@link SynxRelight}
 */
public record RelightStats(long relights, long skipped, long lightMaterialized, long lightPackets, long lightSections,
                           double queueMsP99, double relightMsP99, double installMsMax, int pending) {
}
