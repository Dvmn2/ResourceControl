package net.dvmn2.resourcecontrol.client.network;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;

/**
 * Sends status messages to the server plugin.
 */
public final class StatusReporter {

    /**
     * Short enough to stay under the plugin's 512-byte limit even with multi-byte characters.
     */
    private static final int MAX_MESSAGE_CHARS = 160;

    private StatusReporter() {
    }

    /**
     * Sends a status from any thread; does nothing when the server has no matching channel.
     */
    public static void send(MinecraftClient client, StatusPayload.State state, String message) {
        String trimmed = message.length() > MAX_MESSAGE_CHARS ? message.substring(0, MAX_MESSAGE_CHARS) : message;
        client.execute(() -> {
            if (ClientPlayNetworking.canSend(StatusPayload.ID)) {
                ClientPlayNetworking.send(new StatusPayload(ResourceControlProtocol.PROTOCOL, state, trimmed));
            }
        });
    }
}
