package net.dvmn2.resourcecontrol.client.pack;

import net.dvmn2.resourcecontrol.client.network.SyncPayload.PackEntry;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.BooleanSupplier;

/**
 * Безопасная загрузка пака: только HTTPS (редиректы https->http запрещены самим HttpClient),
 * жёсткий лимит размера, проверка SHA-1 и объявленного размера, атомарная запись в кеш.
 */
final class PackDownloader {

    interface Progress {
        void update(long done, long total);
    }

    static final class CancelledException extends IOException {
        CancelledException() {
            super("cancelled");
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private PackDownloader() {
    }

    static void download(PackEntry entry, BooleanSupplier cancelled, Progress progress)
            throws IOException, InterruptedException {
        URI uri = URI.create(entry.url());
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("only https is allowed");
        }
        long expected = entry.size();
        if (expected <= 0 || expected > PackCache.MAX_PACK_SIZE) {
            throw new IOException("invalid pack size: " + expected);
        }

        Files.createDirectories(PackCache.cacheDir());
        Path part = PackCache.partFile(entry.sha1());
        Path target = PackCache.file(entry.sha1());

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "ResourceControl/1.0")
                .GET()
                .build();
        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }
            if (!"https".equalsIgnoreCase(response.uri().getScheme())) {
                throw new IOException("redirected to non-https url");
            }

            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-1");
            } catch (NoSuchAlgorithmException ex) {
                throw new IOException(ex);
            }

            long total = 0;
            try (OutputStream out = Files.newOutputStream(part,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) {
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
            }

            if (total != expected) {
                throw new IOException("size mismatch: " + total + " != " + expected);
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equals(entry.sha1())) {
                throw new IOException("sha1 mismatch");
            }
            try {
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFailed) {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(part);
        }
    }
}
