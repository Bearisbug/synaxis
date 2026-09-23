package io.github.bearisbug.synaxis.network.packet;

import io.github.bearisbug.synaxis.network.packet.client.common.ClientCookieResponsePacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientCustomClickActionPacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientKeepAlivePacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientPingRequestPacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientPluginMessagePacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientPongPacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientResourcePackStatusPacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientSettingsPacket;
import io.github.bearisbug.synaxis.network.packet.client.configuration.ClientAcceptCodeOfConductPacket;
import io.github.bearisbug.synaxis.network.packet.client.configuration.ClientFinishConfigurationPacket;
import io.github.bearisbug.synaxis.network.packet.client.configuration.ClientSelectKnownPacksPacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientEncryptionResponsePacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginAcknowledgedPacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginPluginResponsePacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginStartPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientAdvancementTabPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientAnimationPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientAttackPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientChangeDifficultyPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientChangeGameModePacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientChatAckPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientChatMessagePacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientChatSessionUpdatePacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientChunkBatchReceivedPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientClickWindowButtonPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientClickWindowPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientCloseWindowPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientCommandChatPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientConfigurationAckPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientCreativeInventoryActionPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientDebugSubscriptionRequestPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientEditBookPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientEntityActionPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientGenerateStructurePacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientHeldItemChangePacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientInputPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientInteractEntityPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientLockDifficultyPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientNameItemPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPickItemFromBlockPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPickItemFromEntityPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlaceRecipePacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerAbilitiesPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerActionPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerBlockPlacementPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerLoadedPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerPositionAndRotationPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerPositionPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerPositionStatusPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerRotationPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientQueryBlockNbtPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientQueryEntityNbtPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientRecipeBookSeenRecipePacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientSelectBundleItemPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientSelectTradePacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientSetBeaconEffectPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientSetGameRulesPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientSetRecipeBookStatePacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientSetTestBlockPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientSignedCommandChatPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientSpectatorActionPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientStatusPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientSteerBoatPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientTabCompletePacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientTeleportConfirmPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientTeleportToEntityPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientTestInstanceBlockActionPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientTickEndPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientUpdateCommandBlockMinecartPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientUpdateCommandBlockPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientUpdateJigsawBlockPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientUpdateSignPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientUpdateStructureBlockPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientUseItemPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientVehicleMovePacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientWindowSlotStatePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.ClearDialogPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.CookieRequestPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.CookieStorePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.CustomReportDetailsPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.DisconnectPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.KeepAlivePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.PingPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.PingResponsePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.PluginMessagePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.ResourcePackPopPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.ResourcePackPushPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.ServerLinksPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.ShowDialogPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.TagsPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.TransferPacket;
import io.github.bearisbug.synaxis.network.packet.server.configuration.CodeOfConductPacket;
import io.github.bearisbug.synaxis.network.packet.server.configuration.FinishConfigurationPacket;
import io.github.bearisbug.synaxis.network.packet.server.configuration.RegistryDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.configuration.ResetChatPacket;
import io.github.bearisbug.synaxis.network.packet.server.configuration.SelectKnownPacksPacket;
import io.github.bearisbug.synaxis.network.packet.server.configuration.UpdateEnabledFeaturesPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginDisconnectPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginPluginRequestPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginSuccessPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.SetCompressionPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.AcknowledgeBlockChangePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ActionBarPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.AttachEntityPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.BlockActionPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.BlockBreakAnimationPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.BlockChangePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.BlockEntityDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.BossBarPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.BundlePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.CameraPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChangeGameStatePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkBatchFinishedPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkBatchStartPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkBiomesPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ClearTitlesPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.CloseWindowPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.CollectItemPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.CustomChatCompletionPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.DamageEventPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.DeathCombatEventPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.DeclareCommandsPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.DeleteChatPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.DestroyEntitiesPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.DisguisedChatPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.EndCombatEventPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.EnterCombatEventPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityAnimationPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityAttributesPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityEffectPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityEquipmentPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityHeadLookPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityMetaDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityPositionAndRotationPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityPositionPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityPositionSyncPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityRotationPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.EntitySoundEffectPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityStatusPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityTeleportPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityVelocityPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ExplosionPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.FacePlayerPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.GameRuleValuesPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.GameTestHighlightPosPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.HeldItemChangePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.HitAnimationPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.InitializeWorldBorderPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.JoinGamePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.LowDiskSpaceWarningPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.MapDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.MoveMinecartPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.MultiBlockChangePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.NbtQueryResponsePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.OpenBookPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.OpenHorseWindowPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.OpenSignEditorPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.OpenWindowPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ParticlePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.PlayerAbilitiesPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.PlayerChatMessagePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.PlayerInfoRemovePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.PlayerInfoUpdatePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.PlayerListHeaderAndFooterPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.PlayerPositionAndLookPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.PlayerRotationPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ProjectilePowerPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.RemoveEntityEffectPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.RespawnPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ServerDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ServerDifficultyPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SetCooldownPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SetCursorItemPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SetExperiencePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SetPassengersPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SetPlayerInventorySlotPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SetSlotPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SetTickStatePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SetTimePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SetTitleSubTitlePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SetTitleTextPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SetTitleTimePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SoundEffectPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SpawnEntityPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SpawnPositionPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.StartConfigurationPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.StopSoundPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SystemChatPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.TabCompletePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.TestInstanceBlockStatus;
import io.github.bearisbug.synaxis.network.packet.server.play.TickStepPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.TrackedWaypointPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.TradeListPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.UnloadChunkPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.UpdateHealthPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.UpdateLightPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.UpdateSimulationDistancePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.UpdateViewDistancePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.UpdateViewPositionPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.VehicleMovePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.WindowItemsPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.WindowPropertyPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.WorldBorderCenterPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.WorldBorderLerpSizePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.WorldBorderSizePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.WorldBorderWarningDelayPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.WorldBorderWarningReachPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.WorldEventPacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Every packet id of the login, configuration and play states, both directions, pinned to the
 * vanilla 26.2 table it has to match ({@code docs/DESIGN.md} REQ-005, {@code docs/TEST.md} TC-036).
 * <p>
 * A packet id is the packet's position in its registry, so removing a packet renumbers every packet
 * after it. The other tests, the probes and the load harness all read their ids back from this
 * repository's own registries and cannot notice that; this one compares against constants taken
 * from the vanilla client instead. Where the engine lacks a vanilla packet a {@link ReservedPacket}
 * has to hold the slot, and such an id must not resolve to anything.
 * <p>
 * The tables were read off the 26.2 client jar with {@code javap -c -p}: the order of the
 * {@code addPacket} / {@code withBundlePacket} calls in the builder lambdas of
 * {@code net.minecraft.network.protocol.login.LoginProtocols},
 * {@code net.minecraft.network.protocol.configuration.ConfigurationProtocols} and
 * {@code net.minecraft.network.protocol.game.GameProtocols} gives the ids, and the string each
 * {@code *PacketTypes} static initialiser hands to {@code createClientbound} /
 * {@code createServerbound} gives the names. The test itself does not need the jar. A Minecraft
 * version bump means reading them again.
 */
