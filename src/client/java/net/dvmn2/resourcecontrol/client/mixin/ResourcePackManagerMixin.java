package net.dvmn2.resourcecontrol.client.mixin;

import net.dvmn2.resourcecontrol.client.pack.PackProfiles;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.ResourcePackProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Подмешивает паки ResourceControl в список доступных профилей клиентского менеджера ресурспаков.
 * Менеджер датапаков (тот же класс) не трогаем — сравниваем с менеджером клиента.
 */
@Mixin(ResourcePackManager.class)
public abstract class ResourcePackManagerMixin {

    @Inject(method = "providePackProfiles", at = @At("RETURN"), cancellable = true)
    private void rescontrol$addPacks(CallbackInfoReturnable<Map<String, ResourcePackProfile>> cir) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || (Object) this != client.getResourcePackManager()) {
            return;
        }
        Map<String, ResourcePackProfile> ours = PackProfiles.buildProfiles();
        if (ours.isEmpty()) {
            return;
        }
        Map<String, ResourcePackProfile> merged = new LinkedHashMap<>(cir.getReturnValue());
        merged.putAll(ours);
        cir.setReturnValue(merged);
    }
}
