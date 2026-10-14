package io.github.bearisbug.synaxis.baker;

import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.synx.SynxDigests;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static io.github.bearisbug.synaxis.baker.BakeTesting.REGISTRIES;
import static io.github.bearisbug.synaxis.baker.BakeTesting.list;
import static io.github.bearisbug.synaxis.baker.BakeTesting.request;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code farview-export} ({@code docs/DESIGN-M3.md} {@code API-M3-003}, {@code API-M3-014} 分发; §19
 * 「farview-export 按内容摘要导出」) on a release of four tiles: every pack as two objects named by their
 * SHA-256, the whole pack and its coarse prefix.
 */
public class FarViewExportTest {
    @TempDir
    Path temp;

    private record Run(int exitCode, List<String> out, String err) {
    }

    private static Run run(String... args) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
        final PrintStream stdout = System.out, stderr = System.err;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            final int exit = BakerMain.run(args);
            return new Run(exit, out.toString(StandardCharsets.UTF_8).lines().toList(), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(stdout);
            System.setErr(stderr);
        }
    }

    private Path release() {
        final Baker.Result result = Baker.bake(request("export", new GeneratorSource(new GeneratorPreset(
                unit -> unit.modifier().fillHeight(0, 40, Block.STONE), 0, 64, new SynxManifest.Bounds(250, -8, 262, 7)),
                REGISTRIES), temp, 2));
        assertEquals(Baker.EXIT_PUBLISHED, result.exitCode(), result.message());
        return result.directory();
    }

    private static SynxManifest manifest(Path build) throws Exception {
        return SynxManifest.fromJson(Files.readString(build.resolve(SynxManifest.FILE_NAME)));
    }

    /** The manifest of a release with another {@code farview[]}. */
    private static void rewrite(Path build, List<SynxManifest.FarView> farview) throws Exception {
        final SynxManifest manifest = manifest(build);
        Files.writeString(build.resolve(SynxManifest.FILE_NAME), new SynxManifest(manifest.mapId(), manifest.buildId(),
                manifest.contentHash(), manifest.mcVersion(), manifest.protocolVersion(), manifest.registryDigest(),
                manifest.lightEngineVersion(), manifest.bakerVersion(), manifest.world(), manifest.bounds(), manifest.regions(),
                farview, manifest.source(), manifest.bake(), manifest.verify()).toJson());
    }

    @Test
    public void theFirstExportWritesTwoObjectsAPackAndTheSecondKeepsThem() throws Exception {
        final Path build = release();
        final Path out = temp.resolve("cdn");
        final SynxManifest manifest = manifest(build);
        assertEquals(4, manifest.farview().size());

        final Run first = run("farview-export", "--build", build.toString(), "--out", out.toString());
        assertEquals(Baker.EXIT_PUBLISHED, first.exitCode(), first.err());
        final List<String> exported = new ArrayList<>(), kept = new ArrayList<>();
        for (SynxManifest.FarView entry : manifest.farview()) {
            final String file = "t." + entry.tileX() + "." + entry.tileZ() + ".hfp";
            final byte[] pack = Files.readAllBytes(build.resolve("farview").resolve(file));
            final String whole = "export/farview/" + SynxDigests.hex(entry.sha256()) + ".hfp";
            final String coarse = "export/farview/" + SynxDigests.hex(entry.coarseSha256()) + ".hfp";
            assertArrayEquals(pack, Files.readAllBytes(out.resolve(whole)), whole);
            assertArrayEquals(Arrays.copyOf(pack, (int) entry.coarseBytes()), Files.readAllBytes(out.resolve(coarse)), coarse);
            if (out.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                for (String object : List.of(whole, coarse)) {
                    assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(out.resolve(object))),
                            object + " is readable by all, as the files of a release");
                }
            }
            exported.add("exported " + file + " -> " + whole + " (" + pack.length + " B)");
            exported.add("exported " + file + " coarse -> " + coarse + " (" + entry.coarseBytes() + " B)");
            kept.add("kept " + file + " -> " + whole + " (" + pack.length + " B)");
            kept.add("kept " + file + " coarse -> " + coarse + " (" + entry.coarseBytes() + " B)");
        }
        assertEquals(exported, first.out());
        assertEquals(8, list(out.resolve("export").resolve("farview")).size(), "nothing else in there");

        final Run second = run("farview-export", "--build", build.toString(), "--out", out.toString());
        assertEquals(Baker.EXIT_PUBLISHED, second.exitCode(), second.err());
        assertEquals(kept, second.out());
    }

    /** An object already exported with other bytes refuses the export, naming the object. */
    @Test
    public void anExportedObjectWithOtherBytesExitsFour() throws Exception {
        final Path build = release();
        final Path out = temp.resolve("cdn");
        assertEquals(Baker.EXIT_PUBLISHED, run("farview-export", "--build", build.toString(), "--out", out.toString()).exitCode());
        final SynxManifest.FarView entry = manifest(build).farview().get(1);
        final String coarse = "export/farview/" + SynxDigests.hex(entry.coarseSha256()) + ".hfp";
        flip(out.resolve(coarse));

        final Run again = run("farview-export", "--build", build.toString(), "--out", out.toString());
        assertEquals(Baker.EXIT_VERIFY, again.exitCode());
        assertTrue(again.err().contains(coarse + " is already there with other bytes"), again.err());
    }

    /** A pack of the release that does not match its manifest entry is not exported at all. */
    @Test
    public void aPackThatDoesNotMatchItsManifestExitsFourBeforeAnythingIsWritten() throws Exception {
        final Path build = release();
        flip(build.resolve("farview").resolve("t.1.0.hfp"));
        final Path out = temp.resolve("cdn");

        final Run run = run("farview-export", "--build", build.toString(), "--out", out.toString());
        assertEquals(Baker.EXIT_VERIFY, run.exitCode());
        assertTrue(run.err().contains("farview/t.1.0.hfp does not have the size and SHA-256 of the manifest"), run.err());
        assertEquals(List.of(), list(out));
    }

    /**
     * A coarse_bytes below 0 in the manifest makes no prefix of the pack: the coarse prefix does not match the
     * manifest, exit code 4, nothing written.
     */
    @Test
    public void aNegativeCoarseBytesExitsFourBeforeAnythingIsWritten() throws Exception {
        final Path build = release();
        final List<SynxManifest.FarView> farview = new ArrayList<>(manifest(build).farview());
        final SynxManifest.FarView entry = farview.get(2);
        farview.set(2, new SynxManifest.FarView(entry.tileX(), entry.tileZ(), entry.baseLevel(), entry.coarseLevel(),
                entry.bytes(), entry.sha256(), -1, entry.coarseSha256()));
        rewrite(build, farview);
        final Path out = temp.resolve("cdn");

        final Run run = run("farview-export", "--build", build.toString(), "--out", out.toString());
        assertEquals(Baker.EXIT_VERIFY, run.exitCode(), run.err());
        assertTrue(run.err().contains("the coarse prefix of farview/t." + entry.tileX() + "." + entry.tileZ()
                + ".hfp does not have the coarse_bytes and coarse_sha256 of the manifest"), run.err());
        assertEquals(List.of(), list(out));
    }

    /**
     * A release whose {@code farview[]} is empty, as those before baker_version 2 are, has nothing to export: one
     * line, exit code 0, no directory.
     */
    @Test
    public void aReleaseWithoutPacksExportsNothingAndSaysSo() throws Exception {
        final Path build = release();
        rewrite(build, List.of());
        final Path out = temp.resolve("cdn");

        final Run run = run("farview-export", "--build", build.toString(), "--out", out.toString());
        assertEquals(Baker.EXIT_PUBLISHED, run.exitCode(), run.err());
        assertEquals(List.of("baker: " + build + " lists no far view packs (baker_version 2), nothing to export"), run.out());
        assertFalse(Files.exists(out));
    }

    @Test
    public void anExportWithoutItsOutputDirectoryExitsOne() throws Exception {
        assertEquals(BakerMain.EXIT_USAGE, run("farview-export", "--build", temp.toString()).exitCode());
    }

    private static void flip(Path file) throws IOException {
        final byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(file, bytes);
    }
}
