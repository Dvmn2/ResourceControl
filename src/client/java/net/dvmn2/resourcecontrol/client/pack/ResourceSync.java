package net.dvmn2.resourcecontrol.client.pack;

import net.dvmn2.resourcecontrol.client.network.LimitPayload;
import net.dvmn2.resourcecontrol.client.network.StatusPayload;
import net.dvmn2.resourcecontrol.client.network.SyncPayload;
import net.dvmn2.resourcecontrol.client.network.SyncPayload.PackEntry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
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

    // --- Согласие игрока на размер паков. Только клиентский поток. Решение живёт ТОЛЬКО на клиенте. ---

    /**
     * Действующий лимит размера одного пака. 0 — согласия нет, от сервера ничего не скачиваем.
     */
    private static long grantedLimit = 0L;

    /**
     * Одобрено в этой сессии (нужно, когда сервер нельзя опознать и согласие не сохраняется на диск).
     */
    private static long sessionApproved = 0L;

    /**
     * Игрок отказал на запрос declinedLimit; на тот же запрос повторно не спрашиваем до перезахода.
     */
    private static boolean declined = false;
    private static long declinedLimit = 0L;

    /**
     * Лимит, о котором сейчас спрашивает открытый диалог.
     */
    private static long pendingLimit = 0L;

    /**
     * Последний rc_sync, ожидающий решения игрока (новый заменяет старый).
     */
    private static SyncPayload heldSync = null;

    /**
     * Номер диалога: колбэк устаревшего диалога (после дисконнекта/нового запроса) игнорируется.
     */
    private static int consentSerial = 0;
    private static ConfirmScreen consentScreen = null;
    private static Screen consentParent = null;

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
        resetConsentState();
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
     * Клиентский поток. Без согласия игрока набор НЕ обрабатывается: ничего не скачивается и не применяется.
     */
    public static void onSync(MinecraftClient client, SyncPayload payload) {
        if (grantedLimit > 0) {
            startSync(client, payload, grantedLimit);
        } else if (!declined) {
            heldSync = payload; // ждём решения игрока
        }
        // declined и согласия нет — молча игнорируем
    }

    private static void startSync(MinecraftClient client, SyncPayload payload, long limit) {
        int generation = GENERATION.incrementAndGet();
        List<PackEntry> wanted;
        try {
            wanted = validate(payload, limit);
        } catch (IllegalArgumentException ex) {
            sendStatus(client, StatusPayload.STATE_FAILED, ex.getMessage());
            return;
        }
        boolean allowShaders = payload.allowShaders();
        EXECUTOR.submit(() -> process(client, generation, wanted, allowShaders, limit));
    }

    // ------------------------------------------------------------------
    // Запрос лимита и согласие игрока
    // ------------------------------------------------------------------

    /**
     * Сервер просит разрешить паки размером до N. Клиентский поток.
     * Спрашиваем игрока, только если N больше уже одобренного для этого сервера.
     */
    public static void onLimit(MinecraftClient client, LimitPayload payload) {
        if (payload.protocol() != PROTOCOL) {
            sendStatus(client, StatusPayload.STATE_FAILED, "unsupported protocol " + payload.protocol());
            return;
        }
        long requested = Math.min(payload.maxPackBytes(), PackCache.MAX_PACK_SIZE);
        if (requested <= 0) {
            sendStatus(client, StatusPayload.STATE_FAILED, "invalid size limit");
            return;
        }

        String key = ConsentStore.serverKey(client);
        long approvedBytes = Math.max(ConsentStore.approved(key), sessionApproved);
        if (approvedBytes >= requested) {
            // Лимит тот же или ниже одобренного — игрока не беспокоим.
            declined = false;
            grantedLimit = requested;
            dismissDialog(client);
            processHeld(client);
            return;
        }
        if (declined && declinedLimit == requested) {
            return;
        }
        declined = false;
        askConsent(client, requested);
    }

    private static void askConsent(MinecraftClient client, long requested) {
        pendingLimit = requested;
        int serial = ++consentSerial;
        Screen parent = (consentScreen != null && client.currentScreen == consentScreen)
                ? consentParent : client.currentScreen;
        ConfirmScreen screen = new ConfirmScreen(
                allowed -> onConsentResult(client, serial, allowed),
                Text.translatable("resourcecontrol.consent.title"),
                Text.translatable("resourcecontrol.consent.message", formatSize(requested)),
                Text.translatable("resourcecontrol.consent.allow"),
                Text.translatable("resourcecontrol.consent.deny"));
        consentScreen = screen;
        consentParent = parent;
        client.setScreen(screen);
    }

    private static void onConsentResult(MinecraftClient client, int serial, boolean allowed) {
        if (serial != consentSerial) {
            return;
        }
        long limit = pendingLimit;
        closeDialog(client);

        if (allowed) {
            String key = ConsentStore.serverKey(client);
            if (key != null) {
                ConsentStore.approve(key, limit);
            }
            sessionApproved = Math.max(sessionApproved, limit);
            declined = false;
            grantedLimit = limit;
            processHeld(client);
        } else {
            declined = true;
            declinedLimit = limit;
            if (grantedLimit <= 0) {
                heldSync = null;
                GENERATION.incrementAndGet(); // отменить возможную загрузку
            }
            // Только информация для админа; на решения сервера это не влияет.
            sendStatus(client, StatusPayload.STATE_FAILED, "declined by player");
        }
    }

    private static void processHeld(MinecraftClient client) {
        SyncPayload held = heldSync;
        heldSync = null;
        if (held != null) {
            startSync(client, held, grantedLimit);
        }
    }

    /**
     * Закрывает открытый диалог (если он ещё на экране) и инвалидирует его колбэк.
     */
    private static void dismissDialog(MinecraftClient client) {
        if (consentScreen != null) {
            consentSerial++;
            closeDialog(client);
        }
    }

    private static void closeDialog(MinecraftClient client) {
        Screen parent = consentParent;
        boolean onScreen = consentScreen != null && client.currentScreen == consentScreen;
        consentScreen = null;
        consentParent = null;
        if (onScreen) {
            client.setScreen(parent);
        }
    }

    private static void resetConsentState() {
        grantedLimit = 0L;
        sessionApproved = 0L;
        declined = false;
        declinedLimit = 0L;
        pendingLimit = 0L;
        heldSync = null;
        consentSerial++; // колбэк открытого диалога больше не сработает
        consentScreen = null; // сам экран заменит ванильный код дисконнекта
        consentParent = null;
    }

    private static Text formatSize(long bytes) {
        long mb = Math.max(1L, bytes / 1048576L);
        if (mb >= 1024) {
            String gb = mb % 1024 == 0
                    ? String.valueOf(mb / 1024)
                    : String.format(Locale.ROOT, "%.1f", mb / 1024.0);
            return Text.translatable("resourcecontrol.size.gb", gb);
        }
        return Text.translatable("resourcecontrol.size.mb", String.valueOf(mb));
    }

    private static List<PackEntry> validate(SyncPayload payload, long limit) {
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
            if (e.size() <= 0 || e.size() > limit || e.size() > PackCache.MAX_PACK_SIZE) {
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

    private static void process(MinecraftClient client, int generation, List<PackEntry> wanted,
                                boolean allowShaders, long limit) {
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
                    downloadWithProgress(client, generation, entry, index, missing, limit);
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
                                             int index, int total, long limit) throws IOException, InterruptedException {
        long[] lastUpdate = {0L};
        PackDownloader.download(entry, limit, () -> stale(generation), (done, size) -> {
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
