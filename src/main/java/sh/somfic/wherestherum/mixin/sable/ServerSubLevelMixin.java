package sh.somfic.wherestherum.mixin.sable;

import sh.somfic.wherestherum.Wakes;
import sh.somfic.wherestherum.WakesConfig;
import sh.somfic.wherestherum.buoyancy.FloatTracker;
import sh.somfic.wherestherum.wave.WakesDepth;
import sh.somfic.wherestherum.wave.WakesWaveFunction;
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
     *  Pure impulse model: F = wave × WAVE_ACCEL × mass. Big ships sit at
     *  ω_eq ∝ WAVE_ACCEL × τ_damp / I, so trimming this is the most direct
     *  knob for "still rotating too much". */
    private static final double WAVE_ACCEL = 1.5;

    /** Per-tick cap on wave-induced acceleration magnitude (blocks/s²). */
    private static final double MAX_WAVE_ACCEL = 4.0;

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

    /** Angular-damping time constant (seconds) for the inertia-proportional
     *  component. Damping torque from this part is -I·ω/τ — works well for
     *  large hulls (decay rate independent of size), but tiny SubLevels
     *  (1-block logs, mass<2) have I close to 0 so this term vanishes. The
     *  constant-coefficient term below covers them. */
    private static final double ANG_DAMPING_TAU = 1.0;

    /** Constant-coefficient angular damping (1/s, applied as -k·ω, no inertia
     *  scaling). This is what actually damps small-mass SubLevels; for big
     *  ships it's swamped by the inertia-proportional term. The diagnostic
     *  logs showed mass<2 ships sitting at ω≈1 rad/s with the inertia-only
     *  damping; this term should pull them down to ω≈0.1 quickly. */
    private static final double ANG_DAMPING_CONST = 0.5;

    /** Righting-moment stiffness (1/s²). Sable's flat-water buoyancy is COM-
     *  centric and provides no metacentric restoring moment, so SubLevels can
     *  settle at any orientation — including 45° on their side. We assume the
     *  ship was built with its body-Y axis "up" and apply a gentle torque to
     *  restore that. K=0.25 + critical damping (τ=1) → upright in ~2 seconds
     *  from a 45° tilt. Set to 0 to disable. */
    private static final double UPRIGHT_RESTORE_K = 0.25;

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
        // mass. Force on each cell is a spring toward (SEA_LEVEL + wave_height)
        // at that cell's xz, so each piece of hull tries to ride its local wave
        // surface like a buoy. Spatial variation in wave height = different
        // targets across cells = pitch/roll torque around the COM.
        double mass = massData.getMass();
        double cellMass = mass / (nx * nz);
        double cellCap  = MAX_WAVE_ACCEL * cellMass * dt;
        // Apply forces at the body's COM Y in world frame, NOT at bb.minY().
        // Using bb.minY() means the application point swings around the body
        // as it rotates (the AABB's lowest corner moves with orientation), and
        // each tick the world-frame force creates a body-frame torque from the
        // shifted lever arm — that's the positive-feedback loop that was making
        // logs spin faster as they rolled. COM Y doesn't shift with rotation,
        // so torque comes only from the xz offset of each cell relative to COM.
        double comWorldY = pose.position().y();

        QueuedForceGroup group = self.getOrCreateQueuedForceGroup(wakesForceGroup());

        Vector3d worldPoint = new Vector3d();
        Vector3d plotPoint  = new Vector3d();
        Vector3d worldUp    = new Vector3d();
        Vector3d localImpulse = new Vector3d();
        int applied = 0;
        double maxAbsImpulse = 0;
        double totalImpulse = 0;
        double totalDragMag = 0;
        // Net torque accumulator in world frame: Σ (cellPos − COM) × F.
        // |τ| / cellsApplied tells us whether forces are net-rotating or net-zero.
        Vector3d totalTorque = new Vector3d();
        Vector3d comWorld = new Vector3d(pose.position().x(), pose.position().y(), pose.position().z());

        boolean debug    = WakesConfig.DEBUG_PARTICLES.get();
        boolean debugLog = WakesConfig.DEBUG_LOG.get();

        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < nz; j++) {
                double sx = bb.minX() + i * dx;
                double sz = bb.minZ() + j * dz;
                double wave = WakesWaveFunction.waveHeight(sx, sz, time, weather, depthFactor);

                // Pure impulse: F = wave × WAVE_ACCEL × mass. Sable's own
                // flat-water buoyancy provides the spring back to sea level;
                // we just add the wave-driven oscillation on top.
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

                // World-space sample point at cell xz, COM Y → plot-frame point.
                // See comWorldY note above for why we don't use bb.minY here.
                worldPoint.set(sx, comWorldY, sz);
                pose.transformPositionInverse(worldPoint, plotPoint);

                // World-space combined force: vertical heave + horizontal drift.
                // Then convert to body/plot frame so axes survive ship rotation.
                worldUp.set(dragX, impulse, dragZ);
                pose.transformNormalInverse(worldUp, localImpulse);

                group.applyAndRecordPointForce(plotPoint, localImpulse);
                applied++;
                totalImpulse += impulse;
                totalDragMag += Math.abs(dragX) + Math.abs(dragZ);
                if (Math.abs(impulse) > maxAbsImpulse) maxAbsImpulse = Math.abs(impulse);

                if (debugLog) {
                    // Accumulate world-frame torque about COM: r × F.
                    double rx = sx - comWorld.x;
                    double ry = comWorldY - comWorld.y; // = 0 by construction; left explicit
                    double rz = sz - comWorld.z;
                    double fx = dragX, fy = impulse, fz = dragZ;
                    totalTorque.x += ry * fz - rz * fy;
                    totalTorque.y += rz * fx - rx * fz;
                    totalTorque.z += rx * fy - ry * fx;
                }

                if (debug) {
                    wakes$spawnDebugParticle(level, sx, comWorldY, sz, impulse);
                }
            }
        }

        wakes$applyFloatBuoyancy(self, level, body, group, pose, com, dt,
                time, weather, depthFactor);

        Vector3d angVel = body.getAngularVelocity(new Vector3d());

        // Angular damping: combined inertia-proportional + constant-coefficient
        // term. Big ships (high I) are dominated by -I·ω/τ → decay rate ≈ 1/τ
        // regardless of size. Tiny ships (I ≈ 0) need the constant -k·ω term
        // because the inertia term vanishes. Both go into a single world-frame
        // angular impulse, transformed to body frame for ForceTotal.
        Matrix3dc inertia = massData.getInertiaTensor();
        if (ANG_DAMPING_TAU > 0 || ANG_DAMPING_CONST > 0 || UPRIGHT_RESTORE_K > 0) {
            Vector3d angImpulseWorld = new Vector3d();
            if (ANG_DAMPING_TAU > 0) {
                angImpulseWorld.add(inertia.transform(angVel, new Vector3d())
                        .mul(-dt / ANG_DAMPING_TAU));
            }
            if (ANG_DAMPING_CONST > 0) {
                angImpulseWorld.add(new Vector3d(angVel).mul(-ANG_DAMPING_CONST * dt));
            }
            // Righting torque: rotate body so its body-Y axis aligns with world +Y.
            // bodyUpInWorld = pose · (0,1,0). Cross with world up (0,1,0) gives an
            // axis perpendicular to both, magnitude sin(tilt). Apply scaled by mass-
            // weighted inertia so the torque produces the same angular acceleration
            // regardless of ship size (matches the inertia-aware damping above).
            if (UPRIGHT_RESTORE_K > 0) {
                Vector3d bodyUp = pose.transformNormal(new Vector3d(0, 1, 0), new Vector3d());
                // cross = bodyUp × (0,1,0) = (-bodyUp.z, 0, bodyUp.x).
                Vector3d cross = new Vector3d(-bodyUp.z, 0.0, bodyUp.x);
                Vector3d rightingImpulse = inertia.transform(cross, new Vector3d())
                        .mul(UPRIGHT_RESTORE_K * dt);
                angImpulseWorld.add(rightingImpulse);
            }
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
            // Cyan trail along the angular velocity axis (length ∝ |ω|): shows
            // visually which axis the ship is spinning around right now.
            wakes$spawnAxisTrail(level, comWorld, angVel, 4.0, new Vector3f(0.2f, 0.9f, 1.0f));
            // Yellow trail along the net wave-driven torque axis: if it stays
            // aligned with the cyan trail, our forces are *pumping* rotation;
            // if it opposes, they're damping it.
            if (debugLog) {
                wakes$spawnAxisTrail(level, comWorld, totalTorque, 1.0, new Vector3f(1.0f, 1.0f, 0.2f));
            }
        }

        if (debugLog) {
            double angVelMag    = angVel.length();
            double torqueMag    = totalTorque.length();
            // Sign relative to angVel: positive means torque is along ω
            // (pumping rotation), negative means opposing (damping).
            double torqueDotOmega = angVelMag > 1e-6
                    ? totalTorque.dot(angVel) / angVelMag
                    : 0.0;
            String key = "diag@" + System.identityHashCode(self);
            wakes$logOnce(key, String.format(
                "ship %s mass=%.1f I.diag=%.1f,%.1f,%.1f comY=%.2f vy=%.3f " +
                "ω=(%.3f,%.3f,%.3f)|%.3f| Σimp_y=%.4f Σ|drag|=%.4f " +
                "|τ|=%.4f τ·ω̂=%.4f cells=%d maxImp=%.4f",
                key,
                mass,
                inertia.m00(), inertia.m11(), inertia.m22(),
                comWorld.y, body.getLinearVelocity(new Vector3d()).y,
                angVel.x, angVel.y, angVel.z, angVelMag,
                totalImpulse, totalDragMag,
                torqueMag, torqueDotOmega,
                applied, maxAbsImpulse
            ));
        }
    }

    /**
     * Depth-proportional buoyancy for blocks tagged {@code #wherestherum:floats}
     * (vanilla sponge), plus the soak-through simulation that sinks a breached hull.
     *
     * Deliberately unlike Aeronautics levitite in two ways:
     *
     * 1. Lift is per-BLOCK, not per-unit-mass. A heavier ship does not get more
     *    lift for free — you float it by packing in more sponge. That is what
     *    makes sponge volume a real design constraint.
     *
     * 2. There is NO friction/drag term. Sable's floating_material friction fields
     *    feed a frictionTorque that flattens a vessel's rocking; we add nothing of
     *    the sort. The only damping here is explicitly vertical, applied at the COM
     *    (zero lever arm ⇒ zero torque), so heave settles while roll and pitch stay
     *    completely free.
     *
     * Because force grows with submersion depth and stops at the surface, the hull
     * finds its own waterline: sink it and lift rises until it balances weight. A
     * tilted hull has deeper blocks on the low side, which lift harder — a genuine
     * metacentric righting moment that emerges from the geometry rather than being
     * faked with a restoring torque.
     */
    @org.spongepowered.asm.mixin.Unique
    private void wakes$applyFloatBuoyancy(ServerSubLevel self, ServerLevel level,
                                          RigidBodyHandle body, QueuedForceGroup group,
                                          Pose3dc pose, Vector3dc com, double dt,
                                          double time, float weather, float depthFactor) {
        if (!WakesConfig.FLOAT_ENABLED.get()) return;

        var plot = self.getPlot();
        if (plot == null) return;

        FloatTracker.State state = FloatTracker.get(self.getRuntimeId());
        long gameTime = level.getGameTime();
        FloatTracker.maybeRescan(state, level, gameTime, plot);
        if (state.isEmpty()) return;

        double liftPerBlock = WakesConfig.FLOAT_LIFT_PER_BLOCK.get();
        double maxDepth = WakesConfig.FLOAT_MAX_DEPTH.get();

        // Submersion is measured against the live wave surface, not flat sea level,
        // so blocks duck under and clear the water as swells roll past.
        java.util.function.ToDoubleFunction<BlockPos> depthOf = pos -> {
            Vector3d w = new Vector3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            pose.transformPosition(w);
            double surface = SEA_LEVEL + WakesWaveFunction.waveHeight(w.x, w.z, time, weather, depthFactor);
            return surface - w.y;
        };

        Vector3d plotPoint = new Vector3d();
        Vector3d worldForce = new Vector3d();
        Vector3d localForce = new Vector3d();
        int submerged = 0;
        double springK = 0;

        for (BlockPos pos : state.floats()) {
            double depth = depthOf.applyAsDouble(pos);
            if (depth <= 0) continue;   // riding above the surface: no lift
            submerged++;

            double impulse = liftPerBlock * Math.min(depth, maxDepth) * dt;

            // Accumulate the hull's vertical spring stiffness k = dF/dy for the
            // damping calculation below. Only blocks still on the linear part of
            // the curve contribute: once a block saturates at maxDepth its lift
            // stops changing with depth, so it provides force but no restoring
            // stiffness. Getting this right is what lets damping track the real
            // spring rather than a guess.
            if (depth < maxDepth) springK += liftPerBlock;

            // The block's own plot position IS the correct application point —
            // SubLevel blocks live in the parent level's plot region, the same
            // frame the mass tracker reports its centre of mass in. Using the
            // real position (not the COM) is the whole point: off-centre lift
            // is what produces the righting moment.
            plotPoint.set(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            worldForce.set(0, impulse, 0);
            pose.transformNormalInverse(worldForce, localForce);
            group.applyAndRecordPointForce(plotPoint, localForce);
        }

        // Vertical damping at the COM, derived from the spring we just measured
        // rather than being a fixed constant.
        //
        // The hull is a spring-mass system: stiffness k = springK (force per block
        // of displacement), mass m. Its damping ratio is ζ = c / (2·√(k·m)). The
        // previous version used a fixed coefficient in 1/s, which meant ζ silently
        // changed with BOTH ship mass and coral count — pack in more coral, stiffen
        // the spring, and a constant 0.5 becomes badly underdamped. That is the
        // oscillation: not a wrong number, a quantity that cannot be a constant.
        //
        // So solve for the coefficient that hits the requested ratio:
        //     c = ζ · 2·√(k·m)
        // ζ=1 is critical damping (settles fast, no overshoot); ~0.7 keeps a little
        // life in the heave; 0 disables. This is applied at the COM, so the lever
        // arm is zero and it still cannot produce any torque — roll and pitch stay
        // as free as before.
        double zeta = WakesConfig.FLOAT_DAMPING_RATIO.get();
        if (submerged > 0 && zeta > 0 && springK > 0) {
            double vy = body.getLinearVelocity(new Vector3d()).y;
            if (Math.abs(vy) > 1e-4) {
                double mass = self.getMassTracker() != null ? self.getMassTracker().getMass() : 0;
                if (mass > 0) {
                    double c = zeta * 2.0 * Math.sqrt(springK * mass);
                    double impulse = -c * vy * dt;
                    // Never remove more vertical momentum than the hull actually
                    // has: an over-large damping impulse would flip the velocity
                    // and become a driver instead of a damper, which is its own
                    // oscillation. Clamping makes this unconditionally stable
                    // regardless of substep size or how stiff the spring gets.
                    double maxImpulse = Math.abs(vy) * mass;
                    if (Math.abs(impulse) > maxImpulse) impulse = Math.copySign(maxImpulse, impulse);

                    worldForce.set(0, impulse, 0);
                    pose.transformNormalInverse(worldForce, localForce);
                    plotPoint.set(com.x(), com.y(), com.z());   // zero lever arm ⇒ no torque
                    group.applyAndRecordPointForce(plotPoint, localForce);
                }
            }
        }


        if (WakesConfig.DEBUG_LOG.get()) {
            wakes$logOnce("float@" + System.identityHashCode(self), String.format(
                "float ship floats=%d submerged=%d liftPerBlock=%.1f maxDepth=%.1f",
                state.floats().size(), submerged, liftPerBlock, maxDepth));
        }
    }

    /** Spawn a small line of dust particles starting at {@code origin} along
     *  {@code axis}, length {@code lengthScale × |axis|}. Used to visualise
     *  angular-velocity / net-torque vectors in-world. */
    @org.spongepowered.asm.mixin.Unique
    private static void wakes$spawnAxisTrail(ServerLevel level, Vector3dc origin,
                                              Vector3dc axis, double lengthScale,
                                              Vector3f color) {
        double mag = axis.length();
        if (mag < 1e-4) return;
        double sx = axis.x() / mag, sy = axis.y() / mag, sz = axis.z() / mag;
        double len = Math.min(6.0, mag * lengthScale);
        int steps = (int) Math.ceil(len * 2.0);
        for (int k = 1; k <= steps; k++) {
            double t = k * len / steps;
            level.sendParticles(
                new DustParticleOptions(color, 0.6f),
                origin.x() + sx * t, origin.y() + sy * t, origin.z() + sz * t,
                1, 0, 0, 0, 0
            );
        }
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

    // Wave-height calculation moved to sh.somfic.wherestherum.wave.WakesWaveFunction.
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
    private static void wakes$logOnce(String key, String msg) {
        long now = System.currentTimeMillis();
        Long prev = wakes$lastLogByKey.get(key);
        if (prev != null && now - prev < 1000L) return;
        wakes$lastLogByKey.put(key, now);
        Wakes.LOG.info("[ServerSubLevel] {}", msg);
    }
}
