package dev.wakes.mixin.sodium;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.wakes.render.WakesDepthTexture;
import dev.wakes.render.WakesTime;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat2v;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat3v;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformInt;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.DefaultShaderInterface;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ShaderBindingContext;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
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
    @Unique
    private GlUniformFloat wakes$uniformWeather;
    @Unique
    private GlUniformFloat wakes$uniformDepth;
    @Unique
    private GlUniformInt wakes$uniformDepthMap;
    @Unique
    private GlUniformFloat2v wakes$uniformDepthMapOrigin;
    @Unique
    private GlUniformFloat wakes$uniformDepthMapRange;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void wakes$bindUniforms(ShaderBindingContext ctx, ChunkShaderOptions options, CallbackInfo ci) {
        this.wakes$uniformTime    = ctx.bindUniformOptional("u_WakesTime", GlUniformFloat::new);
        this.wakes$uniformCam     = ctx.bindUniformOptional("u_WakesCameraPos", GlUniformFloat3v::new);
        this.wakes$uniformWeather = ctx.bindUniformOptional("u_WakesWeather", GlUniformFloat::new);
        this.wakes$uniformDepth         = ctx.bindUniformOptional("u_WakesDepth", GlUniformFloat::new);
        this.wakes$uniformDepthMap      = ctx.bindUniformOptional("u_WakesDepthMap", GlUniformInt::new);
        this.wakes$uniformDepthMapOrigin= ctx.bindUniformOptional("u_WakesDepthMapOrigin", GlUniformFloat2v::new);
        this.wakes$uniformDepthMapRange = ctx.bindUniformOptional("u_WakesDepthMapRange", GlUniformFloat::new);
    }

    @Inject(method = "setupState", at = @At("TAIL"))
    private void wakes$pushUniforms(CallbackInfo ci) {
        if (this.wakes$uniformTime != null) {
            this.wakes$uniformTime.setFloat(WakesTime.getTime());
        }
        if (this.wakes$uniformCam != null) {
            this.wakes$uniformCam.set(WakesTime.getCamX(), WakesTime.getCamY(), WakesTime.getCamZ());
        }
        if (this.wakes$uniformWeather != null) {
            this.wakes$uniformWeather.setFloat(WakesTime.getWeather());
        }
        if (this.wakes$uniformDepth != null) {
            this.wakes$uniformDepth.setFloat(WakesTime.getDepthFactor());
        }
        if (this.wakes$uniformDepthMap != null) {
            // Bind our depth texture to slot 4. Sodium uses 0 (block atlas) and
            // 1 (light texture) so 4 is well clear. Restore active-texture to 0
            // afterwards so we don't surprise Sodium's later GL state.
            int prevActive = GlStateManager._getActiveTexture();
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + WakesDepthTexture.TEXTURE_UNIT);
            GlStateManager._bindTexture(WakesDepthTexture.id());
            GlStateManager._activeTexture(prevActive);
            this.wakes$uniformDepthMap.setInt(WakesDepthTexture.TEXTURE_UNIT);
        }
        if (this.wakes$uniformDepthMapOrigin != null) {
            this.wakes$uniformDepthMapOrigin.set(
                (float) WakesDepthTexture.originX(),
                (float) WakesDepthTexture.originZ());
        }
        if (this.wakes$uniformDepthMapRange != null) {
            this.wakes$uniformDepthMapRange.setFloat(WakesDepthTexture.RANGE);
        }
    }
}
