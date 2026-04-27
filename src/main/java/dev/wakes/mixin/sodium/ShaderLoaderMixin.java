package dev.wakes.mixin.sodium;

import dev.wakes.Wakes;
import dev.wakes.render.WakesShaderInjection;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks Sodium's source loader so we can patch its block-vertex shader to do
 * Wakes wave displacement. Source mutation only — Sodium still compiles and links
 * the program; we just hand it modified GLSL.
 */
@Mixin(value = ShaderLoader.class, remap = false)
public abstract class ShaderLoaderMixin {

    @Inject(method = "getShaderSource", at = @At("RETURN"), cancellable = true)
    private static void wakes$injectWaves(ResourceLocation name, CallbackInfoReturnable<String> cir) {
        String src = cir.getReturnValue();
        if (src == null) return;
        if (!"sodium".equals(name.getNamespace())) return;

        try {
            String patched = WakesShaderInjection.maybePatch(name.getPath(), src);
            if (patched != src) {
                cir.setReturnValue(patched);
            }
        } catch (Throwable t) {
            Wakes.LOG.error("Failed to patch Sodium shader {} — falling back to vanilla source", name, t);
        }
    }
}
