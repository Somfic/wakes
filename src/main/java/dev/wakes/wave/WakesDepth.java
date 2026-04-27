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
     * @return [0, 1]. Two-segment linear ramp:
     *           depth = 1 block → 0 (just water touching surface, no waves)
     *           depth = {@link #MIN_DEPTH}     → 0.5 (small waves fully on, big swell off)
     *           depth = {@link #DEEP_DEPTH}    → 1.0 (everything full strength)
     *         {@code WakesWaveFunction.waveHeight} interprets the value: it uses
     *         {@code smoothstep(0, 0.5, f)} for swell + chop (shallow-friendly)
     *         and {@code smoothstep(0.5, 1, f)} for the sub-swell (deep only).
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

        if (depth <= 1) return 0f;
        if (depth >= DEEP_DEPTH) return 1f;
        if (depth <= MIN_DEPTH) {
            // Shallow segment: linear 0 → 0.5 across [1, MIN_DEPTH].
            return 0.5f * (depth - 1) / (float) (MIN_DEPTH - 1);
        }
        // Deep segment: linear 0.5 → 1.0 across [MIN_DEPTH, DEEP_DEPTH].
        return 0.5f + 0.5f * (depth - MIN_DEPTH) / (float) (DEEP_DEPTH - MIN_DEPTH);
    }
}
