package net.dvmn2.resourcecontrol.client.sync;

import net.dvmn2.resourcecontrol.client.ResourceControlConfig;
import net.dvmn2.resourcecontrol.client.network.LimitPayload;
import net.dvmn2.resourcecontrol.client.network.ResourceControlProtocol;
import net.dvmn2.resourcecontrol.client.network.StatusPayload.State;
import net.dvmn2.resourcecontrol.client.network.StatusReporter;
import net.dvmn2.resourcecontrol.client.network.SyncPayload;
import net.dvmn2.resourcecontrol.client.network.SyncPayload.PackEntry;
import net.dvmn2.resourcecontrol.client.pack.PackCache;
import net.dvmn2.resourcecontrol.client.pack.PackDownloader;
import net.dvmn2.resourcecontrol.client.pack.PackValidator;
import net.dvmn2.resourcecontrol.client.state.ActivePacks;
import net.dvmn2.resourcecontrol.client.util.Overlay;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Receives the wanted pack set from the plugin, downloads what is missing (progress in the action bar),
 * applies the packs and reloads resources. Drops session packs when the player leaves the server.
 * Public methods run on the client thread.
 */
public final class SyncManager implements ConsentManager.Listener {

    private static final Logger LOGGER = LoggerFactory.getLogger("resourcecontrol");

    private static final long PROGRESS_INTERVAL_NANOS = 100_000_000L;
    private static final int PROGRESS_BAR_CELLS = 20;
    private static final int PERCENT_FULL = 100;
    private static final int PERCENT_PER_CELL = PERCENT_FULL / PROGRESS_BAR_CELLS;
    private static final double BYTES_PER_MEGABYTE = 1_048_576.0;

