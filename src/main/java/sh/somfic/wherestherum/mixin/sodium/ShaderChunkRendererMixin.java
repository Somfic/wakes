package sh.somfic.wherestherum.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderConstants;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Adds an IS_TRANSLUCENT shader-constant when Sodium is compiling its block-vertex
 * shader for the translucent terrain pass. {@link sh.somfic.wherestherum.render.WakesShaderInjection}
 * gates the whole wave-displacement block on this define, so without it the
 * preprocessor strips our injected GLSL and the patch becomes a silent no-op.
 *
 * Translucent ≠ water exactly, but it's the most specific cut Sodium gives us
 * without rewriting its render pipeline. The world-Y falloff in the injected
 * GLSL narrows it further to roughly sea level.
 *
 * <h2>Why here and not on ChunkShaderOptions.constants()</h2>
 * Up to Sodium 0.6 the defines were built by {@code ChunkShaderOptions.constants()},
 * which is what we used to wrap. Sodium 0.8 moved that logic into this class's
 * private static {@code createShaderConstants} and compiles from it instead.
 * {@code ChunkShaderOptions.constants()} still EXISTS in 0.8 — so the old mixin
 * kept applying cleanly and simply never fired, leaving the water flat with no
 * error anywhere in the log. Keep this targeted at whatever `compileProgram`
 * actually calls, not at whatever merely still exists.
 */
@Mixin(value = ShaderChunkRenderer.class, remap = false)
public abstract class ShaderChunkRendererMixin {

    @WrapOperation(
        method = "createShaderConstants(Lnet/caffeinemc/mods/sodium/client/render/chunk/shader/ChunkShaderOptions;)Lnet/caffeinemc/mods/sodium/client/gl/shader/ShaderConstants;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/gl/shader/ShaderConstants$Builder;build()Lnet/caffeinemc/mods/sodium/client/gl/shader/ShaderConstants;"
        )
    )
    private static ShaderConstants wakes$addTranslucentDefine(ShaderConstants.Builder builder,
                                                              Operation<ShaderConstants> original,
                                                              @Local(argsOnly = true) ChunkShaderOptions options) {
        if (options != null && options.pass() != null && options.pass().isTranslucent()) {
            builder.add("IS_TRANSLUCENT");
        }
        return original.call(builder);
    }
}
