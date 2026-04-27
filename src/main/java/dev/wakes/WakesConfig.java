package dev.wakes;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class WakesConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.IntValue SAMPLE_POINTS_PER_AXIS;
    public static final ModConfigSpec.DoubleValue HEAVE_SCALE;
    public static final ModConfigSpec.DoubleValue PITCH_SCALE;
    public static final ModConfigSpec.DoubleValue ROLL_SCALE;
    public static final ModConfigSpec.BooleanValue REQUIRE_WATER_UNDERNEATH;
    public static final ModConfigSpec.BooleanValue AFFECT_ALL_CONTRAPTIONS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("visual");
        ENABLED = b.comment("Master toggle for the wave-bobbing render transform.")
                .define("enabled", true);
        SAMPLE_POINTS_PER_AXIS = b.comment("Hull sample grid size per axis. 1 = single-point bob, 3+ = ship-like rocking.")
                .defineInRange("samplePointsPerAxis", 3, 1, 9);
        HEAVE_SCALE = b.comment("Multiplier on vertical bob height.")
                .defineInRange("heaveScale", 1.0, 0.0, 4.0);
        PITCH_SCALE = b.comment("Multiplier on pitch tilt induced by waves.")
                .defineInRange("pitchScale", 1.0, 0.0, 4.0);
        ROLL_SCALE = b.comment("Multiplier on roll tilt induced by waves.")
                .defineInRange("rollScale", 1.0, 0.0, 4.0);
        REQUIRE_WATER_UNDERNEATH = b.comment("Only apply when the contraption footprint actually overlaps water.")
                .define("requireWaterUnderneath", true);
        AFFECT_ALL_CONTRAPTIONS = b.comment("Phase 1 only targets propeller-bearing contraptions; flip on later phases.")
                .define("affectAllContraptions", false);
        b.pop();

        SPEC = b.build();
    }

    private WakesConfig() {}
}
