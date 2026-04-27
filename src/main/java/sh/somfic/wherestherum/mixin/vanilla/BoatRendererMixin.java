package sh.somfic.wherestherum.mixin.vanilla;

import com.mojang.blaze3d.vertex.PoseStack;
import sh.somfic.wherestherum.WakesConfig;
import sh.somfic.wherestherum.wave.WakesDepth;
import sh.somfic.wherestherum.wave.WakesWaveFunction;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.BoatRenderer;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.Level;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Visual wave bobbing for vanilla Minecraft boats. Doesn't touch physics —
 * just translates the render position by the local wave height + tilts by the
 * wave gradient. The boat still floats at flat water from the engine's point
 * of view (collisions, fishing, mounting all behave normally), but you can
 * see it bobbing with the waves.
 *
 * Real wave-driven boat physics would mean overriding Boat.floatBoat() to
 * sample the wave-displaced surface — much higher risk of breaking vanilla
 * behaviour. Visual-only is the right scope here.
 */
@Mixin(BoatRenderer.class)
public abstract class BoatRendererMixin {

    @Inject(
        method = "render(Lnet/minecraft/world/entity/vehicle/Boat;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At("HEAD"),
        require = 0
    )
    private void wakes$bobBoat(Boat boat, float yaw, float partialTick, PoseStack stack,
                                MultiBufferSource bufferSource, int packedLight, CallbackInfo ci) {
        if (!WakesConfig.ENABLED.get()) return;
        Level level = boat.level();
        if (level == null) return;

        double bx = boat.getX();
        double bz = boat.getZ();
        // Skip if not over water. Saves cost; also prevents bobbing land-locked boats.
        float depth = WakesDepth.factorAt(level, bx, bz);
        if (depth <= 0.01f) return;

        double time = level.getGameTime() + partialTick;
        float weather = Math.min(1.5f, level.getRainLevel(partialTick) + level.getThunderLevel(partialTick) * 0.5f);
        double heave = WakesWaveFunction.waveHeight(bx, bz, time, weather, depth);

        // Cheap pitch/roll from wave gradient — gives a sense of the boat tilting
        // along the slope of the swell underneath it.
        final double e = 0.6;
        double hXp = WakesWaveFunction.waveHeight(bx + e, bz, time, weather, depth);
        double hXm = WakesWaveFunction.waveHeight(bx - e, bz, time, weather, depth);
        double hZp = WakesWaveFunction.waveHeight(bx, bz + e, time, weather, depth);
        double hZm = WakesWaveFunction.waveHeight(bx, bz - e, time, weather, depth);
        float pitch = (float) ((hZm - hZp) / (2.0 * e)) * 0.6f;   // tilt fore/aft
        float roll  = (float) ((hXp - hXm) / (2.0 * e)) * 0.6f;   // tilt port/starboard

        stack.translate(0.0, heave, 0.0);
        if (pitch != 0f) stack.mulPose(new Quaternionf(new AxisAngle4f(pitch, new Vector3f(1, 0, 0))));
        if (roll  != 0f) stack.mulPose(new Quaternionf(new AxisAngle4f(roll,  new Vector3f(0, 0, 1))));
    }
}
