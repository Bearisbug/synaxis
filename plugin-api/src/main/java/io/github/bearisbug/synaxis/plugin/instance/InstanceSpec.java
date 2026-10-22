package io.github.bearisbug.synaxis.plugin.instance;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.generator.Generator;
import io.github.bearisbug.synaxis.plugin.DisconnectKeys;
import io.github.bearisbug.synaxis.plugin.PluginApi;

import java.time.Duration;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * What {@link PluginInstances#create} builds ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-006}).
 *
 * @param name          the kind of instance, matching {@code [a-z0-9_]{1,32}}; each instance gets the
 *                      dimension name {@code <plugin_id>:<name>_<n>}, unique in the process
 * @param dimensionType the dimension type, one registered before the port was bound
 * @param chunkRadius   the chunks loaded around chunk 0, 0, from 0 to 16; the map is
 *                      {@code (2 * chunkRadius + 1)²} chunks and no more are ever loaded
 * @param generator     the generator of those chunks, called from several threads at once under the
 *                      rules of {@link Generator#generate}; while the instance is
 *                      {@link ManagedInstance.State#PREPARING} the host turns an exception it throws
 *                      into {@link EndCause#PREPARE_FAILED} ({@code API-PLAY-006})
 * @param settleTimeout how long the instance may stay {@link ManagedInstance.State#ENDING} before the
 *                      host closes it with {@code fallbackExit}; more than zero
 * @param fallbackExit  where the host sends each player when the instance is still
 *                      {@link ManagedInstance.State#ENDING} once {@code settleTimeout} is over (the
 *                      settle timeout of {@code docs/DESIGN-PLAY.md} §11.1): called on the tick scheduler
 *                      thread for those online inside, and like the exit of {@link ManagedInstance#cancel}
 *                      for a move that lands afterwards, so it should read only state that is thread safe and
 *                      not cleared once the instance closes
 */
public record InstanceSpec(String name, String dimensionType, int chunkRadius, Generator generator,
                           Duration settleTimeout, Function<Player, Exit> fallbackExit) {

    private static final Pattern NAME = Pattern.compile("[a-z0-9_]{1,32}");

    /**
     * Checks the name, the chunk radius and the settle timeout, so that no spec {@code create} would
     * refuse for them exists.
     *
     * @throws IllegalArgumentException if one of them is out of range
     */
    public InstanceSpec {
        if (!NAME.matcher(name).matches())
            throw new IllegalArgumentException("an instance name matches " + NAME.pattern() + ", not \"" + name + "\"");
        if (chunkRadius < 0 || chunkRadius > 16)
            throw new IllegalArgumentException("a chunk radius is 0 to 16, not " + chunkRadius);
        if (!settleTimeout.isPositive())
            throw new IllegalArgumentException("a settle timeout is more than zero, not " + settleTimeout);
    }

    /**
     * A spec on the match dimension type ({@link PluginApi#MATCH_DIMENSION_TYPE}), with a settle
     * timeout of 30 s and every player kicked with {@link DisconnectKeys#MATCH_UNAVAILABLE} when the
     * instance is not settled within it. An instance that needs another settle timeout or another
     * fallback exit takes the canonical constructor instead.
     *
     * @param name        the kind of instance, matching {@code [a-z0-9_]{1,32}}
     * @param chunkRadius the chunks loaded around chunk 0, 0, from 0 to 16
     * @param generator   the generator of those chunks
     * @throws IllegalArgumentException if the name or the chunk radius is out of range
     */
    public InstanceSpec(String name, int chunkRadius, Generator generator) {
        this(name, PluginApi.MATCH_DIMENSION_TYPE, chunkRadius, generator, Duration.ofSeconds(30),
                _ -> new Exit.Kick(DisconnectKeys.MATCH_UNAVAILABLE));
    }
}
