package dev.wakes.mixin.sable;

import dev.wakes.WakesConfig;
import dev.wakes.wave.HullPlane;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds wave-driven heave/pitch/roll to Sable SubLevels at render time. This is the
 * hook that catches everything assembled with Aeronautics' Physics Assembler block —
 * the assembler creates a SubLevel via PhysicsAssemblerBlockEntity#getSubLevel().
 *
 * We mutate the *return* of {@code renderPose(float)} only. Sable's interpolation
 * cache works off the logical pose, not what render reads, so the modification stays
 * frame-local and never feeds back into next-tick prediction.
 *
 * Targeted via class reference so it compiles only when Sable's jar is on the
 * classpath; the mixin plugin gates the apply on Sable being loaded at runtime.
 */
@Mixin(value = ClientSubLevel.class, remap = false)
public abstract class ClientSubLevelMixin {

    @Inject(method = "renderPose(F)Ldev/ryanhcode/sable/companion/math/Pose3dc;",
            at = @At("RETURN"),
            cancellable = true,
            require = 0)
    private void wakes$applyWaveTransform(float partialTick, CallbackInfoReturnable<Pose3dc> cir) {
        if (!WakesConfig.ENABLED.get()) return;
        Pose3dc original = cir.getReturnValue();
        if (original == null) return;

        ClientSubLevel self = (ClientSubLevel) (Object) this;
        Level level = self.getLevel();
        if (level == null) return;

        BoundingBox3dc bb = self.boundingBox();
        if (bb == null) return;
        AABB footprint = new AABB(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ());

        if (WakesConfig.REQUIRE_WATER_UNDERNEATH.get() && !overlapsSurfaceWater(level, footprint)) {
            return;
        }

        double time = level.getGameTime() + partialTick;
        HullPlane plane = HullPlane.sample(level, footprint, time,
                WakesConfig.SAMPLE_POINTS_PER_AXIS.get());
        if (plane.isFlat()) return;

        double heave = plane.heave * WakesConfig.HEAVE_SCALE.get();
        double pitch = plane.pitchRad * WakesConfig.PITCH_SCALE.get();
        double roll  = plane.rollRad  * WakesConfig.ROLL_SCALE.get();

        // Build a fresh Pose3d so we never mutate the value Sable cached upstream.
        Vector3d pos = new Vector3d(original.position());
        pos.y += heave;

        Quaterniond ori = new Quaterniond(original.orientation());
        // Roll about world Z, pitch about world X — applied as a small-angle delta
        // multiplied INTO the existing orientation.
        Quaterniond delta = new Quaterniond()
                .rotateZ(roll)
                .rotateX(pitch);
        ori.mul(delta);

        Pose3d modified = new Pose3d(
                pos,
                ori,
                new Vector3d(original.rotationPoint()),
                new Vector3d(original.scale())
        );
        cir.setReturnValue(modified);
    }

    private static boolean overlapsSurfaceWater(Level level, AABB world) {
        int x0 = (int) Math.floor(world.minX), x1 = (int) Math.floor(world.maxX);
        int z0 = (int) Math.floor(world.minZ), z1 = (int) Math.floor(world.maxZ);
        int y  = (int) Math.floor(world.minY);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                FluidState fs = level.getFluidState(p.set(x, y, z));
                if (fs.is(Fluids.WATER) || fs.is(Fluids.FLOWING_WATER)) return true;
            }
        }
        return false;
    }
}
