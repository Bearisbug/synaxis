package net.minestom.codegen;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

public final class Generators {

    private static final List<EnumSpec> ENUMS = List.of(
            new EnumSpec("consume_effects", "net.minestom.server.item.component", "ConsumeEffectType", false, UnaryOperator.identity(), "GenericEnumGenerator"),
            new EnumSpec("command_arguments", "net.minestom.server.command", "ArgumentParserType"),
            new EnumSpec("villager_types", "net.minestom.server.entity", "VillagerType")
    );

    private static final List<StaticRegistrySpec> STATIC_REGISTRIES = List.of(
            new StaticRegistrySpec("block", "net.minestom.server.instance.block", "Block", "BlockImpl", "Blocks"),
            new StaticRegistrySpec("item", "net.minestom.server.item", "Material", "MaterialImpl", "Materials"),
            new StaticRegistrySpec("entity_type", "net.minestom.server.entity", "EntityType", "EntityTypeImpl", "EntityTypes"),
            new StaticRegistrySpec("mob_effect", "net.minestom.server.potion", "PotionEffect", "PotionEffectImpl", "PotionEffects"),
            new StaticRegistrySpec("potion", "net.minestom.server.potion", "PotionType", "PotionTypeImpl", "PotionTypes"),
            new StaticRegistrySpec("sound_event", "net.minestom.server.sound", "SoundEvent", "BuiltinSoundEvent", "SoundEvents", "BuiltinSoundEvent"),
            new StaticRegistrySpec("attribute", "net.minestom.server.entity.attribute", "Attribute", "AttributeImpl", "Attributes"),
            new StaticRegistrySpec("feature_flag", "net.minestom.server", "FeatureFlag", "FeatureFlagImpl", "FeatureFlags"),
            new StaticRegistrySpec("fluid", "net.minestom.server.instance.fluid", "Fluid", "FluidImpl", "Fluids"),
            new StaticRegistrySpec("villager_profession", "net.minestom.server.entity", "VillagerProfession", "VillagerProfessionImpl", "VillagerProfessions"),
            new StaticRegistrySpec("game_event", "net.minestom.server.game", "GameEvent", "GameEventImpl", "GameEvents"),
            new StaticRegistrySpec("block_sound_type", "net.minestom.server.instance.block", "BlockSoundType", "BlockSoundImpl", "BlockSoundTypes"),
            new StaticRegistrySpec("block_entity_type", "net.minestom.server.instance.block", "BlockEntityType", "BlockEntityTypeImpl", "BlockEntityTypes")
    );

    private static final List<DynamicRegistrySpec> DYNAMIC_REGISTRIES = List.of(
            new DynamicRegistrySpec("chat_type", "net.minestom.server.message", "ChatType"),
            new DynamicRegistrySpec("dimension_type", "net.minestom.server.world", "DimensionType"),
            new DynamicRegistrySpec("damage_type", "net.minestom.server.entity.damage", "DamageType"),
            new DynamicRegistrySpec("worldgen/biome", "net.minestom.server.world.biome", "Biome")
    );

    static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: <registry data folder> <target folder>");
            return;
        }
        generateAll(Path.of(args[0]), Path.of(args[1]));
        System.out.println("Finished generating code");
    }

    public static void generateAll(Path dataFolder, Path outputFolder) {
        Codegen codegen = new Codegen(dataFolder, outputFolder);

        new DyeColorGenerator(codegen).generate();
        new ParticleGenerator(codegen).generate();
        new ConstantsGenerator(codegen).generate();
        new GameRuleGenerator(codegen).generate();

        GenericEnumGenerator enumGenerator = new GenericEnumGenerator(codegen);
        ENUMS.forEach(enumGenerator::generate);

        new WorldEventGenerator(codegen, "net.minestom.server.worldevent", "WorldEvent", "world_events").generate();

        RegistryGenerator registryGenerator = new RegistryGenerator(codegen);
        STATIC_REGISTRIES.forEach(registryGenerator::generate);
        DYNAMIC_REGISTRIES.forEach(registryGenerator::generate);
        new BuiltinRegistriesGenerator(codegen).generate(STATIC_REGISTRIES, DYNAMIC_REGISTRIES);
    }

    record EnumSpec(String resource, String packageName, String className, boolean isPublic,
                    UnaryOperator<String> constantNameTransform, String generatorName) {
        EnumSpec(String resource, String packageName, String className) {
            this(resource, packageName, className, true, UnaryOperator.identity(), "GenericEnumGenerator");
        }

        EnumSpec {
            Objects.requireNonNull(resource, "resource cannot be null");
            Objects.requireNonNull(packageName, "packageName cannot be null");
            Objects.requireNonNull(className, "className cannot be null");
            Objects.requireNonNull(constantNameTransform, "constantNameTransform cannot be null");
            Objects.requireNonNull(generatorName, "generatorName cannot be null");
        }
    }

    record StaticRegistrySpec(String key, String packageName, String typeName, String loaderName,
                              String generatedName, String registryTypeName) {
        StaticRegistrySpec(String key, String packageName, String typeName, String loaderName,
                           String generatedName) {
            this(key, packageName, typeName, loaderName, generatedName, typeName);
        }

        StaticRegistrySpec {
            Objects.requireNonNull(key, "key cannot be null");
            Objects.requireNonNull(packageName, "packageName cannot be null");
            Objects.requireNonNull(typeName, "typeName cannot be null");
            Objects.requireNonNull(loaderName, "loaderName cannot be null");
            Objects.requireNonNull(generatedName, "generatedName cannot be null");
            Objects.requireNonNull(registryTypeName, "registryTypeName cannot be null");
        }
    }

    record DynamicRegistrySpec(String key, String packageName, String typeName, String generatedName) {
        DynamicRegistrySpec(String key, String packageName, String typeName) {
            this(key, packageName, typeName, typeName + "s");
        }

        DynamicRegistrySpec {
            Objects.requireNonNull(key, "key cannot be null");
            Objects.requireNonNull(packageName, "packageName cannot be null");
            Objects.requireNonNull(typeName, "typeName cannot be null");
            Objects.requireNonNull(generatedName, "generatedName cannot be null");
        }
    }
}
