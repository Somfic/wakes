package dev.wakes.render;

import dev.wakes.Wakes;
import dev.wakes.wave.WakesWaveGLSL;

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

    /** GLSL wave block injected into the pack's gbuffers_water.vsh. Pulls in
     *  the central wave function from {@link WakesWaveGLSL} and adds an Iris
     *  adapter that maps to its built-in uniforms ({@code cameraPosition},
     *  {@code frameTimeCounter}, {@code rainStrength}). Iris reports time in
     *  seconds; we convert to ticks (×20) to share the SAME wave-function
     *  constants as the Sodium path. */
    private static final String WAVE_BLOCK = SENTINEL + "\n\n"
        + WakesWaveGLSL.SWELL_CHOP_FNS
        + "\n"
        + "// Iris adapter: derive `t` from frameTimeCounter, weather from rainStrength.\n"
        + "// Depth tied to 1.0 (full ocean) — could be plumbed via custom uniform later.\n"
        + "float wakes_waveHeight(vec2 worldXZ) {\n"
        + "    float t = frameTimeCounter * 20.0;\n"
        + "    return wakes_waveHeightAmp(worldXZ, t, rainStrength, 1.0);\n"
        + "}\n";

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
