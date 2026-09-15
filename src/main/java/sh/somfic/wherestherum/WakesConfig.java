package sh.somfic.wherestherum;

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
    public static final ModConfigSpec.BooleanValue DEBUG_PARTICLES;
    public static final ModConfigSpec.BooleanValue DEBUG_LOG;

    public static final ModConfigSpec.BooleanValue FLOAT_ENABLED;
    public static final ModConfigSpec.DoubleValue FLOAT_LIFT_PER_BLOCK;
    public static final ModConfigSpec.DoubleValue FLOAT_MAX_DEPTH;
    public static final ModConfigSpec.DoubleValue FLOAT_DAMPING_RATIO;
    public static final ModConfigSpec.BooleanValue PREVENT_CORAL_DEATH;

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
        DEBUG_PARTICLES = b.comment("Spawn bubble particles at each wave-force sample point, showing force direction and magnitude. Useful for tuning, noisy in normal play.")
                .define("debugParticles", false);
        DEBUG_LOG = b.comment("Log per-ship per-second wave-physics diagnostics (mass, angVel, total impulse, net torque). Useful for chasing rotation runaways; spammy in normal play.")
                .define("debugLog", false);
        b.pop();

        b.push("buoyancy");
        FLOAT_ENABLED = b.comment(
                "Depth-proportional buoyancy for blocks tagged #wherestherum:floats (full-cube coral blocks by default).",
                "Unlike Aeronautics levitite this adds NO rotational drag, so hulls keep rocking with the swell.")
                .define("enabled", true);
        FLOAT_LIFT_PER_BLOCK = b.comment(
                "Lift contributed per floating block, per block of submersion depth.",
                "Scales with floating-block COUNT, not ship mass — float a heavier hull by packing in more coral.",
                "Total lift = liftPerBlock * submergedDepth, so a hull settles at its own waterline instead of launching.")
                .defineInRange("liftPerBlock", 8.0, 0.0, 200.0);
        FLOAT_MAX_DEPTH = b.comment(
                "Submersion depth at which a block's lift stops growing (blocks). Caps force on deeply sunk hulls.")
                .defineInRange("maxDepth", 3.0, 0.1, 32.0);
        FLOAT_DAMPING_RATIO = b.comment(
                "Vertical damping as a DAMPING RATIO, not an absolute rate. 1.0 = critically damped",
                "(settles fast, no overshoot), ~0.7 leaves some life in the heave, 0 disables entirely.",
                "The actual coefficient is derived per tick from the hull's real stiffness and mass",
                "(c = ratio * 2*sqrt(k*m)), so it stays correctly damped as you add coral or cargo.",
                "Applied at the centre of mass, so it never damps roll or pitch — only heave.")
                .defineInRange("dampingRatio", 1.0, 0.0, 4.0);
        PREVENT_CORAL_DEATH = b.comment(
                "Stop full-cube coral blocks drying out into their dead variants.",
                "Coral is the default buoyancy material and hulls enclose it in dry spaces, where vanilla",
                "would kill it. Dead coral still floats, so this is cosmetic — it keeps a hull from going grey.",
                "Applies worldwide, not just on ships, so coral also survives while you are still building.")
                .define("preventCoralDeath", true);
        b.pop();

        SPEC = b.build();
    }

    private WakesConfig() {}
}
