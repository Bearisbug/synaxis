package io.github.bearisbug.synaxis.instance.generator;

@FunctionalInterface
public interface Generator {
    /**
     * This method is called when this generator is requesting this unit to be filled with blocks or biomes.
     * <p>
     * It is called on virtual threads, for different chunks of the same instance at the same time, so
     * an implementation is thread safe: no mutable state such as a {@code HashMap} or a {@code Random}
     * is shared between calls, and randomness is derived from the seed and the coordinates of the unit.
     * The same unit is never handed to two threads at once. The unit and its forks are valid during this
     * call only: what is written or forked once it returned is not applied. The call does not wait for
     * other chunks of the same instance to load. An exception it throws goes to the exception handler of
     * the process, and the chunk loads anyway with what was written before, without a retry.
     *
     * @param unit the unit to fill
     */
    void generate(GenerationUnit unit);
}
