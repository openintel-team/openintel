package dev.openintel.mixin;

import dev.openintel.investigate.InvestigateCamera;
import net.minecraft.client.Camera;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class InvestigateCameraMixin {
    @Inject(method = "setup(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;ZZF)V", at = @At("HEAD"))
    private void openintel$focusInvestigate(Level level, Entity entity, boolean detached, boolean mirrored, float partialTick, CallbackInfo ci) {
        InvestigateCamera.update((Camera) (Object) this, entity, partialTick);
    }
}
