package io.github.bearisbug.synaxis.registry;

import net.kyori.adventure.key.Key;

record TagKeyImpl<T>(Key key) implements TagKey<T> {
}
