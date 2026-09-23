package io.github.bearisbug.synaxis.game.client.gametest.s5;

import io.github.bearisbug.synaxis.game.client.gametest.camera.SpikeCamera;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * S5, terrain half: the spike camera with its eye 20 and 200 blocks above the ground and 50 blocks
 * past the map's edge, one 1920x1080 screenshot {@code s5-<shot>-<label>} each, then the README's
 * fallback for the shots that lose terrain: the ortho eye pulled along its view axis until it is
 * inside the loaded sections, the picture unchanged ({@code -clamped}).
 * <p>
 * The numbers behind each picture go to the report. A surface section is a section of a loaded
 * chunk holding the topmost block of at least one of the chunk's columns: open to the sky, so
 * nothing but the frustum may hide it from a camera above the map. Of those inside the cull
 * frustum, the report counts how many are missing from {@code LevelRenderer#visibleSections}, the
 * list the section occlusion graph hands to the renderer, and how many are on it without a
 * compiled mesh ({@code LevelRenderer#isSectionCompiledAndVisible}).
 */
public final class TerrainSpike {

    /** The pitch of every shot, the S2 default. */
    private static final float PITCH = 60f;
    private static final float SIN_PITCH = (float) Math.sin(Math.toRadians(PITCH));

    /** Where the map ends on +X, MapPreset.DEFAULT (rts-512) spanning [0, 512). */
    private static final int MAP_EDGE_X = 512;

    /** The ortho frame height of the shots, the S2 default. */
    private static final float ORTHO_HEIGHT = 96f;

    private TerrainSpike() {}

    public static void run(ClientGameTestContext context, List<String> report) {
        final Vec3 feet = context.computeOnClient(client -> client.player.position());
        final float yaw = context.computeOnClient(client -> client.player.getYRot());
        final Vec3 ground = context.computeOnClient(client -> groundAt(client.level, feet.x, feet.z));

        // 1 and 2: the eye above the player's own column.
        shot(context, report, "eye20", new SpikeCamera.Settings(SpikeCamera.Mode.PERSPECTIVE, ground, yaw, PITCH,
                20f / SIN_PITCH, ORTHO_HEIGHT));
        shot(context, report, "eye200", new SpikeCamera.Settings(SpikeCamera.Mode.PERSPECTIVE, ground, yaw, PITCH,
                200f / SIN_PITCH, ORTHO_HEIGHT));
        final SpikeCamera.Settings high = new SpikeCamera.Settings(SpikeCamera.Mode.ORTHO, ground, yaw, PITCH,
                200f / SIN_PITCH, ORTHO_HEIGHT);
        shot(context, report, "eye200", high);
        shot(context, report, "eye200-clamped", clamped(context, high));

        // 3: the map's +X edge is 256 blocks from the spawn, well past the 10 chunk view distance, so
        // the player goes over first and the server streams the edge's chunks. The eye then hangs 50
        // blocks past the edge looking back west (yaw 90) at a point 32 blocks inside the map.
        final Vec3 edgeFeet = new Vec3(MAP_EDGE_X - 40 + 0.5, 72, feet.z);
        context.runOnClient(client -> {
            client.player.getAbilities().mayfly = true;
            client.player.getAbilities().flying = true;
            client.player.setPos(edgeFeet);
        });
        final int edgeChunkX = SectionPos.blockToSectionCoord(MAP_EDGE_X - 1);
        final int edgeChunkZ = SectionPos.blockToSectionCoord(feet.z);
        context.waitFor(client -> client.level.getChunkSource().hasChunk(edgeChunkX, edgeChunkZ), 20 * 30);
        context.waitTicks(60);
        report.add(context.computeOnClient(client -> "S5 player moved to " + client.player.position()
                + " edge chunk " + edgeChunkX + "," + edgeChunkZ + " loaded "
                + client.level.getChunkSource().hasChunk(edgeChunkX, edgeChunkZ)));
        final Vec3 edgeAnchor = context.computeOnClient(client -> groundAt(client.level, MAP_EDGE_X - 32 + 0.5, feet.z));
        // Eye 82 blocks east of the anchor at 60 degrees: 50 past the edge.
        final float edgeDistance = (32f + 50f) / (float) Math.cos(Math.toRadians(PITCH));
        shot(context, report, "outside50", new SpikeCamera.Settings(SpikeCamera.Mode.PERSPECTIVE, edgeAnchor, 90f, PITCH,
                edgeDistance, ORTHO_HEIGHT));
        final SpikeCamera.Settings outside = new SpikeCamera.Settings(SpikeCamera.Mode.ORTHO, edgeAnchor, 90f, PITCH,
                edgeDistance, ORTHO_HEIGHT);
        shot(context, report, "outside50", outside);
        shot(context, report, "outside50-clamped", clamped(context, outside));
        context.runOnClient(_ -> SpikeCamera.set(null));
    }

    /**
     * The fallback: the same settings with the distance cut until the eye is below the top of the
     * world and above a loaded chunk, in half-block steps.
     */
    private static SpikeCamera.Settings clamped(ClientGameTestContext context, SpikeCamera.Settings settings) {
        return context.computeOnClient(client -> {
            final Vec3 forwards = Vec3.directionFromRotation(settings.pitch(), settings.yaw());
            float distance = settings.distance();
            while (distance > 0.5f) {
                final Vec3 eye = settings.anchor().subtract(forwards.scale(distance));
                if (eye.y < client.level.getMaxY() && client.level.getChunkSource().hasChunk(
                        SectionPos.blockToSectionCoord(eye.x), SectionPos.blockToSectionCoord(eye.z))) break;
                distance -= 0.5f;
            }
            return new SpikeCamera.Settings(settings.mode(), settings.anchor(), settings.yaw(), settings.pitch(), distance,
                    settings.orthoHeight());
        });
    }

    private static void shot(ClientGameTestContext context, List<String> report, String name, SpikeCamera.Settings settings) {
        context.runOnClient(_ -> SpikeCamera.set(settings));
        context.waitTicks(100);
        report.add(context.computeOnClient(client -> "S5 " + name + " " + settings.label() + " anchor " + settings.anchor()
                + " eye " + client.gameRenderer.mainCamera().position() + " eye_section "
                + SectionPos.of(client.gameRenderer.mainCamera().position()) + " " + sections(client)));
        context.takeScreenshot(TestScreenshotOptions.of("s5-" + name + "-" + settings.label()).withSize(1920, 1080));
    }

    private static String sections(Minecraft client) {
        final ClientLevel level = client.level;
        final Frustum frustum = client.gameRenderer.mainCamera().getCullFrustum();
        final LongOpenHashSet visible = new LongOpenHashSet();
        for (SectionRenderDispatcher.RenderSection section : client.levelRenderer.visibleSections())
            visible.add(SectionPos.asLong(section.getRenderOrigin()));
        final int centreX = client.player.chunkPosition().x();
        final int centreZ = client.player.chunkPosition().z();
        final int reach = client.options.getEffectiveRenderDistance() + 3;
        int loadedChunks = 0;
        int surface = 0;
        final Tally notInGraph = new Tally();
        final Tally notCompiled = new Tally();
        for (int cx = centreX - reach; cx <= centreX + reach; cx++) {
            for (int cz = centreZ - reach; cz <= centreZ + reach; cz++) {
                final LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
                if (chunk == null) continue;
                loadedChunks++;
                for (int index : surfaceSections(chunk.getSections())) {
                    final int sy = level.getSectionYFromSectionIndex(index);
                    if (!frustum.isVisible(new AABB(cx * 16, sy * 16, cz * 16, cx * 16 + 16, sy * 16 + 16, cz * 16 + 16))) continue;
                    surface++;
                    if (!visible.contains(SectionPos.asLong(cx, sy, cz))) notInGraph.add(cx, cz);
                    else if (!client.levelRenderer.isSectionCompiledAndVisible(new BlockPos(cx * 16, sy * 16, cz * 16)))
                        notCompiled.add(cx, cz);
                }
            }
        }
        return "visible_sections " + visible.size() + " surface_sections_in_frustum " + surface
                + " not_in_graph " + notInGraph + " in_graph_not_compiled " + notCompiled
                + " loaded_chunks " + loadedChunks + " render_distance " + client.options.getEffectiveRenderDistance();
    }

    /** A count of sections and the chunk range they span. */
    private static final class Tally {
        private int count;
        private int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        void add(int cx, int cz) {
            count++;
            minX = Math.min(minX, cx);
            maxX = Math.max(maxX, cx);
            minZ = Math.min(minZ, cz);
            maxZ = Math.max(maxZ, cz);
        }

        @Override
        public String toString() {
            return count == 0 ? "0" : count + " (chunks x[" + minX + "," + maxX + "] z[" + minZ + "," + maxZ + "])";
        }
    }

    /** The indices of the sections holding the topmost non-air block of at least one column. */
    private static int[] surfaceSections(LevelChunkSection[] sections) {
        final boolean[] surface = new boolean[sections.length];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                column:
                for (int i = sections.length - 1; i >= 0; i--) {
                    if (sections[i].hasOnlyAir()) continue;
                    for (int y = 15; y >= 0; y--) {
                        if (!sections[i].getBlockState(x, y, z).isAir()) {
                            surface[i] = true;
                            break column;
                        }
                    }
                }
            }
        }
        int count = 0;
        for (boolean s : surface) if (s) count++;
        final int[] indices = new int[count];
        for (int i = 0, n = 0; i < surface.length; i++) if (surface[i]) indices[n++] = i;
        return indices;
    }

    /** The top of the highest non-air block of a column, or the world's floor when the column is not loaded. */
    private static Vec3 groundAt(ClientLevel level, double x, double z) {
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = level.getMaxY(); y >= level.getMinY(); y--) {
            if (!level.getBlockState(pos.set(x, y, z)).isAir()) return new Vec3(x, y + 1, z);
        }
        return new Vec3(x, level.getMinY(), z);
    }
}
