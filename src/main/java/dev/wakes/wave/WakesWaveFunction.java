package dev.wakes.wave;

/**
 * Self-contained ocean wave field. Iterated sum of damped sines with
 * per-iteration direction rotation and parameter decay — the classic
 * Gerstner-with-decay technique used by every wave shader you've ever seen.
 *
 * Pure function of (x, z, time). Same algorithm lives in
 * assets/wakes/shaders/include/wakes_waves.glsl so CPU contraption sampling
 * agrees with GPU water displacement at every (x,z,time).
 *
 * Tunable constants below mirror the structure of physics-mod's wave field but
 * are independently chosen for a "calm sea, ~0.4 block amplitude" baseline.
 */
public final class WakesWaveFunction {

    public static final int    ITERATIONS      = 13;
    public static final double FREQUENCY       = 6.0;
    public static final double SPEED           = 2.0;
    public static final double WEIGHT          = 0.8;
    public static final double FREQUENCY_MULT  = 1.18;
    public static final double SPEED_MULT      = 1.07;
    public static final double ITER_INC        = 12.0;
    public static final double DRAG_MULT       = 0.048;
    public static final double XZ_SCALE        = 0.04;
    public static final double TIME_MULT       = 0.05;
    public static final double AMPLITUDE       = 0.4;

    private WakesWaveFunction() {}

    /**
     * @param x world X (blocks)
     * @param z world Z (blocks)
     * @param time time in ticks (gametime + partialTick); scaled internally by TIME_MULT.
     * @return wave height offset in blocks at (x,z,time). Roughly in [-AMPLITUDE, +AMPLITUDE].
     */
    public static double waveHeight(double x, double z, double time) {
        double t = time * TIME_MULT;
        double px = x * XZ_SCALE;
        double pz = z * XZ_SCALE;

        double angle  = 0.0;
        double freq   = FREQUENCY;
        double speed  = SPEED;
        double weight = WEIGHT;
        double height = 0.0;
        double sumW   = 0.0;

        for (int i = 0; i < ITERATIONS; i++) {
            double dx = Math.sin(angle);
            double dz = Math.cos(angle);
            double phase = (dx * px + dz * pz) * freq + t * speed;
            double wave  = Math.exp(Math.sin(phase) - 1.0);
            double dWave = wave * Math.cos(phase);

            // Drag the sample point along the wave direction — gives the iterated
            // sines a coherent, non-grid-aligned look.
            px -= dx * dWave * weight * DRAG_MULT;
            pz -= dz * dWave * weight * DRAG_MULT;

            height += wave * weight;
            sumW   += weight;

            weight *= 0.8;
            freq   *= FREQUENCY_MULT;
            speed  *= SPEED_MULT;
            angle  += ITER_INC;
        }

        return (height / sumW) * AMPLITUDE * 2.0 - AMPLITUDE;
    }

    /**
     * Surface normal at (x,z,time). Computed by central differences over a small
     * step in world space — cheaper and numerically stabler than analytic gradients
     * for a 13-iteration sum like this one.
     */
    public static double[] waveNormal(double x, double z, double time) {
        final double e = 0.5;
        double hL = waveHeight(x - e, z, time);
        double hR = waveHeight(x + e, z, time);
        double hD = waveHeight(x, z - e, time);
        double hU = waveHeight(x, z + e, time);
        double nx = (hL - hR) / (2 * e);
        double nz = (hD - hU) / (2 * e);
        double ny = 1.0;
        double inv = 1.0 / Math.sqrt(nx * nx + ny * ny + nz * nz);
        return new double[]{nx * inv, ny * inv, nz * inv};
    }
}
