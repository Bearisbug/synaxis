package io.github.bearisbug.synaxis.baker;

import org.jetbrains.annotations.Nullable;

/**
 * Supplies the presets of {@code --source generator:<preset>:<seed>}. The baker finds providers with
 * {@link java.util.ServiceLoader}, so a game module offers its generators without the baker depending on
 * it at compile time ({@code docs/DESIGN-M3.md} §6). List the class in
 * {@code META-INF/services/io.github.bearisbug.synaxis.baker.BakeGeneratorProvider}, with a public no-argument
 * constructor, and add its module to {@code baker/build.gradle.kts} as {@code runtimeOnly}. An id holds no
 * {@code ':'} (the command line splits {@code generator:<preset>:<seed>} on it) and belongs to one provider: the
 * first provider on the class path that returns a preset wins. What a generator must be is on
 * {@link GeneratorPreset}.
 */
public interface BakeGeneratorProvider {
    /**
     * Returns the preset named {@code id}, or null when this provider has none.
     *
     * @return the preset named {@code id} generated from {@code seed}, or null when this provider has no such preset
     */
    @Nullable GeneratorPreset preset(String id, long seed);
}
