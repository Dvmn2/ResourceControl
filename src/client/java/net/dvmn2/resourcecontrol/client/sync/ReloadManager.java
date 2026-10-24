package net.dvmn2.resourcecontrol.client.sync;

import net.dvmn2.resourcecontrol.client.pack.PackProfiles;
import net.dvmn2.resourcecontrol.client.state.ActivePacks;
import net.dvmn2.resourcecontrol.client.util.Overlay;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts resource reloads when it is safe to do so. Client thread only.
 * <p>
 * Reloading right inside the DISCONNECT event races with the world being torn down (Voxy/Iris/Sodium and
 * vanilla workers) and may never finish, leaving the red loading screen forever. So callers only mark that
 * a reload is needed, and {@link #onTick} starts it later.
 */
public final class ReloadManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("resourcecontrol");

    private @Nullable String appliedSignature;
    private boolean reloadRequested;
    private boolean deferUntilOutOfWorld;
    private boolean announceDone;

    /**
     * Remembers what the game started with and fixes the pack order if needed. Call once on client start.
     */
    public void onClientStarted(MinecraftClient client) {
        appliedSignature = ActivePacks.getSignature();
        if (PackProfiles.reorderEnabled(client)) {
            PackProfiles.reload(client);
        }
    }

    /**
     * Whether the active set differs from what is applied to the resources.
     */
    public boolean hasUnappliedChanges() {
        return !ActivePacks.getSignature().equals(appliedSignature);
    }

    /**
     * Marks that a reload is needed.
     *
     * @param untilOutOfWorld wait until the world has been closed
     * @param announce        show "applying" and "done" messages
     */
    public void request(boolean untilOutOfWorld, boolean announce) {
        // A pending request without a delay must not be postponed by a later request with one.
        deferUntilOutOfWorld = reloadRequested ? (deferUntilOutOfWorld && untilOutOfWorld) : untilOutOfWorld;
        announceDone = announce;
        reloadRequested = true;
    }

    /**
     * Requests a reload only when the active set changed.
     */
    public void requestIfChanged(boolean untilOutOfWorld) {
        if (hasUnappliedChanges()) {
            request(untilOutOfWorld, false);
        }
    }

    /**
     * Called every client tick. Starts the pending reload when no other reload is running and, if asked,
     * the world is closed. The applied signature is fixed only at the moment of the real start, because
     * vanilla ignores a second reload while one is running.
     */
    public void onTick(MinecraftClient client) {
        if (!reloadRequested || client.getOverlay() != null) {
            return;
        }
        if (deferUntilOutOfWorld && client.world != null) {
            return;
        }
        reloadRequested = false;
        boolean announce = announceDone;
        announceDone = false;

        String signature = ActivePacks.getSignature();
        if (signature.equals(appliedSignature)) {
            return;
        }
        appliedSignature = signature;

        if (announce) {
            Overlay.show(client, Text.translatable("resourcecontrol.applying"));
        }
        try {
            PackProfiles.reload(client).whenComplete((ignored, error) -> client.execute(() -> {
                if (error != null) {
                    LOGGER.error("Resource reload failed", error);
                } else if (announce) {
                    Overlay.show(client, Text.translatable("resourcecontrol.done"));
                }
            }));
        } catch (RuntimeException ex) {
            LOGGER.error("Could not start a resource reload", ex);
        }
    }
}
