package net.dvmn2.resourcecontrol.client.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * Action bar messages that are safe to request from any thread.
 */
public final class Overlay {

    private Overlay() {
    }

    /**
     * Shows a message above the hotbar when the player is in a world.
     */
    public static void show(MinecraftClient client, Text text) {
        client.execute(() -> {
            if (client.inGameHud != null && client.world != null) {
                client.inGameHud.setOverlayMessage(text, false);
            }
        });
    }
}
