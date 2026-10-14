package io.github.bearisbug.synaxis.game.client.farview;

import io.github.bearisbug.synaxis.game.protocol.FarViewAnnouncement;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack;
import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import org.jspecify.annotations.Nullable;

/**
 * The downloads of {@code docs/DESIGN-M3.md} {@code API-M3-015} (下载条): every announced object not
 * in the cache, {@value #CONCURRENCY} at a time, over the JDK's {@link HttpClient} with the JVM's
 * proxy settings. An object gets {@value #ATTEMPTS} attempts, waiting {@link #RETRY_WAITS} before the
 * second and the third; a {@code .part} left by an earlier attempt or connection is resumed with
 * {@code Range}. An object is kept only when its size and SHA-256 are the announced ones and
 * {@code FarViewPack.Reader} reads its header and indexes as the layer and the tile it was announced as.
 * <p>
 * The limits of M3-S ({@code REQ-M3-032}): no redirect is followed; a URL that cannot be requested and
 * a 3xx answer end the object at its first attempt; the SHA-256 is taken as the bytes are written,
 * a resumed {@code .part} read into it first, so a finished object is never read whole for it; and a
 * download, every object of one announcement, stops after {@link #TOTAL_TIMEOUT} as a cancel does.
 */
public final class FarViewDownloader {

    /** Attempts per object: the first and two retries. */
    public static final int ATTEMPTS = 3;
    /** Objects downloading at once. */
    public static final int CONCURRENCY = 2;
    /** The connect timeout. */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** An attempt fails when no byte arrives for this long. */
    public static final Duration IDLE_TIMEOUT = Duration.ofSeconds(30);
    /** The waits before the second and the third attempt. */
    public static final List<Duration> RETRY_WAITS = List.of(Duration.ofSeconds(1), Duration.ofSeconds(4));
    /** What one download, every object of an announcement, may take in all. */
    public static final Duration TOTAL_TIMEOUT = Duration.ofMinutes(10);

    /** The reason of a download stopped by its cancel flag. */
    static final String CANCELLED = "cancelled";
    /** How often a wait looks at the cancel flag, the idle clock and the time limit. */
    private static final Duration POLL = Duration.ofMillis(200);
    /** How much of a {@code .part} is read at a time into the digest of a resumed object. */
    private static final int DIGEST_CHUNK = 64 * 1024;

    private final FarViewCache cache;
    private final HttpClient http;
    private final List<Duration> retryWaits;
    private final Duration idleTimeout;
    private final Duration totalTimeout;

    /**
     * A downloader with the time limit of the design.
     *
     * @param cache       where the objects go
     * @param http        the client
     * @param retryWaits  the waits before the second and third attempt
     * @param idleTimeout how long an attempt may go without a byte
     */
    public FarViewDownloader(FarViewCache cache, HttpClient http, List<Duration> retryWaits, Duration idleTimeout) {
        this(cache, http, retryWaits, idleTimeout, TOTAL_TIMEOUT);
    }

    /**
     * @param cache        where the objects go
     * @param http         the client
     * @param retryWaits   the waits before the second and third attempt
     * @param idleTimeout  how long an attempt may go without a byte
     * @param totalTimeout how long one download may take in all
     */
    FarViewDownloader(FarViewCache cache, HttpClient http, List<Duration> retryWaits, Duration idleTimeout, Duration totalTimeout) {
        this.cache = cache;
        this.http = http;
        this.retryWaits = List.copyOf(retryWaits);
        this.idleTimeout = idleTimeout;
        this.totalTimeout = totalTimeout;
    }

    /**
     * The downloader of the design: the client the process shares, with the JVM's proxy selector and
     * no redirect followed, and the timeouts and waits above.
     *
     * @param cache where the objects go
     * @return the downloader
     */
    public static FarViewDownloader standard(FarViewCache cache) {
        return new FarViewDownloader(cache, Shared.HTTP, RETRY_WAITS, IDLE_TIMEOUT);
    }

    /** The client every {@link #standard} downloader shares, one for the process: built with the first, never closed. */
    private static final class Shared {
        static final HttpClient HTTP = http().proxy(ProxySelector.getDefault()).build();
    }

