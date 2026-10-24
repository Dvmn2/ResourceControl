package net.dvmn2.resourcecontrol.client.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.dvmn2.resourcecontrol.client.pack.PackCache;
import net.dvmn2.resourcecontrol.client.util.AtomicFiles;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The player's decisions "allow downloading packs up to N bytes", one per server, stored in
 * {@code <gameDir>/resourcecontrol/consent.json}:
 * <pre>{ "version": 1, "servers": { "play.example.com": 1073741824 } }</pre>
 * Read and written ONLY on the client. The decision is never sent to the server and has no effect there,
 * so it cannot be forged over the network. A corrupt file means no consents.
 */
public final class ConsentStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("resourcecontrol");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int FORMAT_VERSION = 1;
    private static final String DEFAULT_PORT_SUFFIX = ":25565";

    private static final Map<String, Long> APPROVED = new HashMap<>();
    private static boolean loaded;

    private ConsentStore() {
    }

    /**
     * Returns a key for the current server: the address from the server list, lowercase, without a trailing
     * dot and without the default port. Null when the server cannot be identified (singleplayer, replay):
     * then consent lasts until the player leaves and is not written to disk.
     */
    public static @Nullable String serverKey(MinecraftClient client) {
        ServerInfo info = client.getCurrentServerEntry();
        if (info == null || info.address == null) {
            return null;
        }
        String address = info.address.trim().toLowerCase(Locale.ROOT);
        if (address.endsWith(DEFAULT_PORT_SUFFIX)) {
            address = address.substring(0, address.length() - DEFAULT_PORT_SUFFIX.length());
        }
        while (address.endsWith(".")) {
            address = address.substring(0, address.length() - 1);
        }
        return address.isEmpty() ? null : address;
    }

    /**
     * Returns how many bytes per pack the player already allowed this server (0 = nothing).
     */
    public static synchronized long approved(@Nullable String key) {
        if (key == null) {
            return 0L;
        }
        ensureLoaded();
        return APPROVED.getOrDefault(key, 0L);
    }

    /**
     * Remembers the player's approval and saves it.
     */
    public static synchronized void approve(@Nullable String key, long bytes) {
        if (key == null) {
            return;
        }
        ensureLoaded();
        APPROVED.put(key, Math.min(bytes, PackCache.MAX_PACK_SIZE));
        save();
    }

    private static Path file() {
        return PackCache.root().resolve("consent.json");
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
            for (Map.Entry<String, JsonElement> entry : servers.entrySet()) {
                long value = entry.getValue().getAsLong();
                if (value > 0) {
                    APPROVED.put(entry.getKey(), Math.min(value, PackCache.MAX_PACK_SIZE));
                }
            }
        } catch (IOException | RuntimeException ex) {
            // A corrupt file means "no consents": the player is simply asked again.
            LOGGER.warn("Could not read consent.json, consents are dropped", ex);
            APPROVED.clear();
        }
    }

    private static void save() {
        JsonObject servers = new JsonObject();
        APPROVED.forEach(servers::addProperty);
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        root.add("servers", servers);
        try {
            AtomicFiles.writeAtomically(file(), GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            LOGGER.warn("Could not save consent.json", ex);
        }
    }
}