class PacketVanillaIdTest {

    private static final Class<?> RESERVED = ReservedPacket.class;

    private record Row(int id, String name, Class<?> packetClass) {
    }

    private static Row row(int id, String name, Class<?> packetClass) {
        return new Row(id, name, packetClass);
    }

    /** {@code LoginProtocols.CLIENTBOUND_TEMPLATE}. */
    private static final List<Row> CLIENTBOUND_LOGIN = List.of(
            row(0, "minecraft:login_disconnect", LoginDisconnectPacket.class),
            row(1, "minecraft:hello", RESERVED),
            row(2, "minecraft:login_finished", LoginSuccessPacket.class),
            row(3, "minecraft:login_compression", SetCompressionPacket.class),
            row(4, "minecraft:custom_query", LoginPluginRequestPacket.class),
            row(5, "minecraft:cookie_request", CookieRequestPacket.class)
    );

    /** {@code LoginProtocols.SERVERBOUND_TEMPLATE}. */
    private static final List<Row> SERVERBOUND_LOGIN = List.of(
            row(0, "minecraft:hello", ClientLoginStartPacket.class),
            row(1, "minecraft:key", ClientEncryptionResponsePacket.class),
            row(2, "minecraft:custom_query_answer", ClientLoginPluginResponsePacket.class),
            row(3, "minecraft:login_acknowledged", ClientLoginAcknowledgedPacket.class),
            row(4, "minecraft:cookie_response", ClientCookieResponsePacket.class)
    );

