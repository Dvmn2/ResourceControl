package net.dvmn2.resourcecontrol.client.pack;

import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Enumeration;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Local pack cache: {@code <gameDir>/resourcecontrol/cache/<sha1>.zip}. File names are built ONLY from a
 * validated sha1, so no URL or pack name from the network can influence a path. Packs are never unpacked:
 * they are mounted straight from the zip, which rules out path traversal by construction.
 */
public final class PackCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("resourcecontrol");

    /**
     * Absolute ceiling for one pack (4 GB). The effective limit is what the player approved.
     */
    public static final long MAX_PACK_SIZE = 4L * 1024L * 1024L * 1024L;

    private static final int MAX_NAME_LENGTH = 32;
    private static final long UNUSED_DELETE_DAYS = 14L;
    private static final long TOUCH_INTERVAL_DAYS = 1L;
    private static final Pattern SHA1 = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1," + MAX_NAME_LENGTH + "}");

    private PackCache() {
    }

    public static Path root() {
        return FabricLoader.getInstance().getGameDir().resolve("resourcecontrol");
    }

    public static Path cacheDir() {
        return root().resolve("cache");
    }

    public static Path stateFile() {
        return root().resolve("state.json");
    }

    public static Path file(String sha1) {
        return cacheDir().resolve(sha1 + ".zip");
    }

    public static Path partFile(String sha1) {
        return cacheDir().resolve(sha1 + ".part");
    }

    public static boolean isValidSha1(String value) {
        return SHA1.matcher(value).matches();
    }

    public static boolean isValidName(String value) {
        return NAME.matcher(value).matches();
    }

    /**
     * Checks a downloaded or cached zip.
     *
     * @return null when the pack is acceptable, otherwise the reason it is not
     */
    public static @Nullable String inspect(Path file, boolean allowShaders) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            if (zip.getEntry("pack.mcmeta") == null) {
                return "no pack.mcmeta in archive root";
            }
            if (!allowShaders) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String[] parts = entries.nextElement().getName().split("/");
                    if (parts.length >= 3 && parts[0].equals("assets") && parts[2].equals("shaders")) {
                        return "shaders are not allowed";
                    }
                }
            }
            return null;
        } catch (IOException ex) {
            return "not a valid zip: " + ex.getMessage();
        }
    }

    /**
     * Cache maintenance. Retention counts from the LAST USE of a pack: files in {@code keepSha1} are never
     * deleted and get their timestamp refreshed at most once a day; other files (and unfinished .part files)
     * are deleted after 14 days without use.
     */
    public static void cleanup(Set<String> keepSha1) {
        Path dir = cacheDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        Instant now = Instant.now();
        Instant deleteBefore = now.minus(UNUSED_DELETE_DAYS, ChronoUnit.DAYS);
        Instant touchBefore = now.minus(TOUCH_INTERVAL_DAYS, ChronoUnit.DAYS);
        try (Stream<Path> files = Files.list(dir)) {
            files.forEach(path -> cleanupFile(path, keepSha1, now, deleteBefore, touchBefore));
        } catch (IOException ex) {
            LOGGER.warn("Could not list the pack cache", ex);
        }
    }

    private static void cleanupFile(Path path, Set<String> keepSha1, Instant now, Instant deleteBefore,
                                    Instant touchBefore) {
        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot < 0 ? fileName : fileName.substring(0, dot);
        try {
            Instant modified = Files.getLastModifiedTime(path).toInstant();
            if (keepSha1.contains(base)) {
                if (fileName.endsWith(".zip") && modified.isBefore(touchBefore)) {
                    Files.setLastModifiedTime(path, FileTime.from(now));
                }
            } else if (modified.isBefore(deleteBefore)) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ex) {
            LOGGER.debug("Cache maintenance skipped {}", fileName, ex);
        }
    }
}
