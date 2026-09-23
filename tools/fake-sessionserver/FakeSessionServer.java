import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * usage: java tools/fake-sessionserver/FakeSessionServer.java --mode record|allow-all --port P
 *        [--bind 127.0.0.1] [--inject RULE]...
 *
 * A stand-in for Mojang's session server (`docs/DESIGN-PROD.md` `API-PROD-027`, `REQ-PROD-044`),
 * for CI and load tests that have no Mojang account. Single file, JDK only, never a production
 * dependency: a server-app pointed at it logs a WARN at start-up.
 *
 * Endpoints, the paths of authlib's YggdrasilMinecraftSessionService:
 *
 *   POST /session/minecraft/join       what a client calls before it sends its key; the body is
 *                                      {"accessToken", "selectedProfile", "serverId"}. Answers 204.
 *   GET  /session/minecraft/hasJoined  ?username=&serverId=[&ip=], what the server asks.
 *
 * Modes:
 *
 *   record     remembers every join as (selectedProfile, serverId). hasJoined answers 200 only for
 *              a serverId that was joined, with the joined profile uuid and the username asked
 *              about, 204 for everything else. The username is not checked against the profile:
 *              a join does not carry it, and the serverId is fresh for every login.
 *   allow-all  answers 200 for any username, the uuid derived from it the way offline mode does
 *              ("OfflinePlayer:<name>"), so a load test's bots keep their uuids across runs.
 *
 * Fault injection (repeatable), applied to hasJoined only:
 *
 *   --inject status=204|429|503[@N]   answer with that status
 *   --inject delay=MS[@N]             wait MS milliseconds, then answer normally
 *   --inject timeout[@N]              never answer (the connection is held until the client gives up)
 *
 * `@N` limits a rule to the N-th hasJoined request (1-based, counted over the process); without it
 * the rule applies to every request. The first rule that matches a request wins.
 *
 * Every request is one line on stdout: `<n> <method> <path> -> <status>`, so a CI job can check
 * that the client joined and the server asked with the same serverId.
 */
public final class FakeSessionServer {

    private record Rule(String kind, long value, long onlyRequest) {
        boolean matches(long request) {
            return onlyRequest == 0 || onlyRequest == request;
        }
    }

    private static final Pattern RULE = Pattern.compile("(status|delay)=(\\d+)(?:@(\\d+))?|timeout(?:@(\\d+))?");

    private final boolean record;
    private final List<Rule> rules;
    private final Map<String, String> joinedProfiles = new ConcurrentHashMap<>();
    private final AtomicLong hasJoinedRequests = new AtomicLong();
    private final AtomicLong requests = new AtomicLong();

    private FakeSessionServer(boolean record, List<Rule> rules) {
        this.record = record;
        this.rules = rules;
    }