    /** {@code ConfigurationProtocols.CLIENTBOUND_TEMPLATE}. */
    private static final List<Row> CLIENTBOUND_CONFIGURATION = List.of(
            row(0, "minecraft:cookie_request", CookieRequestPacket.class),
            row(1, "minecraft:custom_payload", PluginMessagePacket.class),
            row(2, "minecraft:disconnect", DisconnectPacket.class),
            row(3, "minecraft:finish_configuration", FinishConfigurationPacket.class),
            row(4, "minecraft:keep_alive", KeepAlivePacket.class),
            row(5, "minecraft:ping", PingPacket.class),
            row(6, "minecraft:reset_chat", ResetChatPacket.class),
            row(7, "minecraft:registry_data", RegistryDataPacket.class),
            row(8, "minecraft:resource_pack_pop", ResourcePackPopPacket.class),
            row(9, "minecraft:resource_pack_push", ResourcePackPushPacket.class),
            row(10, "minecraft:store_cookie", CookieStorePacket.class),
            row(11, "minecraft:transfer", TransferPacket.class),
            row(12, "minecraft:update_enabled_features", UpdateEnabledFeaturesPacket.class),
            row(13, "minecraft:update_tags", TagsPacket.class),
            row(14, "minecraft:select_known_packs", SelectKnownPacksPacket.class),
            row(15, "minecraft:custom_report_details", CustomReportDetailsPacket.class),
            row(16, "minecraft:server_links", ServerLinksPacket.class),
            row(17, "minecraft:clear_dialog", ClearDialogPacket.class),
            row(18, "minecraft:show_dialog", ShowDialogPacket.class),
            row(19, "minecraft:code_of_conduct", CodeOfConductPacket.class)
    );

    /** {@code ConfigurationProtocols.SERVERBOUND_TEMPLATE}. */
    private static final List<Row> SERVERBOUND_CONFIGURATION = List.of(
            row(0, "minecraft:client_information", ClientSettingsPacket.class),
            row(1, "minecraft:cookie_response", ClientCookieResponsePacket.class),
            row(2, "minecraft:custom_payload", ClientPluginMessagePacket.class),
            row(3, "minecraft:finish_configuration", ClientFinishConfigurationPacket.class),
            row(4, "minecraft:keep_alive", ClientKeepAlivePacket.class),
            row(5, "minecraft:pong", ClientPongPacket.class),
            row(6, "minecraft:resource_pack", ClientResourcePackStatusPacket.class),
            row(7, "minecraft:select_known_packs", ClientSelectKnownPacksPacket.class),
            row(8, "minecraft:custom_click_action", ClientCustomClickActionPacket.class),
            row(9, "minecraft:accept_code_of_conduct", ClientAcceptCodeOfConductPacket.class)
    );

