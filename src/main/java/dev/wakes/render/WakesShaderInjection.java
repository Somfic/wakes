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

// Inlined wave field — must stay numerically identical to WakesWaveFunction.java.
float wakes_waveHeight(vec2 p) {
    const int   ITERATIONS     = 13;
    const float FREQUENCY      = 6.0;
    const float SPEED          = 2.0;
    const float WEIGHT_INIT    = 0.8;
    const float FREQUENCY_MULT = 1.18;
    const float SPEED_MULT     = 1.07;
    const float ITER_INC       = 12.0;
    const float DRAG_MULT      = 0.048;
    const float XZ_SCALE       = 0.04;
    const float TIME_MULT      = 0.05;
    const float AMPLITUDE      = 0.8;

    float t = u_WakesTime * TIME_MULT;
    vec2  pos = p * XZ_SCALE;
    float angle = 0.0, freq = FREQUENCY, speed = SPEED, weight = WEIGHT_INIT;
    float height = 0.0, sumW = 0.0;
    for (int i = 0; i < ITERATIONS; i++) {
        vec2 dir = vec2(sin(angle), cos(angle));
        float phase = dot(dir, pos) * freq + t * speed;
        float wave  = exp(sin(phase) - 1.0);
        float dWave = wave * cos(phase);
        pos    -= dir * dWave * weight * DRAG_MULT;
        height += wave * weight;
        sumW   += weight;
        weight *= 0.8;
        freq   *= FREQUENCY_MULT;
        speed  *= SPEED_MULT;
        angle  += ITER_INC;
    }
    return (height / sumW) * AMPLITUDE * 2.0 - AMPLITUDE;
}

// `pos` arrives in camera-relative space. Recover absolute world position so the
// wave field is locked to the world (not dragged with the player), and so the
// sea-level Y-clamp can compare to actual world Y. Translucent pass includes
// stained glass / slime / ice — narrow by world Y to spare them.
vec3 wakes_displace(vec3 pos) {
    vec3 world = pos + u_WakesCameraPos;
    float dist = abs(world.y - 63.0);
    float falloff = 1.0 - smoothstep(2.0, 6.0, dist);
    if (falloff <= 0.0) return pos;
    pos.y += wakes_waveHeight(world.xz) * falloff;
    return pos;
}
#endif
""";

    private static final String VERTEX_GL_POSITION_ORIGINAL =
        "gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);";
    private static final String VERTEX_GL_POSITION_REPLACEMENT = """
#ifdef IS_TRANSLUCENT
    position = wakes_displace(position);
#endif
    gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);""";

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
        Wakes.LOG.info("Wakes: patched Sodium block_layer_opaque.vsh with {}", INJECTED_WAVE_FN);
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