    public static void main(String[] args) throws IOException {
        String mode = null;
        String bind = "127.0.0.1";
        int port = -1;
        final List<Rule> rules = new ArrayList<>();
        for (int index = 0; index < args.length; index++) {
            final String option = args[index];
            if (index + 1 >= args.length) usage("missing value for " + option);
            final String value = args[++index];
            switch (option) {
                case "--mode" -> mode = value;
                case "--port" -> port = Integer.parseInt(value);
                case "--bind" -> bind = value;
                case "--inject" -> rules.add(rule(value));
                default -> usage("unknown option " + option);
            }
        }
        if (!"record".equals(mode) && !"allow-all".equals(mode)) usage("--mode must be record or allow-all");
        if (port < 0 || port > 65535) usage("--port is required");

        final FakeSessionServer fake = new FakeSessionServer("record".equals(mode), List.copyOf(rules));
        final HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(bind), port), 128);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/session/minecraft/join", fake::join);
        server.createContext("/session/minecraft/hasJoined", fake::hasJoined);
        server.start();
        System.out.printf(Locale.ROOT, "fake sessionserver mode=%s listening on http://%s:%d, hasJoined at "
                + "/session/minecraft/hasJoined%n", mode, bind, server.getAddress().getPort());
    }

    private static Rule rule(String text) {
        final Matcher matcher = RULE.matcher(text);
        if (!matcher.matches()) usage("--inject " + text + " is not status=CODE, delay=MS or timeout, each optionally @N");
        if (matcher.group(1) != null) {
            return new Rule(matcher.group(1), Long.parseLong(matcher.group(2)),
                    matcher.group(3) == null ? 0 : Long.parseLong(matcher.group(3)));
        }
        return new Rule("timeout", 0, matcher.group(4) == null ? 0 : Long.parseLong(matcher.group(4)));
    }

    private static void usage(String problem) {
        System.err.println(problem);
        System.err.println("usage: java tools/fake-sessionserver/FakeSessionServer.java --mode record|allow-all "
                + "--port P [--bind 127.0.0.1] [--inject status=204|429|503[@N]] [--inject delay=MS[@N]] "
                + "[--inject timeout[@N]]");
        System.exit(2);
    }

    private void join(HttpExchange exchange) throws IOException {
        final long request = requests.incrementAndGet();
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, request, 405, null);
                return;
            }
            final String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            final String profile = jsonString(body, "selectedProfile");
            final String serverId = jsonString(body, "serverId");
            if (profile == null || serverId == null) {
                respond(exchange, request, 400, null);
                return;
            }
            if (record) joinedProfiles.put(serverId, profile);
            respond(exchange, request, 204, null);
        }
    }

    private void hasJoined(HttpExchange exchange) throws IOException {
        final long request = requests.incrementAndGet();
        final long number = hasJoinedRequests.incrementAndGet();
        try (exchange) {
            final Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            final String username = query.get("username");
            final String serverId = query.get("serverId");
            for (Rule rule : rules) {
                if (!rule.matches(number)) continue;
                switch (rule.kind()) {
                    case "status" -> {
                        respond(exchange, request, (int) rule.value(), null);
                        return;
                    }
                    case "delay" -> sleep(rule.value());
                    case "timeout" -> {
                        System.out.printf(Locale.ROOT, "%d %s %s -> held%n", request, exchange.getRequestMethod(),
                                exchange.getRequestURI().getPath());
                        sleep(Long.MAX_VALUE);
                        return;
                    }
                    default -> throw new IllegalStateException(rule.kind());
                }
                break;
            }
            if (username == null || serverId == null) {
                respond(exchange, request, 400, null);
                return;
            }
            final String profile = record ? joinedProfiles.get(serverId) : offlineUuid(username);
            if (profile == null) {
                respond(exchange, request, 204, null);
                return;
            }
            respond(exchange, request, 200, "{\"id\":\"" + profile + "\",\"name\":\"" + escape(username)
                    + "\",\"properties\":[]}");
        }
    }

    private static void respond(HttpExchange exchange, long request, int status, String body) throws IOException {
        System.out.printf(Locale.ROOT, "%d %s %s -> %d%n", request, exchange.getRequestMethod(),
                exchange.getRequestURI().getPath() + (exchange.getRequestURI().getRawQuery() == null ? ""
                        : "?" + exchange.getRequestURI().getRawQuery()), status);
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    /** The undashed name based uuid offline mode gives {@code username}. */
    private static String offlineUuid(String username) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");
    }

    /** The string value of a top level member of a flat JSON object, enough for the join body. */
    private static String jsonString(String json, String member) {
        final Matcher matcher = Pattern.compile("\"" + Pattern.quote(member) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static Map<String, String> query(String raw) {
        final Map<String, String> parameters = new HashMap<>();
        if (raw == null) return parameters;
        for (String pair : raw.split("&", -1)) {
            final int equals = pair.indexOf('=');
            if (equals < 0) continue;
            parameters.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
        }
        return parameters;
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
