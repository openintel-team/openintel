package dev.openintel.mixin;

import dev.openintel.investigate.InvestigateCamera;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class InvestigateCameraMixin {
    @Inject(method = "update(Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"))
    private void openintel$focusInvestigate(DeltaTracker delta, CallbackInfo ci) {
        InvestigateCamera.update((Camera) (Object) this, delta);
    }
}
