package net.dvmn2.resourcecontrol.client;

import net.dvmn2.resourcecontrol.client.network.LimitPayload;
import net.dvmn2.resourcecontrol.client.network.StatusPayload;
import net.dvmn2.resourcecontrol.client.network.SyncPayload;
import net.dvmn2.resourcecontrol.client.pack.ResourceSync;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.text.Text;

public final class ResourceControlClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Регистрация приёмника заодно сообщает серверу (minecraft:register), что мод установлен —
        // по этому плагин решает, кикать ли игрока и когда слать набор паков.
        PayloadTypeRegistry.playS2C().register(LimitPayload.ID, LimitPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SyncPayload.ID, SyncPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(StatusPayload.ID, StatusPayload.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(LimitPayload.ID, (payload, context) ->
                context.client().execute(() -> ResourceSync.onLimit(context.client(), payload)));

        ClientPlayNetworking.registerGlobalReceiver(SyncPayload.ID, (payload, context) ->
                context.client().execute(() -> ResourceSync.onSync(context.client(), payload)));

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ResourceSync.onDisconnect(client));

        ClientLifecycleEvents.CLIENT_STARTED.register(ResourceSync::onClientStarted);

        // Отложенная перезагрузка ресурсов (см. ResourceSync#onTick).
        ClientTickEvents.END_CLIENT_TICK.register(ResourceSync::onTick);

        // Аварийный выключатель: убрать все сохранённые (persistent) паки.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("rcclear").executes(context -> {
                    ResourceSync.clearPersistent(context.getSource().getClient());
                    context.getSource().sendFeedback(Text.translatable("resourcecontrol.cleared"));
                    return 1;
                })));
    }
}
