package sh.somfic.wherestherum.wind;

import sh.somfic.wherestherum.WakesConfig;

/**
 * The world's wind: one direction and strength, shared by everything that cares.
 *
 * <h2>Why this is a function of time rather than stored state</h2>
 * The wave field ({@code WakesWaveGLSL} / {@code WakesWaveFunction}) already
 * derives everything from game time so the GPU and the server agree on "now"
 * without synchronising anything. Wind follows the same rule: it is a pure
 * function of game time, so the client's visuals, the server's sail forces, and
 * any future client prediction all compute the identical vector with no packets.
 * Storing wind as mutable state would immediately create a desync problem that
 * this design simply does not have.
 *
 * <h2>Relationship to the wave field</h2>
 * The shader's {@code WAKES_WIND_DIR} constant (~30° from +X) is the direction
 * the swell propagates. The base wind here matches it, so sails push the way the
 * waves run — which is what a player will intuitively expect from looking at the
 * sea. {@code windVariability} then swings the wind slowly either side of that.
 *
 * NOTE: the swell direction itself is still a GLSL compile-time constant, so at
 * high variability the wind and the visible wave direction will drift apart.
 * Making the waves follow the wind would mean feeding the direction in as a
 * uniform — worth doing, but it is a shader change, not a physics one.
 */
public final class WakesWind {

    /** Base direction, matching WAKES_WIND_DIR in the GLSL wave field (~30° from +X). */
    private static final double BASE_X = 0.866;
    private static final double BASE_Z = 0.5;

    /** Seconds for one full swing of the direction wander. ~4 minutes: long enough
     *  that a voyage feels like it has settled weather, short enough to notice. */
    private static final double SWING_PERIOD_TICKS = 4800.0;

    /** Seconds for the gust envelope. Shorter than the direction swing so strength
     *  varies within a steady breeze rather than only when the wind shifts. */
    private static final double GUST_PERIOD_TICKS = 700.0;

    private WakesWind() {}

    /**
     * Wind direction as a unit vector in world XZ, at the given game time.
     * Index 0 = x, index 1 = z.
     */
    public static double[] direction(double timeTicks) {
        double variability = WakesConfig.WIND_VARIABILITY.get();
        // Two incommensurate sines so the heading never repeats on a short cycle.
        double swing = Math.sin(timeTicks / SWING_PERIOD_TICKS * Math.PI * 2.0) * 0.75
                     + Math.sin(timeTicks / (SWING_PERIOD_TICKS * 0.37) * Math.PI * 2.0) * 0.25;
        // At variability 1.0 the wind can swing a full +/-90 degrees off base.
        double angle = Math.atan2(BASE_Z, BASE_X) + swing * variability * (Math.PI * 0.5);
        return new double[] { Math.cos(angle), Math.sin(angle) };
    }

    /**
     * Wind strength multiplier. Combines the configured base speed, a slow gust
     * envelope, and the current weather — storms genuinely blow harder, which is
     * what makes running before a gale feel different from a calm day.
     *
     * @param weather 0 = clear, ~1.5 = thunderstorm (same scale the wave field uses)
     */
    public static double strength(double timeTicks, double weather) {
        double base = WakesConfig.WIND_SPEED.get();
        double gust = 0.8 + 0.2 * Math.sin(timeTicks / GUST_PERIOD_TICKS * Math.PI * 2.0);
        return base * gust * (1.0 + weather * 0.8);
    }
}
