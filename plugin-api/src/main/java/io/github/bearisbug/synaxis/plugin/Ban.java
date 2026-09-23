package io.github.bearisbug.synaxis.plugin;

import org.jetbrains.annotations.Nullable;

import java.time.Instant;

/**
 * A ban in force ({@code docs/DESIGN-PROD.md} API-PROD-009, table {@code ban}).
 *
 * @param banId     the id {@link Moderation#unban} takes
 * @param target    who it applies to
 * @param reason    what the banned player is shown, at most 256 characters
 * @param expiresAt when it stops applying, null for never
 */
public record Ban(long banId, BanTarget target, String reason, @Nullable Instant expiresAt) {
}