    /** {@code GameProtocols.CLIENTBOUND_TEMPLATE}; id 0 is the delimiter {@code withBundlePacket} adds. */
    private static final List<Row> CLIENTBOUND_PLAY = List.of(
            row(0, "minecraft:bundle_delimiter", BundlePacket.class),
            row(1, "minecraft:add_entity", SpawnEntityPacket.class),
            row(2, "minecraft:animate", EntityAnimationPacket.class),
            row(3, "minecraft:award_stats", RESERVED),
            row(4, "minecraft:block_changed_ack", AcknowledgeBlockChangePacket.class),
            row(5, "minecraft:block_destruction", BlockBreakAnimationPacket.class),
            row(6, "minecraft:block_entity_data", BlockEntityDataPacket.class),
            row(7, "minecraft:block_event", BlockActionPacket.class),
            row(8, "minecraft:block_update", BlockChangePacket.class),
            row(9, "minecraft:boss_event", BossBarPacket.class),
            row(10, "minecraft:change_difficulty", ServerDifficultyPacket.class),
            row(11, "minecraft:chunk_batch_finished", ChunkBatchFinishedPacket.class),
            row(12, "minecraft:chunk_batch_start", ChunkBatchStartPacket.class),
            row(13, "minecraft:chunks_biomes", ChunkBiomesPacket.class),
            row(14, "minecraft:clear_titles", ClearTitlesPacket.class),
            row(15, "minecraft:command_suggestions", TabCompletePacket.class),
            row(16, "minecraft:commands", DeclareCommandsPacket.class),
            row(17, "minecraft:container_close", CloseWindowPacket.class),
            row(18, "minecraft:container_set_content", WindowItemsPacket.class),
            row(19, "minecraft:container_set_data", WindowPropertyPacket.class),
            row(20, "minecraft:container_set_slot", SetSlotPacket.class),
            row(21, "minecraft:cookie_request", CookieRequestPacket.class),
            row(22, "minecraft:cooldown", SetCooldownPacket.class),
            row(23, "minecraft:custom_chat_completions", CustomChatCompletionPacket.class),
            row(24, "minecraft:custom_payload", PluginMessagePacket.class),
            row(25, "minecraft:damage_event", DamageEventPacket.class),
            row(26, "minecraft:debug/block_value", RESERVED),
            row(27, "minecraft:debug/chunk_value", RESERVED),
            row(28, "minecraft:debug/entity_value", RESERVED),
            row(29, "minecraft:debug/event", RESERVED),
            row(30, "minecraft:debug_sample", RESERVED),
            row(31, "minecraft:delete_chat", DeleteChatPacket.class),
            row(32, "minecraft:disconnect", DisconnectPacket.class),
            row(33, "minecraft:disguised_chat", DisguisedChatPacket.class),
            row(34, "minecraft:entity_event", EntityStatusPacket.class),
            row(35, "minecraft:entity_position_sync", EntityPositionSyncPacket.class),
            row(36, "minecraft:explode", ExplosionPacket.class),
            row(37, "minecraft:forget_level_chunk", UnloadChunkPacket.class),
            row(38, "minecraft:game_event", ChangeGameStatePacket.class),
            row(39, "minecraft:game_rule_values", GameRuleValuesPacket.class),
            row(40, "minecraft:game_test_highlight_pos", GameTestHighlightPosPacket.class),
            row(41, "minecraft:mount_screen_open", OpenHorseWindowPacket.class),
            row(42, "minecraft:hurt_animation", HitAnimationPacket.class),
            row(43, "minecraft:initialize_border", InitializeWorldBorderPacket.class),
            row(44, "minecraft:keep_alive", KeepAlivePacket.class),
            row(45, "minecraft:level_chunk_with_light", ChunkDataPacket.class),
            row(46, "minecraft:level_event", WorldEventPacket.class),
            row(47, "minecraft:level_particles", ParticlePacket.class),
            row(48, "minecraft:light_update", UpdateLightPacket.class),
            row(49, "minecraft:login", JoinGamePacket.class),
            row(50, "minecraft:low_disk_space_warning", LowDiskSpaceWarningPacket.class),
            row(51, "minecraft:map_item_data", MapDataPacket.class),
            row(52, "minecraft:merchant_offers", TradeListPacket.class),
            row(53, "minecraft:move_entity_pos", EntityPositionPacket.class),
            row(54, "minecraft:move_entity_pos_rot", EntityPositionAndRotationPacket.class),
            row(55, "minecraft:move_minecart_along_track", MoveMinecartPacket.class),
            row(56, "minecraft:move_entity_rot", EntityRotationPacket.class),
            row(57, "minecraft:move_vehicle", VehicleMovePacket.class),
            row(58, "minecraft:open_book", OpenBookPacket.class),
            row(59, "minecraft:open_screen", OpenWindowPacket.class),
            row(60, "minecraft:open_sign_editor", OpenSignEditorPacket.class),
            row(61, "minecraft:ping", PingPacket.class),
            row(62, "minecraft:pong_response", PingResponsePacket.class),
            row(63, "minecraft:place_ghost_recipe", RESERVED),
            row(64, "minecraft:player_abilities", PlayerAbilitiesPacket.class),
            row(65, "minecraft:player_chat", PlayerChatMessagePacket.class),
            row(66, "minecraft:player_combat_end", EndCombatEventPacket.class),
            row(67, "minecraft:player_combat_enter", EnterCombatEventPacket.class),
            row(68, "minecraft:player_combat_kill", DeathCombatEventPacket.class),
            row(69, "minecraft:player_info_remove", PlayerInfoRemovePacket.class),
            row(70, "minecraft:player_info_update", PlayerInfoUpdatePacket.class),
            row(71, "minecraft:player_look_at", FacePlayerPacket.class),
            row(72, "minecraft:player_position", PlayerPositionAndLookPacket.class),
            row(73, "minecraft:player_rotation", PlayerRotationPacket.class),
            row(74, "minecraft:recipe_book_add", RESERVED),
            row(75, "minecraft:recipe_book_remove", RESERVED),
            row(76, "minecraft:recipe_book_settings", RESERVED),
            row(77, "minecraft:remove_entities", DestroyEntitiesPacket.class),
            row(78, "minecraft:remove_mob_effect", RemoveEntityEffectPacket.class),
            row(79, "minecraft:reset_score", RESERVED),
            row(80, "minecraft:resource_pack_pop", ResourcePackPopPacket.class),
            row(81, "minecraft:resource_pack_push", ResourcePackPushPacket.class),
            row(82, "minecraft:respawn", RespawnPacket.class),
            row(83, "minecraft:rotate_head", EntityHeadLookPacket.class),
            row(84, "minecraft:section_blocks_update", MultiBlockChangePacket.class),
            row(85, "minecraft:select_advancements_tab", RESERVED),
            row(86, "minecraft:server_data", ServerDataPacket.class),
            row(87, "minecraft:set_action_bar_text", ActionBarPacket.class),
            row(88, "minecraft:set_border_center", WorldBorderCenterPacket.class),
            row(89, "minecraft:set_border_lerp_size", WorldBorderLerpSizePacket.class),
            row(90, "minecraft:set_border_size", WorldBorderSizePacket.class),
            row(91, "minecraft:set_border_warning_delay", WorldBorderWarningDelayPacket.class),
            row(92, "minecraft:set_border_warning_distance", WorldBorderWarningReachPacket.class),
            row(93, "minecraft:set_camera", CameraPacket.class),
            row(94, "minecraft:set_chunk_cache_center", UpdateViewPositionPacket.class),
            row(95, "minecraft:set_chunk_cache_radius", UpdateViewDistancePacket.class),
            row(96, "minecraft:set_cursor_item", SetCursorItemPacket.class),
            row(97, "minecraft:set_default_spawn_position", SpawnPositionPacket.class),
            row(98, "minecraft:set_display_objective", RESERVED),
            row(99, "minecraft:set_entity_data", EntityMetaDataPacket.class),
            row(100, "minecraft:set_entity_link", AttachEntityPacket.class),
            row(101, "minecraft:set_entity_motion", EntityVelocityPacket.class),
            row(102, "minecraft:set_equipment", EntityEquipmentPacket.class),
            row(103, "minecraft:set_experience", SetExperiencePacket.class),
            row(104, "minecraft:set_health", UpdateHealthPacket.class),
            row(105, "minecraft:set_held_slot", HeldItemChangePacket.class),
            row(106, "minecraft:set_objective", RESERVED),
            row(107, "minecraft:set_passengers", SetPassengersPacket.class),
            row(108, "minecraft:set_player_inventory", SetPlayerInventorySlotPacket.class),
            row(109, "minecraft:set_player_team", RESERVED),
            row(110, "minecraft:set_score", RESERVED),
            row(111, "minecraft:set_simulation_distance", UpdateSimulationDistancePacket.class),
            row(112, "minecraft:set_subtitle_text", SetTitleSubTitlePacket.class),
            row(113, "minecraft:set_time", SetTimePacket.class),
            row(114, "minecraft:set_title_text", SetTitleTextPacket.class),
            row(115, "minecraft:set_titles_animation", SetTitleTimePacket.class),
            row(116, "minecraft:sound_entity", EntitySoundEffectPacket.class),
            row(117, "minecraft:sound", SoundEffectPacket.class),
            row(118, "minecraft:start_configuration", StartConfigurationPacket.class),
            row(119, "minecraft:stop_sound", StopSoundPacket.class),
            row(120, "minecraft:store_cookie", CookieStorePacket.class),
            row(121, "minecraft:system_chat", SystemChatPacket.class),
            row(122, "minecraft:tab_list", PlayerListHeaderAndFooterPacket.class),
            row(123, "minecraft:tag_query", NbtQueryResponsePacket.class),
            row(124, "minecraft:take_item_entity", CollectItemPacket.class),
            row(125, "minecraft:teleport_entity", EntityTeleportPacket.class),
            row(126, "minecraft:test_instance_block_status", TestInstanceBlockStatus.class),
            row(127, "minecraft:ticking_state", SetTickStatePacket.class),
            row(128, "minecraft:ticking_step", TickStepPacket.class),
            row(129, "minecraft:transfer", TransferPacket.class),
            row(130, "minecraft:update_advancements", RESERVED),
            row(131, "minecraft:update_attributes", EntityAttributesPacket.class),
            row(132, "minecraft:update_mob_effect", EntityEffectPacket.class),
            row(133, "minecraft:update_recipes", RESERVED),
            row(134, "minecraft:update_tags", TagsPacket.class),
            row(135, "minecraft:projectile_power", ProjectilePowerPacket.class),
            row(136, "minecraft:custom_report_details", CustomReportDetailsPacket.class),
            row(137, "minecraft:server_links", ServerLinksPacket.class),
            row(138, "minecraft:waypoint", TrackedWaypointPacket.class),
            row(139, "minecraft:clear_dialog", ClearDialogPacket.class),
            row(140, "minecraft:show_dialog", ShowDialogPacket.class)
    );

