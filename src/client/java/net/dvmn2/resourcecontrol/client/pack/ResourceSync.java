package net.dvmn2.resourcecontrol.client.pack;

import net.dvmn2.resourcecontrol.client.network.StatusPayload;
import net.dvmn2.resourcecontrol.client.network.SyncPayload;
import net.dvmn2.resourcecontrol.client.network.SyncPayload.PackEntry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Оркестратор: получает желаемый набор от плагина, докачивает недостающее (прогресс — в action bar),
 * применяет паки и перезагружает ресурсы. Сбрасывает сессионные паки при выходе с сервера.
 */
public final class ResourceSync {

    private static final int PROTOCOL = 1;
    private static final long PROGRESS_INTERVAL_NANOS = 100_000_000L;

    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ResourceControl-Downloader");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Подпись набора, который реально применён к ресурсам клиента. Только клиентский поток.
     */
    private static String appliedSignature = null;

    /**
     * Перезагрузка ресурсов запрошена, но ещё не запущена. Только клиентский поток.
     * Реально запускается в {@link #onTick}, когда это безопасно (нет другой перезагрузки/оверлея).
     */
    private static boolean reloadRequested = false;

    /**
     * Не запускать перезагрузку, пока клиент ещё в мире (после дисконнекта мир ещё разбирается).
     */
    private static boolean deferUntilOutOfWorld = false;

    /**
     * Показать «готово» после завершения перезагрузки.
     */
    private static boolean announceDone = false;

    private ResourceSync() {
    }

    // ------------------------------------------------------------------
    // Жизненный цикл
    // ------------------------------------------------------------------

    /**
     * Вызывается один раз после старта клиента (клиентский поток).
     */
    public static void onClientStarted(MinecraftClient client) {
        appliedSignature = ActivePacks.signature();
        // Если при старте ванильный код расставил несколько наших паков в другом порядке — выровнять.
        if (PackProfiles.reorderEnabled(client)) {
            PackProfiles.reload(client);
        }
    }

    /**
     * Дисконнект: сессионные паки уходят, постоянные остаются (панорама меню).
     * <p>
     * Событие DISCONNECT приходит ДО того, как ванильный код закончил разбирать мир. Запускать
     * перезагрузку ресурсов прямо здесь нельзя: она гонится с закрытием мира (Voxy/Iris/Sodium и
     * ванильные рабочие потоки) и может не завершиться — красный экран остаётся навсегда.
     * Поэтому только помечаем, что перезагрузка нужна; запускает её {@link #onTick}.
     */
    public static void onDisconnect(MinecraftClient client) {
        GENERATION.incrementAndGet();
        ActivePacks.clearSession();
        client.execute(() -> {
            if (!ActivePacks.signature().equals(appliedSignature)) {
                requestReload(true, false);
            }
        });
    }

    /**
     * Клиентская команда /rcclear.
     */
    public static void clearPersistent(MinecraftClient client) {
        GENERATION.incrementAndGet();
        ActivePacks.clearPersistent();
        requestReload(false, false);
    }

    // ------------------------------------------------------------------
    // Отложенная перезагрузка ресурсов
    // ------------------------------------------------------------------

    private static void requestReload(boolean untilOutOfWorld, boolean announce) {
        // если уже ждёт запрос без отсрочки — отсрочка не должна его задерживать
        deferUntilOutOfWorld = reloadRequested ? (deferUntilOutOfWorld && untilOutOfWorld) : untilOutOfWorld;
        announceDone = announce;
        reloadRequested = true;
    }

    /**
     * Каждый клиентский тик (END_CLIENT_TICK). Запускает отложенную перезагрузку, когда безопасно:
     * сейчас не идёт другая перезагрузка (оверлей) и, для дисконнекта, мир уже закрыт.
     * Повторный reloadResources() во время идущей перезагрузки ванилью игнорируется, поэтому
     * подпись «применённого» набора фиксируется только в момент реального запуска.
     */
    public static void onTick(MinecraftClient client) {
        if (!reloadRequested || client.getOverlay() != null) {
            return;
        }
        if (deferUntilOutOfWorld && client.world != null) {
            return;
        }
        reloadRequested = false;
        boolean announce = announceDone;
        announceDone = false;

        String signature = ActivePacks.signature();
        if (signature.equals(appliedSignature)) {
            return;
        }
        appliedSignature = signature;

        if (announce) {
            showOverlay(client, Text.translatable("resourcecontrol.applying"));
        }
        try {
            PackProfiles.reload(client).whenComplete((ignored, error) -> client.execute(() -> {
                if (error != null) {
                    System.err.println("[ResourceControl] Перезагрузка ресурсов завершилась ошибкой: " + error);
                } else if (announce) {
                    showOverlay(client, Text.translatable("resourcecontrol.done"));
                }
            }));
        } catch (RuntimeException ex) {
            System.err.println("[ResourceControl] Не удалось запустить перезагрузку ресурсов: " + ex);
        }
    }

    // ------------------------------------------------------------------
    // Приём набора от плагина
    // ------------------------------------------------------------------

    /**
     * Клиентский поток.
     */
    public static void onSync(MinecraftClient client, SyncPayload payload) {
        int generation = GENERATION.incrementAndGet();
        List<PackEntry> wanted;
        try {
            wanted = validate(payload);
        } catch (IllegalArgumentException ex) {
            sendStatus(client, StatusPayload.STATE_FAILED, ex.getMessage());
            return;
        }
        boolean allowShaders = payload.allowShaders();
        EXECUTOR.submit(() -> process(client, generation, wanted, allowShaders));
    }

