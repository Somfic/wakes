package dev.wakes.render;

import dev.wakes.Wakes;

/**
 * Patches Sodium's chunk shaders with our wave-displacement code at source-load
 * time. We touch only the vertex stage of the block layer, and gate displacement
 * behind {@code IS_TRANSLUCENT} so opaque/cutout passes are untouched.
 *
 * The vertex shader injection:
 *   1. Inserts a uniform declaration and the wave function include.
 *   2. Replaces the line that builds {@code gl_Position} with a call wrapping
 *      the world-space {@code position} through {@link #INJECTED_WAVE_FN}.
 */
public final class WakesShaderInjection {

    private static final String VERTEX_TARGET   = "blocks/block_layer_opaque.vsh";
    private static final String FRAGMENT_TARGET = "blocks/block_layer_opaque.fsh";

    /** Marker that proves to debugging eyes the patch landed. */
    private static final String SENTINEL = "// --- wakes:injected ---";

    private static final String VERTEX_HEADER_INJECTION = SENTINEL + """

#ifdef IS_TRANSLUCENT
uniform float u_WakesTime;
uniform vec3  u_WakesCameraPos;
uniform float u_WakesWeather;   // 0 calm .. 1.5 thunderstorm

// Big swell — base ocean motion. Damped sum-of-sines, rotating direction.
float wakes_swell(vec2 p) {
    const int   ITER         = 10;
    const float FREQUENCY    = 4.5;   // slightly lower base freq -> longer wavelength
    const float SPEED        = 1.6;
    const float WEIGHT_INIT  = 0.85;
    const float FREQ_MULT    = 1.21;
    const float SPEED_MULT   = 1.07;
    const float ITER_INC     = 12.0;
    const float DRAG_MULT    = 0.052;
    const float XZ_SCALE     = 0.035;
    const float TIME_MULT    = 0.045;

    float t = u_WakesTime * TIME_MULT;
    vec2  pos = p * XZ_SCALE;
    float angle = 0.0, freq = FREQUENCY, speed = SPEED, weight = WEIGHT_INIT;
    float height = 0.0, sumW = 0.0;
    for (int i = 0; i < ITER; i++) {
        vec2 dir = vec2(sin(angle), cos(angle));
        float phase = dot(dir, pos) * freq + t * speed;
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
    return (height / sumW) * 2.0 - 1.0;   // unit-ish, [-1, +1]
}

// High-frequency wind chop — only really visible during weather. Skipped on calm days.
float wakes_chop(vec2 p) {
    const float FREQ_A = 1.4, FREQ_B = 2.1;
    float t = u_WakesTime * 0.18;
    vec2 q = p * 0.22;
    return 0.5 * (
          sin(q.x * FREQ_A + q.y * 0.7 + t)
        + sin(q.y * FREQ_B - q.x * 0.5 + t * 1.3)
    );
}

float wakes_waveHeight(vec2 p) {
    float swellAmp = 0.9 + u_WakesWeather * 1.4;       // calm 0.9 .. storm 3.0
    float chopAmp  = 0.05 + u_WakesWeather * 0.45;     // small in clear, real bite in storm
    float h = wakes_swell(p) * swellAmp;
    h += wakes_chop(p) * chopAmp;
    return h;
}

// Central-difference normal — analytic gradient on the iterated stack would be
// painful and the eps cost is negligible vs. the ~12 trig calls already paid.
vec3 wakes_normal(vec2 p) {
    const float E = 0.6;
    float hL = wakes_waveHeight(p - vec2(E, 0.0));
    float hR = wakes_waveHeight(p + vec2(E, 0.0));
    float hD = wakes_waveHeight(p - vec2(0.0, E));
    float hU = wakes_waveHeight(p + vec2(0.0, E));
    return normalize(vec3((hL - hR) / (2.0 * E), 1.0, (hD - hU) / (2.0 * E)));
}

// `pos` arrives in camera-relative space. Recover absolute world position so the
// wave field is locked to the world (not dragged with the player), and so the
// sea-level Y-clamp can compare to actual world Y. Translucent pass includes
// stained glass / slime / ice — narrow by world Y to spare them.
//
// Out param `outShade` is a [0,1] lighting factor based on the wave normal vs a
// fixed sun direction. Used to modulate v_Color so crests catch light.
vec3 wakes_displace(vec3 pos, out float outShade) {
    outShade = 1.0;
    vec3 world = pos + u_WakesCameraPos;
    float dist = abs(world.y - 63.0);
    float falloff = 1.0 - smoothstep(2.0, 6.0, dist);
    if (falloff <= 0.0) return pos;

    pos.y += wakes_waveHeight(world.xz) * falloff;

    // Lambert-ish: dot(normal, sun) remapped into a gentle [0.7, 1.3] band so we
    // shade crests bright and troughs dim without crushing the existing tint.
    vec3 n = wakes_normal(world.xz);
    const vec3 SUN = normalize(vec3(0.4, 0.85, 0.3));
    float ndl = max(dot(n, SUN), 0.0);
    outShade = mix(1.0, 0.7 + ndl * 0.6, falloff);
    return pos;
}
#endif
""";

