package dev.wakes.mixin.sable;

import dev.wakes.Wakes;
import dev.wakes.WakesConfig;
import dev.ryanhcode.sable.api.physics.force.ForceGroup;
import dev.ryanhcode.sable.api.physics.force.QueuedForceGroup;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Real wave-driven buoyancy for Sable SubLevels (Aeronautics' physics-assembled
 * ships). Sable's existing FloatingBlockController already applies flat-water
 * buoyancy that holds the ship at sea level; we add a per-tick *delta* impulse at
 * a grid of hull-bottom sample points proportional to the wave-displacement of
 * the water surface above (or below) flat sea level.
 *
 * Net effect: the existing baseline keeps the ship floating; our delta makes it
 * rock with the wave field — heaving on swells, tilting toward troughs, getting
 * thrown around in storms.
 *
 * Server-side only: physics happens on the server, then state replicates to the
 * client which renders it.
 */
@Mixin(value = ServerSubLevel.class, remap = false)
public abstract class ServerSubLevelMixin {

    /** Vanilla overworld sea level. Configurable later if user has custom sea level. */
    private static final double SEA_LEVEL = 63.0;

    /** Direct gain on wave height. force = wave * GAIN * area.
     *  Sable's own flat-water buoyancy holds the ship at sea level; we just
     *  nudge it up when wave crests are over us, down in troughs. No spring,
     *  no target tracking — those were fighting Sable and triggering its
     *  collision-swept-bounds sanity check. */
    private static final double WAVE_GAIN = 0.4;

    /** Hard cap on the central force magnitude. */
    private static final double MAX_FORCE = 1.5;

    /** Stable identity for the queued force group — Sable indexes by ForceGroup record equality. */
    private static final ForceGroup WAKES_FORCE_GROUP = new ForceGroup(
        Component.literal("Wakes — wave buoyancy"),
        Component.literal("Per-sample wave-driven buoyancy delta on top of Sable's flat-water float"),
        0x3399ff,
        true
    );

    @Inject(
        method = "applyQueuedForces(Ldev/ryanhcode/sable/sublevel/system/SubLevelPhysicsSystem;Ldev/ryanhcode/sable/api/physics/handle/RigidBodyHandle;D)V",
        at = @At("HEAD"),
        require = 1
    )
    private void wakes$applyWaveBuoyancy(SubLevelPhysicsSystem system, RigidBodyHandle body, double dt, CallbackInfo ci) {
        // Disabled: Sable's force pipeline does not gracefully accept external
        // mods adding force groups. Every variant we tried either no-ops or
        // destroys the rigid body. Visual bobbing happens in ClientSubLevelMixin.
        if (true) return;
        if (!WakesConfig.ENABLED.get()) return;
        if (body == null || !body.isValid()) return;
        wakes$logOnce("prePhysicsTick inject firing — body valid, dt=" + dt);

        ServerSubLevel self = (ServerSubLevel) (Object) this;
        ServerLevel level = self.getLevel();
        if (level == null) return;

        BoundingBox3dc bb = self.boundingBox();
        if (bb == null) return;

        // Don't touch ships that aren't near the water surface. Two gates:
        //   1. Hull bottom must be within a few blocks of sea level (skip flying / sky).
        //   2. There must actually be water under the footprint at sea level.
        // Without (1) we'd fight gravity during free-fall from height — pulling a
        // raft down hard from y=200 trips Sable's "extreme Y range" sanity check.
        // Only gate on the UPPER bound — a ship that's been pushed deep under needs
        // the spring to lift it back. Engaging force on a free-falling ship from
        // 200m up would be wrong (handled by gravity), but anything below sea
        // level is fair game.
        double hullBottom = bb.minY();
        double maxAmp = 3.0;
        if (hullBottom > SEA_LEVEL + maxAmp + 2.0) {
            wakes$logOnce("hull at Y=" + hullBottom + " — too high above water, skipping");
            return;
        }
        if (!overlapsAnyWater(level, bb)) {
            wakes$logOnce("bb=" + bb.minX() + "," + bb.minY() + "," + bb.minZ() + ".." + bb.maxX() + "," + bb.maxY() + "," + bb.maxZ() + " — no water under footprint");
            return;
        }

        double time = level.getGameTime();
        // Mirror the client shader's amplitude scaling so visuals & physics agree.
        float weather = Math.min(1.5f, level.getRainLevel(0f) + level.getThunderLevel(0f) * 0.5f);
        double swellAmp = 0.9 + weather * 1.4;
        double chopAmp  = 0.05 + weather * 0.45;

        int n = Math.max(2, Math.min(5, WakesConfig.SAMPLE_POINTS_PER_AXIS.get()));
        double dx = (bb.maxX() - bb.minX()) / (n - 1);
        double dz = (bb.maxZ() - bb.minZ()) / (n - 1);
        // Effective surface "area" each sample stands in for — buoyancy scales with it.
        double sampleArea = ((bb.maxX() - bb.minX()) * (bb.maxZ() - bb.minZ())) / (n * n);

        Pose3dc pose = self.logicalPose();
        Vector3d vel = body.getLinearVelocity(new Vector3d());

        QueuedForceGroup group = self.getOrCreateQueuedForceGroup(WAKES_FORCE_GROUP);

        // Sample wave height at the ship's xz centre.
        double centerX = (bb.minX() + bb.maxX()) * 0.5;
        double centerZ = (bb.minZ() + bb.maxZ()) * 0.5;
        double wave = waveHeightServer(centerX, centerZ, time, swellAmp, chopAmp);

        // Force scales with footprint area so big rafts get proportional push.
        double totalArea = (bb.maxX() - bb.minX()) * (bb.maxZ() - bb.minZ());
        double rawForce = wave * WAVE_GAIN * totalArea;
        double force = Math.max(-MAX_FORCE, Math.min(MAX_FORCE, rawForce));

        // Local frame: apply at pose origin (no torque), world-up transformed
        // into local. Local coords were the only mode Sable didn't immediately
        // teleport the body in earlier tests.
        Vector3d worldForce = new Vector3d(0.0, force, 0.0);
        Vector3d localForce = new Vector3d();
        pose.transformNormalInverse(worldForce, localForce);
        Vector3d localPoint = new Vector3d(0.0, 0.0, 0.0);
        // Use record-only — applyAndRecordPointForce was destructive. recordPointForce
        // queues into Sable's pipeline for application at its preferred time, without
        // mid-tick mutation of the body's force accumulator.
        group.recordPointForce(localPoint, localForce);

        var posPos = pose.position();
        wakes$logOnce(String.format(
            "force=%.3f bbY=%.2f..%.2f vy=%.3f wave=%.3f pos=(%.2f,%.2f,%.2f)",
            force, bb.minY(), bb.maxY(), vel.y, wave,
            posPos.x(), posPos.y(), posPos.z()));
    }

