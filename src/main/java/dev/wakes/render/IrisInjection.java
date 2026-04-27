package dev.wakes.render;

import dev.wakes.Wakes;

/**
 * Patches a loaded Iris shader pack's water vertex shader to use our wave
 * displacement, so visible water lines up with our physics-driven ship bobbing.
 *
 * Strategy: wrap the pack's existing {@code gl_Position = ...} write with our
 * wave displacement function applied to the world-space position. Iris exposes
 * {@code cameraPosition} and {@code frameTimeCounter} as built-in uniforms so
 * we don't need to bind any custom ones.
 *
 * Pack-specific: for v0.1 we only target Complementary (detected via the
 * commented header line every Complementary shader has). Other packs are
 * left alone — see {@link #shouldPatch}.
 */
public final class IrisInjection {

    /** Marker so we don't double-patch on shader reloads. */
    private static final String SENTINEL = "// --- wakes:iris-injected ---";

    /** Heuristic for detecting a Complementary shader pack — every pack file
     *  starts with a "Complementary Shaders" comment header. */
    private static final String COMPLEMENTARY_MARKER = "Complementary";

    /** GLSL wave block injected into the pack's gbuffers_water.vsh. Uses Iris
     *  built-in uniforms ({@code cameraPosition}, {@code frameTimeCounter}) —
     *  these are already declared by the pack's own include chain so we MUST
     *  NOT redeclare them. */
    private static final String WAVE_BLOCK = SENTINEL + """

float wakes_swell(vec2 p) {
    const int   ITER         = 10;
    const float FREQUENCY    = 4.5;
    const float SPEED        = 1.6;
    const float WEIGHT_INIT  = 0.85;
    const float FREQ_MULT    = 1.21;
    const float SPEED_MULT   = 1.07;
    const float ITER_INC     = 12.0;
    const float DRAG_MULT    = 0.052;
    const float XZ_SCALE     = 0.035;
    const float TIME_MULT    = 0.045 * 20.0;   // frameTimeCounter is in seconds; we want ticks

    float t = frameTimeCounter * TIME_MULT;
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
    return (height / sumW) * 2.0 - 1.0;
}

float wakes_chop(vec2 p) {
    float t = frameTimeCounter * (0.18 * 20.0);
    vec2 q = p * 0.22;
    return 0.5 * (
          sin(q.x * 1.4 + q.y * 0.7 + t)
        + sin(q.y * 2.1 - q.x * 0.5 + t * 1.3)
    );
}

float wakes_waveHeight(vec2 worldXZ) {
    // Match the Sodium-path formula so visual amplitude is consistent between
    // shaders-on and shaders-off. Iris built-in `rainStrength` (0..1) drives
    // weather scaling. (No thunder distinction available as a built-in; close
    // enough — thunderstorm waves will be ~30% smaller than Sodium's, fine.)
    float weather = rainStrength;
    float swellAmp = 0.9 + weather * 1.4;   // calm 0.9 .. storm 2.3
    float chopAmp  = 0.05 + weather * 0.45;
    return wakes_swell(worldXZ) * swellAmp + wakes_chop(worldXZ) * chopAmp;
}
""";

    private IrisInjection() {}

