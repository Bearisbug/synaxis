import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The race of PlayableClientGameTest#since: the gametest thread reads the lines since a mark while
 * the client thread adds one. `java SinceSnapshotProof.java view` is the view of the live list the
 * script had, `java SinceSnapshotProof.java snapshot` the snapshot it reads now.
 * <p>
 * Deterministic part: the client thread's add is put between taking the sub list and streaming it,
 * which is where it lands when the race goes wrong. Stress part: two real threads for 2 s, counting
 * the reads that threw.
 */
public class SinceSnapshotProof {

    static final List<String> MESSAGES = new CopyOnWriteArrayList<>();

    static List<String> since(String form, int mark) {
        if (form.equals("view")) return MESSAGES.subList(Math.min(mark, MESSAGES.size()), MESSAGES.size());
        final List<String> messages = List.copyOf(MESSAGES);
        return messages.subList(Math.min(mark, messages.size()), messages.size());
    }

    public static void main(String[] args) throws Exception {
        final String form = args[0];
        MESSAGES.add("before");
        final int mark = MESSAGES.size();
        MESSAGES.add("hello g6-1234");
        final List<String> lines = since(form, mark);
        MESSAGES.add("a line the client thread adds meanwhile");
        try {
            System.out.println(form + " deterministic: match=" + lines.stream().anyMatch(line -> line.contains("g6-1234")));
        } catch (ConcurrentModificationException e) {
            System.out.println(form + " deterministic: threw " + e);
        }

        final AtomicBoolean stop = new AtomicBoolean();
        final Thread client = new Thread(() -> {
            while (!stop.get()) {
                MESSAGES.add("line");
                if (MESSAGES.size() > 1000) MESSAGES.subList(0, 500).clear();
            }
        });
        client.start();
        long reads = 0, threw = 0;
        final long end = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < end) {
            reads++;
            try {
                since(form, 0).stream().anyMatch(line -> line.contains("g6-"));
            } catch (ConcurrentModificationException | IndexOutOfBoundsException e) {
                threw++;
            }
        }
        stop.set(true);
        client.join();
        System.out.println(form + " stress: " + threw + " of " + reads + " reads threw");
    }
}
