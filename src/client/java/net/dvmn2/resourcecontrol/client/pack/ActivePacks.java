package net.dvmn2.resourcecontrol.client.pack;

import com.google.gson.*;
import net.dvmn2.resourcecontrol.client.network.SyncPayload.PackEntry;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Что сейчас должно быть включено на клиенте.
 * <ul>
 *   <li><b>persistent</b> — переживают дисконнект, лежат в state.json, подключаются при старте игры
 *       (так меняется панорама главного меню);</li>
 *   <li><b>session</b> — только до выхода с сервера.</li>
 * </ul>
 * Порядок: меньший priority — ниже, больший — выше. Сессионный пак с тем же именем перекрывает постоянный.
 */
public final class ActivePacks {

    private static List<PackEntry> persistent = List.of();
    private static List<PackEntry> session = List.of();
    private static boolean loaded = false;

    private ActivePacks() {
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path file = PackCache.stateFile();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            List<PackEntry> list = new ArrayList<>();
            for (JsonElement el : root.getAsJsonArray("persistent")) {
                JsonObject o = el.getAsJsonObject();
                String name = o.get("name").getAsString();
                String sha1 = o.get("sha1").getAsString();
                if (!PackCache.isValidName(name) || !PackCache.isValidSha1(sha1)) {
                    continue;
                }
                list.add(new PackEntry(name, "", sha1, o.get("size").getAsLong(),
                        o.get("priority").getAsInt(), true));
            }
            persistent = List.copyOf(list);
        } catch (Exception ex) {
            // повреждённый state.json не должен ронять запуск игры
            persistent = List.of();
        }
    }

    private static void save() {
        try {
            Files.createDirectories(PackCache.root());
            JsonArray array = new JsonArray();
            for (PackEntry e : persistent) {
                JsonObject o = new JsonObject();
                o.addProperty("name", e.name());
                o.addProperty("sha1", e.sha1());
                o.addProperty("size", e.size());
                o.addProperty("priority", e.priority());
                array.add(o);
            }
            JsonObject root = new JsonObject();
            root.add("persistent", array);
            try (Writer writer = Files.newBufferedWriter(PackCache.stateFile(), StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(root, writer);
            }
        } catch (IOException ex) {
            System.err.println("[ResourceControl] Не удалось сохранить state.json: " + ex.getMessage());
        }
    }

    /**
     * Эффективный набор, от нижнего слоя к верхнему.
     */
    public static synchronized List<PackEntry> effective() {
        ensureLoaded();
        Map<String, PackEntry> byName = new LinkedHashMap<>();
        for (PackEntry e : persistent) {
            byName.put(e.name(), e);
        }
        for (PackEntry e : session) {
            byName.put(e.name(), e);
        }
        List<PackEntry> list = new ArrayList<>(byName.values());
        list.sort(Comparator.comparingInt(PackEntry::priority).thenComparing(PackEntry::name));
        return list;
    }

    public static synchronized String signature() {
        return effective().stream()
                .map(e -> e.name() + ":" + e.sha1() + ":" + e.priority())
                .collect(Collectors.joining(";"));
    }

    /**
     * Заменяет оба набора (вызывается после того, как все файлы скачаны).
     */
    public static synchronized void set(List<PackEntry> persistentNew, List<PackEntry> sessionNew) {
        ensureLoaded();
        // url в state.json не хранится, поэтому сравниваем без него
        boolean persistentChanged = !stripUrls(persistent).equals(stripUrls(persistentNew));
        persistent = List.copyOf(persistentNew);
        session = List.copyOf(sessionNew);
        if (persistentChanged) {
            save();
        }
    }

    private static List<PackEntry> stripUrls(List<PackEntry> list) {
        return list.stream()
                .map(e -> new PackEntry(e.name(), "", e.sha1(), e.size(), e.priority(), e.persistent()))
                .toList();
    }

    public static synchronized void clearSession() {
        ensureLoaded();
        session = List.of();
    }

    public static synchronized void clearPersistent() {
        ensureLoaded();
        persistent = List.of();
        save();
    }
}
