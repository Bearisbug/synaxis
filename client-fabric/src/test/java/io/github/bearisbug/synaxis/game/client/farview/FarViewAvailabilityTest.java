package io.github.bearisbug.synaxis.game.client.farview;

import io.github.bearisbug.synaxis.game.protocol.FarViewAnnouncement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Availability of {@code docs/DESIGN-M3.md} {@code API-M3-015} (可用性), the unit half of the §19
 * {@code REQ-M3-025} scenario {@code DH 缺席或版本不符时只有原版视距}: DH 3.3.1, API 8, or one surface
 * member short is no far view, and such a DH is asked its version and nothing else.
 */
class FarViewAvailabilityTest {

    /** A DH side that says what it is told to and writes down every call. */
    private static final class FakeDh implements FarViewDh {
        final List<String> calls = new ArrayList<>();
        final String version;
        final int major;
        final List<String> problems;

        FakeDh(String version, int major, List<String> problems) {
            this.version = version;
            this.major = major;
            this.problems = problems;
        }

        @Override
        public String modVersion() {
            calls.add("modVersion");
            return version;
        }

        @Override
        public int apiMajorVersion() {
            calls.add("apiMajorVersion");
            return major;
        }

        @Override
        public List<String> surfaceProblems() {
            calls.add("surfaceProblems");
            return problems;
        }

        @Override
        public void install() {
            calls.add("install");
        }
    }

    private static final FarViewAnnouncement ANNOUNCEMENT = new FarViewAnnouncement("b", 2, 4, -64, 256,
            List.of(new FarViewAnnouncement.Tile(0, 0, 0, 1, new byte[32], "http://cdn.test/a.hfp")));

    @Test
    void withoutDistantHorizonsNothingIsAsked() {
        final List<String> asked = new ArrayList<>();
        assertEquals(FarViewClient.Gate.ABSENT, FarViewClient.gate(false, () -> {
            asked.add("loaded");
            return new FakeDh("3.3.2", 7, List.of());
        }));
        assertEquals(List.of(), asked, "DH's side is not even loaded");
    }

    @Test
    void anotherVersionIsAskedItsVersionAndNothingElse() {
        final FakeDh dh = new FakeDh("3.3.1", 7, List.of());
        assertEquals(FarViewClient.Gate.UNSUPPORTED, FarViewClient.gate(true, () -> dh));
        assertEquals(List.of("modVersion"), dh.calls);
    }

    @Test
    void anotherApiMajorVersionIsAskedTheVersionsAndNothingElse() {
        final FakeDh dh = new FakeDh("3.3.2", 8, List.of());
        assertEquals(FarViewClient.Gate.UNSUPPORTED, FarViewClient.gate(true, () -> dh));
        assertEquals(List.of("modVersion", "apiMajorVersion"), dh.calls);
    }

    @Test
    void aSurfaceShortOfOneMemberIsNotInstalled() {
        final FakeDh dh = new FakeDh("3.3.2", 7,
                List.of("com.seibel.distanthorizons.core.level.IDhLevel#updateDataSourcesAsync is missing"));
        assertEquals(FarViewClient.Gate.UNSUPPORTED, FarViewClient.gate(true, () -> dh));
        assertEquals(List.of("modVersion", "apiMajorVersion", "surfaceProblems"), dh.calls, "the surface is probed by reflection");
    }

    @Test
    void theSupportedVersionIsInstalledOnce() {
        final FakeDh dh = new FakeDh("3.3.2", 7, List.of());
        assertEquals(FarViewClient.Gate.SUPPORTED, FarViewClient.gate(true, () -> dh));
        assertEquals(List.of("modVersion", "apiMajorVersion", "surfaceProblems", "install"), dh.calls);
    }

    @Test
    void aDistantHorizonsThatCannotBeLoadedIsUnsupported() {
        assertEquals(FarViewClient.Gate.UNSUPPORTED, FarViewClient.gate(true, () -> {
            throw new IllegalStateException("NoClassDefFoundError");
        }));
    }

    @Test
    void aConnectionHasAFarViewOnlyWithSupportARadiusAndAnAnnouncement() {
        final FarViewSettings on = new FarViewSettings(4096);
        assertTrue(FarViewClient.available(FarViewClient.Gate.SUPPORTED, on, ANNOUNCEMENT));
        assertTrue(FarViewClient.available(FarViewClient.Gate.SUPPORTED, new FarViewSettings(2048), ANNOUNCEMENT));
        assertFalse(FarViewClient.available(FarViewClient.Gate.SUPPORTED, new FarViewSettings(0), ANNOUNCEMENT));
        assertFalse(FarViewClient.available(FarViewClient.Gate.SUPPORTED, on, null));
        assertFalse(FarViewClient.available(FarViewClient.Gate.UNSUPPORTED, on, ANNOUNCEMENT));
        assertFalse(FarViewClient.available(FarViewClient.Gate.ABSENT, on, ANNOUNCEMENT));
    }

    @Test
    void theSettingFileIsWrittenOnceAndReadEachTime(@TempDir Path config) throws IOException {
        assertEquals(4096, FarViewSettings.load(config).radius());
        final Path file = config.resolve(FarViewSettings.FILE);
        assertTrue(Files.readString(file).contains("farview.radius=4096"), "the default file is written with its comments");
        for (String value : List.of("2048", "0", "4096", "1000", "x")) {
            Files.writeString(file, "farview.radius=" + value + "\n");
            final int expected = value.equals("2048") ? 2048 : value.equals("0") ? 0 : 4096;
            assertEquals(expected, FarViewSettings.load(config).radius(), value);
        }
        assertEquals(256, new FarViewSettings(4096).chunkRenderDistance());
        assertEquals(128, new FarViewSettings(2048).chunkRenderDistance());
    }
}
