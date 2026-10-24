package net.dvmn2.resourcecontrol.client.pack;

import net.dvmn2.resourcecontrol.client.ResourceControlConfig;
import net.dvmn2.resourcecontrol.client.network.SyncPayload.PackEntry;
import net.dvmn2.resourcecontrol.client.state.ActivePacks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourcePackInfo;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.ResourcePackPosition;
import net.minecraft.resource.ResourcePackProfile;
import net.minecraft.resource.ResourcePackSource;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.ZipResourcePack;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Bridge between {@link ActivePacks} and the vanilla {@link ResourcePackManager}.
 * <p>
 * Profiles are required and pinned, so the player cannot switch them off in the resource pack menu, like
 * ordinary server packs. {@code ResourcePackManagerMixin} adds them on every {@code scanPacks()}.
 */
public final class PackProfiles {

    public static final String ID_PREFIX = "resourcecontrol/";

    private static final Logger LOGGER = LoggerFactory.getLogger("resourcecontrol");

    private static volatile boolean failed;

    private PackProfiles() {
    }

    /**
     * Returns {@code original} plus the profiles of every active pack. On any error the feature switches
     * itself off until the game restarts instead of breaking the resource pack screen.
     */
    public static Map<String, ResourcePackProfile> mergeInto(Map<String, ResourcePackProfile> original) {
        if (failed || !ResourceControlConfig.get().enabled) {
            return original;
        }
        try {
            Map<String, ResourcePackProfile> ours = buildProfiles();
            if (ours.isEmpty()) {
                return original;
            }
            Map<String, ResourcePackProfile> merged = new LinkedHashMap<>(original);
            merged.putAll(ours);
            return merged;
        } catch (RuntimeException ex) {
            failed = true;
            LOGGER.error("Could not add ResourceControl packs; they stay disabled until the game restarts", ex);
            return original;
        }
    }

    /**
     * Puts our packs at the end of the enabled list (the top layers), ordered by priority. Vanilla server
     * packs and the player's own packs stay below them.
     *
     * @return true when the order changed
     */
    public static boolean reorderEnabled(MinecraftClient client) {
        ResourcePackManager manager = client.getResourcePackManager();
        List<String> current = new ArrayList<>(manager.getEnabledIds());
        List<String> target = new ArrayList<>(current);
        target.removeIf(id -> id.startsWith(ID_PREFIX));
        for (PackEntry entry : ActivePacks.getEffective()) {
            String id = ID_PREFIX + entry.name();
            if (manager.getProfile(id) != null) {
                target.add(id);
            }
        }
        if (target.equals(current)) {
            return false;
        }
        manager.setEnabledProfiles(target);
        return true;
    }

    /**
     * Rescans packs, fixes the order and reloads resources. Client thread only.
     */
    public static CompletableFuture<Void> reload(MinecraftClient client) {
        client.getResourcePackManager().scanPacks();
        reorderEnabled(client);
        return client.reloadResources();
    }

    private static Map<String, ResourcePackProfile> buildProfiles() {
        Map<String, ResourcePackProfile> map = new LinkedHashMap<>();
        for (PackEntry entry : ActivePacks.getEffective()) {
            ResourcePackProfile profile = createProfile(entry);
            if (profile != null) {
                map.put(ID_PREFIX + entry.name(), profile);
            }
        }
        return map;
    }

    private static @Nullable ResourcePackProfile createProfile(PackEntry entry) {
        Path file = PackCache.file(entry.sha1());
        if (!Files.isRegularFile(file)) {
            return null;
        }
        // The player's shader block also covers saved packs that are mounted before any server connects.
        if (ResourceControlConfig.get().blockShaders && PackCache.inspect(file, false) != null) {
            LOGGER.debug("Pack {} skipped: it is invalid or contains shaders that the player blocked", entry.name());
            return null;
        }
        try {
            ResourcePackInfo info = new ResourcePackInfo(
                    ID_PREFIX + entry.name(),
                    Text.literal(entry.name()),
                    ResourcePackSource.SERVER,
                    Optional.empty());
            return ResourcePackProfile.create(
                    info,
                    new ZipResourcePack.ZipBackedFactory(file),
                    ResourceType.CLIENT_RESOURCES,
                    new ResourcePackPosition(true, ResourcePackProfile.InsertionPosition.TOP, true));
        } catch (Exception ex) {
            LOGGER.warn("Could not open pack {}", entry.name(), ex);
            return null;
        }
    }
}
