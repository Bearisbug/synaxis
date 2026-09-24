package io.github.bearisbug.synaxis.app.metrics;

import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.app.store.RedisLink;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/DESIGN-PROD.md} §24: the names of {@code API-PROD-015} are a constant, and the
 * registry holds exactly those {@code synaxis_*} families, one more or one fewer fails.
 */
public class MetricsNamesTest {

    private static final Pattern TYPE = Pattern.compile("^# TYPE (\\S+) (\\S+)$", Pattern.MULTILINE);

    @Test
    public void theRegistryHoldsExactlyTheNamesOfTheDesign() {
        final ServerProcess process = ServerProcess.of(ServerConfig.builder().insideTest(true).build());
        try (HostMetrics metrics = HostMetrics.register(process, new HostMetrics.Sources(() -> 0,
                PlayerStore.inMemory(process, (_, _) -> {
                }), null, null, RedisLink.disabled("names")))) {
            final String text = metrics.scrape();
            final Set<String> families = families(text);
            assertEquals(new TreeSet<>(HostMetrics.NAMES), families, "the synaxis_* families of the registry");
            assertTrue(text.contains("\njvm_memory_used_bytes{"), "jvm_* from Micrometer's binders");
            assertTrue(text.contains("\njvm_gc_"), "GC from Micrometer's binders");
            assertTrue(text.contains("\nprocess_cpu_usage "), "process_* from Micrometer's binders");
        } finally {
            process.stop();
        }
    }

    /**
     * The {@code synaxis_} families of a Prometheus text: a counter under its {@code _total} name,
     * the {@code _max} gauge Micrometer adds to a summary folded into the summary.
     */
    static Set<String> families(String text) {
        final Set<String> families = new TreeSet<>();
        final Matcher type = TYPE.matcher(text);
        while (type.find()) {
            final String name = type.group(1);
            if (!name.startsWith("synaxis_")) continue;
            final String kind = type.group(2);
            final boolean maxOfSummary = kind.equals("gauge") && name.endsWith("_max")
                    && text.contains("# TYPE " + name.substring(0, name.length() - 4) + " summary");
            if (kind.equals("counter")) families.add(name.endsWith("_total") ? name : name + "_total");
            else if (!maxOfSummary) families.add(name);
        }
        return families;
    }
}
