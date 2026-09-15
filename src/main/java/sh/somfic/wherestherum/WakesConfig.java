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

    public static final ModConfigSpec.BooleanValue DRAG_ENABLED;
    public static final ModConfigSpec.DoubleValue DRAG_LONGITUDINAL;
    public static final ModConfigSpec.DoubleValue DRAG_LATERAL;
    public static final ModConfigSpec.DoubleValue DRAG_VERTICAL;
    public static final ModConfigSpec.DoubleValue DRAG_YAW;

    public static final ModConfigSpec.BooleanValue SAILS_ENABLED;
    public static final ModConfigSpec.DoubleValue SAILS_THRUST;
    public static final ModConfigSpec.DoubleValue SAILS_MIN_ANGLE;
    public static final ModConfigSpec.DoubleValue WIND_SPEED;
    public static final ModConfigSpec.DoubleValue WIND_VARIABILITY;

    public static final ModConfigSpec.BooleanValue ENTITY_BUOYANCY;
    public static final ModConfigSpec.DoubleValue ENTITY_BUOYANCY_STRENGTH;
    public static final ModConfigSpec.BooleanValue ENTITY_BUOYANCY_BOATS_ONLY;

    public static final ModConfigSpec.BooleanValue SOUND_ENABLED;
    public static final ModConfigSpec.DoubleValue SOUND_WAVE_VOLUME;
    public static final ModConfigSpec.DoubleValue SOUND_CREAK_VOLUME;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> SOUND_CREAK_IDS;

    public static final ModConfigSpec.BooleanValue WAKE_ENABLED;
    public static final ModConfigSpec.DoubleValue WAKE_DENSITY;
    public static final ModConfigSpec.DoubleValue WAKE_MIN_SPEED;

    public static final ModConfigSpec.BooleanValue SURF_ENABLED;
    public static final ModConfigSpec.DoubleValue SURF_INTENSITY;

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

        b.push("hydrodynamics");
        DRAG_ENABLED = b.comment(
                "Resist motion THROUGH the water. Without this a hull skates sideways like it is on ice,",
                "because only wave drift acts on it. Drag is anisotropic in the hull frame — see below.")
                .define("enabled", true);
        DRAG_LONGITUDINAL = b.comment(
                "Drag along the keel (fore/aft), per submerged floating block. Low: hulls are shaped to go this way.")
                .defineInRange("longitudinal", 0.6, 0.0, 50.0);
        DRAG_LATERAL = b.comment(
                "Drag abeam (sideways), per submerged block. Should be MUCH higher than longitudinal —",
                "this ratio is what makes a ship carve a turn instead of sliding through it.")
                .defineInRange("lateral", 6.0, 0.0, 50.0);
        DRAG_VERTICAL = b.comment(
                "Drag on vertical motion through water, per submerged block. Damps slamming into swell.")
                .defineInRange("vertical", 2.0, 0.0, 50.0);
        DRAG_YAW = b.comment(
                "Rotational drag about the vertical axis only (1/s). Stops a hull spinning forever after a turn.",
                "Deliberately yaw-ONLY: roll and pitch stay undamped so the ship keeps rocking with the swell.")
                .defineInRange("yawDamping", 1.5, 0.0, 20.0);
        b.pop();

        b.push("sails");
        SAILS_ENABLED = b.comment(
                "Wind propulsion for blocks tagged #wherestherum:sails (wool and banners by default).")
                .define("enabled", true);
        SAILS_THRUST = b.comment(
                "Thrust per sail block at full wind, dead downwind. Scales with sail area and wind alignment.")
                .defineInRange("thrustPerBlock", 4.0, 0.0, 200.0);
        SAILS_MIN_ANGLE = b.comment(
                "Cosine of the closest angle to the wind a sail can still draw. 0.0 = can sail across the wind,",
                "negative values let you point upwind. Positive values force real tacking.")
                .defineInRange("minWindAlignment", 0.0, -1.0, 1.0);
        WIND_SPEED = b.comment(
                "Base wind strength multiplier. Storms raise this automatically via the weather factor.")
                .defineInRange("windSpeed", 1.0, 0.0, 5.0);
        WIND_VARIABILITY = b.comment(
                "How much the wind direction wanders over time (0 = fixed, 1 = swings widely).",
                "Wind also steers the wave field, so changing this changes which way the swell runs.")
                .defineInRange("windVariability", 0.35, 0.0, 1.0);
        b.pop();

        b.push("entities");
        ENTITY_BUOYANCY = b.comment(
                "Let boats, players and mobs ride the wave surface instead of a flat sea.",
                "Applies a gentle vertical force toward the local wave height while they are at the surface.")
                .define("enabled", true);
        ENTITY_BUOYANCY_STRENGTH = b.comment(
                "How strongly entities are pulled toward the wave surface. Higher = snappier bobbing.")
                .defineInRange("strength", 1.0, 0.0, 10.0);
        ENTITY_BUOYANCY_BOATS_ONLY = b.comment(
                "Restrict wave bobbing to boats, leaving swimming players and mobs on the vanilla surface.")
                .define("boatsOnly", false);
        b.pop();

        b.push("sound");
        SOUND_ENABLED = b.comment(
                "Ambient ocean and hull sounds driven by the live wave state.")
                .define("enabled", true);
        SOUND_WAVE_VOLUME = b.comment(
                "Volume of the wave wash loop. Scales with local wave amplitude, so storms get louder.")
                .defineInRange("waveVolume", 1.0, 0.0, 2.0);
        SOUND_CREAK_VOLUME = b.comment(
                "Volume of hull creaking. Scales with how hard the ship you are standing on is rocking.")
                .defineInRange("creakVolume", 1.0, 0.0, 2.0);
        SOUND_CREAK_IDS = b.comment(
                "Sound events used for hull creaks, tried in order — the first one that EXISTS is used.",
                "Vanilla 1.21.1 has no creak asset (the Creaking mob is 1.21.4+), and the only sounds Mojang",
                "itself labels as creaking are the door / fence-gate / trapdoor toggles, which carry an obvious",
                "latch click. So the default prefers the Sounds mod's block.ice.stress: a sustained groan of",
                "material under strain, which is far closer to timbers working than a door hinge.",
                "Ids that are not registered are skipped silently, so listing optional-mod sounds here is safe.",
                "wherestherum:hull_creak is our own vanilla-only fallback, defined in assets/wherestherum/sounds.json.",
                "Swap in any sound id you prefer — this is deliberately data-driven because the right creak is",
                "a matter of taste and cannot be settled by reading identifiers.")
                .defineList("creakSounds",
                        java.util.List.of("sounds:block.ice.stress", "wherestherum:hull_creak"),
                        () -> "wherestherum:hull_creak",
                        o -> o instanceof String);
        b.pop();

        b.push("wake");
        WAKE_ENABLED = b.comment(
                "Foam and spray trailing vessels that are under way. Purely visual, client-side.")
                .define("enabled", true);
        WAKE_DENSITY = b.comment(
                "How many wake particles to emit. Scales with speed; lower this first if it costs frames.")
                .defineInRange("density", 1.0, 0.0, 4.0);
        WAKE_MIN_SPEED = b.comment(
                "Speed (blocks/tick) a vessel must exceed before it throws a wake at all.")
                .defineInRange("minSpeed", 0.04, 0.0, 2.0);
        b.pop();

        b.push("surf");
        SURF_ENABLED = b.comment(
                "Foam where waves meet shallow water. Uses the same depth map that scales wave amplitude,",
                "so the foam line follows the real shoreline rather than a fixed radius.")
                .define("enabled", true);
        SURF_INTENSITY = b.comment(
                "Foam strength. 0 = none, 1 = default, higher = heavier surf.")
                .defineInRange("intensity", 1.0, 0.0, 3.0);
        b.pop();

        SPEC = b.build();
    }

    private WakesConfig() {}
}
