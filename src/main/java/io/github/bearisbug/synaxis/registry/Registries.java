package io.github.bearisbug.synaxis.registry;

import net.kyori.adventure.nbt.BinaryTag;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.entity.damage.DamageType;
import io.github.bearisbug.synaxis.gameevent.GameEvent;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.block.predicate.DataComponentPredicate;
import io.github.bearisbug.synaxis.instance.fluid.Fluid;
import io.github.bearisbug.synaxis.instance.gamerule.GameRule;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.message.ChatType;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.TagsPacket;
import io.github.bearisbug.synaxis.potion.PotionEffect;
import io.github.bearisbug.synaxis.potion.PotionType;
import io.github.bearisbug.synaxis.world.DimensionType;
import io.github.bearisbug.synaxis.world.biome.Biome;

import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * <p>Provides access to all the dynamic registries. {@link io.github.bearisbug.synaxis.ServerProcess} is the most relevant
 * implementation of this interface.</p>
 *
 * @see io.github.bearisbug.synaxis.ServerProcess for the process owned registries
 */
public interface Registries {
    /**
     * Loads the whole vanilla data from the classpath on every call and returns a new, independent, writable registry
     * set that is never frozen, so keep the result instead of calling this repeatedly. It sees none of the entries a
     * {@link io.github.bearisbug.synaxis.ServerProcess} registers (such as a host's {@code synaxis:managed}); encode,
     * decode and look up with {@link io.github.bearisbug.synaxis.ServerProcess#registries()} or the process a plugin
     * context gives.
     *
     * @return a new set of vanilla registries
     */
    static Registries vanilla() {
        return new VanillaRegistries();
    }

    static List<SendablePacket> registryDataPackets(Registries registries, boolean excludeVanilla) {
        return RegistriesImpl.registryDataPackets(registries, excludeVanilla);
    }

    static TagsPacket tagsPacket(Registries registries) {
        return RegistriesImpl.tagsPacket(registries);
    }

    /**
     * Freezes every dynamic registry so later writes throw, called when the owning process starts.
     */
    @ApiStatus.Internal
    static void freeze(Registries registries) {
        RegistriesImpl.freeze(registries);
    }

    /**
     * Registers the callback fired when the tags of one of these registries change.
     */
    @ApiStatus.Internal
    static void tagChangeListener(Registries registries, Runnable listener) {
        RegistriesImpl.tagChangeListener(registries, listener);
    }

    // Static registries

    // The name block conflicts with blockmanager :(
    default Registry<Block> blocks() {
        return Block.staticRegistry();
    }

    default Registry<Material> material() {
        return Material.staticRegistry();
    }

    default Registry<PotionEffect> potionEffect() {
        return PotionEffect.staticRegistry();
    }

    default Registry<PotionType> potionType() {
        return PotionType.staticRegistry();
    }

    default Registry<EntityType> entityType() {
        return EntityType.staticRegistry();
    }

    default Registry<Fluid> fluid() {
        return Fluid.staticRegistry();
    }

    default Registry<GameEvent> gameEvent() {
        return GameEvent.staticRegistry();
    }

    default Registry<GameRule<?>> gameRule() {
        return GameRule.staticRegistry();
    }

    // Dynamic registries with server-side semantics

    DynamicRegistry<ChatType> chatType();

    DynamicRegistry<DimensionType> dimensionType();

    DynamicRegistry<Biome> biome();

    DynamicRegistry<DamageType> damageType();

    /**
     * <p>Returns one of the registries the server forwards to the client without interpreting it. Its entries are
     * the raw NBT the client receives, so no server-side type exists for them.</p>
     *
     * <p>The registry is frozen once the server has started; writing to it then throws
     * {@link IllegalStateException}.</p>
     *
     * @param key one of the opaque registry keys of {@link BuiltinRegistries}
     * @return the registry
     */
    DynamicRegistry<BinaryTag> opaque(RegistryKey<Registry<BinaryTag>> key);

    // The following are _not_ sent to the client.

    DynamicRegistry<Codec<? extends DataComponentPredicate>> componentPredicateTypes();

    @FunctionalInterface
    interface Selector<T> {
        Registry<T> select(Registries registries);
    }

    @FunctionalInterface
    interface Delegating extends Registries {
        Registries registries();

        @Override
        default Registry<Block> blocks() {
            return registries().blocks();
        }

        @Override
        default Registry<Material> material() {
            return registries().material();
        }

        @Override
        default Registry<PotionEffect> potionEffect() {
            return registries().potionEffect();
        }

        @Override
        default Registry<EntityType> entityType() {
            return registries().entityType();
        }

        @Override
        default Registry<PotionType> potionType() {
            return registries().potionType();
        }

        @Override
        default Registry<Fluid> fluid() {
            return registries().fluid();
        }

        @Override
        default Registry<GameEvent> gameEvent() {
            return registries().gameEvent();
        }

        @Override
        default DynamicRegistry<ChatType> chatType() {
            return registries().chatType();
        }

        @Override
        default DynamicRegistry<DimensionType> dimensionType() {
            return registries().dimensionType();
        }

        @Override
        default DynamicRegistry<Biome> biome() {
            return registries().biome();
        }

        @Override
        default DynamicRegistry<DamageType> damageType() {
            return registries().damageType();
        }

        @Override
        default DynamicRegistry<BinaryTag> opaque(RegistryKey<Registry<BinaryTag>> key) {
            return registries().opaque(key);
        }

        @Override
        default DynamicRegistry<Codec<? extends DataComponentPredicate>> componentPredicateTypes() {
            return registries().componentPredicateTypes();
        }
    }
}
