package dev.wakes.wave;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * Looks up local water depth and converts it to a [0, 1] amplitude factor for
 * wave forces and visuals. Shallow water near shore returns a low factor so
 * waves are gentle there; open ocean returns 1.0 so waves are full strength.
 *
 * Shared between server-side ServerSubLevelMixin (per-ship sample) and
 * client-side WakesClientEvents (player-position uniform push).
 */
public final class WakesDepth {

    /** Sea level Y. Topmost water block is at SEA_LEVEL - 1 = 62 by default. */
    public static final int SEA_LEVEL = 63;

    /** Depth at which we consider the water "deep enough" for full-amplitude waves.
     *  Open-ocean trenches at 30+ blocks get the full sub-swell; coastal / shelf
     *  water in the teens gets a partial smoothstepped contribution. */
    public static final int DEEP_DEPTH = 30;

    /** Minimum depth required to register any wave at all. Lakes, rivers, and
     *  glassy shallows stay calm; from here it ramps up smoothly to DEEP_DEPTH. */
    public static final int MIN_DEPTH = 8;

    private WakesDepth() {}

    /**
     * @return [0, 1]. 0 = above water / very shallow. 1 = at least DEEP_DEPTH blocks
     *         of water under the surface. Smooth ramp between MIN_DEPTH and DEEP_DEPTH.
     */
    public static float factorAt(BlockGetter level, double x, double z) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        // Confirm there's water at the surface column.
        FluidState surface = level.getFluidState(p.set(blockX, SEA_LEVEL - 1, blockZ));
        if (!surface.is(Fluids.WATER) && !surface.is(Fluids.FLOWING_WATER)) {
            return 0f;   // not water at all → no waves
        }

        // Walk down until we leave water.
        int depth = 1;
        for (int y = SEA_LEVEL - 2; y >= SEA_LEVEL - DEEP_DEPTH - 4; y--) {
            FluidState fs = level.getFluidState(p.set(blockX, y, blockZ));
            if (fs.is(Fluids.WATER) || fs.is(Fluids.FLOWING_WATER)) {
                depth++;
            } else {
                break;
            }
        }

        if (depth <= MIN_DEPTH) return 0f;
        if (depth >= DEEP_DEPTH) return 1f;
        // Smooth ramp.
        float t = (depth - MIN_DEPTH) / (float) (DEEP_DEPTH - MIN_DEPTH);
        // Smoothstep curve.
        return t * t * (3f - 2f * t);
    }
}