    /** {@code GameProtocols.SERVERBOUND_TEMPLATE}. */
    private static final List<Row> SERVERBOUND_PLAY = List.of(
            row(0, "minecraft:accept_teleportation", ClientTeleportConfirmPacket.class),
            row(1, "minecraft:attack", ClientAttackPacket.class),
            row(2, "minecraft:block_entity_tag_query", ClientQueryBlockNbtPacket.class),
            row(3, "minecraft:bundle_item_selected", ClientSelectBundleItemPacket.class),
            row(4, "minecraft:change_difficulty", ClientChangeDifficultyPacket.class),
            row(5, "minecraft:change_game_mode", ClientChangeGameModePacket.class),
            row(6, "minecraft:chat_ack", ClientChatAckPacket.class),
            row(7, "minecraft:chat_command", ClientCommandChatPacket.class),
            row(8, "minecraft:chat_command_signed", ClientSignedCommandChatPacket.class),
            row(9, "minecraft:chat", ClientChatMessagePacket.class),
            row(10, "minecraft:chat_session_update", ClientChatSessionUpdatePacket.class),
            row(11, "minecraft:chunk_batch_received", ClientChunkBatchReceivedPacket.class),
            row(12, "minecraft:client_command", ClientStatusPacket.class),
            row(13, "minecraft:client_tick_end", ClientTickEndPacket.class),
            row(14, "minecraft:client_information", ClientSettingsPacket.class),
            row(15, "minecraft:command_suggestion", ClientTabCompletePacket.class),
            row(16, "minecraft:configuration_acknowledged", ClientConfigurationAckPacket.class),
            row(17, "minecraft:container_button_click", ClientClickWindowButtonPacket.class),
            row(18, "minecraft:container_click", ClientClickWindowPacket.class),
            row(19, "minecraft:container_close", ClientCloseWindowPacket.class),
            row(20, "minecraft:container_slot_state_changed", ClientWindowSlotStatePacket.class),
            row(21, "minecraft:cookie_response", ClientCookieResponsePacket.class),
            row(22, "minecraft:custom_payload", ClientPluginMessagePacket.class),
            row(23, "minecraft:debug_subscription_request", ClientDebugSubscriptionRequestPacket.class),
            row(24, "minecraft:edit_book", ClientEditBookPacket.class),
            row(25, "minecraft:entity_tag_query", ClientQueryEntityNbtPacket.class),
            row(26, "minecraft:interact", ClientInteractEntityPacket.class),
            row(27, "minecraft:jigsaw_generate", ClientGenerateStructurePacket.class),
            row(28, "minecraft:keep_alive", ClientKeepAlivePacket.class),
            row(29, "minecraft:lock_difficulty", ClientLockDifficultyPacket.class),
            row(30, "minecraft:move_player_pos", ClientPlayerPositionPacket.class),
            row(31, "minecraft:move_player_pos_rot", ClientPlayerPositionAndRotationPacket.class),
            row(32, "minecraft:move_player_rot", ClientPlayerRotationPacket.class),
            row(33, "minecraft:move_player_status_only", ClientPlayerPositionStatusPacket.class),
            row(34, "minecraft:move_vehicle", ClientVehicleMovePacket.class),
            row(35, "minecraft:paddle_boat", ClientSteerBoatPacket.class),
            row(36, "minecraft:pick_item_from_block", ClientPickItemFromBlockPacket.class),
            row(37, "minecraft:pick_item_from_entity", ClientPickItemFromEntityPacket.class),
            row(38, "minecraft:ping_request", ClientPingRequestPacket.class),
            row(39, "minecraft:place_recipe", ClientPlaceRecipePacket.class),
            row(40, "minecraft:player_abilities", ClientPlayerAbilitiesPacket.class),
            row(41, "minecraft:player_action", ClientPlayerActionPacket.class),
            row(42, "minecraft:player_command", ClientEntityActionPacket.class),
            row(43, "minecraft:player_input", ClientInputPacket.class),
            row(44, "minecraft:player_loaded", ClientPlayerLoadedPacket.class),
            row(45, "minecraft:pong", ClientPongPacket.class),
            row(46, "minecraft:recipe_book_change_settings", ClientSetRecipeBookStatePacket.class),
            row(47, "minecraft:recipe_book_seen_recipe", ClientRecipeBookSeenRecipePacket.class),
            row(48, "minecraft:rename_item", ClientNameItemPacket.class),
            row(49, "minecraft:resource_pack", ClientResourcePackStatusPacket.class),
            row(50, "minecraft:seen_advancements", ClientAdvancementTabPacket.class),
            row(51, "minecraft:select_trade", ClientSelectTradePacket.class),
            row(52, "minecraft:set_beacon", ClientSetBeaconEffectPacket.class),
            row(53, "minecraft:set_carried_item", ClientHeldItemChangePacket.class),
            row(54, "minecraft:set_command_block", ClientUpdateCommandBlockPacket.class),
            row(55, "minecraft:set_command_minecart", ClientUpdateCommandBlockMinecartPacket.class),
            row(56, "minecraft:set_creative_mode_slot", ClientCreativeInventoryActionPacket.class),
            row(57, "minecraft:set_game_rule", ClientSetGameRulesPacket.class),
            row(58, "minecraft:set_jigsaw_block", ClientUpdateJigsawBlockPacket.class),
            row(59, "minecraft:set_structure_block", ClientUpdateStructureBlockPacket.class),
            row(60, "minecraft:set_test_block", ClientSetTestBlockPacket.class),
            row(61, "minecraft:sign_update", ClientUpdateSignPacket.class),
            row(62, "minecraft:spectator_action", ClientSpectatorActionPacket.class),
            row(63, "minecraft:swing", ClientAnimationPacket.class),
            row(64, "minecraft:teleport_to_entity", ClientTeleportToEntityPacket.class),
            row(65, "minecraft:test_instance_block_action", ClientTestInstanceBlockActionPacket.class),
            row(66, "minecraft:use_item_on", ClientPlayerBlockPlacementPacket.class),
            row(67, "minecraft:use_item", ClientUseItemPacket.class),
            row(68, "minecraft:custom_click_action", ClientCustomClickActionPacket.class)
    );

