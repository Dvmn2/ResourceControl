package net.dvmn2.resourcecontrol.client.pack;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Локальный кеш паков: {@code <gameDir>/resourcecontrol/cache/<sha1>.zip}.
 * Имя файла строится ТОЛЬКО из проверенного sha1 (40 hex), поэтому ни URL, ни имя пака
 * из сети не могут повлиять на путь на диске. Паки не распаковываются — они
 * подключаются прямо из zip, так что path traversal невозможен по построению.
 */
public final class PackCache {

    /**
     * Абсолютный потолок размера одного пака (4 ГБ). Сервер не может запросить больше, что бы он
     * ни прислал. Реально действующий лимит — то, что одобрил игрок (см. {@code ConsentStore}).
     */
    public static final long MAX_PACK_SIZE = 4L * 1024 * 1024 * 1024;

    private static final Pattern SHA1 = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");

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

    public static boolean isValidSha1(String s) {
        return s != null && SHA1.matcher(s).matches();
    }

    public static boolean isValidName(String s) {
        return s != null && NAME.matcher(s).matches();
    }

    /**
     * Проверка скачанного/закешированного zip.
     *
     * @return null, если пак годен; иначе — причина отказа
     */
    public static String inspect(Path file, boolean allowShaders) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            if (zip.getEntry("pack.mcmeta") == null) {
                return "no pack.mcmeta in archive root";
            }
            if (!allowShaders) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String[] parts = entry.getName().split("/");
                    if (parts.length >= 3 && parts[0].equals("assets") && parts[2].equals("shaders")) {
                        return "shaders are not allowed by the server";
                    }
                }
            }
            return null;
        } catch (IOException ex) {
            return "not a valid zip: " + ex.getMessage();
        }
    }

    /**
     * Обслуживание кеша. Срок хранения считается от ПОСЛЕДНЕГО ИСПОЛЬЗОВАНИЯ пака:
     * <ul>
     *   <li>файлы из {@code keepSha1} (активный набор) защищены от удаления, а их время изменения
     *       обновляется («продлевается»), не чаще раза в сутки;</li>
     *   <li>остальные файлы (и недокачанные .part) удаляются, если их не использовали 14 дней.</li>
     * </ul>
     * Вызывается при старте игры и после каждой обработки набора от сервера.
     */
    public static void cleanup(Set<String> keepSha1) {
        Path dir = cacheDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        Instant now = Instant.now();
        Instant deleteBefore = now.minus(14, ChronoUnit.DAYS);
        Instant touchBefore = now.minus(1, ChronoUnit.DAYS);
        try (Stream<Path> files = Files.list(dir)) {
            files.forEach(path -> {
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
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
