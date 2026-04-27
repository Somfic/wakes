package dev.wakes;

import net.neoforged.fml.loading.LoadingModList;

public final class ModCompat {
    public static final boolean AERONAUTICS_LOADED = isLoaded("aeronautics");
    public static final boolean SODIUM_LOADED      = isLoaded("sodium");
    public static final boolean SABLE_LOADED       = isLoaded("sable");

    private ModCompat() {}

    private static boolean isLoaded(String modId) {
        try {
            return LoadingModList.get().getModFileById(modId) != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