    private final AtomicInteger generation = new AtomicInteger();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ResourceControl-Downloader");
        thread.setDaemon(true);
        return thread;
    });
    private final ReloadManager reload;
    private final ConsentManager consent;

    private @Nullable LimitPayload lastLimit;
    private @Nullable SyncPayload lastSync;
    private @Nullable SyncPayload heldSync;

    public SyncManager(ReloadManager reload) {
        this.reload = reload;
        this.consent = new ConsentManager(this);
    }

    /**
     * Called once after the client started: refreshes the cache retention of packs in use.
     */
    public void onClientStarted(MinecraftClient client) {
        reload.onClientStarted(client);
        executor.submit(() -> PackCache.cleanup(shaSet()));
    }

    /**
     * The server asks the player to allow downloads of up to N bytes per pack.
     */
    public void onLimit(MinecraftClient client, LimitPayload payload) {
        if (payload.protocol() != ResourceControlProtocol.PROTOCOL) {
            return;
        }
        lastLimit = payload;
        if (!ResourceControlConfig.get().enabled) {
            return;
        }
        long requested = Math.min(payload.maxPackBytes(), PackCache.MAX_PACK_SIZE);
        if (requested <= 0) {
            StatusReporter.send(client, State.FAILED, "invalid size limit");
            return;
        }
        consent.onLimit(client, requested);
    }

    /**
     * The server sent the complete wanted set. Without the player's consent nothing is downloaded or applied.
     */
    public void onSync(MinecraftClient client, SyncPayload payload) {
        if (payload.protocol() != ResourceControlProtocol.PROTOCOL) {
            return;
        }
        lastSync = payload;
        if (!ResourceControlConfig.get().enabled) {
            return;
        }
        long granted = consent.getGrantedLimit();
        if (granted > 0) {
            start(client, payload, granted);
        } else if (!consent.isDeclined()) {
            heldSync = payload;
        }
    }

    /**
     * The player left the server: session packs go, saved ones stay (main menu panorama).
     */
    public void onDisconnect(MinecraftClient client) {
        generation.incrementAndGet();
        consent.reset();
        heldSync = null;
        lastSync = null;
        lastLimit = null;
        ActivePacks.clearSession();
        client.execute(() -> reload.requestIfChanged(true));
    }

    /**
     * Switches the mod on or off (player command). Off removes every pack and ignores the server.
     */
    public void setEnabled(MinecraftClient client, boolean enabled) {
        ResourceControlConfig config = ResourceControlConfig.get();
        if (config.enabled == enabled) {
            return;
        }
        config.enabled = enabled;
        ResourceControlConfig.save();
        if (enabled) {
            replay(client);
        } else {
            disable(client);
        }
    }

    /**
     * Blocks or allows packs with shaders (player command) and re-evaluates the last set from the server.
     */
    public void setShadersBlocked(MinecraftClient client, boolean blocked) {
        ResourceControlConfig config = ResourceControlConfig.get();
        config.blockShaders = blocked;
        ResourceControlConfig.save();
        SyncPayload sync = lastSync;
        if (config.enabled && sync != null) {
            onSync(client, sync);
        } else {
            reload.request(false, false);
        }
    }

    /**
     * Removes saved packs (player command).
     */
    public void clearPersistent() {
        generation.incrementAndGet();
        ActivePacks.clearPersistent();
        reload.request(false, false);
    }

    @Override
    public void onGranted(MinecraftClient client) {
        SyncPayload held = heldSync;
        heldSync = null;
        if (held != null) {
            start(client, held, consent.getGrantedLimit());
        }
    }

    @Override
    public void onDeclined(MinecraftClient client) {
        if (consent.getGrantedLimit() <= 0) {
            heldSync = null;
            generation.incrementAndGet();
        }
        // Information for the server admin only; it does not influence the server's decisions.
        StatusReporter.send(client, State.FAILED, "declined by player");
    }

    private void disable(MinecraftClient client) {
        generation.incrementAndGet();
        heldSync = null;
        consent.dismiss(client);
        consent.reset();
        ActivePacks.clearSession();
        ActivePacks.clearPersistent();
        reload.request(false, false);
        StatusReporter.send(client, State.FAILED, "disabled by player");
    }

    /**
     * Applies the last messages again, as if the server had just sent them.
     */
    private void replay(MinecraftClient client) {
        LimitPayload limit = lastLimit;
        if (limit != null) {
            onLimit(client, limit);
        }
        SyncPayload sync = lastSync;
        if (sync != null) {
            onSync(client, sync);
        }
    }

    private void start(MinecraftClient client, SyncPayload payload, long limit) {
        int current = generation.incrementAndGet();
        List<PackEntry> wanted;
        try {
            wanted = PackValidator.validate(payload, limit);
        } catch (IllegalArgumentException ex) {
            StatusReporter.send(client, State.FAILED, ex.getMessage());
            return;
        }
        boolean allowShaders = payload.allowShaders() && !ResourceControlConfig.get().blockShaders;
        executor.submit(() -> run(client, current, wanted, allowShaders, limit));
    }

    private boolean isStale(int expectedGeneration) {
        return expectedGeneration != generation.get();
    }

    private void run(MinecraftClient client, int expectedGeneration, List<PackEntry> wanted,
                     boolean allowShaders, long limit) {
        try {
            process(client, expectedGeneration, wanted, allowShaders, limit);
        } catch (RuntimeException ex) {
            // Last line of defence of the background thread: report instead of silently losing the task.
            LOGGER.error("Unexpected error while processing the pack set", ex);
            StatusReporter.send(client, State.FAILED, "internal error");
        }
    }

    private void process(MinecraftClient client, int expectedGeneration, List<PackEntry> wanted,
                         boolean allowShaders, long limit) {
        List<PackEntry> ready = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        int missing = 0;
        for (PackEntry entry : wanted) {
            if (!isCached(entry)) {
                missing++;
            }
        }
        if (missing > 0) {
            StatusReporter.send(client, State.DOWNLOADING, "downloading " + missing + " pack(s)");
        }

        int index = 0;
        for (PackEntry entry : wanted) {
            if (isStale(expectedGeneration)) {
                return;
            }
            try {
                if (!isCached(entry)) {
                    index++;
                    downloadWithProgress(client, expectedGeneration, entry, index, missing, limit);
                }
                String problem = PackCache.inspect(PackCache.file(entry.sha1()), allowShaders);
                if (problem != null) {
                    throw new IOException(problem);
                }
                ready.add(entry);
            } catch (PackDownloader.CancelledException cancelled) {
                return;
            } catch (IOException ex) {
                failures.add(entry.name() + ": " + ex.getMessage());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        if (isStale(expectedGeneration)) {
            return;
        }
        client.execute(() -> finish(client, expectedGeneration, ready, failures));
    }

    private static boolean isCached(PackEntry entry) {
        Path file = PackCache.file(entry.sha1());
        try {
            return Files.isRegularFile(file) && Files.size(file) == entry.size();
        } catch (IOException ex) {
            return false;
        }
    }

    private void downloadWithProgress(MinecraftClient client, int expectedGeneration, PackEntry entry,
                                      int index, int total, long limit) throws IOException, InterruptedException {
        long[] lastUpdate = {0L};
        PackDownloader.download(entry, limit, () -> isStale(expectedGeneration), (done, size) -> {
            long now = System.nanoTime();
            if (now - lastUpdate[0] < PROGRESS_INTERVAL_NANOS && done < size) {
                return;
            }
            lastUpdate[0] = now;
            int percent = (int) (done * PERCENT_FULL / size);
            int filled = percent / PERCENT_PER_CELL;
            String bar = "█".repeat(filled) + "░".repeat(PROGRESS_BAR_CELLS - filled);
            String megabytes = String.format(Locale.ROOT, "%.1f/%.1f MB", done / BYTES_PER_MEGABYTE,
                    size / BYTES_PER_MEGABYTE);
            Overlay.show(client, Text.translatable("resourcecontrol.progress", entry.name(),
                    String.valueOf(index), String.valueOf(total), bar, String.valueOf(percent), megabytes));
        });
    }

    private void finish(MinecraftClient client, int expectedGeneration, List<PackEntry> ready,
                        List<String> failures) {
        if (isStale(expectedGeneration)) {
            return;
        }
        List<PackEntry> persistent = new ArrayList<>();
        List<PackEntry> session = new ArrayList<>();
        for (PackEntry entry : ready) {
            (entry.persistent() ? persistent : session).add(entry);
        }
        ActivePacks.set(persistent, session);

        Set<String> keep = shaSet();
        executor.submit(() -> PackCache.cleanup(keep));

        if (reload.hasUnappliedChanges()) {
            reload.request(false, failures.isEmpty());
        }

        if (failures.isEmpty()) {
            StatusReporter.send(client, State.READY, "ok");
        } else {
            String message = String.join("; ", failures);
            Overlay.show(client, Text.translatable("resourcecontrol.failed", message));
            StatusReporter.send(client, State.FAILED, message);
        }
    }

    private static Set<String> shaSet() {
        Set<String> result = new HashSet<>();
        for (PackEntry entry : ActivePacks.getEffective()) {
            result.add(entry.sha1());
        }
        return result;
    }
}
