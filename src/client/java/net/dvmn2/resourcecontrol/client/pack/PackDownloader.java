package net.dvmn2.resourcecontrol.client.pack;

import net.dvmn2.resourcecontrol.client.network.SyncPayload.PackEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Safe pack download: https only (the HttpClient never follows https to http), size limit approved by the
 * player, declared size and SHA-1 verification, atomic move into the cache, timeouts and a stall watchdog.
 */
public final class PackDownloader {

    private static final Logger LOGGER = LoggerFactory.getLogger("resourcecontrol");

    private static final int CONNECT_TIMEOUT_SECONDS = 15;
    private static final int RESPONSE_TIMEOUT_SECONDS = 30;
    private static final int STALL_TIMEOUT_SECONDS = 30;
    private static final int WATCHDOG_PERIOD_SECONDS = 5;
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final int HTTP_OK = 200;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .build();

    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ResourceControl-Watchdog");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Receives download progress.
     */
    @FunctionalInterface
    public interface Progress {
        void update(long done, long total);
    }

    /**
     * Thrown when the download was cancelled because its result is no longer wanted.
     */
    public static final class CancelledException extends IOException {
        CancelledException() {
            super("cancelled");
        }
    }

    private PackDownloader() {
    }

    /**
     * Downloads one pack into the cache.
     *
     * @param maxBytes  largest size the player approved
     * @param cancelled polled between chunks; when true the download is aborted
     */
    public static void download(PackEntry entry, long maxBytes, BooleanSupplier cancelled, Progress progress)
            throws IOException, InterruptedException {
        URI uri = URI.create(entry.url());
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("only https is allowed");
        }
        long expected = entry.size();
        if (expected <= 0 || expected > maxBytes || expected > PackCache.MAX_PACK_SIZE) {
            throw new IOException("invalid pack size: " + expected);
        }

        Files.createDirectories(PackCache.cacheDir());
        Path part = PackCache.partFile(entry.sha1());
        Path target = PackCache.file(entry.sha1());

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(RESPONSE_TIMEOUT_SECONDS))
                .header("User-Agent", "ResourceControl")
                .GET()
                .build();
        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            if (response.statusCode() != HTTP_OK) {
                throw new IOException("HTTP " + response.statusCode());
            }
            if (!"https".equalsIgnoreCase(response.uri().getScheme())) {
                throw new IOException("redirected to non-https url");
            }
            String actual = copyVerified(in, part, expected, cancelled, progress);
            if (!actual.equals(entry.sha1())) {
                throw new IOException("sha1 mismatch");
            }
            moveIntoCache(part, target);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    private static String copyVerified(InputStream in, Path part, long expected, BooleanSupplier cancelled,
                                       Progress progress) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException(ex);
        }

        AtomicLong lastRead = new AtomicLong(System.nanoTime());
        AtomicBoolean stalled = new AtomicBoolean();
        ScheduledFuture<?> watch = WATCHDOG.scheduleWithFixedDelay(() -> {
            if (System.nanoTime() - lastRead.get() > TimeUnit.SECONDS.toNanos(STALL_TIMEOUT_SECONDS)) {
                stalled.set(true);
                closeQuietly(in);
            }
        }, WATCHDOG_PERIOD_SECONDS, WATCHDOG_PERIOD_SECONDS, TimeUnit.SECONDS);

        long total = 0L;
        try (OutputStream out = Files.newOutputStream(part,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[BUFFER_BYTES];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                lastRead.set(System.nanoTime());
                if (cancelled.getAsBoolean()) {
                    throw new CancelledException();
                }
                total += read;
                if (total > expected) {
                    throw new IOException("file is larger than declared size");
                }
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
                progress.update(total, expected);
            }
        } catch (IOException ex) {
            if (stalled.get()) {
                throw new IOException("download stalled", ex);
            }
            throw ex;
        } finally {
            watch.cancel(false);
        }

        if (total != expected) {
            throw new IOException("size mismatch: " + total + " != " + expected);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void moveIntoCache(Path part, Path target) throws IOException {
        try {
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ex) {
            LOGGER.debug("Could not close a stalled download", ex);
        }
    }
}
