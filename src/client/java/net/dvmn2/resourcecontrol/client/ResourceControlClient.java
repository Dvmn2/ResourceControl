package net.dvmn2.resourcecontrol.client;

import net.dvmn2.resourcecontrol.client.command.ResourceControlCommand;
import net.dvmn2.resourcecontrol.client.network.LimitPayload;
import net.dvmn2.resourcecontrol.client.network.StatusPayload;
import net.dvmn2.resourcecontrol.client.network.SyncPayload;
import net.dvmn2.resourcecontrol.client.sync.ReloadManager;
import net.dvmn2.resourcecontrol.client.sync.SyncManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/**
 * Client entry point: registers payloads, receivers, events and the command.
 */
public final class ResourceControlClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ReloadManager reload = new ReloadManager();
        SyncManager sync = new SyncManager(reload);

        // Registering a receiver also tells the server (minecraft:register) that the mod is installed.
        PayloadTypeRegistry.playS2C().register(LimitPayload.ID, LimitPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SyncPayload.ID, SyncPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(StatusPayload.ID, StatusPayload.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(LimitPayload.ID, (payload, context) ->
                context.client().execute(() -> sync.onLimit(context.client(), payload)));
        ClientPlayNetworking.registerGlobalReceiver(SyncPayload.ID, (payload, context) ->
                context.client().execute(() -> sync.onSync(context.client(), payload)));

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> sync.onDisconnect(client));
        ClientLifecycleEvents.CLIENT_STARTED.register(sync::onClientStarted);
        ClientTickEvents.END_CLIENT_TICK.register(reload::onTick);

        ResourceControlCommand command = new ResourceControlCommand(sync);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(command.build()));
    }
}
