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
 * Visual wave bobbing for Sable SubLevels — modifies the per-frame render pose
 * only, never the physics body. Sable's flat-water buoyancy continues to keep the
 * ship floating; we just rotate/translate what gets drawn so it looks like the
 * ship is riding the visible wave surface.
 *
 * Pragmatic compromise: the alternative (real wave-driven force on the rigid body)
 * required hooking into Sable's force pipeline, which fights its internal physics
 * bookkeeping in ways that consistently destroyed the body. Visual fakery is
 * what every game does for ocean rocking — the player can't tell from collision.
 */
@Mixin(value = ClientSubLevel.class, remap = false)
public abstract class ClientSubLevelMixin {

    @Inject(method = "renderPose(F)Ldev/ryanhcode/sable/companion/math/Pose3dc;",
            at = @At("RETURN"),
            cancellable = true,
            require = 0)
    private void wakes$applyWaveTransform(float partialTick, CallbackInfoReturnable<Pose3dc> cir) {
        // Disabled: real physics now moves the SubLevel via ServerSubLevelMixin,
        // so the rendered pose is already at the correct wave-displaced position.
        // Adding a visual transform on top would double-bob.
        if (true) return;
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

        Vector3d pos = new Vector3d(original.position());
        pos.y += heave;

        Quaterniond ori = new Quaterniond(original.orientation());
        Quaterniond delta = new Quaterniond().rotateZ(roll).rotateX(pitch);
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
        // Scan the topmost water block (sea_level - 1).
        int yTop = 62;
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
