package dev.wakes.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderConstants;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Adds an IS_TRANSLUCENT shader-constant when Sodium is compiling its block-vertex
 * shader for the translucent terrain pass. Our shader-source mixin gates the wave
 * displacement on this define so only translucent geometry is touched (water,
 * stained glass, slime, etc.) — never solid blocks.
 *
 * Translucent ≠ water exactly, but it's the most specific cut Sodium gives us
 * without rewriting its render pipeline. The world-Y heuristic in wakes_waves.glsl
 * narrows it further to roughly sea level.
 */
@Mixin(value = ChunkShaderOptions.class, remap = false)
public abstract class ChunkShaderOptionsMixin {

    @Shadow @Final
    private TerrainRenderPass pass;

    @WrapOperation(
        method = "constants",
        at = @At(
            value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/gl/shader/ShaderConstants$Builder;build()Lnet/caffeinemc/mods/sodium/client/gl/shader/ShaderConstants;"
        )
    )
    private ShaderConstants wakes$addTranslucentDefine(ShaderConstants.Builder builder,
                                                        Operation<ShaderConstants> original) {
        if (pass != null && pass.isTranslucent()) {
            builder.add("IS_TRANSLUCENT");
        }
        return original.call(builder);
    }
}
