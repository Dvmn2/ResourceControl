package net.dvmn2.resourcecontrol.client.pack;

import com.google.gson.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Решения игрока «разрешаю скачивать паки размером до N» — по одному на сервер.
 * Файл {@code <gameDir>/resourcecontrol/consent.json}:
 * <pre>{ "servers": { "play.example.com": 1073741824 } }</pre>
 * Хранится и читается ТОЛЬКО на клиенте. На сервер решение не отправляется и ни на что там не
 * влияет, поэтому подменить его по сети невозможно.
 */
public final class ConsentStore {

    private static final Map<String, Long> approved = new HashMap<>();
    private static boolean loaded = false;

    private ConsentStore() {
    }

    private static Path file() {
        return PackCache.root().resolve("consent.json");
    }

    /**
     * Ключ текущего сервера: адрес из списка серверов в нижнем регистре, без точки в конце и
     * без порта по умолчанию. {@code null} — если сервер определить нельзя (одиночная игра, реплей):
     * тогда согласие действует только до выхода и на диск не пишется.
     */
    public static String serverKey(MinecraftClient client) {
        ServerInfo info = client.getCurrentServerEntry();
        if (info == null || info.address == null) {
            return null;
        }
        String a = info.address.trim().toLowerCase(Locale.ROOT);
        if (a.endsWith(":25565")) {
            a = a.substring(0, a.length() - ":25565".length());
        }
        while (a.endsWith(".")) {
            a = a.substring(0, a.length() - 1);
        }
        return a.isEmpty() ? null : a;
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject servers = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("servers");
            if (servers == null) {
                return;
            }
            for (Map.Entry<String, JsonElement> e : servers.entrySet()) {
                long value = e.getValue().getAsLong();
                if (value > 0) {
                    approved.put(e.getKey(), Math.min(value, PackCache.MAX_PACK_SIZE));
                }
            }
        } catch (Exception ex) {
            // повреждённый файл = согласий нет, игрока просто спросят заново
            approved.clear();
        }
    }

    /**
     * Сколько байт на один пак игрок уже разрешил этому серверу (0 — ничего).
     */
    public static synchronized long approved(String key) {
        if (key == null) {
            return 0L;
        }
        ensureLoaded();
        return approved.getOrDefault(key, 0L);
    }

    public static synchronized void approve(String key, long bytes) {
        if (key == null) {
            return;
        }
        ensureLoaded();
        approved.put(key, Math.min(bytes, PackCache.MAX_PACK_SIZE));
        save();
    }

    private static void save() {
        try {
            Files.createDirectories(PackCache.root());
            JsonObject servers = new JsonObject();
            approved.forEach(servers::addProperty);
            JsonObject root = new JsonObject();
            root.add("servers", servers);

            Path target = file();
            Path tmp = target.resolveSibling("consent.json.tmp");
            Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFailed) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            System.err.println("[ResourceControl] Не удалось сохранить consent.json: " + ex.getMessage());
        }
    }
}
