package dev.wakes.mixin.iris;

import dev.wakes.render.IrisInjection;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
// Removed unused: ProgramSet self ref, ShaderPack import
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Intercepts Iris's {@link ProgramSet#get(ProgramId)} for water-related programs
 * and patches the program's vertex shader source in-place with our wave
 * displacement code. Only fires once per source — the patched marker prevents
 * re-injection on subsequent calls.
 */
@Mixin(value = ProgramSet.class, remap = false)
public abstract class ProgramSetMixin {

    @Inject(method = "get(Lnet/irisshaders/iris/shaderpack/loading/ProgramId;)Ljava/util/Optional;", at = @At("RETURN"))
    private void wakes$patchWaterShader(ProgramId programId, CallbackInfoReturnable<Optional<ProgramSource>> cir) {
        // Only the regular water and hand-water programs. Skip DhWater (Distant
        // Horizons) — different coordinate space, separate problem.
        if (programId != ProgramId.Water && programId != ProgramId.HandWater) return;

        Optional<ProgramSource> result = cir.getReturnValue();
        if (result == null || result.isEmpty()) return;

        ProgramSource src = result.get();
        ProgramSourceMixin acc = (ProgramSourceMixin) (Object) src;
        String existing = acc.wakes$getVertexSource();
        if (existing == null) return;

        // ShaderPack doesn't expose a name on this Iris version — IrisInjection
        // falls back to a content marker in the GLSL itself for pack detection.
        String patched = IrisInjection.maybePatchWaterVertex(null, existing);
        if (patched != existing) {
            acc.wakes$setVertexSource(patched);
        }
    }
}
