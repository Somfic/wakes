package dev.wakes.mixin.iris;

import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor mixin so we can rewrite the final {@code vertexSource} field on a
 * loaded shader pack's {@link ProgramSource}. Required for in-place wave
 * displacement injection — we want to keep the original ProgramSource instance
 * (Iris caches them by ProgramId) but swap its GLSL.
 */
@Mixin(value = ProgramSource.class, remap = false)
public interface ProgramSourceMixin {
    @Mutable
    @Accessor("vertexSource")
    void wakes$setVertexSource(String source);

    @Accessor("vertexSource")
    String wakes$getVertexSource();
}
