package dev.wakes.wave;

/**
 * CPU-side wave field. Numerically identical to the GLSL in
 * {@link WakesWaveGLSL#SWELL_CHOP_FNS} so server-side physics samples agree
 * with what the GPU renders for visible water.
 *
 * Constants and algorithm are kept in sync by hand. If you tune one side, tune
 * the other in lock-step.
 */
public final class WakesWaveFunction {

    // Swell parameters (mirror GLSL constants)
    private static final int    ITER         = 10;
    private static final double FREQUENCY    = 4.5;
    private static final double SPEED        = 1.6;
    private static final double WEIGHT_INIT  = 0.85;
    private static final double FREQ_MULT    = 1.21;
    private static final double SPEED_MULT   = 1.07;
    private static final double ITER_INC     = 12.0;
    private static final double DRAG_MULT    = 0.052;
    private static final double XZ_SCALE     = 0.035;
    private static final double TIME_MULT    = 0.045;

    // Global wind direction (unit vector). All three wave octaves rotate input
    // coords so their dominant propagation axis aligns with this — gives the
    // ocean a unified "this way" feel instead of looking like crossed swells
    // from nowhere. Currently ~30° from +X (ENE).
    private static final double WIND_DIR_X = 0.866;   // cos(30°)
    private static final double WIND_DIR_Z = 0.5;     // sin(30°)

    private WakesWaveFunction() {}

    /** Big-swell component, returns roughly [-1, +1]. */
    public static double swell(double x, double z, double t) {
        // Rotate input into wind-aligned frame: each octave's per-iteration
        // direction is now expressed relative to the wind axis, so the bundle
        // of 10 octaves drifts as a coherent wind-driven swell rather than
        // sitting in random world directions.
        double xR =  x * WIND_DIR_X + z * WIND_DIR_Z;
        double zR = -x * WIND_DIR_Z + z * WIND_DIR_X;
        double scaledT = t * TIME_MULT;
        double px = xR * XZ_SCALE, pz = zR * XZ_SCALE;
        double angle = 0.0, freq = FREQUENCY, speed = SPEED, weight = WEIGHT_INIT;
        double height = 0.0, sumW = 0.0;
        for (int i = 0; i < ITER; i++) {
            double dx = Math.sin(angle), dz = Math.cos(angle);
            double phase = (dx * px + dz * pz) * freq + scaledT * speed;
            double wave  = Math.exp(Math.sin(phase) - 1.0);
            double dWave = wave * Math.cos(phase);
            px     -= dx * dWave * weight * DRAG_MULT;
            pz     -= dz * dWave * weight * DRAG_MULT;
            height += wave * weight;
            sumW   += weight;
            weight *= 0.82;
            freq   *= FREQ_MULT;
            speed  *= SPEED_MULT;
            angle  += ITER_INC;
        }
        return (height / sumW) * 2.0 - 1.0;
    }

    /** High-frequency wind chop, returns roughly [-1, +1]. */
    public static double chop(double x, double z, double t) {
        double xR =  x * WIND_DIR_X + z * WIND_DIR_Z;
        double zR = -x * WIND_DIR_Z + z * WIND_DIR_X;
        double scaledT = t * 0.18;
        double qx = xR * 0.22, qz = zR * 0.22;
        return 0.5 * (
              Math.sin(qx * 1.4 + qz * 0.7 + scaledT)
            + Math.sin(qz * 2.1 - qx * 0.5 + scaledT * 1.3)
        );
    }

    /** Big rolling sub-swell. Two slow sines with wavelengths ~85–110 blocks
     *  give long-period ocean rolls — bow and stern of a 50-block ship sit on
     *  meaningfully different phases, producing real fore-aft pitch instead of
     *  averaging out across short swell. Returns roughly [-1, +1]. */
    public static double sub(double x, double z, double t) {
        double xR =  x * WIND_DIR_X + z * WIND_DIR_Z;
        double zR = -x * WIND_DIR_Z + z * WIND_DIR_X;
        double scaledT = t * 0.0467;          // 0.07 / 1.5 — period ~135 s
        double qx = xR * 0.0267, qz = zR * 0.0267;  // 0.04 / 1.5 — wavelengths ~165–195 blocks
        return 0.5 * (
              Math.sin(qx * 1.2 + qz * 0.7 + scaledT)
            + Math.sin(qz * 1.4 - qx * 0.9 + scaledT * 1.15)
        );
    }

    /**
     * Combined wave height with weather + depth amplitude scaling. Mirrors
     * {@code wakes_waveHeightAmp} in {@link WakesWaveGLSL#SWELL_CHOP_FNS}.
     *
     * @param weather 0 = calm, ~1.5 = thunderstorm
     * @param depth   0 = shore, 1 = deep ocean
     */
    public static double waveHeight(double x, double z, double t, double weather, double depth) {
        // Two-stage depth ramp from WakesDepth.factorAt:
        //   factor=0   → no water
        //   factor=0.5 → MIN_DEPTH (small waves fully on, big sub still off)
        //   factor=1.0 → DEEP_DEPTH (everything full strength)
        double shallowFactor = smoothstep(0.0, 0.5, depth);   // swell + chop
        double deepFactor    = smoothstep(0.5, 1.0, depth);   // sub
        double subAmp   = (1.2 + weather * 1.6) * deepFactor;
        double swellAmp = (1.0 + weather * 1.4) * shallowFactor;
        double chopAmp  = (0.05 + weather * 0.45) * shallowFactor;
        return sub(x, z, t)   * subAmp
             + swell(x, z, t) * swellAmp
             + chop(x, z, t)  * chopAmp;
    }

    /** GLSL-style smoothstep. java.lang.Math doesn't ship one. */
    private static double smoothstep(double edge0, double edge1, double x) {
        double t = (x - edge0) / (edge1 - edge0);
        if (t <= 0.0) return 0.0;
        if (t >= 1.0) return 1.0;
        return t * t * (3.0 - 2.0 * t);
    }
}
