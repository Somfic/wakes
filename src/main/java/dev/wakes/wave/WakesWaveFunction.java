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

    private WakesWaveFunction() {}

    /** Big-swell component, returns roughly [-1, +1]. */
    public static double swell(double x, double z, double t) {
        double scaledT = t * TIME_MULT;
        double px = x * XZ_SCALE, pz = z * XZ_SCALE;
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
        double scaledT = t * 0.18;
        double qx = x * 0.22, qz = z * 0.22;
        return 0.5 * (
              Math.sin(qx * 1.4 + qz * 0.7 + scaledT)
            + Math.sin(qz * 2.1 - qx * 0.5 + scaledT * 1.3)
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
        double swellAmp = (0.9 + weather * 1.4) * depth;
        double chopAmp  = (0.05 + weather * 0.45) * depth;
        return swell(x, z, t) * swellAmp + chop(x, z, t) * chopAmp;
    }
}