    @org.spongepowered.asm.mixin.Unique
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> wakes$lastLogByKey = new java.util.concurrent.ConcurrentHashMap<>();

    @org.spongepowered.asm.mixin.Unique
    private static void wakes$logOnce(String msg) {
        // Key = first 24 chars so distinct callsites have independent throttles.
        String key = msg.length() > 24 ? msg.substring(0, 24) : msg;
        long now = System.currentTimeMillis();
        Long prev = wakes$lastLogByKey.get(key);
        if (prev != null && now - prev < 1000L) return;
        wakes$lastLogByKey.put(key, now);
        Wakes.LOG.info("[ServerSubLevel] {}", msg);
    }

    /**
     * Server-side wave height matching the shader's {@code wakes_swell + wakes_chop}.
     * Algorithm and constants kept in sync by hand — they're small enough that this
     * is faster than refactoring WakesWaveFunction into shared parameterised helpers.
     */
    private static double waveHeightServer(double x, double z, double time,
                                            double swellAmp, double chopAmp) {
        // --- Big swell (matches GLSL wakes_swell) ---
        final int   ITER       = 10;
        final double FREQ      = 4.5, SPEED = 1.6, WEIGHT_INIT = 0.85;
        final double FREQ_MULT = 1.21, SPEED_MULT = 1.07, ITER_INC = 12.0;
        final double DRAG_MULT = 0.052, XZ_SCALE = 0.035, TIME_MULT = 0.045;

        double t = time * TIME_MULT;
        double px = x * XZ_SCALE, pz = z * XZ_SCALE;
        double angle = 0.0, freq = FREQ, speed = SPEED, weight = WEIGHT_INIT;
        double height = 0.0, sumW = 0.0;
        for (int i = 0; i < ITER; i++) {
            double dxv = Math.sin(angle), dzv = Math.cos(angle);
            double phase = (dxv * px + dzv * pz) * freq + t * speed;
            double wave  = Math.exp(Math.sin(phase) - 1.0);
            double dWave = wave * Math.cos(phase);
            px -= dxv * dWave * weight * DRAG_MULT;
            pz -= dzv * dWave * weight * DRAG_MULT;
            height += wave * weight;
            sumW   += weight;
            weight *= 0.82;
            freq   *= FREQ_MULT;
            speed  *= SPEED_MULT;
            angle  += ITER_INC;
        }
        double swell = (height / sumW) * 2.0 - 1.0;

        // --- Chop (matches GLSL wakes_chop) ---
        double tc = time * 0.18;
        double qx = x * 0.22, qz = z * 0.22;
        double chop = 0.5 * (
              Math.sin(qx * 1.4 + qz * 0.7 + tc)
            + Math.sin(qz * 2.1 - qx * 0.5 + tc * 1.3)
        );

        return swell * swellAmp + chop * chopAmp;
    }

    private static boolean overlapsAnyWater(ServerLevel level, BoundingBox3dc bb) {
        int x0 = (int) Math.floor(bb.minX()), x1 = (int) Math.floor(bb.maxX());
        int z0 = (int) Math.floor(bb.minZ()), z1 = (int) Math.floor(bb.maxZ());
        // Sample at the topmost water block (sea_level - 1) and one below, to be
        // robust to slightly varying sea levels and to SubLevels floating high.
        int yTop = (int) Math.floor(SEA_LEVEL) - 1;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = yTop; y >= yTop - 2; y--) {
                    FluidState fs = level.getFluidState(p.set(x, y, z));
                    if (fs.is(Fluids.WATER) || fs.is(Fluids.FLOWING_WATER)) return true;
                }
            }
        }
        return false;
    }
}
