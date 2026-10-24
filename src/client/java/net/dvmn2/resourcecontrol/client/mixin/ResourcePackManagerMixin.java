package net.dvmn2.resourcecontrol.client.mixin;

import net.dvmn2.resourcecontrol.client.pack.PackProfiles;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.ResourcePackProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * Adds ResourceControl packs to the profiles known to the client's resource pack manager. The data pack
 * manager is the same class, so the target is compared with the client's manager.
 */
@Mixin(ResourcePackManager.class)
public abstract class ResourcePackManagerMixin {

    @Inject(method = "providePackProfiles()Ljava/util/Map;", at = @At("RETURN"), cancellable = true)
    private void resourcecontrol$addPacks(CallbackInfoReturnable<Map<String, ResourcePackProfile>> cir) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || (Object) this != client.getResourcePackManager()) {
            return;
        }
        cir.setReturnValue(PackProfiles.mergeInto(cir.getReturnValue()));
    }
}
