package dev.wakes.mixin.sable;

import dev.wakes.Wakes;
import dev.wakes.WakesConfig;
import dev.wakes.wave.WakesDepth;
import dev.wakes.wave.WakesWaveFunction;
import dev.ryanhcode.sable.api.physics.force.ForceGroup;
import dev.ryanhcode.sable.api.physics.force.ForceGroups;
import dev.ryanhcode.sable.api.physics.force.QueuedForceGroup;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.joml.Matrix3dc;
import org.joml.Vector3f;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Real wave-driven buoyancy for Sable SubLevels.
 *
 * Pattern mirrors Aeronautics' propeller-bearing actor (which works without
 * destroying the body). Critical learning from decompiled Sable source:
 *
 * - {@code QueuedForceGroup.applyAndRecordPointForce(point, force)} feeds into
 *   {@code ForceTotal.applyImpulseAtPoint(massTracker, point, force)}, which
 *   computes torque as {@code (point - COM) × force}. So {@code point} MUST be
 *   in the same coordinate frame as {@code MassTracker.getCenterOfMass()}.
 *
 * - That frame is the SubLevel's PLOT frame — actual world BlockPos values
 *   inside the plot region (chunks ~10000+), NOT the original world coords
 *   where the player built the ship and NOT pose-local. Earlier attempts that
 *   used pose-local coords saw {@code position - COM} produce ~160000-block
 *   phantom offsets and runaway torques.
 *
 * - Pure linear heave (no torque) is achieved by passing the COM itself as the
 *   application point: {@code (COM - COM) × force = 0 × force = 0}. Avoids
 *   needing to know any block positions.
 *
 * Tick order in {@link ServerSubLevel}:
 *   1. {@code prePhysicsTickBegin()} resets queued force groups.
 *   2. {@code prePhysicsTick()} runs actors (e.g. propellers calling
 *      {@code applyAndRecordPointForce} on PROPULSION group) + applies
 *      direct lift/drag/buoyancy via {@code handle.applyLinearAndAngularImpulse}.
 *   3. {@code applyQueuedForces()} iterates groups and applies each via
 *      {@code handle.applyForcesAndReset(group.forceTotal)}.
 *
 * We hook {@code prePhysicsTick} TAIL — same window as the propeller actors,
 * so our forces are queued and then applied by step 3.
 */
@Mixin(value = ServerSubLevel.class, remap = false)
public abstract class ServerSubLevelMixin {

    private static final double SEA_LEVEL = 63.0;

    /** Vertical acceleration per unit wave height (blocks/s² per block of wave).
     *  Force is now computed as wave × WAVE_ACCEL × mass, so acceleration =
     *  wave × WAVE_ACCEL is constant regardless of ship size — fixes the L²/L³
     *  scaling bug where bigger ships barely moved because mass grew faster
     *  than the footprint-proportional force. */
    private static final double WAVE_ACCEL = 2.0;

    /** Per-tick cap on wave-induced acceleration magnitude (blocks/s²). Replaces
     *  the per-area impulse cap. ~12 ≈ above 1G but bounded — keeps pathological
     *  wave samples from launching the ship while letting deep-ocean storms
     *  actually toss the ship around. */
    private static final double MAX_WAVE_ACCEL = 12.0;

    /** Horizontal acceleration per unit wave slope (blocks/s² per (∂h/∂x)).
     *  Same mass-scaled formulation as WAVE_ACCEL — drift force scales with
     *  ship mass instead of footprint area. */
    private static final double DRAG_ACCEL = 8.0;

    /** Sample epsilon for the wave-gradient finite difference (blocks). Smaller =
     *  sharper response to chop, larger = smoother response to long swells. */
    private static final double GRADIENT_EPS = 0.6;

    /** Hull-bottom sampling density. One sample every ~2.5 blocks gives Nyquist
     *  coverage of WakesWaveFunction's mid-band swell components (wavelengths
     *  down to ~5 blocks). Fixed n×n grids alias hard on long hulls. */
    private static final double BLOCKS_PER_SAMPLE   = 2.5;
    /** Per-axis sample-count cap. 24 → up to 576 samples on a giant hull;
     *  bounds trig cost on cruise-liner-sized ships. */
    private static final int    MAX_SAMPLES_PER_AXIS = 24;
    /** Per-axis sample-count floor. Keeps the existing feel for sub-10-block
     *  hulls (matches the old default n=3). */
    private static final int    MIN_SAMPLES_PER_AXIS = 3;

    /** Angular-damping time constant (seconds). Damping torque is -I·ω/τ, so
     *  angular velocity decays exponentially with this time constant regardless
     *  of ship inertia — large hulls finally settle on the same timescale as
     *  small ones. 0.25 s ≈ matches the old constant-coefficient ANG_DAMPING=4.0
     *  for small ships. */
    private static final double ANG_DAMPING_TAU = 0.25;

