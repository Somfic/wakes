package sh.somfic.wherestherum.render;

import sh.somfic.wherestherum.ModCompat;
import sh.somfic.wherestherum.Wakes;
import sh.somfic.wherestherum.WakesConfig;
import sh.somfic.wherestherum.wave.WakesWaveGLSL;

import java.util.Locale;

/**
 * Patches Sodium's chunk shaders with our wave-displacement code at source-load
 * time. We touch only the vertex stage of the block layer, and gate displacement
 * behind {@code IS_TRANSLUCENT} so opaque/cutout passes are untouched.
 *
 * The vertex shader injection:
 *   1. Inserts a uniform declaration and the wave function include.
 *   2. Replaces the line that builds {@code gl_Position} with a call wrapping
 *      the world-space {@code position} through {@link #INJECTED_WAVE_FN}.
 *   3. Replaces the {@code v_Color} line to fold in wave shading and (optionally)
 *      shoreline foam.
 *
 * <h2>Why the header is built at patch time instead of being a constant</h2>
 * The surf/foam feature is configurable ({@link WakesConfig#SURF_ENABLED},
 * {@link WakesConfig#SURF_INTENSITY}) but GLSL cannot read a NeoForge config.
 * The obvious fix — another uniform — is not available to us: the uniform
 * binding lives in Sodium's shader-interface mixin, which this class does not
 * own. So the config is resolved on the Java side and baked into the generated
 * source: when surf is off (or intensity is 0) the foam code is simply not
 * emitted at all, and when it is on the intensity arrives as a GLSL literal.
 *
 * <p><b>Consequence:</b> changing either surf config value does NOT take effect
 * until the shaders are re-created — i.e. a resource reload (F3+T) or a game
 * restart. That is a deliberate trade-off against a uniform we cannot bind.
 */
public final class WakesShaderInjection {

    private static final String VERTEX_TARGET = "blocks/block_layer_opaque.vsh";

    /** Marker that proves to debugging eyes the patch landed. */
    private static final String SENTINEL = "// --- wakes:injected ---";

    /**
     * Everything up to (but not including) {@code wakes_displace}. Constant,
     * because none of it depends on config.
     */
    private static final String VERTEX_HEADER_PRELUDE = SENTINEL + "\n"
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
        + "\n";

    /** Body of {@code wakes_displace} up to the point where foam is computed. */
    private static final String DISPLACE_HEAD =
          "vec3 wakes_displace(vec3 pos, out float outShade) {\n"
        + "    outShade = 1.0;\n"
        + "    vec3 world = pos + u_WakesCameraPos;\n"
        + "    float dist = abs(world.y - 63.0);\n"
        + "    float falloff = 1.0 - smoothstep(2.0, 6.0, dist);\n"
        + "    if (falloff <= 0.0) return pos;\n"
        + "    pos.y += wakes_waveHeight(world.xz) * falloff;\n"
        + "    vec3 n = wakes_normal(world.xz);\n"
        + "    const vec3 SUN = normalize(vec3(0.4, 0.85, 0.3));\n"
        + "    float ndl = max(dot(n, SUN), 0.0);\n"
        + "    outShade = mix(1.0, 0.7 + ndl * 0.6, falloff);\n";

    private static final String DISPLACE_TAIL =
          "    return pos;\n"
        + "}\n";

    /**
     * Foam write, emitted only when surf is enabled. Note it sits AFTER the
     * {@code falloff <= 0.0} early-out, so geometry far from sea level never
     * pays for the noise and never foams — {@code wakes_foamAmt} keeps its
     * global initialiser of 0.0 in that case.
     */
    private static final String DISPLACE_FOAM =
          "    wakes_foamAmt = clamp(\n"
        + "        wakes_foam(world.xz, u_WakesTime, wakes_depthAt(world.xz))\n"
        + "            * falloff * WAKES_SURF_INTENSITY,\n"
        + "        0.0, 1.0);\n";

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

    /**
     * How far foam is allowed to push the vertex colour towards white. Capped
     * below 1.0 on purpose: at a full mix the water loses its tint entirely and
     * the crest reads as a hole in the world rather than as surf.
     */
    private static final String FOAM_MAX_WHITEN = "0.85";

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

