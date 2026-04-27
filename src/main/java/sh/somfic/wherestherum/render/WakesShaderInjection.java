package sh.somfic.wherestherum.render;

import sh.somfic.wherestherum.ModCompat;
import sh.somfic.wherestherum.Wakes;
import sh.somfic.wherestherum.wave.WakesWaveGLSL;

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

    private static final String VERTEX_TARGET = "blocks/block_layer_opaque.vsh";

    /** Marker that proves to debugging eyes the patch landed. */
    private static final String SENTINEL = "// --- wakes:injected ---";

    private static final String VERTEX_HEADER_INJECTION = SENTINEL + "\n"
        + "#ifdef IS_TRANSLUCENT\n"
        + "uniform float u_WakesTime;\n"
        + "uniform vec3  u_WakesCameraPos;\n"
        + "uniform float u_WakesWeather;\n"
        + "uniform float u_WakesDepth;\n"
        + "uniform sampler2D u_WakesDepthMap;\n"
        + "uniform vec2  u_WakesDepthMapOrigin;\n"
        + "uniform float u_WakesDepthMapRange;\n"
        + "\n"
        + "float wakes_depthAt(vec2 worldXZ) {\n"
        + "    vec2 uv = (worldXZ - u_WakesDepthMapOrigin) / u_WakesDepthMapRange;\n"
        + "    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) {\n"
        + "        return u_WakesDepth;\n"
        + "    }\n"
        + "    return texture(u_WakesDepthMap, uv).r;\n"
        + "}\n"
        + "\n"
        + WakesWaveGLSL.SWELL_CHOP_FNS
        + "\n"
        + "// Convenience overload: pulls weather/depth from uniforms / depth-map.\n"
        + "float wakes_waveHeight(vec2 worldXZ) {\n"
        + "    return wakes_waveHeightAmp(worldXZ, u_WakesTime, u_WakesWeather, wakes_depthAt(worldXZ));\n"
        + "}\n"
        + "\n"
        + "vec3 wakes_normal(vec2 p) {\n"
        + "    const float E = 0.6;\n"
        + "    float hL = wakes_waveHeight(p - vec2(E, 0.0));\n"
        + "    float hR = wakes_waveHeight(p + vec2(E, 0.0));\n"
        + "    float hD = wakes_waveHeight(p - vec2(0.0, E));\n"
        + "    float hU = wakes_waveHeight(p + vec2(0.0, E));\n"
        + "    return normalize(vec3((hL - hR) / (2.0 * E), 1.0, (hD - hU) / (2.0 * E)));\n"
        + "}\n"
        + "\n"
        + "vec3 wakes_displace(vec3 pos, out float outShade) {\n"
        + "    outShade = 1.0;\n"
        + "    vec3 world = pos + u_WakesCameraPos;\n"
        + "    float dist = abs(world.y - 63.0);\n"
        + "    float falloff = 1.0 - smoothstep(2.0, 6.0, dist);\n"
        + "    if (falloff <= 0.0) return pos;\n"
        + "    pos.y += wakes_waveHeight(world.xz) * falloff;\n"
        + "    vec3 n = wakes_normal(world.xz);\n"
        + "    const vec3 SUN = normalize(vec3(0.4, 0.85, 0.3));\n"
        + "    float ndl = max(dot(n, SUN), 0.0);\n"
        + "    outShade = mix(1.0, 0.7 + ndl * 0.6, falloff);\n"
        + "    return pos;\n"
        + "}\n"
        + "#endif\n";

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

    private static final String INJECTED_WAVE_FN = "wakes_displace";

    private WakesShaderInjection() {}

    public static String maybePatch(String path, String source) {
        if (source.contains(SENTINEL)) return source;   // already patched (recursion guard)
        // Always patch — when Iris is loaded with a pack active, Iris's own
        // pipeline overrides Sodium's program anyway, so our patched Sodium
        // version is harmlessly unused. Skipping it here was leaving water
        // flat in the no-pack-loaded-but-Iris-installed case where the
        // isShaderPackInUse() check disagreed with reality.

        if (path.endsWith(VERTEX_TARGET)) {
            return patchVertex(source);
        }
        // Fragment shader intentionally untouched — keeps the look vanilla-MC-aligned.
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