    /**
     * @param packName  result of {@code programSet.getPack().getName()} or null
     * @param source    the pack's water vertex shader GLSL
     * @return source possibly patched, or unchanged if pack isn't supported
     *         or pattern didn't match
     */
    public static String maybePatchWaterVertex(String packName, String source) {
        if (source == null || source.contains(SENTINEL)) return source;
        if (!shouldPatch(packName, source)) return source;

        // Inject right before the gl_Position write — same place CR's own
        // DoWave() fires. This is AFTER `playerPos = position.xyz` so the
        // fragment shader's lighting/material/reflection calculations use the
        // un-displaced position (matching CR's "water surface lives at this
        // block Y" assumption). Otherwise the pack's at-sea-level material
        // checks misfire on displaced vertices.
        String[] positionPatterns = {
            "gl_Position = gl_ProjectionMatrix * gbufferModelView * position;",
            "gl_Position = gl_ProjectionMatrix * gl_ModelViewMatrix * gl_Vertex;",
            "gl_Position = ftransform();"
        };
        String matched = null;
        int posIdx = -1;
        for (String p : positionPatterns) {
            int i = source.indexOf(p);
            if (i >= 0) { matched = p; posIdx = i; break; }
        }
        if (matched == null) {
            Wakes.LOG.warn("Wakes/Iris: water vsh has no recognised gl_Position line; skipping pack '{}'.", packName);
            return source;
        }

        // The wave function reference uniforms (cameraPosition, frameTimeCounter)
        // declared by the pack's includes — those are in scope inside main(),
        // not at file-top before #include. Inject the wave function definition
        // just before `void main()` so the uniforms have already been declared.
        int mainIdx = source.indexOf("void main()");
        if (mainIdx < 0) {
            Wakes.LOG.warn("Wakes/Iris: water vsh has no `void main()`; skipping pack '{}'.", packName);
            return source;
        }

        // Build the patched source in two splices: insert wave-function block
        // before main(), and the displacement statement before gl_Position write.
        // Pattern 1 (CR canonical) and 2 use a `position` local variable already
        // in player-relative world coords (post `gbufferModelViewInverse`).
        // Pattern 3 (ftransform) doesn't expose such a variable — skip those packs.
        boolean usesPositionLocal = matched.contains("position;");
        String displacementPatch;
        if (usesPositionLocal) {
            displacementPatch = """
    // --- Wakes wave displacement (injected just before gl_Position) ---
    {
        vec2 wakes_worldXZ = position.xz + cameraPosition.xz;
        position.y += wakes_waveHeight(wakes_worldXZ);
    }
    """ + matched;
        } else {
            Wakes.LOG.warn("Wakes/Iris: pack uses ftransform()-style gl_Position; not yet supported.");
            return source;
        }

        StringBuilder sb = new StringBuilder(source.length() + WAVE_BLOCK.length() + 256);
        sb.append(source, 0, mainIdx);
        sb.append(WAVE_BLOCK);
        sb.append('\n');
        // Adjust posIdx if it was after mainIdx (it isn't in CR's layout but be safe).
        int posIdxAdjusted = posIdx >= mainIdx ? posIdx + WAVE_BLOCK.length() + 1 : posIdx;
        // Now from mainIdx onward, append with displacement patched.
        String tail = source.substring(mainIdx);
        int relativePosIdx = tail.indexOf(matched);
        if (relativePosIdx < 0) {
            // posIdx was BEFORE main(), so it'll already be in the prepended chunk.
            // But we copied source[0..mainIdx] verbatim, so we need to patch it there.
            sb.append(tail);
            String full = sb.toString();
            int finalIdx = full.indexOf(matched);
            if (finalIdx < 0) {
                Wakes.LOG.warn("Wakes/Iris: position pattern lost during patching; aborting pack '{}'.", packName);
                return source;
            }
            String result = full.substring(0, finalIdx) + displacementPatch + full.substring(finalIdx + matched.length());
            Wakes.LOG.info("Wakes/Iris: patched water vertex shader for pack '{}'", packName);
            return result;
        } else {
            sb.append(tail, 0, relativePosIdx);
            sb.append(displacementPatch);
            sb.append(tail, relativePosIdx + matched.length(), tail.length());
            Wakes.LOG.info("Wakes/Iris: patched water vertex shader for pack '{}'", packName);
            return sb.toString();
        }
    }

    private static boolean shouldPatch(String packName, String source) {
        // Detect Complementary either by pack name (preferred) or by the
        // characteristic header comment Complementary shaders embed.
        if (packName != null && packName.toLowerCase().contains("complementary")) return true;
        return source.contains(COMPLEMENTARY_MARKER);
    }
}
