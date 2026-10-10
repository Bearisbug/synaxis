package io.github.bearisbug.synaxis.game.client.gametest;

import io.github.bearisbug.synaxis.game.client.ClientHandshake;
import io.github.bearisbug.synaxis.game.client.RtsClient;
import io.github.bearisbug.synaxis.game.client.UnitBindings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * The playable items of {@code docs/DESIGN-PROD.md} §19 ({@code REQ-PROD-046}, {@code REQ-PROD-047},
 * the playable script of {@code API-PROD-026}), played by the real client: the ten steps G1 to G10
 * on the flat core world SYNAXIS_PLAYABLE_SERVER names, then G1, G2, G6, G7 and G10 on the RTS
 * server of the regression, SYNAXIS_GAMETEST_SERVER.
 * <p>
 * Every step is one line {@code <mode> G<n> PASS|FAIL|SKIP <evidence>} of
 * {@code synaxis-gametest/playable.txt}, written in a {@code finally}; a step that could not run
 * because an earlier one left the client out of the world, or dead, is {@code SKIP}, one the run
 * never reached is {@code FAIL}. The test catches what its steps throw and never fails itself:
 * Fabric's runner stops at the first test that throws, and the file is what CI judges.
 * <p>
 * Both client launches of a CI run play on the same core server, world and in-memory store kept, so
 * every step has to hold for an account that played there before: the steps that change the world
 * do it at fixed places off each other's paths (G3 and G4 beside the end of G2, the G9 shaft
 * north-west of the spawn), and G10 leaves the player where the next launch's G2 starts. G9 needs
 * the core world and the RTS map to have no bedrock: the shaft goes through y = 0 into the void.
 * <p>
 * The client is driven the way a player drives it: {@link TestInput} keys for walking, digging,
 * using, dropping, the hotbar, the inventory and chat; the clicks inside the inventory screen go
 * through {@code MultiPlayerGameMode#handleContainerInput}, which is what a click on a slot calls.
 * Only waits on conditions bound the steps; the fixed waits are the observation windows the design
 * names (an item staying put for 40 ticks, the 40 tick pickup delay, the 12 tick rhythm of hits).
 */
public final class PlayableClientGameTest implements FabricClientGameTest {

    private static final String CORE_VARIABLE = "SYNAXIS_PLAYABLE_SERVER";
    private static final String RTS_VARIABLE = "SYNAXIS_GAMETEST_SERVER";

    /** The lines of the report, in the order they are written. */
    private static final List<String> STEPS = List.of(
            "core G1", "core G2", "core G3", "core G4", "core G5", "core G6", "core G7", "core G8", "core G9", "core G10",
            "rts G1", "rts G2", "rts G6", "rts G7", "rts G10");

    /** server-app's own cap; the gametest harness drops the render distance to 5 chunks. */
    private static final int RENDER_DISTANCE = 10;

    private static final int CONNECT_TIMEOUT_TICKS = 20 * 60;
    private static final int SETTLE_STILL_TICKS = 10;
    private static final int SETTLE_TIMEOUT_TICKS = 200;
    private static final int LEAVE_TIMEOUT_TICKS = 100;
    private static final int SCREEN_TIMEOUT_TICKS = 20;
    private static final int SELECT_TIMEOUT_TICKS = 5;
    private static final int STOP_TIMEOUT_TICKS = 40;
    private static final int WALK_TO_TICKS = 600;

    /** G2: the forward key is held 3 s, which walks about 13 blocks. */
    private static final int WALK_TICKS = 60;
    private static final double MIN_WALK = 3.0;
    private static final int LIGHT_TIMEOUT_TICKS = 100;
    /** G2 on the RTS map: how far ahead a direction has to be free of pillars to be taken. */
    private static final int CLEAR_RUN = 16;

    /** G3: stone takes 150 ticks bare handed, 8 with the iron pickaxe. */
    private static final int DIG_TIMEOUT_TICKS = 300;
    /** G3, G4: how long a dug or placed block has to stay as it is. */
    private static final int HOLD_TICKS = 40;

    private static final int INVENTORY_SETTLE_TICKS = 20;
    private static final int ITEM_TIMEOUT_TICKS = 20;
    private static final int PICKUP_DELAY_TICKS = 40;
    private static final int PICKUP_TIMEOUT_TICKS = 100;
    private static final double ITEM_RANGE = 8.0;

    private static final int MESSAGE_TIMEOUT_TICKS = 40;
    private static final String UNKNOWN_COMMAND = "synaxisnosuchcommand";

    private static final Vec3 DUMMY_HOME = new Vec3(3.5, 40, 0.5);
    private static final double DUMMY_SEARCH = 4.0;
    private static final double DUMMY_RESPAWN_RANGE = 2.0;
    /** G8: the player stands this far west of the dummy, inside the 2.5 the design allows. */
    private static final double MELEE_STANDOFF = 2.0;
    private static final double MELEE_REACH = 2.5;
    private static final int HURT_TIMEOUT_TICKS = 20;
    private static final int REHIT_TICKS = 12;
    private static final int MAX_HITS = 15;
    private static final int DEATH_REMOVAL_TICKS = 40;
    private static final int DUMMY_RESPAWN_TICKS = 140;

    private static final Vec3 CORE_SPAWN = new Vec3(0.5, 40, 0.5);
    private static final double SHAFT_X = -8.5;
    private static final double SHAFT_Z = -8.5;
    private static final double SHAFT_TOLERANCE = 0.2;
    /** Below this the player is inside the shaft: a second launch finds it dug and drops in. */
    private static final double SHAFT_ENTERED_Y = 39.5;
    private static final double VOID_Y = -64.0;
    private static final int FALL_TIMEOUT_TICKS = 1600;
    private static final int DEATH_TIMEOUT_TICKS = 200;
    private static final int RESPAWN_TIMEOUT_TICKS = 100;

    private static final double REJOIN_X = 0.5;
    private static final double REJOIN_Z = -5.5;
    private static final float REJOIN_YAW = 45f;
    private static final int REJOIN_SLOT = 2;
    private static final int REJOIN_TIMEOUT_TICKS = 100;
    private static final double REJOIN_DISTANCE = 0.5;
    private static final float REJOIN_DEGREES = 1f;
    private static final int UNITS_TIMEOUT_TICKS = 20 * 60;
    private static final int UNITS_STILL_TICKS = 20;

    /** A speed below which the player counts as standing, in blocks per tick. */
    private static final double STOPPED = 0.01;
    /** How far the player slides after the forward key is let go, per block per tick of speed. */
    private static final double SLIDE = 1.3;

    /** Every chat and system line the client shows, in order; written on the client thread. */
    private static final List<Component> MESSAGES = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean LISTENING = new AtomicBoolean();
    /** The reason of every disconnect the client handled, in order (DisconnectReasonMixin). */
    private static final List<String> DISCONNECTS = new CopyOnWriteArrayList<>();

    /**
     * Called by {@code DisconnectReasonMixin} for every connection the client loses or leaves.
     *
     * @param reason what the client shows as the reason
     */
    public static void disconnected(Component reason) {
        DISCONNECTS.add(reason.getString());
    }

    private final Report report = new Report();
    private @Nullable ClientGameTestContext context;
    private @Nullable TestInput input;
    private @Nullable Vec3 rtsSpawn;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (TransferRoundTripClientGameTest.enabled()) return;
        final String core = System.getenv(CORE_VARIABLE);
        if (core == null || core.isBlank()) return;
        final String rts = System.getenv(RTS_VARIABLE);
        this.context = context;
        this.input = context.getInput();
        String ended = "the playthrough ended before this step";
        try {
            listen();
            context.waitForScreen(TitleScreen.class);
            context.runOnClient(client -> client.options.renderDistance().set(RENDER_DISTANCE));
            core(core);
            leave();
            if (rts == null || rts.isBlank()) report.failRest("rts", RTS_VARIABLE + " not set");
            else rts(rts);
        } catch (RuntimeException | AssertionError e) {
            ended = "the playthrough threw " + e;
        } finally {
            try {
                leave();
            } catch (RuntimeException | AssertionError e) {
                ended += "; leaving to the title screen threw " + e;
            }
            report.write(ended);
        }
    }

    private void core(String server) {
        if (!joined("core", server)) return;
        attempt("core G2", () -> move("core"));
        attempt("core G3", this::dig);
        attempt("core G4", this::place);
        attempt("core G5", this::inventory);
        attempt("core G6", () -> chat("core"));
        attempt("core G7", () -> commands("core"));
        attempt("core G8", this::melee);
        attempt("core G9", this::death);
        attempt("core G10", () -> rejoinCore(server));
    }

    private void rts(String server) {
        if (!joined("rts", server)) return;
        rtsSpawn = position();
        attempt("rts G2", () -> move("rts"));
        attempt("rts G6", () -> chat("rts"));
        attempt("rts G7", () -> commands("rts"));
        attempt("rts G10", () -> rejoinRts(server));
    }

    /** G1, and SKIP for the rest of the mode when it did not reach the world. */
    private boolean joined(String mode, String server) {
        boolean inWorld = false;
        try {
            inWorld = join(mode, server);
        } catch (RuntimeException | AssertionError e) {
            report.failIfAbsent(mode + " G1", "threw " + e + disconnectsSince(0));
        }
        if (!inWorld) report.skipRest(mode, mode + " G1 did not reach the world");
        return inWorld;
    }

    /**
     * Runs one step; one that throws is FAIL with what it threw, and the run goes on to the next,
     * which is SKIP if the client is no longer in the world. Every key a step holds is let go in a
     * {@code finally} of the step itself.
     */
    private void attempt(String step, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException | AssertionError e) {
            report.failIfAbsent(step, "threw " + e + disconnectsSince(0));
        }
    }

    // ---- G1, G2 ------------------------------------------------------------------------------

    /** G1: in the world within 60 s; on the core world also in survival and alive. */
    private boolean join(String mode, String server) {
        final String step = mode + " G1";
        final int mark = DISCONNECTS.size();
        connect(server, "synaxis-" + mode);
        final boolean inWorld = ctx().computeOnClient(RegressionClientGameTest::inWorld);
        final boolean settled = inWorld && settle();
        final ClientHandshake handshake = RegressionClientGameTest.staticField(ClientHandshake.class, "HANDSHAKE");
        final boolean core = mode.equals("core");
        final boolean pass = inWorld && (!core || ctx().computeOnClient(client ->
                client.gameMode.getPlayerMode() == GameType.SURVIVAL && client.player.getHealth() > 0));
        report.put(step, pass, ctx().computeOnClient(client -> RegressionClientGameTest.inWorld(client)
                ? String.format(Locale.ROOT, "in world at %s mode=%s health=%.1f handshake=%s encrypted=%s settled=%s",
                        xyz(client.player.position()), client.gameMode.getPlayerMode(), client.player.getHealth(),
                        handshake.state(), RegressionClientGameTest.encrypted(client), settled)
                : "not in world: " + RegressionClientGameTest.screenText(client) + disconnectsSince(mark)));
        return inWorld;
    }

    /**
     * G2: the forward key held for 3 s; on the core world facing south, on the RTS map the first of
     * south, east, north and west that is free of pillars for {@link #CLEAR_RUN} blocks.
     */
    private void move(String mode) {
        final String step = mode + " G2";
        if (!ready(step)) return;
        final float yaw = mode.equals("core") ? 0f : clearYaw();
        input().lookAt(yaw, 0f);
        final Vec3 start = position();
        input().holdKeyFor(options -> options.keyUp, WALK_TICKS);
        final boolean stopped = ticksUntil(client -> client.player.onGround() && speed(client) < STOPPED, STOP_TIMEOUT_TICKS) >= 0;
        final double moved = horizontal(start, position());
        final boolean lit = ticksUntil(client -> RegressionClientGameTest.sky(client, client.player.blockPosition().above()) == 15,
                LIGHT_TIMEOUT_TICKS) >= 0;
        final boolean grounded = ctx().computeOnClient(client -> !client.level.getBlockState(client.player.blockPosition().below()).isAir());
        final boolean loaded = ctx().computeOnClient(client -> client.level.hasChunk(
                client.player.blockPosition().getX() >> 4, client.player.blockPosition().getZ() >> 4));
        ctx().takeScreenshot(mode + "-g2-walked");
        report.put(step, moved >= MIN_WALK && grounded && loaded && lit, ctx().computeOnClient(client -> String.format(Locale.ROOT,
                "yaw %.0f from %s to %s moved %.2f blocks (need %.1f) stopped=%s ground_below=%s chunk_loaded=%s sky_above=%d",
                yaw, xyz(start), xyz(client.player.position()), moved, MIN_WALK, stopped,
                client.level.getBlockState(client.player.blockPosition().below()), loaded,
                RegressionClientGameTest.sky(client, client.player.blockPosition().above()))));
    }

    private float clearYaw() {
        return ctx().computeOnClient(client -> {
            final BlockPos feet = client.player.blockPosition();
            float best = 0f;
            int bestRun = -1;
            for (float yaw : new float[] {0f, -90f, 180f, 90f}) {
                final int dx = (int) Math.round(-Math.sin(Math.toRadians(yaw)));
                final int dz = (int) Math.round(Math.cos(Math.toRadians(yaw)));
                int run = 0;
                while (run < CLEAR_RUN) {
                    final BlockPos at = feet.offset(dx * (run + 1), 0, dz * (run + 1));
                    if (!client.level.getBlockState(at).isAir() || !client.level.getBlockState(at.above()).isAir()
                            || client.level.getBlockState(at.below()).isAir()) break;
                    run++;
                }
                if (run > bestRun) {
                    best = yaw;
                    bestRun = run;
                }
                if (run == CLEAR_RUN) break;
            }
            return best;
        });
    }

    // ---- G3, G4 ------------------------------------------------------------------------------

    /** G3: the ground stone east of the block under the player, dug from its top face. */
    private void dig() {
        final String step = "core G3";
        if (!ready(step)) return;
        final int pickaxe = hotbarSlot(stack -> stack.is(Items.IRON_PICKAXE));
        if (pickaxe >= 0) select(pickaxe);
        final String tool = ctx().computeOnClient(client -> client.player.getMainHandItem().toString());
        final BlockPos target = ctx().computeOnClient(client -> client.player.blockPosition().below().east());
        if (ctx().computeOnClient(client -> client.level.getBlockState(target).isAir())) {
            report.put(step, false, "the block to dig at " + target.toShortString() + " is air already");
            return;
        }
        final String aim = aimAtTop(target);
        if (aim != null) {
            report.put(step, false, "target " + target.toShortString() + ": " + aim);
            return;
        }
        final boolean grabbed = ctx().computeOnClient(client -> client.mouseHandler.isMouseGrabbed());
        final int ticks;
        input().holdKey(options -> options.keyAttack);
        try {
            ticks = ticksUntil(client -> client.level.getBlockState(target).isAir(), DIG_TIMEOUT_TICKS);
        } finally {
            input().releaseKey(options -> options.keyAttack);
        }
        final boolean stayed = ticks >= 0 && holds(client -> client.level.getBlockState(target).isAir(), HOLD_TICKS);
        report.put(step, ticks >= 0 && stayed, String.format(Locale.ROOT,
                "target %s with %s mouse_grabbed=%s %s",
                target.toShortString(), tool, grabbed,
                ticks < 0 ? "still " + blockAt(target) + " after " + DIG_TIMEOUT_TICKS + " ticks"
                        : "air after " + ticks + " ticks, " + (stayed ? "stayed air " + HOLD_TICKS + " ticks" : "back to " + blockAt(target))));
    }

    /** G4: a stone placed on the top face of the ground stone west of the block under the player. */
    private void place() {
        final String step = "core G4";
        if (!ready(step)) return;
        final int slot = hotbarSlot(stack -> stack.is(Items.STONE));
        if (slot < 0) {
            report.put(step, false, "no stone in the hotbar: " + inventoryText());
            return;
        }
        select(slot);
        final int before = count(slot);
        final BlockPos target = ctx().computeOnClient(client -> client.player.blockPosition().below().west());
        final BlockPos placed = target.above();
        if (!ctx().computeOnClient(client -> client.level.getBlockState(placed).isAir())) {
            report.put(step, false, "the block to place at " + placed.toShortString() + " is " + blockAt(placed) + " already");
            return;
        }
        final String aim = aimAtTop(target);
        if (aim != null) {
            report.put(step, false, "target " + target.toShortString() + ": " + aim);
            return;
        }
        input().pressKey(options -> options.keyUse);
        final boolean appeared = ticksUntil(client -> client.level.getBlockState(placed).is(Blocks.STONE), HOLD_TICKS) >= 0;
        final boolean stayed = appeared && holds(client -> client.level.getBlockState(placed).is(Blocks.STONE), HOLD_TICKS);
        final boolean spent = ticksUntil(client -> client.player.getInventory().getItem(slot).getCount() == before - 1,
                ITEM_TIMEOUT_TICKS) >= 0;
        report.put(step, appeared && stayed && spent, String.format(Locale.ROOT,
                "%s: %s; hotbar slot %d stone %d -> %d",
                placed.toShortString(), !appeared ? "no stone within " + HOLD_TICKS + " ticks, " + blockAt(placed)
                        : stayed ? "stone, stayed " + HOLD_TICKS + " ticks" : "stone, then " + blockAt(placed),
                slot, before, count(slot)));
    }

    // ---- G5 ----------------------------------------------------------------------------------

    /** One item entity the step saw appear. */
    private record Drop(int id, int count) {
    }

    /**
     * G5: the hotbar, a move inside the inventory, the three ways to drop (Q, a click outside the
     * inventory, closing it with items on the cursor) and picking the three drops up again.
     */
    private void inventory() {
        final String step = "core G5";
        if (!ready(step)) return;
        input().lookAt(0f, 0f);
        final List<String> done = new ArrayList<>();

        // (1) at least 2 stones and the iron pickaxe
        final int stones = total(Items.STONE);
        final int pickaxe = hotbarSlot(stack -> stack.is(Items.IRON_PICKAXE));
        if (stones < 2 || pickaxe < 0) {
            report.put(step, false, "(1) stone=" + stones + " iron_pickaxe_in_hotbar=" + (pickaxe >= 0) + " inventory: " + inventoryText());
            return;
        }
        done.add("(1) stone=" + stones);

        // (2) the pickaxe in the main hand
        if (!select(pickaxe) || !ctx().computeOnClient(client -> client.player.getMainHandItem().is(Items.IRON_PICKAXE))) {
            report.put(step, false, String.join(" ", done) + " (2) main hand " + ctx().computeOnClient(client ->
                    client.player.getMainHandItem().toString() + " selected=" + client.player.getInventory().getSelectedSlot()));
            return;
        }
        done.add("(2) pickaxe slot " + pickaxe);

        // (3) half of a hotbar stack of stones to an empty slot of the main inventory
        final int hotbar = hotbarSlot(stack -> stack.is(Items.STONE) && stack.getCount() >= 2);
        final int empty = emptyMainSlot();
        if (hotbar < 0 || empty < 0) {
            report.put(step, false, String.join(" ", done) + " (3) no hotbar stack of 2 stones or no empty slot: " + inventoryText());
            return;
        }
        final int stacked = count(hotbar);
        if (!openInventory()) {
            report.put(step, false, String.join(" ", done) + " (3) the inventory screen did not open: " + screenText());
            return;
        }
        click(InventoryMenu.USE_ROW_SLOT_START + hotbar, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        click(empty, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        closeInventory();
        ctx().waitTicks(INVENTORY_SETTLE_TICKS);
        final boolean moved = count(hotbar) == stacked / 2 && stoneCount(empty) == stacked - stacked / 2 && carried() == 0;
        final String movedText = String.format(Locale.ROOT, "(3) hotbar %d: %d -> %d (want %d), slot %d: %d (want %d), cursor %d",
                hotbar, stacked, count(hotbar), stacked / 2, empty, stoneCount(empty), stacked - stacked / 2, carried());
        if (!moved) {
            report.put(step, false, String.join(" ", done) + " " + movedText);
            return;
        }
        done.add(movedText);

        // (4) Q on the hotbar stack drops one stone
        select(hotbar);
        final Set<Integer> known = itemEntities();
        final int beforeDrops = total(Items.STONE);
        input().pressKey(options -> options.keyDrop);
        final Drop dropped = awaitDrop(known);
        final boolean spent = ticksUntil(client -> total(client, Items.STONE) == beforeDrops - 1, ITEM_TIMEOUT_TICKS) >= 0;
        if (dropped == null || !spent) {
            report.put(step, false, String.join(" ", done) + " (4) Q: " + (dropped == null ? "no stone item entity within "
                    + ITEM_TIMEOUT_TICKS + " ticks" : "entity " + dropped) + ", stone " + beforeDrops + " -> " + total(Items.STONE));
            return;
        }
        known.add(dropped.id());
        done.add("(4) Q " + dropped);

        // (5) half of the main inventory stack on the cursor, one dropped outside, the rest dropped by closing
        final int half = stoneCount(empty);
        if (!openInventory()) {
            report.put(step, false, String.join(" ", done) + " (5) the inventory screen did not open: " + screenText());
            return;
        }
        click(empty, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        final int cursor = carried();
        click(AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        final Drop outside = awaitDrop(known);
        if (outside != null) known.add(outside.id());
        closeInventory();
        final Drop closed = awaitDrop(known);
        final boolean cleared = ticksUntil(client -> client.player.inventoryMenu.getCarried().isEmpty(), ITEM_TIMEOUT_TICKS) >= 0;
        final String droppedText = String.format(Locale.ROOT, "(5) slot %d had %d, cursor %d, outside %s, closing %s (want %d), cursor after %d",
                empty, half, cursor, outside, closed, cursor - 1, carried());
        if (outside == null || outside.count() != 1 || closed == null || closed.count() != cursor - 1 || !cleared) {
            report.put(step, false, String.join(" ", done) + " " + droppedText);
            return;
        }
        done.add(droppedText);

        // (6) after the pickup delay, south onto the three drops
        ctx().waitTicks(PICKUP_DELAY_TICKS);
        final Set<Integer> drops = Set.of(dropped.id(), outside.id(), closed.id());
        input().lookAt(0f, 0f);
        final int gone;
        input().holdKey(options -> options.keyUp);
        try {
            gone = ticksUntil(client -> drops.stream().allMatch(id -> client.level.getEntity(id) == null), PICKUP_TIMEOUT_TICKS);
        } finally {
            input().releaseKey(options -> options.keyUp);
        }
        final boolean back = ticksUntil(client -> total(client, Items.STONE) == beforeDrops, ITEM_TIMEOUT_TICKS) >= 0;
        report.put(step, gone >= 0 && back, String.join(" ", done) + String.format(Locale.ROOT,
                " (6) %s, stone %d (want %d)", gone >= 0 ? "picked up after " + gone + " ticks" : "drops still there after "
                        + PICKUP_TIMEOUT_TICKS + " ticks", total(Items.STONE), beforeDrops));
    }

    private boolean openInventory() {
        input().pressKey(options -> options.keyInventory);
        return ticksUntil(client -> client.gui.screen() instanceof InventoryScreen, SCREEN_TIMEOUT_TICKS) >= 0;
    }

    private void closeInventory() {
        input().pressKey(options -> options.keyInventory);
        if (ticksUntil(client -> !(client.gui.screen() instanceof InventoryScreen), SCREEN_TIMEOUT_TICKS) < 0) closeScreen();
    }

    /** A click on a slot of the open inventory screen, the way the screen sends it. */
    private void click(int slot, int button) {
        ctx().runOnClient(client -> client.gameMode.handleContainerInput(client.player.containerMenu.containerId, slot, button,
                ContainerInput.PICKUP, client.player));
        ctx().waitTick();
    }

    private Set<Integer> itemEntities() {
        return ctx().computeOnClient(client -> client.level.getEntitiesOfClass(ItemEntity.class,
                client.player.getBoundingBox().inflate(ITEM_RANGE)).stream().map(Entity::getId).collect(Collectors.toCollection(HashSet::new)));
    }

    /** The first stone item entity near the player that is not one of {@code known}, null after the timeout. */
    private @Nullable Drop awaitDrop(Set<Integer> known) {
        final Drop[] found = new Drop[1];
        final int ticks = ticksUntil(client -> {
            for (ItemEntity item : client.level.getEntitiesOfClass(ItemEntity.class, client.player.getBoundingBox().inflate(ITEM_RANGE))) {
                if (!known.contains(item.getId()) && item.getItem().is(Items.STONE)) {
                    found[0] = new Drop(item.getId(), item.getItem().getCount());
                    return true;
                }
            }
            return false;
        }, ITEM_TIMEOUT_TICKS);
        return ticks >= 0 ? found[0] : null;
    }

    // ---- G6, G7 ------------------------------------------------------------------------------

    /** G6: a chat line with a random string comes back. */
    private void chat(String mode) {
        final String step = mode + " G6";
        if (!ready(step)) return;
        final String token = "g6-" + UUID.randomUUID().toString().substring(0, 8);
        final int mark = MESSAGES.size();
        final String typed = type("hello " + token);
        if (typed != null) {
            report.put(step, false, typed);
            return;
        }
        final int ticks = ticksUntil(_ -> since(mark).stream().anyMatch(line -> line.getString().contains(token)), MESSAGE_TIMEOUT_TICKS);
        report.put(step, ticks >= 0, ticks >= 0
                ? "line \"" + since(mark).stream().map(Component::getString).filter(line -> line.contains(token)).findFirst().orElse("")
                        + "\" after " + ticks + " ticks"
                : "no line with " + token + " within " + MESSAGE_TIMEOUT_TICKS + " ticks; lines since: " + linesText(mark));
    }

    /** G7: /list answers with the player's name, an unknown command with its own sentence. */
    private void commands(String mode) {
        final String step = mode + " G7";
        if (!ready(step)) return;
        final String name = ctx().computeOnClient(client -> client.player.getName().getString());
        final String list = command("list", "synaxis:command-list", name);
        final String unknown = command(UNKNOWN_COMMAND, "synaxis:command-unknown", UNKNOWN_COMMAND);
        report.put(step, list.startsWith("ok ") && unknown.startsWith("ok "), "/list " + list + "; /" + UNKNOWN_COMMAND + " " + unknown);
    }

    /**
     * Sends a command and waits for a line that carries {@code key}, is translated by the mod's
     * language file and contains {@code needle}.
     *
     * @return {@code ok <the line>}, or what went wrong
     */
    private String command(String command, String key, String needle) {
        final int mark = MESSAGES.size();
        final String typed = type("/" + command);
        if (typed != null) return typed;
        final Component[] found = new Component[1];
        final int ticks = ticksUntil(_ -> {
            for (Component line : since(mark)) {
                final String text = line.getString();
                if (carries(line, key) && Language.getInstance().has(key) && text.contains(needle) && !text.contains("synaxis:")) {
                    found[0] = line;
                    return true;
                }
            }
            return false;
        }, MESSAGE_TIMEOUT_TICKS);
        return ticks >= 0 ? "ok \"" + found[0].getString() + "\" after " + ticks + " ticks"
                : "no line of " + key + " with " + needle + " within " + MESSAGE_TIMEOUT_TICKS + " ticks (translated="
                        + ctx().computeOnClient(_ -> Language.getInstance().has(key)) + "); lines since: " + linesText(mark);
    }

    /** Types a line into the chat screen and sends it with Enter; null when that went as a player's would. */
    private @Nullable String type(String text) {
        input().pressKey(options -> options.keyChat);
        if (ticksUntil(client -> client.gui.screen() instanceof ChatScreen, SCREEN_TIMEOUT_TICKS) < 0)
            return "the chat screen did not open: " + screenText();
        input().typeChars(text);
        input().pressKey(GLFW.GLFW_KEY_ENTER);
        if (ticksUntil(client -> !(client.gui.screen() instanceof ChatScreen), SCREEN_TIMEOUT_TICKS) < 0) {
            closeScreen();
            return "the chat screen stayed open after Enter";
        }
        return null;
    }

    private static boolean carries(Component component, String key) {
        if (component.getContents() instanceof TranslatableContents translatable) {
            if (translatable.getKey().equals(key)) return true;
            for (Object argument : translatable.getArgs())
                if (argument instanceof Component child && carries(child, key)) return true;
        }
        for (Component sibling : component.getSiblings())
            if (carries(sibling, key)) return true;
        return false;
    }

    private static List<Component> since(int mark) {
        return MESSAGES.subList(Math.min(mark, MESSAGES.size()), MESSAGES.size());
    }

    private static String linesText(int mark) {
        return since(mark).stream().map(line -> "\"" + line.getString() + "\"").toList().toString();
    }

    private static void listen() {
        if (!LISTENING.compareAndSet(false, true)) return;
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) MESSAGES.add(message);
        });
        ClientReceiveMessageEvents.CHAT.register((message, _, _, _, _) -> MESSAGES.add(message));
    }

    // ---- G8 ----------------------------------------------------------------------------------

    /**
     * G8: the training dummy east of the spawn, hit from its west side so that the knockback pushes
     * it away from the spawn and the shaft; a second hit inside the cooldown does nothing, then one
     * hit every 12 ticks until it dies, and a fresh one comes back where it stood.
     */
    private void melee() {
        final String step = "core G8";
        if (!ready(step)) return;
        final Integer dummy = dummy(-1);
        if (dummy == null) {
            report.put(step, false, "no living zombie within " + DUMMY_SEARCH + " blocks of " + xyz(DUMMY_HOME) + "; living entities: "
                    + ctx().computeOnClient(client -> client.level.getEntitiesOfClass(LivingEntity.class,
                            client.player.getBoundingBox().inflate(32), entity -> entity != client.player).stream()
                            .map(entity -> entity.getType().toShortString() + "@" + xyz(entity.position())).toList()));
            return;
        }
        // Back to the spawn along x = 0.5 first: the holes and stones of G3 and G4 lie a block either side of that line
        walkTo(CORE_SPAWN.x, CORE_SPAWN.z, 0.5, WALK_TO_TICKS, null);
        final int pickaxe = hotbarSlot(stack -> stack.is(Items.IRON_PICKAXE));
        if (pickaxe >= 0) select(pickaxe);
        final String tool = ctx().computeOnClient(client -> client.player.getMainHandItem().toString());
        if (!approach(dummy)) {
            report.put(step, false, "could not get within " + MELEE_REACH + " blocks west of the zombie at " + entityAt(dummy)
                    + ", player at " + xyz(position()));
            return;
        }
        final float full = health(dummy);
        final String first = hit(dummy);
        final String second = hit(dummy);
        final int hurt = ticksUntil(client -> health(client, dummy) < full, HURT_TIMEOUT_TICKS);
        final float after = health(dummy);
        final boolean once = hurt >= 0 && holds(client -> health(client, dummy) == after, HURT_TIMEOUT_TICKS);
        final String opening = String.format(Locale.ROOT, "with %s health %.1f -> %.1f (%s), first hit %s, second hit %s, %s",
                tool, full, after, hurt >= 0 ? "after " + hurt + " ticks" : "no drop within " + HURT_TIMEOUT_TICKS + " ticks",
                first == null ? "sent" : first, second == null ? "sent" : second,
                once ? "no second drop" : "health now " + health(dummy));
        if (hurt < 0 || !once) {
            report.put(step, false, opening);
            return;
        }
        int hits = 2;
        while (alive(dummy) && hits < MAX_HITS) {
            ctx().waitTicks(REHIT_TICKS);
            if (!alive(dummy)) break;
            approach(dummy);
            hit(dummy);
            hits++;
        }
        final boolean died = ticksUntil(_ -> !alive(dummy), DEATH_REMOVAL_TICKS) >= 0;
        final Integer[] fresh = new Integer[1];
        final int respawned = died ? ticksUntil(client -> (fresh[0] = freshDummy(client, dummy)) != null, DUMMY_RESPAWN_TICKS) : -1;
        report.put(step, died && respawned >= 0, opening + String.format(Locale.ROOT, "; %d hits, %s, %s",
                hits, died ? "died" : "still alive at " + health(dummy),
                respawned >= 0 ? "a fresh zombie at " + entityAt(fresh[0]) + " after " + respawned + " ticks"
                        : "no fresh zombie within " + DUMMY_RESPAWN_TICKS + " ticks"));
    }

    /** A living zombie near the dummy's place other than {@code except}, or null. */
    private @Nullable Integer dummy(int except) {
        return ctx().computeOnClient(client -> client.level.getEntitiesOfClass(LivingEntity.class,
                        AABB.ofSize(DUMMY_HOME, 2 * DUMMY_SEARCH, 6, 2 * DUMMY_SEARCH),
                        entity -> entity.getType() == EntityTypes.ZOMBIE && entity.isAlive() && entity.getId() != except)
                .stream().map(Entity::getId).findFirst().orElse(null));
    }

    /** A full health zombie standing where the dummy respawns, other than {@code old}, or null. */
    private static @Nullable Integer freshDummy(Minecraft client, int old) {
        for (LivingEntity entity : client.level.getEntitiesOfClass(LivingEntity.class,
                AABB.ofSize(DUMMY_HOME, 2 * DUMMY_RESPAWN_RANGE, 6, 2 * DUMMY_RESPAWN_RANGE))) {
            if (entity.getType() == EntityTypes.ZOMBIE && entity.getId() != old && entity.isAlive()
                    && entity.getHealth() >= entity.getMaxHealth()) return entity.getId();
        }
        return null;
    }

    /** Walks to the west side of the entity, within {@link #MELEE_REACH} of it. */
    private boolean approach(int id) {
        final Vec3 at = ctx().computeOnClient(client -> {
            final Entity entity = client.level.getEntity(id);
            return entity == null ? null : entity.position();
        });
        if (at == null) return false;
        walkTo(at.x - MELEE_STANDOFF, at.z, 0.4, WALK_TO_TICKS, null);
        return ctx().computeOnClient(client -> {
            final Entity entity = client.level.getEntity(id);
            return entity != null && horizontal(client.player.position(), entity.position()) <= MELEE_REACH;
        });
    }

    /** One attack with the crosshair on the entity; null when it went out, else what the crosshair is on. */
    private @Nullable String hit(int id) {
        final Vec3 center = ctx().computeOnClient(client -> {
            final Entity entity = client.level.getEntity(id);
            return entity == null ? null : entity.getBoundingBox().getCenter();
        });
        if (center == null) return "the entity is gone";
        lookAtPoint(center);
        ctx().waitTick();
        final String on = ctx().computeOnClient(client -> client.hitResult instanceof EntityHitResult hit && hit.getEntity().getId() == id
                ? null : "crosshair on " + describe(client.hitResult));
        if (on != null) return on;
        input().pressKey(options -> options.keyAttack);
        return null;
    }

    private boolean alive(int id) {
        return ctx().computeOnClient(client -> client.level.getEntity(id) instanceof LivingEntity entity && entity.isAlive());
    }

    private float health(int id) {
        return ctx().computeOnClient(client -> health(client, id));
    }

    private static float health(Minecraft client, int id) {
        return client.level.getEntity(id) instanceof LivingEntity entity ? entity.getHealth() : -1f;
    }

    private String entityAt(@Nullable Integer id) {
        if (id == null) return "nowhere";
        return ctx().computeOnClient(client -> {
            final Entity entity = client.level.getEntity(id);
            return entity instanceof LivingEntity living
                    ? xyz(entity.position()) + String.format(Locale.ROOT, " health %.1f/%.1f", living.getHealth(), living.getMaxHealth())
                    : "gone";
        });
    }

    // ---- G9, G10 -----------------------------------------------------------------------------

    /**
     * G9: down the shaft north-west of the spawn into the void, the death screen, respawn. The first
     * launch digs the shaft through the 40 layers of stone (there is no bedrock); the next one finds
     * it open and drops in on the way to its middle.
     */
    private void death() {
        final String step = "core G9";
        if (!ready(step)) return;
        final int pickaxe = hotbarSlot(stack -> stack.is(Items.IRON_PICKAXE));
        if (pickaxe < 0) {
            report.put(step, false, "no iron pickaxe in the hotbar to dig the shaft with: " + inventoryText());
            return;
        }
        final boolean reached = walkTo(SHAFT_X, SHAFT_Z, SHAFT_TOLERANCE, WALK_TO_TICKS, client -> client.player.getY() < SHAFT_ENTERED_Y);
        if (!reached) {
            report.put(step, false, "could not reach " + SHAFT_X + "," + SHAFT_Z + ": at " + xyz(position()));
            return;
        }
        select(pickaxe);
        input().lookAt(ctx().computeOnClient(client -> client.player.getYRot()), 90f);
        final int fell;
        input().holdKey(options -> options.keyAttack);
        try {
            fell = ticksUntil(client -> client.player.getY() < VOID_Y || client.gui.screen() instanceof DeathScreen, FALL_TIMEOUT_TICKS);
        } finally {
            input().releaseKey(options -> options.keyAttack);
        }
        final int died = fell >= 0 ? ticksUntil(client -> client.gui.screen() instanceof DeathScreen, DEATH_TIMEOUT_TICKS) : -1;
        int back = -1;
        if (died >= 0) {
            ctx().takeScreenshot("core-g9-death");
            if (!ctx().tryClickScreenButton("deathScreen.respawn")) ctx().runOnClient(client -> client.player.respawn());
            back = ticksUntil(client -> RegressionClientGameTest.inWorld(client) && !(client.gui.screen() instanceof DeathScreen)
                    && client.player.position().distanceTo(CORE_SPAWN) <= 1.0 && client.player.getHealth() == 20f, RESPAWN_TIMEOUT_TICKS);
        }
        final int respawned = back;
        report.put(step, fell >= 0 && died >= 0 && respawned >= 0, ctx().computeOnClient(client -> String.format(Locale.ROOT,
                "%s, %s, %s; now at %s health %.1f screen %s",
                fell >= 0 ? "below y " + VOID_Y + " after " + fell + " ticks" : "still at y " + String.format(Locale.ROOT, "%.1f", client.player.getY())
                        + " after " + FALL_TIMEOUT_TICKS + " ticks",
                died >= 0 ? "death screen after " + died + " ticks" : "no death screen",
                respawned >= 0 ? "respawned at the spawn with full health after " + respawned + " ticks" : "not back at the spawn with full health",
                xyz(client.player.position()), client.player.getHealth(), RegressionClientGameTest.screenText(client))));
    }

    /** What the core world keeps of a player across a reconnect (REQ-PROD-047). */
    private record Kept(Vec3 position, float yaw, int selected, List<ItemStack> slots) {

        static Kept of(Minecraft client) {
            final List<ItemStack> slots = new ArrayList<>();
            for (ItemStack stack : client.player.inventoryMenu.getItems()) slots.add(stack.copy());
            return new Kept(client.player.position(), client.player.getYRot(), client.player.getInventory().getSelectedSlot(), slots);
        }

        boolean same(Kept other) {
            return differences(other).isEmpty();
        }

        List<String> differences(Kept other) {
            final List<String> differences = new ArrayList<>();
            if (position.distanceTo(other.position) > REJOIN_DISTANCE) differences.add("position " + xyz(other.position));
            if (Mth.degreesDifferenceAbs(yaw, other.yaw) > REJOIN_DEGREES)
                differences.add(String.format(Locale.ROOT, "yaw %.1f", other.yaw));
            if (selected != other.selected) differences.add("selected " + other.selected);
            for (int slot = 0; slot < Math.max(slots.size(), other.slots.size()); slot++) {
                final ItemStack mine = slot < slots.size() ? slots.get(slot) : ItemStack.EMPTY;
                final ItemStack theirs = slot < other.slots.size() ? other.slots.get(slot) : ItemStack.EMPTY;
                if (!ItemStack.matches(mine, theirs)) differences.add("slot " + slot + " " + mine + " -> " + theirs);
            }
            return differences;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%s yaw %.1f selected %d items %d", xyz(position), yaw, selected,
                    slots.stream().filter(stack -> !stack.isEmpty()).count());
        }
    }

    /**
     * G10 on the core world: position, facing, the 46 slots and the selected one survive leaving and
     * joining again, with no second starter kit.
     */
    private void rejoinCore(String server) {
        final String step = "core G10";
        if (!ready(step)) return;
        if (!ctx().computeOnClient(client -> client.player.isAlive() && client.player.getY() > 0)) {
            report.skip(step, "core G9 left the player dead or in the void at " + xyz(position()));
            return;
        }
        walkTo(REJOIN_X, REJOIN_Z, 0.3, WALK_TO_TICKS, null);
        input().lookAt(REJOIN_YAW, 0f);
        select(REJOIN_SLOT);
        ctx().waitTick();
        final Kept before = ctx().computeOnClient(Kept::of);
        final String rejoined = reconnect(server, "synaxis-core");
        if (rejoined != null) {
            report.put(step, false, "before " + before + "; " + rejoined);
            return;
        }
        final int matched = ticksUntil(client -> before.same(Kept.of(client)), REJOIN_TIMEOUT_TICKS);
        final Kept after = ctx().computeOnClient(Kept::of);
        report.put(step, matched >= 0, "before " + before + "; after " + after
                + (matched >= 0 ? " within " + matched + " ticks" : "; differs in " + before.differences(after)));
    }

    /** G10 on the RTS map: the units the player may command are still there after a reconnect. */
    private void rejoinRts(String server) {
        final String step = "rts G10";
        if (!ready(step)) return;
        final RtsClient rts = RegressionClientGameTest.staticField(RtsClient.class, "RTS");
        // Counted where G1 put the player, as the reconnect puts it there again
        final Vec3 spawn = rtsSpawn;
        if (spawn != null) walkTo(spawn.x, spawn.z, 0.5, WALK_TO_TICKS, null);
        final long before = stableCount(rts);
        final String rejoined = reconnect(server, "synaxis-rts");
        if (rejoined != null) {
            report.put(step, false, "commandable before " + before + "; " + rejoined);
            return;
        }
        final int matched = ticksUntil(_ -> commandable(rts) == before, UNITS_TIMEOUT_TICKS);
        final long after = ctx().computeOnClient(_ -> commandable(rts));
        report.put(step, before > 0 && matched >= 0, "commandable before " + before + " after " + after
                + (matched >= 0 ? " within " + matched + " ticks" : " after " + UNITS_TIMEOUT_TICKS + " ticks"));
    }

    /** The commandable units once their number stayed the same for {@link #UNITS_STILL_TICKS} ticks. */
    private long stableCount(RtsClient rts) {
        long last = -1;
        int still = 0;
        for (int tick = 0; tick < UNITS_TIMEOUT_TICKS && still < UNITS_STILL_TICKS; tick++) {
            final long now = ctx().computeOnClient(_ -> commandable(rts));
            still = now == last && now > 0 ? still + 1 : 0;
            last = now;
            ctx().waitTick();
        }
        return last;
    }

    private static long commandable(RtsClient rts) {
        return rts.bindings().all().stream().filter(UnitBindings.Binding::commandable).count();
    }

    // ---- connection ----------------------------------------------------------------------------

    private void connect(String server, String name) {
        ctx().runOnClient(client -> ConnectScreen.startConnecting(new TitleScreen(), client, ServerAddress.parseString(server),
                new ServerData(name, server, ServerData.Type.OTHER), false, null));
        ticksUntil(client -> RegressionClientGameTest.inWorld(client) && client.gui.screen() == null
                || client.gui.screen() instanceof DisconnectedScreen, CONNECT_TIMEOUT_TICKS);
    }

    /** Leaves to the title screen and joins the same server again; null once back in the world. */
    private @Nullable String reconnect(String server, String name) {
        leave();
        final int mark = DISCONNECTS.size();
        connect(server, name);
        if (!ctx().computeOnClient(RegressionClientGameTest::inWorld))
            return "the reconnect did not reach the world: " + screenText() + disconnectsSince(mark);
        settle();
        return null;
    }

    /** Back to the title screen with no level, from wherever the client is. */
    private void leave() {
        RegressionClientGameTest.leaveToTitle(ctx());
        ticksUntil(client -> client.level == null, LEAVE_TIMEOUT_TICKS);
    }

    /**
     * Waits for the player to stand on the ground with no screen open and not to have moved for
     * {@link #SETTLE_STILL_TICKS} ticks, which takes in the teleport a server sends to a saved
     * position right after the spawn.
     */
    private boolean settle() {
        Vec3 last = null;
        int still = 0;
        for (int tick = 0; tick < SETTLE_TIMEOUT_TICKS; tick++) {
            final Vec3 now = ctx().computeOnClient(client -> RegressionClientGameTest.inWorld(client) && client.gui.screen() == null
                    && client.player.onGround() ? client.player.position() : null);
            still = now != null && last != null && now.distanceToSqr(last) < 1e-6 ? still + 1 : 0;
            if (still >= SETTLE_STILL_TICKS) return true;
            last = now;
            ctx().waitTick();
        }
        return false;
    }

    /**
     * Whether the step can run: the client is in the world. A screen an earlier step left open is
     * closed first. A step that cannot run is SKIP, with the last disconnect reason if there is one.
     */
    private boolean ready(String step) {
        if (ctx().computeOnClient(client -> client.gui.screen() instanceof InventoryScreen || client.gui.screen() instanceof ChatScreen))
            closeScreen();
        final boolean inWorld = ctx().computeOnClient(client -> RegressionClientGameTest.inWorld(client)
                && !(client.gui.screen() instanceof DisconnectedScreen));
        if (!inWorld) report.skip(step, "not in the world: " + screenText() + disconnectsSince(0));
        return inWorld;
    }

    private void closeScreen() {
        ctx().runOnClient(client -> {
            final Screen screen = client.gui.screen();
            if (screen != null) screen.onClose();
        });
        ctx().waitTick();
    }

    private static String disconnectsSince(int mark) {
        return DISCONNECTS.size() > mark ? "; last disconnect: " + DISCONNECTS.getLast() : "";
    }

    // ---- walking and aiming --------------------------------------------------------------------

    /**
     * Walks towards (x, z) with the forward key, turning towards the target every tick and letting go
     * of the key while the slide would carry the player there, until it stands within the tolerance.
     *
     * @param until ends the walk early when it holds, null for none
     * @return whether the player got there, or {@code until} held
     */
    private boolean walkTo(double x, double z, double tolerance, int ticks, @Nullable Predicate<Minecraft> until) {
        boolean holding = false;
        try {
            for (int tick = 0; tick < ticks; tick++) {
                if (until != null && ctx().computeOnClient(until::test)) return true;
                final double[] state = ctx().computeOnClient(client -> new double[] {client.player.getX(), client.player.getZ(), speed(client)});
                final double dx = x - state[0];
                final double dz = z - state[1];
                final double distance = Math.hypot(dx, dz);
                if (distance <= tolerance && state[2] < STOPPED) return true;
                final boolean hold = distance > tolerance / 2 + state[2] * SLIDE;
                if (distance > tolerance / 2) input().lookAt(yawTowards(dx, dz), 0f);
                if (hold != holding) {
                    if (hold) input().holdKey(options -> options.keyUp);
                    else input().releaseKey(options -> options.keyUp);
                    holding = hold;
                }
                ctx().waitTick();
            }
            return until != null && ctx().computeOnClient(until::test);
        } finally {
            if (holding) input().releaseKey(options -> options.keyUp);
        }
    }

    /** Aims at the middle of the block's top face; null when the crosshair is on that face. */
    private @Nullable String aimAtTop(BlockPos block) {
        lookAtPoint(new Vec3(block.getX() + 0.5, block.getY() + 1.0, block.getZ() + 0.5));
        ctx().waitTick();
        return ctx().computeOnClient(client -> client.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(block) && hit.getDirection() == Direction.UP ? null : "crosshair on " + describe(client.hitResult));
    }

    private void lookAtPoint(Vec3 point) {
        final Vec3 eye = ctx().computeOnClient(client -> client.player.getEyePosition());
        final Vec3 to = point.subtract(eye);
        input().lookAt(yawTowards(to.x, to.z), (float) -Math.toDegrees(Math.atan2(to.y, Math.hypot(to.x, to.z))));
    }

    private static float yawTowards(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    private static String describe(@Nullable HitResult hit) {
        if (hit instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK)
            return "block " + block.getBlockPos().toShortString() + " face " + block.getDirection();
        if (hit instanceof EntityHitResult entity) return "entity " + entity.getEntity().getType().toShortString();
        return "nothing";
    }

    // ---- inventory -----------------------------------------------------------------------------

    /** Presses the hotbar key of the slot; whether the slot is selected after it. */
    private boolean select(int slot) {
        input().pressKey(options -> options.keyHotbarSlots[slot]);
        return ticksUntil(client -> client.player.getInventory().getSelectedSlot() == slot, SELECT_TIMEOUT_TICKS) >= 0;
    }

    private int hotbarSlot(Predicate<ItemStack> wanted) {
        return ctx().computeOnClient(client -> {
            for (int slot = 0; slot < Inventory.getSelectionSize(); slot++)
                if (wanted.test(client.player.getInventory().getItem(slot))) return slot;
            return -1;
        });
    }

    private int emptyMainSlot() {
        return ctx().computeOnClient(client -> {
            for (int slot = InventoryMenu.INV_SLOT_START; slot < InventoryMenu.INV_SLOT_END; slot++)
                if (client.player.getInventory().getItem(slot).isEmpty()) return slot;
            return -1;
        });
    }

    private int count(int slot) {
        return ctx().computeOnClient(client -> client.player.getInventory().getItem(slot).getCount());
    }

    private int stoneCount(int slot) {
        return ctx().computeOnClient(client -> {
            final ItemStack stack = client.player.getInventory().getItem(slot);
            return stack.is(Items.STONE) ? stack.getCount() : 0;
        });
    }

    private int carried() {
        return ctx().computeOnClient(client -> client.player.inventoryMenu.getCarried().getCount());
    }

    private int total(Item item) {
        return ctx().computeOnClient(client -> total(client, item));
    }

    private static int total(Minecraft client, Item item) {
        final Inventory inventory = client.player.getInventory();
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++)
            if (inventory.getItem(slot).is(item)) total += inventory.getItem(slot).getCount();
        return total;
    }

    private String inventoryText() {
        return ctx().computeOnClient(client -> {
            final Map<Integer, String> slots = new TreeMap<>();
            final Inventory inventory = client.player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++)
                if (!inventory.getItem(slot).isEmpty()) slots.put(slot, inventory.getItem(slot).toString());
            return slots.toString();
        });
    }

    // ---- small helpers -------------------------------------------------------------------------

    private Vec3 position() {
        return ctx().computeOnClient(client -> client.player.position());
    }

    private String blockAt(BlockPos pos) {
        return ctx().computeOnClient(client -> client.level.getBlockState(pos).toString());
    }

    private String screenText() {
        return ctx().computeOnClient(RegressionClientGameTest::screenText);
    }

    private static double speed(Minecraft client) {
        final Vec3 velocity = client.player.getDeltaMovement();
        return Math.hypot(velocity.x, velocity.z);
    }

    private static double horizontal(Vec3 from, Vec3 to) {
        return Math.hypot(to.x - from.x, to.z - from.z);
    }

    private static String xyz(Vec3 v) {
        return String.format(Locale.ROOT, "%.2f,%.2f,%.2f", v.x, v.y, v.z);
    }

    /** Ticks until the condition held, -1 when it did not within the limit. */
    private int ticksUntil(Predicate<Minecraft> condition, int limit) {
        try {
            return ctx().waitFor(condition, limit);
        } catch (AssertionError _) {
            return -1;
        }
    }

    /** Whether the condition holds at every one of the next {@code ticks} ticks. */
    private boolean holds(Predicate<Minecraft> condition, int ticks) {
        for (int tick = 0; tick < ticks; tick++) {
            ctx().waitTick();
            if (!ctx().computeOnClient(condition::test)) return false;
        }
        return true;
    }

    private ClientGameTestContext ctx() {
        return Objects.requireNonNull(context);
    }

    private TestInput input() {
        return Objects.requireNonNull(input);
    }

    /** The fifteen lines, written whole every time so the file never holds a previous run's lines. */
    private static final class Report {

        private final Map<String, String> lines = new HashMap<>();

        void put(String step, boolean pass, String evidence) {
            lines.put(step, step + (pass ? " PASS " : " FAIL ") + oneLine(evidence));
        }

        void skip(String step, String why) {
            lines.putIfAbsent(step, step + " SKIP " + oneLine(why));
        }

        void skipRest(String mode, String why) {
            for (String step : STEPS) if (step.startsWith(mode + " ")) skip(step, why);
        }

        void failIfAbsent(String step, String why) {
            lines.putIfAbsent(step, step + " FAIL " + oneLine(why));
        }

        void failRest(String mode, String why) {
            for (String step : STEPS) if (step.startsWith(mode + " ")) failIfAbsent(step, why);
        }

        void write(String why) {
            final List<String> out = new ArrayList<>();
            for (String step : STEPS) out.add(lines.getOrDefault(step, step + " FAIL not reached: " + oneLine(why)));
            final Path file = FabricLoader.getInstance().getGameDir().resolve("synaxis-gametest/playable.txt");
            try {
                Files.createDirectories(file.getParent());
                Files.write(file, out, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("could not write " + file, e);
            }
        }

        private static String oneLine(String text) {
            return text.replace('\n', ' ').replace('\r', ' ');
        }
    }
}
