package net.dvmn2.resourcecontrol.client.state;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.dvmn2.resourcecontrol.client.network.SyncPayload.PackEntry;
import net.dvmn2.resourcecontrol.client.pack.PackCache;
import net.dvmn2.resourcecontrol.client.util.AtomicFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What should be enabled on the client right now.
 * <ul>
 *   <li><b>persistent</b> packs survive leaving a server, are stored in state.json and mounted at game start
 *       (this is how the main menu panorama changes);</li>
 *   <li><b>session</b> packs last until the player leaves the server.</li>
 * </ul>
 * A lower priority is lower in the stack. A session pack overrides a persistent one with the same name.
 */
public final class ActivePacks {

    private static final Logger LOGGER = LoggerFactory.getLogger("resourcecontrol");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int FORMAT_VERSION = 1;
    private static final int MIN_PRIORITY = -1000;
    private static final int MAX_PRIORITY = 1000;

    private static List<PackEntry> persistent = List.of();
    private static List<PackEntry> session = List.of();
    private static boolean loaded;

    private ActivePacks() {
    }

    /**
     * Returns the effective set, from the bottom layer to the top one.
     */
    public static synchronized List<PackEntry> getEffective() {
        ensureLoaded();
        Map<String, PackEntry> byName = new LinkedHashMap<>();
        for (PackEntry entry : persistent) {
            byName.put(entry.name(), entry);
        }
        for (PackEntry entry : session) {
            byName.put(entry.name(), entry);
        }
        List<PackEntry> list = new ArrayList<>(byName.values());
        list.sort(Comparator.comparingInt(PackEntry::priority).thenComparing(PackEntry::name));
        return list;
    }

    /**
     * Returns a text that changes whenever the effective set changes.
     */
    public static synchronized String getSignature() {
        return getEffective().stream()
                .map(entry -> entry.name() + ":" + entry.sha1() + ":" + entry.priority())
                .collect(Collectors.joining(";"));
    }

    /**
     * Returns how many packs are saved across restarts.
     */
    public static synchronized int getPersistentCount() {
        ensureLoaded();
        return persistent.size();
    }

    /**
     * Replaces both sets (called after every file is downloaded).
     */
    public static synchronized void set(List<PackEntry> persistentNew, List<PackEntry> sessionNew) {
        ensureLoaded();
        // The url is not stored in state.json, so it is left out of the comparison.
        boolean persistentChanged = !withoutUrls(persistent).equals(withoutUrls(persistentNew));
        persistent = List.copyOf(persistentNew);
        session = List.copyOf(sessionNew);
        if (persistentChanged) {
            save();
        }
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

    private static List<PackEntry> withoutUrls(List<PackEntry> list) {
        return list.stream()
                .map(entry -> new PackEntry(entry.name(), "", entry.sha1(), entry.size(), entry.priority(),
                        entry.persistent()))
                .toList();
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
            for (JsonElement element : root.getAsJsonArray("persistent")) {
                JsonObject object = element.getAsJsonObject();
                String name = object.get("name").getAsString();
                String sha1 = object.get("sha1").getAsString();
                if (!PackCache.isValidName(name) || !PackCache.isValidSha1(sha1)) {
                    LOGGER.warn("Skipped an invalid entry in state.json: {}", name);
                    continue;
                }
                int priority = Math.max(MIN_PRIORITY, Math.min(MAX_PRIORITY, object.get("priority").getAsInt()));
                list.add(new PackEntry(name, "", sha1, Math.max(0L, object.get("size").getAsLong()), priority, true));
            }
            persistent = List.copyOf(list);
        } catch (IOException | RuntimeException ex) {
            // A corrupt state.json must not stop the game from starting; it only loses saved packs.
            LOGGER.warn("Could not read state.json, saved packs are dropped", ex);
            persistent = List.of();
        }
    }

    private static void save() {
        JsonArray array = new JsonArray();
        for (PackEntry entry : persistent) {
            JsonObject object = new JsonObject();
            object.addProperty("name", entry.name());
            object.addProperty("sha1", entry.sha1());
            object.addProperty("size", entry.size());
            object.addProperty("priority", entry.priority());
            array.add(object);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        root.add("persistent", array);
        try {
            AtomicFiles.writeAtomically(PackCache.stateFile(), GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            LOGGER.warn("Could not save state.json", ex);
        }
    }
}
