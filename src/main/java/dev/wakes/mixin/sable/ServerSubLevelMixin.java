package dev.wakes.mixin.sable;

import dev.wakes.Wakes;
import dev.wakes.WakesConfig;
import dev.ryanhcode.sable.api.physics.force.ForceGroup;
import dev.ryanhcode.sable.api.physics.force.QueuedForceGroup;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
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

    /** Per-second buoyancy force gain on wave height, before multiplying by dt
     *  (Sable's force API takes IMPULSES = force × dt, like the propeller does). */
    private static final double WAVE_GAIN = 1.5;

    /** Hard cap on per-tick impulse magnitude (post-dt-multiplication). */
    private static final double MAX_IMPULSE = 0.4;

    private static final ForceGroup WAKES_FORCE_GROUP = new ForceGroup(
        Component.literal("Wakes — wave buoyancy"),
        Component.literal("Wave-driven buoyancy delta on top of Sable's flat-water float"),
        0x3399ff,
        true
    );

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

        // CRITICAL: COM and force-point must be in the SAME frame. We apply at
        // exactly the COM → zero offset → zero torque arm → pure heave.
        MassData massData = self.getMassTracker();
        if (massData == null) return;
        Vector3dc com = massData.getCenterOfMass();
        if (com == null) return;

        // Sample wave height at the ship's REAL world center (not plot coords).
        // The wave field is parametric in real-world (x, z, t).
        double centerX = (bb.minX() + bb.maxX()) * 0.5;
        double centerZ = (bb.minZ() + bb.maxZ()) * 0.5;
        double time = level.getGameTime();
        float weather = Math.min(1.5f, level.getRainLevel(0f) + level.getThunderLevel(0f) * 0.5f);
        double swellAmp = 0.9 + weather * 1.4;
        double chopAmp  = 0.05 + weather * 0.45;
        double wave = waveHeightServer(centerX, centerZ, time, swellAmp, chopAmp);

        double area = (bb.maxX() - bb.minX()) * (bb.maxZ() - bb.minZ());
        // CRITICAL: multiply by dt (timestep). Sable's API takes impulses, not
        // raw forces. Mirrors `prop.getScaledThrust() * timeStep` in the propeller.
        double rawImpulse = wave * WAVE_GAIN * area * dt;
        double impulse = Math.max(-MAX_IMPULSE, Math.min(MAX_IMPULSE, rawImpulse));

        // Apply at COM (zero offset → no torque). World-up impulse vector.
        QueuedForceGroup group = self.getOrCreateQueuedForceGroup(WAKES_FORCE_GROUP);
        Vector3d point = new Vector3d(com);
        Vector3d impulseVec = new Vector3d(0.0, impulse, 0.0);
        group.applyAndRecordPointForce(point, impulseVec);

        Vector3d vel = body.getLinearVelocity(new Vector3d());
        wakes$logOnce(String.format(
            "wave=%.3f impulse=%.4f area=%.2f bbY=%.2f..%.2f vy=%.3f COM=(%.1f,%.1f,%.1f)",
            wave, impulse, area, bb.minY(), bb.maxY(), vel.y,
            com.x(), com.y(), com.z()));
    }

    /** Server-side wave height — must stay numerically aligned with the GLSL in
     *  WakesShaderInjection so visible waves and physics waves agree. */
    private static double waveHeightServer(double x, double z, double time,
                                            double swellAmp, double chopAmp) {
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
