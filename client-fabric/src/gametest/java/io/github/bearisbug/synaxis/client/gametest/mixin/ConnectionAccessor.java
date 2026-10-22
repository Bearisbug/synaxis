package io.github.bearisbug.synaxis.client.gametest.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The client connection's netty channel: {@code Connection#setEncryptionKey} is the only place
 * that adds the {@code decrypt} and {@code encrypt} handlers, and nothing public on
 * {@link Connection} says whether it ran. R3 of the regression reads the pipeline through this.
 */
@Mixin(Connection.class)
public interface ConnectionAccessor {

    @Accessor("channel")
    Channel synaxis$channel();
}
