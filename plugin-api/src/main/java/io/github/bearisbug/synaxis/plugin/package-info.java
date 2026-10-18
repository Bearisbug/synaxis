/**
 * The stable surface a plugin is written against.
 *
 * <h2>Threads</h2>
 * The thread contract of {@code docs/DESIGN-PROD.md} {@code API-PROD-016}, which holds whatever
 * number of tick threads the process runs with (an MMO shard runs all cores but two, spread by
 * cells of 16 by 16 chunks):
 * <ul>
 *     <li>a callback about an entity or a player (its events, its dormancy policy, the payloads of
 *     its channels, the tasks {@link io.github.bearisbug.synaxis.plugin.PluginScheduler#runOn} hands
 *     it) runs on the tick thread that owns that entity, so the plugin may read and change that
 *     entity there;</li>
 *     <li>that holds for the events the host calls when a plugin asks it for something: a
 *     {@link io.github.bearisbug.synaxis.plugin.Transfers#transfer}, from whatever thread, is carried
 *     out at the player's next tick on the player's tick thread, where its
 *     {@code OutgoingTransferEvent} is called;</li>
 *     <li>an effect on another entity, which may belong to another tick thread (an attack, a
 *     trade), is handed to the target with {@code runOn} and runs on the target's thread at its
 *     next tick; {@code Acquirable#sync} is only for the case where the value has to be read in
 *     the same tick, since every access across tick threads waits on one lock of the process;</li>
 *     <li>{@link io.github.bearisbug.synaxis.plugin.PluginScheduler#scheduleTask} runs on the tick
 *     scheduler thread, one task after another: logic a plugin runs there every tick is the serial
 *     part of the tick, and a plugin that has to scale splits it by cell or by entity;</li>
 *     <li>the {@code apply} of {@link io.github.bearisbug.synaxis.plugin.PlayerDataStore#commitTogether}
 *     runs there too, once, in the serial part of the tick after the commit was written: it may
 *     touch every participant, and checks first that each of them is still there;</li>
 *     <li>{@link io.github.bearisbug.synaxis.plugin.PluginScheduler#runAsync} runs on a virtual
 *     thread of its own and touches no entity and no instance;</li>
 *     <li>the server list event {@code ServerListPingEvent} runs on the read thread of the
 *     connection that asked, a virtual thread, and changes nothing but the event.</li>
 * </ul>
 */
@NotNullByDefault
package io.github.bearisbug.synaxis.plugin;

import org.jetbrains.annotations.NotNullByDefault;