    @Test
    void theClientboundLoginIdsAreTheVanillaOnes() {
        assertVanilla(PacketVanilla.SERVER_PACKET_PARSER.login(), CLIENTBOUND_LOGIN);
    }

    @Test
    void theClientboundConfigurationIdsAreTheVanillaOnes() {
        assertVanilla(PacketVanilla.SERVER_PACKET_PARSER.configuration(), CLIENTBOUND_CONFIGURATION);
    }

    @Test
    void theClientboundPlayIdsAreTheVanillaOnes() {
        assertVanilla(PacketVanilla.SERVER_PACKET_PARSER.play(), CLIENTBOUND_PLAY);
    }

    @Test
    void theServerboundLoginIdsAreTheVanillaOnes() {
        assertVanilla(PacketVanilla.CLIENT_PACKET_PARSER.login(), SERVERBOUND_LOGIN);
    }

    @Test
    void theServerboundConfigurationIdsAreTheVanillaOnes() {
        assertVanilla(PacketVanilla.CLIENT_PACKET_PARSER.configuration(), SERVERBOUND_CONFIGURATION);
    }

    @Test
    void theServerboundPlayIdsAreTheVanillaOnes() {
        assertVanilla(PacketVanilla.CLIENT_PACKET_PARSER.play(), SERVERBOUND_PLAY);
    }