        // Resolve config ONCE per patch so the header and the v_Color rewrite
        // can't disagree about whether foam exists (a mismatch would emit a
        // reference to an undeclared `wakes_foamAmt` and fail to compile).
        float surfIntensity = resolveSurfIntensity();
        boolean surf = surfIntensity > 0.0f;

        String injected = injectAfterImports(source, buildVertexHeader(surf, surfIntensity));
        injected = injected.replace(VERTEX_GL_POSITION_ORIGINAL, VERTEX_GL_POSITION_REPLACEMENT);
        if (injected.contains(VERTEX_VCOLOR_ORIGINAL)) {
            injected = injected.replace(VERTEX_VCOLOR_ORIGINAL, buildVColorReplacement(surf));
        } else {
            Wakes.LOG.warn("Wakes: v_Color line not found — wave shading{} skipped (geometry still displaces).",
                surf ? " and surf foam" : "");
        }
        Wakes.LOG.info("Wakes: patched Sodium block_layer_opaque.vsh with {} + shade{}", INJECTED_WAVE_FN,
            surf ? " + surf (intensity " + surfIntensity + ")" : " (surf off)");
        return injected;
    }

    /**
     * Assembles the injected GLSL header. When {@code surf} is false not one byte
     * of foam code is emitted, so a user who turns surf off pays exactly nothing
     * at runtime — no branch, no noise, no extra depth-map fetch.
     */
    private static String buildVertexHeader(boolean surf, float intensity) {
        StringBuilder sb = new StringBuilder(VERTEX_HEADER_PRELUDE.length() + 4096);
        sb.append(VERTEX_HEADER_PRELUDE);

        if (surf) {
            // Baked from WakesConfig.SURF_INTENSITY at shader-build time; see the
            // class javadoc for why this is a literal and not a uniform. Changing
            // the config requires a shader reload (F3+T) to take effect.
            sb.append("// Baked from config `surf.intensity`; requires a resource reload to change.\n");
            sb.append("const float WAKES_SURF_INTENSITY = ").append(glslFloat(intensity)).append(";\n");
            // Mutable global rather than an extra out-parameter on wakes_displace:
            // keeps the existing call site in VERTEX_GL_POSITION_REPLACEMENT (and
            // therefore the string anchors) completely untouched.
            sb.append("float wakes_foamAmt = 0.0;\n\n");
            sb.append(WakesWaveGLSL.SURF_FNS);
            sb.append('\n');
        }

        sb.append(DISPLACE_HEAD);
        if (surf) sb.append(DISPLACE_FOAM);
        sb.append(DISPLACE_TAIL);
        sb.append("#endif\n");
        return sb.toString();
    }

    private static String buildVColorReplacement(boolean surf) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(VERTEX_VCOLOR_ORIGINAL).append('\n');
        sb.append("#ifdef IS_TRANSLUCENT\n");
        sb.append("    v_Color.rgb *= wakes_shade;\n");
        if (surf) {
            // mix() towards white rather than a multiply: foam is an additive
            // layer of aerated water sitting on top of the block colour, and a
            // multiply can only ever scale the existing (blue) tint, which reads
            // as "brighter water" instead of "white water".
            sb.append("    v_Color.rgb = mix(v_Color.rgb, vec3(1.0), wakes_foamAmt * ")
              .append(FOAM_MAX_WHITEN).append(");\n");
        }
        sb.append("#endif");
        return sb.toString();
    }

    /**
     * @return the configured foam intensity, or 0 when surf is disabled.
     *         Falls back to the default of 1.0 if the config isn't loaded yet —
     *         shader sources can be requested very early, and a config read
     *         before load throws rather than returning a default.
     */
    private static float resolveSurfIntensity() {
        try {
            if (!WakesConfig.SURF_ENABLED.get()) return 0.0f;
            return (float) Math.max(0.0, WakesConfig.SURF_INTENSITY.get());
        } catch (IllegalStateException | NullPointerException e) {
            Wakes.LOG.warn("Wakes: surf config not loaded at shader-build time; using default intensity 1.0");
            return 1.0f;
        }
    }

    /** Formats a Java float as a GLSL float literal (always with a decimal point,
     *  always locale-independent — a comma here would be a compile error). */
    private static String glslFloat(float v) {
        return String.format(Locale.ROOT, "%.4f", v);
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
