package io.github.bearisbug.synaxis.command.builder.arguments;

import io.github.bearisbug.synaxis.command.builder.arguments.minecraft.ArgumentBlockState;
import io.github.bearisbug.synaxis.command.builder.arguments.minecraft.ArgumentComponent;
import io.github.bearisbug.synaxis.command.builder.arguments.minecraft.ArgumentEntity;
import io.github.bearisbug.synaxis.command.builder.arguments.minecraft.ArgumentFloatRange;
import io.github.bearisbug.synaxis.command.builder.arguments.minecraft.ArgumentIntRange;
import io.github.bearisbug.synaxis.command.builder.arguments.minecraft.ArgumentItemStack;
import io.github.bearisbug.synaxis.command.builder.arguments.minecraft.ArgumentNbtCompoundTag;
import io.github.bearisbug.synaxis.command.builder.arguments.minecraft.ArgumentNbtTag;
import io.github.bearisbug.synaxis.command.builder.arguments.minecraft.ArgumentResource;
import io.github.bearisbug.synaxis.command.builder.arguments.minecraft.ArgumentResourceLocation;
import io.github.bearisbug.synaxis.command.builder.arguments.minecraft.ArgumentResourceOrTag;
import io.github.bearisbug.synaxis.command.builder.arguments.minecraft.ArgumentTeamColor;
import io.github.bearisbug.synaxis.command.builder.arguments.minecraft.ArgumentTime;
import io.github.bearisbug.synaxis.command.builder.arguments.minecraft.ArgumentUUID;
import io.github.bearisbug.synaxis.command.builder.arguments.minecraft.registry.ArgumentEntityType;
import io.github.bearisbug.synaxis.command.builder.arguments.minecraft.registry.ArgumentParticle;
import io.github.bearisbug.synaxis.command.builder.arguments.number.ArgumentDouble;
import io.github.bearisbug.synaxis.command.builder.arguments.number.ArgumentFloat;
import io.github.bearisbug.synaxis.command.builder.arguments.number.ArgumentInteger;
import io.github.bearisbug.synaxis.command.builder.arguments.number.ArgumentLong;
import io.github.bearisbug.synaxis.command.builder.arguments.relative.ArgumentRelativeBlockPosition;
import io.github.bearisbug.synaxis.command.builder.arguments.relative.ArgumentRelativeVec2;
import io.github.bearisbug.synaxis.command.builder.arguments.relative.ArgumentRelativeVec3;
import io.github.bearisbug.synaxis.command.builder.parser.ArgumentParser;
import org.jetbrains.annotations.ApiStatus;

/**
 * Convenient class listing all the basics {@link Argument}.
 * <p>
 * Please see the specific class documentation for further info.
 */
public class ArgumentType {

    /**
     * Creates a new {@link ArgumentLiteral}.
     *
     * @see ArgumentLiteral
     */
    public static ArgumentLiteral Literal(String id) {
        return new ArgumentLiteral(id);
    }

    /**
     * Creates a new {@link ArgumentGroup}.
     *
     * @see ArgumentGroup
     */
    public static ArgumentGroup Group(String id, Argument<?>... arguments) {
        return new ArgumentGroup(id, arguments);
    }

    /**
     * Creates a new {@link ArgumentLoop}.
     *
     * @see ArgumentLoop
     */
    @SafeVarargs
    @SuppressWarnings("varargs")
    public static <T> ArgumentLoop<T> Loop(String id, Argument<T>... arguments) {
        return new ArgumentLoop<>(id, arguments);
    }

    /**
     * Creates a new {@link ArgumentBoolean}.
     *
     * @see ArgumentBoolean
     */
    public static ArgumentBoolean Boolean(String id) {
        return new ArgumentBoolean(id);
    }

    /**
     * Creates a new {@link ArgumentInteger}.
     *
     * @see ArgumentInteger
     */
    public static ArgumentInteger Integer(String id) {
        return new ArgumentInteger(id);
    }

    /**
     * Creates a new {@link ArgumentDouble}.
     *
     * @see ArgumentDouble
     */
    public static ArgumentDouble Double(String id) {
        return new ArgumentDouble(id);
    }

    /**
     * Creates a new {@link ArgumentFloat}.
     *
     * @see ArgumentFloat
     */
    public static ArgumentFloat Float(String id) {
        return new ArgumentFloat(id);
    }

    /**
     * Creates a new {@link ArgumentString}.
     *
     * @see ArgumentString
     */
    public static ArgumentString String(String id) {
        return new ArgumentString(id);
    }

    /**
     * Creates a new {@link ArgumentWord}.
     *
     * @see ArgumentWord
     */
    public static ArgumentWord Word(String id) {
        return new ArgumentWord(id);
    }

    /**
     * Creates a new {@link ArgumentStringArray}.
     *
     * @see ArgumentStringArray
     */
    public static ArgumentStringArray StringArray(String id) {
        return new ArgumentStringArray(id);
    }

    /**
     * Creates a new {@link ArgumentCommand}.
     *
     * @see ArgumentCommand
     */
    public static ArgumentCommand Command(String id) {
        return new ArgumentCommand(id);
    }

    /**
     * Creates a new {@link ArgumentEnum}.
     *
     * @see ArgumentEnum
     */
    public static <E extends Enum<E>> ArgumentEnum<E> Enum(String id, Class<E> enumClass) {
        return new ArgumentEnum<>(id, enumClass);
    }

    // Minecraft specific arguments

