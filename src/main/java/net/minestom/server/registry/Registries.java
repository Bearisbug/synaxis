package net.minestom.server.registry;

import net.kyori.adventure.nbt.BinaryTag;
import net.minestom.server.codec.Codec;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.damage.DamageType;
import net.minestom.server.game.GameEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.predicate.DataComponentPredicate;
import net.minestom.server.instance.fluid.Fluid;
import net.minestom.server.instance.gamerule.GameRule;
import net.minestom.server.item.Material;
import net.minestom.server.message.ChatType;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.common.TagsPacket;
import net.minestom.server.potion.PotionEffect;
import net.minestom.server.potion.PotionType;
import net.minestom.server.world.DimensionType;
import net.minestom.server.world.biome.Biome;

import java.util.List;

/**
 * <p>Provides access to all the dynamic registries. {@link net.minestom.server.ServerProcess} is the most relevant
 * implementation of this interface.</p>
 *
 * @see net.minestom.server.MinecraftServer for static access to these
 */
public interface Registries {
    static Registries vanilla() {
        return new VanillaRegistries();
    }

    static List<SendablePacket> registryDataPackets(Registries registries, boolean excludeVanilla) {
        return RegistriesImpl.registryDataPackets(registries, excludeVanilla);
    }

    static TagsPacket tagsPacket(Registries registries) {
        return RegistriesImpl.tagsPacket(registries);
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
