/**
 * The stable surface a plugin is written against.
 *
 * <h2>Threads</h2>
 * The thread contract of {@code docs/DESIGN-PROD.md} {@code API-PROD-016}, which holds whatever
 * number of tick threads the process runs with (an MMO shard runs all cores but two, spread by
 * cells of 16 by 16 chunks):
 * <ul>
 *     <li>a callback about an entity or a player (its events, its dormancy policy, the payloads of
 *     its channels, which a handler only gets while the player is in the play state, the tasks
 *     {@link io.github.bearisbug.synaxis.plugin.PluginScheduler#runOn} hands it) runs on the tick
 *     thread that owns that entity, so the plugin may read and change that entity there; the player
 *     events of the exceptions below do not;</li>
 *     <li>that holds for the events the host calls when a plugin asks it for something: a
 *     {@link io.github.bearisbug.synaxis.plugin.Transfers#transfer}, from whatever thread, is carried
 *     out at the player's next tick on the player's tick thread, where its
 *     {@code OutgoingTransferEvent} is called;</li>
 *     <li>an effect on another entity, which may belong to another tick thread (an attack, a
 *     trade), is handed to the target with {@code runOn} and runs on the target's thread at its
 *     next tick; {@code Acquirable#sync} is only for the case where the value has to be read in
 *     the same tick, since every access across tick threads waits on one lock of the process;</li>
 *     <li>a listener of {@link io.github.bearisbug.synaxis.plugin.PluginEvents#forInstance} runs on
 *     the thread that dispatches its event: an entity or player event on the tick thread owning that
 *     entity, several of them for one instance under {@code grid}; {@code InstanceTickEvent} on the
 *     tick scheduler thread; {@code InstanceChunkLoadEvent} on the virtual thread that loaded the
 *     chunk; {@code PlayInstanceStateEvent} on the thread that carried the transition out. State the
 *     listeners of one instance share needs a concurrent structure or a lock;</li>
 *     <li>{@link io.github.bearisbug.synaxis.plugin.PluginScheduler#scheduleTask} runs on the tick
 *     scheduler thread, one task after another: logic a plugin runs there every tick is the serial
 *     part of the tick, and a plugin that has to scale splits it by cell or by entity. A task may
 *     read any entity and call the host methods documented as callable there (the scene layer); it
 *     does not change an entity assigned to a tick thread itself but hands the change over with
 *     {@code runOn} (next tick) or {@code Acquirable#sync} (at once): strict mode throws
 *     {@code AcquirableOwnershipException} on a direct change, and without it the change races a
 *     {@code sync} from another thread;</li>
 *     <li>a task {@link io.github.bearisbug.synaxis.plugin.PluginScheduler#runOn} hands an instance
 *     runs there too, where the instance's own scheduler runs, under the same rules;</li>
 *     <li>the {@code apply} of {@link io.github.bearisbug.synaxis.plugin.PlayerDataStore#commitTogether}
 *     runs there too, once, in the serial part of the tick after the commit was written: it may
 *     touch every participant, and checks first that each of them is still there;</li>
 *     <li>{@link io.github.bearisbug.synaxis.plugin.PluginScheduler#runAsync} runs on a virtual
 *     thread of its own and touches no entity and no instance;</li>
 *     <li>the server list event {@code ServerListPingEvent} runs on the read thread of the
 *     connection that asked, a virtual thread, and changes nothing but the event;</li>
 *     <li>{@link io.github.bearisbug.synaxis.plugin.SynaxisPlugin#onDisable} runs on a platform
 *     thread of its own, {@code plugin-disable-<id>}, that owns no tick thread: it changes an entity
 *     assigned to a tick thread through {@code Acquirable#sync}; the plugins share 5 s, after which
 *     the host takes the capabilities back anyway;</li>
 *     <li>the predicates of {@code Entity#updateViewableRule} and {@code Entity#updateViewerRule}
 *     run on the thread that changes the visibility, holding the view locks of both entities, and
 *     call no visibility method of any entity (see those methods).</li>
 * </ul>
 * Exceptions, player events that are not dispatched on the player's tick thread, or only for some
 * packets; state their listeners share with code on the tick threads needs a concurrent structure
 * or a lock:
 * <ul>
 *     <li>{@code PlayerPacketOutEvent}, once per packet written, on the write thread of the
 *     connection: a listener only looks at or cancels the packet, touches no entity and no
 *     instance, and does not block, which would hold up every packet of that player;</li>
 *     <li>{@code PlayerPacketEvent} is only dispatched for the play packets queued for the player,
 *     on the player's tick thread, or in the serial part of the tick on the tick scheduler thread
 *     while the player is in no instance (waiting to join after the configuration, or sent back to
 *     the configuration and not acknowledged yet): the keep alive, ping and cookie answers are
 *     handled on the read thread without it, and a packet that switches the decoding state (the
 *     acknowledgement of a return to the configuration) is never offered to it;</li>
 *     <li>{@code AsyncPlayerPreLoginEvent} and {@code AsyncPlayerConfigurationEvent} run on a
 *     virtual thread of the connection, where a listener may block;</li>
 *     <li>{@code PlayerSkinInitEvent} and the {@code PlayerSpawnEvent} of a first spawn run in the
 *     serial part of the tick on the tick scheduler thread, before the player is assigned to a tick
 *     thread: a listener may change the player but must not block, the whole tick waits for it;</li>
 *     <li>{@code PlayerDisconnectEvent} runs on the tick scheduler thread for a player that reached
 *     the play state without spawning, and on whichever thread drops the connection for one that
 *     leaves before the play state.</li>
 * </ul>
 */
@NotNullByDefault
package io.github.bearisbug.synaxis.plugin;

import org.jetbrains.annotations.NotNullByDefault;