    /**
     * The client of {@link #standard} without its proxy. A redirect is never followed: the one-host and
     * local-address rules ({@link FarViewClient#admitted}, {@link FarViewConnection#localRefusal}) see
     * the announced URL only, and a redirect could send the request to any other host. A 3xx ends its
     * object at the first attempt ({@link PartWriter}): objects are named by their digest and the CDN
     * answers with the object itself, so the same redirect would come back on every retry.
     *
     * @return a builder to set the proxy on
     */
    static HttpClient.Builder http() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(CONNECT_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1);
    }

    /**
     * How a download ended.
     *
     * @param success   every object is in the cache
     * @param objects   the objects downloaded
     * @param bytes     their bytes
     * @param millis    the wall clock of the whole download
     * @param failed    the digest of the object that failed, null on success, when cancelled or out of time
     * @param reason    the reason of its last attempt, {@code cancelled}, or the time limit; empty on success
     * @param attempts  the attempts the failed object had, 0 without one
     */
    public record Result(boolean success, int objects, long bytes, long millis, @Nullable String failed, String reason,
                         int attempts) {
    }

    /**
     * Downloads the objects, blocking until all are in the cache, one has failed its attempts,
     * {@code cancel} is set, or the download has taken its time limit. On a failure the others stop
     * and the failed object's {@code .part} is removed; a cancel and the time limit leave every
     * {@code .part} for the next connection.
     *
     * @param tiles    the announced objects to download
     * @param cancel   set to stop
     * @param progress told the bytes received so far, out of the sum of the objects' sizes
     * @return how it ended
     */
    public Result download(List<FarViewAnnouncement.Tile> tiles, AtomicBoolean cancel, LongConsumer progress) {
        final long started = System.nanoTime();
        final long deadline = started + totalTimeout.toNanos();
        final AtomicLongArray received = new AtomicLongArray(tiles.size());
        final AtomicBoolean stop = new AtomicBoolean();
        final AtomicBoolean late = new AtomicBoolean();
        final AtomicReference<Result> failure = new AtomicReference<>();
        final BooleanSupplier stopped = () -> {
            if (System.nanoTime() - deadline > 0) late.set(true);
            return stop.get() || cancel.get() || late.get();
        };
        final LongConsumer[] perObject = new LongConsumer[tiles.size()];
        for (int i = 0; i < tiles.size(); i++) {
            final int index = i;
            // One lock for the update, the sum and the report: the last report then carries the latest sum,
            // never one a slower thread summed before another object's bytes came in
            perObject[i] = bytes -> {
                synchronized (received) {
                    received.set(index, bytes);
                    long sum = 0;
                    for (int j = 0; j < received.length(); j++) sum += received.get(j);
                    progress.accept(sum);
                }
            };
        }
        final ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(CONCURRENCY, tiles.size())),
                Thread.ofPlatform().daemon().name("synaxis-farview-get-", 0).factory());
        final List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < tiles.size(); i++) {
                final FarViewAnnouncement.Tile tile = tiles.get(i);
                final LongConsumer bytes = perObject[i];
                results.add(pool.submit(() -> {
                    if (stopped.getAsBoolean()) return false;
                    final Outcome outcome = object(tile, bytes, stopped);
                    if (outcome.reason().isEmpty()) return true;
                    if (!outcome.reason().equals(CANCELLED)) {
                        failure.compareAndSet(null, new Result(false, 0, 0, 0, FarViewCache.hex(tile), outcome.reason(),
                                outcome.attempts()));
                        stop.set(true);
                    }
                    return false;
                }));
            }
            int objects = 0;
            long bytes = 0;
            for (int i = 0; i < results.size(); i++) {
                if (results.get(i).get()) {
                    objects++;
                    bytes += tiles.get(i).bytes();
                }
            }
            final long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            final Result failed = failure.get();
            if (failed != null && !cancel.get()) {
                return new Result(false, objects, bytes, millis, failed.failed(), failed.reason(), failed.attempts());
            }
            if (objects < tiles.size()) {
                final String reason = late.get() && !cancel.get() ? "the download took over " + totalTimeout.toSeconds() + " s"
                        : CANCELLED;
                return new Result(false, objects, bytes, millis, null, reason, 0);
            }
            return new Result(true, objects, bytes, millis, null, "", 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, 0, 0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), null, CANCELLED, 0);
        } catch (ExecutionException e) {
            return new Result(false, 0, 0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), null,
                    String.valueOf(e.getCause()), 0);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * How one object ended.
     *
     * @param reason   empty once it is in the cache, {@link #CANCELLED} when stopped, else the reason of the last attempt
     * @param attempts the attempts made
     */
    private record Outcome(String reason, int attempts) {
    }

    /**
     * One object, up to {@value #ATTEMPTS} attempts; a URL that cannot be requested, a 3xx answer and bytes
     * of the announced digest that are not the announced pack end it at the first. A runtime exception of
     * an attempt is a failed attempt, its {@code .part} removed; an I/O exception is one that keeps the
     * {@code .part} for the next attempt to resume or digest again.
     */
    private Outcome object(FarViewAnnouncement.Tile tile, LongConsumer progress, BooleanSupplier stopped) throws IOException {
        final String hex = FarViewCache.hex(tile);
        final Path part = cache.part(hex);
        Files.createDirectories(part.getParent());
        String reason = "";
        int attempt = 1;
        for (; attempt <= ATTEMPTS; attempt++) {
            if (attempt > 1 && !sleep(retryWaits.get(Math.min(attempt - 2, retryWaits.size() - 1)), stopped)) {
                return new Outcome(CANCELLED, attempt - 1);
            }
            if (stopped.getAsBoolean()) return new Outcome(CANCELLED, attempt - 1);
            try {
                final byte[] digest = fetch(tile, part, progress, stopped);
                if (stopped.getAsBoolean()) return new Outcome(CANCELLED, attempt);
                final String wrong = check(tile, part, digest);
                if (wrong.isEmpty()) {
                    // What packs/ holds is never checked again: its bytes reach the disk before its name does
                    try (FileChannel out = FileChannel.open(part, StandardOpenOption.WRITE)) {
                        out.force(true);
                    }
                    Files.move(part, cache.pack(hex), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    return new Outcome("", attempt);
                }
                Files.deleteIfExists(part);
                progress.accept(0);
                reason = wrong;
                // The same bytes fail the same way: only bytes of another digest are worth another attempt
                if (Arrays.equals(digest, tile.sha256())) break;
            } catch (Failed e) {
                if (e.cancelled) return new Outcome(CANCELLED, attempt);
                if (e.discard) {
                    Files.deleteIfExists(part);
                    progress.accept(0);
                }
                reason = e.getMessage();
                if (e.fatal) break;
            } catch (RuntimeException e) {
                Files.deleteIfExists(part);
                progress.accept(0);
                reason = e.toString();
            } catch (IOException e) {
                // A file the system would not move or read this time: the next attempt resumes or digests the .part again
                reason = e.toString();
            }
        }
        Files.deleteIfExists(part);
        return new Outcome(reason, Math.min(attempt, ATTEMPTS));
    }

    /**
     * An attempt that failed; {@code discard} when what it wrote cannot be resumed from, {@code fatal}
     * when another attempt would end the same.
     */
    @SuppressWarnings("serial")
    private static final class Failed extends Exception {
        final boolean discard;
        final boolean cancelled;
        final boolean fatal;

        Failed(String message, boolean discard, boolean cancelled, boolean fatal) {
            super(message);
            this.discard = discard;
            this.cancelled = cancelled;
            this.fatal = fatal;
        }
    }

    /**
     * One GET into the {@code .part}, from where it ends when it has bytes; returns when it holds the
     * whole object, with the SHA-256 of what the {@code .part} holds.
     */
    private byte[] fetch(FarViewAnnouncement.Tile tile, Path part, LongConsumer progress, BooleanSupplier stopped)
            throws IOException, Failed {
        long have = Files.exists(part) ? Files.size(part) : 0;
        if (have > tile.bytes()) {
            Files.delete(part);
            have = 0;
        }
        progress.accept(have);
        if (have == tile.bytes()) return digestOf(part, have);
        final HttpRequest request;
        try {
            final URI uri = URI.create(tile.url());
            final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) throw new IllegalArgumentException(scheme + " is not http or https");
            final HttpRequest.Builder builder = HttpRequest.newBuilder(uri).header("User-Agent", "synaxis-farview").GET();
            if (have > 0 && FarViewTestHook.range) builder.header("Range", "bytes=" + have + "-");
            request = builder.build();
        } catch (IllegalArgumentException e) {
            throw new Failed("the url cannot be requested: " + e.getMessage(), false, false, true);
        }
        final PartWriter writer = new PartWriter(part, have, tile.bytes(), progress);
        final CompletableFuture<HttpResponse<Void>> response;
        try {
            response = http.sendAsync(request, writer);
        } catch (IllegalArgumentException e) {
            throw new Failed("the url cannot be requested: " + e.getMessage(), false, false, true);
        }
        try {
            while (true) {
                try {
                    response.get(POLL.toMillis(), TimeUnit.MILLISECONDS);
                    break;
                } catch (TimeoutException _) {
                    if (stopped.getAsBoolean()) {
                        writer.abort();
                        throw new Failed(CANCELLED, false, true, false);
                    }
                    // A refused answer's body is not waited for: the refusal is already in done
                    if (writer.done.isCompletedExceptionally()) break;
                    if (System.nanoTime() - writer.lastByte() > idleTimeout.toNanos()) {
                        writer.abort();
                        throw new Failed("no byte for " + idleTimeout.toSeconds() + " s", false, false, false);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    writer.abort();
                    throw new Failed(CANCELLED, false, true, false);
                } catch (ExecutionException e) {
                    throw failed(e.getCause());
                }
            }
            // A refusal is in the writer's body: the response completes normally or is left at the next poll
            if (writer.done.isCompletedExceptionally()) {
                try {
                    writer.done.join();
                } catch (CompletionException e) {
                    throw failed(e.getCause());
                }
            }
        } finally {
            // An attempt given up on before its response came ends its exchange; a late answer would open the .part again
            response.cancel(true);
            writer.close();
        }
        final long size = Files.size(part);
        if (size != tile.bytes()) throw new Failed("the body ended at " + size + " of " + tile.bytes() + " bytes", false, false, false);
        return writer.digest();
    }

    private static Failed failed(Throwable cause) {
        if (cause instanceof Refused refused) return new Failed(refused.getMessage(), refused.discard, false, refused.fatal);
        // HttpClient reports a host it cannot resolve as a ConnectException without a message, the reason in its cause
        final Throwable inner = cause.getCause();
        return new Failed(inner == null ? String.valueOf(cause) : cause + " (" + inner + ")", false, false, false);
    }

    /**
     * Size, digest, then the header and indexes as the layer and the tile it was announced as. Only an
     * object of the announced size and digest is read whole, and that size is at most
     * {@link FarViewAnnouncement#MAX_OBJECT_BYTES}, which {@code FarViewAnnouncement.Tile} holds to.
     *
     * @return empty when the object is the announced one, else why it is not
     */
    private static String check(FarViewAnnouncement.Tile tile, Path part, byte[] digest) throws IOException {
        final long size = Files.size(part);
        if (size != tile.bytes()) return size + " bytes, not the announced " + tile.bytes();
        if (FarViewTestHook.digest && !Arrays.equals(digest, tile.sha256())) {
            return "its SHA-256 is " + HexFormat.of().formatHex(digest).substring(0, 12) + ", not the announced "
                    + FarViewCache.hex(tile).substring(0, 12);
        }
        final FarViewPack.Reader reader;
        try {
            reader = FarViewPack.Reader.read(Files.readAllBytes(part));
        } catch (FarViewPack.Invalid e) {
            return "not a pack: " + e.getMessage();
        }
        final boolean coarseOnly = reader.coarseOnly();
        if (coarseOnly != (tile.layer() == FarViewAnnouncement.LAYER_COARSE)) {
            return (coarseOnly ? "a coarse prefix" : "a whole pack") + " where layer " + tile.layer() + " was announced";
        }
        if (reader.header().tileX() != tile.tileX() || reader.header().tileZ() != tile.tileZ()) {
            return "the pack is of tile " + reader.header().tileX() + "," + reader.header().tileZ() + ", not the announced "
                    + tile.tileX() + "," + tile.tileZ();
        }
        return "";
    }

    /** The SHA-256 of the first {@code bytes} of a file, read {@value #DIGEST_CHUNK} bytes at a time. */
    private static byte[] digestOf(Path file, long bytes) throws IOException {
        final MessageDigest digest = sha256();
        feed(digest, file, bytes);
        return digest.digest();
    }

    private static void feed(MessageDigest digest, Path file, long bytes) throws IOException {
        try (FileChannel in = FileChannel.open(file, StandardOpenOption.READ)) {
            final ByteBuffer chunk = ByteBuffer.allocate(DIGEST_CHUNK);
            long left = bytes;
            while (left > 0) {
                chunk.clear().limit((int) Math.min(DIGEST_CHUNK, left));
                final int read = in.read(chunk);
                if (read < 0) throw new IOException(file.getFileName() + " ends before byte " + bytes);
                chunk.flip();
                digest.update(chunk);
                left -= read;
            }
        }
    }

    /** A response the writer turned down: a status, a range, or more bytes than announced. */
    @SuppressWarnings("serial")
    private static final class Refused extends IOException {
        final boolean discard;
        final boolean fatal;

        Refused(String message, boolean discard, boolean fatal) {
            super(message);
            this.discard = discard;
            this.fatal = fatal;
        }
    }

    /**
     * Writes a response body into the {@code .part}, its SHA-256 taken as it goes: appended for a 206
     * that starts where the part ends (the part's bytes read into the digest first), from the start for
     * a 200; a 3xx is refused for good, any other answer or a body running past the announced size for
     * this attempt.
     */
    private static final class PartWriter implements HttpResponse.BodyHandler<Void> {
        private final Path part;
        private final long have;
        private final long size;
        private final LongConsumer progress;
        private final Object lock = new Object();
        private final MessageDigest digest = sha256();
        private volatile long lastByte = System.nanoTime();
        private @Nullable FileChannel channel;
        private Flow.@Nullable Subscription subscription;
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private long written;
        private boolean closed;

        PartWriter(Path part, long have, long size, LongConsumer progress) {
            this.part = part;
            this.have = have;
            this.size = size;
            this.progress = progress;
        }

        long lastByte() {
            return lastByte;
        }

        byte[] digest() {
            synchronized (lock) {
                return digest.digest();
            }
        }

        @Override
        public HttpResponse.BodySubscriber<Void> apply(HttpResponse.ResponseInfo info) {
            lastByte = System.nanoTime();
            final int status = info.statusCode();
            final long from;
            if (status == 206 && have > 0) {
                final String range = info.headers().firstValue("Content-Range").orElse("");
                if (!range.startsWith("bytes " + have + "-")) return refuse("206 for " + range + ", not from byte " + have, true, false);
                from = have;
            } else if (status == 200) {
                from = 0;
            } else {
                return refuse("HTTP " + status, status == 416, status >= 300 && status < 400);
            }
            try {
                synchronized (lock) {
                    if (closed) return refuse("the attempt was given up on", false, false);
                    final FileChannel opened = FileChannel.open(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    try {
                        opened.truncate(from);
                        digest.reset();
                        if (from > 0) feed(digest, part, from);
                        opened.position(from);
                    } catch (IOException e) {
                        opened.close();
                        throw e;
                    }
                    channel = opened;
                    written = from;
                }
            } catch (IOException e) {
                return refuse("the .part cannot be written: " + e, false, false);
            }
            progress.accept(from);
            return new HttpResponse.BodySubscriber<>() {
                @Override
                public CompletionStage<Void> getBody() {
                    return done;
                }

                @Override
                public void onSubscribe(Flow.Subscription s) {
                    synchronized (lock) {
                        subscription = s;
                    }
                    if (done.isDone()) s.cancel();
                    else s.request(1);
                }

                @Override
                public void onNext(List<ByteBuffer> buffers) {
                    lastByte = System.nanoTime();
                    try {
                        synchronized (lock) {
                            if (channel == null || done.isDone()) return;
                            for (ByteBuffer buffer : buffers) {
                                written += buffer.remaining();
                                if (written > size) {
                                    abort(new Refused("the body runs to more than the announced " + size + " bytes", true, false));
                                    return;
                                }
                                digest.update(buffer.duplicate());
                                while (buffer.hasRemaining()) channel.write(buffer);
                            }
                        }
                        progress.accept(written);
                        final Flow.Subscription s = subscription;
                        if (s != null) s.request(1);
                    } catch (IOException e) {
                        abort(new Refused("the .part cannot be written: " + e, false, false));
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    done.completeExceptionally(throwable);
                }

                @Override
                public void onComplete() {
                    done.complete(null);
                }
            };
        }

        private HttpResponse.BodySubscriber<Void> refuse(String why, boolean discard, boolean fatal) {
            final Refused refused = new Refused(why, discard, fatal);
            done.completeExceptionally(refused);
            return HttpResponse.BodySubscribers.replacing(null);
        }

        /** Gives up on the attempt: the body stops, what {@link #done} holds is never read. */
        void abort() {
            abort(new Refused("the attempt was given up on", false, false));
        }

        private void abort(Refused why) {
            done.completeExceptionally(why);
            final Flow.Subscription s;
            synchronized (lock) {
                s = subscription;
            }
            if (s != null) s.cancel();
        }

        void close() throws IOException {
            synchronized (lock) {
                closed = true;
                if (channel != null) channel.close();
                channel = null;
            }
        }
    }

    private static boolean sleep(Duration wait, BooleanSupplier stopped) {
        final long until = System.nanoTime() + wait.toNanos();
        try {
            while (System.nanoTime() < until) {
                if (stopped.getAsBoolean()) return false;
                Thread.sleep(Math.max(1, Math.min(POLL.toMillis(), TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime()))));
            }
            return !stopped.getAsBoolean();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
