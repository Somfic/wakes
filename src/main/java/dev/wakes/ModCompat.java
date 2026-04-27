package dev.wakes;

import net.neoforged.fml.loading.LoadingModList;

public final class ModCompat {
    public static final boolean AERONAUTICS_LOADED = isLoaded("aeronautics");
    public static final boolean SODIUM_LOADED      = isLoaded("sodium");
    public static final boolean SABLE_LOADED       = isLoaded("sable");
    public static final boolean IRIS_LOADED        = isLoaded("iris");

    private ModCompat() {}

    /** True iff Iris is loaded AND a shader pack is currently active. Reflective so
     *  the Iris classes don't have to be on the classpath when Iris is absent. */
    public static boolean irisShadersActive() {
        if (!IRIS_LOADED) return false;
        try {
            Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Object inUse = apiClass.getMethod("isShaderPackInUse").invoke(api);
            return inUse instanceof Boolean && (Boolean) inUse;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isLoaded(String modId) {
        try {
            return LoadingModList.get().getModFileById(modId) != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
