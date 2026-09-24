package io.github.bearisbug.synaxis.baker;

import org.jetbrains.annotations.Nullable;

/**
 * Supplies the presets of {@code --source generator:<preset>:<seed>}. The baker finds providers with
 * {@link java.util.ServiceLoader}, so a game module offers its generators without the baker depending on
 * it ({@code docs/DESIGN-M3.md} §6).
 */
public interface BakeGeneratorProvider {
    /**
     * Returns the preset named {@code id}, or null when this provider has none.
     *
     * @return the preset named {@code id} generated from {@code seed}, or null when this provider has no such preset
     */
    @Nullable GeneratorPreset preset(String id, long seed);
}
