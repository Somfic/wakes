package sh.somfic.wherestherum.wave;

/**
 * Single source of truth for the wave field.
 *
 * Java side: {@link WakesWaveFunction} mirrors the same numeric formula for the
 * server-side physics path.
 *
 * GLSL side: this class hands out a string constant containing the wave
 * function definition, plus a small helper that wraps amplitude scaling.
 * Both shader injectors ({@link sh.somfic.wherestherum.render.WakesShaderInjection} for
 * Sodium, {@link sh.somfic.wherestherum.render.IrisInjection} for Iris) embed it.
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
// Global wind direction (unit vector). All three octaves rotate input coords
// into this frame so the ocean has a single coherent propagation direction.
const vec2 WAKES_WIND_DIR = vec2(0.866, 0.5);   // ~30° from +X (ENE)

vec2 wakes_windRot(vec2 p) {
    return vec2( p.x * WAKES_WIND_DIR.x + p.y * WAKES_WIND_DIR.y,
                -p.x * WAKES_WIND_DIR.y + p.y * WAKES_WIND_DIR.x);
}

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

    p = wakes_windRot(p);
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
    p = wakes_windRot(p);
    float scaledT = t * 0.18;
    vec2 q = p * 0.22;
    return 0.5 * (
          sin(q.x * 1.4 + q.y * 0.7 + scaledT)
        + sin(q.y * 2.1 - q.x * 0.5 + scaledT * 1.3)
    );
}

// Big rolling sub-swell. Two slow sines with wavelengths ~85-110 blocks give
// long-period ocean rolls — bow and stern of long ships sit on meaningfully
// different phases, producing real fore-aft pitch instead of averaging out
// across short swell.
float wakes_sub(vec2 p, float t) {
    p = wakes_windRot(p);
    float scaledT = t * 0.0467;          // 0.07 / 1.5 — period ~135 s
    vec2 q = p * 0.0267;                 // 0.04 / 1.5 — wavelengths ~165–195 blocks
    return 0.5 * (
          sin(q.x * 1.2 + q.y * 0.7 + scaledT)
        + sin(q.y * 1.4 - q.x * 0.9 + scaledT * 1.15)
    );
}

// Combined wave height with weather + depth amplitude scaling.
//   weather: 0 calm .. ~1.5 thunderstorm
//   depth:   factor from WakesDepth.factorAt — two-segment:
//              0   = no water
//              0.5 = MIN_DEPTH (small waves on, sub still off)
//              1.0 = DEEP_DEPTH (everything full)
float wakes_waveHeightAmp(vec2 worldXZ, float t, float weather, float depth) {
    float shallowFactor = smoothstep(0.0, 0.5, depth);   // swell + chop
    float deepFactor    = smoothstep(0.5, 1.0, depth);   // sub
    float subAmp   = (1.2 + weather * 1.6) * deepFactor;
    float swellAmp = (1.0 + weather * 1.4) * shallowFactor;
    float chopAmp  = (0.05 + weather * 0.45) * shallowFactor;
    return wakes_sub(worldXZ, t)   * subAmp
         + wakes_swell(worldXZ, t) * swellAmp
         + wakes_chop(worldXZ, t)  * chopAmp;
}
""";

    /**
     * Shoreline surf / foam field. Kept OUT of {@link #SWELL_CHOP_FNS} on purpose:
     * that constant is the numeric contract mirrored by {@link WakesWaveFunction}
     * on the Java/physics side, and foam is a purely visual term that must never
     * move a single displaced vertex. Folding it in there would silently desync
     * ship bobbing from the server simulation for a cosmetic effect.
     *
     * <p>Depends on {@code wakes_swell}, {@code wakes_chop} and
     * {@code WAKES_WIND_DIR} from {@link #SWELL_CHOP_FNS}, so it MUST be appended
     * after that block, never before it.
     *
     * <p>The host shader is expected to supply a mutable global
     * {@code float wakes_foamAmt} for the vertex stage to write into, and to bake
     * the user's intensity setting in as a literal constant — see
     * {@code WakesShaderInjection}. Nothing here reads a uniform we don't already
     * bind, because the uniform-binding mixin is not ours to extend.
     */
    public static final String SURF_FNS = """
// ---------------------------------------------------------------------------
// Shoreline surf / foam
// ---------------------------------------------------------------------------

// Cheap hash + value noise. We only need "not obviously tiled" here, and this
// runs per-vertex (water vertices sit ~1 block apart), so real gradient noise
// would be wasted cycles. GLSL 330 safe: no bit ops, no integer hashing.
float wakes_hash12(vec2 p) {
    vec3 p3 = fract(p.xyx * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float wakes_vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);          // smoothstep interpolant
    float a = wakes_hash12(i);
    float b = wakes_hash12(i + vec2(1.0, 0.0));
    float c = wakes_hash12(i + vec2(0.0, 1.0));
    float d = wakes_hash12(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

// The "shore band": a window over the depth factor produced by WakesDepth.
//   0.0  = dry land / no water     -> no foam (there is no water to foam)
//   ~0.5 = MIN_DEPTH (8 blocks)
//   1.0  = DEEP (30 blocks)        -> no foam (open ocean)
// Foam wants depth LOW BUT NON-ZERO. The inner smoothstep softens the hard
// 0 -> epsilon edge at the land boundary; without it the very first wet texel
// gets full foam and the coast reads as a painted-on white outline. The outer
// one fades foam back out well before MIN_DEPTH so it stays a coastal effect.
float wakes_shoreBand(float depth) {
    float inner = smoothstep(0.015, 0.140, depth);
    float outer = 1.0 - smoothstep(0.300, 0.600, depth);
    return inner * outer;
}

// Crest mask. Deliberately built from the NORMALISED wave shape (swell/chop are
// -1..1 before amplitude scaling) rather than from the final wave height: in the
// shore band the depth factor has already crushed amplitude towards zero, so
// thresholding the scaled height would fade foam out exactly where we want it
// strongest. This way foam tracks where the wave is cresting regardless of how
// tall that crest actually is.
float wakes_crest(vec2 worldXZ, float t) {
    float s = wakes_swell(worldXZ, t) * 0.75 + wakes_chop(worldXZ, t) * 0.50;
    return smoothstep(0.10, 0.65, s);
}

// Final foam coverage in 0..1, BEFORE the baked intensity scale.
// The noise term drifts along the wind direction so the foam line shimmers and
// breaks into patches instead of sitting as a static ring around every island.
float wakes_foam(vec2 worldXZ, float t, float depth) {
    float band = wakes_shoreBand(depth);
    if (band <= 0.0) return 0.0;              // early out: covers most of the ocean
    float crest = wakes_crest(worldXZ, t);
    vec2  drift = WAKES_WIND_DIR * (t * 0.012);
    float n = wakes_vnoise(worldXZ * 0.35 + drift);
    n = mix(0.65, 1.35, n);                   // +/-35% patchiness, never fully off
    return clamp(band * crest * n, 0.0, 1.0);
}
""";

    private WakesWaveGLSL() {}
}