    /**
     * Creates a new {@link ArgumentTeamColor}.
     *
     * @see ArgumentTeamColor
     */
    public static ArgumentTeamColor TeamColor(String id) {
        return new ArgumentTeamColor(id);
    }

    /**
     * Creates a new {@link ArgumentTime}.
     *
     * @see ArgumentTime
     */
    public static ArgumentTime Time(String id) {
        return new ArgumentTime(id);
    }

    /**
     * Creates a new {@link ArgumentParticle}.
     *
     * @see ArgumentParticle
     */
    public static ArgumentParticle Particle(String id) {
        return new ArgumentParticle(id);
    }

    /**
     * Creates a new {@link ArgumentResource}.
     *
     * @param id          the id of the argument
     * @param registryKey the key of a registry the client knows, such as {@code minecraft:enchantment}
     * @throws IllegalArgumentException if {@code registryKey} is not a key
     * @see ArgumentResource
     */
    public static ArgumentResource Resource(String id, String registryKey) {
        return new ArgumentResource(id, registryKey);
    }

    /**
     * Creates a new {@link ArgumentResourceLocation}.
     *
     * @see ArgumentResourceLocation
     */
    public static ArgumentResourceLocation ResourceLocation(String id) {
        return new ArgumentResourceLocation(id);
    }

    /**
     * Creates a new {@link ArgumentResourceOrTag}.
     *
     * @param id          the id of the argument
     * @param registryKey the key of a registry the client knows, such as {@code minecraft:enchantment}
     * @throws IllegalArgumentException if {@code registryKey} is not a key
     * @see ArgumentResourceOrTag
     */
    public static ArgumentResourceOrTag ResourceOrTag(String id, String registryKey) {
        return new ArgumentResourceOrTag(id, registryKey);
    }

    /**
     * Creates a new {@link ArgumentEntityType}.
     *
     * @see ArgumentEntityType
     */
    public static ArgumentEntityType EntityType(String id) {
        return new ArgumentEntityType(id);
    }

    /**
     * Creates a new {@link ArgumentBlockState}.
     *
     * @see ArgumentBlockState
     */
    public static ArgumentBlockState BlockState(String id) {
        return new ArgumentBlockState(id);
    }

    /**
     * Creates a new {@link ArgumentIntRange}.
     *
     * @see ArgumentIntRange
     */
    public static ArgumentIntRange IntRange(String id) {
        return new ArgumentIntRange(id);
    }

    /**
     * Creates a new {@link ArgumentFloatRange}.
     *
     * @see ArgumentFloatRange
     */
    public static ArgumentFloatRange FloatRange(String id) {
        return new ArgumentFloatRange(id);
    }

    /**
     * Creates a new {@link ArgumentEntity}.
     *
     * @see ArgumentEntity
     */
    public static ArgumentEntity Entity(String id) {
        return new ArgumentEntity(id);
    }

    /**
     * Creates a new {@link ArgumentItemStack}.
     *
     * @see ArgumentItemStack
     */
    public static ArgumentItemStack ItemStack(String id) {
        return new ArgumentItemStack(id);
    }

    /**
     * Creates a new {@link ArgumentComponent}.
     *
     * @see ArgumentComponent
     */
    public static ArgumentComponent Component(String id) {
        return new ArgumentComponent(id);
    }

    /**
     * Creates a new {@link ArgumentUUID}.
     *
     * @see ArgumentUUID
     */
    public static ArgumentUUID UUID(String id) {
        return new ArgumentUUID(id);
    }

    /**
     * Creates a new {@link ArgumentNbtTag}.
     *
     * @see ArgumentNbtTag
     */
    public static ArgumentNbtTag NBT(String id) {
        return new ArgumentNbtTag(id);
    }

    /**
     * Creates a new {@link ArgumentNbtCompoundTag}.
     *
     * @see ArgumentNbtCompoundTag
     */
    public static ArgumentNbtCompoundTag NbtCompound(String id) {
        return new ArgumentNbtCompoundTag(id);
    }

    /**
     * Creates a new {@link ArgumentRelativeBlockPosition}.
     *
     * @see ArgumentRelativeBlockPosition
     */
    public static ArgumentRelativeBlockPosition RelativeBlockPosition(String id) {
        return new ArgumentRelativeBlockPosition(id);
    }

    /**
     * Creates a new {@link ArgumentRelativeVec3}.
     *
     * @see ArgumentRelativeVec3
     */
    public static ArgumentRelativeVec3 RelativeVec3(String id) {
        return new ArgumentRelativeVec3(id);
    }

    /**
     * Creates a new {@link ArgumentRelativeVec2}.
     *
     * @see ArgumentRelativeVec2
     */
    public static ArgumentRelativeVec2 RelativeVec2(String id) {
        return new ArgumentRelativeVec2(id);
    }

    /**
     * Generates arguments from a string format.
     * <p>
     * Example: "Entity&lt;targets&gt; Integer&lt;number&gt;"
     * <p>
     * Note: this feature is in beta and is very likely to change depending on feedback.
     */
    @ApiStatus.Experimental
    public static Argument<?>[] generate(String format) {
        return ArgumentParser.generate(format);
    }

    /**
     * Creates a new {@link ArgumentLong}.
     *
     * @see ArgumentLong
     */
    public static ArgumentLong Long(String id) {
        return new ArgumentLong(id);
    }

    /**
     * Creates a new {@link ArgumentEntity}.
     *
     * @see ArgumentEntity
     * @deprecated use {@link #Entity(String)}
     */
    @Deprecated
    public static ArgumentEntity Entities(String id) {
        return new ArgumentEntity(id);
    }
}
