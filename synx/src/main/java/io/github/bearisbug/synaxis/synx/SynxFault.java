package io.github.bearisbug.synaxis.synx;

/**
 * Why a single chunk record cannot be read. A faulty record fails its chunk only: the chunk goes to
 * {@code failed} ({@code docs/DESIGN-M3.md} §11.1) and the rest of the file stays readable.
 */
public enum SynxFault {
    /**
     * The index entry points before the record area, off the 8 byte grid or past the end of the file, or
     * names a record shorter than its segment length table.
     */
    OFFSET_OUT_OF_RANGE,
    /** The record bytes do not match the {@code crc32c} of the index entry. */
    CRC_MISMATCH,
    /** The segment length table disagrees with the record length or with the fixed segment sizes. */
    MALFORMED
}
