package io.github.bearisbug.synaxis.synx;

import java.io.Serial;

/**
 * A map release a shard must not start on. The message leads with the {@link SynxError#code()}
 * and names the offending file, which is what the start-up log shows ({@code docs/DESIGN-M3.md} §14).
 */
public final class SynxException extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    private final SynxError error;

    public SynxException(SynxError error, String file, String detail) {
        super(error.code() + ": " + file + ": " + detail);
        this.error = error;
    }

    public SynxError error() {
        return error;
    }
}
