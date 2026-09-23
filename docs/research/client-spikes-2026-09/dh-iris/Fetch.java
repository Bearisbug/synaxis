import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * Downloads the third-party jars and the shader pack of the DH / Iris spike inside the container:
 * {@code java Fetch.java <dh|iris|dh-iris> <dir>} writes {@code <dir>/mods} (handed to Fabric Loader
 * through {@code -Dfabric.addMods}) and {@code <dir>/shaderpacks} (copied into the game directory by
 * the spike itself). Loom clears the gametest run directory before each run, so nothing can be put
 * there beforehand. Every file is pinned by SHA-1; none of them is ever committed.
 */
public class Fetch {

    record Item(String set, String dir, String name, String url, String sha1) {}

    static final List<Item> ITEMS = List.of(
            new Item("dh", "mods", "DistantHorizons-3.3.2-26.2-fabric-neoforge.jar",
                    "https://cdn.modrinth.com/data/uCdwusMi/versions/9NoftEde/DistantHorizons-3.3.2-26.2-fabric-neoforge.jar",
                    "51cdbdd9d25b1f27c3825bfc405ca6c49b864889"),
            new Item("iris", "mods", "sodium-fabric-0.9.2+mc26.2.jar",
                    "https://cdn.modrinth.com/data/AANobbMI/versions/xJZxADzI/sodium-fabric-0.9.2%2Bmc26.2.jar",
                    "62a70961a5b3e06938e06ab5f2dc4c5a51cf3323"),
            new Item("iris", "mods", "iris-fabric-1.11.4+mc26.2.jar",
                    "https://cdn.modrinth.com/data/YL57xq9U/versions/gxZWWnKH/iris-fabric-1.11.4%2Bmc26.2.jar",
                    "bd0b24731a94b8fe164531e6345718d30ab08ed2"),
            new Item("iris", "shaderpacks", "ComplementaryReimagined_r5.9.3.zip",
                    "https://cdn.modrinth.com/data/HVnmMxH1/versions/Bqen1mJX/ComplementaryReimagined_r5.9.3.zip",
                    "838139b54cddb56b2e83cd260d8efd960ac536d6"));

    public static void main(String[] args) throws Exception {
        final List<String> sets = List.of(args[0].split("-"));
        final Path run = Path.of(args[1]);
        Files.createDirectories(run.resolve("mods"));
        Files.createDirectories(run.resolve("shaderpacks"));
        final HttpClient http = HttpClient.newBuilder().proxy(ProxySelector.getDefault())
                .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30)).build();
        for (Item item : ITEMS) {
            if (!sets.contains(item.set())) continue;
            final Path target = run.resolve(item.dir()).resolve(item.name());
            Files.createDirectories(target.getParent());
            byte[] body = null;
            for (int attempt = 1; body == null; attempt++) {
                try {
                    body = http.send(HttpRequest.newBuilder(URI.create(item.url())).header("User-Agent", "synaxis-spike")
                            .build(), HttpResponse.BodyHandlers.ofByteArray()).body();
                } catch (IOException e) {
                    if (attempt == 6) throw e;
                    System.out.println("retry " + item.name() + ": " + e);
                }
            }
            final String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(body));
            if (!sha1.equals(item.sha1())) throw new IllegalStateException(item.name() + " sha1 " + sha1);
            Files.write(target, body);
            System.out.println("fetched " + target + " " + body.length + " B");
        }
    }
}
