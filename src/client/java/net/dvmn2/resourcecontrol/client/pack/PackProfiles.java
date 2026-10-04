package net.dvmn2.resourcecontrol.client.pack;

import net.dvmn2.resourcecontrol.client.network.SyncPayload.PackEntry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.*;
import net.minecraft.text.Text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Мост между {@link ActivePacks} и ванильным {@link ResourcePackManager}.
 * <p>
 * Профили создаются как required + pinned (fixedPosition) — игрок не может выключить их
 * в меню ресурспаков, как и обычные серверные паки. Подмешиваются в менеджер миксином
 * {@code ResourcePackManagerMixin} при каждом {@code scanPacks()}.
 */
public final class PackProfiles {

    public static final String ID_PREFIX = "resourcecontrol/";

    private PackProfiles() {
    }

    /**
     * Профили всех активных паков, чьи файлы есть в кеше.
     */
    public static Map<String, ResourcePackProfile> buildProfiles() {
        Map<String, ResourcePackProfile> map = new LinkedHashMap<>();
        for (PackEntry entry : ActivePacks.effective()) {
            ResourcePackProfile profile = createProfile(entry);
            if (profile != null) {
                map.put(ID_PREFIX + entry.name(), profile);
            }
        }
        return map;
    }

    private static ResourcePackProfile createProfile(PackEntry entry) {
        Path file = PackCache.file(entry.sha1());
        if (!Files.isRegularFile(file)) {
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
            System.err.println("[ResourceControl] Не удалось открыть пак " + entry.name() + ": " + ex.getMessage());
            return null;
        }
    }

    /**
     * Ставит наши паки в конец списка включённых (= верхние слои) в порядке priority.
     * Ванильные паки сервера и пакеты игрока остаются под ними.
     *
     * @return true, если порядок изменился
     */
    public static boolean reorderEnabled(MinecraftClient client) {
        ResourcePackManager manager = client.getResourcePackManager();
        List<String> current = new ArrayList<>(manager.getEnabledIds());
        List<String> target = new ArrayList<>(current);
        target.removeIf(id -> id.startsWith(ID_PREFIX));
        for (PackEntry entry : ActivePacks.effective()) {
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
     * Пересканировать паки, расставить порядок и перезагрузить ресурсы. Только в клиентском потоке.
     */
    public static CompletableFuture<Void> reload(MinecraftClient client) {
        client.getResourcePackManager().scanPacks();
        reorderEnabled(client);
        return client.reloadResources();
    }
}
