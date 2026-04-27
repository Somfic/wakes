package sh.somfic.wherestherum.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import sh.somfic.wherestherum.WakesConfig;
import sh.somfic.wherestherum.wave.HullPlane;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds wave-driven heave/pitch/roll to propeller-bearing contraptions at render time
 * so they visually ride the wave field instead of sitting on a flat plane.
 *
 * Targeted by string so this file compiles without Aeronautics on the classpath; we
 * still need Create on the classpath for {@link AbstractContraptionEntity} and
 * {@link Contraption}.
 */
@Mixin(targets = "dev.eriksonn.aeronautics.content.blocks.propeller.bearing.contraption.PropellerBearingContraptionEntity")
public abstract class PropellerBearingContraptionEntityMixin {

    @Inject(
        method = "applyLocalTransforms",
        at = @At("HEAD"),
        require = 0   // tolerate Aeronautics renaming the method between versions
    )
    private void wakes$applyWaveTransform(PoseStack stack, float partialTick, CallbackInfo ci) {
        if (!WakesConfig.ENABLED.get()) return;

        Entity self = (Entity) (Object) this;
        AbstractContraptionEntity ace = (AbstractContraptionEntity) self;

        Contraption contraption = ace.getContraption();
        if (contraption == null) return;

        Level level = self.level();
        Vec3 pos = self.getPosition(partialTick);
        AABB local = contraption.bounds;          // contraption-local block AABB
        if (local == null) return;
        AABB world = local.move(pos);

        if (WakesConfig.REQUIRE_WATER_UNDERNEATH.get() && !overlapsSurfaceWater(level, world)) {
            return;
        }

        double time = level.getGameTime() + partialTick;
        HullPlane plane = HullPlane.sample(level, world, time,
                WakesConfig.SAMPLE_POINTS_PER_AXIS.get());
        if (plane.isFlat()) return;

        double heave = plane.heave * WakesConfig.HEAVE_SCALE.get();
        float pitch = (float) (plane.pitchRad * WakesConfig.PITCH_SCALE.get());
        float roll  = (float) (plane.rollRad  * WakesConfig.ROLL_SCALE.get());

        // PoseStack is in contraption-local space here. Pivot at hull centre so
        // tilt doesn't translate the contraption sideways.
        float cx = (float) ((local.minX + local.maxX) * 0.5);
        float cz = (float) ((local.minZ + local.maxZ) * 0.5);
        stack.translate(cx, (float) heave, cz);
        stack.mulPose(new Quaternionf(new AxisAngle4f(roll,  new Vector3f(0, 0, 1))));
        stack.mulPose(new Quaternionf(new AxisAngle4f(pitch, new Vector3f(1, 0, 0))));
        stack.translate(-cx, 0, -cz);
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