    // Reuse Sable's registered LEVITATION group rather than creating our own.
    // A custom in-memory ForceGroup record has no registry ID, so when the
    // simulated:diagram_data network packet tries to encode it for the F3 force
    // visualizer, it crashes with NPE in ByteBufCodecs. Sable's LEVITATION group
    // is the right semantic match anyway — wave buoyancy IS lift.
    private static ForceGroup wakesForceGroup() {
        return ForceGroups.LEVITATION.get();
    }

    @Inject(
        method = "prePhysicsTick(Ldev/ryanhcode/sable/sublevel/system/SubLevelPhysicsSystem;Ldev/ryanhcode/sable/api/physics/handle/RigidBodyHandle;D)V",
        at = @At("TAIL"),
        require = 1
    )
    private void wakes$applyWaveBuoyancy(SubLevelPhysicsSystem system, RigidBodyHandle body, double dt, CallbackInfo ci) {
        if (!WakesConfig.ENABLED.get()) return;
        if (body == null || !body.isValid()) return;

        ServerSubLevel self = (ServerSubLevel) (Object) this;
        ServerLevel level = self.getLevel();
        if (level == null) return;

        BoundingBox3dc bb = self.boundingBox();
        if (bb == null) return;

        // Skip ships that aren't near the water surface (no upper bound on how
        // submerged because we want push-up for sunk ships; only skip free-flying).
        if (bb.minY() > SEA_LEVEL + 5.0) return;
        if (!overlapsAnyWater(level, bb)) return;

        MassData massData = self.getMassTracker();
        if (massData == null) return;
        Vector3dc com = massData.getCenterOfMass();
        if (com == null) return;

        // Per-sample impulses across an N×N grid of hull-bottom points. Wave
        // varies in xz, so different samples produce different impulses → natural
        // torque around the COM gives pitch and roll.
        Pose3dc pose = self.logicalPose();
        // Sample wave at sub-tick resolution to match the visible water shader.
        // Sable subdivides each MC tick into physics substeps; partialPhysicsTick
        // gives the substep's position in [0, 1].
        double time = level.getGameTime() + system.getPartialPhysicsTick();
        float partial = (float) system.getPartialPhysicsTick();
        float weather = Math.min(1.5f, level.getRainLevel(partial) + level.getThunderLevel(partial) * 0.5f);
        // Depth-aware amplitude — calmer in shallows, full force in deep ocean.
        // Sample at the ship's centre so we get one factor for the whole hull
        // (cheap; per-sample depth lookup would be 9× the cost).
        double cx = (bb.minX() + bb.maxX()) * 0.5;
        double cz = (bb.minZ() + bb.maxZ()) * 0.5;
        float depthFactor = WakesDepth.factorAt(level, cx, cz);

        double extentX = bb.maxX() - bb.minX();
        double extentZ = bb.maxZ() - bb.minZ();
        int nx = Math.max(MIN_SAMPLES_PER_AXIS, Math.min(MAX_SAMPLES_PER_AXIS,
                (int) Math.ceil(extentX / BLOCKS_PER_SAMPLE) + 1));
        int nz = Math.max(MIN_SAMPLES_PER_AXIS, Math.min(MAX_SAMPLES_PER_AXIS,
                (int) Math.ceil(extentZ / BLOCKS_PER_SAMPLE) + 1));
        double dx = extentX / (nx - 1);
        double dz = extentZ / (nz - 1);
        // Per-cell mass share: each sample drives an even fraction of the hull
        // mass. Summed over all cells = total mass, so total impulse = wave_avg
        // × WAVE_ACCEL × mass × dt → ship-wide acceleration = wave × WAVE_ACCEL,
        // independent of ship size. Spatial wave variation across cells produces
        // torque around the COM as before.
        double mass = massData.getMass();
        double cellMass = mass / (nx * nz);
        double cellCap  = MAX_WAVE_ACCEL * cellMass * dt;
        double hullY = bb.minY();   // apply at hull bottom

        QueuedForceGroup group = self.getOrCreateQueuedForceGroup(wakesForceGroup());

        Vector3d worldPoint = new Vector3d();
        Vector3d plotPoint  = new Vector3d();
        Vector3d worldUp    = new Vector3d();
        Vector3d localImpulse = new Vector3d();
        int applied = 0;
        double maxAbsImpulse = 0;
        double totalImpulse = 0;

        boolean debug = false;

        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < nz; j++) {
                double sx = bb.minX() + i * dx;
                double sz = bb.minZ() + j * dz;
                double wave = WakesWaveFunction.waveHeight(sx, sz, time, weather, depthFactor);

                double rawImpulse = wave * WAVE_ACCEL * cellMass * dt;
                double impulse = clamp(rawImpulse, cellCap);

                // Wave gradient via 2-tap forward differences (reuses the center
                // sample). Direction (∂h/∂x, ∂h/∂z) points UP the slope — the
                // direction a wave pushes floating objects. Magnitude = steepness,
                // so steep crests drift ships harder than gentle swells. Half-cell
                // phase lead vs central differences is visually invisible and saves
                // 2 wave-height calls per sample.
                double e = GRADIENT_EPS;
                double hXp = WakesWaveFunction.waveHeight(sx + e, sz, time, weather, depthFactor);
                double hZp = WakesWaveFunction.waveHeight(sx, sz + e, time, weather, depthFactor);
                double gradX = (hXp - wave) / e;
                double gradZ = (hZp - wave) / e;
                double dragX = clamp(gradX * DRAG_ACCEL * cellMass * dt, cellCap);
                double dragZ = clamp(gradZ * DRAG_ACCEL * cellMass * dt, cellCap);

                if (Math.abs(impulse) < 1e-5 && Math.abs(dragX) < 1e-5 && Math.abs(dragZ) < 1e-5) continue;

                // World-space hull-bottom sample point → plot-frame point.
                worldPoint.set(sx, hullY, sz);
                pose.transformPositionInverse(worldPoint, plotPoint);

                // World-space combined force: vertical heave + horizontal drift.
                // Then convert to body/plot frame so axes survive ship rotation.
                worldUp.set(dragX, impulse, dragZ);
                pose.transformNormalInverse(worldUp, localImpulse);

                group.applyAndRecordPointForce(plotPoint, localImpulse);
                applied++;
                totalImpulse += impulse;
                if (Math.abs(impulse) > maxAbsImpulse) maxAbsImpulse = Math.abs(impulse);

                if (debug) {
                    wakes$spawnDebugParticle(level, sx, hullY, sz, impulse);
                }
            }
        }

        Vector3d vel = body.getLinearVelocity(new Vector3d());
        Vector3d angVel = body.getAngularVelocity(new Vector3d());

        // Angular damping: counter-torque -I·ω/τ in world frame, transformed to
        // body/plot frame for ForceTotal. Using the actual inertia tensor means
        // the angular-velocity decay rate is τ-seconds *regardless of ship size*
        // — fixes large hulls rocking forever because their inertia dwarfed the
        // old constant-coefficient damping torque.
        if (ANG_DAMPING_TAU > 0) {
            Matrix3dc inertia = massData.getInertiaTensor();
            Vector3d angImpulseWorld = inertia.transform(angVel, new Vector3d())
                    .mul(-dt / ANG_DAMPING_TAU);
            Vector3d angImpulseLocal = new Vector3d();
            pose.transformNormalInverse(angImpulseWorld, angImpulseLocal);
            group.getForceTotal().applyAngularImpulse(angImpulseLocal);
        }

        // Magenta dust at the COM in world coords to verify our reference point.
        if (debug) {
            // pose.position() is COM in world coords (from Pose3dc.transformPosition definition).
            level.sendParticles(
                new DustParticleOptions(new Vector3f(1.0f, 0.0f, 1.0f), 1.5f),
                pose.position().x(), pose.position().y(), pose.position().z(),
                1, 0, 0, 0, 0
            );
        }

        // Logging silenced — re-enable wakes$logOnce(...) here if you need to debug.
    }

    /** Visualise per-sample force as a coloured dust particle: green for upward
     *  push (crest), red for downward push (trough). Particle scale grows with
     *  impulse magnitude so you can see hot-spots at a glance. */
    @org.spongepowered.asm.mixin.Unique
    private static void wakes$spawnDebugParticle(ServerLevel level, double x, double y, double z, double impulse) {
        Vector3f color = impulse > 0
            ? new Vector3f(0.2f, 1.0f, 0.2f)    // up = green
            : new Vector3f(1.0f, 0.2f, 0.2f);   // down = red
        float scale = Math.min(2.0f, (float) (Math.abs(impulse) * 8.0));
        level.sendParticles(new DustParticleOptions(color, Math.max(0.4f, scale)),
            x, y, z, 1, 0, 0, 0, 0);

        // Bubble trail in the direction of the impulse — climbs for up, sinks for down.
        double dyShift = impulse > 0 ? 0.3 : -0.3;
        level.sendParticles(ParticleTypes.BUBBLE,
            x, y + dyShift, z, 1, 0, 0, 0, 0);
    }

    private static double clamp(double v, double cap) {
        return Math.max(-cap, Math.min(cap, v));
    }

    // Wave-height calculation moved to dev.wakes.wave.WakesWaveFunction.
    // Same formula is shared with the GLSL in WakesWaveGLSL via WakesShaderInjection
    // (Sodium) and IrisInjection (shader packs).

    private static boolean overlapsAnyWater(ServerLevel level, BoundingBox3dc bb) {
        int x0 = (int) Math.floor(bb.minX()), x1 = (int) Math.floor(bb.maxX());
        int z0 = (int) Math.floor(bb.minZ()), z1 = (int) Math.floor(bb.maxZ());
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

    @org.spongepowered.asm.mixin.Unique
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> wakes$lastLogByKey = new java.util.concurrent.ConcurrentHashMap<>();

    @org.spongepowered.asm.mixin.Unique
    private static void wakes$logOnce(String msg) {
        String key = msg.length() > 24 ? msg.substring(0, 24) : msg;
        long now = System.currentTimeMillis();
        Long prev = wakes$lastLogByKey.get(key);
        if (prev != null && now - prev < 1000L) return;
        wakes$lastLogByKey.put(key, now);
        Wakes.LOG.info("[ServerSubLevel] {}", msg);
    }
}