    private static List<PackEntry> validate(SyncPayload payload) {
        if (payload.protocol() != PROTOCOL) {
            throw new IllegalArgumentException("unsupported protocol " + payload.protocol());
        }
        Set<String> names = new HashSet<>();
        for (PackEntry e : payload.packs()) {
            if (!PackCache.isValidName(e.name())) {
                throw new IllegalArgumentException("invalid pack name");
            }
            if (!names.add(e.name())) {
                throw new IllegalArgumentException("duplicate pack " + e.name());
            }
            if (!PackCache.isValidSha1(e.sha1())) {
                throw new IllegalArgumentException("invalid sha1 for " + e.name());
            }
            if (e.size() <= 0 || e.size() > PackCache.MAX_PACK_SIZE) {
                throw new IllegalArgumentException("invalid size for " + e.name());
            }
            try {
                URI uri = URI.create(e.url());
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || e.url().length() > 512) {
                    throw new IllegalArgumentException("url must be https for " + e.name());
                }
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("bad url for " + e.name());
            }
        }
        return payload.packs();
    }

    // ------------------------------------------------------------------
    // Фоновая загрузка
    // ------------------------------------------------------------------

    private static boolean stale(int generation) {
        return generation != GENERATION.get();
    }

    private static void process(MinecraftClient client, int generation, List<PackEntry> wanted, boolean allowShaders) {
        List<PackEntry> ready = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        int missing = 0;
        for (PackEntry e : wanted) {
            if (!isCached(e)) {
                missing++;
            }
        }
        if (missing > 0) {
            sendStatus(client, StatusPayload.STATE_DOWNLOADING, "downloading " + missing + " pack(s)");
        }

        int index = 0;
        for (PackEntry entry : wanted) {
            if (stale(generation)) {
                return;
            }
            try {
                if (!isCached(entry)) {
                    index++;
                    downloadWithProgress(client, generation, entry, index, missing);
                }
                String problem = PackCache.inspect(PackCache.file(entry.sha1()), allowShaders);
                if (problem != null) {
                    throw new IOException(problem);
                }
                ready.add(entry);
            } catch (PackDownloader.CancelledException cancelled) {
                return;
            } catch (Exception ex) {
                failures.add(entry.name() + ": " + ex.getMessage());
            }
        }

        if (stale(generation)) {
            return;
        }
        client.execute(() -> finish(client, generation, ready, failures));
    }

    private static boolean isCached(PackEntry entry) {
        try {
            var file = PackCache.file(entry.sha1());
            return Files.isRegularFile(file) && Files.size(file) == entry.size();
        } catch (IOException ex) {
            return false;
        }
    }

    private static void downloadWithProgress(MinecraftClient client, int generation, PackEntry entry,
                                             int index, int total) throws IOException, InterruptedException {
        long[] lastUpdate = {0L};
        PackDownloader.download(entry, () -> stale(generation), (done, size) -> {
            long now = System.nanoTime();
            if (now - lastUpdate[0] < PROGRESS_INTERVAL_NANOS && done < size) {
                return;
            }
            lastUpdate[0] = now;
            int percent = (int) (done * 100 / size);
            int filled = percent / 5;
            String bar = "█".repeat(filled) + "░".repeat(20 - filled);
            String megabytes = String.format(Locale.ROOT, "%.1f/%.1f MB", done / 1048576.0, size / 1048576.0);
            showOverlay(client, Text.translatable("resourcecontrol.progress",
                    entry.name(), String.valueOf(index), String.valueOf(total), bar, String.valueOf(percent), megabytes));
        });
    }

    // ------------------------------------------------------------------
    // Применение (клиентский поток)
    // ------------------------------------------------------------------

    private static void finish(MinecraftClient client, int generation, List<PackEntry> ready, List<String> failures) {
        if (stale(generation)) {
            return;
        }
        List<PackEntry> persistent = new ArrayList<>();
        List<PackEntry> session = new ArrayList<>();
        for (PackEntry e : ready) {
            (e.persistent() ? persistent : session).add(e);
        }
        ActivePacks.set(persistent, session);

        String signature = ActivePacks.signature();
        boolean changed = !signature.equals(appliedSignature);

        Set<String> keep = new HashSet<>();
        for (PackEntry e : ActivePacks.effective()) {
            keep.add(e.sha1());
        }
        EXECUTOR.submit(() -> PackCache.cleanup(keep));

        if (changed) {
            requestReload(false, failures.isEmpty());
        }

        if (failures.isEmpty()) {
            sendStatus(client, StatusPayload.STATE_READY, "ok");
        } else {
            String message = String.join("; ", failures);
            showOverlay(client, Text.translatable("resourcecontrol.failed", message));
            sendStatus(client, StatusPayload.STATE_FAILED, message);
        }
    }

    // ------------------------------------------------------------------
    // Утилиты
    // ------------------------------------------------------------------

    private static void showOverlay(MinecraftClient client, Text text) {
        client.execute(() -> {
            if (client.inGameHud != null && client.world != null) {
                client.inGameHud.setOverlayMessage(text, false);
            }
        });
    }

    private static void sendStatus(MinecraftClient client, int state, String message) {
        String trimmed = message.length() > 400 ? message.substring(0, 400) : message;
        client.execute(() -> {
            if (ClientPlayNetworking.canSend(StatusPayload.ID)) {
                ClientPlayNetworking.send(new StatusPayload(PROTOCOL, state, trimmed));
            }
        });
    }
}