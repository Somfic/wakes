package dev.wakes.mixin.sodium;

import dev.wakes.render.WakesTime;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat3v;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.DefaultShaderInterface;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ShaderBindingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the {@code u_WakesTime} float uniform to Sodium's chunk-shader interface
 * and pushes the current wave time on every {@code setupState()} call. The
 * uniform is bound as <i>optional</i>, so when our shader injection didn't run
 * (non-translucent variants, or our patch failed), the missing uniform doesn't
 * fail the program link.
 */
@Mixin(value = DefaultShaderInterface.class, remap = false)
public abstract class DefaultShaderInterfaceMixin {

    @Unique
    private GlUniformFloat wakes$uniformTime;
    @Unique
    private GlUniformFloat3v wakes$uniformCam;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void wakes$bindUniforms(ShaderBindingContext ctx, ChunkShaderOptions options, CallbackInfo ci) {
        this.wakes$uniformTime = ctx.bindUniformOptional("u_WakesTime", GlUniformFloat::new);
        this.wakes$uniformCam  = ctx.bindUniformOptional("u_WakesCameraPos", GlUniformFloat3v::new);
    }

    @Inject(method = "setupState", at = @At("TAIL"))
    private void wakes$pushUniforms(CallbackInfo ci) {
        if (this.wakes$uniformTime != null) {
            this.wakes$uniformTime.setFloat(WakesTime.getTime());
        }
        if (this.wakes$uniformCam != null) {
            this.wakes$uniformCam.set(WakesTime.getCamX(), WakesTime.getCamY(), WakesTime.getCamZ());
        }
    }
}
