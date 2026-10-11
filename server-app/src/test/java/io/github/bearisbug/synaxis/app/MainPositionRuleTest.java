package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.app.shard.MapFixture;
import io.github.bearisbug.synaxis.app.shard.ShardSettings;
import io.github.bearisbug.synaxis.app.shard.ShardWorld;
import io.github.bearisbug.synaxis.app.store.PositionRule;
import io.github.bearisbug.synaxis.synx.OverlayOptions;
import io.github.bearisbug.synaxis.synx.SynxOptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code Main#positionRule}, the rule step (5) sets on {@code synaxis:player} ({@code docs/DESIGN-PROD.md}
 * {@code API-PROD-032}): a map shard names the map id, the build id and the bounds of the release {@code ShardWorld}
 * prepared in step (3); without a map the core world and a gameplay plugin get the rule of their mode.
 */
class MainPositionRuleTest {

    @TempDir
    static Path maps;
    private static ShardSettings release;

    @BeforeAll
    static void bake() {
        release = MapFixture.bake(maps);
    }

    @Test
    void aMapShardNamesTheMapIdTheBuildIdAndTheBoundsOfShardWorld() {
        final ServerProcess process = ServerProcess.of(ServerConfig.builder().insideTest(true).shutdownOnSignal(false)
                .build());
        // Step (3) of Main on a map
        final ShardWorld world = ShardWorld.prepare(process, release, new SynxOptions(true, 1 << 24, 1, false),
                new OverlayOptions(16_384, 10), null);

        assertEquals(new PositionRule.MapShard(world.mapId(), world.buildId(), world.bounds()),
                Main.positionRule(CoreMode.Mode.CORE, world));
    }

    @Test
    void withoutAMapTheCoreWorldAndAGameplayPluginGetTheRuleOfTheirMode() {
        assertEquals(new PositionRule.Core(), Main.positionRule(CoreMode.Mode.CORE, null), "the core world");
        assertEquals(new PositionRule.Rts(), Main.positionRule(CoreMode.Mode.GAMEPLAY, null), "a gameplay plugin");
    }
}