    private static final String VERTEX_GL_POSITION_ORIGINAL =
        "gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);";
    private static final String VERTEX_GL_POSITION_REPLACEMENT = """
#ifdef IS_TRANSLUCENT
    float wakes_shade = 1.0;
    position = wakes_displace(position, wakes_shade);
#endif
    gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);""";

    /** Marker line we look for in Sodium's shader to attach the shade modulation. */
    private static final String VERTEX_VCOLOR_ORIGINAL =
        "v_Color = _vert_color * texture(u_LightTex, _vert_tex_light_coord);";
    private static final String VERTEX_VCOLOR_REPLACEMENT = """
v_Color = _vert_color * texture(u_LightTex, _vert_tex_light_coord);
#ifdef IS_TRANSLUCENT
    v_Color.rgb *= wakes_shade;
#endif""";

    /** Header only; fragment-stage just needs to know u_WakesTime exists if it ever wants it. */
    private static final String INJECTED_WAVE_FN = "wakes_displace";

    private WakesShaderInjection() {}

    public static String maybePatch(String path, String source) {
        if (source.contains(SENTINEL)) return source;   // already patched (recursion guard)

        if (path.endsWith(VERTEX_TARGET)) {
            return patchVertex(source);
        }
        // Fragment stage left untouched for now — wave colouring/normals can come later.
        return source;
    }

    private static String patchVertex(String source) {
        if (!source.contains(VERTEX_GL_POSITION_ORIGINAL)) {
            Wakes.LOG.warn("Wakes: Sodium vertex shader didn't match expected gl_Position pattern; skipping patch.");
            return source;
        }

        String injected = injectAfterImports(source, VERTEX_HEADER_INJECTION);
        injected = injected.replace(VERTEX_GL_POSITION_ORIGINAL, VERTEX_GL_POSITION_REPLACEMENT);
        if (injected.contains(VERTEX_VCOLOR_ORIGINAL)) {
            injected = injected.replace(VERTEX_VCOLOR_ORIGINAL, VERTEX_VCOLOR_REPLACEMENT);
        } else {
            Wakes.LOG.warn("Wakes: v_Color line not found — wave shading skipped (geometry still displaces).");
        }
        Wakes.LOG.info("Wakes: patched Sodium block_layer_opaque.vsh with {} + shade", INJECTED_WAVE_FN);
        return injected;
    }

    /**
     * Insert {@code injection} after the last {@code #import} or {@code uniform} block at
     * the top of the file, so we don't break Sodium's preprocessor's import resolution.
     */
    private static String injectAfterImports(String source, String injection) {
        int lastImport = source.lastIndexOf("#import");
        if (lastImport < 0) {
            // No imports — drop after #version.
            int versionEnd = source.indexOf('\n', source.indexOf("#version"));
            if (versionEnd < 0) return injection + "\n" + source;
            return source.substring(0, versionEnd + 1) + injection + "\n" + source.substring(versionEnd + 1);
        }
        int eol = source.indexOf('\n', lastImport);
        if (eol < 0) eol = source.length() - 1;
        return source.substring(0, eol + 1) + injection + "\n" + source.substring(eol + 1);
    }
}
