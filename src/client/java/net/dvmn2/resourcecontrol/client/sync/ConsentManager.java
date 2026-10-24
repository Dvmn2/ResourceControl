package net.dvmn2.resourcecontrol.client.sync;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Asks the player to allow pack downloads of a given size and remembers the answer. The decision lives ONLY
 * on the client. Client thread only.
 */
public final class ConsentManager {

    private static final long BYTES_PER_MEGABYTE = 1_048_576L;
    private static final long MEGABYTES_PER_GIGABYTE = 1024L;

    /**
     * Receives the outcome of a consent decision.
     */
    public interface Listener {

        /**
         * Downloads are allowed up to {@link ConsentManager#getGrantedLimit()}.
         */
        void onGranted(MinecraftClient client);

        /**
         * The player refused the request.
         */
        void onDeclined(MinecraftClient client);
    }

    private final Listener listener;

    private long grantedLimit;
    private long sessionApproved;
    private boolean declined;
    private long declinedLimit;
    private long pendingLimit;
    private int serial;
    private @Nullable ConfirmScreen screen;
    private @Nullable Screen parent;

    public ConsentManager(Listener listener) {
        this.listener = listener;
    }

    /**
     * Returns the effective limit for one pack; 0 means no consent, nothing may be downloaded.
     */
    public long getGrantedLimit() {
        return grantedLimit;
    }

    /**
     * Whether the player refused and has not been asked again since.
     */
    public boolean isDeclined() {
        return declined;
    }

    /**
     * The server asks for packs up to {@code requested} bytes. The player is asked only when this exceeds
     * what was already approved for the server.
     */
    public void onLimit(MinecraftClient client, long requested) {
        String key = ConsentStore.serverKey(client);
        long approvedBytes = Math.max(ConsentStore.approved(key), sessionApproved);
        if (approvedBytes >= requested) {
            declined = false;
            grantedLimit = requested;
            dismiss(client);
            listener.onGranted(client);
            return;
        }
        if (declined && declinedLimit == requested) {
            return;
        }
        declined = false;
        ask(client, requested);
    }

    /**
     * Closes an open dialog (if still on screen) and invalidates its callback.
     */
    public void dismiss(MinecraftClient client) {
        if (screen != null) {
            serial++;
            close(client);
        }
    }

    /**
     * Forgets everything: on leaving the server or switching the mod off.
     */
    public void reset() {
        grantedLimit = 0L;
        sessionApproved = 0L;
        declined = false;
        declinedLimit = 0L;
        pendingLimit = 0L;
        serial++;
        screen = null;
        parent = null;
    }

    private void ask(MinecraftClient client, long requested) {
        pendingLimit = requested;
        int current = ++serial;
        Screen previous = (screen != null && client.currentScreen == screen) ? parent : client.currentScreen;
        ConfirmScreen dialog = new ConfirmScreen(
                allowed -> onResult(client, current, allowed),
                Text.translatable("resourcecontrol.consent.title"),
                Text.translatable("resourcecontrol.consent.message", formatSize(requested)),
                Text.translatable("resourcecontrol.consent.allow"),
                Text.translatable("resourcecontrol.consent.deny"));
        screen = dialog;
        parent = previous;
        client.setScreen(dialog);
    }

    private void onResult(MinecraftClient client, int dialogSerial, boolean allowed) {
        if (dialogSerial != serial) {
            return;
        }
        long limit = pendingLimit;
        close(client);

        if (allowed) {
            ConsentStore.approve(ConsentStore.serverKey(client), limit);
            sessionApproved = Math.max(sessionApproved, limit);
            declined = false;
            grantedLimit = limit;
            listener.onGranted(client);
        } else {
            declined = true;
            declinedLimit = limit;
            listener.onDeclined(client);
        }
    }

    private void close(MinecraftClient client) {
        Screen previous = parent;
        boolean onScreen = screen != null && client.currentScreen == screen;
        screen = null;
        parent = null;
        if (onScreen) {
            client.setScreen(previous);
        }
    }

    private static Text formatSize(long bytes) {
        long megabytes = Math.max(1L, bytes / BYTES_PER_MEGABYTE);
        if (megabytes >= MEGABYTES_PER_GIGABYTE) {
            String gigabytes = megabytes % MEGABYTES_PER_GIGABYTE == 0
                    ? String.valueOf(megabytes / MEGABYTES_PER_GIGABYTE)
                    : String.format(Locale.ROOT, "%.1f", megabytes / (double) MEGABYTES_PER_GIGABYTE);
            return Text.translatable("resourcecontrol.size.gb", gigabytes);
        }
        return Text.translatable("resourcecontrol.size.mb", String.valueOf(megabytes));
    }
}
