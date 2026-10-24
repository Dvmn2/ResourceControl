package net.dvmn2.resourcecontrol.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.dvmn2.resourcecontrol.client.util.AtomicFiles;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Player-side settings, stored in {@code config/resourcecontrol.json}. The server cannot override them.
 */
public final class ResourceControlConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("resourcecontrol");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "resourcecontrol.json";

    private static @Nullable ResourceControlConfig instance;

    /**
     * When false the mod ignores the server completely and keeps no packs.
     */
    public boolean enabled = true;

    /**
     * When true, packs with core shaders are rejected even if the server allows them.
     */
    public boolean blockShaders = false;

    /**
     * Returns the config, loading it from disk on first use.
     */
    public static synchronized ResourceControlConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    /**
     * Writes the current config to disk.
     */
    public static synchronized void save() {
        Path file = path();
        try {
            AtomicFiles.writeAtomically(file, GSON.toJson(get()).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            LOGGER.warn("Could not save {}", file, ex);
        }
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    private static ResourceControlConfig load() {
        Path file = path();
        if (!Files.isRegularFile(file)) {
            return new ResourceControlConfig();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            ResourceControlConfig loaded = GSON.fromJson(reader, ResourceControlConfig.class);
            return loaded == null ? new ResourceControlConfig() : loaded;
        } catch (IOException | JsonParseException ex) {
            LOGGER.warn("Could not read {}, using defaults", file, ex);
            return new ResourceControlConfig();
        }
    }
}
