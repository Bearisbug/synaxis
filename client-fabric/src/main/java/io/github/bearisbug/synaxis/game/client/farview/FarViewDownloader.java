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
import java.util.HexFormat;
import java.util.List;
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
 * {@code FarViewPack.Reader} reads its header and indexes as the layer it was announced as.
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

    /** The reason of a download stopped by its cancel flag. */
    static final String CANCELLED = "cancelled";
    /** How often a wait looks at the cancel flag and the idle clock. */
    private static final Duration POLL = Duration.ofMillis(200);

    private final FarViewCache cache;
    private final HttpClient http;
    private final List<Duration> retryWaits;
    private final Duration idleTimeout;

    /**
     * @param cache       where the objects go
     * @param http        the client
     * @param retryWaits  the waits before the second and third attempt
     * @param idleTimeout how long an attempt may go without a byte
     */
    public FarViewDownloader(FarViewCache cache, HttpClient http, List<Duration> retryWaits, Duration idleTimeout) {
        this.cache = cache;
        this.http = http;
        this.retryWaits = List.copyOf(retryWaits);
        this.idleTimeout = idleTimeout;
    }

    /**
     * The downloader of the design: the JVM's proxy selector, redirects followed, the timeouts and
     * waits above.
     *
     * @param cache where the objects go
     * @return the downloader
     */
    public static FarViewDownloader standard(FarViewCache cache) {
        final HttpClient http = HttpClient.newBuilder().proxy(ProxySelector.getDefault()).followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(CONNECT_TIMEOUT).version(HttpClient.Version.HTTP_1_1).build();
        return new FarViewDownloader(cache, http, RETRY_WAITS, IDLE_TIMEOUT);
    }

    /**
     * How a download ended.
     *
     * @param success   every object is in the cache
     * @param objects   the objects downloaded
     * @param bytes     their bytes
     * @param millis    the wall clock of the whole download
     * @param failed    the digest of the object that failed, null on success or when cancelled
     * @param reason    the reason of its last attempt, or {@code cancelled}; empty on success
     */
    public record Result(boolean success, int objects, long bytes, long millis, @Nullable String failed, String reason) {
    }

    /**
     * Downloads the objects, blocking until all are in the cache, one has failed all its attempts,
     * or {@code cancel} is set. On a failure the others stop and the failed object's {@code .part}
     * is removed; a cancel leaves every {@code .part} for the next connection.
     *
     * @param tiles    the announced objects to download
     * @param cancel   set to stop
     * @param progress told the bytes received so far, out of the sum of the objects' sizes
     * @return how it ended
     */
    public Result download(List<FarViewAnnouncement.Tile> tiles, AtomicBoolean cancel, LongConsumer progress) {
        final long started = System.nanoTime();
        final AtomicLongArray received = new AtomicLongArray(tiles.size());
        final AtomicBoolean stop = new AtomicBoolean();
        final AtomicReference<Result> failure = new AtomicReference<>();
        final LongConsumer[] perObject = new LongConsumer[tiles.size()];
        for (int i = 0; i < tiles.size(); i++) {
            final int index = i;
            perObject[i] = bytes -> {
                received.set(index, bytes);
                long sum = 0;
                for (int j = 0; j < received.length(); j++) sum += received.get(j);
                progress.accept(sum);
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
                    if (stop.get() || cancel.get()) return false;
                    final String reason = object(tile, bytes, () -> stop.get() || cancel.get());
                    if (reason.isEmpty()) return true;
                    if (!cancel.get() && !reason.equals(CANCELLED)) {
                        failure.compareAndSet(null, new Result(false, 0, 0, 0, hex(tile), reason));
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
            if (failed != null) return new Result(false, objects, bytes, millis, failed.failed(), failed.reason());
            if (objects < tiles.size()) return new Result(false, objects, bytes, millis, null, CANCELLED);
            return new Result(true, objects, bytes, millis, null, "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, 0, 0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), null, CANCELLED);
        } catch (ExecutionException e) {
            return new Result(false, 0, 0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), null,
                    String.valueOf(e.getCause()));
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * One object, up to {@value #ATTEMPTS} attempts.
     *
     * @return empty once it is in the cache, {@link #CANCELLED} when stopped, else the reason of the last attempt
     */
    private String object(FarViewAnnouncement.Tile tile, LongConsumer progress, BooleanSupplier stopped) throws IOException {
        final String hex = hex(tile);
        final Path part = cache.part(hex);
        Files.createDirectories(part.getParent());
        String reason = "";
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            if (attempt > 1 && !sleep(retryWaits.get(Math.min(attempt - 2, retryWaits.size() - 1)), stopped)) return CANCELLED;
            if (stopped.getAsBoolean()) return CANCELLED;
            try {
                fetch(tile, part, progress, stopped);
                if (stopped.getAsBoolean()) return CANCELLED;
                final String wrong = check(tile, Files.readAllBytes(part));
                if (wrong.isEmpty()) {
                    Files.move(part, cache.pack(hex), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    return "";
                }
                Files.deleteIfExists(part);
                progress.accept(0);
                reason = wrong;
            } catch (Failed e) {
                if (e.cancelled) return CANCELLED;
                if (e.discard) {
                    Files.deleteIfExists(part);
                    progress.accept(0);
                }
                reason = e.getMessage();
            }
        }
        Files.deleteIfExists(part);
        return reason;
    }

    /** An attempt that failed; {@code discard} when what it wrote cannot be resumed from. */
    @SuppressWarnings("serial")
    private static final class Failed extends Exception {
        final boolean discard;
        final boolean cancelled;

        Failed(String message, boolean discard, boolean cancelled) {
            super(message);
            this.discard = discard;
            this.cancelled = cancelled;
        }
    }

    /** One GET into the {@code .part}, from where it ends when it has bytes; returns when it holds the whole object. */
    private void fetch(FarViewAnnouncement.Tile tile, Path part, LongConsumer progress, BooleanSupplier stopped)
            throws IOException, Failed {
        long have = Files.exists(part) ? Files.size(part) : 0;
        if (have > tile.bytes()) {
            Files.delete(part);
            have = 0;
        }
        progress.accept(have);
        if (have == tile.bytes()) return;
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(tile.url())).timeout(idleTimeout)
                .header("User-Agent", "synaxis-farview").GET();
        if (have > 0) request.header("Range", "bytes=" + have + "-");
        final PartWriter writer = new PartWriter(part, have, tile.bytes(), progress);
        final CompletableFuture<HttpResponse<Void>> response;
        try {
            response = http.sendAsync(request.build(), writer);
        } catch (IllegalArgumentException e) {
            throw new Failed("the url cannot be requested: " + e.getMessage(), false, false);
        }
        try {
            while (true) {
                try {
                    response.get(POLL.toMillis(), TimeUnit.MILLISECONDS);
                    break;
                } catch (TimeoutException _) {
                    if (stopped.getAsBoolean()) {
                        writer.abort("cancelled");
                        throw new Failed(CANCELLED, false, true);
                    }
                    if (System.nanoTime() - writer.lastByte() > idleTimeout.toNanos()) {
                        writer.abort("no byte for " + idleTimeout.toSeconds() + " s");
                        throw new Failed("no byte for " + idleTimeout.toSeconds() + " s", false, false);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    writer.abort("cancelled");
                    throw new Failed(CANCELLED, false, true);
                } catch (ExecutionException e) {
                    throw failed(e.getCause());
                }
            }
            // A refused response completes the request normally, with the refusal in the writer's body
            if (writer.done.isCompletedExceptionally()) {
                try {
                    writer.done.join();
                } catch (CompletionException e) {
                    throw failed(e.getCause());
                }
            }
        } finally {
            writer.close();
        }
        final long size = Files.size(part);
        if (size != tile.bytes()) throw new Failed("the body ended at " + size + " of " + tile.bytes() + " bytes", false, false);
    }

    private static Failed failed(Throwable cause) {
        if (cause instanceof Refused refused) return new Failed(refused.getMessage(), refused.discard, false);
        return new Failed(String.valueOf(cause), false, false);
    }

    /** Size, digest, and the header and indexes as the layer it was announced as. */
    private static String check(FarViewAnnouncement.Tile tile, byte[] bytes) {
        if (bytes.length != tile.bytes()) return bytes.length + " bytes, not the announced " + tile.bytes();
        final String digest = HexFormat.of().formatHex(sha256(bytes));
        if (!digest.equals(hex(tile))) {
            return "its SHA-256 is " + digest.substring(0, 12) + ", not the announced " + hex(tile).substring(0, 12);
        }
        final FarViewPack.Reader reader;
        try {
            reader = FarViewPack.Reader.read(bytes);
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

    /** A response the writer turned down: a status, a range, or more bytes than announced. */
    @SuppressWarnings("serial")
    private static final class Refused extends IOException {
        final boolean discard;

        Refused(String message, boolean discard) {
            super(message);
            this.discard = discard;
        }
    }

    /**
     * Writes a response body into the {@code .part}: appended for a 206 that starts where the part
     * ends, from the start for a 200; any other answer, or a body running past the announced size,
     * is refused.
     */
    private static final class PartWriter implements HttpResponse.BodyHandler<Void> {
        private final Path part;
        private final long have;
        private final long size;
        private final LongConsumer progress;
        private final Object lock = new Object();
        private volatile long lastByte = System.nanoTime();
        private @Nullable FileChannel channel;
        private Flow.@Nullable Subscription subscription;
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private long written;

        PartWriter(Path part, long have, long size, LongConsumer progress) {
            this.part = part;
            this.have = have;
            this.size = size;
            this.progress = progress;
        }

        long lastByte() {
            return lastByte;
        }

        @Override
        public HttpResponse.BodySubscriber<Void> apply(HttpResponse.ResponseInfo info) {
            lastByte = System.nanoTime();
            final long from;
            if (info.statusCode() == 206 && have > 0) {
                final String range = info.headers().firstValue("Content-Range").orElse("");
                if (!range.startsWith("bytes " + have + "-")) return refuse("206 for " + range + ", not from byte " + have, true);
                from = have;
            } else if (info.statusCode() == 200) {
                from = 0;
            } else {
                return refuse("HTTP " + info.statusCode(), info.statusCode() == 416);
            }
            try {
                synchronized (lock) {
                    final FileChannel opened = FileChannel.open(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    opened.truncate(from);
                    opened.position(from);
                    channel = opened;
                    written = from;
                }
            } catch (IOException e) {
                return refuse("the .part cannot be written: " + e.getMessage(), false);
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
                                    abort(new Refused("the body runs to more than the announced " + size + " bytes", true));
                                    return;
                                }
                                while (buffer.hasRemaining()) channel.write(buffer);
                            }
                        }
                        progress.accept(written);
                        final Flow.Subscription s = subscription;
                        if (s != null) s.request(1);
                    } catch (IOException e) {
                        abort(new Refused("the .part cannot be written: " + e.getMessage(), false));
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

        private HttpResponse.BodySubscriber<Void> refuse(String why, boolean discard) {
            final Refused refused = new Refused(why, discard);
            done.completeExceptionally(refused);
            return HttpResponse.BodySubscribers.replacing(null);
        }

        void abort(String why) {
            abort(new Refused(why, false));
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

    private static String hex(FarViewAnnouncement.Tile tile) {
        return HexFormat.of().formatHex(tile.sha256());
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