    private static void assertVanilla(PacketRegistry<?> registry, List<Row> vanilla) {
        final List<Executable> checks = new ArrayList<>();
        int present = 0;
        for (int i = 0; i < vanilla.size(); i++) {
            final Row row = vanilla.get(i);
            final int position = i;
            final String where = registry.side() + "_" + registry.state() + " id " + row.id() + " " + row.name();
            checks.add(() -> assertEquals(position, row.id(), "the table itself skips or repeats an id at " + where));
            if (row.packetClass() == RESERVED) {
                checks.add(() -> assertThrows(IllegalStateException.class, () -> registry.packetInfo(row.id()),
                        where + " is a packet the engine does not have, its slot must stay reserved"));
            } else {
                present++;
                checks.add(() -> assertEquals(row.id(), registry.packetInfo(row.packetClass()).id(),
                        where + " is not where " + row.packetClass().getSimpleName() + " sits"));
                checks.add(() -> assertSame(row.packetClass(), registry.packetInfo(row.id()).packetClass(),
                        where + " resolves to a different packet"));
            }
        }
        checks.add(() -> assertThrows(IllegalStateException.class, () -> registry.packetInfo(vanilla.size()),
                registry.side() + "_" + registry.state() + " has ids past the " + vanilla.size() + " of vanilla"));
        final int expected = present;
        checks.add(() -> {
            int registered = 0;
            for (var _ : registry) registered++;
            assertEquals(expected, registered, "the registry holds packets the vanilla table has no slot for");
        });
        assertAll(checks);
    }
}
