package io.github.bearisbug.synaxis.game.client.farview.dh;

import io.github.bearisbug.synaxis.game.client.farview.FarViewClient;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Distant Horizons surface of {@code docs/DESIGN-M3.md} {@code API-M3-015} (DH 接入面) against the
 * very jar this mod compiles against ({@code build/dh/}, on this test's classpath): every DH core
 * member the bake uses is there with its signature, the start-up probe reads the same list, the
 * {@code FullData} table has the two columns the counts read, and the jar is the pinned version. A
 * DH upgrade turns this red before anything runs.
 */
class DhSurfaceTest {

    private static final ClassLoader LOADER = DhSurfaceTest.class.getClassLoader();

    @Test
    void everyMemberOfTheSurfaceIsInTheJarWithItsSignature() {
        assertEquals(List.of(), DhFarView.surfaceProblems(DhFarView.SURFACE, LOADER));
    }

    @Test
    void theSurfaceIsTheDesignedRows() {
        assertEquals(List.of(
                "FullDataSourceV2#createEmpty", "FullDataSourceV2#close", "FullDataSourceV2#applyToParent",
                "FullDataSourceV2 is a IDhApiFullDataSource", "DhSectionPos#encode", "ILevelWrapper is a IDhApiLevelWrapper",
                "ILevelWrapper#getDhLevel", "IDhLevel#updateDataSourcesAsync", "IDhLevel#getFullDataProvider",
                "FullDataSourceProviderV2#repo", "FullDataSourceV2Repo#queryDictionaryFirst", "FullDataSourceProviderV2#get"),
                DhFarView.SURFACE.stream().map(member -> member.kind() == DhFarView.Kind.SUPERTYPE
                        ? simple(member.owner()) + " is a " + simple(member.type())
                        : simple(member.owner()) + "#" + member.name()).toList());
    }

    private static String simple(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    @Test
    void theProbeSeesAMemberThatIsNotThere() {
        final List<DhFarView.Member> wrong = List.of(
                new DhFarView.Member("com.seibel.distanthorizons.core.pos.DhSectionPos", DhFarView.Kind.STATIC_METHOD, "encode",
                        List.of("byte", "int", "int"), "int"),
                new DhFarView.Member("com.seibel.distanthorizons.core.pos.DhSectionPos", DhFarView.Kind.METHOD, "encode",
                        List.of("byte", "int", "int"), "long"),
                new DhFarView.Member("com.seibel.distanthorizons.core.level.IDhLevel", DhFarView.Kind.METHOD,
                        "updateDataSourcesAsyncRenamed", List.of(), "void"),
                new DhFarView.Member("com.seibel.distanthorizons.core.NoSuchClass", DhFarView.Kind.FIELD, "x", List.of(), "int"),
                new DhFarView.Member("com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2",
                        DhFarView.Kind.FIELD, "applyToParent", List.of(), "boolean"));
        final List<String> problems = DhFarView.surfaceProblems(wrong, LOADER);
        assertEquals(5, problems.size(), problems.toString());
        assertTrue(problems.get(0).endsWith("is long"), problems.get(0));
        assertTrue(problems.get(1).endsWith("is static"), problems.get(1));
        assertTrue(problems.get(2).contains("is missing"), problems.get(2));
        assertTrue(problems.get(3).contains("is missing"), problems.get(3));
        assertTrue(problems.get(4).endsWith("is java.lang.Boolean"), problems.get(4));
    }

    @Test
    void theFullDataTableHasTheTwoColumnsTheCountsRead() throws IOException {
        final String script = resource("sqlScripts/0020-sqlite-createFullDataSourceV2Tables.sql");
        final Matcher table = Pattern.compile("CREATE TABLE FullData \\((.*?)\\n\\);", Pattern.DOTALL).matcher(script);
        assertTrue(table.find(), "sqlScripts/0020 creates no FullData table");
        assertTrue(Pattern.compile("DetailLevel TINYINT NOT NULL").matcher(table.group(1)).find(), table.group(1));
        assertTrue(Pattern.compile(",ApplyToParent BIT NULL").matcher(table.group(1)).find(), table.group(1));
    }

    @Test
    void theJarIsThePinnedVersion() throws IOException {
        final String descriptor = resource("fabric.mod.json");
        final Matcher id = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(descriptor);
        final Matcher version = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"").matcher(descriptor);
        assertTrue(id.find() && version.find(), descriptor);
        assertEquals(FarViewClient.DH_MOD_ID, id.group(1));
        assertEquals(FarViewClient.DH_VERSION, version.group(1));
    }

    /** A resource of the DH jar, which is the only jar on this classpath with these names under that path. */
    private static String resource(String name) throws IOException {
        final String jarSuffix = "DistantHorizons-3.3.2-26.2-fabric-neoforge.jar!/" + name;
        for (var urls = LOADER.getResources(name); urls.hasMoreElements(); ) {
            final var url = urls.nextElement();
            if (!url.toString().endsWith(jarSuffix)) continue;
            try (InputStream in = url.openStream()) {
                return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException(name + " of the DH jar is not on the test classpath");
    }
}
