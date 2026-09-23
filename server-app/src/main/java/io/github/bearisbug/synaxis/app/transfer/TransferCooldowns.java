package io.github.bearisbug.synaxis.app.transfer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The per player transfer cooldown of {@code docs/DESIGN-PROD.md} {@code API-PROD-021} and
 * {@code API-PROD-028}: {@code synaxis.app.transfer-cooldown-ms}, 5,000 by default, counted from
 * the last transfer out of this shard or from the {@code issued_at} of the ticket that brought the
 * player in, so a round trip A → B → A is bound by it too.
 */
public final class TransferCooldowns {

    static final String PROPERTY = "synaxis.app.transfer-cooldown-ms";
    static final long DEFAULT_MILLIS = 5_000;

    private final long cooldownMillis;
    private final LongSupplier clock;
    private final Map<UUID, Long> lastTransfer = new ConcurrentHashMap<>();

    /**
     * @param cooldownMillis the cooldown
     * @param clock          Unix milliseconds
     */
    public TransferCooldowns(long cooldownMillis, LongSupplier clock) {
        this.cooldownMillis = cooldownMillis;
        this.clock = clock;
    }

    /**
     * The cooldown of the process, {@value #PROPERTY}.
     *
     * @return the cooldowns on the system clock
     * @throws IllegalArgumentException if the property is not a number of milliseconds ≥ 0
     */
    public static TransferCooldowns fromSystemProperties() {
        final String value = System.getProperty(PROPERTY);
        long millis = DEFAULT_MILLIS;
        if (value != null) {
            try {
                millis = Long.parseLong(value.strip());
            } catch (NumberFormatException _) {
                millis = -1;
            }
            if (millis < 0) throw new IllegalArgumentException(PROPERTY + "=" + value + " is not a number of milliseconds");
        }
        return new TransferCooldowns(millis, System::currentTimeMillis);
    }

    /**
     * Whether the player is still cooling down from its last transfer.
     *
     * @param account the account
     * @return true if a transfer now is refused
     */
    boolean coolingDown(UUID account) {
        final Long last = lastTransfer.get(account);
        if (last == null) return false;
        if (clock.getAsLong() - last < cooldownMillis) return true;
        lastTransfer.remove(account, last);
        return false;
    }

    /**
     * Records a transfer of the player, out of here or into here.
     *
     * @param account the account
     * @param at      when, Unix milliseconds: now for a transfer out, the ticket's {@code issued_at} in
     */
    void record(UUID account, long at) {
        lastTransfer.merge(account, at, Math::max);
    }

    long now() {
        return clock.getAsLong();
    }
}
