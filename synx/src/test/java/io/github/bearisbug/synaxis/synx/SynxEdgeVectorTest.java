package io.github.bearisbug.synaxis.synx;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The damaged and incompatible vectors under {@code vectors/edge/} (REQ-M3-001, §19
 * 「损坏与不兼容的区域文件」). A damaged record fails its own chunk only; an incompatible file is
 * refused as a whole with the error code of {@code docs/DESIGN-M3.md} §14.
 */
public class SynxEdgeVectorTest {

    private static Path edge(String name) {
        return SynxVectors.DIR.resolve("edge").resolve(name + ".synx");
    }

    @Test
    public void fileEndingInsideTheIndexIsRefused() {
        try (Arena arena = Arena.ofConfined()) {
            final SynxException refused = assertThrows(SynxException.class,
                    () -> SynxRegion.open(edge("truncated-index"), arena));
            assertEquals(SynxError.TRUNCATED, refused.error());
            assertTrue(refused.getMessage().startsWith("synx-truncated: truncated-index.synx"), refused.getMessage());
        }
    }

    @Test
    public void fileEndingInsideARecordFailsTheCutChunksAndThePayloadHash() throws Exception {
        try (Arena arena = Arena.ofConfined()) {
            final SynxRegion region = SynxRegion.open(edge("truncated-records"), arena);
            assertInstanceOf(SynxSlot.Present.class, region.slot(-2, 0));
            assertInstanceOf(SynxSlot.Present.class, region.slot(-1, 0));
            assertFault(SynxFault.OFFSET_OUT_OF_RANGE, region.slot(-2, 1));
            assertFault(SynxFault.OFFSET_OUT_OF_RANGE, region.slot(-1, 1));
            final SynxException refused = assertThrows(SynxException.class, region::verifyPayload);
            assertEquals(SynxError.PAYLOAD_HASH, refused.error());
        }
    }

    @Test
    public void indexEntryPastTheEndFailsThatChunkOnly() throws Exception {
        try (Arena arena = Arena.ofConfined()) {
            final SynxRegion region = SynxRegion.open(edge("offset-out-of-range"), arena);
            assertFault(SynxFault.OFFSET_OUT_OF_RANGE, region.slot(-1, 1));
            for (int[] chunk : new int[][]{{-2, 0}, {-1, 0}, {-2, 1}}) {
                final SynxSlot.Present present = assertInstanceOf(SynxSlot.Present.class, region.slot(chunk[0], chunk[1]));
                assertEquals(chunk[0], present.record().chunkX());
            }
            // The index is not covered by payload_sha256, the per record checks are what catch it
            region.verifyPayload();
            region.checkAgainst(SynxVectors.expectations());
        }
    }

    @Test
    public void flippedRecordByteFailsThatChunkOnly() throws Exception {
        try (Arena arena = Arena.ofConfined()) {
            final SynxRegion region = SynxRegion.open(edge("crc-mismatch"), arena);
            assertFault(SynxFault.CRC_MISMATCH, region.slot(-2, 1));
            assertInstanceOf(SynxSlot.Present.class, region.slot(-2, 0));
            assertInstanceOf(SynxSlot.Present.class, region.slot(-1, 0));
            assertInstanceOf(SynxSlot.Present.class, region.slot(-1, 1));
            assertEquals(SynxError.PAYLOAD_HASH, assertThrows(SynxException.class, region::verifyPayload).error());
        }
    }

    @Test
    public void payloadHashThatDoesNotMatchTheBytesIsRefused() throws Exception {
        try (Arena arena = Arena.ofConfined()) {
            final SynxRegion region = SynxRegion.open(edge("payload-hash"), arena);
            assertInstanceOf(SynxSlot.Present.class, region.slot(5, 7));
            final SynxException refused = assertThrows(SynxException.class, region::verifyPayload);
            assertEquals(SynxError.PAYLOAD_HASH, refused.error());
            assertTrue(refused.getMessage().startsWith("synx-payload-hash: payload-hash.synx"), refused.getMessage());
        }
    }

    @Test
    public void otherProtocolVersionIsRefused() throws Exception {
        assertRefusedAtStart("protocol-775", SynxError.PROTOCOL_MISMATCH);
    }

    @Test
    public void otherRegistryDigestIsRefused() throws Exception {
        assertRefusedAtStart("registry-mismatch", SynxError.REGISTRY_MISMATCH);
    }

    @Test
    public void unknownFlagIsRefusedOnOpen() {
        try (Arena arena = Arena.ofConfined()) {
            final SynxException refused = assertThrows(SynxException.class,
                    () -> SynxRegion.open(edge("unknown-flag"), arena));
            assertEquals(SynxError.MAGIC, refused.error());
        }
    }

    private static void assertRefusedAtStart(String name, SynxError error) throws Exception {
        try (Arena arena = Arena.ofConfined()) {
            final SynxRegion region = SynxRegion.open(edge(name), arena);
            final SynxException refused = assertThrows(SynxException.class,
                    () -> region.checkAgainst(SynxVectors.expectations()));
            assertEquals(error, refused.error());
            assertTrue(refused.getMessage().startsWith(error.code() + ": " + name + ".synx"), refused.getMessage());
        }
    }

    private static void assertFault(SynxFault fault, SynxSlot slot) {
        assertEquals(fault, assertInstanceOf(SynxSlot.Failed.class, slot).fault());
    }
}
