package dev.wakes.wave;

/**
 * Single source of truth for the wave field.
 *
 * Java side: {@link WakesWaveFunction} mirrors the same numeric formula for the
 * server-side physics path.
 *
 * GLSL side: this class hands out a string constant containing the wave
 * function definition, plus a small helper that wraps amplitude scaling.
 * Both shader injectors ({@link dev.wakes.render.WakesShaderInjection} for
 * Sodium, {@link dev.wakes.render.IrisInjection} for Iris) embed it.
 *
 * Constants here MUST stay numerically identical to {@link WakesWaveFunction};
 * the test {@code WakesWaveFunctionTest} can grow assertions to enforce that.
 */
public final class WakesWaveGLSL {

    /** The wave-field functions (`wakes_swell`, `wakes_chop`, `wakes_waveHeight`).
     *  Does NOT declare uniforms — the host shader is expected to declare or
     *  already have {@code uniform float u_WakesTime} (or `float wakes_time`)
     *  and {@code uniform float u_WakesDepth01}. See {@link #SWELL_CHOP_FNS}
     *  for the variant that takes those as parameters instead of uniforms. */
    public static final String SWELL_CHOP_FNS = """
float wakes_swell(vec2 p, float t) {
    const int   ITER         = 10;
    const float FREQUENCY    = 4.5;
    const float SPEED        = 1.6;
    const float WEIGHT_INIT  = 0.85;
    const float FREQ_MULT    = 1.21;
    const float SPEED_MULT   = 1.07;
    const float ITER_INC     = 12.0;
    const float DRAG_MULT    = 0.052;
    const float XZ_SCALE     = 0.035;
    const float TIME_MULT    = 0.045;

    float scaledT = t * TIME_MULT;
    vec2  pos = p * XZ_SCALE;
    float angle = 0.0, freq = FREQUENCY, speed = SPEED, weight = WEIGHT_INIT;
    float height = 0.0, sumW = 0.0;
    for (int i = 0; i < ITER; i++) {
        vec2 dir = vec2(sin(angle), cos(angle));
        float phase = dot(dir, pos) * freq + scaledT * speed;
        float wave  = exp(sin(phase) - 1.0);
        float dWave = wave * cos(phase);
        pos    -= dir * dWave * weight * DRAG_MULT;
        height += wave * weight;
        sumW   += weight;
        weight *= 0.82;
        freq   *= FREQ_MULT;
        speed  *= SPEED_MULT;
        angle  += ITER_INC;
    }
    return (height / sumW) * 2.0 - 1.0;
}

float wakes_chop(vec2 p, float t) {
    float scaledT = t * 0.18;
    vec2 q = p * 0.22;
    return 0.5 * (
          sin(q.x * 1.4 + q.y * 0.7 + scaledT)
        + sin(q.y * 2.1 - q.x * 0.5 + scaledT * 1.3)
    );
}

// Combined wave height with weather + depth amplitude scaling.
//   weather: 0 calm .. ~1.5 thunderstorm
//   depth:   0 shore  .. 1 deep ocean
float wakes_waveHeightAmp(vec2 worldXZ, float t, float weather, float depth) {
    float swellAmp = (0.9 + weather * 1.4) * depth;
    float chopAmp  = (0.05 + weather * 0.45) * depth;
    return wakes_swell(worldXZ, t) * swellAmp + wakes_chop(worldXZ, t) * chopAmp;
}
""";

    private WakesWaveGLSL() {}
}
