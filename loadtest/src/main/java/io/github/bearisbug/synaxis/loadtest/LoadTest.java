package io.github.bearisbug.synaxis.loadtest;

import java.util.Arrays;
import java.util.List;

/**
 * The load test harness of {@code docs/DESIGN-PROD.md} {@code REQ-PROD-029}, {@code API-PROD-025}
 * and {@code ADR-PROD-013}: Java bots with a real login, an optional encrypted one, chunk batch
 * receipts and walk, ride or fly movement, split over processes or machines.
 *
 * <pre>
 * run    --host H --port P --bots N [--bot-offset K] [--profile walk|ride|fly] [--encryption on|off]
 *        [--chunks-per-tick 9.0] [--duration 600s] [--login-rate 20/s] [--warmup 60s] [--hello on|off]
 *        --out bots-&lt;n&gt;.json
 * sample --pid P [--duration 600s] [--every 10s] [--metrics http://127.0.0.1:9464/metrics] --out server.json
 * merge  bots-*.json server.json [--tick-p99-ms 50] [--max-over-50ms 0] [--rss-mib 4096] [--min-chunks 9]
 *        --out summary.json
 * flood  --host H --port P [--ip-rate 100/s] [--duration 60s] [--hold 0.2s] --out flood.json
 * </pre>
 */
public final class LoadTest {

    private LoadTest() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 0) {
            System.err.println("usage: loadtest run|sample|merge|flood [options], see LoadTest's javadoc");
            System.exit(64);
            return;
        }
        final Args args = new Args(List.of(Arrays.copyOfRange(arguments, 1, arguments.length)));
        final int status = switch (arguments[0]) {
            case "run" -> RunCommand.run(args);
            case "sample" -> SampleCommand.run(args);
            case "merge" -> MergeCommand.run(args);
            case "flood" -> FloodCommand.run(args);
            default -> {
                System.err.println("unknown command " + arguments[0] + ": run, sample, merge or flood");
                yield 64;
            }
        };
        System.exit(status);
    }
}
